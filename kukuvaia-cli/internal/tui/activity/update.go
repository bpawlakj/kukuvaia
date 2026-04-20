package activity

import (
	"math/rand"
	"time"
)

// Update processes an activity-related message and returns the new model.
// Caller is responsible for dispatching the right message types — any other
// Msg is returned unchanged.
func (m Model) Update(msg any) Model {
	switch msg := msg.(type) {
	case SpanStartMsg:
		return m.handleStart(msg)
	case SpanEndMsg:
		return m.handleEnd(msg)
	case SpanAttributeDeltaMsg:
		return m.handleDelta(msg)
	case SseDisconnectedMsg:
		return m.hardReset()
	case TickMsg:
		m.frame++
		return m
	case toggleExpandMsg:
		m.expanded = !m.expanded
		return m
	}
	return m
}

// Toggle switches expanded view on/off. Shorthand for dispatching the internal msg.
func (m Model) Toggle() Model {
	return m.Update(toggleExpandMsg{})
}

type toggleExpandMsg struct{}

func (m Model) handleStart(msg SpanStartMsg) Model {
	// A new top-level span means a new chat turn — clear the stale tree from
	// the previous turn so the user sees the current activity, not a frozen
	// "completed" state from the last reply.
	if msg.ParentID == "" && len(m.nodes) > 0 && m.InFlightCount() == 0 {
		m = m.softReset()
	}

	// If parent is set but unknown, buffer as orphan.
	if msg.ParentID != "" {
		if _, ok := m.nodes[msg.ParentID]; !ok {
			m.orphans[msg.SpanID] = orphan{msg: msg, received: time.Now()}
			m.gcOrphans()
			return m
		}
	}

	node := &SpanNode{
		SpanID:     msg.SpanID,
		ParentID:   msg.ParentID,
		Name:       msg.Name,
		Category:   Categorize(msg.Name),
		StartedAt:  msg.Timestamp,
		Status:     StatusInFlight,
		Attributes: copyAttrs(msg.Attributes),
	}
	m.nodes[msg.SpanID] = node

	if msg.ParentID == "" {
		m.rootIDs = append(m.rootIDs, msg.SpanID)
	} else {
		parent := m.nodes[msg.ParentID]
		parent.ChildIDs = append(parent.ChildIDs, msg.SpanID)
	}

	if m.verb == "" {
		m.verb = pickVerb()
	}

	// Try to attach any orphans now that this node might be their parent.
	m = m.promoteOrphans()
	m.gcOrphans()
	return m
}

func (m Model) handleEnd(msg SpanEndMsg) Model {
	node, ok := m.nodes[msg.SpanID]
	if !ok {
		// End for an unknown span — usually a race after a hard reset. Ignore.
		return m
	}
	endTime := msg.Timestamp
	node.EndedAt = &endTime
	node.Status = parseStatus(msg.Status)
	mergeAttrs(node.Attributes, msg.Attributes)
	// Tree stays visible until the next turn arrives — see handleStart.
	// This gives the user time to read the final state on fast replies.
	return m
}

func (m Model) handleDelta(msg SpanAttributeDeltaMsg) Model {
	node, ok := m.nodes[msg.SpanID]
	if !ok {
		return m
	}
	if node.Attributes == nil {
		node.Attributes = make(map[string]any)
	}
	prev, _ := node.Attributes["kukuvaia.tokens.cumulative"].(int)
	node.Attributes["kukuvaia.tokens.cumulative"] = prev + msg.Tokens
	return m
}

// softReset clears the tree after a top-level span has cleanly ended.
// Keeps orphans cache for any in-flight cross-turn events (rare).
func (m Model) softReset() Model {
	m.nodes = make(map[string]*SpanNode)
	m.rootIDs = m.rootIDs[:0]
	m.verb = ""
	m.frame = 0
	return m
}

// hardReset discards everything. Triggered on SSE disconnect — any spans that
// were in flight are abandoned; the engine will not resume them.
func (m Model) hardReset() Model {
	m.nodes = make(map[string]*SpanNode)
	m.rootIDs = []string{}
	m.orphans = make(map[string]orphan)
	m.verb = ""
	m.frame = 0
	return m
}

// promoteOrphans attaches any buffered orphan whose parent is now present.
// Cascades when newly-promoted nodes themselves unblock further orphans.
func (m Model) promoteOrphans() Model {
	if len(m.orphans) == 0 {
		return m
	}
	changed := true
	for changed {
		changed = false
		for id, o := range m.orphans {
			if _, ok := m.nodes[o.msg.ParentID]; !ok {
				continue
			}
			delete(m.orphans, id)
			m = m.handleStart(o.msg)
			changed = true
			break // restart iteration — map mutated
		}
	}
	return m
}

// gcOrphans drops orphan entries older than the configured TTL.
func (m Model) gcOrphans() {
	if len(m.orphans) == 0 {
		return
	}
	cutoff := time.Now().Add(-m.cfg.OrphanTTL)
	for id, o := range m.orphans {
		if o.received.Before(cutoff) {
			delete(m.orphans, id)
		}
	}
}

func pickVerb() string {
	return Verbs[rand.Intn(len(Verbs))]
}

func parseStatus(s string) SpanStatus {
	switch s {
	case "success":
		return StatusSuccess
	case "error":
		return StatusError
	case "cancelled":
		return StatusCancelled
	}
	return StatusInFlight
}

func copyAttrs(in map[string]any) map[string]any {
	if len(in) == 0 {
		return map[string]any{}
	}
	out := make(map[string]any, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

func mergeAttrs(dst, src map[string]any) {
	for k, v := range src {
		dst[k] = v
	}
}
