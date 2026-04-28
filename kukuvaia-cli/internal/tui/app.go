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
	"github.com/kukuvaia/kukuvaia-cli/internal/tui/activity"
)

// message is a chat message (user or assistant) with rendered blocks.
// For assistant messages, activitySnapshot holds the frozen render of the
// activity tree at the moment this turn completed. Rendered inline above the
// blocks in the chat history, so the user can trace what tools ran for each
// reply (similar to Claude Code's inline tool indicators).
type message struct {
	role             string // "user" or "assistant"
	blocks           []api.OutputBlock
	text             string // raw text for user messages
	activitySnapshot string // rendered activity tree at turn completion
}

// elapsedTickMsg triggers a View refresh to update the elapsed timer.
type elapsedTickMsg struct{}

// planToolbar tracks the planning mode action bar state.
// Number of actions depends on phase:
//   - discovery: 2 (Create plan, Add feedback)
//   - approval:  3 (Approve plan, Request changes, Mark done)
//   - executing: 2 (Mark done, Add feedback)
type planToolbar struct {
	active   bool   // true when showing toolbar
	phase    string // "discovery" | "approval" | "executing"
	selected int    // 0..N-1 — index into the phase's action list
	editing  bool   // true when user is typing in input
}

// planToolbarActionCount returns how many actions the current phase exposes.
func planToolbarActionCount(phase string) int {
	switch phase {
	case "approval":
		return 3
	case "discovery", "executing":
		return 2
	}
	return 2
}

// Model is the root Bubbletea model.
type Model struct {
	input           textinput.Model
	viewport        viewport.Model
	spinner         spinner.Model
	picker          SessionPicker
	planPicker      PlanPicker
	activity        activity.Model
	client          *api.Client
	session         string
	messages        []message
	waiting         bool
	waitStart       time.Time
	showPicker      bool
	showPlanPicker  bool
	// When the plan picker closes with a "combine" action, we stash the ids
	// here and ask the user for a new task description before firing the
	// server call.
	pendingCombineIDs []string
	planning        planToolbar
	width           int
	height          int
	ready           bool
}

// All available slash commands for autocomplete.
var slashCommands = []string{
	"/plan ", "/plans", "/help", "/new", "/sessions", "/session", "/rename ",
	"/clear", "/quit", "/exit",
	"/model ", "/login ",
	"/escalate",
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
		input:    ti,
		spinner:  s,
		client:   client,
		session:  sessionID,
		activity: activity.New().WithStyles(activity.NewStyles(ColorPrimary, ColorMuted, ColorError)),
	}
}

func (m Model) Init() tea.Cmd {
	return tea.Batch(textinput.Blink, m.spinner.Tick)
}

