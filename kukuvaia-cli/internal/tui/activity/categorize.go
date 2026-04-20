package activity

import "strings"

// Categorize maps a kukuvaia span name to a display category.
// Names follow the "<category>:<subject>" convention set by the engine.
//
// For tool spans, a second-level map refines the subject (search / read /
// write / command / other). Unknown tool names fall back to CategoryOther.
func Categorize(spanName string) Category {
	prefix, rest, found := strings.Cut(spanName, ":")
	if !found {
		return CategoryOther
	}
	switch prefix {
	case "role":
		return CategoryRole
	case "llm":
		return CategoryLLM
	case "memory":
		return CategoryMemory
	case "tool":
		return toolSubCategory(rest)
	}
	return CategoryOther
}

// toolSubCategory maps known tool names to coarse activity buckets.
// Unknown tools land in CategoryOther — override via config when adding MCP tools.
func toolSubCategory(tool string) Category {
	switch tool {
	case "grep", "search_content", "find_files", "searchMemories", "web_search":
		return CategorySearch
	case "read_file", "get_file_content", "listMemories":
		return CategoryRead
	case "write_file", "edit_file", "saveMemory", "createPlan", "revisePlan",
		"updateDiscoveryFacts", "completeStep":
		return CategoryWrite
	case "bash_run", "git_status", "runValidation":
		return CategoryCommand
	}
	return CategoryOther
}
