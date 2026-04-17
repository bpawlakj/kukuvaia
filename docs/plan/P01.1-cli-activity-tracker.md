# P01.1: CLI Activity Tracker — Span-Event Consumer & Rendering

**Created**: 2026-04-16
**Status**: Draft
**Module**: kukuvaia-cli (TUI)
**Depends on**: **P01** (OTel span infrastructure + `SpanEventBlock` SSE emission — see P01 §"SpanEventBlock Extension")
**Milestone**: 2A (CLI Full TUI) / 2C (CLI Polish)
**Effort**: S–M (Phase 1: days, Phase 2: days)

> **Why "P01.1"**: This plan is a client-side consumer of the OTel span events emitted by the engine per P01. Numbering makes the dependency explicit — P01 must ship first (at minimum the `SpanEventBlock` extension section).

---

## Problem

While the agent executes tool calls and delegates to sub-agents, the user sees either silence or raw streaming. There's no compact, glanceable indicator of *what's happening right now* — or *which role* (supervisor / worker1 / worker2 / daemon) is currently working.

Reference UX (claude-code, flat single-role):

```
● Searching for 1 pattern, reading 3 files, listing 2 directories… (ctrl+o to expand)
  └ kukuvaia-cli/internal/tui/app.go
  └ Loaded kukuvaia-cli/CLAUDE.md
* Jitterbugging (30s · ↑ 174 tokens · thought for 3s)
```