func (m Model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmds []tea.Cmd

	// Plan picker mode — takes priority over the main chat flow.
	if m.showPlanPicker {
		if ws, ok := msg.(tea.WindowSizeMsg); ok {
			m.width = ws.Width
			m.height = ws.Height
		}
		picker, cmd := m.planPicker.Update(msg)
		m.planPicker = picker
		cmds = append(cmds, cmd)

		switch mm := msg.(type) {
		case planPickerActionMsg:
			m.showPlanPicker = false
			return m, m.dispatchPlanAction(mm)
		case planPickerCancelMsg:
			m.showPlanPicker = false
			return m, nil
		case planPickerFilterMsg:
			return m, m.fetchPlansForPicker(mm.filter)
		case tea.KeyMsg:
			if mm.String() == "ctrl+c" {
				return m, tea.Quit
			}
			if mm.String() == "ctrl+p" {
				// Second ctrl+p while picker is open — close it.
				m.showPlanPicker = false
				return m, nil
			}
		}
		return m, tea.Batch(cmds...)
	}

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
		// Constrain textinput width so the content scrolls horizontally inside
		// the box instead of wrapping and growing the box vertically.
		// InputBoxFocusedStyle: Border(2) + Padding(0,1)(2) → inner = width - 4.
		// View() then subtracts 2 for outer margin, so input width = msg.Width - 6.
		if msg.Width > 10 {
			m.input.Width = msg.Width - 6
		}
		// Use the same height derivation View() uses so the viewport model's
		// YOffset math stays in sync with the actual render.
		vpHeight := m.effectiveViewportHeight()
		if !m.ready {
			m.viewport = viewport.New(msg.Width, vpHeight)
			m.viewport.SetContent(m.renderMessages())
			m.ready = true
		} else {
			m.viewport.Width = msg.Width
			m.viewport.Height = vpHeight
		}

	case tea.MouseMsg:
		// Forward mouse wheel (and other mouse) events to the viewport so the
		// chat history scrolls with the trackpad / wheel. Bubbles' viewport
		// recognises MouseWheelUp / MouseWheelDown natively.
		if m.ready {
			var cmd tea.Cmd
			m.viewport, cmd = m.viewport.Update(msg)
			return m, cmd
		}
		return m, nil

	case tea.KeyMsg:
		// Planning toolbar key handling (when active and not editing)
		if m.planning.active && !m.planning.editing && !m.waiting {
			key := msg.String()
			switch key {
			case "tab", "right":
				count := planToolbarActionCount(m.planning.phase)
				m.planning.selected = (m.planning.selected + 1) % count
				return m, nil
			case "shift+tab", "left":
				count := planToolbarActionCount(m.planning.phase)
				m.planning.selected = (m.planning.selected - 1 + count) % count
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
		case "ctrl+o":
			// Toggle activity tracker expanded view — only meaningful while active.
			if !m.activity.IsIdle() {
				m.activity = m.activity.Toggle()
				return m, nil
			}
			return m, nil
		case "ctrl+p":
			// 3-state toggle:
			//   in planning mode  → suspend locally (clear yellow border, server
			//                       session untouched so the plan can still be
			//                       resumed via /plan resume <id>)
			//   picker is already open (shouldn't reach here because the outer
			//                       showPlanPicker branch catches it) → no-op
			//   neither            → open the picker
			// Destructive cancel stays on the explicit `/plan cancel` command.
			if m.isPlanning() {
				m.planning = planToolbar{}
				m.messages = append(m.messages, message{
					role: "assistant",
					blocks: []api.OutputBlock{api.TextBlock{Content:
						"Planning suspended locally. The draft is still in the server — " +
							"use `/plans` or `ctrl+p` to browse and `/plan resume <id>` to return, " +
							"or `/plan cancel` to abandon."}},
				})
				m.viewport.SetContent(m.renderMessages())
				m.viewport.GotoBottom()
				return m, nil
			}
			m.planPicker = NewPlanPicker()
			m.planPicker.width = m.width
			m.planPicker.height = m.height
			m.showPlanPicker = true
			return m, m.fetchPlansForPicker("")
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

			// If the user has combine-ids staged from the picker, this next
			// message is the task description → send /plan combine <ids> <text>.
			if len(m.pendingCombineIDs) > 0 {
				idsCSV := strings.Join(m.pendingCombineIDs, ",")
				args := idsCSV + " " + text
				m.pendingCombineIDs = nil
				m.messages = append(m.messages, message{role: "user", text: "/plan combine " + args})
				m.waiting = true
				m.waitStart = time.Now()
				m.viewport.SetContent(m.renderMessages())
				m.viewport.GotoBottom()
				return m, tea.Batch(m.sendPlan("combine "+args), tickElapsed())
			}

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
			m.viewport.Height = m.effectiveViewportHeight()
			m.viewport.GotoBottom()
			return m, tea.Batch(m.sendChat(text), tickElapsed())
		}

	case chatStreamMsg:
		if msg.err != nil {
			// Stream error — fall through so chatResultMsg handler finalises.
			return m, func() tea.Msg { return chatResultMsg{err: msg.err} }
		}
		if se, ok := msg.block.(api.SpanEventBlock); ok {
			wasIdle := m.activity.IsIdle()
			m.activity = dispatchSpanEvent(m.activity, se)
			// Start the activity ticker the first time a span arrives.
			if wasIdle && !m.activity.IsIdle() {
				cmds = append(cmds, scheduleActivityTick())
			}
		}
		if msg.next != nil {
			cmds = append(cmds, msg.next)
		}
		return m, tea.Batch(cmds...)

	case activityTickMsg:
		// Pulse spinner while any span is in flight.
		if m.activity.InFlightCount() > 0 {
			m.activity = m.activity.Update(activity.TickMsg(time.Time(msg)))
			return m, scheduleActivityTick()
		}
		return m, nil

	case chatResultMsg:
		m.waiting = false
		log.Printf("[UPDATE] chatResultMsg: err=%v, blocks=%d", msg.err, len(msg.blocks))

		// Freeze the current activity tree into the message that's about to be
		// appended. The live tracker resets so it is ready for the next turn.
		snapshot := ""
		if !m.activity.IsIdle() {
			snapshot = m.activity.View()
			m.activity = m.activity.Update(activity.SseDisconnectedMsg{}) // hard reset
		}

		if msg.err != nil {
			errStyle := "error"
			m.messages = append(m.messages, message{
				role:             "assistant",
				blocks:           []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf("Error: %v", msg.err), Style: &errStyle}},
				activitySnapshot: snapshot,
			})
		} else if len(msg.blocks) > 0 {
			for i, b := range msg.blocks {
				log.Printf("[UPDATE]   block[%d]: type=%T", i, b)
			}
			// Agent-triggered PlanListBlock (e.g. natural-language "show my plans")
			// auto-opens the picker instead of flattening into chat.
			if pb := findPlanListBlock(msg.blocks); pb != nil {
				m.planPicker = NewPlanPicker()
				m.planPicker.width = m.width
				m.planPicker.height = m.height
				m.planPicker.loading = false
				m.planPicker.plans = pb.Plans
				m.planPicker.filter = pb.StatusFilter
				m.showPlanPicker = true
			}
			// Strip the phase-only MetadataBlock out of chat history — it is a
			// control signal consumed below by syncPlanningFromServer, not user-
			// facing content. Other metadata blocks still render normally.
			visibleBlocks := stripPlanningPhaseMetadata(msg.blocks)
			if len(visibleBlocks) > 0 {
				m.messages = append(m.messages, message{
					role:             "assistant",
					blocks:           visibleBlocks,
					activitySnapshot: snapshot,
				})
			} else if snapshot != "" {
				m.messages = append(m.messages, message{
					role:             "assistant",
					activitySnapshot: snapshot,
				})
			}
		} else if snapshot != "" {
			// Pure tool-only turn (no assistant text) — still show the activity.
			m.messages = append(m.messages, message{
				role:             "assistant",
				activitySnapshot: snapshot,
			})
		} else {
			log.Printf("[UPDATE]   no blocks and no error — empty response")
		}

		// Planning toolbar sync — authoritative signal from the server.
		// AgentService emits a MetadataBlock with kukuvaia.planning.phase at
		// the end of every chat turn while a session is in planning mode.
		// The CLI rebuilds the toolbar from this single source of truth,
		// replacing the previous brittle local phase tracking that missed
		// LLM-initiated transitions (DISCOVERY → DRAFTING → APPROVAL in one turn).
		if phase := extractPlanningPhase(msg.blocks); phase != "" {
			m.syncPlanningFromServer(phase)
		} else if m.planning.active || m.planning.phase != "" {
			// Server says "no longer in planning mode" (no metadata block) —
			// drop the toolbar so the user gets a clean prompt back.
			log.Printf("[UPDATE] planning mode exited (no phase metadata) — clearing toolbar")
			m.planning = planToolbar{}
		}

		contentLen := len(m.renderMessages())
		m.viewport.SetContent(m.renderMessages())
		m.viewport.Height = m.effectiveViewportHeight()
		m.viewport.GotoBottom()
		log.Printf("[UPDATE] viewport updated: contentLen=%d, totalMessages=%d", contentLen, len(m.messages))
		cmds = append(cmds, scheduleRepaint())

	case commandResultMsg:
		m.waiting = false
		log.Printf("[UPDATE] commandResultMsg: err=%v, blocks=%d", msg.err, len(msg.blocks))
		for i, b := range msg.blocks {
			log.Printf("[UPDATE]   cmd block[%d]: type=%T", i, b)
		}
		if msg.err != nil {
			errStyle := "error"
			m.messages = append(m.messages, message{
				role:   "assistant",
				blocks: []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf("Error: %v", msg.err), Style: &errStyle}},
			})
		} else if len(msg.blocks) > 0 {
			// Auto-open plan picker on PlanListBlock — skip rendering the raw
			// list into the chat viewport, push it straight into the picker.
			if pb := findPlanListBlock(msg.blocks); pb != nil {
				m.planPicker = NewPlanPicker()
				m.planPicker.width = m.width
				m.planPicker.height = m.height
				m.planPicker.loading = false
				m.planPicker.plans = pb.Plans
				m.planPicker.filter = pb.StatusFilter
				m.showPlanPicker = true
				return m, nil
			}
			// Sync planning phase if the server told us via a TextBlock like
			// "Resumed plan '...' in phase DRAFTING." Keeps the yellow border
			// + badge accurate after /plan resume.
			if phase := extractResumedPhase(msg.blocks); phase != "" {
				m.planning = planToolbar{active: false, phase: phase}
				log.Printf("[UPDATE] planning phase synced from resume: %s", phase)
			}
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

// findPlanListBlock returns the first PlanListBlock in the slice or nil if absent.
func findPlanListBlock(blocks []api.OutputBlock) *api.PlanListBlock {
	for _, b := range blocks {
		if pb, ok := b.(api.PlanListBlock); ok {
			return &pb
		}
	}
	return nil
}

// planningPhaseKey mirrors AgentService.PLANNING_PHASE_KEY on the server.
const planningPhaseKey = "kukuvaia.planning.phase"

// extractPlanningPhase scans blocks for the server-emitted MetadataBlock
// carrying {planningPhaseKey: "<phase>"} and returns the lowercase phase
// name. Empty string means no metadata block was present (session is not
// in planning mode).
func extractPlanningPhase(blocks []api.OutputBlock) string {
	for _, b := range blocks {
		mb, ok := b.(api.MetadataBlock)
		if !ok {
			continue
		}
		if v, present := mb.Metadata[planningPhaseKey]; present {
			if s, isStr := v.(string); isStr {
				return strings.ToLower(strings.TrimSpace(s))
			}
		}
	}
	return ""
}

// stripPlanningPhaseMetadata returns a new slice with MetadataBlocks that
// only carry the planning-phase control key removed — those are consumed
// by syncPlanningFromServer and should NOT appear in the chat viewport.
// Other MetadataBlocks (with additional keys) pass through untouched.
func stripPlanningPhaseMetadata(blocks []api.OutputBlock) []api.OutputBlock {
	out := make([]api.OutputBlock, 0, len(blocks))
	for _, b := range blocks {
		if mb, ok := b.(api.MetadataBlock); ok {
			if len(mb.Metadata) == 1 {
				if _, phaseOnly := mb.Metadata[planningPhaseKey]; phaseOnly {
					continue
				}
			}
		}
		out = append(out, b)
	}
	return out
}

// syncPlanningFromServer rebuilds the local plan-toolbar state from the
// authoritative phase string emitted by the server. Each phase has its own
// toolbar preset (or no toolbar at all) so the UI reflects exactly what the
// server state machine is doing.
func (m *Model) syncPlanningFromServer(phase string) {
	switch phase {
	case "discovery":
		m.planning = planToolbar{active: true, phase: "discovery", selected: 0}
	case "drafting":
		// Transient — server usually advances DRAFTING → APPROVAL within a
		// single createPlan tool-call; we keep the state but hide the toolbar.
		m.planning = planToolbar{active: false, phase: "drafting"}
	case "approval":
		m.planning = planToolbar{active: true, phase: "approval", selected: 0}
	case "executing":
		// Toolbar offers Mark done + Add feedback — user can either close the
		// plan or send a free-text progress update (which the LLM may act on
		// via completeStep).
		m.planning = planToolbar{active: true, phase: "executing", selected: 0}
	default:
		log.Printf("[UPDATE] unknown planning phase from server: %q — leaving toolbar unchanged", phase)
		return
	}
	log.Printf("[UPDATE] planning toolbar synced from server: phase=%s active=%t", phase, m.planning.active)
}

// extractResumedPhase scans TextBlock content for the server's phase-reporting
// pattern ("... in phase DRAFTING.") emitted by CommandRouter.handlePlanResume.
// Returns the lowercase phase or "" if nothing matched.
func extractResumedPhase(blocks []api.OutputBlock) string {
	for _, b := range blocks {
		tb, ok := b.(api.TextBlock)
		if !ok {
			continue
		}
		idx := strings.Index(tb.Content, "in phase ")
		if idx < 0 {
			continue
		}
		rest := tb.Content[idx+len("in phase "):]
		end := strings.IndexAny(rest, ".\n ")
		if end < 0 {
			end = len(rest)
		}
		phase := strings.ToLower(strings.TrimSpace(rest[:end]))
		switch phase {
		case "discovery", "drafting", "approval":
			return phase
		}
	}
	return ""
}

// fetchPlansForPicker loads plans with the given status filter into the plan picker.
func (m Model) fetchPlansForPicker(filter string) tea.Cmd {
	return func() tea.Msg {
		plans, err := m.client.ListPlans(filter)
		return plansLoadedMsg{plans: plans, err: err}
	}
}

// dispatchPlanAction converts a picker action into the right server call.
// Resume and abandon map to slash commands (`/plan resume <id>` / `/plan cancel`
// for abandon). Combine stashes ids and prefills the input with a prompt for
// the user to describe the new combined task.
func (m *Model) dispatchPlanAction(action planPickerActionMsg) tea.Cmd {
	switch action.kind {
	case "resume":
		m.waiting = true
		m.waitStart = time.Now()
		// Optimistically mark planning active locally so the yellow border +
		// badge appear immediately. Server's resume rehydrates the session
		// in-memory; without this flip the CLI stays green until the next
		// phase transition. Phase parsing from the text response happens in
		// commandResultMsg below.
		m.planning = planToolbar{active: false, phase: "drafting"}
		m.messages = append(m.messages, message{role: "user", text: "/plan resume " + action.planID})
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		return tea.Batch(m.sendPlan("resume "+action.planID), tickElapsed())
	case "abandon":
		m.waiting = true
		m.waitStart = time.Now()
		m.messages = append(m.messages, message{role: "user", text: "/plan abandon " + action.planID})
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		return tea.Batch(m.sendPlan("abandon "+action.planID), tickElapsed())
	case "new":
		// Derive-from: prefill input with a hint so the user can type the new task.
		m.input.SetValue("/plan ")
		m.input.CursorEnd()
		m.messages = append(m.messages, message{
			role: "assistant",
			blocks: []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf(
				"Creating new plan derived from `%s`. Type the task description and press enter.",
				action.planID[:8])}},
		})
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		// Parent id passed as a prefix the server will strip — encoded to avoid
		// colliding with user text. Phase D's combine_plans tool is the cleaner
		// long-term path; this keybind is the picker's convenience escape hatch.
		m.pendingCombineIDs = []string{action.planID}
		return nil
	case "combine":
		m.pendingCombineIDs = action.selectedIDs
		m.input.SetValue("")
		m.input.CursorEnd()
		ids := strings.Join(action.selectedIDs, ", ")
		m.messages = append(m.messages, message{
			role: "assistant",
			blocks: []api.OutputBlock{api.TextBlock{Content: fmt.Sprintf(
				"Combining %d plans (%s). Type a description for the combined plan and press enter.",
				len(action.selectedIDs), ids)}},
		})
		m.viewport.SetContent(m.renderMessages())
		m.viewport.GotoBottom()
		return nil
	}
	return nil
}

