package tui

import (
	"fmt"
	"strings"

	"github.com/charmbracelet/lipgloss"
	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

func renderProgress(b api.ProgressBlock, width int) string {
	barWidth := width - 20
	if barWidth > 30 {
		barWidth = 30
	}
	if barWidth < 10 {
		barWidth = 10
	}

	filled := 0
	if b.Total > 0 {
		filled = barWidth * b.Current / b.Total
	}
	if filled > barWidth {
		filled = barWidth
	}
	empty := barWidth - filled

	filledStyle := lipgloss.NewStyle().Foreground(ColorProgressFilled)
	emptyStyle := lipgloss.NewStyle().Foreground(ColorProgressEmpty)

	bar := "[" +
		filledStyle.Render(strings.Repeat("\u2588", filled)) +
		emptyStyle.Render(strings.Repeat("\u2591", empty)) +
		"]"

	counter := fmt.Sprintf(" %d/%d", b.Current, b.Total)
	label := ""
	if b.Label != "" {
		label = " " + b.Label
	}

	return bar + counter + label
}
