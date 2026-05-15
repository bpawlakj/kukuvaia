package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestExecuteCommand_PassesPersona spins up a stub HTTP server that captures the request body
// and asserts the persona makes it across the wire — the bug we fixed was that KUKUVAIA_PERSONA
// got read into cfg.Persona but never reached this layer.
func TestExecuteCommand_PassesPersona(t *testing.T) {
	var captured CommandRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &captured)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	client := NewClient(srv.URL)
	if _, err := client.ExecuteCommand("help", "", "session-1", "rule-editor"); err != nil {
		t.Fatalf("ExecuteCommand: %v", err)
	}

	if captured.Persona != "rule-editor" {
		t.Errorf("persona = %q, want %q", captured.Persona, "rule-editor")
	}
	if captured.SessionID != "session-1" {
		t.Errorf("sessionId = %q, want session-1", captured.SessionID)
	}
}

// TestExecuteCommand_OmitsEmptyPersona verifies omitempty — a default-persona CLI must NOT
// send a stray empty field (legacy server behavior must keep working).
func TestExecuteCommand_OmitsEmptyPersona(t *testing.T) {
	var rawBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		rawBody = string(body)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	client := NewClient(srv.URL)
	if _, err := client.ExecuteCommand("help", "", "session-1", ""); err != nil {
		t.Fatalf("ExecuteCommand: %v", err)
	}

	if strings.Contains(rawBody, "persona") {
		t.Errorf("body should not contain 'persona' field for empty value, got: %s", rawBody)
	}
}

// TestChat_PassesPersona — same contract for the SSE path.
func TestChat_PassesPersona(t *testing.T) {
	var captured ChatRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &captured)
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data:{\"content\":\"ok\",\"style\":null}\n\n"))
	}))
	defer srv.Close()

	client := NewClient(srv.URL)
	blocks, errs := client.Chat("session-1", "hi", "rule-editor")

	// Drain so the goroutine completes before assertions.
	for range blocks {
	}
	for range errs {
	}

	if captured.Persona != "rule-editor" {
		t.Errorf("persona = %q, want rule-editor", captured.Persona)
	}
	if captured.Message != "hi" {
		t.Errorf("message = %q, want hi", captured.Message)
	}
}