// isPlanning reports whether the session is currently inside a /plan flow.
// Source of truth: phase transitions in the Update cycle. `active` is only
// true while the toolbar shows; `phase` persists across editing/feedback modes.
func (m Model) isPlanning() bool {
	return m.planning.phase != "" || m.planning.active
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
	if m.showPlanPicker {
		return m.planPicker.View(m.width, m.height)
	}
	if m.showPicker {
		return m.picker.View(m.width, m.height)
	}
	if !m.ready {
		return "Initializing..."
	}

	bottom := m.buildBottom()
	vp := m.viewport
	vp.Height = viewportHeightFor(m.height, bottom)
	// Do NOT auto-scroll here — user's pgup/arrow navigation must survive
	// re-renders. Scroll-to-bottom happens explicitly in Update() on new
	// messages (see enter handler and chatResultMsg).
	return vp.View() + bottom
}

// effectiveViewportHeight returns the viewport height that View() will use
// given the current model state. Update handlers call this before GotoBottom
// so the scroll anchor matches the actual render size. Computed from the
// exact same `bottom` string View() renders, to guarantee consistency — any
// formula drift here caused visible jumps while typing and clipped last lines
// after GotoBottom.
func (m Model) effectiveViewportHeight() int {
	return viewportHeightFor(m.height, m.buildBottom())
}

