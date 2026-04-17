package api

// ChatRequest is sent to POST /api/chat.
type ChatRequest struct {
	SessionID string `json:"sessionId"`
	Message   string `json:"message"`
}

// CommandRequest is sent to POST /api/commands/{cmd}.
type CommandRequest struct {
	Args      string `json:"args"`
	SessionID string `json:"sessionId"`
}

// SessionInfo is returned by GET /api/sessions (list) and GET /api/sessions/{id}.
type SessionInfo struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	MessageCount int    `json:"messageCount"`
	UpdatedAt    string `json:"updatedAt"`
	// Legacy field — GET /api/sessions/{id} returns "sessionId" instead of "id"
	SessionID string `json:"sessionId"`
}

// DisplayName returns the session name or a truncated ID if unnamed.
func (s SessionInfo) DisplayName() string {
	if s.Name != "" {
		return s.Name
	}
	id := s.ID
	if id == "" {
		id = s.SessionID
	}
	if len(id) > 20 {
		return id[:8] + "..." + id[len(id)-4:]
	}
	return id
}
