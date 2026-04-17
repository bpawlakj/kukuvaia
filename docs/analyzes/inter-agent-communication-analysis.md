# Analysis: Inter-Agent Communication — kukuvaia as Agent Orchestrator

**Created**: 2026-04-12
**Status**: Concept analysis

## Question

Does it make sense for kukuvaia to spawn and control other agents (like Claude Code)? If so, how to build a communication bridge?

## Short Answer

**Yes, it makes sense** — but not by "launching Claude Code as a daemon." Instead, kukuvaia should position itself as an **orchestration hub** that communicates with external agents via standardized protocols (MCP, A2A). The bridge exists through protocols, not process management.

## Why Process Spawning Is Wrong

The naive approach — kukuvaia spawns `claude -p "fix the bug"` as a subprocess — has fundamental problems:

| Problem | Why |
|---------|-----|
| **No shared context** | Claude Code starts fresh. No access to kukuvaia's memory, session, harness rules |
| **No streaming** | Subprocess stdout gives you blob output, not real-time progress |
| **No tool sharing** | Claude Code uses its own tools. Can't call kukuvaia's memory/planning tools |
| **No cost control** | Separate process = separate token budget. kukuvaia can't enforce daemon budget |
| **No security** | Claude Code has full filesystem access. kukuvaia's SubAgentGuard is bypassed |
| **Process management** | Zombie processes, timeouts, resource leaks, platform-specific behavior |
| **Tight coupling** | Depends on `claude` CLI being installed. Breaks if API changes |

Spawning a subprocess treats another agent as a **black box CLI tool**. That's not inter-agent communication — that's shell scripting with extra steps.

## What Inter-Agent Communication Actually Means

Real inter-agent communication requires:

1. **Discovery** — agents find each other and learn capabilities
2. **Context sharing** — agents share relevant state without exposing everything
3. **Task delegation** — structured work requests with clear deliverables
4. **Progress streaming** — real-time updates, not just final result
5. **Result integration** — output from one agent feeds into another's context
6. **Security boundaries** — each agent has scoped access, no escalation

Three protocols exist for this. All three are relevant to kukuvaia.

## Protocol Analysis

### Protocol 1: MCP (Model Context Protocol) — Tool-Level Bridge

**What it is**: Anthropic's standard for sharing tools between AI systems. Client-server model where servers expose tools, clients call them.

**kukuvaia already has both sides**:
- `spring-ai-starter-mcp-server-webmvc` — kukuvaia exposes tools via MCP (memory, planning)
- `spring-ai-starter-mcp-client` — kukuvaia can connect to external MCP servers

**How it works for inter-agent communication**:

```
┌──────────────────┐         MCP (SSE/HTTP)        ┌──────────────────┐
│    kukuvaia       │◄────────────────────────────►│   Claude Code     │
│                   │                               │                   │
│  MCP Server:      │  Claude Code connects as      │  MCP Client:      │
│  - saveMemory     │  MCP client and uses          │  - calls kukuvaia │
│  - searchMemories │  kukuvaia's tools             │    tools          │
│  - createPlan     │                               │  - shares results │
│  - completeStep   │                               │    via tool calls │
│                   │                               │                   │
│  MCP Client:      │  kukuvaia connects as         │  MCP Server:      │
│  - calls external │  MCP client to other agents   │  (if exposed)     │
│    tools          │                               │                   │
└──────────────────┘                               └──────────────────┘
```

**Concrete scenario**: kukuvaia delegates "fix bug in repo X" to Claude Code:
1. kukuvaia starts a daemon task with goal: "fix authentication bug"
2. kukuvaia exposes context via MCP tools: `getTaskContext()`, `getRelevantMemories()`, `getActiveRules()`
3. Claude Code (configured with kukuvaia as MCP server) starts working
4. Claude Code calls `kukuvaia.searchMemories("authentication bug patterns")` — gets relevant context
5. Claude Code calls `kukuvaia.createPlan("Fix auth bug", steps)` — registers plan
6. Claude Code fixes the bug, calls `kukuvaia.completeStep(planId, stepId)` — reports progress
7. kukuvaia monitors plan progress, knows when task is done

