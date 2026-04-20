package activity

import "time"

// SpanStartMsg is dispatched when a SpanEventBlock with phase "start" arrives.
type SpanStartMsg struct {
	SpanID     string
	ParentID   string
	Name       string
	Attributes map[string]any
	Timestamp  time.Time
}

// SpanEndMsg is dispatched when a SpanEventBlock with phase "end" arrives.
type SpanEndMsg struct {
	SpanID     string
	Status     string
	Attributes map[string]any
	Timestamp  time.Time
}

// SpanAttributeDeltaMsg carries an incremental token delta for a running span.
type SpanAttributeDeltaMsg struct {
	SpanID string
	Tokens int
}

// SseDisconnectedMsg signals that the SSE stream has dropped.
// Any in-flight spans are abandoned and the tree is hard-reset.
type SseDisconnectedMsg struct{}

// TickMsg drives spinner animation. Only issued while any span is in flight.
type TickMsg time.Time
