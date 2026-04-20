package activity

import (
	"testing"
	"time"
)

func TestFlatSequence_AssemblesAndKeepsTreeUntilNextTurn(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()

	m = m.Update(SpanStartMsg{SpanID: "root", ParentID: "", Name: "role:supervisor", Timestamp: now})
	if len(m.Roots()) != 1 {
		t.Fatalf("expected 1 root, got %d", len(m.Roots()))
	}
	if m.InFlightCount() != 1 {
		t.Fatalf("expected 1 in flight, got %d", m.InFlightCount())
	}

	m = m.Update(SpanStartMsg{SpanID: "tool1", ParentID: "root", Name: "tool:grep", Timestamp: now})
	if m.InFlightCount() != 2 {
		t.Fatalf("expected 2 in flight after child start, got %d", m.InFlightCount())
	}
	root := m.Nodes()["root"]
	if len(root.ChildIDs) != 1 || root.ChildIDs[0] != "tool1" {
		t.Fatalf("tool1 not attached as child of root: %+v", root.ChildIDs)
	}

	m = m.Update(SpanEndMsg{SpanID: "tool1", Status: "success", Timestamp: now})
	if m.Nodes()["tool1"].Status != StatusSuccess {
		t.Fatalf("tool1 status expected Success")
	}

	m = m.Update(SpanEndMsg{SpanID: "root", Status: "success", Timestamp: now})
	// Tree should stay visible after completion so the user can read the
	// final state on fast replies.
	if m.IsIdle() {
		t.Fatalf("tree should stay visible after top-level end")
	}
	if m.InFlightCount() != 0 {
		t.Fatalf("expected 0 in flight after all ended, got %d", m.InFlightCount())
	}

	// A new turn (top-level start) wipes the previous tree.
	m = m.Update(SpanStartMsg{SpanID: "root2", ParentID: "", Name: "role:supervisor", Timestamp: now})
	if _, stale := m.Nodes()["root"]; stale {
		t.Fatalf("previous turn's tree should be cleared when new top-level span arrives")
	}
	if _, fresh := m.Nodes()["root2"]; !fresh {
		t.Fatalf("new top-level span should be present after reset")
	}
}

func TestNested_SupervisorWithTwoWorkers(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()

	m = m.Update(SpanStartMsg{SpanID: "sup", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "w1", ParentID: "sup", Name: "role:worker/research", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "w2", ParentID: "sup", Name: "role:worker/code", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "t1", ParentID: "w1", Name: "tool:grep", Timestamp: now})

	sup := m.Nodes()["sup"]
	if len(sup.ChildIDs) != 2 {
		t.Fatalf("expected 2 children of supervisor, got %d", len(sup.ChildIDs))
	}
	w1 := m.Nodes()["w1"]
	if len(w1.ChildIDs) != 1 {
		t.Fatalf("expected 1 child of w1, got %d", len(w1.ChildIDs))
	}

	m = m.Update(SpanEndMsg{SpanID: "t1", Status: "success", Timestamp: now})
	m = m.Update(SpanEndMsg{SpanID: "w1", Status: "success", Timestamp: now})
	m = m.Update(SpanEndMsg{SpanID: "w2", Status: "success", Timestamp: now})
	if m.InFlightCount() != 1 {
		t.Fatalf("only supervisor should remain in flight, got %d", m.InFlightCount())
	}

	m = m.Update(SpanEndMsg{SpanID: "sup", Status: "success", Timestamp: now})
	if m.IsIdle() {
		t.Fatalf("tree should stay visible after top-level end (idle only on disconnect or new turn)")
	}
	if m.InFlightCount() != 0 {
		t.Fatalf("all spans should have ended, got %d in flight", m.InFlightCount())
	}
}

func TestOutOfOrder_OrphanPromotes(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()

	// Child arrives before parent.
	m = m.Update(SpanStartMsg{SpanID: "child", ParentID: "parent", Name: "tool:grep", Timestamp: now})
	if len(m.Nodes()) != 0 {
		t.Fatalf("child with unknown parent should be buffered as orphan, got %d nodes", len(m.Nodes()))
	}

	// Parent arrives — orphan should be promoted.
	m = m.Update(SpanStartMsg{SpanID: "parent", Name: "role:supervisor", Timestamp: now})
	if len(m.Nodes()) != 2 {
		t.Fatalf("expected 2 nodes after parent arrives, got %d", len(m.Nodes()))
	}
	parent := m.Nodes()["parent"]
	if len(parent.ChildIDs) != 1 || parent.ChildIDs[0] != "child" {
		t.Fatalf("child not attached after orphan promotion: %+v", parent.ChildIDs)
	}
}

func TestSseDisconnected_HardReset(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()

	m = m.Update(SpanStartMsg{SpanID: "sup", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanStartMsg{SpanID: "t1", ParentID: "sup", Name: "tool:grep", Timestamp: now})

	if m.IsIdle() {
		t.Fatalf("sanity: tree should not be idle before disconnect")
	}

	m = m.Update(SseDisconnectedMsg{})
	if !m.IsIdle() {
		t.Fatalf("hard reset should clear everything, got %d nodes", len(m.Nodes()))
	}
}

func TestCancelled_StatusMapping(t *testing.T) {
	now := time.Unix(0, 0)
	m := New()
	m = m.Update(SpanStartMsg{SpanID: "s", Name: "role:supervisor", Timestamp: now})
	m = m.Update(SpanEndMsg{SpanID: "s", Status: "cancelled", Timestamp: now})
	// Tree persists after end; verify the node got the cancelled status.
	node := m.Nodes()["s"]
	if node == nil {
		t.Fatalf("node should persist after end")
	}
	if node.Status != StatusCancelled {
		t.Fatalf("expected StatusCancelled, got %d", node.Status)
	}
}

func TestCategorize(t *testing.T) {
	tests := []struct {
		name string
		want Category
	}{
		{"role:supervisor", CategoryRole},
		{"tool:grep", CategorySearch},
		{"tool:read_file", CategoryRead},
		{"tool:bash_run", CategoryCommand},
		{"tool:saveMemory", CategoryWrite},
		{"tool:unknown_tool_xyz", CategoryOther},
		{"llm:openai", CategoryLLM},
		{"memory:inject", CategoryMemory},
		{"weird", CategoryOther},
	}
	for _, tt := range tests {
		if got := Categorize(tt.name); got != tt.want {
			t.Errorf("Categorize(%q) = %d; want %d", tt.name, got, tt.want)
		}
	}
}
