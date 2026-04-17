package tui

import (
	"fmt"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

// Session picker messages.
type (
	sessionSelectedMsg   struct{ session api.SessionInfo }
	sessionPickerDoneMsg struct{} // user cancelled
	sessionsLoadedMsg    struct {
		sessions []api.SessionInfo
		err      error
	}
)

// SessionPicker is an interactive session list with arrow-key navigation and scrolling.
type SessionPicker struct {
	sessions []api.SessionInfo
	cursor   int
	offset   int // scroll offset for windowed view
	width    int
	height   int
	loading  bool
	err      error
}

func NewSessionPicker() SessionPicker {
	return SessionPicker{loading: true}
}

// maxVisible returns how many sessions fit in the visible window.
func (p SessionPicker) maxVisible() int {
	// Reserve lines for title (2), footer (2), padding
	available := p.height - 6
	if available < 3 {
		available = 3
	}
	return available
}

// Styles for the picker.
var (
	pickerTitleStyle = lipgloss.NewStyle().
		Foreground(ColorPrimary).
		Bold(true).
		MarginBottom(1)

	pickerItemStyle = lipgloss.NewStyle().
		PaddingLeft(2)

	pickerSelectedStyle = lipgloss.NewStyle().
		Foreground(ColorPrimary).
		Bold(true).
		PaddingLeft(1)

	pickerNameStyle = lipgloss.NewStyle().
		Foreground(ColorText)

	pickerIdStyle = lipgloss.NewStyle().
		Foreground(ColorMuted)

	pickerCountStyle = lipgloss.NewStyle().
		Foreground(ColorMuted)

	pickerHintStyle = lipgloss.NewStyle().
		Foreground(ColorMuted).
		Italic(true).
		MarginTop(1)
)

func (p SessionPicker) Update(msg tea.Msg) (SessionPicker, tea.Cmd) {
	switch msg := msg.(type) {
	case sessionsLoadedMsg:
		p.loading = false
		if msg.err != nil {
			p.err = msg.err
			return p, nil
		}
		p.sessions = msg.sessions
		return p, nil

	case tea.WindowSizeMsg:
		p.height = msg.Height
		p.width = msg.Width

	case tea.KeyMsg:
		switch msg.String() {
		case "up", "k":
			if p.cursor > 0 {
				p.cursor--
				if p.cursor < p.offset {
					p.offset = p.cursor
				}
			}
		case "down", "j":
			if p.cursor < len(p.sessions)-1 {
				p.cursor++
				if p.cursor >= p.offset+p.maxVisible() {
					p.offset = p.cursor - p.maxVisible() + 1
				}
			}
		case "pgup":
			p.cursor -= p.maxVisible()
			if p.cursor < 0 {
				p.cursor = 0
			}
			if p.cursor < p.offset {
				p.offset = p.cursor
			}
		case "pgdown":
			p.cursor += p.maxVisible()
			if p.cursor >= len(p.sessions) {
				p.cursor = len(p.sessions) - 1
			}
			if p.cursor >= p.offset+p.maxVisible() {
				p.offset = p.cursor - p.maxVisible() + 1
			}
		case "home":
			p.cursor = 0
			p.offset = 0
		case "end":
			p.cursor = len(p.sessions) - 1
			max := len(p.sessions) - p.maxVisible()
			if max < 0 {
				max = 0
			}
			p.offset = max
		case "enter":
			if len(p.sessions) > 0 {
				return p, func() tea.Msg {
					return sessionSelectedMsg{session: p.sessions[p.cursor]}
				}
			}
		case "esc", "q":
			return p, func() tea.Msg { return sessionPickerDoneMsg{} }
		}
	}
	return p, nil
}

func (p SessionPicker) View(width, height int) string {
	p.width = width
	if height > 0 {
		p.height = height
	}

	if p.loading {
		return ThinkingStyle.Render("  Loading sessions...")
	}
	if p.err != nil {
		return ErrorStyle.Render(fmt.Sprintf("  Error: %v", p.err))
	}
	if len(p.sessions) == 0 {
		return MutedStyle.Render("  No sessions found.")
	}

	var b strings.Builder

	b.WriteString(pickerTitleStyle.Render("  Sessions"))
	b.WriteString("\n\n")

	// Windowed view — only show sessions in visible range
	visible := p.maxVisible()
	end := p.offset + visible
	if end > len(p.sessions) {
		end = len(p.sessions)
	}

	// Scroll indicator top
	if p.offset > 0 {
		b.WriteString(pickerIdStyle.Render(fmt.Sprintf("  ↑ %d more above", p.offset)))
		b.WriteString("\n")
	}

	for i := p.offset; i < end; i++ {
		s := p.sessions[i]
		name := s.DisplayName()
		id := s.ID
		if id == "" {
			id = s.SessionID
		}
		count := fmt.Sprintf("%d msgs", s.MessageCount)

		if i == p.cursor {
			arrow := lipgloss.NewStyle().Foreground(ColorPrimary).Bold(true).Render("❯ ")
			nameStr := lipgloss.NewStyle().Foreground(ColorPrimary).Bold(true).Render(name)
			idStr := pickerIdStyle.Render(" (" + truncateID(id) + ")")
			countStr := pickerCountStyle.Render(" · " + count)
			b.WriteString(arrow + nameStr + idStr + countStr)
		} else {
			nameStr := pickerNameStyle.Render(name)
			idStr := pickerIdStyle.Render(" (" + truncateID(id) + ")")
			countStr := pickerCountStyle.Render(" · " + count)
			b.WriteString(pickerItemStyle.Render(nameStr + idStr + countStr))
		}
		b.WriteString("\n")
	}

	// Scroll indicator bottom
	remaining := len(p.sessions) - end
	if remaining > 0 {
		b.WriteString(pickerIdStyle.Render(fmt.Sprintf("  ↓ %d more below", remaining)))
		b.WriteString("\n")
	}

	b.WriteString("\n")
	hint := "  ↑↓ navigate · pgup/pgdn scroll · enter select · esc cancel"
	b.WriteString(pickerHintStyle.Render(hint))

	return b.String()
}

func truncateID(id string) string {
	if len(id) > 16 {
		return id[:6] + "…" + id[len(id)-4:]
	}
	return id
}
