package tui

import (
	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/lipgloss/table"
	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

func renderTable(b api.TableBlock, width int) string {
	rows := make([][]string, len(b.Rows))
	copy(rows, b.Rows)

	t := table.New().
		Border(lipgloss.RoundedBorder()).
		BorderStyle(lipgloss.NewStyle().Foreground(ColorTableBorder)).
		Headers(b.Headers...).
		Rows(rows...).
		StyleFunc(func(row, col int) lipgloss.Style {
			if row == 0 {
				// Header row.
				return lipgloss.NewStyle().
					Bold(true).
					Foreground(ColorTableHeaderFg).
					Background(ColorTableHeaderBg).
					Padding(0, 1)
			}
			s := lipgloss.NewStyle().Padding(0, 1)
			if row%2 == 0 {
				s = s.Background(ColorTableRowAlt)
			}
			return s
		})

	if width > 0 {
		t = t.Width(width)
	}

	rendered := t.Render()

	if b.Title != "" {
		return TitleStyle.Render(b.Title) + "\n" + rendered
	}
	return rendered
}
