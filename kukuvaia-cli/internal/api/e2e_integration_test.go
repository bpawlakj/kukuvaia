//go:build integration

package api

import (
	"testing"
	"time"
)

func TestE2E_SessionsListAndDetail(t *testing.T) {
	client := NewClient(testServerURL)

	// List sessions
	sessions, err := client.ListSessions()
	if err != nil {
		t.Fatalf("ListSessions: %v", err)
	}
	t.Logf("Found %d sessions", len(sessions))

	// Get detail of first session (if any)
	if len(sessions) > 0 {
		info, err := client.GetSession(sessions[0].ID)
		if err != nil {
			t.Fatalf("GetSession: %v", err)
		}
		if info == nil {
			t.Fatal("expected session info, got nil")
		}
		t.Logf("Session %s: %d messages", info.SessionID, info.MessageCount)
	}

	// Get nonexistent session
	info, err := client.GetSession("nonexistent-session-999")
	if err != nil {
		t.Fatalf("GetSession nonexistent: %v", err)
	}
	if info != nil {
		t.Error("expected nil for nonexistent session")
	}
}

func TestE2E_SlashCommands(t *testing.T) {
	client := NewClient(testServerURL)

	// /help → should return TableBlock + TextBlock
	blocks, err := client.ExecuteCommand("help", "", "test-e2e", "")
	if err != nil {
		t.Fatalf("/help: %v", err)
	}
	if len(blocks) < 2 {
		t.Fatalf("/help: expected ≥2 blocks, got %d", len(blocks))
	}
	if _, ok := blocks[0].(TableBlock); !ok {
		t.Errorf("/help block[0]: expected TableBlock, got %T", blocks[0])
	}
	if tb, ok := blocks[1].(TextBlock); ok {
		t.Logf("/help tools: %s", tb.Content)
	}

	// /model list → should return TextBlock
	blocks, err = client.ExecuteCommand("model", "list", "test-e2e", "")
	if err != nil {
		t.Fatalf("/model list: %v", err)
	}
	if len(blocks) == 0 {
		t.Fatal("/model list: no blocks")
	}
	if tb, ok := blocks[0].(TextBlock); ok {
		t.Logf("/model: %s", tb.Content)
	}

	// /login status → should return TextBlock
	blocks, err = client.ExecuteCommand("login", "status", "test-e2e", "")
	if err != nil {
		t.Fatalf("/login status: %v", err)
	}
	if len(blocks) == 0 {
		t.Fatal("/login status: no blocks")
	}
	t.Logf("/login: got %d blocks", len(blocks))
}

func TestE2E_ChatWithToolUse(t *testing.T) {
	client := NewClient(testServerURL)

	// Ask agent to list its tools — tests that LLM responds with tool info
	blocks, errs := client.Chat("test-e2e-tools", "What tools do you have available? Just list their names briefly.", "")

	var received []OutputBlock
	timeout := time.After(90 * time.Second)
	for {
		select {
		case block, ok := <-blocks:
			if !ok {
				goto done
			}
			received = append(received, block)
		case err, ok := <-errs:
			if ok && err != nil {
				t.Fatalf("chat error: %v", err)
			}
		case <-timeout:
			t.Fatal("timeout")
		}
	}
done:

	if len(received) == 0 {
		t.Fatal("no blocks from chat")
	}
	if tb, ok := received[0].(TextBlock); ok {
		t.Logf("Tools response (%d chars): %.200s...", len(tb.Content), tb.Content)
	}
}

func TestE2E_DeleteSession(t *testing.T) {
	client := NewClient(testServerURL)

	// First create a session by chatting
	blocks, errs := client.Chat("test-e2e-delete-me", "hi", "")
	for range blocks {
	}
	for range errs {
	}

	// Delete it
	err := client.DeleteSession("test-e2e-delete-me")
	if err != nil {
		t.Fatalf("DeleteSession: %v", err)
	}

	// Verify gone
	info, err := client.GetSession("test-e2e-delete-me")
	if err != nil {
		t.Fatalf("GetSession after delete: %v", err)
	}
	if info != nil {
		t.Error("session should be gone after delete")
	}
	t.Log("Session deleted successfully")
}
