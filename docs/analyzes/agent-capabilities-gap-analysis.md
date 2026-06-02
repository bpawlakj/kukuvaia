# Agent Capabilities Gap Analysis: Kukuvaia vs Claude Code

**Date:** 2026-04-06
**Context:** Kukuvaia operates on **documents** (outlines, content items, classifications), not source code. The same rigor and patterns apply — documents deserve the same quality gates as code.
**Implementation plan:** `docs/architecture/implementation-plan.md` (derived from this analysis)
**Sources:** Gap analysis (original), Teacher Assistant (production comparison), Claude Code (internals)

## Current State

Kukuvaia-server has a solid foundation:
- `SubAgentFactory` — isolated ChatClient per specialist with timeout
- `SubAgentGuard` — depth control, tool filtering, prompt hardening
- `ToolCallAdvisor` — multi-round tool calling (max 10 iterations)
- `ToolResultSanitizingAdvisor` — anti-injection in tool results
- `MessageWindowChatMemory` + `JdbcChatMemoryRepository` — session persistence
- Dual-provider LLM routing (Copilot/SmartGate)

**Missing:** The 6 capabilities below are what separates a "chatbot with database access" from an "intelligent document agent."

---

## 1. Document File Tools (Read, Write, Search, Find)

### Why It Matters

The agent currently has tools for structured data (PG, MongoDB) but **cannot interact with actual files**: markdown documents, YAML configs, exported reports, drafts. A document agent must read, create, edit, and search files the way Claude Code reads and edits source code.

### What "Documents" Means for Kukuvaia

| File Type | Example Use Case |
|-----------|-----------------|
| `.md` | Outlines, reports, analysis drafts, validation summaries |
| `.yaml` | Configuration, personas, rules, skill definitions |
| `.json` | Exported data, structured reports, API responses |
| `.xml` / `.html` | Content items, published documents |
| `.csv` | Classification exports, bulk data |
| `.txt` | Raw notes, logs |

### Implementation: 4 Tools as @McpTool

All tools operate within a **sandboxed workspace** — a configurable root directory per user/session. No path traversal outside it.

```java
@Component
public class DocumentTools {

    private final Path workspaceRoot; // e.g., /var/kukuvaia/workspaces/{userId}/

    @McpTool(name = "read_document",
             description = "Read a document from the workspace. Returns content with line numbers.")
    public String readDocument(
            @McpToolParam(description = "Relative path within workspace", required = true)
            String path,
            @McpToolParam(description = "Start line (0-based, default 0)")
            Integer offset,
            @McpToolParam(description = "Max lines to return (default: all)")
            Integer limit) {

        Path resolved = resolveSafe(path); // throws on traversal attempt
        List<String> lines = Files.readAllLines(resolved);
        // apply offset/limit, prepend line numbers
        return formatWithLineNumbers(lines, offset, limit);
    }

    @McpTool(name = "write_document",
             description = "Write or create a document. For edits, prefer edit_document.")
    public String writeDocument(
            @McpToolParam(description = "Relative path within workspace", required = true)
            String path,
            @McpToolParam(description = "Full document content", required = true)
            String content) {

        Path resolved = resolveSafe(path);
        validateFileType(resolved);     // only allowed extensions
        backupIfExists(resolved);       // .bak before overwrite
        Files.writeString(resolved, content, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return "Written %d bytes to %s".formatted(content.length(), path);
    }

    @McpTool(name = "edit_document",
             description = "Replace exact text in a document (like a precise patch)")
    public String editDocument(
            @McpToolParam(description = "Relative path", required = true) String path,
            @McpToolParam(description = "Exact text to find", required = true) String oldText,
            @McpToolParam(description = "Replacement text", required = true) String newText) {

        Path resolved = resolveSafe(path);
        String content = Files.readString(resolved);
        if (!content.contains(oldText))
            return "ERROR: old_text not found in document";
        long count = countOccurrences(content, oldText);
        if (count > 1)
            return "ERROR: old_text matches %d times — provide more context".formatted(count);

        backupIfExists(resolved);
        Files.writeString(resolved, content.replace(oldText, newText));
        return "Replaced 1 occurrence in %s".formatted(path);
    }

    @McpTool(name = "search_documents",
             description = "Search document contents by regex pattern. Returns matching lines.")
    public String searchDocuments(
            @McpToolParam(description = "Regex pattern to search for", required = true)
            String pattern,
            @McpToolParam(description = "Glob filter for file names (e.g., '*.md')")
            String glob,
            @McpToolParam(description = "Max results (default 50)")
            Integer maxResults) {

        // Walk workspace, filter by glob, search by pattern
        // Return: file:line: matched_line
    }

    @McpTool(name = "find_documents",
             description = "Find documents by name/path pattern (glob)")
    public String findDocuments(
            @McpToolParam(description = "Glob pattern (e.g., '**/*.md', 'reports/*.yaml')",
                          required = true)
            String pattern) {

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        // Walk workspace, collect matches, sort by modification time
    }

    // --- Security ---

    private Path resolveSafe(String relativePath) {
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new SecurityException("Path traversal attempt blocked: " + relativePath);
        }
        return resolved;
    }
}
```

### Security Model

| Threat | Mitigation |
|--------|-----------|
| Path traversal (`../../etc/passwd`) | `normalize()` + `startsWith(root)` check |
| Symlink escape | `Files.readAttributes(NOFOLLOW_LINKS)` before read |
| Large file DoS | Max file size limit (e.g., 5 MB) |
| Binary injection | Extension whitelist (`.md`, `.yaml`, `.json`, `.xml`, `.csv`, `.txt`, `.html`) |
| Data loss on write | Automatic `.bak` backup before overwrite |
| Concurrent writes | File locking or optimistic conflict detection |

### Workspace Configuration

```yaml
kukuvaia:
  workspace:
    root: ${KUKUVAIA_WORKSPACE:/var/kukuvaia/workspaces}
    max-file-size-kb: 5120          # 5 MB
    allowed-extensions: [md, yaml, json, xml, csv, txt, html]
    backup-on-write: true
    max-search-results: 100
```

### Mapping to Claude Code Equivalents

| Claude Code | Kukuvaia | Notes |
|------------|----------|-------|
| `Read` | `read_document` | Line numbers, offset/limit |
| `Write` | `write_document` | Full content replacement |
| `Edit` | `edit_document` | Exact string patch (same semantics!) |
| `Grep` | `search_documents` | Regex + glob filter |
| `Glob` | `find_documents` | PathMatcher glob matching |

---

## 2. Command Execution (Sandboxed)

### Why It Matters

Document agents need to run validation tools (markdownlint, vale), format converters (pandoc), quality checkers (spelling, link validation), and version control (git) — but NEVER arbitrary shell commands.

### Document-Oriented Use Cases

| Command Category | Examples |
|-----------------|----------|
| Validation | `markdownlint *.md`, `vale docs/`, `xmllint --schema ...` |
| Conversion | `pandoc doc.md -o doc.pdf`, `wkhtmltopdf` |
| Quality | `aspell check`, `hunspell`, link checkers |
| Version control | `git diff`, `git log`, `git status` |
| Analysis | `wc -l`, `diff file1 file2` |

### Implementation: Allowlisted Commands

**NOT a general bash tool.** Kukuvaia exposes a curated set of commands, each wrapped as a tool.

```java
@Component
public class CommandTools {

    private final Path workspaceRoot;
    private final Map<String, CommandSpec> allowedCommands;

    @McpTool(name = "run_command",
             description = "Run an allowed command in the workspace. " +
                           "Available: markdownlint, vale, pandoc, git, diff, wc")
    public String runCommand(
            @McpToolParam(description = "Command name", required = true)
            String command,
            @McpToolParam(description = "Arguments as list")
            List<String> args,
            @McpToolParam(description = "Timeout in seconds (default 30)")
            Integer timeoutSeconds) {

        CommandSpec spec = allowedCommands.get(command);
        if (spec == null) {
            return "ERROR: Command '%s' not allowed. Available: %s"
                .formatted(command, allowedCommands.keySet());
        }

        // Build process — NO shell expansion
        List<String> fullCmd = new ArrayList<>();
        fullCmd.add(spec.binaryPath());     // absolute path to binary
        fullCmd.addAll(sanitizeArgs(args));  // no shell metacharacters

        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", "/nonexistent");   // no home dir access
        pb.environment().put("PATH", spec.allowedPath()); // minimal PATH

        int timeout = Math.min(timeoutSeconds != null ? timeoutSeconds : 30, 120);

        Process process = pb.start();
        String output = readOutput(process, MAX_OUTPUT_BYTES);  // 64 KB limit
        boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);

        if (!completed) {
            process.destroyForcibly();
            return "ERROR: Command timed out after %ds".formatted(timeout);
        }

        return "Exit code: %d\n%s".formatted(process.exitValue(), output);
    }
}
```