// viewportHeightFor returns the number of rows left for the scrollable chat
// viewport given the total terminal height and the rendered bottom string.
// Rationale for the formula: total rendered rows = vp.View() rows + '\n' count
// of bottom (vp.View() has no trailing '\n', so each '\n' in bottom adds exactly
// one rendered row). To fill the terminal we need vp.Height = terminalHeight -
// newlineCount(bottom).
func viewportHeightFor(terminalHeight int, bottom string) int {
	h := terminalHeight - strings.Count(bottom, "\n")
	if h < 1 {
		return 1
	}
	return h
}

// buildBottom constructs the string rendered below the chat viewport: activity
// tree + spinner when waiting, planning toolbar when applicable, planning
// badge and input box. Kept as a single helper so View() and
// effectiveViewportHeight() never disagree on its shape (the earlier split
// between the two produced a 1-row mismatch and glitchy scrolling while
// typing).
func (m Model) buildBottom() string {
	w := m.width
	if w < 20 {
		w = 80
	}
	inputBoxWidth := w - 2
	if inputBoxWidth < 10 {
		inputBoxWidth = 10
	}
	inputBoxStyle := InputBoxFocusedStyle
	if m.isPlanning() {
		inputBoxStyle = InputBoxPlanningStyle
	}
	inputBox := inputBoxStyle.Width(inputBoxWidth).Render(m.input.View())

	planningBadge := ""
	if m.isPlanning() {
		phase := strings.ToUpper(m.planning.phase)
		if phase == "" {
			phase = "ACTIVE"
		}
		planningBadge = "  " + PlanningBadgeStyle.Render("◆ PLANNING · "+phase) +
			"  " + MutedStyle.Render("(ctrl+p to exit)")
	}

	// Convention: bottom starts with "\n\n" (blank visual separator) so the
	// last content line is never touching the input/toolbar frame.
	switch {
	case m.waiting:
		elapsed := time.Since(m.waitStart).Truncate(time.Second)
		spinnerLine := "  " + m.spinner.View() + ThinkingStyle.Render(fmt.Sprintf(" Thinking... (%s)", formatElapsed(elapsed)))
		activityLine := ""
		if !m.activity.IsIdle() {
			activityLine = indentLines(m.activity.View(), "  ") + "\n"
		}
		bottom := "\n\n" + activityLine + spinnerLine + "\n"
		if planningBadge != "" {
			bottom += planningBadge + "\n"
		}
		bottom += inputBox
		return bottom
	case m.planning.active && !m.planning.editing:
		toolbar := renderPlanToolbar(m.planning.phase, m.planning.selected, inputBoxWidth)
		bottom := "\n\n"
		if planningBadge != "" {
			bottom += planningBadge + "\n"
		}
		bottom += toolbar + "\n"
		return bottom
	default:
		bottom := "\n\n"
		if planningBadge != "" {
			bottom += planningBadge + "\n"
		}
		bottom += inputBox
		return bottom
	}
}

