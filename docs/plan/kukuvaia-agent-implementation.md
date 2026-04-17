# [ARCHIVED] Implementation Plan: kukuvaia (v2 — MCP-native)

> **Status:** ARCHIVED (2026-04-06)
> **Superseded by:** `kukuvaia-implementation-plan.md` (v3 — Java/Spring AI)
> **Reason:** Architecture changed from Python/FastAPI + separate MCP Gateway to single Java/Spring Boot server with in-process @McpTool. Sub-agent security, daemon infrastructure, and provider routing are already implemented in Java. This plan's Python code and MCP Gateway design are no longer applicable.
> **Preserved for:** Historical context, user extensibility design (rules/skills/commands/personas concepts carried forward), background task patterns.

## Context

ETSL content pipeline (sl-content-engine) has a working agent prototype in `api/chat.py`. This prototype needs to become a standalone, extensible product: separate repo, multiple personas, deterministic slash commands, structured output, CLI + Web API.

**Change vs v1:** Agent uses own MCP client engine for all tool execution. Tools are MCP servers (stdio), not hardcoded Python functions. New tools (k8s, aws, git, jira) = one line in persona YAML, zero Python code. Users can extend agent with custom rules, skills, and commands via `.kukuvaia/` project directory.

**Why MCP:** Agent will be used beyond ETSL — k8s ops, AWS management, other domains. Hardcoding each tool doesn't scale. MCP gives ecosystem compatibility (community servers) and clean extensibility.

**Why user extensibility:** Different projects have different rules ("never delete from MongoDB"), procedures (validation workflows), and shortcuts (custom commands). Users shouldn't need to modify agent source code to customize behavior.

---

## Architecture

```
User input → Router
  ├── /command args  → CommandRegistry → MCP tools/call → (optional) LLM summarizes
  └── free text      → SmartGate (tool_use) → Agent routes tool_calls → MCP Gateway

                    ┌─────────────────────────┐
SmartGate ←────────→│      Agent (Python)     │
(Claude Sonnet)     │  loop.py + router.py    │
                    └──────────┬──────────────┘
                               │ HTTP/SSE
                    ┌──────────┴──────────────┐
                    │  MCP Gateway (Java/      │
                    │  Spring Boot)            │
                    │  — aggregates all tools  │
                    │  — handles concurrency   │
                    │  — manages MCP clients   │
                    └──────────┬──────────────┘
              ┌────────────────┼────────────────┐
              ▼                ▼                 ▼
         PostgreSQL        MongoDB          Authorsuite
         (sl_content)      (etsl, r/o)      (3,955 rules)
              ▼                ▼                 ▼
         ... future: k8s, AWS, git, jira, ...
```

**Flow:** SmartGate returns `tool_calls` → Agent HTTP POST to MCP Gateway → Gateway routes to correct backend → result back to Agent → SmartGate `tool` message.

**Separation of concerns:**
- **Agent (Python):** LLM conversation, sessions, memory, commands, personas, extensions
- **MCP Gateway (Java):** Tool execution, backend connections, concurrency, scaling

---

## Project Structure

```
kukuvaia/
├── src/kukuvaia/
│   ├── __init__.py
│   ├── config.py                  # Pydantic Settings
│   ├── smartgate.py               # JWT auth (from smartgate_auth.py)
│   ├── output.py                  # OutputBlock dataclasses
│   ├── mcp/                       # MCP Gateway HTTP client
│   │   ├── __init__.py
│   │   └── client.py              # McpClient: list_tools, call_tool via HTTP
│   ├── agent/
│   │   ├── __init__.py
│   │   ├── loop.py                # LLM tool-use loop (SmartGate)
│   │   ├── router.py              # /command → dispatch | text → LLM
│   │   ├── session.py             # PG-backed sessions
│   │   ├── memory.py              # OutlineMemory
│   │   ├── compaction.py          # Context compaction
│   │   └── tasks.py               # Background task runner + registry
│   ├── commands/
│   │   ├── __init__.py
│   │   ├── registry.py            # Command registration + dispatch (built-in + user)
│   │   ├── validate.py            # /validate handler
│   │   ├── check.py               # /check handler
│   │   ├── search.py              # /search handler
│   │   ├── report.py              # /report handler
│   │   └── help.py                # /help handler
│   ├── extensions/                # User extensibility engine
│   │   ├── __init__.py
│   │   ├── loader.py              # Discover + load .kukuvaia/ directory
│   │   ├── rules.py               # Rule files → system prompt injection
│   │   ├── skills.py              # Skill loader + /skill command
│   │   └── user_commands.py       # User-defined command YAML → CommandRegistry
│   │                              # No mcp_servers/ — Java Gateway handles all backends
│   ├── personas/                  # Built-in personas (shipped with agent)
│   │   ├── editor.yaml
│   │   ├── content_admin.yaml
│   │   └── validator.yaml
│   ├── prompts/                   # Built-in system prompts
│   │   ├── editor.md
│   │   ├── content_admin.md
│   │   └── validator.md
│   ├── render/
│   │   ├── __init__.py
│   │   └── cli.py                 # rich renderer
│   ├── api/
│   │   ├── __init__.py
│   │   ├── app.py                 # FastAPI factory
│   │   ├── deps.py                # DI
│   │   └── routes/
│   │       ├── __init__.py
│   │       ├── chat.py            # SSE endpoint
│   │       └── sessions.py        # Session CRUD
│   └── cli/
│       ├── __init__.py
│       └── repl.py                # CLI entry point
├── migrations/
│   └── 0001.create-agent-tables.sql
├── tests/
│   ├── test_mcp_client.py          # HTTP client, tool discovery, tool calls
│   ├── test_router.py
│   ├── test_commands.py
│   ├── test_extensions.py         # Rules, skills, user commands loading
│   ├── test_session.py
│   └── test_output.py
├── pyproject.toml
└── CLAUDE.md
```

### User project directory: `.kukuvaia/`

Created per-project (like `.claude/`). Discovered at startup by walking up from CWD.

```
project-root/
└── .kukuvaia/
    ├── rules/                     # Always loaded → system prompt
    │   ├── no-delete.md          # "Never delete content from MongoDB"
    │   └── language.md           # "Respond in Polish for Dutch content"
    ├── skills/                    # On-demand via /skill <name>
    │   ├── validate-outline/
    │   │   └── SKILL.md          # Detailed validation procedure
    │   └── quality-report/
    │       └── SKILL.md          # Report generation procedure
    ├── commands/                  # User-defined slash commands
    │   ├── validate-all.yaml     # /validate-all → prompt template
    │   └── nightly-check.yaml    # /nightly-check → shell command
    ├── personas/                  # User personas (override/extend built-in)
    │   └── reviewer.yaml         # Custom persona with custom MCP servers
    └── settings.yaml              # Agent settings overrides
```

---

## User Extensibility Design

### Rules (`extensions/rules.py`)

Always-on constraints injected into system prompt. Loaded from `.kukuvaia/rules/*.md` at startup.

```python
def load_rules(project_dir: Path) -> str:
    """Load all rule files, concatenate into system prompt section."""
    rules_dir = project_dir / ".kukuvaia" / "rules"
    if not rules_dir.exists():
        return ""
    parts = []
    for path in sorted(rules_dir.glob("*.md")):
        parts.append(f"## Rule: {path.stem}\n{path.read_text().strip()}")
    if not parts:
        return ""
    return "\n\n---\nUSER RULES (always follow):\n" + "\n\n".join(parts)
```

