# Kukuvaia CLI Implementation Plan

**Date:** 2026-04-10
**Status:** Draft
**Component:** kukuvaia-cli (Go / Charm stack)
**Architecture:** CLI as MCP Server + TUI client

## Architecture

```
┌────────────────────────────────────────────────┐
│ kukuvaia-cli (Go)                              │
│                                                │
│  ┌──────────────────┐  ┌───────────────────┐   │
│  │ MCP Server       │  │ TUI (Bubbletea)   │   │
│  │ (stdio/SSE)      │  │                   │   │
│  │                  │  │ Input → /api/chat  │   │
│  │ Local tools:     │  │ SSE ← OutputBlock  │   │
│  │ - fs.read        │  │ Lipgloss render    │   │
│  │ - fs.write       │  │                   │   │
│  │ - fs.find        │  └───────┬───────────┘   │
│  │ - fs.search      │          │               │
│  │ - bash.run       │          │ HTTP/SSE      │
│  │ - git.status     │          │               │
│  └──────┬───────────┘          │               │
│         │ MCP (stdio)          │               │
└─────────┼──────────────────────┼───────────────┘
          │                      │
          ▼                      ▼
┌────────────────────────────────────────────────┐
│ kukuvaia-server (Java / Spring AI)             │
│                                                │
│  MCP Client ← connects to CLI MCP Server       │
│  LLM (SmartGate/OpenAI) ← ChatClient          │
│  Server tools (YAML+Lua): db.query, http.*     │
│  MCP Client ← connects to ETSL MCP Server      │
│                                                │
│  LLM sees ALL tools uniformly:                 │
│  [read_file, bash, git_status, db_query, ...]  │
└────────────────────────────────────────────────┘
```

## Key Design Decision: CLI as MCP Server

The CLI exposes local filesystem and system tools as an MCP server.
kukuvaia-server connects to it as MCP Client via `spring-ai-starter-mcp-client`.

Benefits:
- LLM doesn't know which tools are local vs server — MCP abstracts it
- Standard protocol (no custom WebSocket/SSE bridge for tool calls)
- Go has mature MCP SDK (`github.com/mark3labs/mcp-go`)
- Same pattern as ETSL MCP server — server just adds another MCP connection
- Local TOOL.md definitions (YAML+Lua) can run on CLI side too (future)

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Go 1.22+ |
| TUI framework | Bubbletea (Elm architecture) |
| Styling | Lipgloss (CSS-like) |
| Components | Bubbles (input, table, spinner, viewport) |
| Markdown render | Glamour |
| MCP Server | mcp-go (github.com/mark3labs/mcp-go) |
| HTTP/SSE client | net/http + SSE parser |
| Design tokens | kukuvaia-theme.yaml → go generate → styles.go |

## Phase 1: Foundation (MCP Server + Minimal TUI)

**Goal:** CLI starts, exposes local tools via MCP, connects to server, sends/receives messages.

### 1.1 Project Setup

```
kukuvaia-cli/
├── cmd/
│   └── kukuvaia/
│       └── main.go              # Entry point
├── internal/
│   ├── mcp/
│   │   ├── server.go            # MCP Server (stdio transport)
│   │   ├── tools.go             # Local tool definitions
│   │   └── fs.go                # Filesystem tool implementations
│   ├── api/
│   │   ├── client.go            # HTTP client for kukuvaia-server
│   │   └── sse.go               # SSE stream parser
│   ├── tui/
│   │   ├── app.go               # Root Bubbletea model
│   │   ├── chat.go              # Chat view (messages + input)
│   │   ├── styles.go            # Lipgloss styles (generated from theme)
│   │   └── blocks.go            # OutputBlock renderers
│   └── config/
│       └── config.go            # CLI configuration (~/.kukuvaia.yaml)
├── go.mod
├── go.sum
└── Makefile
```

### 1.2 MCP Server — Local Tools

Expose filesystem tools via MCP protocol (stdio transport):

```go
// internal/mcp/tools.go
var LocalTools = []mcp.Tool{
    {
        Name:        "read_file",
        Description: "Read a file from the local filesystem",
        InputSchema: mcp.Schema{
            Type: "object",
            Properties: map[string]mcp.Property{
                "path":   {Type: "string", Description: "Absolute or relative file path"},
                "offset": {Type: "integer", Description: "Start line (0-based)"},
                "limit":  {Type: "integer", Description: "Number of lines"},
            },
            Required: []string{"path"},
        },
    },
    {
        Name:        "write_file",
        Description: "Write content to a file on the local filesystem",
        // ...
    },
    {
        Name:        "find_files",
        Description: "Find files matching a glob pattern",
        // ...
    },
    {
        Name:        "search_content",
        Description: "Search text across local files",
        // ...
    },
    {
        Name:        "bash_run",
        Description: "Execute a shell command (sandboxed, allowlisted)",
        InputSchema: mcp.Schema{
            Type: "object",
            Properties: map[string]mcp.Property{
                "command": {Type: "string", Description: "Shell command to execute"},
                "cwd":     {Type: "string", Description: "Working directory"},
                "timeout": {Type: "integer", Description: "Timeout in seconds (max 120)"},
            },
            Required: []string{"command"},
        },
    },
    {
        Name:        "git_status",
        Description: "Get git repository status (branch, changes, commits ahead/behind)",
        // ...
    },
}
```

