package activity

import (
	"fmt"
	"sort"
	"strings"
)

// CategoryCounts rolls up leaf activity types across a node's descendants.
type CategoryCounts struct {
	Search  int
	Read    int
	Write   int
	Command int
	LLM     int
	Memory  int
	Other   int
	Errors  int // leaves that ended with error status
}

// AggregateDescendants walks the subtree rooted at nodeID and returns leaf
// category counts plus error count. Role-category nodes are NOT counted as
// leaves — they are branches.
func AggregateDescendants(nodes map[string]*SpanNode, nodeID string) CategoryCounts {
	var c CategoryCounts
	visit(nodes, nodeID, &c, false)
	return c
}

// visit recurses through children. isRoot prevents counting the entry node
// itself as a leaf.
func visit(nodes map[string]*SpanNode, id string, c *CategoryCounts, countSelf bool) {
	n, ok := nodes[id]
	if !ok {
		return
	}
	if countSelf && n.Category != CategoryRole {
		switch n.Category {
		case CategorySearch:
			c.Search++
		case CategoryRead:
			c.Read++
		case CategoryWrite:
			c.Write++
		case CategoryCommand:
			c.Command++
		case CategoryLLM:
			c.LLM++
		case CategoryMemory:
			c.Memory++
		default:
			c.Other++
		}
		if n.Status == StatusError {
			c.Errors++
		}
	}
	for _, cid := range n.ChildIDs {
		visit(nodes, cid, c, true)
	}
}

// SummarizeBranch builds a human-readable activity phrase for a branch node.
// Used both for collapsed root line and for individual role branches.
//
// Examples (English base wording — theming is separate from localisation):
//
//	"searching 3 patterns · reading 2 files"
//	"editing 1 file · running 1 command"
//	"orchestrating 2 workers"
//	"idle"
func SummarizeBranch(nodes map[string]*SpanNode, nodeID string) string {
	n, ok := nodes[nodeID]
	if !ok {
		return ""
	}

	// If this is a role node with role children, summarise as orchestration.
	if n.Category == CategoryRole {
		roleKids := 0
		for _, cid := range n.ChildIDs {
			if child, ok := nodes[cid]; ok && child.Category == CategoryRole {
				roleKids++
			}
		}
		if roleKids > 0 {
			return fmt.Sprintf("orchestrating %d %s", roleKids, plural(roleKids, "worker"))
		}
	}

	counts := AggregateDescendants(nodes, nodeID)
	return summaryFromCounts(counts)
}

// summaryFromCounts builds the bullet string. Order kept stable for UX.
func summaryFromCounts(c CategoryCounts) string {
	var parts []string
	if c.Search > 0 {
		parts = append(parts, fmt.Sprintf("searching %d %s", c.Search, plural(c.Search, "pattern")))
	}
	if c.Read > 0 {
		parts = append(parts, fmt.Sprintf("reading %d %s", c.Read, plural(c.Read, "file")))
	}
	if c.Write > 0 {
		parts = append(parts, fmt.Sprintf("writing %d %s", c.Write, plural(c.Write, "file")))
	}
	if c.Command > 0 {
		parts = append(parts, fmt.Sprintf("running %d %s", c.Command, plural(c.Command, "command")))
	}
	if c.Memory > 0 {
		parts = append(parts, fmt.Sprintf("memory ops %d", c.Memory))
	}
	if c.LLM > 0 {
		parts = append(parts, fmt.Sprintf("%d llm %s", c.LLM, plural(c.LLM, "call")))
	}
	if c.Other > 0 {
		parts = append(parts, fmt.Sprintf("%d %s", c.Other, plural(c.Other, "task")))
	}
	if len(parts) == 0 {
		return "idle"
	}
	return strings.Join(parts, " · ")
}

// TotalTokens sums any cumulative token counts seen on any node's attributes.
// Engine sets kukuvaia.tokens.cumulative via delta messages; extraction reads
// whatever number is there, tolerating float64 from JSON unmarshaling.
func TotalTokens(nodes map[string]*SpanNode) int {
	total := 0
	for _, n := range nodes {
		if v, ok := n.Attributes["kukuvaia.tokens.cumulative"]; ok {
			switch x := v.(type) {
			case int:
				total += x
			case float64:
				total += int(x)
			}
		}
	}
	return total
}

// TotalErrors counts nodes that ended with StatusError.
func TotalErrors(nodes map[string]*SpanNode) int {
	n := 0
	for _, node := range nodes {
		if node.Status == StatusError {
			n++
		}
	}
	return n
}

// rootIDsSorted is a small helper kept private — preserves insertion order
// in the public Roots() slice but ensures deterministic iteration where
// rendering calls for it.
func rootIDsSorted(ids []string) []string {
	out := make([]string, len(ids))
	copy(out, ids)
	sort.Strings(out)
	return out
}

func plural(n int, singular string) string {
	if n == 1 {
		return singular
	}
	// Simple English pluralisation — enough for the small label set.
	return singular + "s"
}