Injected into system prompt after persona prompt, before memory:
```
[persona prompt] + [user rules] + [active skill] + [outline memory]
```

### Skills (`extensions/skills.py`)

Domain-specific procedures loaded on demand. User types `/skill validate-outline` → SKILL.md content injected into system prompt for current session.

```python
def load_skill(project_dir: Path, skill_name: str) -> str | None:
    """Load SKILL.md for named skill."""
    path = project_dir / ".kukuvaia" / "skills" / skill_name / "SKILL.md"
    if path.exists():
        return path.read_text()
    return None

def list_skills(project_dir: Path) -> list[dict]:
    """List available skills with descriptions (first line of SKILL.md)."""
    skills_dir = project_dir / ".kukuvaia" / "skills"
    if not skills_dir.exists():
        return []
    result = []
    for skill_dir in sorted(skills_dir.iterdir()):
        md = skill_dir / "SKILL.md"
        if md.exists():
            first_line = md.read_text().strip().split("\n")[0].lstrip("#").strip()
            result.append({"name": skill_dir.name, "description": first_line})
    return result
```

Built-in commands:
- `/skill <name>` — activate skill (inject into prompt)
- `/skill off` — deactivate current skill
- `/skills` — list available skills

### User Commands (`extensions/user_commands.py`)

Three types of user-defined commands:

```yaml
# .kukuvaia/commands/validate-all.yaml
name: /validate-all
description: "Full validation pipeline on current outline"
type: prompt
prompt: |
  Run all validation rules on outline ${outline_id}:
  1. Check metadata completeness via mcp__postgres__query
  2. Run authorsuite rules via mcp__authorsuite__run_validation
  3. Compare with previous validation from outline memory
  Report as table with pass/fail counts per category.
```

```yaml
# .kukuvaia/commands/deploy.yaml
name: /deploy
description: "Deploy outline to staging"
type: shell
command: "sl-pipeline deploy --outline ${outline_id} --env staging"
confirm: true          # Ask before executing
```

```yaml
# .kukuvaia/commands/quality-check.yaml
name: /quality-check
description: "Full quality check using skill"
type: skill
skill: quality-report
```

Loading:
```python
def load_user_commands(project_dir: Path, registry: CommandRegistry) -> int:
    """Load user commands from .kukuvaia/commands/*.yaml into registry."""
    cmds_dir = project_dir / ".kukuvaia" / "commands"
    if not cmds_dir.exists():
        return 0
    count = 0
    for path in sorted(cmds_dir.glob("*.yaml")):
        data = yaml.safe_load(path.read_text())
        cmd_type = data.get("type", "prompt")
        if cmd_type == "prompt":
            handler = _make_prompt_handler(data["prompt"])
        elif cmd_type == "shell":
            handler = _make_shell_handler(data["command"], data.get("confirm", False))
        elif cmd_type == "skill":
            handler = _make_skill_handler(data["skill"])
        else:
            continue
        registry.register(data["name"], data["description"])(handler)
        count += 1
    return count

def _make_prompt_handler(template: str):
    """Create handler that sends expanded template to LLM."""
    async def handler(ctx, args: str) -> list[OutputBlock]:
        expanded = Template(template).safe_substitute(
            outline_id=ctx.outline_id or "",
            session_id=ctx.session_id or "",
            args=args,
        )
        # Run through LLM agent loop with expanded prompt as user message
        blocks = []
        async for block in run_agent_loop(ctx.system_prompt, 
                [{"role": "user", "content": expanded}], ctx.mcp):
            blocks.append(block)
        return blocks
    return handler

def _make_shell_handler(command: str, confirm: bool):
    """Create handler that executes shell command."""
    async def handler(ctx, args: str) -> list[OutputBlock]:
        expanded = Template(command).safe_substitute(
            outline_id=ctx.outline_id or "", args=args,
        )
        if confirm:
            return [TextBlock(content=f"Execute: {expanded}", style="warning"),
                    TextBlock(content="Type 'yes' to confirm", style="muted")]
        result = await asyncio.create_subprocess_shell(
            expanded, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        stdout, stderr = await result.communicate()
        return [CodeBlock(content=stdout.decode(), language="")]
    return handler

def _make_skill_handler(skill_name: str):
    """Create handler that activates a skill."""
    async def handler(ctx, args: str) -> list[OutputBlock]:
        content = load_skill(ctx.project_dir, skill_name)
        if not content:
            return [TextBlock(content=f"Skill not found: {skill_name}", style="error")]
        ctx.active_skill = content
        return [TextBlock(content=f"Skill '{skill_name}' activated", style="success")]
    return handler
```

### User Personas

Users can define custom personas in `.kukuvaia/personas/`:

```yaml
# .kukuvaia/personas/reviewer.yaml
name: reviewer
description: "Content reviewer with read-only access"
system_prompt: .kukuvaia/prompts/reviewer.md   # relative to project root
mcp_servers:
  - name: postgres
    command: "npx"
    args: ["@modelcontextprotocol/server-postgres", "${PG_DSN}"]
commands:
  - /check
  - /report
  - /skill
  - /skills
  - /help
permissions:
  max_tool_rounds: 10
```

User personas override built-in ones with same name. Discovery: built-in first, then `.kukuvaia/personas/`.

### Settings Override

```yaml
# .kukuvaia/settings.yaml
chat_model: "eu.anthropic.claude-sonnet-4-5-20250929-v1:0"
max_tool_rounds: 20
temperature: 0.1
default_persona: editor
```

Merged on top of env vars / .env.

---

## MCP Gateway Client Design

### Overview

Agent connects to a **single external MCP Gateway** (Java/Spring Boot) via HTTP. The gateway aggregates all tool backends (postgres, mongodb, authorsuite, k8s, aws, ...) and handles concurrency, connection pooling, and scaling independently.

Agent's MCP layer is a thin HTTP client (~80 lines). No subprocess management, no stdio framing, no Lock.

### Protocol: MCP over HTTP (Streamable HTTP transport)

Agent sends JSON-RPC requests as HTTP POST. Gateway responds with JSON-RPC responses.

```
POST {gateway_url}/mcp
Content-Type: application/json

{"jsonrpc": "2.0", "method": "tools/list", "id": 1, "params": {}}
```

### Client: `mcp/client.py` (~80 lines)