### 1.3 Filesystem Tool Implementations

```go
// internal/mcp/fs.go
func ReadFile(path string, offset, limit int) (string, error) {
    // Validate path (no traversal above CWD)
    absPath, err := validatePath(path)
    if err != nil {
        return "", err
    }

    data, err := os.ReadFile(absPath)
    if err != nil {
        return "", err
    }

    lines := strings.Split(string(data), "\n")
    // Apply offset/limit
    start := min(offset, len(lines))
    end := min(start+limit, len(lines))
    if limit <= 0 { end = len(lines) }

    return strings.Join(lines[start:end], "\n"), nil
}
```

### 1.4 SSE Client

```go
// internal/api/sse.go
func (c *Client) Chat(sessionID, message string) (<-chan OutputBlock, error) {
    req := ChatRequest{SessionID: sessionID, Message: message}
    body, _ := json.Marshal(req)

    resp, err := http.Post(c.baseURL+"/api/chat", "application/json", bytes.NewReader(body))
    // Parse SSE stream, emit OutputBlock on channel
    ch := make(chan OutputBlock)
    go func() {
        defer close(ch)
        scanner := bufio.NewScanner(resp.Body)
        for scanner.Scan() {
            line := scanner.Text()
            if strings.HasPrefix(line, "data:") {
                var block OutputBlock
                json.Unmarshal([]byte(line[5:]), &block)
                ch <- block
            }
        }
    }()
    return ch, nil
}
```

### 1.5 Minimal TUI

```go
// internal/tui/app.go
type model struct {
    messages  []OutputBlock
    input     textinput.Model
    viewport  viewport.Model
    client    *api.Client
    sessionID string
    waiting   bool
}

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
    switch msg := msg.(type) {
    case tea.KeyMsg:
        if msg.String() == "enter" && !m.waiting {
            text := m.input.Value()
            m.input.Reset()
            m.waiting = true
            return m, m.sendMessage(text)
        }
    case OutputBlockMsg:
        m.messages = append(m.messages, msg.Block)
        m.waiting = false
    }
    return m, nil
}
```

### Phase 1 Checklist

- [ ] `go mod init github.com/kukuvaia/kukuvaia-cli`
- [ ] MCP Server with stdio transport (mcp-go)
- [ ] Local tools: read_file, write_file, find_files, search_content, bash_run, git_status
- [ ] Path validation (no traversal above CWD)
- [ ] HTTP client for kukuvaia-server API
- [ ] SSE stream parser for OutputBlocks
- [ ] Bubbletea app: input + chat viewport
- [ ] OutputBlock renderers (TextBlock → Glamour, TableBlock → lipgloss/table, CodeBlock → syntax highlight)
- [ ] `~/.kukuvaia.yaml` config (server URL, session ID)
- [ ] Build: `go build -o kukuvaia ./cmd/kukuvaia/`

## Phase 2: Full TUI + Session Management

**Goal:** Polished TUI with session management, personas, command palette.

### 2.1 OutputBlock Renderers

| OutputBlock | Lipgloss Component | Description |
|-------------|-------------------|-------------|
| TextBlock | Glamour.Render() | Markdown rendering in terminal |
| TableBlock | lipgloss/table | Styled table with headers |
| CodeBlock | Chroma syntax | Syntax-highlighted code |
| ProgressBlock | Bubbles progress | Progress bar |
| PlanBlock | Custom panel | Step checklist with progress |
| VerificationBlock | Status panel | Pass/fail with issues list |
| MetadataBlock | Collapsed JSON | Expandable metadata view |

### 2.2 Session Management

```go
// internal/tui/sessions.go
// List sessions: GET /api/sessions
// Switch session: update model.sessionID
// New session: generate UUID, POST first message
// Delete session: DELETE /api/sessions/{id}
```

### 2.3 Slash Command Routing

```go
// CLI intercepts some commands locally:
// /session list    → GET /api/sessions → render table
// /session new     → generate new sessionID
// /session switch  → update sessionID
// /clear           → clear viewport
// /quit            → exit

// All other /commands → POST /api/commands/{cmd}
// Free text → POST /api/chat
```

### 2.4 Persona Switching

```
/persona list     → GET /api/personas (future endpoint)
/persona editor   → switch active persona for session
```

### Phase 2 Checklist

- [ ] OutputBlock renderers for all 7 types
- [ ] Session list/switch/new/delete
- [ ] Slash command local routing (/clear, /quit, /session)
- [ ] Keyboard shortcuts (Ctrl+C exit, Ctrl+L clear, Up/Down history)
- [ ] Input history (arrow keys)
- [ ] Spinner while waiting for response
- [ ] Viewport scrolling (mouse + keyboard)
- [ ] Persona switching
- [ ] kukuvaia-theme.yaml → styles.go (go generate)

