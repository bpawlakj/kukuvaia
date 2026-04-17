# P11: Obsidian Integration -- Knowledge Base via MCP

**Created**: 2026-04-16
**Status**: Draft
**Module**: kukuvaia-core (MCP Client config), kukuvaia-app (YAML config)
**Depends on**: P-none (MCP Client dependency already in build.gradle, no code changes needed for Phase 1)
**Milestone**: 2D (MCP Integration)
**Effort**: S-M (Phase 1: hours, Phase 2-3: days)

---

## Problem

Kukuvaia agents currently operate only on data available through in-process tools (memory, planning, files, git) and future MCP server connections (ETSL). There is no integration with personal knowledge management (PKM) systems where users store their notes, documentation, research, and decision logs.

Users who maintain an Obsidian vault have a rich, structured knowledge base (markdown files with YAML frontmatter, bidirectional links, tags, folders) that kukuvaia agents cannot access. This means the agent lacks context about the user's domain knowledge, project notes, and accumulated insights.

Adding Obsidian as an MCP-connected knowledge source gives kukuvaia agents the ability to read, search, and write to the user's knowledge base -- turning the agent into a knowledge-augmented assistant.

## Current State

| Component | Status | Notes |
|-----------|--------|-------|
| `spring-ai-starter-mcp-client` | Dependency declared | In `kukuvaia-core/build.gradle`, unused |
| MCP Client config | Missing | No `spring.ai.mcp.client.*` in `application.yaml` |
| Embabel MCP conflict | Resolved | `EmbabelBeanOverride` removes conflicting `mcpSyncClients` bean |
| External MCP connections | Zero | No external MCP server configured yet |
| Obsidian MCP servers | Mature ecosystem | Multiple community implementations available |
| Knowledge base tools | None | Agent has no PKM/note-taking capabilities |

## Why Obsidian

1. **Plain markdown** -- no proprietary format, vault is a folder of `.md` files
2. **YAML frontmatter** -- structured metadata (tags, dates, custom fields) queryable by tools
3. **Mature MCP ecosystem** -- 5+ MCP server implementations, battle-tested
4. **Zero vendor lock-in** -- works without Obsidian running (filesystem-based servers)
5. **Aligns with kukuvaia philosophy** -- self-hosted, user owns data, extensible
6. **First MCP Client integration** -- proves the MCP Client path for all future integrations (ETSL, CLI, etc.)

## Architecture

```
User's Obsidian Vault (folder of .md files)
       |
       |  reads/writes files
       v
Obsidian MCP Server (external process)         <-- user runs this
  |  MCPVault (filesystem, no Obsidian needed)
  |  OR cyanheads/obsidian-mcp-server (REST API, Obsidian running)
  |  OR smart-connections-mcp (semantic search)
  |
  |  stdio / SSE transport
  v
kukuvaia-engine (Spring AI MCP Client)
  |
  |  MCP Client discovers tools at startup
  |  Tools registered in ToolRegistry alongside in-process tools
  v
ChatClient + ToolCallAdvisor
  |
  |  LLM sees all tools uniformly:
  |  - in-process: saveMemory, delegateTask, createPlan, ...
  |  - MCP/Obsidian: read_note, search_vault, create_note, ...
  |  - MCP/ETSL (future): get_classifications, search_items, ...
  v
Agent responds using knowledge from vault
```

### MCP Server Options

| Server | Transport | Requires Obsidian | Features | Best for |
|--------|-----------|-------------------|----------|----------|
| **MCPVault** | stdio | No | Read/write/search, frontmatter-safe | Default choice |
| **cyanheads/obsidian-mcp-server** | stdio | Yes (+ Local REST API plugin) | Full API: search, tags, frontmatter, periodic notes | Power users |
| **smart-connections-mcp** | stdio | Yes (+ Smart Connections plugin) | Semantic search (cosine similarity, 384-dim) | RAG use case |
| **obsidian-mcp-plugin** | HTTP | Yes (plugin runs inside Electron) | Direct Vault API, no external process | Minimal setup |

**Recommended default**: MCPVault -- works without Obsidian running, simple stdio transport, safe writes.

## Implementation

### Phase 1: MCP Client Configuration (Effort: S, hours)

**Goal**: kukuvaia-engine connects to an Obsidian MCP server and exposes vault tools to the agent.

No Java code changes required. Spring AI MCP Client auto-discovers tools from configured servers.

#### Step 1: Application YAML config

**File**: `kukuvaia-app/src/main/resources/application.yaml`

Add MCP Client configuration section:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${OBSIDIAN_MCP_ENABLED:false}
        stdio:
          servers:
            obsidian:
              command: npx
              args:
                - -y
                - "@bitbonsai/mcpvault"
              env:
                MCPVAULT_PATH: "${OBSIDIAN_VAULT_PATH:}"