```python
@dataclass(frozen=True)
class McpTool:
    name: str
    description: str
    input_schema: dict            # JSON Schema for tool arguments

class McpClient:
    """HTTP client for external MCP Gateway."""

    def __init__(self, gateway_url: str, api_key: str = ""):
        self.gateway_url = gateway_url.rstrip("/")
        self._client = httpx.AsyncClient(
            timeout=60.0,
            headers={"Authorization": f"Bearer {api_key}"} if api_key else {},
        )
        self._tools: list[McpTool] = []
        self._request_id = 0

    async def list_tools(self) -> list[McpTool]:
        """Discover all tools from gateway."""
        result = await self._rpc("tools/list", {})
        self._tools = [
            McpTool(
                name=t["name"],
                description=t.get("description", ""),
                input_schema=t.get("inputSchema", {}),
            )
            for t in result.get("tools", [])
        ]
        return self._tools

    async def call_tool(self, name: str, arguments: dict) -> str:
        """Execute tool via gateway, return text result."""
        result = await self._rpc("tools/call", {
            "name": name,
            "arguments": arguments,
        })
        contents = result.get("content", [])
        texts = [c.get("text", "") for c in contents if c.get("type") == "text"]
        return "\n".join(texts)

    def get_tool_definitions(self) -> list[dict]:
        """Build OpenAI-compatible tool definitions for SmartGate."""
        return [
            {
                "type": "function",
                "function": {
                    "name": tool.name,
                    "description": tool.description,
                    "parameters": tool.input_schema or {"type": "object", "properties": {}},
                },
            }
            for tool in self._tools
        ]

    async def close(self):
        await self._client.aclose()

    async def _rpc(self, method: str, params: dict) -> dict:
        """Send JSON-RPC request to gateway via HTTP POST."""
        self._request_id += 1
        resp = await self._client.post(
            f"{self.gateway_url}/mcp",
            json={"jsonrpc": "2.0", "method": method, "params": params, "id": self._request_id},
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get("error"):
            raise McpError(data["error"]["message"], data["error"].get("code"))
        return data.get("result", {})
```

**No subprocess management.** No stdio framing. No Lock. Gateway handles concurrency.

**Concurrency:** Fully concurrent — `httpx.AsyncClient` handles multiple simultaneous requests. Gateway (Spring Boot thread pool) processes them in parallel. No bottleneck on agent side.

---

## Persona YAML with MCP Gateway

```yaml
# personas/editor.yaml
name: editor
description: "Content editor — validate, fix, publish"
system_prompt: prompts/editor.md
mcp_gateway: "${MCP_GATEWAY_URL}"       # http://mcp-gateway:8080
mcp_gateway_key: "${MCP_GATEWAY_KEY}"   # optional auth
tool_filter:                             # optional — limit which tools this persona sees
  - query                                # postgres query
  - find                                 # mongodb find
  - run_validation                       # authorsuite
commands:
  - /validate
  - /check
  - /search
  - /help
permissions:
  max_tool_rounds: 15

# personas/devops.yaml (same gateway, different tool filter)
name: devops
description: "K8s and AWS operations"
system_prompt: prompts/devops.md
mcp_gateway: "${MCP_GATEWAY_URL}"
tool_filter:
  - get_pods
  - describe_pod
  - get_logs
  - list_instances
  - describe_instance
commands:
  - /status
  - /deploy
  - /help
permissions:
  max_tool_rounds: 20
```

**Adding k8s/AWS:** Register MCP client in Java Gateway (Spring Boot config). Agent sees new tools via `tools/list`. Zero Python code, zero agent restart — just `tool_filter` in persona if needed.

---

## Background Tasks Design

### Overview

Commands can run in background. User continues chatting while task executes. Results reported when done.

```
User: /validate-all --background
Agent: ⟳ Task #1 started: validate all outlines (100 outlines)
User: How many items does outline X have?     ← continues chatting
Agent: Outline X has 342 items...
Agent: ✓ Task #1 done: 97/100 passed, 3 failed. /task 1 for details.
```

### `agent/tasks.py` (~120 lines)

```python
from enum import Enum
from dataclasses import dataclass, field
from datetime import datetime

class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"

@dataclass
class BackgroundTask:
    task_id: int
    description: str
    status: TaskStatus = TaskStatus.PENDING
    result: list[OutputBlock] = field(default_factory=list)
    error: str = ""
    started_at: datetime | None = None
    completed_at: datetime | None = None
    _asyncio_task: asyncio.Task | None = field(default=None, repr=False)

class TaskRunner:
    """In-memory background task registry."""

    def __init__(self):
        self._tasks: dict[int, BackgroundTask] = {}
        self._next_id = 1
        self._on_complete: Callable | None = None  # callback for CLI/SSE notification

    def submit(
        self,
        description: str,
        coro: Coroutine,
    ) -> BackgroundTask:
        """Submit coroutine as background task. Returns immediately."""
        task = BackgroundTask(task_id=self._next_id, description=description)
        self._next_id += 1
        self._tasks[task.task_id] = task

        async def _run():
            task.status = TaskStatus.RUNNING
            task.started_at = datetime.utcnow()
            try:
                task.result = await coro
                task.status = TaskStatus.COMPLETED
            except Exception as e:
                task.error = str(e)
                task.status = TaskStatus.FAILED
            task.completed_at = datetime.utcnow()
            if self._on_complete:
                await self._on_complete(task)

        task._asyncio_task = asyncio.create_task(_run())
        return task

    def get(self, task_id: int) -> BackgroundTask | None:
        return self._tasks.get(task_id)

    def list_active(self) -> list[BackgroundTask]:
        return [t for t in self._tasks.values() if t.status == TaskStatus.RUNNING]

    def list_all(self) -> list[BackgroundTask]:
        return list(self._tasks.values())
```

### Built-in commands

```python
# commands/task_cmd.py

@registry.register("/task", description="Show background task details")
async def task_cmd(ctx, args: str) -> list[OutputBlock]:
    if not args:
        # List all tasks
        tasks = ctx.task_runner.list_all()
        if not tasks:
            return [TextBlock(content="No background tasks", style="muted")]
        return [TableBlock(
            title="Background Tasks",
            headers=["ID", "Status", "Description", "Duration"],
            rows=[(str(t.task_id), t.status.value, t.description,
                   _format_duration(t)) for t in tasks],
        )]
    # Show specific task
    task = ctx.task_runner.get(int(args))
    if not task:
        return [TextBlock(content=f"Task {args} not found", style="error")]
    blocks = [TextBlock(content=f"Task #{task.task_id}: {task.description} [{task.status.value}]")]
    if task.result:
        blocks.extend(task.result)
    if task.error:
        blocks.append(TextBlock(content=task.error, style="error"))
    return blocks
```

### `--background` flag on commands

Commands opt-in to background execution:

```python
# commands/registry.py — Command gains background_capable flag
@dataclass(frozen=True)
class Command:
    name: str
    description: str
    handler: Callable
    summarize: bool = True
    background_capable: bool = False   # can run with --background

# agent/router.py — parse --background flag
async def handle_input(session, user_input: str):
    stripped = user_input.strip()
    if stripped.startswith("/"):
        background = "--background" in stripped or "-bg" in stripped
        stripped = stripped.replace("--background", "").replace("-bg", "").strip()
        # ... parse command ...
        if background and cmd.background_capable:
            task = ctx.task_runner.submit(
                description=f"{cmd.name} {cmd_args}",
                coro=cmd.handler(ctx, cmd_args),
            )
            yield TextBlock(content=f"Task #{task.task_id} started: {cmd.description}", style="success")
            return
        # ... normal synchronous execution ...
```

### Completion notification

**CLI:** TaskRunner callback prints to console:
```python
async def _on_task_complete(task: BackgroundTask):
    status = "✓" if task.status == TaskStatus.COMPLETED else "✗"
    console.print(f"\n{status} Task #{task.task_id} done: {task.description}", style="bold")
    console.print("[bold]> [/]", end="")  # re-show prompt
```

**Web API (SSE):** TaskRunner callback pushes SSE event to connected client:
```python
yield f"data: {json.dumps({'type': 'task_complete', 'task_id': task.task_id, ...})}\n\n"
```

### Example: `/validate-all --background`

