# Project History

## Origin — ETSL content pipeline

Kukuvaia started as an agent prototype in `api/chat.py` inside the ETSL content pipeline (`sl-content-engine`). In that context the agent was a thin wrapper around a few hardcoded tools, tightly coupled to the content domain (outlines, classifications, validations).

## Productisation goals

The prototype was useful enough to motivate a productised, standalone platform. The target state has:

- A **separate repository** with its own release cadence.
- **Multiple personas** with distinct system prompts and tool allow-lists.
- **Deterministic slash commands** (`/plan`, `/validate`, `/check`) that do not route through the LLM for safety-critical operations.
- **Structured output blocks** (`TextBlock`, `TableBlock`, `CodeBlock`, `ProgressBlock`, `PanelBlock`, `SpinnerBlock`, `SectionBlock`) so clients render consistently.
- **Multiple clients** — a Go TUI (`kukuvaia-cli`) and a web admin UI (`kukuvaia-admin`) sharing design tokens via `kukuvaia-theme.yaml`.
- **Domain-agnostic core** — the agent is expected to be used beyond ETSL (k8s operations, AWS management, git/Jira workflows, custom YAML/Lua tools).

## Why the pivot mattered

Hardcoding each tool in the original Python prototype did not scale. Adopting the Model Context Protocol (MCP) gives the engine ecosystem compatibility (works with any MCP-speaking client or server) and a clean extensibility contract for user-defined tools.

## Scope guidance

When discussing features, priorities, or scope decisions: remember that the ETSL content domain (outlines, classifications, validations) is the first use case but not the only one. Architecture should remain domain-agnostic; ETSL-specific behaviour lives in the external ETSL MCP server (see `etsl-integration.md`), not in the engine.
