package tui

import "github.com/charmbracelet/lipgloss"

// UI-specific styles — layout and component styling beyond theme colors.
// Inspired by Claude Code's TUI patterns adapted to Matrix aesthetic.

// Additional semantic colors not in the theme.
var (
	ColorTool    = lipgloss.Color("#00BFFF") // Bright cyan — tool calls, actions
	ColorCommand = lipgloss.Color("#DA70D6") // Orchid purple — slash commands
	ColorPath    = lipgloss.Color("#FFB800") // Amber — file paths
	ColorAgent   = lipgloss.Color("#00CED1") // Dark turquoise — sub-agents
	ColorKeyword = lipgloss.Color("#87CEEB") // Sky blue — keywords
)

// Input box — bordered prompt area like Claude Code.
var InputBoxStyle = lipgloss.NewStyle().
	Border(lipgloss.RoundedBorder()).
	BorderForeground(ColorBorder).
	Padding(0, 1)

var InputBoxFocusedStyle = lipgloss.NewStyle().
	Border(lipgloss.RoundedBorder()).
	BorderForeground(ColorPrimary).
	Padding(0, 1)

// User message — surface background with pointer prefix.
var UserMsgBoxStyle = lipgloss.NewStyle().
	Foreground(ColorPrimary).
	Bold(true).
	PaddingLeft(1)

var UserMsgPrefixStyle = lipgloss.NewStyle().
	Foreground(ColorMuted).
	Bold(true)

// Tool call badge — [ToolName] in cyan.
var ToolBadgeStyle = lipgloss.NewStyle().
	Foreground(ColorTool).
	Bold(true)

// Command badge — /command in purple.
var CommandBadgeStyle = lipgloss.NewStyle().
	Foreground(ColorCommand).
	Bold(true)

// File path highlight.
var PathStyle = lipgloss.NewStyle().
	Foreground(ColorPath).
	Underline(true)

// Sub-agent indicator.
var AgentBadgeStyle = lipgloss.NewStyle().
	Foreground(ColorAgent).
	Bold(true)

// Thinking spinner line.
var ThinkingStyle = lipgloss.NewStyle().
	Foreground(ColorMuted).
	Italic(true)

// Separator line between messages.
var SeparatorStyle = lipgloss.NewStyle().
	Foreground(lipgloss.Color("#1A1A1A"))

// Session/status bar at bottom.
var StatusBarStyle = lipgloss.NewStyle().
	Foreground(ColorMuted).
	Background(lipgloss.Color("#0A0A0A")).
	Padding(0, 1)
