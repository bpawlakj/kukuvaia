package tui

import (
	"fmt"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

// Plan picker messages.
type (
	// planPickerActionMsg — user picked an action on the selected plan(s).
	// `kind` ∈ "resume" | "combine" | "new" | "abandon".
	// For "combine", SelectedIDs carries ≥2 plan ids.
	planPickerActionMsg struct {
		kind        string
		planID      string
		selectedIDs []string
	}
	planPickerCancelMsg struct{}
	plansLoadedMsg      struct {
		plans []api.PlanEntry
		err   error
	}
)

// PlanPicker — arrow-navigable registry with multi-select for combine.
//
// Keybindings:
//
//	↑↓/j/k      navigate
//	pgup/pgdn   page
//	home/end    edges
//	enter       resume selected (or combine if ≥2 marked)
//	c           toggle combine-mark on current row
//	n           new-from selected (single-parent derive)
//	d           abandon — two-step (press twice within 3s)
//	/           cycle status filter: all → draft → active → completed → abandoned
//	esc/q       cancel
type PlanPicker struct {
	plans        []api.PlanEntry
	cursor       int
	offset       int
	marked       map[string]bool // plan ids marked for combine
	filter       string          // "" = all, else status
	width        int
	height       int
	loading      bool
	err          error
	pendingKill  string // plan id waiting for second 'd'
	killDeadline time.Time
}

func NewPlanPicker() PlanPicker {
	return PlanPicker{loading: true, marked: map[string]bool{}}
}

func (p PlanPicker) maxVisible() int {
	available := p.height - 6
	if available < 3 {
		available = 3
	}
	return available
}

func (p PlanPicker) Update(msg tea.Msg) (PlanPicker, tea.Cmd) {
	switch msg := msg.(type) {
	case plansLoadedMsg:
		p.loading = false
		if msg.err != nil {
			p.err = msg.err
			return p, nil
		}
		p.plans = msg.plans
		if p.cursor >= len(p.plans) {
			p.cursor = 0
			p.offset = 0
		}
		return p, nil

	case tea.WindowSizeMsg:
		p.height = msg.Height
		p.width = msg.Width

	case tea.KeyMsg:
		// Clear the pending two-step kill confirmation if it timed out.
		if p.pendingKill != "" && time.Now().After(p.killDeadline) {
			p.pendingKill = ""
		}
		switch msg.String() {
		case "up", "k":
			if p.cursor > 0 {
				p.cursor--
				if p.cursor < p.offset {
					p.offset = p.cursor
				}
			}
		case "down", "j":
			if p.cursor < len(p.plans)-1 {
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
			if p.cursor >= len(p.plans) {
				p.cursor = len(p.plans) - 1
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
			p.cursor = len(p.plans) - 1
			if p.cursor < 0 {
				p.cursor = 0
			}
			max := len(p.plans) - p.maxVisible()
			if max < 0 {
				max = 0
			}
			p.offset = max
		case "c":
			// Toggle combine-mark on current row.
			if len(p.plans) > 0 {
				id := p.plans[p.cursor].ID
				if p.marked[id] {
					delete(p.marked, id)
				} else {
					p.marked[id] = true
				}
			}
		case "n":
			// New-from: derive a new plan from the current row.
			if len(p.plans) > 0 {
				return p, func() tea.Msg {
					return planPickerActionMsg{kind: "new", planID: p.plans[p.cursor].ID}
				}
			}
		case "d":
			// Two-step abandon. First press marks pendingKill + sets a 3s deadline;
			// second press on the same row within the window commits.
			if len(p.plans) == 0 {
				return p, nil
			}
			id := p.plans[p.cursor].ID
			if p.pendingKill == id && time.Now().Before(p.killDeadline) {
				p.pendingKill = ""
				return p, func() tea.Msg {
					return planPickerActionMsg{kind: "abandon", planID: id}
				}
			}
			p.pendingKill = id
			p.killDeadline = time.Now().Add(3 * time.Second)
		case "/":
			// Cycle filter: all → draft → active → completed → abandoned → all
			p.filter = nextFilter(p.filter)
			p.loading = true
			p.plans = nil
			p.cursor = 0
			p.offset = 0
			return p, func() tea.Msg {
				// Reload with new filter — caller wires this. The actual reload cmd
				// lives in app.go; we just signal intent via a re-fetch action.
				return planPickerFilterMsg{filter: p.filter}
			}
		case "enter":
			if len(p.plans) == 0 {
				return p, nil
			}
			// If ≥2 rows are combine-marked, launch combine. Otherwise resume the
			// row under the cursor.
			if len(p.marked) >= 2 {
				ids := make([]string, 0, len(p.marked))
				for id := range p.marked {
					ids = append(ids, id)
				}
				return p, func() tea.Msg {
					return planPickerActionMsg{kind: "combine", selectedIDs: ids}
				}
			}
			return p, func() tea.Msg {
				return planPickerActionMsg{kind: "resume", planID: p.plans[p.cursor].ID}
			}
		case "esc", "q":
			return p, func() tea.Msg { return planPickerCancelMsg{} }
		}
	}
	return p, nil
}

// planPickerFilterMsg — emitted when the user cycles the status filter.
// app.go handles reloading and pushing plansLoadedMsg back.
type planPickerFilterMsg struct {
	filter string
}

func nextFilter(current string) string {
	switch current {
	case "":
		return "draft"
	case "draft":
		return "active"
	case "active":
		return "completed"
	case "completed":
		return "abandoned"
	default:
		return ""
	}
}

func (p PlanPicker) View(width, height int) string {
	p.width = width
	if height > 0 {
		p.height = height
	}

	if p.loading {
		return ThinkingStyle.Render("  Loading plans...")
	}
	if p.err != nil {
		return ErrorStyle.Render(fmt.Sprintf("  Error: %v", p.err))
	}

	var b strings.Builder

	filterLabel := "all"
	if p.filter != "" {
		filterLabel = p.filter
	}
	title := fmt.Sprintf("  My Plans  [%s ↓]", filterLabel)
	b.WriteString(pickerTitleStyle.Render(title))
	b.WriteString("\n\n")

	if len(p.plans) == 0 {
		b.WriteString(MutedStyle.Render("  No plans match. Use `/plan <task>` to start one."))
		b.WriteString("\n\n")
		b.WriteString(pickerHintStyle.Render("  / filter · esc close"))
		return b.String()
	}

	visible := p.maxVisible()
	end := p.offset + visible
	if end > len(p.plans) {
		end = len(p.plans)
	}

	if p.offset > 0 {
		b.WriteString(pickerIdStyle.Render(fmt.Sprintf("  ↑ %d more above", p.offset)))
		b.WriteString("\n")
	}

	for i := p.offset; i < end; i++ {
		pl := p.plans[i]
		mark := " "
		if p.marked[pl.ID] {
			mark = "x"
		}
		statusBadge := statusBadgeString(pl.Status)
		name := pl.Name
		if name == "" {
			name = pl.TaskPreview
		}
		if len(name) > 50 {
			name = name[:49] + "…"
		}
		age := relativeAge(pl.UpdatedAt)

		parentHint := ""
		if len(pl.Parents) > 0 {
			var parts []string
			for _, parent := range pl.Parents {
				parts = append(parts, parent.ParentName)
			}
			parentHint = " ↳ from: " + strings.Join(parts, " + ")
		}

		killHint := ""
		if p.pendingKill == pl.ID && time.Now().Before(p.killDeadline) {
			killHint = " " + ErrorStyle.Render("[press d again to abandon]")
		}

		row := fmt.Sprintf("[%s] %s  %s  %s", mark, statusBadge, name, pickerIdStyle.Render(age))
		if i == p.cursor {
			arrow := lipgloss.NewStyle().Foreground(ColorPrimary).Bold(true).Render("❯ ")
			b.WriteString(arrow + lipgloss.NewStyle().Bold(true).Render(row) + killHint)
		} else {
			b.WriteString(pickerItemStyle.Render(row) + killHint)
		}
		if parentHint != "" {
			b.WriteString("\n      " + pickerIdStyle.Render(parentHint))
		}
		b.WriteString("\n")
	}

	remaining := len(p.plans) - end
	if remaining > 0 {
		b.WriteString(pickerIdStyle.Render(fmt.Sprintf("  ↓ %d more below", remaining)))
		b.WriteString("\n")
	}

	b.WriteString("\n")
	hint := "  ↑↓ nav · enter resume/combine · c mark · n new-from · d abandon · / filter · esc close"
	if len(p.marked) > 0 {
		hint = fmt.Sprintf("  ↑↓ nav · enter combine (%d marked) · c toggle · esc close", len(p.marked))
	}
	b.WriteString(pickerHintStyle.Render(hint))

	return b.String()
}

// statusBadgeString renders a short fixed-width status badge.
func statusBadgeString(status string) string {
	switch status {
	case "draft":
		return WarningStyle.Render("…draft ")
	case "active":
		return pickerNameStyle.Render("✓active")
	case "completed":
		return MutedStyle.Render("✓done  ")
	case "abandoned":
		return MutedStyle.Render("✗drop  ")
	default:
		return MutedStyle.Render(status)
	}
}

// relativeAge turns an RFC timestamp into a compact "2d", "3h", "5m" string.
func relativeAge(ts string) string {
	if ts == "" {
		return "-"
	}
	// Server emits `Instant.toString()` which is RFC3339 with offset Z.
	t, err := time.Parse(time.RFC3339Nano, ts)
	if err != nil {
		t, err = time.Parse(time.RFC3339, ts)
		if err != nil {
			return "-"
		}
	}
	d := time.Since(t)
	if d < time.Minute {
		return "just now"
	}
	if d < time.Hour {
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	}
	if d < 24*time.Hour {
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	}
	days := int(d.Hours() / 24)
	if days < 14 {
		return fmt.Sprintf("%dd ago", days)
	}
	weeks := days / 7
	return fmt.Sprintf("%dw ago", weeks)
}
