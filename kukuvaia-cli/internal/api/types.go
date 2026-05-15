package api

// ChatRequest is sent to POST /api/chat.
//
// Persona is the active persona name (resolved from KUKUVAIA_PERSONA env / --persona flag /
// ~/.kukuvaia.yaml). It is pushed on every request because the engine's persona binding lives
// in an in-memory per-session map — sending it on every turn means the binding survives engine
// restarts without any session-recovery dance. omitempty so legacy clients stay compatible.
type ChatRequest struct {
	SessionID string `json:"sessionId"`
	Message   string `json:"message"`
	Persona   string `json:"persona,omitempty"`
}

// CommandRequest is sent to POST /api/commands/{cmd}.
type CommandRequest struct {
	Args      string `json:"args"`
	SessionID string `json:"sessionId"`
	Persona   string `json:"persona,omitempty"`
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