**Advantage**: Both agents keep their own LLM, tools, and security. Communication is structured (tool calls with schemas). kukuvaia's security applies to what it exposes.

**Limitation**: MCP is tool-centric, not task-centric. There's no native concept of "delegate this task." You model delegation as tool calls.

### Protocol 2: A2A (Agent-to-Agent Protocol) — Task-Level Bridge

**What it is**: Google's open standard (2025) for agent-to-agent communication. Purpose-built for task delegation, not just tool sharing.

**Core concepts**:
- **Agent Card** — JSON describing agent capabilities, endpoint, auth
- **Task** — work unit with lifecycle (submitted → working → completed/failed)
- **Message** — communication within a task (text, files, structured data)
- **Streaming** — SSE for real-time progress updates

**How it works**:

```
┌──────────────────┐         A2A (HTTP + SSE)       ┌──────────────────┐
│    kukuvaia       │──────────────────────────────►│   Agent B         │
│  (orchestrator)   │                               │  (worker)         │
│                   │  POST /a2a/tasks               │                   │
│  1. Discover      │  { task: "fix auth bug",      │  Receives task,   │
│     agent card    │    context: {...},             │  works on it,     │
│  2. Submit task   │    artifacts: [...] }          │  streams progress │
│  3. Stream        │                               │                   │
│     progress      │  SSE: task.progress            │  Returns result   │
│  4. Get result    │  SSE: task.completed           │  with artifacts   │
└──────────────────┘                               └──────────────────┘
```

**Advantage**: Purpose-built for delegation. Task lifecycle management. Streaming. Artifacts (files, code).

**Limitation**: Relatively new (2025). Not yet widely adopted. Claude Code doesn't support A2A natively.

**Embabel connection**: Embabel 0.3.4 documentation mentions A2A protocol support. This means kukuvaia-agents could implement A2A endpoints using Embabel's built-in capabilities.

### Protocol 3: Hybrid MCP + Custom Task API

**Most practical for kukuvaia**: Combine MCP (already implemented) with a custom task delegation API.

```
┌──────────────────────────────────────────────┐
│              kukuvaia-engine                   │
│                                               │
│  ┌────────────────┐  ┌────────────────────┐  │
│  │  MCP Server     │  │  Task Delegation   │  │
│  │  (tools)        │  │  API               │  │
│  │                 │  │                     │  │
│  │  Memory tools   │  │  POST /api/tasks    │  │
│  │  Planning tools │  │  GET  /api/tasks/id │  │
│  │  Context tools  │  │  SSE  /api/tasks/   │  │
│  │                 │  │       id/stream     │  │
│  └────────────────┘  └────────────────────┘  │
│         ▲                      ▲              │
│         │                      │              │
└─────────┼──────────────────────┼──────────────┘
          │                      │
    MCP protocol           HTTP/SSE
          │                      │
┌─────────▼──────┐  ┌───────────▼──────────────┐
│  Claude Code   │  │  Any HTTP agent           │
│  (MCP client)  │  │  (custom, LangChain, etc) │
└────────────────┘  └──────────────────────────┘
```

## Architecture: kukuvaia as Orchestration Hub

### Role Definition

kukuvaia is NOT a coding agent. kukuvaia is an **orchestrator** that:
- Holds persistent context (memory, harness rules, user preferences)
- Makes strategic decisions (what needs to be done, in what order)
- Delegates execution to specialized agents
- Monitors progress and handles failures
- Consolidates results

This maps to the **hierarchical model routing** plan:
- kukuvaia (Sonnet/supervisor) decides what to do
- External agents (workers) execute specific tasks
- kukuvaia (Opus/advisor) helps when workers get stuck

### Communication Patterns

#### Pattern 1: kukuvaia → External Agent (Task Delegation)

```
kukuvaia decides: "repo X needs bug fix in AuthService"
    │
    ▼
Task Delegation Service
    │
    ├── Create task record in DB (status: SUBMITTED)
    ├── Resolve target agent (from agent registry)
    ├── Send task via agent's protocol:
    │     MCP: call agent's tools directly
    │     A2A: POST task to agent's endpoint
    │     CLI: run agent command with structured input
    │
    ├── Stream progress (SSE / polling)
    │     Update task record (status: WORKING, progress: 40%)
    │
    └── Receive result
          Update task record (status: COMPLETED)
          Extract artifacts (code changes, reports)
          Feed into kukuvaia's memory
```

