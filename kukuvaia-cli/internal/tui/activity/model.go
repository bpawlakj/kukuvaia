package activity

import (
	"time"
)

// Config controls per-instance caps. Defaults match values in the P01.1 plan.
type Config struct {
	MaxSubActionsCollapsed int
	MaxSubActionsExpanded  int
	MaxDepth               int
	OrphanTTL              time.Duration
}

// DefaultConfig returns the plan-recommended values.
func DefaultConfig() Config {
	return Config{
		MaxSubActionsCollapsed: 3,
		MaxSubActionsExpanded:  8,
		MaxDepth:               5, // Stage B plan decision D3 — raised from 3 to 5
		OrphanTTL:              time.Second,
	}
}

// Model is the activity tracker state. Mutation happens only in Update().
type Model struct {
	nodes    map[string]*SpanNode // spanId → node
	rootIDs  []string             // top-level spans (parentId == "")
	orphans  map[string]orphan    // spans whose parent has not yet arrived
	verb     string
	frame    int
	expanded bool
	cfg      Config
	styles   Styles
	width    int // current terminal width — set via WithWidth
}

type orphan struct {
	msg      SpanStartMsg
	received time.Time
}

// New returns an empty activity tracker with default config.
func New() Model {
	return NewWithConfig(DefaultConfig())
}

// NewWithConfig is used by tests to override timing.
func NewWithConfig(cfg Config) Model {
	return Model{
		nodes:   make(map[string]*SpanNode),
		rootIDs: []string{},
		orphans: make(map[string]orphan),
		cfg:     cfg,
		styles:  DefaultStyles(),
	}
}

// WithStyles returns a copy of the model with the supplied themed styles.
// Root TUI calls this once at startup with theme-sourced colours.
func (m Model) WithStyles(s Styles) Model {
	m.styles = s
	return m
}

// WithWidth updates the cached terminal width used for truncation.
func (m Model) WithWidth(w int) Model {
	m.width = w
	return m
}

// IsIdle reports whether the tree is empty (no nodes, no orphans).
// Root TUI uses this to hide the component.
func (m Model) IsIdle() bool {
	return len(m.nodes) == 0 && len(m.orphans) == 0
}

// InFlightCount returns the number of spans that have started but not yet ended.
// Used to decide whether to keep the spinner ticking.
func (m Model) InFlightCount() int {
	count := 0
	for _, n := range m.nodes {
		if n.IsInFlight() {
			count++
		}
	}
	return count
}

// Nodes returns a read-only view of the node map (for tests and rendering).
func (m Model) Nodes() map[string]*SpanNode {
	return m.nodes
}

// Roots returns the current top-level span ids in insertion order.
func (m Model) Roots() []string {
	return m.rootIDs
}

// Verb returns the current spinner verb (empty until first SpanStartMsg).
func (m Model) Verb() string {
	return m.verb
}

// Expanded reports whether the user has toggled the expanded view.
func (m Model) Expanded() bool {
	return m.expanded
}

// Config returns the active configuration.
func (m Model) Config() Config {
	return m.cfg
}
