# Design System — Kukuvaia Shared Theme

## Brand Identity

### Logo

Minimalist owl logo for a terminal-based AI agent. The owl (κουκουβάγια) symbolizes wisdom and knowledge.

**Style:**
- Matrix/hacker aesthetic: phosphor green (#00FF41) on pure black (#000000)
- Terminal-rendered look — ASCII art, monospace grid, scanline glow effect
- Geometric and angular, not cute or cartoonish — serious dev tool
- Owl's eyes are the most prominent feature — two bright glowing circles, like terminal cursors
- Subtle digital rain / falling code effect in the background
- Owl silhouette constructed from terminal characters, code fragments, or circuit-like patterns
- Below the owl: "KUKUVAIA" in clean monospace font with subtle green glow
- Optional tagline: "wisdom agent"

**Color palette (strict):**

| Role | Hex | Usage |
|------|-----|-------|
| Background | `#000000` | Pure black |
| Primary | `#00FF41` | Phosphor green (Matrix green) — main elements |
| Accent | `#008F11` | Darker green — depth, shadows |
| Highlight | `#00FF41` at 100% | Eyes, key details |

No other colors allowed in the logo.

**Mood:** mysterious, intelligent, technical, cyberpunk-minimal.
Think: if the owl from Blade Runner lived in a terminal.

**Format:** square (1:1), high contrast, suitable for dark terminal backgrounds.
Must remain recognizable at 64x64 pixels or converted to monochrome ASCII art.

**File:** `docs/logo.png`

### Visual Language

The entire design system follows the Matrix/terminal aesthetic established by the logo:
- Black backgrounds with phosphor green as the dominant color
- Monospace typography feel
- High contrast, minimal palette
- Terminal-native look and feel

## Problem

Kukuvaia has two client interfaces:
1. **kukuvaia-cli** (Go/Charm) — terminal TUI
2. **kukuvaia-web** (future) — browser frontend

Both render the same `OutputBlock` data from the server. Without a shared design system, visual identity drifts between clients — different colors, spacing, border styles.

## Solution: Shared Design Tokens

A single `kukuvaia-theme.yaml` defines all visual tokens. Both clients consume it at build time:

```
kukuvaia-theme.yaml (source of truth)
       │
       ├── go generate → internal/tui/styles.go   (Lipgloss constants)
       └── build step  → static/theme.css          (CSS custom properties)
```

### Why YAML (not JSON, not CSS)

- YAML supports comments (design rationale inline)
- Readable by both Go (`gopkg.in/yaml.v3`) and any web build tool
- Neutral format — not biased toward terminal or web
- Same format as personas and commands (`.kukuvaia/`)

## Token Categories

| Category | Tokens | Example |
|----------|--------|---------|
| `colors` | Brand colors, semantic colors | `primary: "#00FF41"`, `error: "#FF4444"` |
| `table` | Header style, borders, alternating rows | `header_bg`, `border_style` |
| `panel` | Border, title, padding | `border_style: rounded` |
| `code` | Code block background, border | `background: "#0A0A0A"` |
| `progress` | Bar colors, spinner | `filled_color`, `spinner_color` |
| `status` | Semantic status colors | `pass`, `fail`, `pending`, `skip` |
| `spacing` | Gaps between blocks | `block_gap: 1` |

## Mapping: Lipgloss ↔ CSS

The mapping is intentionally close because Lipgloss was designed as "CSS for terminals":

| Concept | Lipgloss (Go) | CSS (Web) |
|---------|--------------|-----------|
| Text color | `Foreground(Color("#00FF41"))` | `color: var(--color-primary)` |
| Background | `Background(Color("#000000"))` | `background: var(--color-bg)` |
| Bold | `Bold(true)` | `font-weight: 700` |
| Rounded border | `Border(RoundedBorder())` | `border-radius: 8px; border: 1px solid` |
| Thick border | `Border(ThickBorder())` | `border: 2px solid` |
| Double border | `Border(DoubleBorder())` | `border-style: double` |
| Padding | `Padding(0, 1)` | `padding: 0 0.5rem` |
| Width | `Width(40)` | `max-width: 40ch` |
| Alignment | `Align(Center)` | `text-align: center` |

### Border Style Mapping

Theme uses abstract names, each client translates:

| Theme token | Lipgloss | CSS |
|-------------|----------|-----|
| `rounded` | `lipgloss.RoundedBorder()` | `border-radius: 8px` |
| `thick` | `lipgloss.ThickBorder()` | `border-width: 2px` |
| `double` | `lipgloss.DoubleBorder()` | `border-style: double` |
| `normal` | `lipgloss.NormalBorder()` | `border: 1px solid` |

## OutputBlock → Rendering

Server sends typed JSON blocks via SSE. Each client maps block type to themed component:

| OutputBlock | CLI (Charm) | Web (HTML/CSS) |
|------------|-------------|----------------|
| `TextBlock{content, style}` | `lipgloss.Render(content)` with style from theme | `<p class="text--{style}">` |
| `TableBlock{headers, rows, title}` | `lipgloss/table` with `table.*` tokens | `<table>` with `--table-*` vars |
| `CodeBlock{content, language}` | `glamour` or styled `lipgloss` block | `<pre><code>` with highlight.js |
| `ProgressBlock{current, total}` | `bubbles/progress` with `progress.*` tokens | `<progress>` or custom bar |
| `SectionBlock{title, blocks}` | `lipgloss` panel with `panel.*` tokens | `<section>` with card styling |
| `SpinnerBlock{}` | `bubbles/spinner` | CSS animation |

## Go Code Generation

```go
//go:generate go run ../tools/gen-styles/main.go -theme ../kukuvaia-theme.yaml -out styles_gen.go

// styles_gen.go (generated — do not edit)
package tui

import "github.com/charmbracelet/lipgloss"

var (
    ColorPrimary = lipgloss.Color("#00FF41")
    ColorSuccess = lipgloss.Color("#00FF41")
    ColorError   = lipgloss.Color("#FF4444")
    // ...

    TitleStyle = lipgloss.NewStyle().
        Bold(true).
        Foreground(lipgloss.Color("#00FF41")).
        Background(lipgloss.Color("#008F11")).
        Padding(0, 1)

    TableHeaderStyle = lipgloss.NewStyle().
        Bold(true).
        Foreground(lipgloss.Color("#00FF41")).
        Background(lipgloss.Color("#008F11"))

    PanelStyle = lipgloss.NewStyle().
        Border(lipgloss.RoundedBorder()).
        BorderForeground(lipgloss.Color("#008F11")).
        Padding(0, 1)

    CodeBlockStyle = lipgloss.NewStyle().
        Background(lipgloss.Color("#0A0A0A")).
        Border(lipgloss.RoundedBorder()).
        BorderForeground(lipgloss.Color("#008F11")).
        Padding(1, 2)

    ErrorStyle = lipgloss.NewStyle().
        Foreground(lipgloss.Color("#FF4444")).
        Bold(true)

    MutedStyle = lipgloss.NewStyle().
        Foreground(lipgloss.Color("#008F11"))
)
```

## CSS Generation

```css
/* theme.css (generated — do not edit) */
:root {
  --color-primary: #00FF41;
  --color-success: #00FF41;
  --color-error: #FF4444;
  --color-warning: #FFB800;
  --color-muted: #008F11;
  --color-text: #00FF41;
  --color-bg: #000000;
  --color-surface: #0A0A0A;
  --color-border: #008F11;

  --table-header-bg: #008F11;
  --table-header-fg: #00FF41;
  --table-row-alt-bg: #0A0A0A;
  --table-border-color: #008F11;
  --table-border-radius: 8px;

  --panel-border-color: #008F11;
  --panel-title-color: #00FF41;
  --panel-border-radius: 8px;
  --panel-padding: 0 0.5rem;

  --code-bg: #0A0A0A;
  --code-border: #008F11;
  --code-text: #00FF41;

  --progress-filled: #00FF41;
  --progress-empty: #008F11;
  --progress-spinner: #00FF41;

  --status-pass: #00FF41;
  --status-fail: #FF4444;
  --status-pending: #FFB800;
  --status-skip: #008F11;
}
```

## Workflow

1. Designer/dev edits `kukuvaia-theme.yaml`
2. `go generate` in kukuvaia-cli → updates `styles_gen.go`
3. Web build step → updates `theme.css`
4. Both clients now render with identical colors, borders, spacing
5. PR review shows theme diff — easy to verify visual changes

## Future: Light Theme

```yaml
# kukuvaia-theme-light.yaml
colors:
  primary: "#008F11"
  text: "#0A0A0A"
  background: "#FFFFFF"
  surface: "#F5F5F5"
  border: "#008F11"
```

CLI: `kukuvaia --theme light`
Web: media query `prefers-color-scheme: light` or toggle
