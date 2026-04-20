package activity

import (
	"fmt"
	"strings"
	"time"
)

const (
	bulletActive = "●"
	bulletError  = "✗"
	symInFlight  = "…"
	symOK        = "✓"
	symError     = "✗"
	symCancel    = "⊘"
	connMid      = "├─"
	connLast     = "└─"
	connVert     = "│ "
	connSpace    = "  "
	ellipsis     = "…"
)

// spinnerFrames is the animator glyph sequence cycled by TickMsg.
// Braille dots — smooth 10-step rotation, matches Matrix terminal aesthetic.
var spinnerFrames = []string{"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"}

// spinnerFrame picks the glyph for the current tick count. When the tree has
// no in-flight spans, the spinner freezes on the last frame so completed trees
// render with a static glyph (matches claude-code convention).
func spinnerFrame(frame int, hasInFlight bool) string {
	if !hasInFlight {
		return "∘"
	}
	return spinnerFrames[frame%len(spinnerFrames)]
}

// View renders the activity tracker.
// Idle tree returns "" so the root TUI can hide the component entirely.
// Collapsed: two lines — a summary bullet and the animator.
// Expanded: nested tree with box-drawing plus the animator.
func (m Model) View() string {
	if m.IsIdle() {
		return ""
	}
	if m.expanded {
		return m.renderExpanded()
	}
	return m.renderCollapsed()
}

// ─── collapsed ────────────────────────────────────────────────────────────

func (m Model) renderCollapsed() string {
	s := m.styles
	var b strings.Builder
	b.WriteString(m.rootBullet() + " ")
	b.WriteString(m.topLevelSummary())
	b.WriteString("  ")
	b.WriteString(s.Hint.Render("(ctrl+o to expand)"))
	b.WriteString("\n")
	b.WriteString(m.animatorLine())
	return b.String()
}

// ─── expanded ─────────────────────────────────────────────────────────────

func (m Model) renderExpanded() string {
	s := m.styles
	var b strings.Builder
	b.WriteString(m.rootBullet() + " ")
	b.WriteString(m.topLevelSummary())
	b.WriteString("  ")
	b.WriteString(s.Hint.Render("(ctrl+o to collapse)"))
	b.WriteString("\n")

	// Render every root as a tree node, not just its children. With the engine
	// currently emitting tool/llm spans as siblings of role:supervisor, many
	// "trees" are flat lists of roots — we still want the user to see them.
	for i, rid := range m.rootIDs {
		root := m.nodes[rid]
		if root == nil {
			continue
		}
		isLast := i == len(m.rootIDs)-1
		conn := connMid
		childPrefix := connVert
		if isLast {
			conn = connLast
			childPrefix = connSpace
		}
		b.WriteString(s.Tree.Render(conn + " "))
		b.WriteString(m.renderNodeLine(root))
		b.WriteString("\n")
		m.renderSubActions(&b, root, childPrefix)
		if len(root.ChildIDs) > 0 {
			m.renderChildren(&b, root, childPrefix, 1)
		}
	}

	b.WriteString("\n")
	b.WriteString(m.animatorLine())
	return b.String()
}

// renderChildren emits the children of `node` under the supplied prefix.
// `depth` tracks how deep we already are so the MaxDepth cap can kick in.
func (m Model) renderChildren(b *strings.Builder, node *SpanNode, prefix string, depth int) {
	kids := node.ChildIDs
	for i, cid := range kids {
		child := m.nodes[cid]
		if child == nil {
			continue
		}
		isLast := i == len(kids)-1
		conn := connMid
		childPrefix := prefix + connVert
		if isLast {
			conn = connLast
			childPrefix = prefix + connSpace
		}

		// Depth cap — replace deeper subtree with a summary line.
		if depth+1 > m.cfg.MaxDepth {
			remaining := countDescendants(m.nodes, cid)
			b.WriteString(prefix)
			b.WriteString(m.styles.Tree.Render(conn + " "))
			b.WriteString(m.styles.Meta.Render(fmt.Sprintf("%s +%d deeper", ellipsis, remaining)))
			b.WriteString("\n")
			return
		}

		b.WriteString(prefix)
		b.WriteString(m.styles.Tree.Render(conn + " "))
		b.WriteString(m.renderNodeLine(child))
		b.WriteString("\n")

		// Show subActions under leaves and branches alike, indented by child prefix.
		m.renderSubActions(b, child, childPrefix)

		if len(child.ChildIDs) > 0 {
			m.renderChildren(b, child, childPrefix, depth+1)
		}
	}
}