#### Pattern 2: External Agent → kukuvaia (Context Request)

```
Claude Code working on a task needs context:
    │
    ▼
MCP tool call: kukuvaia.searchMemories("auth patterns")
    │
    ▼
kukuvaia returns relevant memories, rules, past fixes
    │
    ▼
Claude Code uses context to make better decisions
```

#### Pattern 3: Agent ↔ Agent via kukuvaia (Orchestrated Collaboration)

```
kukuvaia orchestrates two agents working together:

    kukuvaia (orchestrator)
        │
        ├── Delegate to Agent A: "write implementation"
        │     Agent A produces code
        │     kukuvaia receives result
        │
        ├── Delegate to Agent B: "review Agent A's code"
        │     kukuvaia passes Agent A's output as context
        │     Agent B produces review
        │     kukuvaia receives result
        │
        └── If review has issues:
              Re-delegate to Agent A: "fix issues from review"
              Loop until satisfactory
```

kukuvaia never lets agents talk directly. All communication goes through kukuvaia (star topology, not mesh). This ensures:
- Central logging and audit trail
- Token budget enforcement
- Security boundary maintenance
- Context consistency

### Agent Registry

Like the provider/model registry, kukuvaia needs an **agent registry**:

```sql
CREATE TABLE kukuvaia.agents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL UNIQUE,
    type            VARCHAR(50) NOT NULL,       -- 'claude-code', 'custom-mcp', 'a2a', 'cli'
    description     VARCHAR(500),
    capabilities    JSONB DEFAULT '[]',         -- ["coding","review","testing","devops"]
    endpoint        VARCHAR(500),               -- MCP/A2A endpoint URL or CLI path
    auth            JSONB DEFAULT '{}',         -- auth config (never raw secrets)
    config          JSONB DEFAULT '{}',         -- agent-specific config
    enabled         BOOLEAN DEFAULT TRUE,
    health_status   VARCHAR(20) DEFAULT 'unknown',
    last_health_at  TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE kukuvaia.agent_tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id        UUID NOT NULL REFERENCES kukuvaia.agents(id),
    parent_task_id  UUID REFERENCES kukuvaia.agent_tasks(id),  -- for chained tasks
    goal            TEXT NOT NULL,
    context         JSONB DEFAULT '{}',         -- memories, rules, artifacts passed
    status          VARCHAR(20) DEFAULT 'submitted',
    progress        INT DEFAULT 0,              -- 0-100
    result          JSONB,                      -- structured result
    artifacts       JSONB DEFAULT '[]',         -- files, diffs, reports produced
    token_usage     INT DEFAULT 0,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

## Concrete Bridge Implementations

### Bridge 1: Claude Code via MCP (Recommended First)

**Setup**: kukuvaia runs as MCP server. Claude Code connects to it.

kukuvaia already has `spring-ai-starter-mcp-server-webmvc`. Needs:
1. Expose task-oriented MCP tools (not just memory/planning)
2. Configure Claude Code to connect to kukuvaia MCP server

**New MCP tools to expose**:

```java
@Tool(description = "Get current task context — goal, relevant memories, active rules")
public TaskContext getTaskContext(
    @ToolParam(description = "Task ID") String taskId) {
    // Returns structured context for the working agent
}

@Tool(description = "Report task progress")
public void reportProgress(
    @ToolParam(description = "Task ID") String taskId,
    @ToolParam(description = "Progress 0-100") int progress,
    @ToolParam(description = "Status message") String message) {
    // Updates agent_tasks record, triggers SSE event
}

@Tool(description = "Submit task result with artifacts")
public void submitResult(
    @ToolParam(description = "Task ID") String taskId,
    @ToolParam(description = "Result summary") String summary,
    @ToolParam(description = "Artifacts as JSON") String artifacts) {
    // Completes the task, stores artifacts
}

