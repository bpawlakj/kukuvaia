//go:build integration

package api

import (
	"testing"
)

const testServerURL = "http://localhost:8080"

func TestListSessions_Integration(t *testing.T) {
	client := NewClient(testServerURL)
	sessions, err := client.ListSessions()
	if err != nil {
		t.Fatalf("ListSessions failed: %v", err)
	}
	t.Logf("Found %d sessions", len(sessions))
	for _, s := range sessions {
		t.Logf("  %s — %q (%d msgs)", s.ID, s.DisplayName(), s.MessageCount)
	}
}

func TestRenameSession_Integration(t *testing.T) {
	client := NewClient(testServerURL)

	// Rename a session
	err := client.RenameSession("test-memory-session", "Memory Runtime Test")
	if err != nil {
		t.Fatalf("RenameSession failed: %v", err)
	}

	// Verify
	sessions, _ := client.ListSessions()
	for _, s := range sessions {
		if s.ID == "test-memory-session" {
			if s.Name != "Memory Runtime Test" {
				t.Errorf("name = %q, want %q", s.Name, "Memory Runtime Test")
			}
			t.Logf("Rename verified: %s → %q", s.ID, s.Name)
			return
		}
	}
	t.Error("session not found after rename")
}

func TestExecuteCommand_Help_Integration(t *testing.T) {
	client := NewClient(testServerURL)
	blocks, err := client.ExecuteCommand("help", "", "test-integration")
	if err != nil {
		t.Fatalf("ExecuteCommand help failed: %v", err)
	}
	if len(blocks) == 0 {
		t.Fatal("expected at least one block from /help")
	}
	if _, ok := blocks[0].(TableBlock); !ok {
		t.Errorf("expected first block to be TableBlock, got %T", blocks[0])
	}
	t.Logf("Got %d blocks from /help", len(blocks))
}
