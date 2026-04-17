package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client communicates with kukuvaia-engine REST API.
type Client struct {
	BaseURL    string
	HTTPClient *http.Client
}

// NewClient creates an API client for the given server URL.
func NewClient(baseURL string) *Client {
	return &Client{
		BaseURL: baseURL,
		HTTPClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// ListSessions returns all sessions with metadata.
func (c *Client) ListSessions() ([]SessionInfo, error) {
	resp, err := c.HTTPClient.Get(c.BaseURL + "/api/sessions")
	if err != nil {
		return nil, fmt.Errorf("list sessions: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("list sessions: HTTP %d", resp.StatusCode)
	}

	var sessions []SessionInfo
	if err := json.NewDecoder(resp.Body).Decode(&sessions); err != nil {
		return nil, fmt.Errorf("list sessions: decode: %w", err)
	}
	return sessions, nil
}

// RenameSession sets a new name for a session.
func (c *Client) RenameSession(id, name string) error {
	body, _ := json.Marshal(map[string]string{"name": name})
	req, err := http.NewRequest(http.MethodPut, c.BaseURL+"/api/sessions/"+id+"/name", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("rename session: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("rename session: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("rename session: HTTP %d", resp.StatusCode)
	}
	return nil
}

// GetSession returns details for a specific session.
func (c *Client) GetSession(id string) (*SessionInfo, error) {
	resp, err := c.HTTPClient.Get(c.BaseURL + "/api/sessions/" + id)
	if err != nil {
		return nil, fmt.Errorf("get session: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get session: HTTP %d", resp.StatusCode)
	}

	var info SessionInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return nil, fmt.Errorf("get session: decode: %w", err)
	}
	return &info, nil
}

// DeleteSession removes a session and its history.
func (c *Client) DeleteSession(id string) error {
	req, err := http.NewRequest(http.MethodDelete, c.BaseURL+"/api/sessions/"+id, nil)
	if err != nil {
		return fmt.Errorf("delete session: %w", err)
	}
	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("delete session: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("delete session: HTTP %d", resp.StatusCode)
	}
	return nil
}

// ExecuteCommand sends a slash command to the server and returns the response blocks.
// Commands return a JSON array (not SSE).
func (c *Client) ExecuteCommand(cmd, args, sessionID string) ([]OutputBlock, error) {
	body, _ := json.Marshal(CommandRequest{Args: args, SessionID: sessionID})
	resp, err := c.HTTPClient.Post(
		c.BaseURL+"/api/commands/"+cmd,
		"application/json",
		bytes.NewReader(body),
	)
	if err != nil {
		return nil, fmt.Errorf("command %s: %w", cmd, err)
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("command %s: read body: %w", cmd, err)
	}

	// Response is a JSON array of OutputBlock objects
	var rawBlocks []json.RawMessage
	if err := json.Unmarshal(data, &rawBlocks); err != nil {
		return nil, fmt.Errorf("command %s: decode: %w", cmd, err)
	}

	blocks := make([]OutputBlock, 0, len(rawBlocks))
	for _, raw := range rawBlocks {
		block, err := ParseOutputBlock(raw)
		if err != nil {
			continue
		}
		blocks = append(blocks, block)
	}
	return blocks, nil
}
