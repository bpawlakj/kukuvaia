package tui

import (
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/charmbracelet/bubbles/spinner"
	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

// message is a chat message (user or assistant) with rendered blocks.
type message struct {
	role   string // "user" or "assistant"
	blocks []api.OutputBlock
	text   string // raw text for user messages
}

// elapsedTickMsg triggers a View refresh to update the elapsed timer.
type elapsedTickMsg struct{}

// planToolbar tracks the planning mode action bar state.
type planToolbar struct {
	active   bool   // true when showing toolbar
	phase    string // "discovery" or "approval"
	selected int    // 0 = primary action, 1 = secondary action
	editing  bool   // true when user is typing in input
}

// Model is the root Bubbletea model.
type Model struct {
	input      textinput.Model
	viewport   viewport.Model
	spinner    spinner.Model
	picker     SessionPicker
	client     *api.Client
	session    string
	messages   []message
	waiting    bool
	waitStart  time.Time
	showPicker bool
	planning   planToolbar
	width      int
	height     int
	ready      bool
}

// All available slash commands for autocomplete.
var slashCommands = []string{
	"/plan ", "/help", "/new", "/sessions", "/session", "/rename ",
	"/clear", "/quit", "/exit",
	"/model ", "/login ",
}

// NewModel creates the initial TUI model.
func NewModel(client *api.Client, sessionID string) Model {
	ti := textinput.New()
	ti.Placeholder = "Type a message... (/ for commands)"
	ti.CharLimit = 4096
	ti.ShowSuggestions = true
	ti.Focus()

	s := spinner.New()
	s.Spinner = spinner.MiniDot
	s.Style = SpinnerStyle

	return Model{
		input:   ti,
		spinner: s,
		client:  client,
		session: sessionID,
	}
}

func (m Model) Init() tea.Cmd {
	return tea.Batch(textinput.Blink, m.spinner.Tick)
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmds []tea.Cmd

	// Session picker mode
	if m.showPicker {
		if ws, ok := msg.(tea.WindowSizeMsg); ok {
			m.width = ws.Width
			m.height = ws.Height
		}
		picker, cmd := m.picker.Update(msg)
		m.picker = picker
		cmds = append(cmds, cmd)

		switch msg := msg.(type) {
		case sessionSelectedMsg:
			m.session = msg.session.ID
			if m.session == "" {
				m.session = msg.session.SessionID
			}
			m.showPicker = false
			m.messages = nil
			sessionName := msg.session.DisplayName()
			m.messages = append(m.messages, message{
				role:   "assistant",
				blocks: []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf("Switched to session: **%s**", sessionName)}},
			})
			if m.ready {
				m.viewport.SetContent(m.renderMessages())
			}
			return m, nil
		case sessionPickerDoneMsg:
			m.showPicker = false
			return m, nil
		case tea.KeyMsg:
			if msg.String() == "ctrl+c" {
				return m, tea.Quit
			}
		}
		return m, tea.Batch(cmds...)
	}

	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		log.Printf("[UPDATE] WindowSizeMsg: %dx%d", msg.Width, msg.Height)
		m.width = msg.Width
		m.height = msg.Height
		inputHeight := 4 // bordered input box (3 lines) + gap
		// Constrain textinput width so the content scrolls horizontally inside
		// the box instead of wrapping and growing the box vertically.
		// InputBoxFocusedStyle: Border(2) + Padding(0,1)(2) → inner = width - 4.
		// View() then subtracts 2 for outer margin, so input width = msg.Width - 6.
		if msg.Width > 10 {
			m.input.Width = msg.Width - 6
		}
		if !m.ready {
			m.viewport = viewport.New(msg.Width, msg.Height-inputHeight)
			m.viewport.SetContent(m.renderMessages())
			m.ready = true
		} else {
			m.viewport.Width = msg.Width
			m.viewport.Height = msg.Height - inputHeight
		}

	case tea.KeyMsg:
		// Planning toolbar key handling (when active and not editing)
		if m.planning.active && !m.planning.editing && !m.waiting {
			key := msg.String()
			switch key {
			case "tab", "shift+tab", "left", "right":
				m.planning.selected = 1 - m.planning.selected
				return m, nil
			case "enter":
				return m, m.handlePlanToolbarAction()
			case "ctrl+c":
				return m, tea.Quit
			case "pgup", "pgdown", "home", "end":
				var cmd tea.Cmd
				m.viewport, cmd = m.viewport.Update(msg)
				return m, cmd
			}
			// Don't swallow other keys — let them fall through
		}

		switch msg.String() {
		case "ctrl+c":
			return m, tea.Quit
		case "ctrl+l":
			m.messages = nil
			m.viewport.SetContent("")
			return m, nil
		case "pgup", "pgdown", "home", "end":
			// Forward page navigation directly to viewport (skip input)
			var cmd tea.Cmd
			m.viewport, cmd = m.viewport.Update(msg)
			return m, cmd
		case "enter":
			if m.waiting {
				log.Printf("[UPDATE] enter pressed but waiting=true, ignoring")
				return m, nil
			}
			text := strings.TrimSpace(m.input.Value())
			if text == "" {
				return m, nil
			}
			m.input.Reset()
			m.messages = append(m.messages, message{role: "user", text: text})
			log.Printf("[UPDATE] enter: text=%q, isCommand=%v", text, strings.HasPrefix(text, "/"))

			// Route: slash commands vs free text chat
			if strings.HasPrefix(text, "/") {
				if handled, cmd := m.handleCommand(text); handled {
					log.Printf("[UPDATE] command handled, waiting=%v", m.waiting)
					m.viewport.SetContent(m.renderMessages())
					m.viewport.GotoBottom()
					return m, cmd
				}
			}

			// If in planning editing mode, send feedback then re-show toolbar
			if m.planning.editing {
				m.planning.editing = false
				m.planning.selected = 0
			}

			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] sending chat, waiting=true")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return m, tea.Batch(m.sendChat(text), tickElapsed())
		}

	case chatResultMsg:
		m.waiting = false
		log.Printf("[UPDATE] chatResultMsg: err=%v, blocks=%d", msg.err, len(msg.blocks))
		if msg.err != nil {
			errStyle := "error"
			m.messages = append(m.messages, message{
				role:   "assistant",
				blocks: []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf("Error: %v", msg.err), Style: &errStyle}},
			})
		} else if len(msg.blocks) > 0 {
			for i, b := range msg.blocks {
				log.Printf("[UPDATE]   block[%d]: type=%T", i, b)
			}
			m.messages = append(m.messages, message{role: "assistant", blocks: msg.blocks})
		} else {
			log.Printf("[UPDATE]   no blocks and no error — empty response")
		}

		// Planning toolbar transitions:
		// After "gotowe" response → show approval toolbar
		// After "Add feedback" response in discovery → re-show discovery toolbar
		if m.planning.phase == "drafting" {
			m.planning = planToolbar{active: true, phase: "approval", selected: 0}
			log.Printf("[UPDATE] planning toolbar: → approval")
		} else if m.planning.phase == "discovery" && !m.planning.active {
			// Was in discovery, toolbar was hidden during feedback send — re-show
			m.planning.active = true
			m.planning.editing = false
			m.planning.selected = 0
		} else if m.planning.phase == "approval" && m.planning.editing {
			// Was editing changes in approval — after send, show approval toolbar again
			m.planning.active = true
			m.planning.editing = false
			m.planning.selected = 0
		}

		contentLen := len(m.renderMessages())
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		log.Printf("[UPDATE] viewport updated: contentLen=%d, totalMessages=%d", contentLen, len(m.messages))
		cmds = append(cmds, scheduleRepaint())

	case commandResultMsg:
		m.waiting = false
		log.Printf("[UPDATE] commandResultMsg: err=%v, blocks=%d", msg.err, len(msg.blocks))
		if msg.err != nil {
			errStyle := "error"
			m.messages = append(m.messages, message{
				role:   "assistant",
				blocks: []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf("Error: %v", msg.err), Style: &errStyle}},
			})
		} else if len(msg.blocks) > 0 {
			m.messages = append(m.messages, message{role: "assistant", blocks: msg.blocks})
		}
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		cmds = append(cmds, scheduleRepaint())

	case spinner.TickMsg:
		if m.waiting {
			var cmd tea.Cmd
			m.spinner, cmd = m.spinner.Update(msg)
			cmds = append(cmds, cmd)
		}

	case elapsedTickMsg:
		if m.waiting {
			cmds = append(cmds, tickElapsed())
		}
	}

	// Update sub-models — only pass safe messages to textinput
	var cmd tea.Cmd
	if isInputSafe(msg) {
		m.input, cmd = m.input.Update(msg)
		cmds = append(cmds, cmd)
	}
	m.viewport, cmd = m.viewport.Update(msg)
	cmds = append(cmds, cmd)

	// Update autocomplete suggestions based on current input
	m.updateSuggestions()

	return m, tea.Batch(cmds...)
}