// renderNodeLine renders a single node line — the status-coloured symbol +
// category-aware label + any inline error message.
func (m Model) renderNodeLine(n *SpanNode) string {
	s := m.styles
	sym, style := m.statusSymbolFor(n)
	label := strings.TrimPrefix(n.Name, "role:")
	label = strings.TrimPrefix(label, "tool:")
	label = strings.TrimPrefix(label, "llm:")
	label = strings.TrimPrefix(label, "memory:")

	var sb strings.Builder
	sb.WriteString(style.Render(sym + " "))

	switch n.Category {
	case CategoryRole:
		branchSummary := SummarizeBranch(m.nodes, n.SpanID)
		sb.WriteString(s.Header.Render(label))
		if decision := routingBadge(n); decision != "" {
			sb.WriteString(" ")
			sb.WriteString(s.Meta.Render(decision))
		}
		sb.WriteString(" ")
		sb.WriteString(s.Meta.Render(branchSummary))
	default:
		sb.WriteString(label)
		// For leaf tool nodes, show the primary argument preview inline in
		// muted colour — "grep  pattern=foo" is more useful than just "grep".
		if preview, ok := n.Attributes["kukuvaia.summary"].(string); ok && preview != "" {
			sb.WriteString(" ")
			sb.WriteString(s.Meta.Render(truncate(preview, 50)))
		}
	}

	// Error message on a leaf gets a second-colour suffix with error type prefix.
	if n.Status == StatusError {
		msg, _ := n.Attributes["kukuvaia.error.message"].(string)
		errType, _ := n.Attributes["kukuvaia.error.type"].(string)
		if msg != "" || errType != "" {
			sb.WriteString("  ")
			errLine := ""
			if errType != "" {
				errLine = errType
			}
			if msg != "" {
				if errLine != "" {
					errLine += ": "
				}
				errLine += truncate(msg, 80)
			}
			sb.WriteString(s.ErrorMsg.Render(errLine))
		}
	}

	// Per-node timing + token counters (muted).
	meta := nodeMeta(n)
	if meta != "" {
		sb.WriteString("  ")
		sb.WriteString(s.Meta.Render(meta))
	}
	return sb.String()
}

// renderSubActions emits any sub-action strings under a node, capped by the
// active collapsed/expanded limit.
func (m Model) renderSubActions(b *strings.Builder, node *SpanNode, prefix string) {
	if len(node.SubActions) == 0 {
		return
	}
	limit := m.cfg.MaxSubActionsCollapsed
	if m.expanded {
		limit = m.cfg.MaxSubActionsExpanded
	}
	for i, action := range node.SubActions {
		if i >= limit {
			b.WriteString(prefix)
			b.WriteString(m.styles.Meta.Render(fmt.Sprintf("… +%d more", len(node.SubActions)-limit)))
			b.WriteString("\n")
			return
		}
		b.WriteString(prefix)
		b.WriteString(m.styles.Tree.Render("└ "))
		b.WriteString(m.styles.Meta.Render(truncate(action, 100)))
		b.WriteString("\n")
	}
}

// ─── shared pieces ────────────────────────────────────────────────────────

// rootBullet picks the top-level symbol — ● normally, ✗ when the whole tree
// carries at least one unresolved error propagated to a root-level span.
func (m Model) rootBullet() string {
	// Per plan: errors at leaves do NOT change parent shape — animator carries
	// the error count. Only flip the bullet if a root span itself errored.
	for _, rid := range m.rootIDs {
		if n, ok := m.nodes[rid]; ok && n.Status == StatusError {
			return m.styles.HeaderError.Render(bulletError)
		}
	}
	return m.styles.Header.Render(bulletActive)
}

