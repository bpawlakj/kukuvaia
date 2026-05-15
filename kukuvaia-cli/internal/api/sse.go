package api

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
)

// Chat sends a message via SSE and returns channels for blocks and errors.
// The blocks channel is closed when the stream ends.
// persona is forwarded so the engine (re)binds the session to that persona before routing —
// this is what makes KUKUVAIA_PERSONA=rule-editor actually shape the LLM tool whitelist.
func (c *Client) Chat(sessionID, message, persona string) (<-chan OutputBlock, <-chan error) {
	blocks := make(chan OutputBlock, 8)
	errs := make(chan error, 1)

	go func() {
		defer close(blocks)
		defer close(errs)

		log.Printf("[SSE] Chat request: sessionId=%s, persona=%q, msgLen=%d", sessionID, persona, len(message))
		body, _ := json.Marshal(ChatRequest{SessionID: sessionID, Message: message, Persona: persona})
		req, err := http.NewRequest(http.MethodPost, c.BaseURL+"/api/chat", bytes.NewReader(body))
		if err != nil {
			errs <- fmt.Errorf("chat: create request: %w", err)
			return
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "text/event-stream")

		// No timeout for SSE — responses can take a while (LLM thinking + tool calls)
		sseClient := &http.Client{}
		resp, err := sseClient.Do(req)
		if err != nil {
			errs <- fmt.Errorf("chat: request: %w", err)
			return
		}
		defer resp.Body.Close()

		log.Printf("[SSE] HTTP status: %d", resp.StatusCode)
		if resp.StatusCode != http.StatusOK {
			errs <- fmt.Errorf("chat: HTTP %d", resp.StatusCode)
			return
		}

		scanner := bufio.NewScanner(resp.Body)
		blockCount := 0
		for scanner.Scan() {
			line := scanner.Text()

			// SSE format: "data:{json}" (no space after colon, confirmed from server)
			if !strings.HasPrefix(line, "data:") {
				continue
			}

			payload := strings.TrimPrefix(line, "data:")
			payload = strings.TrimSpace(payload)
			if payload == "" {
				continue
			}

			block, err := ParseOutputBlock([]byte(payload))
			if err != nil {
				log.Printf("[SSE] parse error: %v, payload: %.100s", err, payload)
				continue // skip unparseable events
			}
			blockCount++
			log.Printf("[SSE] block[%d]: type=%T, payload=%.80s", blockCount, block, payload)
			blocks <- block
		}

		if err := scanner.Err(); err != nil {
			log.Printf("[SSE] stream error: %v", err)
			errs <- fmt.Errorf("chat: read stream: %w", err)
		}
		log.Printf("[SSE] stream ended, total blocks=%d", blockCount)
	}()

	return blocks, errs
}
