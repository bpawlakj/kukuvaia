package api

import (
	"testing"
)

func TestParseOutputBlock_TextBlock(t *testing.T) {
	data := `{"content":"Hello world","style":null}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	tb, ok := block.(TextBlock)
	if !ok {
		t.Fatalf("expected TextBlock, got %T", block)
	}
	if tb.Content != "Hello world" {
		t.Errorf("content = %q, want %q", tb.Content, "Hello world")
	}
	if tb.Style != nil {
		t.Errorf("style = %v, want nil", tb.Style)
	}
}

func TestParseOutputBlock_TextBlockWithStyle(t *testing.T) {
	data := `{"content":"Error occurred","style":"error"}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	tb := block.(TextBlock)
	if tb.Style == nil || *tb.Style != "error" {
		t.Errorf("style = %v, want 'error'", tb.Style)
	}
}

func TestParseOutputBlock_TableBlock(t *testing.T) {
	data := `{"title":"Commands","headers":["Name","Description"],"rows":[["help","Show help"],["quit","Exit"]]}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	tb, ok := block.(TableBlock)
	if !ok {
		t.Fatalf("expected TableBlock, got %T", block)
	}
	if tb.Title != "Commands" {
		t.Errorf("title = %q, want %q", tb.Title, "Commands")
	}
	if len(tb.Headers) != 2 {
		t.Errorf("headers len = %d, want 2", len(tb.Headers))
	}
	if len(tb.Rows) != 2 {
		t.Errorf("rows len = %d, want 2", len(tb.Rows))
	}
}

func TestParseOutputBlock_CodeBlock(t *testing.T) {
	data := `{"content":"fmt.Println(\"hello\")","language":"go"}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	cb, ok := block.(CodeBlock)
	if !ok {
		t.Fatalf("expected CodeBlock, got %T", block)
	}
	if cb.Language != "go" {
		t.Errorf("language = %q, want %q", cb.Language, "go")
	}
}

func TestParseOutputBlock_ProgressBlock(t *testing.T) {
	data := `{"label":"Validating","current":3,"total":10}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	pb, ok := block.(ProgressBlock)
	if !ok {
		t.Fatalf("expected ProgressBlock, got %T", block)
	}
	if pb.Current != 3 || pb.Total != 10 {
		t.Errorf("progress = %d/%d, want 3/10", pb.Current, pb.Total)
	}
}

func TestParseOutputBlock_PlanBlock(t *testing.T) {
	data := `{"task":"Build feature","steps":["Design","Implement","Test"],"completedCount":1}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	pb, ok := block.(PlanBlock)
	if !ok {
		t.Fatalf("expected PlanBlock, got %T", block)
	}
	if len(pb.Steps) != 3 {
		t.Errorf("steps len = %d, want 3", len(pb.Steps))
	}
	if pb.CompletedCount != 1 {
		t.Errorf("completedCount = %d, want 1", pb.CompletedCount)
	}
}

func TestParseOutputBlock_VerificationBlock(t *testing.T) {
	data := `{"path":"/api/test","valid":false,"issues":["Missing field"],"passed":["Status OK"]}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	vb, ok := block.(VerificationBlock)
	if !ok {
		t.Fatalf("expected VerificationBlock, got %T", block)
	}
	if vb.Valid {
		t.Error("valid = true, want false")
	}
	if len(vb.Issues) != 1 {
		t.Errorf("issues len = %d, want 1", len(vb.Issues))
	}
}

func TestParseOutputBlock_MetadataBlock(t *testing.T) {
	data := `{"metadata":{"tokens":42,"model":"claude"}}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	mb, ok := block.(MetadataBlock)
	if !ok {
		t.Fatalf("expected MetadataBlock, got %T", block)
	}
	if len(mb.Metadata) != 2 {
		t.Errorf("metadata len = %d, want 2", len(mb.Metadata))
	}
}

func TestParseOutputBlock_ChoiceBlock(t *testing.T) {
	data := `{"choiceId":"abc-123","prompt":"Pick one","options":[` +
		`{"value":"t-1","label":"Sisko MR","description":"PUBLISHED · sanomapro"},` +
		`{"value":"t-2","label":"Sisko MR Utbildning","description":"PUBLISHED · sanomapro"}` +
		`]}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	cb, ok := block.(ChoiceBlock)
	if !ok {
		t.Fatalf("expected ChoiceBlock, got %T", block)
	}
	if cb.ChoiceID != "abc-123" || cb.Prompt != "Pick one" {
		t.Errorf("choiceId/prompt mismatch: %+v", cb)
	}
	if len(cb.Options) != 2 {
		t.Fatalf("options len = %d, want 2", len(cb.Options))
	}
	if cb.Options[0].Value != "t-1" || cb.Options[0].Label != "Sisko MR" {
		t.Errorf("option[0] = %+v", cb.Options[0])
	}
}

func TestParseOutputBlock_UnknownFallback(t *testing.T) {
	data := `{"foo":"bar","baz":123}`
	block, err := ParseOutputBlock([]byte(data))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	mb, ok := block.(MetadataBlock)
	if !ok {
		t.Fatalf("expected MetadataBlock fallback, got %T", block)
	}
	if mb.Metadata["foo"] != "bar" {
		t.Errorf("metadata[foo] = %v, want 'bar'", mb.Metadata["foo"])
	}
}
