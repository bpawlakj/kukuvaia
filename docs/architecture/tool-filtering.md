# Tool Filtering — Persona ↔ Gateway Tool Visibility

## Problem

Personas define which MCP Gateway tools are visible to the agent via `tool_filter` in YAML config. This creates a fragile coupling between persona config (agent side) and tool names (gateway side).

### Failure Scenarios

**1. Gateway renames a tool** (`query` → `execute_sql`)
- Persona has `tool_filter: [query]`
- `tools/list` returns `execute_sql`
- Tool silently disappears from persona — no error, agent just loses the capability
- User sees "no tools available" without explanation

**2. Gateway adds a new tool** (`get_pods`)
- Persona has `tool_filter: [query, find]`
- New tool exists in gateway but persona doesn't see it
- Admin must manually update every persona — easy to forget

**3. Gateway removes/disables a tool** (`run_validation` offline for maintenance)
- Persona expects `run_validation` to exist
- `/validate` command calls `run_validation` → MCP error at runtime
- Command handler doesn't know if this is a bug or intentional

### Root Cause

Tool names are a shared contract between gateway and agent with no versioning, validation, or abstraction layer.

---

## Solutions

### A. Validation + Warning at Startup (Phase 1)

Cheapest fix. Agent checks `tool_filter` names against `tools/list` response at startup.

```python
# In agent startup (cli/repl.py or api/app.py lifespan)
available = {t.name for t in await mcp.list_tools()}
if persona.tool_filter:
    missing = set(persona.tool_filter) - available
    if missing:
        logger.warning(
            "Persona '%s': tools not found in gateway: %s. "
            "Available: %s", persona.name, missing, available
        )
```

| Aspect | Assessment |
|--------|-----------|
| Effort | 3 lines of code |
| Solves scenario 1 (rename) | Warning at startup — admin knows immediately |
| Solves scenario 2 (new tool) | No — new tools still invisible until persona updated |
| Solves scenario 3 (removed tool) | Warning at startup — admin knows immediately |
| Fragility | Names still manually synchronized |

**Verdict:** Good enough for 3-5 tools. Implement in Phase 1.

### B. Blacklist Instead of Whitelist

Persona defines what tools to exclude, not include. All tools visible by default.

```yaml
# Instead of: tool_filter: [query, find, run_validation]
tool_exclude:
  - get_pods      # devops only
  - delete_items  # dangerous
```

| Aspect | Assessment |
|--------|-----------|
| Effort | Minor change to filtering logic |
| Solves scenario 1 (rename) | Partially — excluded tools won't match, but that's safer (tool becomes visible, not invisible) |
| Solves scenario 2 (new tool) | Yes — new tools automatically visible |
| Solves scenario 3 (removed tool) | N/A — removed tool just disappears from list |
| Security | Weaker — new tools are visible by default, including potentially dangerous ones |

**Verdict:** Better for extensibility, worse for security. Not recommended as default.

### C. Tag-Based Filtering (Target)

Gateway returns metadata tags per tool. Persona filters by tag, not name.

Gateway response:
```json
{
  "tools": [
    {"name": "query", "description": "...", "inputSchema": {...}, 
     "annotations": {"tags": ["database", "readonly", "postgres"]}},
    {"name": "find", "description": "...", "inputSchema": {...},
     "annotations": {"tags": ["database", "readonly", "mongodb"]}},
    {"name": "run_validation", "description": "...", "inputSchema": {...},
     "annotations": {"tags": ["validation", "authorsuite"]}},
    {"name": "get_pods", "description": "...", "inputSchema": {...},
     "annotations": {"tags": ["infrastructure", "k8s"]}}
  ]
}
```

Persona config:
```yaml
tool_tags:
  - database
  - validation
```

Agent filtering:
```python
def filter_tools_by_tags(tools: list[McpTool], tags: list[str]) -> list[McpTool]:
    tag_set = set(tags)
    return [t for t in tools if tag_set & set(t.annotations.get("tags", []))]
```

| Aspect | Assessment |
|--------|-----------|
| Effort | Gateway must include `annotations.tags` in tool metadata. Agent adds tag-based filter. |
| Solves scenario 1 (rename) | Yes — filtering by tag, not name. `query` → `execute_sql` still has tag `database`. |
| Solves scenario 2 (new tool) | Yes — new tool with tag `database` automatically visible to personas with that tag. |
| Solves scenario 3 (removed tool) | Yes — tool disappears from tag group, no stale reference. |
| Fragility | Low — tags are a semantic contract, more stable than names. |

**Verdict:** Best long-term solution. Requires gateway cooperation (tags in tool metadata).

### D. No Filter — Prompt-Based Restriction

Persona sees all tools. System prompt instructs LLM which to use.

```markdown
# prompts/editor.md
You are a content editor. Only use database and validation tools.
Never use infrastructure tools (k8s, AWS).
```

| Aspect | Assessment |
|--------|-----------|
| Effort | Zero — just prompt text |
| Solves scenario 1-3 | N/A — no filtering to break |
| Security | Weak — LLM may ignore instructions and call any tool |
| Predictability | Low — LLM behavior varies |

**Verdict:** Not recommended for production. LLM instruction following is not a security boundary.

---

## Recommendation

### Phase 1: Approach A (validation + warning)

```python
# Startup validation
available = {t.name for t in await mcp.list_tools()}
if persona.tool_filter:
    missing = set(persona.tool_filter) - available
    if missing:
        logger.warning("Persona '%s': missing tools: %s", persona.name, missing)
```

Sufficient for 3-5 tools. Fast to implement. Catches renames and removals immediately.

### Phase 2+: Approach C (tag-based filtering)

When gateway has 10+ tools across multiple domains (database, validation, k8s, aws):

```yaml
# Persona config supports both (backward compatible)
tool_filter:          # explicit names (phase 1, still works)
  - run_validation
tool_tags:            # tag-based (phase 2+, preferred)
  - database
  - validation
```

Resolution logic:
1. If `tool_tags` set → filter by tags (preferred)
2. Else if `tool_filter` set → filter by names (legacy)
3. Else → all tools visible

Requires: gateway returns `annotations.tags` per tool in `tools/list` response.

---

## MCP Spec Alignment

The MCP spec supports `annotations` field on tools (optional metadata). Using `annotations.tags` for filtering is spec-compliant and does not require custom protocol extensions.

```json
{
  "name": "query",
  "description": "Execute SQL query on PostgreSQL",
  "inputSchema": {"type": "object", "properties": {"sql": {"type": "string"}}},
  "annotations": {
    "tags": ["database", "readonly", "postgres"],
    "readOnlyHint": true
  }
}
```
