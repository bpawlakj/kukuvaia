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

// ParseOutputBlock infers the OutputBlock type from JSON field presence.
// Field detection order matters — more specific fields checked first,
// TextBlock is the fallback since "content" appears in both Text and Code.
func ParseOutputBlock(data []byte) (OutputBlock, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("invalid JSON: %w", err)
	}

	switch {
	case has(raw, "headers"):
		var b TableBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "language"):
		var b CodeBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "total"):
		var b ProgressBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "steps"):
		var b PlanBlock
		return b, json.Unmarshal(data, &b)
	case has(raw, "valid"):
		var b VerificationBlock
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