### Allowlist Configuration

```yaml
kukuvaia:
  commands:
    allowed:
      markdownlint:
        binary: /usr/local/bin/markdownlint
        max-timeout: 60
      vale:
        binary: /usr/local/bin/vale
        max-timeout: 60
      pandoc:
        binary: /usr/bin/pandoc
        max-timeout: 120
      git:
        binary: /usr/bin/git
        max-timeout: 30
        blocked-subcommands: [push, remote, config, rebase]  # read-only git
      diff:
        binary: /usr/bin/diff
        max-timeout: 10
      wc:
        binary: /usr/bin/wc
        max-timeout: 5
```

### Security Model

| Threat | Mitigation |
|--------|-----------|
| Arbitrary command execution | Allowlist with absolute binary paths |
| Shell injection (`;`, `&&`, `\|`) | `ProcessBuilder` — no shell, no metacharacter expansion |
| Resource exhaustion | Timeout (max 120s) + output size limit (64 KB) |
| Filesystem escape | Working directory = workspace root, minimal PATH |
| Destructive git ops | Blocked subcommands (`push`, `rebase`, `config`) |
| Environment leakage | Stripped env vars, no HOME |

### Alternative: No Shell at All

For maximum safety, implement each operation as a **pure Java tool** — no external process:

```java
@McpTool(name = "diff_documents", ...)
public String diffDocuments(String pathA, String pathB) {
    // Java diff-utils library — no shell needed
}

@McpTool(name = "check_markdown", ...)
public String checkMarkdown(String path) {
    // flexmark-java or commonmark-java for structure validation
    // Custom rules in Java — no external markdownlint
}

@McpTool(name = "word_count", ...)
public String wordCount(String path) {
    // Pure Java — trivial
}
```

**Recommendation:** Start with pure Java tools for common ops. Add ProcessBuilder allowlist only for tools that have no good Java equivalent (pandoc, vale).

---

## 3. Context Window Management (Beyond Sliding Window)

### The Problem

`MessageWindowChatMemory` keeps the last N messages and drops everything older. This means:

- Agent "forgets" documents read 20 messages ago
- User corrections from early in the session vanish
- Multi-step document workflows lose prior context
- No understanding of overall session arc

### Solution: Hierarchical Memory with Summarization

Three levels of context, each with different compression:

```
┌─────────────────────────────────────────────────────────────────┐
│ LEVEL 0: Working Set (always full fidelity)                     │
│  Last 10-15 messages — current task context                     │
│  + Currently "open" documents (last read/edited)                │
│  + Active plan steps                                            │
├─────────────────────────────────────────────────────────────────┤
│ LEVEL 1: Session Summary (compressed)                           │
│  Earlier messages → LLM-generated summary (updated every 10msg) │
│  What was discussed, decisions made, documents touched           │
├─────────────────────────────────────────────────────────────────┤
│ LEVEL 2: Persistent Memory (cross-session)                      │
│  User preferences, project facts, learned corrections           │
│  See section 5                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Implementation: Custom ChatMemory

```java
public class HierarchicalChatMemory implements ChatMemory {

    private final ChatMemory delegate;          // JdbcChatMemoryRepository
    private final ChatClient summarizer;         // lightweight LLM for summaries
    private final int fullWindowSize = 15;       // messages at full fidelity
    private final int summaryTriggerInterval = 10; // summarize every N messages

    // In-memory cache per session — persisted to PG on summarization
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    @Override
    public List<Message> get(String sessionId, int lastN) {
        List<Message> allMessages = delegate.get(sessionId, Integer.MAX_VALUE);

        if (allMessages.size() <= fullWindowSize) {
            return allMessages; // small session — no compression needed
        }

        // Split: older messages → summarize, recent → keep full
        List<Message> older = allMessages.subList(0, allMessages.size() - fullWindowSize);
        List<Message> recent = allMessages.subList(allMessages.size() - fullWindowSize,
                                                    allMessages.size());

        String summary = getOrCreateSummary(sessionId, older);

        List<Message> result = new ArrayList<>();
        result.add(new SystemMessage(buildSummaryBlock(summary)));
        result.addAll(recent);
        return result;
    }

    private String getOrCreateSummary(String sessionId, List<Message> messages) {
        String cached = sessionSummaries.get(sessionId);
        int currentHash = messages.hashCode();

        if (cached != null && !needsRefresh(sessionId, currentHash)) {
            return cached;
        }

        // Use LLM to summarize — but this is a CHEAP call (small model, structured output)
        String summary = summarizer.prompt()
            .system("""
                Summarize this conversation history concisely. Preserve:
                - Key decisions made
                - Documents read, created, or modified (with paths)
                - User corrections and preferences expressed
                - Current task state and what remains to be done
                Format as bullet points. Max 500 words.
                """)
            .user(formatMessages(messages))
            .call().content();

        sessionSummaries.put(sessionId, summary);
        persistSummary(sessionId, summary); // save to PG for crash recovery
        return summary;
    }

    private String buildSummaryBlock(String summary) {
        return """
            ## Earlier in this session:
            %s

            ## Current context continues below:
            """.formatted(summary);
    }
}
```

### Working Set Tracking

Beyond message history, track which documents are "in context":

```java
@Component
public class WorkingSetTracker {

    // Per-session: documents recently read or edited by the agent
    private final Map<String, LinkedHashMap<String, DocumentRef>> workingSets =
        new ConcurrentHashMap<>();

    private static final int MAX_WORKING_SET = 5; // last 5 documents

    public void onDocumentAccess(String sessionId, String path, AccessType type) {
        workingSets.computeIfAbsent(sessionId, k -> new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > MAX_WORKING_SET;
            }
        }).put(path, new DocumentRef(path, type, Instant.now()));
    }

    public String getWorkingSetContext(String sessionId) {
        var set = workingSets.get(sessionId);
        if (set == null || set.isEmpty()) return "";
        return "Documents in your working set:\n" +
               set.values().stream()
                   .map(d -> "- %s (last %s)".formatted(d.path(), d.type()))
                   .collect(Collectors.joining("\n"));
    }
}
```

### Spring AI Integration

Inject as a custom `Advisor` that runs before `MessageChatMemoryAdvisor`:

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                       HierarchicalChatMemory memory,
                       WorkingSetTracker workingSet) {
    return builder
        .defaultAdvisors(
            new WorkingSetAdvisor(workingSet),        // inject document context
            MessageChatMemoryAdvisor.builder(memory).build(), // hierarchical memory
            ToolCallAdvisor.builder().build()
        )
        .build();
}
```

### Cost Consideration

Summarization uses LLM tokens. Mitigation:
- Use cheapest model for summarization (Haiku-class)
- Summarize only when message count crosses threshold (every 10 messages)
- Cache summaries — only regenerate when new messages added to the "older" window
- Budget: ~500 input + 200 output tokens per summarization ≈ negligible vs conversation cost

---

## 4. Agent Loop with Planning

### The Problem

Current `ToolCallAdvisor` provides a flat loop: prompt → tool_call → result → prompt → ... This works for simple tasks ("get classifications for outline X") but fails for complex multi-step work:

- "Review this outline for completeness, check each section, write a summary report"
- "Compare these two document versions and highlight significant changes"
- "Validate all content items, fix formatting issues, and generate a compliance report"

Without planning, the agent jumps to the first action without considering the full scope, loses track mid-execution, and can't recover from failures.

### Solution: Plan-as-Tool Pattern

The simplest approach that works within existing Spring AI: **planning is a tool the agent calls first.**