// chatResultMsg carries all non-span blocks from a completed chat response.
// Dispatched as the terminal message after the SSE stream closes.
type chatResultMsg struct {
	blocks []api.OutputBlock
	err    error
}

// dispatchSpanEvent converts an api.SpanEventBlock into the right
// activity message type and applies it to the tracker model.
func dispatchSpanEvent(am activity.Model, b api.SpanEventBlock) activity.Model {
	ts, err := time.Parse(time.RFC3339Nano, b.Timestamp)
	if err != nil {
		ts = time.Now()
	}
	switch b.Phase {
	case "start":
		return am.Update(activity.SpanStartMsg{
			SpanID:     b.SpanID,
			ParentID:   b.ParentSpanID,
			Name:       b.Name,
			Attributes: b.Attributes,
			Timestamp:  ts,
		})
	case "end":
		status, _ := b.Attributes["kukuvaia.status"].(string)
		return am.Update(activity.SpanEndMsg{
			SpanID:     b.SpanID,
			Status:     status,
			Attributes: b.Attributes,
			Timestamp:  ts,
		})
	case "delta":
		tokens, _ := b.Attributes["kukuvaia.tokens.delta"].(float64) // JSON numbers decode as float64
		return am.Update(activity.SpanAttributeDeltaMsg{
			SpanID: b.SpanID,
			Tokens: int(tokens),
		})
	}
	return am
}

