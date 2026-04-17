package tui

import (
	"strings"

	"github.com/charmbracelet/lipgloss"
	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

func renderVerification(b api.VerificationBlock, width int) string {
	var sb strings.Builder

	pathStyle := lipgloss.NewStyle().Bold(true).Foreground(ColorText)
	passIcon := lipgloss.NewStyle().Foreground(ColorPass).Render("\u2713")
	failIcon := lipgloss.NewStyle().Foreground(ColorFail).Render("\u2717")

	// Header: path + status icon.
	icon := failIcon
	if b.Valid {
		icon = passIcon
	}
	sb.WriteString(pathStyle.Render(b.Path) + " " + icon)

	issueStyle := lipgloss.NewStyle().Foreground(ColorFail)
	passedStyle := lipgloss.NewStyle().Foreground(ColorPass)

	if len(b.Issues) > 0 {
		sb.WriteString("\n")
		for i, issue := range b.Issues {
			sb.WriteString(issueStyle.Render("  \u2717 " + issue))
			if i < len(b.Issues)-1 {
				sb.WriteString("\n")
			}
		}
	}

	if len(b.Passed) > 0 {
		sb.WriteString("\n")
		for i, p := range b.Passed {
			sb.WriteString(passedStyle.Render("  \u2713 " + p))
			if i < len(b.Passed)-1 {
				sb.WriteString("\n")
			}
		}
	}

	panelStyle := PanelStyle.Copy()
	if width > 0 {
		panelStyle = panelStyle.Width(width)
	}
	return panelStyle.Render(sb.String())
}