Kukuvaia target UX (multi-role via span hierarchy — comes free from P01's OTel spans):

```
● supervisor: orchestrating 2 workers (ctrl+o to expand)
  ├─ worker/research: searching 3 patterns, reading 5 files  (12s · ↑ 340 tokens)
  │   └ kukuvaia-cli/internal/tui/app.go
  │   └ Loaded CLAUDE.md
  ├─ worker/code: editing 2 files  (8s · ↑ 120 tokens)
  │   └ wrote activity/model.go
  └─ supervisor: thinking  (2s · ↑ 45 tokens)
* Jitterbugging (20s total · ↑ 505 tokens across 3 roles)
```

The difference between *"the agent is doing something"* and *"worker/research has been searching for 12 s and pulled 340 tokens"*.

## Current State

| Component | Status | Notes |
|-----------|--------|-------|
| SSE transport (engine → CLI) | Exists | `Flux<OutputBlock>` streaming |
| OutputBlock types | Partial | TextBlock, TableBlock, CodeBlock, ProgressBlock |
| `SpanEventBlock` on wire | **Provided by P01 extension** | OTel span start/end events over SSE |
| CLI Bubbletea TUI | Exists (per cli-implementation-plan) | Model/Update/View scaffolding |
| Spinner / status animator | Missing | No live indicator during tool execution |
| Expandable detail view | Missing | No `ctrl+o` collapse/expand |
| Nested multi-role view | Missing | No span-tree aggregation in CLI |

## Reference Implementation Research

Analysis of `/home/bartek/Projects/claude-code-main`:

| File | Role |
|------|------|
| `src/utils/streamlinedTransform.ts:27–104` | Tool categorization → summary text generation |
| `src/utils/activityManager.ts:14–125` | Active-operation tracking by ID (Set) |
| `src/utils/collapseReadSearch.ts:143–200` | Tool classification (search / read / memory / write / command) |
| `src/constants/spinnerVerbs.ts:1–204` | 200+ animated verbs ("Jitterbugging", "Frolicking", …) |
| `src/components/Spinner.tsx:62–250` | Verb selection + activity tracking + token/timer aggregation |
| `src/components/CtrlOToExpand.tsx:29–50` | Collapse/expand keybinding hint |
| `src/components/messages/CollapsedReadSearchContent.tsx:142+` | Sub-action rendering with indentation |

**Pattern summary**:
1. Tools classified at intake into 5 buckets: `search / read / write / command / other`
2. Counts accumulate across tool calls, **reset when text content appears**
3. Active operation IDs tracked in a Set; completion removes them
4. Summary line built from non-zero counts: *"Searching for N patterns, reading M files…"*
5. Sub-actions rendered as indented list below header when expanded
6. Animator: random verb per turn + 50 ms frame spinner + elapsed ms + streamed token count

**Kukuvaia adaptation**: same primitives, but unit of aggregation is **span**, not tool call. A tool call is a leaf span (`name="tool:grep"`). A sub-agent is a branch span (`name="role:worker/research"`) with child tool spans. The CLI builds a live tree from `SpanEventBlock` events.

## Architecture

```
 kukuvaia-engine (per P01 SpanEventBlock extension)
         │  emits SpanEventBlock { spanId, parentSpanId, name, phase, attributes }
         │  over existing SSE /api/chat stream
         ▼
┌───────────────────────── kukuvaia-cli ─────────────────────────────┐
│                                                                    │
│  SseClient — decodes OutputBlock JSON, dispatches tea.Msg          │
│      │                                                             │
│      ▼                                                             │
│  ┌─────────────────────────────────────────────────────┐           │
│  │ activity.Model (Bubbletea component)                │           │
│  │                                                     │           │
│  │  nodes       map[spanID]*SpanNode   (live span tree)│           │
│  │  rootIds     []spanID               (top-level)     │           │
│  │  verb        string                                 │           │
│  │  startTime   time.Time                              │           │
│  │  expanded    bool                                   │           │
│  │  frame       int                    (spinner tick)  │           │
│  │  theme       styles.Theme                           │           │
│  └─────────────────────────────────────────────────────┘           │
│                                                                    │
│  Per SpanNode:                                                    │
│      spanId, parentSpanId, name, category                         │
│      childIds []spanID                                            │
│      startedAt, endedAt (nil if in-flight)                        │
│      attributes (tokens, summary, ...)                            │
│      subActions ring buffer (file names, etc.)                    │
│                                                                    │
│      ▼                                                             │
│  View() renders:                                                  │
│    collapsed: top-level span summary + animator                   │
│    expanded:  nested tree (├─ / └─ / │) with per-span metrics    │
└────────────────────────────────────────────────────────────────────┘
```

## Design Principles

1. **Engine owns observability protocol, CLI owns presentation** — `SpanEventBlock` defined and emitted by P01; CLI never invents its own tool-lifecycle format
2. **Collapse-by-default** — one compact line is steady state; expansion on-demand via `ctrl+o`
3. **Multi-role free** — span `parentSpanId` gives hierarchy without a new "role" protocol
4. **Reset semantics** — top-level span end ⟹ reset tracker state (next turn starts fresh)
5. **No business logic in View()** — Bubbletea Elm purity; all mutation in Update()
6. **Theme-driven styling** — colors/borders from `kukuvaia-theme.yaml` → `styles_gen.go`
7. **Animation off the critical path** — 50 ms ticker only updates spinner + elapsed; span events drive everything else
8. **No goroutines** — all timing via `tea.Tick` commands

---

## Implementation

### Phase 1 — CLI Span-Event Consumer & State (Effort: M, days)

**Goal**: CLI decodes `SpanEventBlock`, maintains live span tree, updates via Bubbletea Elm.

#### Step 1: Package layout

```
kukuvaia-cli/internal/tui/activity/
    model.go        # state struct + New()
    messages.go     # tea.Msg types (decoded from SpanEventBlock)
    node.go         # SpanNode + category helpers
    categorize.go   # span-name → category label
    update.go       # Update(tea.Msg) → (Model, tea.Cmd)
    verbs.go        # spinner verb list
    view.go         # View() string
    styles.go       # lipgloss styles (generated from theme)
```

#### Step 2: State model

**File**: `activity/model.go`

```go
type Model struct {
    nodes         map[string]*SpanNode   // spanId → node
    rootIds       []string                // top-level spans (parentSpanId == "")
    verb          string
    startTime     time.Time
    frame         int                     // spinner frame
    expanded      bool
    theme         styles.Theme
    maxSubActions int                     // cap per node (3 collapsed, 8 expanded)
}

type SpanNode struct {
    SpanID     string
    ParentID   string
    Name       string                     // "tool:grep" | "role:worker/research" | "llm:openai"
    Category   Category
    ChildIDs   []string
    StartedAt  time.Time                  // sourced from SpanEventBlock.timestamp
    EndedAt    *time.Time                 // nil → in-flight
    Status     SpanStatus                 // Success | Error | Cancelled | InFlight
    Attributes map[string]any             // tokens, summary, error.type, error.message, ...
    SubActions []string                   // ring buffer (cap 10)
}

type SpanStatus int
const (
    StatusInFlight SpanStatus = iota
    StatusSuccess
    StatusError
    StatusCancelled
)

type Category int
const (
    CategorySearch Category = iota
    CategoryRead
    CategoryWrite
    CategoryCommand
    CategoryRole
    CategoryLLM
    CategoryOther
)
```

#### Step 3: Messages

**File**: `activity/messages.go`

```go
type SpanStartMsg struct {
    SpanID     string
    ParentID   string
    Name       string
    Attributes map[string]any
    Timestamp  time.Time         // from SpanEventBlock.timestamp (not time.Now())
}

type SpanEndMsg struct {
    SpanID     string
    Status     string             // "success" | "error" | "cancelled"
    Attributes map[string]any    // final tokens, summary, error.type, error.message
    Timestamp  time.Time
}

type SpanAttributeDeltaMsg struct {
    SpanID string
    Tokens int                   // from kukuvaia.tokens.delta
}

type SseDisconnectedMsg struct{}  // SSE stream dropped — reset tracker
type ToggleExpandMsg struct{}
type TickMsg time.Time
```

SSE decoder maps `SpanEventBlock.phase = "start" | "end" | "delta"` to these. Stream-level errors / EOF on SSE connection emit `SseDisconnectedMsg`.

#### Step 4: Update logic

**File**: `activity/update.go`

Handlers:
- `SpanStartMsg` → create `SpanNode` with `StartedAt = msg.Timestamp` (NOT `time.Now()` — preserves accurate elapsed time across network delay), insert into `nodes`, attach to parent's `ChildIDs` or `rootIds`, pick verb if tree was empty, start ticker
- `SpanEndMsg` → set `EndedAt = msg.Timestamp`, record `Status`, merge final attributes (including `error.type`/`error.message` when status=`error`), push summary to parent's `SubActions`
- `SpanAttributeDeltaMsg` → increment tokens on node
- Top-level span end AND no in-flight spans (well-formed spans OTel guarantee) → **reset** (clear tree, stop ticker)
- `SseDisconnectedMsg` → **hard reset**: clear `nodes`, `rootIds`, in-flight state, stop ticker. Any in-flight spans are abandoned (engine will not resume them — reconnect starts fresh)
- `ToggleExpandMsg` → `expanded = !expanded`
- `TickMsg` → `frame++`, schedule next tick only while any span in-flight

Aggregation helpers (computed, not stored):
- `func (m Model) totalTokens() int` — sum across all nodes
- `func (n *SpanNode) categoryCountsFromDescendants(m Model)` — rollup for summary line

#### Step 5: Span-name → category mapping

**File**: `activity/categorize.go`

```go
// Span names follow engine convention (see P01 SpanEventBlock extension):
//   tool:<name>       → leaf tool call
//   role:<specialist> → sub-agent branch
//   llm:<provider>    → LLM call leaf
//   memory:<op>       → memory operation

func Categorize(spanName string) Category {
    prefix, rest, _ := strings.Cut(spanName, ":")
    switch prefix {
    case "role":   return CategoryRole
    case "llm":    return CategoryLLM
    case "tool":   return toolCategory(rest)   // search/read/write/command/other
    case "memory": return CategoryRead
    }
    return CategoryOther
}
```

Tool subcategorization via static map (`grep → Search`, `read_file → Read`, `bash_run → Command`, …). MCP-tool fallback = Other; config map overrides.

#### Step 6: Keybinding integration

Wire `ctrl+o` in root model's `KeyMsg` handler → dispatch `ToggleExpandMsg`.

#### Acceptance criteria
- [ ] Span tree assembles correctly from stream (unit tests with synthetic events)
- [ ] `StartedAt` sourced from `SpanEventBlock.timestamp`, not local `time.Now()` — elapsed time preserved across network delay
- [ ] Flat (single-role) case matches claude-code semantics
- [ ] Nested case (supervisor + 2 workers) builds tree with correct parentage
- [ ] Ticker starts on first span, stops when tree empty
- [ ] `SubActions` ring bounded; old entries dropped
- [ ] Reset fires cleanly at end of top-level span batch
- [ ] `SseDisconnectedMsg` triggers hard reset — no stale in-flight nodes survive reconnect
- [ ] `status="cancelled"` spans transition node to `StatusCancelled`, not `StatusError`
- [ ] `status="error"` spans capture `kukuvaia.error.type` + `kukuvaia.error.message` in node attributes
- [ ] Concurrent events from parallel workers produce correctly-parented tree (no cross-talk)
- [ ] No goroutines leak when model discarded

---

### Phase 2 — View, Theming, Multi-Role Rendering (Effort: M, days)

**Goal**: Render collapsed + expanded states with theme-driven styling, including nested span tree.

#### Step 1: Verb list

**File**: `activity/verbs.go`

Start with 50 verbs curated to match Matrix aesthetic (cyberpunk flavor alongside classics). User override: `~/.kukuvaia/verbs.txt` (one per line) loaded at startup if present.

#### Step 2: Summary text generation

**File**: `activity/view.go`

```go
// For a role-branch node, summarize its descendants' categories:
func summarizeBranch(m Model, node *SpanNode) string {
    counts := aggregateLeafCategories(m, node)
    parts := []string{}
    if counts.Search > 0 { parts = append(parts, plural(counts.Search, "pattern")) }
    if counts.Read   > 0 { parts = append(parts, plural(counts.Read,   "file"))    }
    // ...
    return "searching " + join(parts) + "…"
}
```

- Top-level with multiple role children: *"orchestrating N workers"*
- Leaf-heavy tree (no role children): flat summary of all leaves

#### Step 3: Collapsed layout

```
● {top-level-summary}  (ctrl+o to expand)
* {verb} ({elapsed}s · ↑ {total-tokens} tokens · thought for {thinking}s)
```

#### Step 4: Expanded layout (multi-role aware)

```
● {top-level-summary}  (ctrl+o to collapse)
  ├─ {child-role-1-summary}  ({elapsed}s · ↑ {tokens})
  │   └ {subaction}
  │   └ {subaction}
  ├─ {child-role-2-summary}  ({elapsed}s · ↑ {tokens})
  │   └ {subaction}
  └─ {self-role-label}: {state}  ({elapsed}s · ↑ {tokens})
* {verb} ({elapsed}s total · ↑ {total} tokens across {n} roles)
```

Tree characters `├─`, `└─`, `│` styled with `colors.muted`. Leaf sub-actions shown with `└`.

Depth cap: 3 (supervisor → worker → tool). Deeper collapsed as `… +N deeper`.

**Per-node status rendering**:

| Status | Leaf prefix | Branch prefix | Color token |
|--------|-------------|---------------|-------------|
| `InFlight` | `└` | `├─` / `└─` | `colors.primary` (active) |
| `Success` | `└` | `├─` / `└─` | `colors.muted` (settled) |
| `Error` | `✗` | `✗─` | `colors.error` (#FF4444) |
| `Cancelled` | `⊘` | `⊘─` | `colors.muted` (dimmed) |

Error node example (expanded):
```
● supervisor: orchestrating 2 workers  (ctrl+o to collapse)
  ├─ worker/research: searching 3 patterns  (12s · ↑ 340 tokens)
  │   └ kukuvaia-cli/internal/tui/app.go
  ✗─ worker/code: 1 error  (8s · ↑ 120 tokens)
      ✗ bash_run: command exited 1 — "permission denied: /etc/shadow"
* Jitterbugging (20s · ↑ 460 tokens · 1 error)
```

**Error propagation rule**: errors DO NOT change the parent's symbol — supervisor keeps `●` even if a worker failed. Instead, top-level animator appends `· N error(s)` count. This keeps the shape stable (user still sees orchestration is proceeding) while surfacing the failure.

**Cancelled propagation**: same rule — top-level animator appends `· cancelled` when user aborted. All in-flight nodes transition to `Cancelled` on `SseDisconnectedMsg` OR explicit `status="cancelled"` from engine.

#### Step 5: Lipgloss styles from theme

All colors sourced from `kukuvaia-theme.yaml` → `styles_gen.go` via `go generate`:
- Header bullet `●` → `colors.primary` (phosphor green)
- Tree characters → `colors.muted` (dark green)
- Keybinding hint → `colors.muted` italic
- Verb animator `*` → `colors.primary` with spinner frame
- Timer/token stats → `colors.muted`
- Error symbol `✗` and error messages → `colors.error` (#FF4444 — already in theme)
- Cancelled symbol `⊘` and cancelled summaries → `colors.muted` (dimmed, no distinct color needed)
- Error count in animator (*"· 1 error"*) → `colors.error`

No hardcoded hex values in Go source (per `standards/frontend/css.md`).

#### Step 6: Integration with root TUI

Root `tui.Model` embeds `activity.Model`. Render position: above input prompt, below conversation history. Configurable `max-subactions-per-node` cap, overflow `… +N more`. Idle state (empty tree) hides the component entirely — no blank line.

#### Acceptance criteria
- [ ] Collapsed single-role renders on two lines (summary + animator)
- [ ] Expanded multi-role shows nested tree with correct box-drawing
- [ ] Error node renders with `✗` symbol in `colors.error`; error message surfaced on leaf line
- [ ] Cancelled node renders with `⊘` symbol in `colors.muted`
- [ ] Error count appears in animator line (*"· N error(s)"*) in `colors.error`
- [ ] Top-level symbol stays `●` even when child errored (stable shape during orchestration)
- [ ] `ctrl+o` toggles expansion live
- [ ] Theme regeneration (`go generate`) propagates color changes
- [ ] Verb override file loaded when present, defaults otherwise
- [ ] Terminal resize (`tea.WindowSizeMsg`) re-truncates correctly
- [ ] Idle state hides component entirely

---

## Configuration Reference

| Property / File | Default | Description |
|-----------------|---------|-------------|
| `~/.kukuvaia/verbs.txt` | built-in list | Custom spinner verbs, one per line |
| `KUKUVAIA_ACTIVITY_DISABLED` | `false` | Disable tracker entirely (non-interactive output) |
| `activity.max-subactions-collapsed` | `3` | Sub-actions shown in collapsed root |
| `activity.max-subactions-expanded` | `8` | Sub-actions shown per expanded node |
| `activity.max-depth` | `3` | Tree-rendering depth cap |
| `activity.tick-ms` | `50` | Spinner frame interval |

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Span events arrive out of order (child before parent) | Orphan nodes in tree | Buffer orphans briefly; attach when parent arrives; drop after 1 s |
| Long sub-agent chain (depth 5+) | Cluttered tree | Hard-cap depth at 3; collapse deeper as `… +N deeper` |
| Event flood from parallel tool calls | UI flicker, CPU burn | Coalesce `AttributeDeltaMsg` per tick; Bubbletea batches renders |
| Long tool summaries break layout | Line wrap corrupts display | Truncate to `terminalWidth − indent − 4` with `…` suffix |
| Terminal width changes during render | Garbled output | Subscribe to `tea.WindowSizeMsg`; re-compute truncations |
| Verb list customization typos | Crash at startup | Validate + warn + fallback to defaults; never crash |
| Engine emits without end event (crash mid-span) | In-flight nodes stuck | Garbage-collect spans with no activity for >60 s (log as "abandoned") |
| Categorization misses custom MCP tool names | Everything tagged "other" | Config map `activity.categories.overrides`; document in integrations |

## Open Questions

1. **Should sub-actions persist across top-level span boundaries or reset with tree?**
   claude-code resets. Leaning: **reset** — each turn is its own summary.

2. **Render LLM spans as their own branch or inline?**
   Showing `llm:openai` as a tree child seems noisy.
   Leaning: **hide `llm:*` spans in tree; surface only tokens in animator line**.

3. **Contextual verbs per task type?**
   "Compiling" verbs for build, "Querying" for DB.
   Leaning: **no — random delight is part of the aesthetic**.

4. **Programmatic JSON mode (`kukuvaia --json`) emitting span events?**
   Useful for IDE/editor integrations.
   Leaning: **yes eventually, out of scope for P01.1 — track as future P-task**.

## Future Considerations

- **Web UI counterpart** — same `SpanEventBlock` stream renders in future `kukuvaia-web`; protocol is transport-agnostic
- **Per-tool latency sparkline** — tiny ASCII chart of tokens/sec over recent window
- **Span detail drill-down** — keyboard navigation into a specific span to see full attributes
- **Click-to-expand on web** — browser client expands sub-actions inline without keybinding
- **Persist last activity** — option to keep final tree visible after completion for post-hoc review
