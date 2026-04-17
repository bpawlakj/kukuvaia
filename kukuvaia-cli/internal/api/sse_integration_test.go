//go:build integration

package api

import (
	"testing"
	"time"
)

func TestChat_Integration(t *testing.T) {
	client := NewClient(testServerURL)
	blocks, errs := client.Chat("test-cli-sse", "Say hello in one word")

	var received []OutputBlock
	timeout := time.After(90 * time.Second)

	for {
		select {
		case block, ok := <-blocks:
			if !ok {
				goto done
			}
			received = append(received, block)
			t.Logf("Received block: %T", block)
		case err, ok := <-errs:
			if ok && err != nil {
				t.Fatalf("SSE error: %v", err)
			}
		case <-timeout:
			t.Fatal("timeout waiting for SSE response")
		}
	}

done:
	if len(received) == 0 {
		t.Fatal("expected at least one block from chat")
	}
	if tb, ok := received[0].(TextBlock); ok {
		t.Logf("Response: %s", tb.Content)
	}
}