// updateSuggestions sets autocomplete suggestions based on current input.
func (m *Model) updateSuggestions() {
	val := m.input.Value()
	if strings.HasPrefix(val, "/") {
		// Filter commands matching the prefix
		var matches []string
		for _, cmd := range slashCommands {
			if strings.HasPrefix(cmd, val) && cmd != val {
				matches = append(matches, cmd)
			}
		}
		m.input.SetSuggestions(matches)
	} else {
		m.input.SetSuggestions(nil)
	}
}

func (m Model) View() string {
	if m.showPicker {
		return m.picker.View(m.width, m.height)
	}
	if !m.ready {
		return "Initializing..."
	}

	w := m.width
	if w < 20 {
		w = 80
	}

	// Input box — bordered like Claude Code prompt area
	inputBoxWidth := w - 2
	if inputBoxWidth < 10 {
		inputBoxWidth = 10
	}
	inputBox := InputBoxFocusedStyle.Width(inputBoxWidth).Render(m.input.View())

	// Spinner line with elapsed timer when waiting
	if m.waiting {
		elapsed := time.Since(m.waitStart).Truncate(time.Second)
		spinnerLine := "  " + m.spinner.View() + ThinkingStyle.Render(fmt.Sprintf(" Thinking... (%s)", formatElapsed(elapsed)))
		return m.viewport.View() + "\n" + spinnerLine + "\n" + inputBox
	}

	// Planning toolbar — shown when active and not editing.
	// Extra newline above and below gives breathing room so the toolbar
	// does not sit flush against chat content or the terminal edge.
	if m.planning.active && !m.planning.editing {
		toolbar := renderPlanToolbar(m.planning.phase, m.planning.selected, inputBoxWidth)
		return m.viewport.View() + "\n\n" + toolbar + "\n"
	}

	return m.viewport.View() + "\n" + inputBox
}

