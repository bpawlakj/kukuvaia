package tui

import (
	"strings"

	"github.com/charmbracelet/lipgloss"
	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

func renderPlan(b api.PlanBlock, width int) string {
	var sb strings.Builder

	sb.WriteString(TitleStyle.Render(b.Task))
	sb.WriteString("\n")

	completedStyle := lipgloss.NewStyle().Foreground(ColorPass)
	pendingStyle := lipgloss.NewStyle().Foreground(ColorMuted)

	for i, step := range b.Steps {
		if i < b.CompletedCount {
			sb.WriteString(completedStyle.Render("  \u2713 " + step))
		} else {
			sb.WriteString(pendingStyle.Render("  \u00b7 " + step))
		}
		if i < len(b.Steps)-1 {
			sb.WriteString("\n")
		}
	}

	panelStyle := PanelStyle.Copy()
	if width > 0 {
		panelStyle = panelStyle.Width(width)
	}
	return panelStyle.Render(sb.String())
}