```

When `OBSIDIAN_MCP_ENABLED=true` and `OBSIDIAN_VAULT_PATH=/path/to/vault`, the engine starts MCPVault as a subprocess and auto-registers its tools.

#### Step 2: Verify tool discovery

- Start engine with env vars set
- Check logs for MCP tool registration (Spring AI logs discovered tools at INFO)
- Send a chat message referencing vault content -- agent should use vault tools

#### Step 3: Documentation

**File**: `docs/integrations/obsidian.md`

Document:
- What Obsidian integration provides
- How to install MCPVault (`npm install -g @bitbonsai/mcpvault`)
- Environment variables (`OBSIDIAN_VAULT_PATH`, `OBSIDIAN_MCP_ENABLED`)
- Alternative MCP servers and when to use each
- Example prompts that leverage vault knowledge

#### Acceptance criteria
- [ ] Engine starts without Obsidian config (default: disabled)
- [ ] Engine connects to MCPVault when configured
- [ ] Vault tools appear in agent's tool list
- [ ] Agent can read a note from the vault via chat
- [ ] Agent can search the vault via chat
- [ ] Agent can create/update a note via chat

---

### Phase 2: Smart Tool Routing (Effort: S, days)

**Goal**: Agent knows when and how to use vault tools effectively via system prompt guidance.

#### Step 1: Obsidian-aware rule

**File**: `~/.kukuvaia/rules/obsidian.md` (user-extensible rule, not hardcoded)

```markdown
---
name: obsidian-knowledge
scope: global
type: behavior
---

## Obsidian Vault Integration

When the user's Obsidian vault is connected:
- Search the vault before answering domain-specific questions
- When creating notes, use `[[wikilinks]]` for cross-references
- Respect existing folder structure and naming conventions
- Add YAML frontmatter with: tags, created date, source (conversation ID)
- When the user says "save this" or "remember this", create a vault note
```

#### Step 2: Persona-level vault integration

Personas can define vault interaction patterns:

```yaml
# Example: research persona that heavily uses vault
personas:
  researcher:
    rules:
      - obsidian-knowledge
    system_prompt_append: |
      You have access to the user's Obsidian knowledge base.
      Always check existing notes before creating new ones.
      Link new findings to related existing notes.
```

#### Acceptance criteria
- [ ] Rule file loaded when present in `~/.kukuvaia/rules/`
- [ ] Agent proactively searches vault when relevant
- [ ] Created notes include proper frontmatter and wikilinks

---

### Phase 3: Semantic Search Enhancement (Effort: M, optional)

**Goal**: Combine kukuvaia-memory's pgvector with Obsidian's Smart Connections for hybrid knowledge retrieval.

This phase is optional and depends on:
- kukuvaia-memory module being implemented (Milestone 3B)
- User having Smart Connections plugin installed

#### Step 1: Add Smart Connections MCP server as alternative

```yaml
spring:
  ai:
    mcp:
      client:
        stdio:
          servers:
            obsidian-semantic:
              command: npx
              args:
                - -y
                - "smart-connections-mcp"
              env:
                SMART_CONNECTIONS_PATH: "${OBSIDIAN_VAULT_PATH:}"
```

#### Step 2: SmartMemoryAdvisor vault awareness

When SmartMemoryAdvisor retrieves memories, it can also search the Obsidian vault semantically. The advisor already injects context into the system prompt -- vault results would be added alongside memory results.

This requires a small change in `SmartMemoryAdvisor` to optionally call the semantic search tool when available.

#### Step 3: Bidirectional sync consideration

Memory extraction (episodic/semantic/procedural facts) could optionally write to Obsidian vault in addition to PostgreSQL. This gives users visibility into what the agent remembers.

**Decision**: Defer to Phase 3. The value is real but the complexity of dual-write consistency is non-trivial. Start with read-heavy integration (Phases 1-2), evaluate user feedback.

#### Acceptance criteria
- [ ] Semantic search returns relevant vault notes
- [ ] SmartMemoryAdvisor includes vault context when available
- [ ] No degradation when Obsidian is not configured

---

## Configuration Reference

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `OBSIDIAN_MCP_ENABLED` | `false` | Enable Obsidian MCP Client |
| `OBSIDIAN_VAULT_PATH` | (none) | Absolute path to Obsidian vault directory |
| `OBSIDIAN_MCP_SERVER` | `mcpvault` | Which MCP server to use (`mcpvault`, `cyanheads`, `smart-connections`) |

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| MCP server crashes | Agent loses vault tools mid-conversation | Spring AI MCP Client handles reconnection; non-vault tools continue working |
| Large vault (10k+ notes) | Slow search, high token usage | MCPVault returns paginated results; agent uses targeted search, not full reads |
| Concurrent writes (user + agent) | File conflicts | MCPVault uses atomic writes; Obsidian detects external changes and reloads |
| Sensitive notes in vault | Agent reads private content | User controls vault path -- can point to a specific subfolder, not entire vault |
| NPM dependency (npx) | Requires Node.js on server | Document as prerequisite; alternative: pre-installed binary |

## Future Considerations

- **CLI integration**: `kukuvaia-cli` could also connect to Obsidian MCP server directly for local vault browsing
- **Obsidian Publish**: Agent could manage published notes (draft → publish workflow)
- **Graph analysis**: Use Obsidian's link structure as a knowledge graph for agent reasoning
- **Template-aware creation**: Agent uses Obsidian templates when creating specific note types
- **Multi-vault**: Support multiple vaults (personal, work, project-specific) with different personas

## Open Questions

1. Should the Obsidian MCP server run as a subprocess of kukuvaia-engine (current plan) or as a separate long-running process?
   - Subprocess: simpler lifecycle, auto-cleanup
   - Separate: survives engine restarts, shared with other tools
   - **Leaning**: subprocess via Spring AI stdio transport (standard pattern)

2. Should vault notes created by the agent be tagged/marked as agent-generated?
   - Helps users filter agent vs. human notes
   - **Leaning**: yes, add `source: kukuvaia` to frontmatter + `#kukuvaia` tag

3. Should there be a read-only mode for safety?
   - Some users may want agent to only read, never write to vault
   - **Leaning**: yes, configurable via env var `OBSIDIAN_READONLY=true`