// chatResultMsg carries all blocks from a completed chat response.
type chatResultMsg struct {
	blocks []api.OutputBlock
	err    error
}

// sendChat creates a tea.Cmd that collects the full chat response.
func (m Model) sendChat(text string) tea.Cmd {
	return func() tea.Msg {
		blocks, errs := m.client.Chat(m.session, text)

		var received []api.OutputBlock
		var lastErr error

		// Drain both channels
		for blocks != nil || errs != nil {
			select {
			case block, ok := <-blocks:
				if !ok {
					blocks = nil
					continue
				}
				received = append(received, block)
			case err, ok := <-errs:
				if !ok {
					errs = nil
					continue
				}
				if err != nil {
					lastErr = err
				}
			}
		}

		return chatResultMsg{blocks: received, err: lastErr}
	}
}

// renderMessages builds the viewport content from all messages.
func (m Model) renderMessages() string {
	if len(m.messages) == 0 {
		return MutedStyle.Render("  Start a conversation...")
	}

	width := m.width
	if width < 20 {
		width = 80
	}

	var parts []string
	for _, msg := range m.messages {
		switch msg.role {
		case "user":
			parts = append(parts, renderUserMessage(msg.text, width))
		case "assistant":
			for _, block := range msg.blocks {
				rendered := RenderBlock(block, width-4)
				parts = append(parts, "  "+rendered)
			}
		}
	}

	return strings.Join(parts, "\n")
}

// renderUserMessage styles user input like Claude Code — prefix + styled text.
func renderUserMessage(text string, width int) string {
	prefix := UserMsgPrefixStyle.Render("❯ ")
	if strings.HasPrefix(text, "/") {
		// Slash command — purple badge
		cmdText := CommandBadgeStyle.Render(text)
		return prefix + cmdText
	}
	// Regular message — green bold
	msgText := UserMsgBoxStyle.Width(width - 4).Render(text)
	return prefix + msgText
}

