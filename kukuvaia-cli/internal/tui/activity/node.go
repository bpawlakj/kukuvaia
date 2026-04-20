package activity

import "time"

// Category groups a span by the kind of work it represents.
// Derived from the span name prefix: role:* / tool:* / llm:* / memory:*.
// Finer sub-categorisation of tools (search/read/write/command) happens in
// categorize.go.
type Category int

const (
	CategoryOther Category = iota
	CategoryRole
	CategoryLLM
	CategoryMemory
	CategorySearch
	CategoryRead
	CategoryWrite
	CategoryCommand
)

// SpanStatus tracks the lifecycle of a single span in the tree.
type SpanStatus int

const (
	StatusInFlight SpanStatus = iota
	StatusSuccess
	StatusError
	StatusCancelled
)

// SpanNode is a live node in the activity tree. Updated in place as events
// arrive. One SpanNode per unique span id on the wire.
type SpanNode struct {
	SpanID     string
	ParentID   string
	Name       string // "tool:grep" | "role:worker/research" | "llm:openai"
	Category   Category
	ChildIDs   []string
	StartedAt  time.Time  // sourced from SpanEventBlock.timestamp
	EndedAt    *time.Time // nil while in flight
	Status     SpanStatus
	Attributes map[string]any
	SubActions []string // ring buffer, cap maxSubActionsPerNode
}

// IsInFlight reports whether the node has not yet received an end event.
func (n *SpanNode) IsInFlight() bool {
	return n.EndedAt == nil
}
