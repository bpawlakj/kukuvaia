package tui

import (
	"fmt"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

// Generic single-select picker triggered by a server-emitted ChoiceBlock. Modeled after
// PlanPicker but stripped to the essentials: arrow nav + Enter confirms + Esc cancels. Every
// interactive disambiguation flow funnels through this widget — multi-result template
// lookups, ambiguous-intent confirmations, anything where the agent calls ask_user_to_choose
// server-side.

type (
	// choicePickerSelectedMsg — operator confirmed a choice. The dispatch path in app.go
	// translates this into a follow-up user chat message so the LLM picks up the selection
	// on its next turn.
	choicePickerSelectedMsg struct {
		choiceID string
		option   api.ChoiceOption
	}

	// choicePickerCancelMsg — operator dismissed the picker without choosing (Esc / q).
	// The agent's turn already ended on the server side, so cancel just clears the overlay
	// — operator can manually type a follow-up to steer the agent forward.
	choicePickerCancelMsg struct {
		choiceID string
	}
)

// ChoicePicker — single-select arrow-navigable list. State is intentionally minimal so the
// widget can be re-instantiated cheaply each time a ChoiceBlock arrives.
type ChoicePicker struct {
	choiceID string
	prompt   string
	options  []api.ChoiceOption
	cursor   int
	offset   int
	width    int
	height   int
}

func NewChoicePicker(block api.ChoiceBlock) ChoicePicker {
	return ChoicePicker{
		choiceID: block.ChoiceID,
		prompt:   block.Prompt,
		options:  block.Options,
	}
}

func (p ChoicePicker) maxVisible() int {
	available := p.height - 6
	if available < 3 {
		available = 3
	}
	return available
}

func (p ChoicePicker) Update(msg tea.Msg) (ChoicePicker, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		p.width = msg.Width
		p.height = msg.Height

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
			if p.cursor < len(p.options)-1 {
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
			if p.cursor >= len(p.options) {
				p.cursor = len(p.options) - 1
				if p.cursor < 0 {
					p.cursor = 0
				}
			}
			if p.cursor >= p.offset+p.maxVisible() {
				p.offset = p.cursor - p.maxVisible() + 1
			}
		case "home":
			p.cursor = 0
			p.offset = 0
		case "end":
			p.cursor = len(p.options) - 1
			if p.cursor < 0 {
				p.cursor = 0
			}
			if p.cursor >= p.offset+p.maxVisible() {
				p.offset = p.cursor - p.maxVisible() + 1
			}
		case "enter":
			if len(p.options) == 0 {
				return p, nil
			}
			selected := p.options[p.cursor]
			cid := p.choiceID
			return p, func() tea.Msg {
				return choicePickerSelectedMsg{choiceID: cid, option: selected}
			}
		case "esc", "q":
			cid := p.choiceID
			return p, func() tea.Msg {
				return choicePickerCancelMsg{choiceID: cid}
			}
		}
	}
	return p, nil
}

func (p ChoicePicker) View(width, height int) string {
	p.width = width
	if height > 0 {
		p.height = height
	}

	var b strings.Builder
	title := "  Choose one"
	if strings.TrimSpace(p.prompt) != "" {
		title = "  " + p.prompt
	}
	b.WriteString(pickerTitleStyle.Render(title))
	b.WriteString("\n\n")

	if len(p.options) == 0 {
		b.WriteString(MutedStyle.Render("  No options were provided. Press esc to dismiss."))
		b.WriteString("\n\n")
		b.WriteString(pickerHintStyle.Render("  esc dismiss"))
		return b.String()
	}

	visible := p.maxVisible()
	end := p.offset + visible
	if end > len(p.options) {
		end = len(p.options)
	}

	if p.offset > 0 {
		b.WriteString(pickerIdStyle.Render(fmt.Sprintf("  ↑ %d more above", p.offset)))
		b.WriteString("\n")
	}

	for i := p.offset; i < end; i++ {
		opt := p.options[i]
		label := opt.Label
		if label == "" {
			label = opt.Value
		}
		if len(label) > 60 {
			label = label[:59] + "…"
		}
		desc := opt.Description
		row := label
		if desc != "" {
			if len(desc) > 80 {
				desc = desc[:79] + "…"
			}
			row = fmt.Sprintf("%s  %s", label, pickerIdStyle.Render(desc))
		}
		if i == p.cursor {
			arrow := lipgloss.NewStyle().Foreground(ColorPrimary).Bold(true).Render("❯ ")
			b.WriteString(arrow + lipgloss.NewStyle().Bold(true).Render(label))
			if desc != "" {
				b.WriteString("  " + pickerIdStyle.Render(desc))
			}
		} else {
			b.WriteString(pickerItemStyle.Render(row))
		}
		b.WriteString("\n")
	}

	remaining := len(p.options) - end
	if remaining > 0 {
		b.WriteString(pickerIdStyle.Render(fmt.Sprintf("  ↓ %d more below", remaining)))
		b.WriteString("\n")
	}

	b.WriteString("\n")
	b.WriteString(pickerHintStyle.Render("  ↑↓ nav · enter confirm · esc dismiss"))
	return b.String()
}