No custom advisor needed. No changes to ToolCallAdvisor. The agent's system prompt instructs it to plan before acting, and the plan tool provides structure.

```java
@Component
public class PlanningTools {

    private final Map<String, Plan> activePlans = new ConcurrentHashMap<>();

    @McpTool(name = "create_plan",
             description = "Create a step-by-step execution plan before starting complex work. " +
                           "Call this FIRST for any task with 3+ steps.")
    public String createPlan(
            @McpToolParam(description = "Concise task description", required = true)
            String task,
            @McpToolParam(description = "Ordered list of steps to execute", required = true)
            List<String> steps,
            @McpToolParam(description = "Session ID for plan tracking", required = true)
            String sessionId) {

        Plan plan = new Plan(task, steps);
        activePlans.put(sessionId, plan);
        persistPlan(sessionId, plan); // save to PG for crash recovery

        return """
            Plan created: %s
            Steps:
            %s

            Now execute step 1. After each step, call complete_step to track progress.
            """.formatted(task, plan.formatSteps());
    }

    @McpTool(name = "complete_step",
             description = "Mark a plan step as completed. Report what was accomplished.")
    public String completeStep(
            @McpToolParam(description = "Session ID", required = true) String sessionId,
            @McpToolParam(description = "Step number (1-based)", required = true) int stepNumber,
            @McpToolParam(description = "Brief result of this step", required = true)
            String result) {

        Plan plan = activePlans.get(sessionId);
        if (plan == null) return "ERROR: No active plan for this session";

        plan.completeStep(stepNumber, result);
        persistPlan(sessionId, plan);

        if (plan.isComplete()) {
            return """
                All steps completed.
                Summary:
                %s
                """.formatted(plan.formatResults());
        }

        return """
            Step %d completed: %s
            Remaining: %s
            Next: Step %d — %s
            """.formatted(stepNumber, result,
                          plan.remainingCount(),
                          plan.nextStepNumber(), plan.nextStepDescription());
    }

    @McpTool(name = "revise_plan",
             description = "Update the plan if circumstances changed (e.g., step failed, " +
                           "new information discovered). Preserves completed steps.")
    public String revisePlan(
            @McpToolParam(description = "Session ID", required = true) String sessionId,
            @McpToolParam(description = "Reason for revision", required = true) String reason,
            @McpToolParam(description = "New remaining steps", required = true)
            List<String> newSteps) {

        Plan plan = activePlans.get(sessionId);
        if (plan == null) return "ERROR: No active plan for this session";

        plan.revise(reason, newSteps);
        persistPlan(sessionId, plan);

        return "Plan revised. Reason: %s\nNew remaining steps:\n%s"
            .formatted(reason, plan.formatRemainingSteps());
    }
}
```

### System Prompt Instruction

Add to the agent's system prompt (via persona or rules):

```
## Planning

For any task with 3 or more steps:
1. Call create_plan FIRST — think through all steps before starting
2. Execute steps one at a time
3. Call complete_step after each step — track what you accomplished
4. If something unexpected happens, call revise_plan before continuing
5. Never skip steps or mark steps complete without doing them

For simple tasks (1-2 steps): proceed directly without planning.
```

### Plan Persistence (PostgreSQL)

```sql
CREATE TABLE kukuvaia_agent.agent_plans (
    session_id  VARCHAR(255) PRIMARY KEY,
    task        TEXT NOT NULL,
    steps       JSONB NOT NULL,      -- [{step: 1, desc: "...", status: "pending|done", result: "..."}]
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);
```

### Why Plan-as-Tool Over Custom Advisor

| Approach | Pros | Cons |
|----------|------|------|
| **Plan-as-Tool** (recommended) | Zero framework changes, works with ToolCallAdvisor, LLM decides when to plan | Relies on LLM following instructions |
| Custom PlanningAdvisor | Enforced planning, structured output | Complex, fights ToolCallAdvisor, hard to debug |
| Two-phase execution | Clean separation | Requires custom orchestration outside Spring AI |

Plan-as-Tool is how Claude Code works — planning is a tool (`EnterPlanMode`), not a framework feature.

---

## 5. Persistent Memory (Cross-Session)

### The Problem

Current `MessageWindowChatMemory` is session-scoped. When a session ends or overflows, knowledge is lost:

- "The user prefers bullet points over prose" — forgotten
- "Outline X was restructured last week" — forgotten
- "Use vale for quality checks, not markdownlint" — forgotten
- "The ETSL format requires section numbering" — forgotten

### Solution: Memory as Tools + Advisor Injection

