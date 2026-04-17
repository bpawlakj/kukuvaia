# T10: Daemon File/Git Tools + Sandbox

**Status**: `pending`
**Tier**: 3 — Agents
**Depends On**: T07
**Blocks**: T12, T13
**Source**: `docs/analyzes/inter-agent-communication-analysis.md`

## Goal

Enable kukuvaia daemon to perform coding tasks by adding file system and git @Tool methods with security sandbox. This makes the internal daemon a capable coding agent without needing external agents.

## Scope

### FileTools.java (@Tool methods)
- `readFile(path)` → content
- `writeFile(path, content)` → confirmation
- `editFile(path, oldText, newText)` → confirmation
- `listFiles(pattern)` → matching paths
- `searchContent(pattern, path)` → matching lines

### GitTools.java (@Tool methods)
- `gitStatus()` → status output
- `gitDiff()` → diff output
- `gitDiff(path)` → diff for specific file
- `gitCommit(message)` → commit confirmation
- `gitLog(count)` → recent commits

### BashTool.java (@Tool method)
- `bashRun(command)` → output (sandboxed)
- Allowlisted commands only

### Security Guards

**WorkspaceSandbox**: File operations restricted to configured workspace directories only. No access outside workspace.

**BashAllowlist**: Only whitelisted commands: `git`, `gradle`, `./gradlew`, `npm`, `mvn`, `ls`, `cat`, `grep`, `find`, `wc`. Block: `rm`, `curl`, `wget`, `ssh`, `sudo`, `chmod`, etc.

**PathTraversalGuard**: Block `../`, symlink escapes, absolute paths outside workspace.

**GitGuard**: No `push`, no `push --force`, no `reset --hard`, no `checkout .`. Only: `status`, `diff`, `log`, `add`, `commit`.

## File Inventory

### Create
- `kukuvaia-core/src/main/java/ai/kukuvaia/tools/FileTools.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/tools/GitTools.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/tools/BashTool.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/security/WorkspaceSandbox.java`

## Acceptance Criteria

- [ ] `readFile` returns content for file within workspace
- [ ] `readFile` throws for file outside workspace (PathTraversal blocked)
- [ ] `writeFile` creates/overwrites file within workspace
- [ ] `bashRun("git status")` works, `bashRun("rm -rf /")` blocked
- [ ] `gitCommit` works, `gitPush` not available
- [ ] All tools use `ProcessBuilder` (no shell expansion)
- [ ] Unit tests for sandbox boundary enforcement
- [ ] Existing tools (MemoryTools, PlanningTools) unaffected