// topLevelSummary describes what the whole tree is doing in one phrase.
func (m Model) topLevelSummary() string {
	if len(m.rootIDs) == 0 {
		return m.styles.Meta.Render("waiting…")
	}
	// Multi-root → orchestration frame.
	if len(m.rootIDs) > 1 {
		return m.styles.Header.Render(fmt.Sprintf("orchestrating %d %s",
			len(m.rootIDs), plural(len(m.rootIDs), "stream")))
	}
	root := m.nodes[m.rootIDs[0]]
	label := strings.TrimPrefix(root.Name, "role:")
	label = strings.TrimPrefix(label, "tool:")
	summary := SummarizeBranch(m.nodes, root.SpanID)
	if summary == "idle" {
		return m.styles.Header.Render(label)
	}
	return m.styles.Header.Render(label) + m.styles.Meta.Render(": "+summary)
}

// animatorLine draws the spinner-like bottom line with verb + totals.
func (m Model) animatorLine() string {
	s := m.styles
	verb := m.verb
	if verb == "" {
		verb = "Working"
	}

	// Elapsed wall-clock from earliest in-flight node.
	var earliest *time.Time
	for _, n := range m.nodes {
		if n.IsInFlight() {
			if earliest == nil || n.StartedAt.Before(*earliest) {
				t := n.StartedAt
				earliest = &t
			}
		}
	}
	elapsed := time.Duration(0)
	if earliest != nil {
		elapsed = time.Since(*earliest).Truncate(time.Second)
	}

	tokens := TotalTokens(m.nodes)
	errors := TotalErrors(m.nodes)

	var b strings.Builder
	b.WriteString("  ")
	glyph := spinnerFrame(m.frame, m.InFlightCount() > 0)
	b.WriteString(s.Verb.Render(glyph + " " + verb))
	b.WriteString(" ")
	b.WriteString(s.Meta.Render(fmt.Sprintf("(%s", elapsed)))
	if tokens > 0 {
		b.WriteString(s.Meta.Render(fmt.Sprintf(" · ↑ %d tokens", tokens)))
	}
	if errors > 0 {
		b.WriteString(" ")
		b.WriteString(s.ErrorMsg.Render(fmt.Sprintf("· %d %s", errors, plural(errors, "error"))))
		b.WriteString(s.Meta.Render(")"))
	} else {
		b.WriteString(s.Meta.Render(")"))
	}
	return b.String()
}

// statusSymbolFor returns the glyph and the style to paint it with.
func (m Model) statusSymbolFor(n *SpanNode) (string, lipglossStyle) {
	s := m.styles
	switch n.Status {
	case StatusSuccess:
		return symOK, s.StatusOK
	case StatusError:
		return symError, s.StatusError
	case StatusCancelled:
		return symCancel, s.StatusCancel
	}
	return symInFlight, s.StatusActive
}

// lipglossStyle is a local alias so we can pass a style value around without
// importing lipgloss in every call site.
type lipglossStyle = interface {
	Render(strs ...string) string
}

// nodeMeta returns per-node meta annotation (duration only).
// The kukuvaia.summary attribute is rendered INLINE next to the tool name in
// renderNodeLine — showing it here again would duplicate the preview.
func nodeMeta(n *SpanNode) string {
	if n.IsInFlight() || n.EndedAt == nil {
		return ""
	}
	d := n.EndedAt.Sub(n.StartedAt).Truncate(time.Millisecond)
	return "(" + d.String() + ")"
}

// routingBadge formats the routing decision + model for a supervisor node,
// e.g. "[ESCALATE → opus]" or "[FAST → haiku]". Empty string if the engine
// did not set routing attributes on this span (no override applied).
func routingBadge(n *SpanNode) string {
	if n == nil {
		return ""
	}
	decision, _ := n.Attributes["kukuvaia.routing.decision"].(string)
	model, _ := n.Attributes["kukuvaia.routing.model"].(string)
	if decision == "" && model == "" {
		return ""
	}
	// Skip the default tier — it is the common case, no need to clutter the UI.
	if decision == "DEFAULT" {
		return ""
	}
	if model != "" {
		return "[" + decision + " → " + model + "]"
	}
	return "[" + decision + "]"
}

// countDescendants returns the total number of nodes rooted at id.
func countDescendants(nodes map[string]*SpanNode, id string) int {
	n, ok := nodes[id]
	if !ok {
		return 0
	}
	count := 1
	for _, cid := range n.ChildIDs {
		count += countDescendants(nodes, cid)
	}
	return count
}

// truncate shortens text to at most n visible runes, appending the ellipsis.
func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n-1] + ellipsis
}
