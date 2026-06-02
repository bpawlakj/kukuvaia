# T13: Inter-Agent Communication (MCP Bridge)

**Status**: `pending`
**Tier**: 4 — Intelligence
**Depends On**: T10, T11
**Blocks**: —
**Source**: `docs/analyzes/inter-agent-communication-analysis.md`

## Goal

Enable external agents (Claude Code, custom agents) to connect to kukuvaia via MCP and collaborate on tasks. Agent registry for managing external agent connections. Task-scoped security tokens.

## Scope

### Agent Registry (DB)
- `kukuvaia.agents` table — name, type, capabilities, endpoint, auth config
- `kukuvaia.agent_tasks` table — task lifecycle (submitted → working → completed)

### Task-Oriented MCP Tools
Expose via existing MCP server (`spring-ai-starter-mcp-server-webmvc`):
- `getTaskContext(taskId)` — memories, rules, artifacts for the task
- `searchMemories(query)` — search kukuvaia's memory (read-only, task-scoped)
- `reportProgress(taskId, progress, message)` — update task status
- `requestHelp(taskId, problem)` — escalate to Opus advisor, return guidance
- `submitResult(taskId, summary, artifacts)` — complete the task

### Task-Scoped JWT Authentication
- Short-lived token per task (1 hour)
- Scoped to specific task (can only access own task context)
- Validated on every MCP tool call

### Claude Code Integration
External agent connects with:
```bash
claude --bare -p "task" \
  --mcp-config mcp.json \         # points to kukuvaia MCP
  --allowedTools "mcp__kukuvaia__*" \
  --append-system-prompt "rules"   # from harness
```

## File Inventory

### Create
- Migration: `V10__create_agent_registry.sql`
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/external/AgentRecord.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/external/AgentTaskRecord.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/external/AgentRegistryService.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/tools/AgentBridgeTools.java` — MCP tools
- `kukuvaia-core/src/main/java/ai/kukuvaia/api/AgentController.java`

## Acceptance Criteria

- [ ] Register external agent via `POST /api/agents`
- [ ] Create task via `POST /api/agents/{id}/tasks`
- [ ] Claude Code connects to kukuvaia MCP, calls `getTaskContext`
- [ ] `reportProgress` updates task record visible via API
- [ ] `requestHelp` triggers Opus advisor call, returns guidance
- [ ] `submitResult` completes task, stores artifacts
- [ ] Task-scoped token prevents access to other tasks