Two parts:
1. **Memory Tools** — agent can save/search/delete memories (like Claude Code's memory system)
2. **Memory Advisor** — injects relevant memories at conversation start

### Database Schema

```sql
CREATE TABLE kukuvaia_agent.agent_memory (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(255) NOT NULL,
    category    VARCHAR(50) NOT NULL CHECK (category IN
                    ('user', 'project', 'feedback', 'reference')),
    name        VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,         -- used for relevance matching
    content     TEXT NOT NULL,
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, name)
);

CREATE INDEX idx_memory_user_category ON kukuvaia_agent.agent_memory(user_id, category);
CREATE INDEX idx_memory_search ON kukuvaia_agent.agent_memory
    USING gin(to_tsvector('english', description || ' ' || content));
```

### Memory Tools

```java
@Component
public class MemoryTools {

    private final JdbcTemplate jdbc;

    @McpTool(name = "save_memory",
             description = "Save a persistent memory for future sessions. Categories: " +
                           "user (preferences/profile), project (facts/decisions), " +
                           "feedback (corrections/what works), reference (external resources)")
    public String saveMemory(
            @McpToolParam(description = "Category: user|project|feedback|reference",
                          required = true) String category,
            @McpToolParam(description = "Short name (unique key)", required = true) String name,
            @McpToolParam(description = "One-line description for relevance matching",
                          required = true) String description,
            @McpToolParam(description = "Memory content", required = true) String content) {

        // Upsert — update if name exists, insert if new
        jdbc.update("""
            INSERT INTO kukuvaia_agent.agent_memory (user_id, category, name, description, content)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (user_id, name) DO UPDATE SET
                category = EXCLUDED.category,
                description = EXCLUDED.description,
                content = EXCLUDED.content,
                updated_at = NOW()
            """, currentUserId(), category, name, description, content);

        return "Memory saved: [%s] %s".formatted(category, name);
    }

    @McpTool(name = "search_memories",
             description = "Search persistent memories by keyword or category")
    public String searchMemories(
            @McpToolParam(description = "Search query (keyword or phrase)") String query,
            @McpToolParam(description = "Filter by category (optional)") String category) {

        // Full-text search with optional category filter
        String sql = """
            SELECT name, category, description, content, updated_at
            FROM kukuvaia_agent.agent_memory
            WHERE user_id = ?
            AND (%s)
            AND (%s)
            ORDER BY ts_rank(to_tsvector('english', description || ' ' || content),
                            plainto_tsquery('english', ?)) DESC
            LIMIT 10
            """.formatted(
                category != null ? "category = ?" : "TRUE",
                query != null ? "to_tsvector('english', description || ' ' || content) @@ plainto_tsquery('english', ?)" : "TRUE"
            );
        // ... execute and format results
    }

    @McpTool(name = "delete_memory",
             description = "Delete a persistent memory by name")
    public String deleteMemory(
            @McpToolParam(description = "Memory name to delete", required = true) String name) {
        int rows = jdbc.update(
            "DELETE FROM kukuvaia_agent.agent_memory WHERE user_id = ? AND name = ?",
            currentUserId(), name);
        return rows > 0 ? "Memory deleted: " + name : "Memory not found: " + name;
    }

    @McpTool(name = "list_memories",
             description = "List all memories, optionally filtered by category")
    public String listMemories(
            @McpToolParam(description = "Filter by category (optional)") String category) {
        // Return name, category, description (not full content) — like MEMORY.md index
    }
}
```

### Memory Advisor — Auto-Injection

At conversation start, load relevant memories and inject as system prompt context:

```java
@Component
public class PersistentMemoryAdvisor implements CallAdvisor {

    private final JdbcTemplate jdbc;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5; // before memory advisor, after security
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAdvisorChain chain) {
        String userId = extractUserId(request);

        // Load feedback + user memories (always relevant)
        List<MemoryRecord> alwaysOn = loadByCategories(userId, List.of("user", "feedback"));

        // Load project memories (if workspace/outline context is set)
        List<MemoryRecord> projectMemories = loadByCategory(userId, "project");

        if (!alwaysOn.isEmpty() || !projectMemories.isEmpty()) {
            String memoryBlock = formatMemoryBlock(alwaysOn, projectMemories);
            request = request.mutate()
                .systemText(request.systemText() + "\n\n" + memoryBlock)
                .build();
        }

        return chain.nextAroundCall(request);
    }

    private String formatMemoryBlock(List<MemoryRecord> alwaysOn,
                                      List<MemoryRecord> project) {
        StringBuilder sb = new StringBuilder("## Persistent Memory\n\n");
        if (!alwaysOn.isEmpty()) {
            sb.append("### About this user:\n");
            alwaysOn.forEach(m -> sb.append("- **%s**: %s\n".formatted(m.name(), m.content())));
        }
        if (!project.isEmpty()) {
            sb.append("\n### Project context:\n");
            project.forEach(m -> sb.append("- **%s**: %s\n".formatted(m.name(), m.content())));
        }
        return sb.toString();
    }
}
```

### Memory Categories for Document Work

| Category | Example Memories |
|----------|-----------------|
| `user` | "Prefers concise reports", "Senior editor, knows ETSL format" |
| `project` | "Outline 12345 was restructured on 2026-03-15", "ETSL v3 requires numbered sections" |
| `feedback` | "Don't auto-fix formatting without asking", "Use vale, not markdownlint" |
| `reference` | "Style guide at confluence.internal/etsl-style", "JIRA board: ETSL-CONTENT" |

---

## 6. Self-Verification

### The Problem

Currently the agent generates output and reports "done." It never:
- Re-reads what it wrote to verify correctness
- Runs quality checks on generated documents
- Compares output against requirements
- Iterates if quality is insufficient

Claude Code does this naturally — it runs tests after writing code. Kukuvaia needs the equivalent for documents.

### Solution: Verification Tools + System Prompt Discipline

Two complementary approaches:
1. **Verification tools** — the agent can call to check quality
2. **System prompt instruction** — mandates verification after writes

### Verification Tools

```java
@Component
public class VerificationTools {

    @McpTool(name = "verify_document",
             description = "Run quality checks on a document. Returns issues found. " +
                           "Always call this after writing or editing a document.")
    public String verifyDocument(
            @McpToolParam(description = "Document path", required = true) String path,
            @McpToolParam(description = "Checks to run: structure, links, " +
                                        "consistency, completeness, formatting")
            List<String> checks) {

        Path resolved = resolveSafe(path);
        String content = Files.readString(resolved);
        List<Issue> issues = new ArrayList<>();

        for (String check : checks) {
            switch (check) {
                case "structure" -> issues.addAll(checkStructure(content, path));
                case "links" -> issues.addAll(checkLinks(content, resolved));
                case "consistency" -> issues.addAll(checkConsistency(content));
                case "completeness" -> issues.addAll(checkCompleteness(content));
                case "formatting" -> issues.addAll(checkFormatting(content));
            }
        }

        if (issues.isEmpty()) {
            return "All checks passed for %s".formatted(path);
        }

        return "Issues found in %s:\n%s".formatted(path,
            issues.stream()
                .map(i -> "- [%s] Line %d: %s".formatted(i.severity(), i.line(), i.message()))
                .collect(Collectors.joining("\n")));
    }

    @McpTool(name = "compare_documents",
             description = "Compare two document versions. Shows structural and content changes.")
    public String compareDocuments(
            @McpToolParam(description = "First document path", required = true) String pathA,
            @McpToolParam(description = "Second document path", required = true) String pathB) {
        // Diff + structural comparison
    }

    @McpTool(name = "verify_against_requirements",
             description = "Check if a document meets specified requirements")
    public String verifyAgainstRequirements(
            @McpToolParam(description = "Document path", required = true) String path,
            @McpToolParam(description = "Requirements as checklist items", required = true)
            List<String> requirements) {

        String content = Files.readString(resolveSafe(path));

        // LLM-assisted verification: does the document meet each requirement?
        // This is a structured call — cheap and targeted
        return verificationClient.prompt()
            .system("You are a document quality checker. For each requirement, " +
                    "verify if the document satisfies it. Be strict.")
            .user("Document:\n%s\n\nRequirements:\n%s".formatted(
                content,
                requirements.stream().map(r -> "- " + r).collect(Collectors.joining("\n"))))
            .call().content();
    }

    // --- Built-in checks (no LLM needed) ---

    private List<Issue> checkStructure(String content, String path) {
        List<Issue> issues = new ArrayList<>();
        // Markdown: heading hierarchy (no h3 before h2), no empty sections
        // YAML: valid syntax
        // JSON: valid syntax, required fields
        return issues;
    }

    private List<Issue> checkLinks(String content, Path docPath) {
        // Extract [text](url) and [[wikilinks]]
        // Verify internal links point to existing files
        // Flag external links (optionally check HTTP status)
        return List.of();
    }

    private List<Issue> checkConsistency(String content) {
        // Terminology consistency (same term used for same concept)
        // Date format consistency
        // Numbering consistency (no gaps)
        return List.of();
    }
}
```

### System Prompt: Verification Mandate

Add to agent system prompt:

```
## Quality Gates

After creating or modifying ANY document:
1. Re-read the document using read_document
2. Call verify_document with checks: [structure, formatting, consistency]
3. If issues found: fix them, then verify again
4. Only report completion after verification passes with zero issues

After completing a plan:
1. Review all modified documents
2. Run verify_document on each
3. If requirements were specified, call verify_against_requirements
4. Report: what was done + verification results

NEVER say "done" without verifying.
```

### Self-Verification Loop (Within ToolCallAdvisor)

This requires no custom advisor. The system prompt instruction + verification tools create a natural loop:

```
Agent receives: "Write a summary report for outline X"

1. create_plan(steps: ["Read outline", "Analyze", "Write report", "Verify"])
2. read_document("outlines/X.md")
3. search_documents(pattern: "section", glob: "outlines/X/**")
4. write_document("reports/X-summary.md", content)
5. complete_step(3, "Report written")
6. read_document("reports/X-summary.md")         ← re-reads own output
7. verify_document("reports/X-summary.md",        ← quality check
                   checks: [structure, formatting, completeness])
8. edit_document("reports/X-summary.md", ...)     ← fixes issues
9. verify_document("reports/X-summary.md", ...)   ← re-verify
10. complete_step(4, "Verification passed")
```

The `maxToolRounds` in ToolCallAdvisor (currently 10) may need to increase to 15-20 for complex tasks with verification loops.

---

## Implementation Priority

| Phase | Capability | Effort | Impact | Dependencies |
|-------|-----------|--------|--------|-------------|
| **1** | Document File Tools | Medium | Critical | Workspace configuration |
| **1** | Persistent Memory | Medium | High | PG schema migration |
| **2** | Self-Verification | Low | High | File Tools (phase 1) |
| **2** | Planning Tools | Low | High | None (pure tools) |
| **3** | Context Management | High | Medium | Summarization LLM budget |
| **3** | Command Execution | Medium | Medium | Binary installation, security review |

### Phase 1 (Foundation)

File tools + persistent memory are **prerequisites** for everything else. Without file access, the agent can't read or write documents. Without memory, every session starts from zero.

### Phase 2 (Intelligence)

Planning and verification make the agent reliable. These are **pure tool implementations** — no framework changes. Low effort, high impact.

### Phase 3 (Sophistication)

Hierarchical memory and command execution are refinements. Context management has the highest complexity (LLM-in-the-loop for summarization). Command execution requires security hardening and binary management.

---

## Architecture Fit

All 6 capabilities integrate with the existing architecture without breaking changes:

```
Existing                              New (additive)
────────                              ───────────────
SubAgentFactory                       (unchanged)
SubAgentGuard.filterTools()           + new tool names in filtering
ToolCallAdvisor                       + maxIterations → 15-20
ToolResultSanitizingAdvisor           (unchanged)
MessageWindowChatMemory               → HierarchicalChatMemory (Phase 3)
JdbcChatMemoryRepository              + agent_memory table, agent_plans table
@McpTool catalog                      + DocumentTools, PlanningTools,
                                        MemoryTools, VerificationTools,
                                        CommandTools
Persona YAML (tool_filter)            + new tools in persona configs
DaemonAgentService                    (unchanged — daemon can use new tools)
```

**Key principle:** Everything new is a `@McpTool` component or a custom `Advisor`. No changes to SubAgentFactory, SubAgentGuard, or DaemonAgentService. The existing security model (tool filtering, prompt hardening, depth control) applies automatically to all new tools.

---

## 7. Automatic Memory Extraction (Learned from Teacher Assistant)

### The Problem

Section 5 proposes memory-as-tool — the agent explicitly calls `save_memory` when it decides something is worth remembering. This relies on the LLM choosing to save, which means:

- Agent forgets to save user corrections ("I said don't use bullet points!")
- Preferences expressed casually are never captured
- Memory quality depends on prompt following, not architecture

Teacher Assistant solves this differently: it extracts memory **automatically after every turn**, using a separate lightweight LLM call. The agent never "decides" to remember — the system always does.

### Solution: MemoryExtractionAdvisor

A post-processing advisor that runs after every agent response, extracts memorable facts, and persists them — without the agent's involvement.

```java
@Component
public class MemoryExtractionAdvisor implements CallAdvisor {

    private final ChatClient extractor;  // lightweight model (Haiku-class)
    private final MemoryRepository memoryRepository;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // runs AFTER response is generated
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAdvisorChain chain) {
        // Let the main agent generate its response first
        AdvisedResponse response = chain.nextAroundCall(request);

        // Async: extract memories from user message + agent response
        CompletableFuture.runAsync(() ->
            extractAndPersist(request.userText(), response.content(),
                              extractUserId(request))
        );

        return response; // don't block the response
    }

    private void extractAndPersist(String userMessage, String agentResponse, String userId) {
        String extraction = extractor.prompt()
            .system("""
                Analyze this conversation turn. Extract ONLY if present:

                1. USER_PREFERENCE: How the user wants things done
                   (format preferences, communication style, tool preferences)
                2. USER_CORRECTION: User corrected the agent's approach
                   (explicit "don't do X", "use Y instead", "that's wrong")
                3. PROJECT_FACT: Non-obvious fact about the project/domain
                   (decisions made, constraints discovered, deadlines)
                4. NONE: Nothing worth remembering in this turn

                Output JSON array. Empty array if NONE.
                Example: [{"type":"USER_PREFERENCE","name":"format_preference",
                           "content":"User prefers tables over bullet lists for comparisons"}]
                """)
            .user("User: %s\nAssistant: %s".formatted(userMessage, agentResponse))
            .call().content();

        List<MemoryExtraction> memories = parseExtractions(extraction);
        for (MemoryExtraction m : memories) {
            memoryRepository.upsert(userId, mapCategory(m.type()), m.name(), m.content());
        }
    }
}
```

### Dual Memory Strategy

Both approaches coexist — they complement each other:

| Mechanism | What It Captures | When It Runs |
|-----------|-----------------|-------------|
| **MemoryExtractionAdvisor** (automatic) | Preferences, corrections, facts — from conversation flow | After every turn, async |
| **Memory Tools** (explicit, section 5) | Deliberate saves, structured references, curated knowledge | When agent decides to call `save_memory` |

The automatic extractor catches what the agent would miss. The explicit tools let the agent save structured, intentional memories. Together they provide comprehensive coverage.

### Cost

- Lightweight model (Haiku-class): ~200 input + 50 output tokens per turn
- Async — doesn't slow down the response
- Most turns produce `NONE` — no write to PG
- Budget: ~$0.001 per turn ≈ negligible

---

## 8. Intent-Driven Retrieval (Learned from Teacher Assistant)

### The Problem

Current Kukuvaia routes all free-form text to the ChatClient agent loop with tools. Every message gets the same treatment — full tool access, full context, full cost. But many messages don't need tools at all:

- "What does ETSL stand for?" — answer from memory, no tools needed
- "Summarize what we discussed" — conversation history, no tools needed
- "Compare sections 3 and 7 of outline X" — needs document tools + retrieval
- "Validate all content items" — needs tools + planning + verification

Teacher Assistant solves this with **intent detection**: a cheap, fast LLM call that classifies the message and decides what resources to activate.

### Solution: IntentDetectionAdvisor

A pre-processing advisor that classifies user intent before the main agent runs, and configures the request accordingly.

```java
@Component
public class IntentDetectionAdvisor implements CallAdvisor {

    private final ChatClient intentDetector; // lightweight model

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // early, before tool setup
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAdvisorChain chain) {
        UserIntent intent = detectIntent(request);

        // Inject intent as advisor param — downstream advisors/tools can read it
        Map<String, Object> params = new HashMap<>(request.advisorParams());
        params.put("kukuvaia.intent", intent);

        AdvisedRequest enriched = request.mutate()
            .advisorParams(params)
            .build();

        // Optionally: adjust tool availability based on intent
        // CONVERSATION_ONLY → no tools (cheaper, faster)
        // DOCUMENT_WORK → full tool set
        // RETRIEVAL_NEEDED → enable RAG sources

        return chain.nextAroundCall(enriched);
    }

    private UserIntent detectIntent(AdvisedRequest request) {
        String result = intentDetector.prompt()
            .system("""
                Classify the user's message intent. Output JSON:
                {
                  "type": "CONVERSATION | DOCUMENT_READ | DOCUMENT_WRITE |
                           ANALYSIS | RETRIEVAL | COMMAND",
                  "needs_tools": true/false,
                  "needs_planning": true/false,
                  "complexity": "simple | moderate | complex",
                  "refined_query": "optimized search query if retrieval needed"
                }
                """)
            .user(request.userText())
            .call().content();

        return parseIntent(result);
    }
}
```

### Intent-Based Routing

| Intent | Tools | Planning | Model | Cost |
|--------|-------|----------|-------|------|
| `CONVERSATION` | None | No | Lightweight | Lowest |
| `DOCUMENT_READ` | read, search, find | No | Standard | Low |
| `DOCUMENT_WRITE` | read, write, edit, verify | If complex | Standard | Medium |
| `ANALYSIS` | All document + data tools | Yes | Full | High |
| `RETRIEVAL` | Data tools + search | No | Standard | Medium |
| `COMMAND` | Skip agent — route to CommandRouter | No | None | Zero LLM |

### Why This Matters

Without intent detection, a simple "thanks" message triggers:
1. Load all tools into context (token cost)
2. Full model processing (slower, more expensive)
3. Tool calling advisor overhead

With intent detection, "thanks" is classified as `CONVERSATION` → no tools loaded, lightweight model, instant response. Savings: ~80% cost on simple messages.

### Cost

- Intent detection: ~100 input + 30 output tokens (Haiku)
- Per-turn overhead: ~$0.0003
- Pays for itself by avoiding unnecessary full-model calls on simple messages

---

## 9. Deterministic + AI Hybrid Routing (Learned from Teacher Assistant)

### The Problem

Kukuvaia already has this pattern in its design (`CommandRouter`: slash commands → deterministic, free text → agent loop). But the boundary is binary: either it's a `/command` or it goes to the full agent.

Teacher Assistant extends this with **generators** — structured, parameterized workflows that use AI but follow a deterministic pipeline (not free-form agent loop).

### Solution: Structured Workflows (Between Commands and Agent)

Three tiers of execution:

```
Tier 1: Deterministic Commands (no LLM)
  /status, /sessions, /help
  → CommandRouter → direct execution → OutputBlocks

Tier 2: Structured Workflows (LLM in controlled pipeline)
  /validate outline-123, /summarize outline-123, /compare outline-a outline-b
  → WorkflowRouter → parameterized pipeline → OutputBlocks
  Uses LLM but with fixed steps, fixed tools, fixed output format

Tier 3: Agent Loop (LLM with full autonomy)
  "Analyze this outline and suggest improvements"
  → ChatClient + ToolCallAdvisor → multi-round tool calling → OutputBlocks
```

### Implementation: WorkflowRegistry

```java
@Component
public class WorkflowRegistry {

    private final Map<String, Workflow> workflows = new LinkedHashMap<>();

    @PostConstruct
    void register() {
        workflows.put("validate", new ValidateWorkflow());
        workflows.put("summarize", new SummarizeWorkflow());
        workflows.put("compare", new CompareWorkflow());
        workflows.put("export", new ExportWorkflow());
        workflows.put("review", new ReviewWorkflow());
    }

    public Optional<Workflow> resolve(String name) {
        return Optional.ofNullable(workflows.get(name));
    }
}

public interface Workflow {
    /** Fixed sequence of steps — uses LLM but in a controlled pipeline */
    List<OutputBlock> execute(WorkflowContext context);
}
```

### Example: SummarizeWorkflow

```java
public class SummarizeWorkflow implements Workflow {

    @Override
    public List<OutputBlock> execute(WorkflowContext ctx) {
        // Step 1: Read outline (deterministic — tool call)
        String content = documentTools.readDocument(ctx.outlineId());

        // Step 2: Generate summary (LLM — but single call, fixed prompt)
        String summary = summarizer.prompt()
            .system("Summarize this document. Structure: overview, key sections, " +
                    "statistics. Max 500 words. Use markdown.")
            .user(content)
            .call().content();

        // Step 3: Format output (deterministic)
        return List.of(
            new TextBlock("## Summary: " + ctx.outlineId()),
            new TextBlock(summary),
            new TableBlock(computeStats(content))  // word count, sections, etc.
        );
    }
}
```

### Why This Matters for Documents

Many document operations are **repeatable and predictable**:

| Operation | Type | Why Not Full Agent |
|-----------|------|-------------------|
| Validate outline | Workflow | Fixed steps: read → check rules → report. Agent would waste rounds figuring out what to do |
| Summarize document | Workflow | Single LLM call with fixed prompt. Agent would over-think it |
| Compare versions | Workflow | Diff → analyze → format. Deterministic pipeline |
| Export to PDF | Command | No LLM needed at all |
| "Review this and suggest improvements" | Agent | Open-ended — needs planning, multiple tool calls, iteration |

Workflows give **predictable, fast, cost-efficient** results for known tasks. The agent loop is reserved for tasks that genuinely need autonomy.

### Integration with CommandRouter

```java
@Component
public class CommandRouter {

    private final Map<String, Command> commands;         // Tier 1: deterministic
    private final WorkflowRegistry workflows;            // Tier 2: structured
    private final AgentService agentService;             // Tier 3: agent loop

    public Flux<OutputBlock> route(String input, String sessionId) {
        if (input.startsWith("/")) {
            String[] parts = input.substring(1).split("\\s+", 2);
            String name = parts[0];
            String args = parts.length > 1 ? parts[1] : "";

            // Try command first (deterministic)
            Command cmd = commands.get(name);
            if (cmd != null) return Flux.fromIterable(cmd.execute(args));

            // Try workflow (structured LLM pipeline)
            Optional<Workflow> wf = workflows.resolve(name);
            if (wf.isPresent()) return Flux.fromIterable(
                wf.get().execute(WorkflowContext.from(args, sessionId)));

            return Flux.just(new TextBlock("Unknown command: /" + name));
        }

        // Free text → full agent loop (Tier 3)
        return agentService.streamChat(sessionId, input);
    }
}
```

---

## 10. Structured Output Chunks (Learned from Teacher Assistant)

### The Problem

Kukuvaia already has `OutputBlock` types (TextBlock, TableBlock, CodeBlock, ProgressBlock). But the current design treats them as **rendering hints** — the agent produces text, and it gets wrapped in blocks.

Teacher Assistant uses **typed chunks in the streaming protocol**: each chunk carries semantic meaning (content, sources, suggestions, metadata, memory updates). The client doesn't just render — it **routes** each chunk to the right UI component.

### Solution: Enriched OutputBlock Types for Document Agent

Extend the existing OutputBlock hierarchy with document-agent-specific types:

```java
public sealed interface OutputBlock permits
    TextBlock, TableBlock, CodeBlock, ProgressBlock,
    // New: document agent blocks
    PlanBlock, StepProgressBlock, VerificationBlock,
    SourceBlock, MemoryBlock, IntentBlock, MetadataBlock {
}

/** Shows the agent's execution plan with step status */
public record PlanBlock(
    String task,
    List<PlanStep> steps  // each: {number, description, status: pending|active|done|failed}
) implements OutputBlock {}

/** Shows progress of current step within a plan */
public record StepProgressBlock(
    int stepNumber,
    String description,
    String status,
    String result
) implements OutputBlock {}

/** Shows verification results after document modification */
public record VerificationBlock(
    String documentPath,
    boolean passed,
    List<Issue> issues  // each: {severity, line, message}
) implements OutputBlock {}

/** Shows sources/documents referenced in the response */
public record SourceBlock(
    List<Source> sources  // each: {path, type, relevance, snippet}
) implements OutputBlock {}

/** Shows memory operations (saved/updated/deleted) */
public record MemoryBlock(
    String operation,  // "saved" | "updated" | "deleted"
    String category,
    String name,
    String description
) implements OutputBlock {}

/** Shows detected intent (debug/transparency) */
public record IntentBlock(
    String type,
    boolean needsTools,
    boolean needsPlanning,
    String complexity
) implements OutputBlock {}

/** Final metadata: token usage, cost, duration, tools called */
public record MetadataBlock(
    int inputTokens,
    int outputTokens,
    double cost,
    long durationMs,
    List<String> toolsCalled
) implements OutputBlock {}
```

### SSE Stream Example

Client receives a stream of typed chunks — each rendered by the appropriate UI component:

```
event: intent
data: {"type":"DOCUMENT_WRITE","needsTools":true,"needsPlanning":true,"complexity":"complex"}

event: plan
data: {"task":"Write summary report","steps":[{"number":1,"desc":"Read outline","status":"active"},..]}

event: step_progress
data: {"stepNumber":1,"description":"Read outline","status":"done","result":"Read 3 sections"}

event: content
data: {"text":"## Summary Report\n\nBased on the analysis..."}

event: verification
data: {"documentPath":"reports/summary.md","passed":true,"issues":[]}

event: memory
data: {"operation":"saved","category":"project","name":"outline_123_summary","description":"..."}

event: metadata
data: {"inputTokens":2450,"outputTokens":890,"cost":0.012,"durationMs":4200,"toolsCalled":["read_document","write_document","verify_document"]}
```

### CLI Rendering (Lipgloss)

Each block type gets its own Lipgloss renderer in kukuvaia-cli:

| Block | CLI Rendering |
|-------|-------------|
| `PlanBlock` | Numbered checklist with colored status icons |
| `StepProgressBlock` | Inline progress update (green checkmark / spinner) |
| `VerificationBlock` | Panel with pass/fail badge + issue list |
| `SourceBlock` | Compact table of referenced documents |
| `MemoryBlock` | Muted inline notification ("Memory saved: ...") |
| `IntentBlock` | Debug line (hidden by default, shown with `--verbose`) |
| `MetadataBlock` | Footer with token count + cost + duration |

### Why Structured Chunks Matter

Without typed chunks, the client gets a flat text stream and must **guess** what's happening. With typed chunks:

1. **Plan visibility** — user sees the plan and tracks progress in real-time
2. **Verification transparency** — user sees quality check results, not just "done"
3. **Cost awareness** — metadata block shows token usage per request
4. **Source attribution** — user knows which documents were read/used
5. **Memory feedback** — user sees what the agent remembered

This is the difference between a chatbot that outputs text and an agent that communicates its reasoning process.

---

## Updated Implementation Priority

| Phase | Capability | Effort | Impact | Dependencies |
|-------|-----------|--------|--------|-------------|
| **1** | Document File Tools | Medium | Critical | Workspace configuration |
| **1** | Persistent Memory (tools + auto-extraction) | Medium | High | PG schema migration |
| **1** | Structured Output Chunks | Medium | High | CLI rendering updates |
| **2** | Self-Verification | Low | High | File Tools (phase 1) |
| **2** | Planning Tools | Low | High | OutputBlock types (phase 1) |
| **2** | Deterministic + AI Hybrid (WorkflowRegistry) | Medium | High | File Tools (phase 1) |
| **3** | Context Management (hierarchical) | High | Medium | Summarization LLM budget |
| **3** | Command Execution (sandboxed) | Medium | Medium | Binary installation, security review |
| **3** | Intent-Driven Retrieval | Medium | Medium | Intent model cost, routing logic |

---

## 11. Lessons from Claude Code Internals

Analysis of Claude Code source code (1,884 TypeScript files) reveals architectural patterns that Kukuvaia should adopt. These are not the 6 capabilities already described — these are **cross-cutting concerns** that make the difference between a functional agent and a great one.

### 11.1 System Prompt: Static vs Dynamic Boundary

Claude Code splits its system prompt with an explicit `__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__` marker:

```
[STATIC — globally cacheable across all users/sessions]
  - Tool definitions and schemas
  - Behavioral rules ("never", "always", "must")
  - Tone and style guidance
  - Output efficiency rules
  - Security instructions
  
──── DYNAMIC BOUNDARY ────

[DYNAMIC — recomputed per user/session]
  - Environment details (cwd, git status, platform)
  - Memory content (MEMORY.md)
  - Active skills/plugins
  - MCP server instructions
  - Token budget guidance
```

**Why it matters:** LLM providers cache system prompts by prefix match. Everything before the boundary is cached across all API calls (massive cost savings). Everything after is session-specific.

**For Kukuvaia:**

```java
public class SystemPromptBuilder {
    
    // STATIC: cacheable across all sessions (same for every user)
    private static final String STATIC_PREFIX = """
        You are Kukuvaia, an intelligent document agent...
        
        ## Tools
        %s
        
        ## Behavioral Rules
        - Never claim "done" without verification
        - Read before writing, search before creating
        - Plan before complex tasks (3+ steps)
        ...
        
        ## Quality Gates
        ...
        
        __SYSTEM_PROMPT_DYNAMIC_BOUNDARY__
        """;
    
    // DYNAMIC: per-session, per-user
    public String build(String userId, String sessionId) {
        return STATIC_PREFIX.formatted(toolSchemas())
            + buildEnvironmentSection()
            + buildMemorySection(userId)
            + buildPersonaSection(sessionId)
            + buildWorkingSetSection(sessionId);
    }
}
```

### 11.2 Tool Result Persistence (Large Results → Disk)

Claude Code has `maxResultSizeChars` per tool. If a tool returns more than this limit, the result is **saved to disk** and only a file path is sent to the LLM.

```typescript
// Claude Code pattern
if (output.length > tool.maxResultSizeChars) {
    const path = saveResultToDisk(output);
    return `Result saved to: ${path}. Use Read tool to access specific parts.`;
}
```

**Why it matters:** Without this, a single `search_documents` call returning 200 matches blows the context window. With it, the agent gets a pointer and reads selectively.

**For Kukuvaia:**

```java
public interface ToolResultHandler {
    
    int MAX_RESULT_CHARS = 8000; // ~2000 tokens
    
    default String handleResult(String rawResult) {
        if (rawResult.length() <= MAX_RESULT_CHARS) {
            return rawResult;
        }
        // Save full result to workspace, return pointer
        String path = persistLargeResult(rawResult);
        return """
            Result too large (%d chars). Saved to: %s
            Use read_document to access specific parts.
            First 500 chars preview:
            %s
            """.formatted(rawResult.length(), path, rawResult.substring(0, 500));
    }
}
```

### 11.3 File State Cache (Read Deduplication)

Claude Code maintains an LRU cache for file reads. If the agent reads the same file twice in the same turn (common in verify loops), the second read hits cache instead of disk.

**For Kukuvaia:**

```java
@Component
public class FileStateCache {
    
    // Key: path + offset + limit → content
    private final Map<String, CachedRead> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedRead> eldest) {
            return size() > 20; // max 20 cached files
        }
    };
    
    public String readCached(Path path, Integer offset, Integer limit) {
        String key = "%s:%d:%d".formatted(path, offset, limit);
        CachedRead cached = cache.get(key);
        if (cached != null && cached.isValid(path)) {
            return cached.content(); // cache hit
        }
        String content = Files.readString(path);
        cache.put(key, new CachedRead(content, Files.getLastModifiedTime(path)));
        return content;
    }
    
    // Invalidate when agent writes to a file
    public void invalidate(Path path) {
        cache.entrySet().removeIf(e -> e.getKey().startsWith(path.toString()));
    }
}
```

### 11.4 Compaction Strategies (Beyond Simple Summarization)

Claude Code uses 4 compaction strategies, not just one. Kukuvaia's proposed `HierarchicalChatMemory` (section 3) should adopt a layered approach:

| Strategy | When | What It Does | Cost |
|----------|------|-------------|------|
| **Microcompact** | Every turn | Trim whitespace/redundant formatting from cached messages | Zero (string ops) |
| **Snip compact** | At 60% context | Replace middle messages with `[...N messages snipped...]` boundary | Zero (no LLM) |
| **Auto-compact** | At 80% context | LLM summarizes snipped messages into a compact summary | Low (Haiku call) |
| **Context collapse** | At 95% context | Aggressive projection — only keep last 5 messages + summary | Low |

**Key insight:** Snip compact (zero-cost) should happen BEFORE summarization (LLM-cost). Most sessions never hit auto-compact because snip keeps them under budget.

**For Kukuvaia:**

```java
public class LayeredCompaction {
    
    public List<Message> compact(List<Message> messages, int maxTokens) {
        int currentTokens = estimateTokens(messages);
        
        // Level 1: Microcompact (always, free)
        messages = microcompact(messages); // trim formatting
        
        if (currentTokens < maxTokens * 0.6) return messages;
        
        // Level 2: Snip (free, no LLM)
        messages = snipCompact(messages, maxTokens);
        
        if (estimateTokens(messages) < maxTokens * 0.8) return messages;
        
        // Level 3: Auto-compact (LLM summarization)
        messages = autoCompact(messages, maxTokens);
        
        return messages;
    }
    
    private List<Message> snipCompact(List<Message> messages, int maxTokens) {
        // Keep first 3 + last 10, replace middle with boundary marker
        int keepFirst = 3;
        int keepLast = 10;
        if (messages.size() <= keepFirst + keepLast) return messages;
        
        List<Message> result = new ArrayList<>();
        result.addAll(messages.subList(0, keepFirst));
        result.add(new SystemMessage("[...%d earlier messages snipped for context...]"
            .formatted(messages.size() - keepFirst - keepLast)));
        result.addAll(messages.subList(messages.size() - keepLast, messages.size()));
        return result;
    }
}
```

### 11.5 Tool Concurrency Safety

Claude Code marks each tool as `isConcurrencySafe`. Safe tools (Read, Grep, Glob) execute in parallel. Unsafe tools (Write, Edit, Bash) execute sequentially.

**For Kukuvaia:**

```java
public record ToolMetadata(
    String name,
    boolean readOnly,           // can run in parallel
    boolean destructive,        // requires confirmation
    int maxResultChars,         // persist to disk if exceeded
    long timeoutSeconds         // per-tool timeout
) {}

// In tool execution:
if (toolCalls.stream().allMatch(t -> getMetadata(t).readOnly())) {
    // Execute all in parallel (CompletableFuture.allOf)
    return executeParallel(toolCalls);
} else {
    // Execute sequentially
    return executeSequential(toolCalls);
}
```

### 11.6 Verification Discipline (System Prompt Rules)

Claude Code's system prompt contains specific anti-false-claim rules. These are critical for document agents:

```
## Verification Rules (non-negotiable)

1. Before reporting a task complete, verify it actually works:
   re-read the document, run verify_document, check the output.
   
2. Report outcomes faithfully: if verification finds issues, say so
   with the specific issues. If you did not run verification, say
   that rather than implying it succeeded.

3. Never claim "all checks pass" when output shows failures.
   Never suppress or simplify failing checks to manufacture a
   green result. Never characterize incomplete work as done.

4. Equally, when a check did pass, state it plainly — do not
   hedge confirmed results with unnecessary disclaimers.

5. Minimum complexity means no gold-plating, not skipping the
   finish line.
```

**For Kukuvaia:** Add this verbatim to the agent's system prompt. This single change will dramatically improve reliability.

### 11.7 Hook System (Pre/Post Tool Events)

Claude Code fires events at every stage of tool execution. Kukuvaia should adopt this for extensibility and audit:

```java
public interface ToolHook {
    
    /** Before tool execution — can modify input or block execution */
    record PreToolUse(String toolName, Map<String, Object> input,
                      String sessionId) implements ToolHook {}
    
    /** After successful tool execution */
    record PostToolUse(String toolName, Map<String, Object> input,
                       String result, long durationMs) implements ToolHook {}
    
    /** After failed tool execution */
    record PostToolUseFailure(String toolName, Map<String, Object> input,
                              String error) implements ToolHook {}
}

@Component
public class ToolHookDispatcher {
    
    private final List<ToolHookHandler> handlers;
    
    public Map<String, Object> firePreToolUse(String tool, Map<String, Object> input,
                                               String sessionId) {
        var event = new PreToolUse(tool, input, sessionId);
        for (ToolHookHandler handler : handlers) {
            var result = handler.handle(event);
            if (result.blocked()) {
                throw new ToolBlockedException(tool, result.reason());
            }
            if (result.modifiedInput() != null) {
                input = result.modifiedInput(); // allow input modification
            }
        }
        return input;
    }
}
```

**Use cases:**
- **Audit logging**: Log every tool call with input/output/duration
- **Rate limiting**: Prevent agent from calling same tool 50 times in a loop
- **Input sanitization**: Clean up paths, validate arguments before execution
- **Cost tracking**: Track which tools consume most tokens via result size
- **Memory triggers**: Auto-save memory when certain patterns detected in tool results

### 11.8 Token Budget Per Task

Claude Code tracks token budgets per agentic turn. The agent receives guidance about remaining budget and adjusts behavior:

```
You have used 45,000 of 100,000 output tokens for this task.
Prioritize completing the current step efficiently.
```

**For Kukuvaia:**

```java
@Component  
public class TokenBudgetAdvisor implements CallAdvisor {
    
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAdvisorChain chain) {
        TokenBudget budget = getBudget(request);
        
        if (budget.remainingPercent() < 20) {
            // Inject urgency into system prompt
            request = request.mutate()
                .systemText(request.systemText() + """
                    
                    TOKEN BUDGET WARNING: You have used %d%% of your token budget.
                    Wrap up current work. Summarize what's done and what remains.
                    Do not start new steps.
                    """.formatted(budget.usedPercent()))
                .build();
        }
        
        return chain.nextAroundCall(request);
    }
}
```

### 11.9 Permission Modes

Claude Code has 4 permission modes. For a document agent, this maps to:

| Mode | Claude Code | Kukuvaia Equivalent |
|------|------------|-------------------|
| `default` | Ask for each tool | Ask before write/edit operations |
| `auto` | ML classifier decides | Intent-based (section 8) — auto-approve reads, ask for writes |
| `plan` | Require /approve before execution | Plan mode — show plan, user approves, then execute |
| `bypass` | Auto-allow everything | Daemon mode — unattended execution with budget guard |

**For Kukuvaia:** Map permission modes to personas:

```yaml
# personas/editor.yaml — needs approval for writes
permission_mode: default
auto_approve_tools: [read_document, search_documents, find_documents, list_memories]
require_approval_tools: [write_document, edit_document, delete_memory]

# personas/reviewer.yaml — read-only, no approval needed
permission_mode: auto
tool_filter: [read_document, search_documents, find_documents, verify_document]

# personas/daemon-validator.yaml — unattended
permission_mode: bypass
budget_guard: { daily_tokens: 100000 }
```

---

## Final Architecture: Kukuvaia Agent Stack

```
┌──────────────────────────────────────────────────────────────────────┐
│                        System Prompt                                  │
│  [STATIC: tools, rules, verification mandate, behavioral guidance]    │
│  ──── DYNAMIC BOUNDARY ────                                           │
│  [DYNAMIC: memory, persona, environment, working set, token budget]   │
└──────────────────────────────────────────────────────────────────────┘
                              │
┌──────────────────────────────────────────────────────────────────────┐
│                      Advisor Chain                                    │
│                                                                       │
│  1. ToolHookDispatcher        (pre/post tool events)                  │
│  2. IntentDetectionAdvisor    (classify → route)                      │
│  3. PersistentMemoryAdvisor   (inject memories into prompt)           │
│  4. WorkingSetAdvisor         (inject open documents)                 │
│  5. TokenBudgetAdvisor        (budget warnings)                       │
│  6. ToolResultSanitizingAdvisor (anti-injection)                      │
│  7. HierarchicalChatMemory   (snip → auto-compact → summarize)       │
│  8. ToolCallAdvisor           (multi-round tool loop, max 20)         │
│  9. MemoryExtractionAdvisor   (post-response, async)                  │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
                              │
┌──────────────────────────────────────────────────────────────────────┐
│                        Tool Catalog                                   │
│                                                                       │
│  Document:  read_document, write_document, edit_document,             │
│             search_documents, find_documents                          │
│  Memory:    save_memory, search_memories, list_memories, delete_memory│
│  Planning:  create_plan, complete_step, revise_plan                   │
│  Verify:    verify_document, compare_documents,                       │
│             verify_against_requirements                               │
│  Data:      get_classifications, search_items, get_outline_raw, ...   │
│  Command:   run_command (allowlisted)                                 │
│                                                                       │
│  Each tool has: readOnly, destructive, maxResultChars, timeout        │
│  Parallel execution for readOnly tools                                │
│  Large results → persist to disk, return pointer                      │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
                              │
┌──────────────────────────────────────────────────────────────────────┐
│                     Execution Routing                                 │
│                                                                       │
│  Tier 1: /command → CommandRouter → deterministic (no LLM)            │
│  Tier 2: /workflow → WorkflowRegistry → fixed pipeline (1 LLM call)  │
│  Tier 3: free text → ChatClient + ToolCallAdvisor (full agent loop)   │
│                                                                       │
│  SubAgentFactory: isolated specialists with depth=1, tool filtering   │
│  DaemonAgentService: background tasks with budget + schedule guards   │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
                              │
┌──────────────────────────────────────────────────────────────────────┐
│                     Output Protocol                                   │
│                                                                       │
│  SSE stream of typed OutputBlocks:                                    │
│  IntentBlock → PlanBlock → StepProgressBlock → TextBlock →            │
│  VerificationBlock → MemoryBlock → SourceBlock → MetadataBlock        │
│                                                                       │
│  CLI renders each block type with dedicated Lipgloss component        │
│  Web renders each block type with dedicated CSS component             │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

## Final Implementation Priority (Complete)

| Phase | Capability | Source | Effort | Impact |
|-------|-----------|--------|--------|--------|
| **1** | Document File Tools | Gap analysis | Medium | Critical |
| **1** | Persistent Memory (tools + auto-extraction) | Gap + TA | Medium | Critical |
| **1** | System Prompt: static/dynamic split + verification rules | CC | Low | High |
| **1** | Tool result persistence (large results → disk) | CC | Low | High |
| **2** | Planning Tools (plan-as-tool) | Gap + CC | Low | High |
| **2** | Self-Verification tools + prompt mandate | Gap + CC | Low | High |
| **2** | Structured Output Chunks | TA + CC | Medium | High |
| **2** | Tool hooks (pre/post events) | CC | Medium | Medium |
| **2** | File state cache (read dedup) | CC | Low | Medium |
| **3** | Layered Compaction (snip → auto → collapse) | CC | High | High |
| **3** | Deterministic + AI Hybrid (WorkflowRegistry) | TA | Medium | Medium |
| **3** | Intent-Driven Retrieval | TA | Medium | Medium |
| **3** | Tool concurrency (parallel reads) | CC | Medium | Medium |
| **3** | Token budget per task | CC | Low | Medium |
| **3** | Permission modes per persona | CC | Medium | Medium |
| **3** | Command Execution (sandboxed) | Gap | Medium | Low |

**Legend:** Gap = original gap analysis, TA = Teacher Assistant, CC = Claude Code