@Tool(description = "Request help from orchestrator when stuck")
public String requestHelp(
    @ToolParam(description = "Task ID") String taskId,
    @ToolParam(description = "What you're stuck on") String problem) {
    // kukuvaia uses Opus (advisor) to help, returns guidance
}
```

**Claude Code `.mcp.json` configuration**:
```json
{
  "mcpServers": {
    "kukuvaia": {
      "url": "http://localhost:8080/mcp",
      "transport": "sse"
    }
  }
}
```

**Flow**:
```
1. User tells kukuvaia: "fix the auth bug in kukuvaia-engine"
2. kukuvaia creates agent_task record
3. kukuvaia spawns Claude Code session (or user starts one manually)
4. Claude Code connects to kukuvaia MCP, calls getTaskContext(taskId)
5. Claude Code works on the fix, calls reportProgress() periodically
6. If stuck, calls requestHelp() — kukuvaia uses Opus to advise
7. Claude Code calls submitResult() with the fix
8. kukuvaia stores result, updates memory
```

### Bridge 2: Claude Code via CLI (Simpler, Less Capable)

**Setup**: kukuvaia calls Claude Code's SDK/CLI programmatically.

Claude Code has a non-interactive mode:
```bash
claude -p "Fix the auth bug" --output-format json
```

But more powerfully, the **Claude Code SDK** (TypeScript/Python) allows programmatic control:
```python
# Claude Code SDK
from claude_code import ClaudeCode
session = ClaudeCode(project_dir="/path/to/repo")
result = session.send("Fix the auth bug in AuthService.java")
```

kukuvaia could have a **ClaudeCodeBridge** that:
1. Runs Claude Code SDK in a subprocess
2. Passes structured task description
3. Monitors output (JSON streaming)
4. Captures result

**Limitation**: No MCP tool sharing in this mode. Claude Code works independently. kukuvaia only gets the final output.

### Bridge 3: A2A Protocol (Future, Most Complete)

**Setup**: kukuvaia implements A2A server and client. External agents expose A2A endpoints.

Embabel 0.3.4 mentions A2A support. When mature:
1. kukuvaia-agents module implements A2A server (Kotlin/Embabel)
2. kukuvaia publishes an Agent Card describing its capabilities
3. External agents discover kukuvaia and submit/receive tasks
4. Full lifecycle management with streaming

**When to implement**: When A2A ecosystem matures and agents start exposing A2A endpoints. Currently too early — few agents support it.

### Bridge 4: Generic HTTP Agent (Any Agent)

**Setup**: kukuvaia has a generic HTTP bridge for agents that expose a REST API.

```java
public interface AgentBridge {
    AgentTaskResult submitTask(AgentTask task);
    AgentTaskStatus getStatus(String taskId);
    Stream<AgentProgress> streamProgress(String taskId);
}

// Implementations:
class McpAgentBridge implements AgentBridge { ... }
class CliAgentBridge implements AgentBridge { ... }
class HttpAgentBridge implements AgentBridge { ... }
class A2aAgentBridge implements AgentBridge { ... }
```

Each bridge type implements the same interface. The agent registry specifies which bridge type to use per agent.

## Security Considerations

### What kukuvaia Exposes via MCP

**Safe to expose**:
- Memory search (read-only, user-scoped)
- Task context (read-only, task-scoped)
- Progress reporting (write to own task only)
- Help requests (creates advisor consultation)

**Never expose**:
- Direct database access
- Provider credentials
- Other users' data
- Administrative operations

### Agent Authentication

External agents connecting to kukuvaia's MCP server need authentication:
- **API key** per registered agent (stored in agent registry)
- **Task-scoped tokens** — agent gets a token for specific task, can only access that task's context
- **Rate limiting** — per-agent rate limits (reuse existing webhook rate limiter pattern)

### Cost Control

Each external agent task runs under kukuvaia's daemon budget:
- Task creation checks `DaemonBudgetGuard`
- kukuvaia's token usage for orchestration/advice counted
- External agent's token usage is NOT counted (external agent's own budget)
- But task count and frequency ARE limited

## Relationship to Existing Architecture

```
Provider Registry (DB-driven models)
  └── Agent tasks can specify preferred model for kukuvaia's orchestration calls

