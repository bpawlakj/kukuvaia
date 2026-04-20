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
	case api.PlanListBlock:
		// PlanListBlock drives the plan picker overlay — once consumed by
		// findPlanListBlock it is stripped from chat history, but if one
		// slipped through (e.g. offline playback), render a summary line
		// instead of "[unknown block]".
		return MutedStyle.Render("  [plan list — use /plans to open]")
	default:
		return MutedStyle.Render("[unknown block]")
	}
}
