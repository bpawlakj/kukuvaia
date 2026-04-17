# CLAUDE.md — kukuvaia-cli

## What This Is

Go TUI client for Kukuvaia. Connects to kukuvaia-server Web API (HTTP/SSE) and renders responses with Charm stack. Thin client — no agent logic, no LLM calls, no database access.

## Tech Stack

- Go 1.22+
- [Bubbletea](https://github.com/charmbracelet/bubbletea) — TUI framework (Elm architecture)
- [Lipgloss](https://github.com/charmbracelet/lipgloss) — CSS-like terminal styling
- [Bubbles](https://github.com/charmbracelet/bubbles) — pre-built components (spinner, table, viewport, input)
- [Glamour](https://github.com/charmbracelet/glamour) — markdown rendering in terminal

## Architecture

```
kukuvaia-cli (Go)
  │  Bubbletea Model/Update/View
  │  Lipgloss styling
  │  Bubbles components
  │
  │  HTTP/SSE
  ▼
kukuvaia-server (Java/Spring AI)
  POST /api/chat          → SSE stream of OutputBlocks
  GET  /api/sessions      → session list
  POST /api/commands/{cmd} → command execution
```

CLI is a pure presentation layer. All intelligence lives in the server.

## Module Layout

```
cmd/
└── kukuvaia/
    └── main.go            # Entry point, CLI flags (cobra/pflag)
internal/
├── tui/
│   ├── app.go             # Bubbletea root model
│   ├── chat.go            # Chat view (input + message history)
│   ├── table.go           # Table renderer (lipgloss/table)
│   ├── progress.go        # Progress bar component
│   ├── code.go            # Syntax highlighted code block
│   ├── panel.go           # Panel/box renderer
│   ├── styles.go          # Generated Lipgloss styles (from theme)
│   └── theme.go           # Theme loader (reads kukuvaia-theme.yaml)
├── api/
│   ├── client.go          # HTTP client to kukuvaia-server
│   ├── sse.go             # SSE stream reader
│   └── types.go           # OutputBlock types (mirror server)
└── config/
    └── config.go          # Server URL, persona, session config
kukuvaia-theme.yaml        # Shared design tokens (same file used by web)
go.mod
go.sum
```

## OutputBlock Rendering

Server sends structured JSON blocks via SSE. CLI maps each block type to a Charm component:

| Server OutputBlock | CLI Renderer | Charm Component |
|-------------------|-------------|-----------------|
| `TextBlock` | Styled text | Lipgloss `.Render()` |
| `TableBlock` | Unicode table | lipgloss/table with styled headers |
| `CodeBlock` | Syntax highlighted | Glamour or custom Lipgloss |
| `ProgressBlock` | Animated progress bar | Bubbles progress |
| `SectionBlock` | Panel with title | Lipgloss border + title |
| `SpinnerBlock` | Animated spinner | Bubbles spinner |

## Key Patterns

### Bubbletea Elm Architecture

```go
type model struct {
    input    textinput.Model    // user input
    messages []OutputBlock      // chat history
    spinner  spinner.Model      // loading indicator
    client   *api.Client        // server connection
    width    int
    height   int
}

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
    switch msg := msg.(type) {
    case tea.KeyMsg:
        if msg.String() == "enter" {
            return m, m.sendMessage(m.input.Value())
        }
    case OutputBlockMsg:
        m.messages = append(m.messages, msg.Block)
    case SSEDoneMsg:
        m.spinner = spinner.Model{} // stop spinner
    }
    return m, nil
}

func (m model) View() string {
    var b strings.Builder
    for _, block := range m.messages {
        b.WriteString(renderBlock(block, m.width))
    }
    b.WriteString(m.input.View())
    return b.String()
}
```

### Lipgloss Styling (from shared theme)

Styles are generated from `kukuvaia-theme.yaml` via `go generate` → `internal/tui/styles.go`. This ensures visual consistency with the web frontend which generates CSS variables from the same file.

```go
// internal/tui/styles.go — generated from kukuvaia-theme.yaml

var (
    titleStyle = lipgloss.NewStyle().
        Bold(true).
        Foreground(lipgloss.Color(theme.Colors.Text)).       // "#FAFAFA"
        Background(lipgloss.Color(theme.Colors.Primary)).    // "#7D56F4"
        Padding(0, 1)

    tableHeaderStyle = lipgloss.NewStyle().
        Bold(true).
        Foreground(lipgloss.Color(theme.Colors.Success))     // "#04B575"

    errorStyle = lipgloss.NewStyle().
        Foreground(lipgloss.Color(theme.Colors.Error)).      // "#FF4444"
        Bold(true)

    mutedStyle = lipgloss.NewStyle().
        Foreground(lipgloss.Color(theme.Colors.Muted))       // "#626262"

    panelStyle = lipgloss.NewStyle().
        Border(lipgloss.RoundedBorder()).
        BorderForeground(lipgloss.Color(theme.Panel.BorderColor)).
        Padding(theme.Panel.Padding...)

    codeBlockStyle = lipgloss.NewStyle().
        Background(lipgloss.Color(theme.Code.Background)).
        Border(lipgloss.RoundedBorder()).
        BorderForeground(lipgloss.Color(theme.Code.BorderColor)).
        Padding(1, 2)
)
```

### SSE Stream Consumption

```go
func (c *Client) Chat(sessionID, message string) <-chan OutputBlock {
    ch := make(chan OutputBlock)
    go func() {
        defer close(ch)
        resp, _ := http.Post(c.baseURL+"/api/chat", "application/json",
            bytes.NewReader(chatRequest(sessionID, message)))
        scanner := bufio.NewScanner(resp.Body)
        for scanner.Scan() {
            line := scanner.Text()
            if strings.HasPrefix(line, "data: ") {
                var block OutputBlock
                json.Unmarshal([]byte(line[6:]), &block)
                ch <- block
            }
        }
    }()
    return ch
}
```

## CLI Entry Points

```bash
kukuvaia                              # REPL (default persona)
kukuvaia --persona editor             # specific persona
kukuvaia --persona support            # different persona
kukuvaia --session abc123             # resume session
kukuvaia --outline <id>               # set outline context
kukuvaia --server http://localhost:8080  # custom server URL
```

## Auth & Model Commands

```bash
# In REPL:
/login github          # OAuth device flow → opens browser → authenticates
/login smartgate       # JWT auth with corporate SmartGate
/login status          # show current provider, model, token expiry
/login logout          # clear stored credentials

/model                 # show current model
/model list            # list available models for current provider
/model claude-opus-4.5 # switch model
```

## Configuration

`~/.kukuvaia.yaml` or env vars:
```yaml
server_url: http://localhost:8080
default_persona: editor
theme: dark                            # dark/light
```

Environment variables:
- `KUKUVAIA_SERVER_URL` — server endpoint
- `KUKUVAIA_PERSONA` — default persona
- `KUKUVAIA_SESSION` — resume session ID

## Build

```bash
cd kukuvaia-cli
go build -o kukuvaia ./cmd/kukuvaia/
```

Single binary, no dependencies. Distributable as standalone executable.

## Testing

```bash
go test ./...
```
