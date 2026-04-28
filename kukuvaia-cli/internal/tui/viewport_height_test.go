package tui

import "testing"

// Pins the contract that viewport height + newline count of the bottom string
// equals the terminal height. The earlier formula (strings.Count + 1) was off
// by one, which produced a persistent mismatch between the height set by
// Update handlers and the height used by View() — visible as jumping content
// and clipped last lines while typing.
func TestViewportHeightFor(t *testing.T) {
	tests := []struct {
		name     string
		term     int
		bottom   string
		wantVP   int
		wantFits bool // vp rows + bottom rows must equal term
	}{
		{
			name:     "idle: 3-line input box with leading blank separator",
			term:     34,
			bottom:   "\n\n╭─╮\n│x│\n╰─╯",
			wantVP:   30, // 34 - 4 newlines
			wantFits: true,
		},
		{
			name:     "planning badge + input adds one extra line",
			term:     34,
			bottom:   "\n\nbadge\n╭─╮\n│x│\n╰─╯",
			wantVP:   29, // 34 - 5 newlines
			wantFits: true,
		},
		{
			name:     "waiting: spinner + activity + input",
			term:     40,
			bottom:   "\n\nactivity\nspinner\n╭─╮\n│x│\n╰─╯",
			wantVP:   34, // 40 - 6 newlines
			wantFits: true,
		},
		{
			name:     "very small terminal never returns < 1",
			term:     2,
			bottom:   "\n\n╭─╮\n│x│\n╰─╯",
			wantVP:   1,
			wantFits: false, // clamp regime — formula would underflow
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := viewportHeightFor(tc.term, tc.bottom)
			if got != tc.wantVP {
				t.Fatalf("viewportHeightFor(%d, %q) = %d, want %d", tc.term, tc.bottom, got, tc.wantVP)
			}
			if tc.wantFits {
				// viewport rendered output: got rows, (got-1) newlines, last row no trailing '\n'.
				// Concatenated with bottom, total newlines = (got-1) + newlineCount(bottom).
				// Total rendered rows = total newlines + 1 = got + newlineCount(bottom) = term.
				total := got + countNL(tc.bottom)
				if total != tc.term {
					t.Fatalf("vp rows (%d) + bottom newlines (%d) = %d, want terminal height %d",
						got, countNL(tc.bottom), total, tc.term)
				}
			}
		})
	}
}

func countNL(s string) int {
	n := 0
	for _, r := range s {
		if r == '\n' {
			n++
		}
	}
	return n
}
