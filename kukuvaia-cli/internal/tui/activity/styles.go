package activity

import "github.com/charmbracelet/lipgloss"

// Styles bundles the lipgloss styles used by the activity view.
// Colours are sourced from kukuvaia-theme.yaml via the tui package's generated
// ColorPrimary / ColorMuted / ColorError. Callers build this with NewStyles()
// and pass it at construction time so the activity package stays testable
// without a theme dependency.
type Styles struct {
	Header       lipgloss.Style // "●" bullet + primary colour
	HeaderError  lipgloss.Style // "✗" bullet for top-level with errors
	Tree         lipgloss.Style // "├─ └─ │" in muted
	StatusActive lipgloss.Style // "…" for in-flight leaves
	StatusOK     lipgloss.Style // "✓" for success, muted
	StatusError  lipgloss.Style // "✗" in error colour
	StatusCancel lipgloss.Style // "⊘" in muted
	Verb         lipgloss.Style // animator verb line (primary)
	Meta         lipgloss.Style // elapsed / tokens meta text (muted)
	Hint         lipgloss.Style // "ctrl+o to expand" hint (muted italic)
	ErrorMsg     lipgloss.Style // inline error message, error colour
}

// NewStyles builds styles from three theme colours. The tui package passes
// its ColorPrimary / ColorMuted / ColorError constants.
func NewStyles(primary, muted, errColor lipgloss.Color) Styles {
	return Styles{
		Header:       lipgloss.NewStyle().Foreground(primary).Bold(true),
		HeaderError:  lipgloss.NewStyle().Foreground(errColor).Bold(true),
		Tree:         lipgloss.NewStyle().Foreground(muted),
		StatusActive: lipgloss.NewStyle().Foreground(primary),
		StatusOK:     lipgloss.NewStyle().Foreground(muted),
		StatusError:  lipgloss.NewStyle().Foreground(errColor).Bold(true),
		StatusCancel: lipgloss.NewStyle().Foreground(muted),
		Verb:         lipgloss.NewStyle().Foreground(primary).Bold(true),
		Meta:         lipgloss.NewStyle().Foreground(muted),
		Hint:         lipgloss.NewStyle().Foreground(muted).Italic(true),
		ErrorMsg:     lipgloss.NewStyle().Foreground(errColor),
	}
}

// DefaultStyles returns styles with plain colours — used by tests and the View
// fallback when the root TUI has not supplied a themed Styles instance.
func DefaultStyles() Styles {
	return NewStyles(
		lipgloss.Color("#00FF41"), // primary — Matrix green
		lipgloss.Color("#008F11"), // muted — dark green
		lipgloss.Color("#FF4444"), // error — red
	)
}