// chatStreamMsg is dispatched for each block as it arrives on the SSE stream.
// Span events are routed to the activity tracker immediately; other blocks are
// buffered into turnBuffer for final rendering. `next` chains the read loop.
type chatStreamMsg struct {
	block api.OutputBlock
	next  tea.Cmd // nil when the stream has ended
	err   error
}

// sendChat opens an SSE stream and returns a cmd that reads the first block.
// Each read produces a chatStreamMsg with a `next` cmd that reads the following
// block, until the channels close and a chatResultMsg is emitted.
func (m Model) sendChat(text string) tea.Cmd {
	blocks, errs := m.client.Chat(m.session, text)
	return readNextChatBlock(blocks, errs, nil)
}

// readNextChatBlock builds a cmd that reads one block or one error from the
// SSE channel pair. `accumulated` carries non-span blocks from prior iterations
// so the terminal chatResultMsg can include them all at once.
func readNextChatBlock(
	blocks <-chan api.OutputBlock,
	errs <-chan error,
	accumulated []api.OutputBlock,
) tea.Cmd {
	return func() tea.Msg {
		select {
		case block, ok := <-blocks:
			if !ok {
				return chatResultMsg{blocks: accumulated, err: nil}
			}
			var next []api.OutputBlock
			if _, isSpan := block.(api.SpanEventBlock); !isSpan {
				next = append(accumulated, block)
			} else {
				next = accumulated
			}
			return chatStreamMsg{
				block: block,
				next:  readNextChatBlock(blocks, errs, next),
			}
		case err, ok := <-errs:
			if !ok {
				return chatResultMsg{blocks: accumulated, err: nil}
			}
			if err != nil {
				return chatResultMsg{blocks: accumulated, err: err}
			}
			return chatStreamMsg{next: readNextChatBlock(blocks, errs, accumulated)}
		}
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
			// Inline activity tree for this turn — appears right above the
			// assistant's content, indented to match the message gutter.
			if msg.activitySnapshot != "" {
				parts = append(parts, indentLines(msg.activitySnapshot, "  "))
			}
			for _, block := range msg.blocks {
				rendered := RenderBlock(block, width-4)
				parts = append(parts, "  "+rendered)
			}
		}
	}

	return strings.Join(parts, "\n")
}

