package api

import (
	"encoding/json"
	"fmt"
)

// OutputBlock is the sealed interface for server response blocks.
// The server sends no type discriminator — type is inferred from field presence.
type OutputBlock interface {
	blockType() string
}

// TextBlock — general text/markdown response.
type TextBlock struct {
	Content string  `json:"content"`
	Style   *string `json:"style"`
}

func (TextBlock) blockType() string { return "text" }

// TableBlock — structured table data.
type TableBlock struct {
	Title   string     `json:"title"`
	Headers []string   `json:"headers"`
	Rows    [][]string `json:"rows"`
}

func (TableBlock) blockType() string { return "table" }

// CodeBlock — syntax-highlighted code snippet.
type CodeBlock struct {
	Content  string `json:"content"`
	Language string `json:"language"`
}

func (CodeBlock) blockType() string { return "code" }

// ProgressBlock — step progress indicator.
type ProgressBlock struct {
	Label   string `json:"label"`
	Current int    `json:"current"`
	Total   int    `json:"total"`
}

func (ProgressBlock) blockType() string { return "progress" }

// PlanBlock — task plan with step checklist.
type PlanBlock struct {
	Task           string   `json:"task"`
	Steps          []string `json:"steps"`
	CompletedCount int      `json:"completedCount"`
}

func (PlanBlock) blockType() string { return "plan" }

// PlanLinkSummary mirrors the Java-side summary attached to list entries.
type PlanLinkSummary struct {
	ParentID   string `json:"parentId"`
	Relation   string `json:"relation"`
	ParentName string `json:"parentName"`
}

// PlanEntry mirrors the server-side PlanEntry record.
type PlanEntry struct {
	ID          string            `json:"id"`
	SessionID   string            `json:"sessionId"`
	UserID      string            `json:"userId"`
	Name        string            `json:"name"`
	TaskPreview string            `json:"taskPreview"`
	Status      string            `json:"status"`
	Phase       string            `json:"phase"`
	CreatedAt   string            `json:"createdAt"`
	UpdatedAt   string            `json:"updatedAt"`
	Parents     []PlanLinkSummary `json:"parents"`
}

// PlanListBlock — P21 interactive registry. CLI auto-opens the picker on receipt.
type PlanListBlock struct {
	Plans        []PlanEntry `json:"plans"`
	StatusFilter string      `json:"statusFilter"`
}

func (PlanListBlock) blockType() string { return "plan_list" }

// VerificationBlock — validation results with pass/fail.
type VerificationBlock struct {
	Path   string   `json:"path"`
	Valid  bool     `json:"valid"`
	Issues []string `json:"issues"`
	Passed []string `json:"passed"`
}

func (VerificationBlock) blockType() string { return "verification" }

// MetadataBlock — arbitrary key-value metadata.
type MetadataBlock struct {
	Metadata map[string]any `json:"metadata"`
}

func (MetadataBlock) blockType() string { return "metadata" }

// SpanEventBlock — OTel-style span lifecycle event for the live activity tracker.
// Phase is one of "start", "delta", "end".
type SpanEventBlock struct {
	SpanID       string         `json:"spanId"`
	ParentSpanID string         `json:"parentSpanId"`
	Name         string         `json:"name"`
	Phase        string         `json:"phase"`
	Attributes   map[string]any `json:"attributes"`
	Timestamp    string         `json:"timestamp"`
}

func (SpanEventBlock) blockType() string { return "span_event" }

// ChoiceOption — one entry in a ChoiceBlock picker.
// Value is the canonical machine-readable id the agent will get back.
// Label and Description drive the human-readable rendering.
type ChoiceOption struct {
	Value       string `json:"value"`
	Label       string `json:"label"`
	Description string `json:"description"`
}

// ChoiceBlock — interactive single-select prompt. The CLI auto-opens an arrow-navigable picker
// on receipt; on confirm it sends back a follow-up user message of the form
// "[user-choice <choiceId>] <label> (id=<value>)" so the LLM next turn can pick up the operator's
// selection from the chat history. Every interactive persona that needs the operator to
// disambiguate (multi-result template lookups, ambiguous intent) calls the server-side
// ask_user_to_choose tool which emits this block.
type ChoiceBlock struct {
	ChoiceID string         `json:"choiceId"`
	Prompt   string         `json:"prompt"`
	Options  []ChoiceOption `json:"options"`
}

func (ChoiceBlock) blockType() string { return "choice" }

// ParseOutputBlock infers the OutputBlock type from JSON field presence.
// Field detection order matters — more specific fields checked first,
// TextBlock is the fallback since "content" appears in both Text and Code.
func ParseOutputBlock(data []byte) (OutputBlock, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("invalid JSON: %w", err)
	}

	switch {
	case has(raw, "spanId"):
		var b SpanEventBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "headers"):
		var b TableBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "language"):
		var b CodeBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "total"):
		var b ProgressBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "plans"):
		var b PlanListBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "steps"):
		var b PlanBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "valid"):
		var b VerificationBlock
		return b, json.Unmarshal(data, &b)
	// ChoiceBlock has both "choiceId" and "options" — checking either uniquely identifies it.
	// Using "choiceId" because "options" might appear in some future generic block variant.
	case has(raw, "choiceId"):
		var b ChoiceBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "metadata"):
		var b MetadataBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "content"):
		var b TextBlock
		return b, json.Unmarshal(data, &b)
	default:
		// Unknown block type — wrap as metadata
		var m map[string]any
		_ = json.Unmarshal(data, &m)
		return MetadataBlock{Metadata: m}, nil
	}
}

func has(m map[string]json.RawMessage, key string) bool {
	_, ok := m[key]
	return ok
}