## Phase 3: Polish + Advanced Features

**Goal:** Production-quality CLI with auth, streaming, and workspace awareness.

### 3.1 Authentication Flow

```
kukuvaia login
  → Device code flow (GitHub Copilot) or JWT input (SmartGate)
  → Stores token in ~/.kukuvaia/credentials.json (chmod 600)
  → Token sent as Authorization: Bearer header
```

### 3.2 Streaming Rendering

Real-time rendering of SSE stream:
- Text appears word-by-word as SSE events arrive
- Tables/code blocks render when complete
- Spinner shows during tool calls
- Progress bar updates in-place

### 3.3 Workspace Awareness

```
kukuvaia
  → Detects .kukuvaia/ directory (walks up from CWD)
  → Loads project-specific config (.kukuvaia/settings.yaml)
  → MCP Server exposes files relative to workspace root
  → Server knows workspace context via header: X-Kukuvaia-Workspace
```

### 3.4 Multi-Panel Layout (future)

```
┌──────────────────────────────────┬──────────────┐
│ Chat                             │ Context      │
│                                  │              │
│ > Przeanalizuj plik main.go      │ Session: a1  │
│                                  │ Persona: dev │
│ Analizuję plik main.go...       │ Model: sonnet│
│ [spinner]                        │              │
│                                  │ Tools:       │
│                                  │ ✓ read_file  │
│                                  │ ✓ bash_run   │
│                                  │ ✓ db_query   │
├──────────────────────────────────┤              │
│ > _                              │              │
└──────────────────────────────────┴──────────────┘
```

### Phase 3 Checklist

- [ ] Auth: device code flow + JWT
- [ ] Credential storage (chmod 600)
- [ ] Streaming word-by-word rendering
- [ ] Tool call spinner with tool name
- [ ] Workspace detection (.kukuvaia/)
- [ ] Project-specific settings
- [ ] X-Kukuvaia-Workspace header
- [ ] Multi-panel layout (optional)
- [ ] `kukuvaia version` command
- [ ] `kukuvaia config` command
- [ ] Cross-platform build (Linux, macOS, Windows)

## MCP Integration with Server

### Server-side Configuration

```yaml
# kukuvaia-server application.yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          connections:
            kukuvaia-cli:
              command: kukuvaia
              args: [--mcp]
```

When server starts with MCP client config, it:
1. Spawns `kukuvaia --mcp` as subprocess
2. CLI starts in MCP server mode (stdio, no TUI)
3. Server discovers CLI's local tools via MCP `tools/list`
4. LLM sees local tools alongside server tools

### CLI MCP Mode

```go
// cmd/kukuvaia/main.go
func main() {
    if slices.Contains(os.Args, "--mcp") {
        // MCP Server mode: stdio, no TUI
        mcp.RunStdioServer(mcp.LocalTools)
        return
    }

    // Normal mode: TUI + connect to server
    tui.Run()
}
```

### Two Modes of Operation

| Mode | Trigger | What happens |
|------|---------|-------------|
| **TUI** | `kukuvaia` | Full TUI, connects to server via HTTP/SSE |
| **MCP** | `kukuvaia --mcp` | Headless, MCP Server on stdio, spawned by server |

In TUI mode, local tools are available via the TUI itself (not MCP).
In MCP mode, local tools are exposed to the server via MCP protocol.

## Security Considerations

### Local Tool Sandbox

- `bash_run`: Command allowlist (no rm -rf, no sudo, timeout 120s)
- `read_file`/`write_file`: Path validation (no traversal above workspace root)
- `find_files`: Respects .gitignore patterns
- Credentials: chmod 600, never sent to LLM

### MCP Transport Security

- stdio transport: inherently secure (same machine, pipe)
- SSE transport (future, for remote CLI): TLS + auth token required

## Build & Distribution

```bash
# Development
go build -o kukuvaia ./cmd/kukuvaia/
./kukuvaia

# Release (cross-platform)
GOOS=linux GOARCH=amd64 go build -o kukuvaia-linux-amd64 ./cmd/kukuvaia/
GOOS=darwin GOARCH=arm64 go build -o kukuvaia-darwin-arm64 ./cmd/kukuvaia/
GOOS=windows GOARCH=amd64 go build -o kukuvaia-windows-amd64.exe ./cmd/kukuvaia/

# Install
go install github.com/kukuvaia/kukuvaia-cli/cmd/kukuvaia@latest
```

## Dependencies

```go
// go.mod
require (
    github.com/charmbracelet/bubbletea v1.x
    github.com/charmbracelet/lipgloss  v1.x
    github.com/charmbracelet/bubbles   v0.x
    github.com/charmbracelet/glamour   v0.x
    github.com/mark3labs/mcp-go        v0.x
)
```
