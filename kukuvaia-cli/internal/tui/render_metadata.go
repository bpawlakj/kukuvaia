package tui

import (
	"fmt"
	"sort"
	"strings"

	"github.com/kukuvaia/kukuvaia-cli/internal/api"
)

func renderMetadata(b api.MetadataBlock, width int) string {
	if len(b.Metadata) == 0 {
		return ""
	}

	// Sort keys for deterministic output.
	keys := make([]string, 0, len(b.Metadata))
	for k := range b.Metadata {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var sb strings.Builder
	for i, k := range keys {
		line := fmt.Sprintf("%s: %v", k, b.Metadata[k])
		sb.WriteString(MutedStyle.Render(line))
		if i < len(keys)-1 {
			sb.WriteString("\n")
		}
	}
	return sb.String()
}
