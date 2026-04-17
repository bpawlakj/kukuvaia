package tui

import (
	"github.com/charmbracelet/lipgloss"
	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

func renderCode(b api.CodeBlock, width int) string {
	codeStyle := CodeBlockStyle.Copy().Foreground(ColorCodeText)
	if width > 0 {
		codeStyle = codeStyle.Width(width)
	}

	rendered := codeStyle.Render(b.Content)

	if b.Language != "" {
		label := lipgloss.NewStyle().
			Foreground(ColorMuted).
			Italic(true).
			Render(b.Language)
		return label + "\n" + rendered
	}
	return rendered
}
