//go:build e2e

package api

import (
	"testing"
	"time"

	"github.com/kukuvaia/kukuvaia-cli/internal/tui/activity"
)

// Run with: go test -tags e2e ./internal/api/ -run E2EStageC -v
// Requires engine running on http://localhost:8080.
func TestE2EStageC_SpanStream(t *testing.T) {
	c := NewClient("http://localhost:8080")
	am := activity.New()
	blocks, errs := c.Chat("stage-c-e2e", "użyj searchMemories aby znaleźć moje notatki")

	var textCount, spanCount int
	done := false
	for !done {
		select {
		case b, ok := <-blocks:
			if !ok {
				done = true
				continue
			}
			switch v := b.(type) {
			case SpanEventBlock:
				spanCount++
				ts, _ := time.Parse(time.RFC3339Nano, v.Timestamp)
				switch v.Phase {
				case "start":
					am = am.Update(activity.SpanStartMsg{
						SpanID: v.SpanID, ParentID: v.ParentSpanID,
						Name: v.Name, Attributes: v.Attributes, Timestamp: ts,
					})
				case "end":
					status, _ := v.Attributes["kukuvaia.status"].(string)
					am = am.Update(activity.SpanEndMsg{
						SpanID: v.SpanID, Status: status, Timestamp: ts,
					})
				}
				t.Logf("[SPAN] %s %s parent=%q", v.Phase, v.Name, v.ParentSpanID)
			case TextBlock:
				textCount++
				t.Logf("[TEXT] %.80s", v.Content)
			}
		case e, ok := <-errs:
			if !ok {
				errs = nil
				continue
			}
			if e != nil {
				t.Fatalf("[ERR] %v", e)
			}
		}
	}
	t.Logf("stats: spanEvents=%d textBlocks=%d idleAtEnd=%v", spanCount, textCount, am.IsIdle())
	if spanCount < 2 {
		t.Fatalf("expected >=2 span events (supervisor start+end), got %d", spanCount)
	}
	if textCount < 1 {
		t.Fatalf("expected >=1 text block, got %d", textCount)
	}
	if !am.IsIdle() {
		t.Fatalf("activity model should be idle after stream end")
	}
}
