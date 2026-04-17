package tui

import "github.com/kukuvaia/kukuvaia-cli/internal/api"

// RenderBlock dispatches an OutputBlock to the appropriate renderer.
// Width constrains the rendered output to fit the terminal.
func RenderBlock(block api.OutputBlock, width int) string {
	switch b := block.(type) {
	case api.TextBlock:
		return renderText(b, width)
	case api.TableBlock:
		return renderTable(b, width)
	case api.CodeBlock:
		return renderCode(b, width)
	case api.ProgressBlock:
		return renderProgress(b, width)
	case api.PlanBlock:
		return renderPlan(b, width)
	case api.VerificationBlock:
		return renderVerification(b, width)
	case api.MetadataBlock:
		return renderMetadata(b, width)
	default:
		return MutedStyle.Render("[unknown block]")
	}
}