// indentLines prepends `prefix` to every non-empty line of s.
func indentLines(s, prefix string) string {
	lines := strings.Split(s, "\n")
	for i, l := range lines {
		if l == "" {
			continue
		}
		lines[i] = prefix + l
	}
	return strings.Join(lines, "\n")
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
			// "Create plan" → send "ready", mark as drafting for response detection
			m.planning.active = false
			m.planning.phase = "drafting"
			m.messages = append(m.messages, message{role: "user", text: "ready"})
			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] planning toolbar: Create plan → sending 'ready'")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return tea.Batch(m.sendChat("ready"), tickElapsed())
		}
		// "Add feedback" → switch to text input
		m.planning.editing = true
		m.input.Focus()
		return nil
	}

	if m.planning.phase == "executing" {
		switch m.planning.selected {
		case 0:
			// "Mark done" → /plan done. Server closes the plan (status='completed',
			// phase='done') and drops the session; no phase metadata on next turn
			// so the CLI clears the toolbar via the "no metadata" branch.
			m.planning = planToolbar{}
			m.messages = append(m.messages, message{role: "user", text: "/plan done"})
			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] planning toolbar: Mark done (executing) → sending '/plan done'")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return tea.Batch(m.sendChat("/plan done"), tickElapsed())
		case 1:
			// "Add feedback" → open input. User types progress ("zrobiłem krok 1"
			// / "skip step 2" / …); LLM sees it in EXECUTING-phase prompt and may
			// call completeStep or respond plainly. Server re-emits phase=executing
			// → syncPlanningFromServer restores the toolbar on response.
			m.planning.editing = true
			m.input.Focus()
			return nil
		}
		return nil
	}

	if m.planning.phase == "approval" {
		switch m.planning.selected {
		case 0:
			// "Approve plan" → send "tak"; server advances APPROVAL → EXECUTING
			// (planToolbar stays active on the server side — see step 2 server change).
			m.planning = planToolbar{}
			m.messages = append(m.messages, message{role: "user", text: "approve"})
			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] planning toolbar: Approve plan → sending 'approve'")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return tea.Batch(m.sendChat("approve"), tickElapsed())
		case 1:
			// "Request changes" → switch to text input
			m.planning.editing = true
			m.planning.phase = "drafting" // will transition back to approval after response
			m.input.Focus()
			return nil
		case 2:
			// "Mark done" → send /plan done; closes the plan in one step for
			// users who resumed an already-approved (status='active') plan.
			// For a draft plan the server returns "No active plan to close".
			m.planning = planToolbar{}
			m.messages = append(m.messages, message{role: "user", text: "/plan done"})
			m.waiting = true
			m.waitStart = time.Now()
			log.Printf("[UPDATE] planning toolbar: Mark done → sending '/plan done'")
			m.viewport.SetContent(m.renderMessages())
			m.viewport.GotoBottom()
			return tea.Batch(m.sendChat("/plan done"), tickElapsed())
		}
		return nil
	}

	return nil
}