```python
@registry.register("/validate-all", description="Validate all outlines", background_capable=True)
async def validate_all(ctx, args: str) -> list[OutputBlock]:
    outlines = await _get_all_outlines(ctx.mcp)
    results = []
    for i, outline in enumerate(outlines):
        result = await ctx.mcp.call_tool("run_validation", {"outline_id": outline["id"]})
        results.append(_parse_validation(result))
        # Progress (visible in /task output)
    return [
        TableBlock(title="Validation Summary",
                   headers=["Outline", "Status", "Passed", "Failed"],
                   rows=[(r["id"], r["status"], str(r["pass"]), str(r["fail"])) for r in results]),
        TextBlock(content=f"{sum(1 for r in results if r['status']=='PASS')}/{len(results)} passed"),
    ]
```

### Sub-Agent Orchestration (Phase 1)

Parent agent delegates complex sub-tasks to specialist sub-agents. Each sub-agent is a separate LLM conversation with full autonomy — own system prompt, own context window, own tool set. Parent agent triggers delegation via a `@Tool`-annotated method that Spring AI treats as any other tool call.

#### Architecture

```
User → Parent Agent (ChatClient + ToolCallAdvisor)
         │
         ├── LLM decides to call delegate_to_specialist("analyze content quality", "analyst")
         │     │
         │     └── SubAgentFactory.create("analyst")
         │           → new ChatClient (own system prompt, own tools, own ChatOptions)
         │           → runs full agent loop (multi-round tool calling)
         │           → returns synthesized result string
         │     │
         │     └── result goes back to parent LLM as tool result
         │
         └── Parent LLM continues with sub-agent's result in context
```

#### Implementation

**`agent/subagent/SubAgentFactory.java`**

