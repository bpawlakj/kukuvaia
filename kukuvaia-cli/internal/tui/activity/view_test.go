package activity

import (
	"strings"
	"testing"
	"time"
)

// These tests strip ANSI escape codes from rendered output so assertions can
// focus on layout structure. Colour fidelity is verified manually.

func stripANSI(s string) string {
	var b strings.Builder
	inEsc := false
	for _, r := range s {
		if r == 0x1b {
			inEsc = true
			continue
		}
		if inEsc {
			if r == 'm' {
				inEsc = false
			}
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

func TestView_Idle_ReturnsEmpty(t *testing.T) {
	m := New()
	if m.View() != "" {
		t.Fatalf("idle tree should render empty, got %q", m.View())
	}
}

func TestView_Collapsed_HasBulletAndHint(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()
	m = m.Update(SpanStartMsg{SpanID: "r", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "t", ParentID: "r", Name: "tool:grep", Timestamp: now})

	out := stripANSI(m.View())
	if !strings.Contains(out, "●") {
		t.Errorf("collapsed should contain bullet ●, got %q", out)
	}
	if !strings.Contains(out, "ctrl+o to expand") {
		t.Errorf("collapsed should show expand hint, got %q", out)
	}
	if !strings.Contains(out, "searching 1 pattern") {
		t.Errorf("collapsed should summarise grep as 'searching 1 pattern', got %q", out)
	}
}

func TestView_Expanded_ShowsTreeBoxDrawing(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()
	m = m.Update(SpanStartMsg{SpanID: "r", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "t1", ParentID: "r", Name: "tool:grep", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "t2", ParentID: "r", Name: "tool:read_file", Timestamp: now})
	m = m.Toggle()

	out := stripANSI(m.View())
	if !strings.Contains(out, "├─") {
		t.Errorf("expanded should contain '├─' for middle child, got %q", out)
	}
	if !strings.Contains(out, "└─") {
		t.Errorf("expanded should contain '└─' for last child, got %q", out)
	}
	if !strings.Contains(out, "grep") {
		t.Errorf("expanded should mention grep, got %q", out)
	}
	if !strings.Contains(out, "read_file") {
		t.Errorf("expanded should mention read_file, got %q", out)
	}
	if !strings.Contains(out, "ctrl+o to collapse") {
		t.Errorf("expanded should show collapse hint, got %q", out)
	}
}

// Animator elapsed used to drop to "(0s)" the moment every span ended because the
// IsInFlight() filter excluded completed nodes, leaving earliestStart=nil. The user
// saw "Thinking (0s)" residually on EVERY turn regardless of how long the work took.
// The fix: when no nodes are in-flight, render the post-mortem total (latestEnd -
// earliestStart) instead of zero.
func TestAnimator_ResidualFrame_ShowsPostMortemElapsed(t *testing.T) {
	start := time.Unix(1_700_000_000, 0)
	end := start.Add(7 * time.Second)
	m := New()
	m = m.Update(SpanStartMsg{SpanID: "r", Name: "role:supervisor", Timestamp: start})
	m = m.Update(SpanEndMsg{SpanID: "r", Status: "success", Timestamp: end})

	out := stripANSI(m.View())
	if strings.Contains(out, "(0s)") {
		t.Errorf("animator must NOT show (0s) once the tree has ended — should display the post-mortem total. got: %q", out)
	}
	if !strings.Contains(out, "(7s") {
		t.Errorf("animator should show post-mortem total of 7s, got %q", out)
	}
}

// Sub-second turns previously rendered as "(0s)" because Truncate(time.Second)
// rounded down. The fix truncates to 100ms so short turns still show actual cost.
func TestAnimator_SubSecondTurn_ShowsHundredsOfMillis(t *testing.T) {
	start := time.Unix(1_700_000_000, 0)
	end := start.Add(450 * time.Millisecond)
	m := New()
	m = m.Update(SpanStartMsg{SpanID: "r", Name: "role:supervisor", Timestamp: start})
	m = m.Update(SpanEndMsg{SpanID: "r", Status: "success", Timestamp: end})

	out := stripANSI(m.View())
	if strings.Contains(out, "(0s)") {
		t.Errorf("sub-second turns must NOT round to 0s — got %q", out)
	}
	if !strings.Contains(out, "400ms") {
		t.Errorf("450ms should truncate to 400ms in the animator, got %q", out)
	}
}

func TestView_ErrorNode_UsesErrorSymbol(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()
	m = m.Update(SpanStartMsg{SpanID: "r", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "t", ParentID: "r", Name: "tool:bash_run", Timestamp: now})
	m = m.Update(SpanEndMsg{SpanID: "t", Status: "error",
		Attributes: map[string]any{"kukuvaia.error.message": "permission denied"}, Timestamp: now})
	m = m.Toggle()

	out := stripANSI(m.View())
	if !strings.Contains(out, "✗") {
		t.Errorf("error leaf should render ✗, got %q", out)
	}
	if !strings.Contains(out, "permission denied") {
		t.Errorf("error message should be surfaced, got %q", out)
	}
	if !strings.Contains(out, "1 error") {
		t.Errorf("animator should report error count, got %q", out)
	}
}

func TestView_DepthCap_TruncatesDeepTree(t *testing.T) {
	now := time.Unix(0, 0)
	cfg := DefaultConfig()
	cfg.MaxDepth = 2
	m := NewWithConfig(cfg)

	// Build a 4-deep chain: root → a → b → c
	m = m.Update(SpanStartMsg{SpanID: "root", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "a", ParentID: "root", Name: "role:worker", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "b", ParentID: "a", Name: "role:worker", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "c", ParentID: "b", Name: "tool:grep", Timestamp: now})
	m = m.Toggle()

	out := stripANSI(m.View())
	if !strings.Contains(out, "deeper") {
		t.Errorf("depth-capped output should contain 'deeper', got %q", out)
	}
}