Model Routing (hierarchical)
  └── Orchestration uses Sonnet (supervisor)
  └── Help requests escalate to Opus (advisor)
  └── External agents handle execution (workers)

Harness Engineering (rules)
  └── Active rules injected into task context for external agents
  └── Agent-specific rules (e.g., "Claude Code should use conventional commits")

Dreaming
  └── Cross-service inspection checks agent health
  └── Model scout considers external agents' model usage

Daemon System (existing)
  └── Agent tasks ARE daemon tasks (extend DaemonAgentService)
  └── Budget guard applies
  └── Schedule guard prevents duplicate tasks
```

## Implementation Phases

### Phase A: Agent Registry + Task Management
- Database schema (agents, agent_tasks tables)
- CRUD API for agent registration
- Task lifecycle management (submit → working → completed)
- Task context compilation (memories + rules + artifacts)

### Phase B: MCP Bridge (kukuvaia as server)
- Expose task-oriented MCP tools (getTaskContext, reportProgress, submitResult, requestHelp)
- Authentication for MCP connections
- Test with Claude Code as MCP client

### Phase C: Orchestration Logic
- Task delegation service (decide which agent handles what)
- Progress monitoring and timeout handling
- Failure recovery (retry, reassign to different agent)
- Result integration into kukuvaia memory

### Phase D: CLI Bridge
- Claude Code SDK/CLI integration
- Subprocess management with timeout
- Structured output parsing

### Phase E: A2A Protocol
- Embabel-based A2A server implementation
- Agent Card publication
- Task streaming via SSE
- External agent discovery

## Decision Matrix: Which Bridge First?

| Bridge | Effort | Capability | Ecosystem Ready? | Recommendation |
|--------|--------|-----------|-----------------|----------------|
| MCP Server (kukuvaia exposes tools) | Low | Medium | Yes (Claude Code supports MCP) | **Start here** |
| CLI Bridge (subprocess) | Low | Low | Yes | Fallback option |
| A2A Protocol | High | High | Not yet (early 2025) | Wait for ecosystem |
| Generic HTTP | Medium | Medium | Depends on agent | As needed |

**Recommended order**: MCP Server → CLI Bridge → A2A Protocol

## Concrete Flow: Daemon Launches Claude Code with Full Context

This is the validated, production-ready flow based on Claude Code's actual capabilities.

### What the Daemon Does

```
DaemonAgentService.executeExternalAgent(task)
    │
    ├── 1. PREPARE WORKSPACE
    │   ├── mkdir /tmp/kukuvaia-task-{uuid}
    │   ├── git clone {repo_url} → workspace/
    │   │
    │   ├── Generate .claude/ in workspace:
    │   │   │
    │   │   ├── CLAUDE.md
    │   │   │   Compiled from harness rules (DB):
    │   │   │   - Platform rules (Level 1)
    │   │   │   - Group rules (Level 2, e.g., "java-backend-team")
    │   │   │   - User rules (Level 3, preferences)
    │   │   │   - Task-specific instructions
    │   │   │
    │   │   └── settings.json (placeholder for future use)
    │   │
    │   └── Generate mcp.json:
    │       {
    │         "mcpServers": {
    │           "kukuvaia": {
    │             "type": "sse",
    │             "url": "http://localhost:8080/mcp/sse",
    │             "headers": {
    │               "Authorization": "Bearer {task-scoped-token}"
    │             }
    │           }
    │         }
    │       }
    │
    ├── 2. LAUNCH CLAUDE CODE (--bare mode)
    │   │
    │   │   claude --bare \
    │   │     -p "Fix authentication bug in AuthService. Task ID: {uuid}" \
    │   │     --output-format stream-json \
    │   │     --mcp-config ./mcp.json \
    │   │     --allowedTools "Read,Edit,Bash(git *),mcp__kukuvaia__*" \
    │   │     --append-system-prompt "$(cat .claude/CLAUDE.md)"
    │   │
    │   │   Key flags:
    │   │     --bare         → skip auto-discovery, deterministic behavior
    │   │     --mcp-config   → connect to kukuvaia MCP server
    │   │     --allowedTools → scoped permissions (no rm -rf, no push --force)
    │   │     --output-format stream-json → real-time progress monitoring
    │   │
    │   │
    ├── 3. MONITOR (parse stream-json output)
    │   │
    │   │   for each line in stdout:
    │   │     {"type":"system","subtype":"init","mcp_servers":[{"name":"kukuvaia","status":"connected"}]}
    │   │     → Verify MCP connection succeeded
    │   │
    │   │     {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__kukuvaia__getTaskContext",...}]}}
    │   │     → Claude Code is fetching context from kukuvaia
    │   │
    │   │     {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit",...}]}}
    │   │     → Claude Code is editing files
    │   │
    │   │     {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__kukuvaia__reportProgress",...}]}}
    │   │     → Update agent_tasks record (progress: 60%)
    │   │
    │   │     {"type":"assistant","message":{"content":[{"type":"tool_use","name":"mcp__kukuvaia__requestHelp",...}]}}
    │   │     → kukuvaia calls Opus for strategic advice, returns guidance
    │   │
    │   │     {"type":"result","subtype":"success","result":"Fixed the auth bug...","session_id":"..."}
    │   │     → Task complete, capture result
    │   │
    ├── 4. COLLECT RESULT
    │   ├── Parse final JSON result
    │   ├── git diff → capture code changes as artifacts
    │   ├── Store result + artifacts in agent_tasks table
    │   ├── Feed summary into kukuvaia memory (procedural: "how we fixed auth bugs")
    │   │
    └── 5. CLEANUP
        ├── rm -rf /tmp/kukuvaia-task-{uuid} (or keep for review)
        └── Update agent_tasks status → COMPLETED
```

### Alternative: Claude Code SDK (Python) Instead of CLI

For tighter integration, the daemon can use the Agent SDK directly:

```python
# This runs inside kukuvaia as a Python subprocess or sidecar
import asyncio
from claude_agent_sdk import query, ClaudeAgentOptions

async def execute_task(task_id: str, prompt: str, workspace: str):
    options = ClaudeAgentOptions(
        bare=True,
        mcp_servers={
            "kukuvaia": {
                "type": "sse",
                "url": "http://localhost:8080/mcp/sse",
                "headers": {"Authorization": f"Bearer {task_token}"}
            }
        },
        allowed_tools=["Read", "Edit", "Bash(git *)", "mcp__kukuvaia__*"],
        output_format="stream-json",
        cwd=workspace,
    )

    async for message in query(prompt=prompt, options=options):
        if message.type == "tool_use" and "mcp__kukuvaia__reportProgress" in str(message):
            # Progress already sent to kukuvaia via MCP — just log
            pass
        elif message.type == "result":
            return message.result

    return None
```

### What Claude Code Sees (System Prompt)

The `--append-system-prompt` flag injects harness rules. Claude Code starts with:

```
[Claude Code's default system prompt]

## Project Context (from kukuvaia harness)

### Team Standards (java-backend-team)
- Constructor injection only. All fields final. Never use @Autowired on fields.
- JUnit 5 + AssertJ. Test naming: methodName_scenario_expectedBehavior().
- 80%+ coverage for security-critical code.

### User Preferences (bartek)
- Senior developer. Skip basic explanations. Be concise.

### Task Instructions
- Task ID: {uuid} — report progress via mcp__kukuvaia__reportProgress
- When stuck, call mcp__kukuvaia__requestHelp with description of the problem
- When done, call mcp__kukuvaia__submitResult with summary and artifacts
- Use conventional commits. Do not push — only commit locally.

### Available kukuvaia Tools
- mcp__kukuvaia__getTaskContext — get memories and context relevant to this task
- mcp__kukuvaia__searchMemories — search for past solutions and patterns
- mcp__kukuvaia__reportProgress — report progress (0-100)
- mcp__kukuvaia__requestHelp — escalate to senior advisor (Opus) when stuck
- mcp__kukuvaia__submitResult — submit final result with artifacts
```

### Security: Task-Scoped Tokens

Each Claude Code session gets a **short-lived, task-scoped JWT**:

```java
public String generateTaskToken(UUID taskId, UUID agentId) {
    return Jwts.builder()
        .subject(agentId.toString())
        .claim("taskId", taskId.toString())
        .claim("scope", "task:read,task:write,memory:read")
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + 3600_000)) // 1 hour
        .signWith(secretKey)
        .compact();
}
```

kukuvaia's MCP endpoint validates: token is valid, taskId matches, scope allows the operation. Claude Code cannot access other tasks, other users' memories, or admin operations.

### Cost Control

- Claude Code uses its own Anthropic API key (ANTHROPIC_API_KEY) — separate billing
- kukuvaia's token budget only counts its own LLM calls (orchestration, Opus advice)
- Task count and frequency limited by DaemonScheduleGuard
- Timeout: daemon kills the subprocess after configurable limit (default 30 min for external agents)

## Recommended: Internal Daemon (Option 3) Over External Agent Spawning

### Why Same-Process Daemon Wins

The analysis above covers external agent bridges (MCP, CLI, A2A). But the **strongest option** is kukuvaia's own daemon — the same JVM, same Spring context, same everything:

```
kukuvaia-engine (single JVM)
┌──────────────────────────────────────────┐
│                                          │
│  Interactive context    Daemon context   │
│  ┌────────────────┐   ┌──────────────┐  │
│  │ AgentService   │   │ DaemonAgent  │  │
│  │ ChatClient     │   │ SubAgentFact │  │
│  │ Sonnet default │   │ Haiku worker │  │
│  │ User persona   │   │ Daemon pers. │  │
│  │ Full tools     │   │ Scoped tools │  │
│  └───────┬────────┘   └──────┬───────┘  │
│          │    SHARED STATE   │           │
│          └────────┬──────────┘           │
│          ┌────────▼──────────┐           │
│          │ Memory (pgvector) │           │
│          │ Provider Registry │           │
│          │ Harness Rules     │           │
│          │ Tool Registry     │           │
│          │ Model Routing     │           │
│          └───────────────────┘           │
└──────────────────────────────────────────┘
```

Compared to external agent spawning:

| Aspect | External (Claude Code) | Internal (daemon) |
|--------|----------------------|-------------------|
| Shared memory | Via MCP tool calls (latency) | Direct Java method call (0ms) |
| Shared providers | None — needs own API key | Same ChatModelCache |
| Shared harness | Generated CLAUDE.md (snapshot) | Live from DB (always current) |
| Token budget | Separate billing | Same DaemonBudgetGuard |
| Security | Subprocess + MCP auth | SubAgentGuard (in-process) |
| Overhead | Process spawn + HTTP + JSON serialize | Zero — same JVM |
| Cost | Requires Anthropic API key or Bedrock | Uses existing SmartGate — free |
| Model routing | Fixed model per session | Dynamic per-request (Haiku/Sonnet/Opus) |

### What's Missing for Internal Daemon to Code

The daemon already works (DaemonAgentService, SubAgentFactory, budget/schedule guards). Missing piece: **file and git @Tool methods**:

```
Existing @Tool:
  ✅ saveMemory, searchMemories, listMemories, deleteMemory
  ✅ createPlan, completeStep, revisePlan

Needed @Tool for coding tasks:
  ❌ readFile(path) → content
  ❌ writeFile(path, content)
  ❌ editFile(path, oldText, newText)
  ❌ listFiles(pattern) → paths
  ❌ searchContent(pattern, path) → matches
  ❌ bashRun(command) → output (sandboxed)
  ❌ gitStatus() → status
  ❌ gitDiff() → diff
  ❌ gitCommit(message)
```

This is ~400 lines of code in `FileTools.java` + `GitTools.java` with sandbox guards. After this, daemon can:

- **Dreaming**: `searchMemories + readFile(logs) + createPlan(recommendations)`
- **Bug fix**: `readFile → editFile → bashRun(tests) → gitCommit`
- **Code review**: `gitDiff → readFile(changed) → createPlan(findings)`
- **Refactoring**: `readFile → writeFile(new) → editFile(old) → bashRun(tests)`

### Security Guards Needed for File/Bash Tools

Existing guards (already built):

| Guard | Purpose |
|-------|---------|
| DaemonBudgetGuard | Token cost cap (100k/day) |
| DaemonScheduleGuard | Skip-if-running (no overlap) |
| SubAgentGuard (depth=1) | No recursive spawning |
| SubAgentGuard (tool filter) | Scoped tool access per specialist |
| ToolResultSanitizingAdvisor | Anti-injection in tool results |
| Prompt hardening | Anti-injection in system prompt |

Additional guards needed for file/bash:

| Guard | Purpose |
|-------|---------|
| WorkspaceSandbox | File ops only in allowed directories |
| BashAllowlist | Only whitelisted commands (git, gradle, npm — no rm, no curl) |
| PathTraversalGuard | Block `../` and symlink escapes |
| ReadOnlyMode | Dreaming = read-only, coding = read-write |
| GitGuard | No force push, no push to main, commit only |

### When External Agents Still Make Sense

Internal daemon is the default. External agents (Claude Code, others) for:

1. **Different repo** — daemon works on its own codebase, Claude Code on another repo the user doesn't want kukuvaia to access directly
2. **Different LLM** — specific task needs GPT-4 vision or a model not available on SmartGate
3. **Specialized agent** — a purpose-built agent (security scanner, performance profiler) that kukuvaia orchestrates but doesn't replicate
4. **Team scaling** — multiple developers each running their own Claude Code, all connected to shared kukuvaia for memory and rules

For these cases, the MCP bridge (documented above) provides the communication channel.

## Uniqueness Analysis

### What No Other Platform Combines

| Element | Who else has it | kukuvaia's difference |
|---------|----------------|----------------------|
| Agent + tools | Everyone | Standard — not unique |
| Persistent memory | OpenAI Assistants, MemGPT | kukuvaia: 3 types (episodic/semantic/procedural), pgvector, cross-session, with decay and consolidation |
| Multi-agent | CrewAI, AutoGen, LangGraph | kukuvaia: same-process shared state vs separate processes with HTTP overhead |
| Multi-model routing | LiteLLM, OpenRouter | kukuvaia: DB-driven roles (advisor/supervisor/worker) with automatic dispatch, not just proxy-level fallback |
| GOAP planning | Nobody in AI agent space | Embabel — deterministic A* planning from game AI. LLM inside actions, not for action sequencing |
| Daemon/background | n8n, Zapier AI | kukuvaia: same-process daemon, not workflow automation. Shared state, zero overhead |
| Dreaming | Nobody | Autonomous self-inspection: memory consolidation, model scouting, health checks, log analysis |
| Harness engineering | Claude Code (CLAUDE.md), Cursor (.cursorrules) | kukuvaia: 5-level hierarchy (platform→group→user→project→session), DB-driven, API-managed, conditional activation |
| Self-hosted + open source | Ollama, LocalAI | Those are inference-only. kukuvaia is a full agent platform with orchestration, memory, routing |

### The Combination Is the Differentiator

No single element is unprecedented. The combination doesn't exist anywhere:

```
GOAP planning (game AI)
  + same-process daemon (server architecture)
  + dreaming (neuroscience metaphor)
  + DB-driven model routing (infrastructure engineering)
  + layered harness (DevEx tooling + enterprise IAM)
  + persistent semantic memory (information retrieval)
  + MCP hub (emerging standard)
  + self-hosted open source (deployment model)
  ───────────────────────────────────────────
  = kukuvaia
```

Closest competitor: **Devin** — but closed-source, cloud-only, $500/mo, not extensible. kukuvaia is open source, self-hosted, extensible, and has elements (dreaming, GOAP, harness) Devin lacks.

## Key Insight

kukuvaia's value is NOT in executing tasks — it's in **knowing what to do, who should do it, and integrating the results**. The actual coding, testing, deploying can be done by internal daemon agents (primary) or external agents via MCP (when needed). kukuvaia provides:

1. **Persistent memory** — what happened before, what works, what doesn't
2. **Harness rules** — how this team/user/project operates
3. **Strategic decisions** — which agent, which model, which approach
4. **Escalation path** — when workers are stuck, bring in the big guns (Opus)
5. **Audit trail** — who did what, when, how much it cost

This positions kukuvaia as the **brain** (orchestration + memory + strategy) while daemon specialists and external agents are the **hands** (execution).