// renderPlanToolbar renders the planning mode action bar on a single line.
func renderPlanToolbar(phase string, selected int, width int) string {
	var labels []string
	switch phase {
	case "approval":
		labels = []string{"Approve plan", "Request changes", "Mark done"}
	case "executing":
		labels = []string{"Mark done", "Add feedback"}
	default: // discovery
		labels = []string{"Create plan", "Add feedback"}
	}

	active := lipgloss.NewStyle().Bold(true).
		Foreground(lipgloss.Color("#000000")).Background(ColorPrimary)
	inactive := lipgloss.NewStyle().Foreground(ColorMuted)

	var parts []string
	for i, label := range labels {
		if i == selected {
			parts = append(parts, active.Render(" "+label+" "))
		} else {
			parts = append(parts, inactive.Render("["+label+"]"))
		}
	}

	hint := MutedStyle.Render("tab/← → · enter")
	return "  " + strings.Join(parts, "  ") + "  " + hint
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

// activityTickMsg pulses the activity spinner frame.
type activityTickMsg time.Time

// scheduleActivityTick returns a Cmd that fires every 200 ms while the
// activity tracker has any in-flight spans.
func scheduleActivityTick() tea.Cmd {
	return tea.Tick(200*time.Millisecond, func(t time.Time) tea.Msg {
		return activityTickMsg(t)
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
	// WithMouseCellMotion forwards mouse events (including wheel) to the model.
	// Bubbles' viewport handles MouseWheel up/down natively — restores native
	// iTerm2-style scroll inside the alt-screen TUI. Text selection with drag
	// now requires Option (macOS) / Shift (Linux) — standard across vim, k9s, lazygit.
	p := tea.NewProgram(m, tea.WithAltScreen(), tea.WithMouseCellMotion())
	_, err := p.Run()
	return err
}