// handlePlanToolbarAction executes the selected toolbar action.
func (m *Model) handlePlanToolbarAction() tea.Cmd {
	if m.planning.phase == "discovery" {
		if m.planning.selected == 0 {
			// "Create plan" → send "gotowe", mark as drafting for response detection
			m.planning.active = false
			m.planning.phase = "drafting"
			m.messages = append(m.messages, message{role: "user", text: "gotowe"})
			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] planning toolbar: Create plan → sending 'gotowe'")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return tea.Batch(m.sendChat("gotowe"), tickElapsed())
		}
		// "Add feedback" → switch to text input
		m.planning.editing = true
		m.input.Focus()
		return nil
	}

	if m.planning.phase == "approval" {
		if m.planning.selected == 0 {
			// "Approve plan" → send "tak", exit planning mode
			m.planning = planToolbar{}
			m.messages = append(m.messages, message{role: "user", text: "tak"})
			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] planning toolbar: Approve plan → sending 'tak'")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return tea.Batch(m.sendChat("tak"), tickElapsed())
		}
		// "Request changes" → switch to text input
		m.planning.editing = true
		m.planning.phase = "drafting" // will transition back to approval after response
		m.input.Focus()
		return nil
	}

	return nil
}

// renderPlanToolbar renders the planning mode action bar on a single line.
func renderPlanToolbar(phase string, selected int, width int) string {
	var primaryLabel, secondaryLabel string
	if phase == "approval" {
		primaryLabel = "Approve plan"
		secondaryLabel = "Request changes"
	} else {
		primaryLabel = "Create plan"
		secondaryLabel = "Add feedback"
	}

	active := lipgloss.NewStyle().Bold(true).
		Foreground(lipgloss.Color("#000000")).Background(ColorPrimary)
	inactive := lipgloss.NewStyle().Foreground(ColorMuted)

	var primary, secondary string
	if selected == 0 {
		primary = active.Render(" " + primaryLabel + " ")
		secondary = inactive.Render("[" + secondaryLabel + "]")
	} else {
		primary = inactive.Render("[" + primaryLabel + "]")
		secondary = active.Render(" " + secondaryLabel + " ")
	}

	hint := MutedStyle.Render("tab/← → · enter")
	return "  " + primary + "  " + secondary + "  " + hint
}

// isInputSafe returns true if the message should be forwarded to textinput.
// Filters out terminal escape sequence responses (OSC color queries, etc.)
// that would otherwise appear as typed text in the input field.
func isInputSafe(msg tea.Msg) bool {
	switch msg.(type) {
	case tea.KeyMsg, tea.WindowSizeMsg:
		return true
	default:
		return false
	}
}

// repaintMsg forces a View refresh after async responses.
type repaintMsg struct{}

func scheduleRepaint() tea.Cmd {
	return tea.Tick(50*time.Millisecond, func(time.Time) tea.Msg {
		return repaintMsg{}
	})
}

// tickElapsed returns a Cmd that fires an elapsedTickMsg every second.
func tickElapsed() tea.Cmd {
	return tea.Tick(time.Second, func(time.Time) tea.Msg {
		return elapsedTickMsg{}
	})
}

// formatElapsed formats a duration as "Xs", "Xm Ys", or "Xm" for display.
func formatElapsed(d time.Duration) string {
	s := int(d.Seconds())
	if s < 60 {
		return fmt.Sprintf("%ds", s)
	}
	m := s / 60
	rem := s % 60
	if rem == 0 {
		return fmt.Sprintf("%dm", m)
	}
	return fmt.Sprintf("%dm %ds", m, rem)
}

// Run starts the Bubbletea program with debug logging to ~/.kukuvaia/cli.log.
func Run(client *api.Client, sessionID string) error {
	// Set up file logging (bubbletea uses alt screen, so stdout is unavailable)
	if f, err := tea.LogToFile("cli-debug.log", "kukuvaia"); err == nil {
		defer f.Close()
		log.Printf("[INIT] session=%s, server=%s", sessionID, client.BaseURL)
	}

	m := NewModel(client, sessionID)
	p := tea.NewProgram(m, tea.WithAltScreen())
	_, err := p.Run()
	return err
}
