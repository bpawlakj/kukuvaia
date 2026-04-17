package tui

import (
	"regexp"
	"strings"

	"github.com/charmbracelet/glamour"
	"github.com/charmbracelet/lipgloss"
	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

// Known tool names for highlighting.
var toolNames = map[string]bool{
	"saveMemory": true, "searchMemories": true, "listMemories": true, "deleteMemory": true,
	"createPlan": true, "completeStep": true, "revisePlan": true,
	"read_file": true, "write_file": true, "edit_file": true,
	"find_files": true, "search_content": true, "web_search": true,
	"bash_run": true, "git_status": true,
}

// Patterns for highlighting (used for non-markdown text).
var (
	pathPattern     = regexp.MustCompile(`(?:^|[\s(])([~./][^\s,)]+\.[a-zA-Z0-9]+)`)
	toolCallPattern = regexp.MustCompile(`\b([a-zA-Z_][a-zA-Z0-9_]*)\(`)
)

// markdownIndicators detects if content likely contains markdown.
var mdIndicators = []string{
	"|", "```", "##", "**", "- [", "1. ", "---",
}

// glamourRenderer is a lazily-initialized markdown renderer with dark theme.
var glamourRenderer *glamour.TermRenderer

func getGlamourRenderer(width int) *glamour.TermRenderer {
	if glamourRenderer != nil {
		return glamourRenderer
	}
	r, err := glamour.NewTermRenderer(
		glamour.WithStandardStyle("dark"),
		glamour.WithWordWrap(width),
	)
	if err != nil {
		return nil
	}
	glamourRenderer = r
	return r
}

func renderText(b api.TextBlock, width int) string {
	style := resolveTextStyle(b.Style)

	// For error/warning/muted/title styles, render plain
	if b.Style != nil && *b.Style != "" {
		if width > 0 {
			style = style.Width(width)
		}
		return style.Render(b.Content)
	}

	// If content looks like markdown, use Glamour
	if hasMarkdown(b.Content) {
		if r := getGlamourRenderer(width); r != nil {
			rendered, err := r.Render(b.Content)
			if err == nil {
				return strings.TrimSpace(rendered)
			}
		}
	}

	// Fallback: keyword highlighting for plain text
	return highlightText(b.Content, width)
}

func resolveTextStyle(s *string) lipgloss.Style {
	if s == nil || *s == "" {
		return lipgloss.NewStyle().Foreground(ColorText)
	}
	switch *s {
	case "error":
		return ErrorStyle.Copy()
	case "warning":
		return WarningStyle.Copy()
	case "muted":
		return MutedStyle.Copy()
	case "bold":
		return lipgloss.NewStyle().Foreground(ColorText).Bold(true)
	case "title":
		return TitleStyle.Copy()
	default:
		return lipgloss.NewStyle().Foreground(ColorText)
	}
}

// hasMarkdown checks if the text likely contains markdown formatting.
func hasMarkdown(text string) bool {
	for _, ind := range mdIndicators {
		if strings.Contains(text, ind) {
			return true
		}
	}
	return false
}

// highlightText applies semantic coloring to plain text (non-markdown).
func highlightText(text string, width int) string {
	lines := strings.Split(text, "\n")
	var result []string
	for _, line := range lines {
		result = append(result, highlightLine(line))
	}
	return strings.Join(result, "\n")
}

func highlightLine(line string) string {
	// Tool call references like toolName( → cyan bold
	line = toolCallPattern.ReplaceAllStringFunc(line, func(match string) string {
		name := match[:len(match)-1]
		if toolNames[name] {
			return ToolBadgeStyle.Render(name) + "("
		}
		return match
	})

	// File paths → amber underlined
	line = pathPattern.ReplaceAllStringFunc(line, func(match string) string {
		trimmed := strings.TrimLeft(match, " (")
		prefix := match[:len(match)-len(trimmed)]
		return prefix + PathStyle.Render(trimmed)
	})

	return line
}