Creates isolated ChatClient instances per specialist type. Each sub-agent gets:
- **Prompt isolation** — own system prompt from persona/specialist definition
- **Tool filtering** — subset of tools relevant to the specialist role
- **Cost control** — `maxTokens` budget per sub-agent invocation
- **Round limit** — independent `max_tool_rounds` (default: 10, lower than parent's 15)

```java
@Component
public class SubAgentFactory {

    private final ChatModel chatModel;
    private final Map<String, SubAgentSpec> specs;  // loaded from YAML

    public String execute(String task, String specialistType) {
        SubAgentSpec spec = specs.get(specialistType);
        ChatClient subAgent = ChatClient.builder(chatModel)
            .defaultSystem(spec.systemPrompt())
            .defaultTools(spec.tools())        // filtered tool set
            .defaultAdvisors(new ToolCallAdvisor())
            .build();

        return subAgent.prompt()
            .options(OpenAiChatOptions.builder()
                .model(spec.model())           // can use cheaper model
                .maxTokens(spec.maxTokens())   // cost control
                .temperature(spec.temperature())
                .build())
            .user(task)
            .call().content();
    }
}
```

**`agent/subagent/SubAgentTool.java`** — exposed as @Tool to parent agent

```java
@Component
public class SubAgentTool {

    private final SubAgentFactory factory;
    private final Semaphore parallelLimit = new Semaphore(3);  // max 3 concurrent sub-agents

    @Tool(description = "Delegate a task to a specialist sub-agent. " +
          "Use when the task requires focused analysis, multi-step tool usage, " +
          "or a different expertise than the current conversation.")
    public String delegateToSpecialist(
            @ToolParam(description = "Clear description of the task to delegate") String task,
            @ToolParam(description = "Specialist type: analyst, validator, researcher, devops") String specialistType) {

        if (!parallelLimit.tryAcquire(30, TimeUnit.SECONDS)) {
            return "Sub-agent limit reached (max 3 concurrent). Try again later.";
        }
        try {
            return factory.execute(task, specialistType);
        } finally {
            parallelLimit.release();
        }
    }
}
```

#### Sub-Agent Specialist Definitions

Loaded from `specialists/*.yaml` (built-in) and `.kukuvaia/specialists/*.yaml` (user-defined):

```yaml
# specialists/analyst.yaml
name: analyst
description: "Content quality analyst — deep analysis with multi-tool investigation"
provider: ~                       # null — inherits from execution context (interactive → copilot, daemon → smartgate)
system_prompt: |
  You are a content quality analyst. Investigate thoroughly using available tools.
  Return structured findings: summary, details, recommendations.
tools:
  - get_classifications
  - get_groups
  - search_items
  - get_outline_stats
model: claude-sonnet              # can use cheaper model than parent
max_tokens: 4096                  # cost cap per invocation
max_tool_rounds: 10               # independent round limit
temperature: 0.1
```

```yaml
# specialists/validator.yaml
name: validator
description: "Validation specialist — runs and interprets validation rules"
provider: ~                       # null — inherits from execution context
system_prompt: |
  You are a validation specialist. Run validation rules, analyze results,
  identify patterns in failures, and suggest fixes.
tools:
  - run_validation
  - get_validation_report
  - get_classifications
model: claude-sonnet
max_tokens: 2048
max_tool_rounds: 8
temperature: 0.0
```

#### Parallel Sub-Agents

For tasks requiring multiple specialists in parallel (e.g., "validate and analyze this outline"), parent agent can call `delegate_to_specialist` multiple times. Spring AI's `ToolCallAdvisor` handles parallel tool calls natively — if LLM returns multiple `tool_calls` in one response, they execute concurrently. `Semaphore(3)` limits concurrency.

For explicit parallel orchestration beyond what the LLM decides:

```java
@Tool(description = "Run multiple specialist sub-agents in parallel and aggregate results")
public String delegateParallel(
        @ToolParam(description = "JSON array of {task, specialistType} objects") String delegations) {

    List<Delegation> tasks = objectMapper.readValue(delegations, new TypeReference<>() {});
    List<CompletableFuture<String>> futures = tasks.stream()
        .map(d -> CompletableFuture.supplyAsync(
            () -> factory.execute(d.task(), d.specialistType())))
        .toList();

    return futures.stream()
        .map(CompletableFuture::join)
        .collect(Collectors.joining("\n\n---\n\n"));
}
```

#### Cost Control & Observability

| Control | Mechanism |
|---------|-----------|
| Token budget per sub-agent | `maxTokens` in ChatOptions |
| Round limit per sub-agent | `max_tool_rounds` in specialist spec |
| Concurrency limit | `Semaphore(3)` in SubAgentTool |
| Model selection | Specialists can use cheaper models |
| Logging | Each sub-agent execution logged with: specialist type, task, token usage, duration, tool calls made |
| Timeout | `CompletableFuture.get(timeout)` prevents runaway sub-agents |

#### Integration with Personas

Personas define which specialists are available via `specialists` field:

```yaml
# personas/editor.yaml
name: editor
specialists:
  - analyst
  - validator
# Only analyst and validator sub-agents available to this persona
```

If no `specialists` field — all built-in + user-defined specialists are available.

#### What Spring AI Provides vs Custom

| Capability | Spring AI | Custom |
|---|---|---|
| Isolated ChatClient per sub-agent | `ChatClient.builder()` | — |
| Automatic tool calling loop | `ToolCallAdvisor` | — |
| Per-request model/token override | `ChatOptions` | — |
| Sub-agent as tool for parent | `@Tool` annotation | — |
| Parallel tool execution | `ToolCallAdvisor` native | — |
| Concurrency limit | — | `Semaphore` |
| Specialist definitions (YAML) | — | `SubAgentFactory` + loader |
| Cost logging / observability | — | Custom interceptor |
| Timeout control | — | `CompletableFuture.get(timeout)` |

#### Files to Create (Phase 1)

1. `src/main/java/.../agent/subagent/SubAgentSpec.java` — specialist definition record
2. `src/main/java/.../agent/subagent/SubAgentFactory.java` — creates isolated ChatClient per specialist
3. `src/main/java/.../agent/subagent/SubAgentTool.java` — @Tool for parent agent delegation
4. `src/main/resources/specialists/analyst.yaml` — built-in analyst specialist
5. `src/main/resources/specialists/validator.yaml` — built-in validator specialist
6. `src/test/java/.../agent/subagent/SubAgentFactoryTest.java` — unit tests
7. `src/test/java/.../agent/subagent/SubAgentToolTest.java` — integration tests

#### Test

- Parent agent asked "analyze content quality for outline X" → LLM calls `delegate_to_specialist` → sub-agent runs with analyst persona → multi-round tool calls → synthesized result returned to parent
- Parallel: "validate and analyze outline X" → LLM calls both specialists → concurrent execution → combined results
- Cost control: sub-agent respects `maxTokens` and `max_tool_rounds` — doesn't run away
- Concurrency: 4th concurrent sub-agent blocks until one completes (Semaphore)
- User-defined specialist in `.kukuvaia/specialists/custom.yaml` → available to parent agent

---

## Daemon Agent Design (Phase 1)

### Overview

kukuvaia-server runs as a persistent JVM process (Spring Boot). Adding daemon-like autonomous agent behavior is a natural extension — same ChatClient, same tools, same memory infrastructure. No separate process needed.

Three execution modes in one server:

```
kukuvaia-server (Spring Boot — single persistent process)
  │
  ├── Interactive mode     — /api/chat (SSE) — user-driven conversation
  │     User sends message → ChatClient + ToolCallAdvisor → streaming response
  │
  ├── Scheduled mode       — @Scheduled (cron) — time-driven autonomous tasks
  │     Cron fires → DaemonAgentService → SubAgentFactory → specialist runs autonomously
  │     Results stored in PG + optional notification (webhook, SSE push, Slack)
  │
  └── Event-driven mode    — /api/webhooks + PG LISTEN/NOTIFY — trigger-driven
        External event → DaemonAgentService → SubAgentFactory → specialist handles event
        Sources: CI/CD webhooks, monitoring alerts, PG notifications, queue messages
```

### Architecture: Daemon Uses Sub-Agents as Workers

The daemon doesn't run its own ChatClient loop. Instead, it delegates to the **sub-agent @Tool infrastructure** (Phase 1) — same `SubAgentFactory`, same specialist definitions, same cost controls. This means:

- One way to define specialists (YAML) — used by both interactive delegation and daemon tasks
- One set of cost controls (maxTokens, round limits, Semaphore) — shared
- One observability layer — all agent executions logged identically

```
@Scheduled / Webhook / Queue
       │
       ▼
DaemonAgentService
       │
       ├── SubAgentFactory.execute(task, "analyst")      ← same factory as interactive
       ├── SubAgentFactory.execute(task, "validator")     ← same specialists
       └── Results → DaemonTaskRepository (PG) + NotificationService
```

### Implementation

**`agent/daemon/DaemonTask.java`** — persistent task record

```java
@Entity
@Table(name = "daemon_tasks", schema = "kukuvaia_agent")
public class DaemonTask {
    @Id @GeneratedValue
    private Long id;
    private String name;                    // "nightly-validation"
    private String specialistType;          // "validator"
    private String prompt;                  // task description for sub-agent
    private String cronExpression;          // "0 0 2 * * *" (nullable for event-driven)
    private DaemonTaskStatus status;        // PENDING, RUNNING, COMPLETED, FAILED
    private String result;                  // sub-agent response
    private Integer tokenUsage;             // cost tracking
    private Duration duration;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private String triggerSource;           // "cron", "webhook:ci", "pg-notify", "manual"
}
```

**`agent/daemon/DaemonAgentService.java`** — orchestrates daemon tasks via sub-agents

```java
@Service
public class DaemonAgentService {

    private final SubAgentFactory subAgentFactory;
    private final DaemonTaskRepository taskRepository;
    private final NotificationService notifications;

    public DaemonTaskResult execute(String taskName, String specialistType, String prompt, String triggerSource) {
        DaemonTask task = DaemonTask.builder()
            .name(taskName).specialistType(specialistType)
            .prompt(prompt).triggerSource(triggerSource)
            .status(RUNNING).startedAt(Instant.now())
            .build();
        taskRepository.save(task);

        try {
            String result = subAgentFactory.execute(prompt, specialistType);
            task.complete(result);
            notifications.onTaskComplete(task);
        } catch (Exception e) {
            task.fail(e.getMessage());
            notifications.onTaskFailed(task);
        }
        taskRepository.save(task);
        return task.toResult();
    }
}
```

**`agent/daemon/ScheduledTasks.java`** — cron-driven daemon tasks

```java
@Component
public class ScheduledTasks {

    private final DaemonAgentService daemonAgent;
    private final DaemonTaskConfigRepository configRepo;

    // Fixed schedule example — runs every night at 2 AM
    @Scheduled(cron = "0 0 2 * * *")
    public void nightlyValidation() {
        daemonAgent.execute(
            "nightly-validation",
            "validator",
            "Run validation on all outlines updated in the last 24 hours. " +
            "Report: outline_id, pass/fail, critical issues.",
            "cron"
        );
    }

    // Dynamic schedules — loaded from DB/YAML at startup
    @PostConstruct
    public void registerDynamicTasks() {
        configRepo.findAllEnabled().forEach(config ->
            taskScheduler.schedule(
                () -> daemonAgent.execute(config.name(), config.specialistType(),
                                          config.prompt(), "cron"),
                new CronTrigger(config.cronExpression())
            ));
    }
}
```

**`api/routes/WebhookController.java`** — event-driven daemon tasks

```java
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final DaemonAgentService daemonAgent;

    @PostMapping("/ci")
    public ResponseEntity<DaemonTaskResult> onCiEvent(@RequestBody CiEvent event) {
        if (event.status() == FAILED) {
            return ResponseEntity.ok(daemonAgent.execute(
                "ci-failure-analysis",
                "analyst",
                "CI build failed for " + event.repo() + " branch " + event.branch() +
                ". Error: " + event.errorLog() + ". Analyze root cause and suggest fix.",
                "webhook:ci"
            ));
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/alert")
    public ResponseEntity<DaemonTaskResult> onMonitoringAlert(@RequestBody AlertEvent alert) {
        return ResponseEntity.ok(daemonAgent.execute(
            "alert-investigation",
            "devops",
            "Monitoring alert: " + alert.description() + ". Investigate using available tools.",
            "webhook:alert"
        ));
    }
}
```

### User-Defined Daemon Tasks

Users define daemon tasks in `.kukuvaia/daemon/*.yaml`:

```yaml
# .kukuvaia/daemon/nightly-review.yaml
name: nightly-review
description: "Review content quality for all active outlines"
specialist: analyst
provider: smartgate                     # daemon default — safe for automated use
prompt: |
  Review all active outlines. For each, check:
  1. Metadata completeness
  2. Classification coverage
  3. Known issues from outline memory
  Report as structured summary with action items.
schedule: "0 0 2 * * *"               # 2 AM daily
enabled: true
notification:
  on_complete: webhook                  # webhook | slack | email | none
  webhook_url: "${NOTIFICATION_WEBHOOK}"
```

```yaml
# .kukuvaia/daemon/content-watch.yaml
name: content-watch
description: "Analyze new content items as they arrive"
specialist: analyst
provider: smartgate                     # explicit — no Copilot quota burn
prompt: |
  New content items detected: ${event.payload}.
  Classify them and check for quality issues.
trigger: pg-notify                      # listens to PG NOTIFY channel
channel: new_content_items              # PG channel name
enabled: true
```

Provider resolution chain: task YAML `provider:` → specialist YAML `provider:` → `kukuvaia.daemon.default-provider` → `kukuvaia.default-provider`. See `docs/architecture/auth-and-providers.md` for full architecture decision.

Loaded at startup by `DaemonTaskLoader`, registered with Spring's `TaskScheduler` (cron) or PG LISTEN/NOTIFY listener (event-driven).

### PG LISTEN/NOTIFY Integration

For event-driven daemon tasks without external webhooks — database changes trigger agent work:

```java
@Component
public class PgNotifyListener {

    private final DaemonAgentService daemonAgent;
    private final DaemonTaskConfigRepository configRepo;

    @PostConstruct
    public void startListening() {
        configRepo.findByTrigger("pg-notify").forEach(config -> {
            pgConnection.addNotificationListener(config.channel(), notification -> {
                daemonAgent.execute(
                    config.name(), config.specialistType(),
                    config.prompt().replace("${event.payload}", notification.getPayload()),
                    "pg-notify:" + config.channel()
                );
            });
        });
    }
}
```

### Notification Service

Daemon task results need to reach humans. Pluggable notification backends:

```java
public interface NotificationSink {
    void send(DaemonTask task);
}

// Implementations:
// - WebhookNotificationSink   → POST result to configured URL
// - SlackNotificationSink     → post to Slack channel via webhook
// - SseNotificationSink       → push to connected SSE clients (kukuvaia-cli/web)
// - LogNotificationSink       → structured log only (default)
```

### Migration

```sql
CREATE TABLE IF NOT EXISTS daemon_tasks (
    id              BIGSERIAL PRIMARY KEY,
    name            TEXT NOT NULL,
    specialist_type TEXT NOT NULL,
    prompt          TEXT NOT NULL,
    cron_expression TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    result          TEXT,
    token_usage     INT,
    duration_ms     BIGINT,
    trigger_source  TEXT NOT NULL,
    scheduled_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_daemon_tasks_status ON daemon_tasks (status);
CREATE INDEX idx_daemon_tasks_name ON daemon_tasks (name, created_at DESC);
```

### Cost Control & Safety

| Control | Mechanism |
|---------|-----------|
| Per-task token budget | Inherited from specialist's `maxTokens` |
| Per-task round limit | Inherited from specialist's `max_tool_rounds` |
| Global concurrency | Shared `Semaphore(3)` from SubAgentTool — daemon + interactive share the limit |
| Daily token budget | `DaemonBudgetGuard` — tracks daily usage, pauses daemon tasks when exceeded |
| Kill switch | `kukuvaia.daemon.enabled=false` in application.yaml or env var — disables all daemon tasks |
| Task timeout | `CompletableFuture.get(timeout)` per task — prevents runaway agents |
| Audit trail | All daemon tasks persisted in `daemon_tasks` table with full metadata |

```java
@Component
public class DaemonBudgetGuard {
    private final int dailyTokenLimit;  // from config
    
    public boolean canExecute() {
        int todayUsage = taskRepository.sumTokenUsageToday();
        return todayUsage < dailyTokenLimit;
    }
}
```

### Observability

```
/api/daemon/tasks              — list recent daemon task executions
/api/daemon/tasks/{id}         — task detail with result, tokens, duration
/api/daemon/tasks/stats        — daily/weekly aggregates (runs, tokens, success rate)
/api/daemon/schedules          — list active cron schedules
/api/daemon/schedules/{name}/run  — manually trigger a scheduled task
```

Built-in CLI commands:
```
/daemon status                 — show active schedules + recent executions
/daemon run <task-name>        — manually trigger daemon task
/daemon pause <task-name>      — temporarily disable
/daemon resume <task-name>     — re-enable
/daemon log <task-name>        — show execution history
```

### What Spring AI Provides vs Custom

| Capability | Spring AI / Spring Boot | Custom |
|---|---|---|
| Persistent JVM process | Spring Boot inherent | — |
| Cron scheduling | `@Scheduled` + `TaskScheduler` | — |
| Sub-agent execution | ChatClient + ToolCallAdvisor (via SubAgentFactory) | — |
| Tool calling loop | ToolCallAdvisor automatic | — |
| Cost control (maxTokens) | ChatOptions | — |
| PG persistence | JPA / JdbcTemplate | DaemonTask entity |
| PG LISTEN/NOTIFY | — | PgNotifyListener |
| Dynamic schedule registration | `TaskScheduler.schedule()` | Config loader |
| Budget guard | — | DaemonBudgetGuard |
| Notification sinks | — | NotificationService + sinks |
| User-defined daemon YAML | — | DaemonTaskLoader |
| REST API for daemon management | Spring MVC | Controller |
| CLI commands for daemon | — | Commands |

### Files to Create (Phase 1)

1. `src/main/java/.../agent/daemon/DaemonTask.java` — entity
2. `src/main/java/.../agent/daemon/DaemonTaskRepository.java` — JPA repository
3. `src/main/java/.../agent/daemon/DaemonAgentService.java` — orchestrator (uses SubAgentFactory)
4. `src/main/java/.../agent/daemon/ScheduledTasks.java` — cron tasks
5. `src/main/java/.../agent/daemon/DaemonTaskLoader.java` — loads `.kukuvaia/daemon/*.yaml`
6. `src/main/java/.../agent/daemon/DaemonBudgetGuard.java` — daily token budget
7. `src/main/java/.../agent/daemon/PgNotifyListener.java` — event-driven triggers
8. `src/main/java/.../agent/daemon/NotificationService.java` — pluggable notifications
9. `src/main/java/.../api/routes/WebhookController.java` — webhook triggers
10. `src/main/java/.../api/routes/DaemonController.java` — daemon management REST API
11. `src/main/resources/db/migration/V2__daemon_tasks.sql` — migration
12. `src/test/java/.../agent/daemon/DaemonAgentServiceTest.java` — unit tests
13. `src/test/java/.../agent/daemon/ScheduledTasksTest.java` — scheduling tests

### Test

- Cron task fires at configured time → SubAgentFactory.execute() called → specialist runs full tool loop → result stored in PG
- Webhook POST to `/api/webhooks/ci` with failure payload → analyst sub-agent investigates → result returned
- PG NOTIFY on `new_content_items` channel → daemon task triggered → analyst classifies new items
- Budget guard: after daily limit exceeded → daemon tasks skipped with logged warning
- `/daemon status` shows active schedules and recent executions
- `/daemon run nightly-review` manually triggers → executes immediately
- User-defined `.kukuvaia/daemon/custom.yaml` loaded at startup → registered with TaskScheduler
- Kill switch: `kukuvaia.daemon.enabled=false` → no daemon tasks execute

---

## Code to Extract from sl-content-engine

Source: `/home/bartek/Projects/sl-content/sl-content-engine/src/sl_content_agent/`

| Source file | Target | Changes |
|---|---|---|
| `smartgate_auth.py` (136 lines) | `smartgate.py` | Update imports |
| `api/chat.py:_get_client()` | `agent/loop.py` | Identical singleton pattern |
| `api/chat.py:run_agent_loop()` | `agent/loop.py` | `execute_tool` → `mcp.call_tool`, yield OutputBlocks |
| `api/app.py:lifespan()` | `api/app.py` | Add MCP manager start/shutdown |
| `api/deps.py` | `api/deps.py` | Add `get_mcp_client()` |
| `api/routes/chat.py` | `api/routes/chat.py` | session_id instead of outline_id |
| `config.py` | `config.py` | Strip to DB + SmartGate + agent settings |

**No longer extracted:** `api/tools.py` — replaced by MCP servers.

---

## Phase 1: Repo Setup + MCP Gateway Client

**Goal:** CLI connects to MCP Gateway, discovers tools, talks to SmartGate with those tools. Sub-agent orchestration enabled — parent agent can delegate tasks to specialist sub-agents with prompt isolation, cost control, and concurrency limits. Daemon agent infrastructure — Spring Boot scheduled/event-driven tasks that use sub-agents as autonomous workers.

**Prerequisite:** MCP Gateway (Java/Spring Boot) running with at least one backend (e.g. postgres).

### Files to create

**`pyproject.toml`**
```toml
[project]
name = "kukuvaia"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
    "openai>=1.0",
    "asyncpg>=0.30",
    "httpx>=0.27",
    "pydantic-settings>=2.0",
    "pyyaml>=6.0",
    "rich>=13.0",
    "click>=8.0",
]

[project.optional-dependencies]
api = ["fastapi>=0.115", "uvicorn[standard]>=0.34"]
dev = ["pytest>=8.0", "pytest-asyncio>=0.24", "ruff>=0.4"]

[project.scripts]
kukuvaia = "kukuvaia.cli.repl:main"
kukuvaia-api = "kukuvaia.api.app:main"
```

Note: no `motor` — MongoDB access through MCP Gateway, not direct Python driver.

### Create
1. `src/kukuvaia/config.py` — Pydantic Settings (PG DSN, SmartGate, MCP Gateway URL, agent settings)
2. `src/kukuvaia/smartgate.py` — JWT auth (copy from smartgate_auth.py)
3. `src/kukuvaia/output.py` — OutputBlock dataclasses
4. `src/kukuvaia/mcp/client.py` — McpClient (HTTP: list_tools, call_tool, get_tool_definitions)
5. `src/kukuvaia/agent/loop.py` — LLM tool-use loop (SmartGate + MCP)
6. `src/kukuvaia/render/cli.py` — rich renderer
7. `src/kukuvaia/cli/repl.py` — Minimal REPL

### Agent loop integration with MCP Gateway

```python
# agent/loop.py
async def run_agent_loop(
    system_prompt: str,
    messages: list[dict],
    mcp: McpClient,
) -> AsyncGenerator[OutputBlock, None]:
    tool_definitions = mcp.get_tool_definitions()
    client = await _get_client()
    full_messages = [{"role": "system", "content": system_prompt}] + messages

    for _round in range(MAX_TOOL_ROUNDS):
        response = await client.chat.completions.create(
            model=CHAT_MODEL, messages=full_messages,
            tools=tool_definitions, max_tokens=4096, temperature=0.2,
        )
        msg = response.choices[0].message
        if msg.content:
            yield TextBlock(content=msg.content)
        if not msg.tool_calls:
            break
        full_messages.append(msg.model_dump())
        for tc in msg.tool_calls:
            args = json.loads(tc.function.arguments)
            result = await mcp.call_tool(tc.function.name, args)
            yield TextBlock(content=f"Tool: {tc.function.name}", style="muted")
            full_messages.append({"role": "tool", "tool_call_id": tc.id, "content": result})
```

### CLI startup

```python
# cli/repl.py
async def _repl(persona_name: str):
    persona = load_persona(persona_name)
    mcp = McpClient(persona.mcp_gateway, persona.mcp_gateway_key)
    await mcp.list_tools()  # discover available tools
    # Filter tools by persona's tool_filter if set
    try:
        messages = []
        while True:
            user_input = console.input("[bold]> [/]")
            if user_input in ("exit", "quit"):
                break
            async for block in run_agent_loop(persona.system_prompt, messages, mcp):
                render_blocks([block])
    finally:
        await mcp.close()
```

### Test
- `test_mcp_client.py` — mock httpx responses for tools/list and tools/call
- Manual: `kukuvaia --persona editor` → SmartGate calls tools discovered from MCP Gateway
- Sub-agent: parent agent asked complex analysis → calls `delegate_to_specialist` → sub-agent runs isolated loop → result returned to parent
- Sub-agent concurrency: 3 concurrent sub-agents OK, 4th waits
- Daemon: cron task fires → DaemonAgentService → SubAgentFactory → specialist runs autonomously → result in PG
- Daemon: webhook POST → event-driven task → specialist analyzes → result returned
- Daemon: `/daemon status` shows schedules, `/daemon run <name>` triggers manually

### Prerequisite
MCP Gateway (Java/Spring Boot) running at `MCP_GATEWAY_URL` with backends registered.

---

## Phase 2: Command Dispatch + Personas + Extensions

**Goal:** `/help` works, personas loaded, `/command` routes deterministically, user rules/skills/commands loaded from `.kukuvaia/`.

### Create
1. `src/kukuvaia/commands/registry.py` — CommandRegistry with decorator
2. `src/kukuvaia/commands/help.py` — `/help` (commands + MCP tools + user commands)
3. `src/kukuvaia/commands/skill_cmd.py` — `/skill <name>`, `/skill off`, `/skills`
4. `src/kukuvaia/agent/router.py` — `/command` → dispatch, free text → LLM
5. `src/kukuvaia/extensions/loader.py` — Discover `.kukuvaia/` directory
6. `src/kukuvaia/extensions/rules.py` — Load rules → system prompt
7. `src/kukuvaia/extensions/skills.py` — Load/list/activate skills
8. `src/kukuvaia/extensions/user_commands.py` — Load user command YAMLs → registry
9. `src/kukuvaia/personas/*.yaml` — 3 built-in persona configs
10. `src/kukuvaia/prompts/*.md` — built-in system prompts

### Persona loading

```python
@dataclass(frozen=True)
class Persona:
    name: str
    system_prompt: str
    mcp_gateway: str               # "http://mcp-gateway:8080"
    mcp_gateway_key: str = ""      # optional auth
    tool_filter: list[str] | None = None  # limit visible tools (None = all)
    commands: list[str]            # ["/validate", "/help"]
    permissions: dict

def load_persona(name: str) -> Persona:
    yaml_path = Path(__file__).parent / "personas" / f"{name}.yaml"
    data = yaml.safe_load(yaml_path.read_text())
    prompt = (Path(__file__).parent / data["system_prompt"]).read_text()
    # Expand env vars in MCP server configs
    servers = _expand_env(data.get("mcp_servers", []))
    return Persona(name=data["name"], system_prompt=prompt,
                   mcp_servers=servers, commands=data["commands"],
                   permissions=data.get("permissions", {}))
```

### `/help` shows MCP tools

```python
@registry.register("/help", description="List commands and tools")
async def help_cmd(ctx) -> list[OutputBlock]:
    blocks = [
        TableBlock(title="Commands", headers=["Command", "Description"],
                   rows=[(c.name, c.description) for c in ctx.available_commands.values()]),
    ]
    if ctx.mcp._tools:
        blocks.append(TableBlock(
            title="MCP Tools (available to LLM)",
            headers=["Tool", "Description"],
            rows=[(t.name, t.description) for t in ctx.mcp._tools],
        ))
    return blocks
```

### Commands can call MCP tools directly

```python
@registry.register("/validate", description="Run validation rule")
async def validate(ctx, args: str) -> list[OutputBlock]:
    result = await ctx.mcp.call_tool(
        "run_validation",
        {"outline_id": ctx.outline_id, "rule": args},
    )
    # Parse result, return OutputBlocks...
```

### Prompt assembly order

```
1. Persona system prompt (prompts/editor.md)
2. User rules (.kukuvaia/rules/*.md)  — always on
3. Active skill (if /skill activated) — session-scoped
4. Outline memory (if outline selected) — from PG
```

### Startup sequence

```python
# cli/repl.py or api/app.py
project_dir = discover_project_dir()          # walk up from CWD looking for .kukuvaia/
persona = load_persona(persona_name, project_dir)  # built-in, then .kukuvaia/personas/
rules_text = load_rules(project_dir)          # .kukuvaia/rules/*.md → string
user_cmd_count = load_user_commands(project_dir, registry)  # .kukuvaia/commands/*.yaml
```

### Test
- `--persona editor` → `/help` shows editor commands + MCP tools + user commands
- `--persona content_admin` → different set
- `/validate all` → MCP call to authorsuite server
- `/skill validate-outline` → skill activated → system prompt extended
- `/skills` → lists available skills from .kukuvaia/skills/
- User command `/validate-all` (from .kukuvaia/commands/) → prompt template executed
- Rule in `.kukuvaia/rules/no-delete.md` → visible in system prompt, LLM respects it

---

## Phase 3: Session Persistence + Memory + Background Tasks

**Goal:** Sessions survive restarts. Outline memory persists. Commands can run in background.

### Migration: `migrations/0001.create-agent-tables.sql`

```sql
-- depends: 0018.add-learning-goal

CREATE TABLE IF NOT EXISTS agent_sessions (
    session_id    TEXT PRIMARY KEY,
    persona       TEXT NOT NULL,
    messages      JSONB NOT NULL DEFAULT '[]',
    context       JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_updated ON agent_sessions (updated_at DESC);

CREATE TABLE IF NOT EXISTS outline_memory (
    outline_id          TEXT PRIMARY KEY,
    publisher           TEXT NOT NULL,
    language            TEXT NOT NULL,
    last_validated_at   TIMESTAMPTZ,
    validation_summary  JSONB NOT NULL DEFAULT '{}',
    known_issues        JSONB NOT NULL DEFAULT '[]',
    user_preferences    JSONB NOT NULL DEFAULT '{}',
    last_session_id     TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Create
1. `src/kukuvaia/agent/session.py` — PG-backed session CRUD
2. `src/kukuvaia/agent/memory.py` — OutlineMemory load/save
3. `src/kukuvaia/agent/tasks.py` — TaskRunner + BackgroundTask (in-memory)
4. `src/kukuvaia/commands/task_cmd.py` — `/task` command (list/show tasks)
5. Update `agent/router.py` — `--background` / `-bg` flag parsing

### Test
- Session survives restart
- Memory injected into prompt on resume
- `/validate-all --background` → task starts → user chats → task completes with notification
- `/task` → lists running/completed tasks
- `/task 1` → shows task result

---

## Phase 4: FastAPI + SSE

**Goal:** Web API with MCP manager in lifespan.

### `api/app.py` lifespan

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Start MCP servers
    persona = load_persona(app.state.persona_name)
    mcp = McpClient(persona.mcp_gateway, persona.mcp_gateway_key)
    await mcp.list_tools()
    app.state.mcp = mcp

    # Start PG pool (for sessions/memory)
    app.state.pg_pool = await asyncpg.create_pool(PG_DSN, min_size=2, max_size=5)

    yield

    await mcp.close()
    await app.state.pg_pool.close()
```

### Create
1. `src/kukuvaia/api/app.py` — FastAPI factory with MCP lifespan
2. `src/kukuvaia/api/deps.py` — DI (pg_pool, mcp_client)
3. `src/kukuvaia/api/routes/chat.py` — SSE streaming with OutputBlocks
4. `src/kukuvaia/api/routes/sessions.py` — Session CRUD

### Test
- `curl -N -X POST /chat -d '{"message":"/help"}'` → SSE stream
- Tool calls route through MCP servers

---

## Phase 5: Compaction + Polish

### `src/kukuvaia/agent/compaction.py`
Same as v1 — chunks of 20, char/4 estimation.

### Polish
- Error handling in MCP client (gateway timeout, HTTP errors, malformed response)
- Logging: module-level `logging.getLogger(__name__)` throughout
- `CLAUDE.md` for new repo
- Type annotations on all public functions
- Graceful degradation: if MCP Gateway is down, agent works in conversation-only mode (no tools)

### Test
- Long conversation → compaction → continues
- MCP Gateway unreachable → agent responds with "tools unavailable" not crash
- `pytest tests/ -v` full suite

---

## Verification Plan

### After each phase
1. **Phase 1:** `kukuvaia` → SmartGate asks "what tables exist?" → MCP postgres tool called → answer. Sub-agent: "analyze content quality for outline X" → parent delegates to analyst sub-agent → isolated multi-round tool calls → synthesized result back to parent. Daemon: cron task fires at 2 AM → validator sub-agent validates all outlines → results in PG + notification. Webhook: CI failure → analyst sub-agent investigates → root cause report.
2. **Phase 2:** `/help` → commands + MCP tools + user commands. `/skills` → lists skills. `/skill validate-outline` → activates. User rule in `.kukuvaia/rules/` → LLM respects it.
3. **Phase 3:** Quit → resume `--session <id>` → history preserved. `/validate-all -bg` → runs in background → `/task` shows status.
4. **Phase 4:** `curl -N -X POST /chat` → SSE stream with tool calls through MCP
5. **Phase 5:** Long session compacts. Gateway down → graceful degradation.

### End-to-end
1. `kukuvaia --persona editor --outline <id>`
2. `/help` → commands + MCP tools table
3. `/validate all` → MCP Gateway → structured result
4. "Show me items without cognitive level" → SmartGate → MCP Gateway query tool → table
5. `/validate-all -bg` → background task starts → continue chatting → notification on complete
6. `/task 1` → see background task results
7. `/report` → multi-section output
8. Quit → `kukuvaia --session <id>` → context preserved

### Extensibility proof
1. Create `personas/devops.yaml` pointing at same MCP Gateway (k8s tools registered there) → `kukuvaia --persona devops` → works. Zero Python code.
2. Create `.kukuvaia/rules/safety.md` → LLM refuses dangerous operations
3. Create `.kukuvaia/skills/deploy/SKILL.md` → `/skill deploy` activates → LLM follows procedure
4. Create `.kukuvaia/commands/rollback.yaml` (type: shell) → `/rollback` executes script
5. Create `.kukuvaia/personas/reviewer.yaml` → `--persona reviewer` → custom tool_filter + commands
6. `/validate-all --background` → 100 outlines validated in background, user chats freely
