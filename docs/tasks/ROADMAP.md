# Roadmap: kukuvaia Platform Evolution

**Created**: 2026-04-13
**Status**: Active
**Source Plan**: `docs/plan/model-routing-and-embabel.md`
**Source Analyses**: `docs/analyzes/`

This file is the **master control document** for all implementation tasks. It defines execution order, dependencies, and tracks progress across all phases.

## Execution Order

```
TIER 1 — Foundation (no dependencies)
│
├── T01: Provider Registry + Model CRUD          ← START HERE
├── T02: ChatModel Factory + Cache               ← after T01
│
TIER 2 — Capabilities (depends on T01+T02)
│
├── T03: Model Discovery                         ← after T02
├── T04: Embabel Activation + ModelProvider       ← after T02
├── T05: Interactive Chat Model Routing           ← after T02
├── T06: Data Masking Advisor                     ← after T05
├── T07: SubAgentFactory Tier Integration         ← after T02
│
TIER 3 — Agents (depends on T04)
│
├── T08: Embabel Multi-Model Agent PoC            ← after T04
├── T09: Task Decomposition + @ActionComplexity   ← after T08
├── T10: Daemon File/Git Tools                    ← after T07
│
TIER 4 — Intelligence (depends on Tier 2+3)
│
├── T11: Harness Engineering (Rules System)       ← after T02 (can start early)
├── T12: Dreaming Agent                           ← after T03, T04, T05, T10
├── T13: Inter-Agent Communication (MCP Bridge)   ← after T10, T11
│
TIER 5 — Optimization (depends on Tier 4)
│
├── T14: Dreaming Model Scout + Self-Optimization ← after T12, T09
└── T15: Admin UI API Completion                  ← after all
```

## Dependency Graph

```
T01 ──→ T02 ──┬──→ T03 ──────────────────────────────┐
              ├──→ T04 ──→ T08 ──→ T09 ──────────────┤
              ├──→ T05 ──→ T06                        ├──→ T12 ──→ T14
              ├──→ T07 ──→ T10 ──────────────────────┤
              └──→ T11 ──────────────────→ T13 ──────┘
                                                       └──→ T15
```

## Task Registry

### TIER 1 — Foundation

| ID | Task | Status | File | Est. Files | Depends On |
|----|------|--------|------|-----------|------------|
| T01 | Provider Registry + Model CRUD | `done` | [T01](T01-provider-registry.md) | 16 new | — |
| T02 | ChatModel Factory + Cache | `done` | [T02](T02-chatmodel-factory.md) | 7 new, 1 mod | T01 |

### TIER 2 — Capabilities

| ID | Task | Status | File | Est. Files | Depends On |
|----|------|--------|------|-----------|------------|
| T03 | Model Discovery | `done` | [T03](T03-model-discovery.md) | 3 new, 2 mod | T02 |
| T04 | Embabel Activation + ModelProvider | `done` | [T04](T04-embabel-activation.md) | 1 new, 3 mod | T02 |
| T05 | Interactive Chat Model Routing | `done` | [T05](T05-model-routing-advisor.md) | 2 new, 3 mod | T02 |
| T06 | Data Masking Advisor | `done` | [T06](T06-data-masking.md) | 3 new, 1 mod | T05 |
| T07 | SubAgentFactory Tier Integration | `done` | [T07](T07-subagent-tiers.md) | 3 mod | T02 |

### TIER 3 — Agents

| ID | Task | Status | File | Est. Files | Depends On |
|----|------|--------|------|-----------|------------|
| T08 | Embabel Multi-Model Agent PoC | `done` | [T08](T08-embabel-agent-poc.md) | 3 new | T04 |
| T09 | Task Decomposition + @ActionComplexity | `done` | [T09](T09-task-decomposition.md) | 5 new, 1 migration, 1 mod | T08 |
| T10 | Daemon File/Git Tools + Sandbox | `done` | [T10](T10-daemon-file-tools.md) | 4 new | T07 |

### TIER 4 — Intelligence

| ID | Task | Status | File | Est. Files | Depends On |
|----|------|--------|------|-----------|------------|
| T11 | Harness Engineering (Rules System) | `done` | [T11](T11-harness-engineering.md) | 9 new, 1 migration, 1 mod | T02 |
| T12 | Dreaming Agent | `done` | [T12](T12-dreaming-agent.md) | 6 new, 1 migration | T03, T04, T05, T10 |
| T13 | Inter-Agent Communication (MCP Bridge) | `pending` | [T13](T13-inter-agent-mcp.md) | ~6 new, 1 migration | T10, T11 |
| T16 | Context Compaction Phases B/C/D/E (continue P24, A shipped) | `pending` | [T16](T16-context-compaction-phases-bcde.md) | ~6 new, ~3 mod | P24-A shipped |

### TIER 5 — Optimization

| ID | Task | Status | File | Est. Files | Depends On |
|----|------|--------|------|-----------|------------|
| T14 | Dreaming Self-Optimization | `pending` | [T14](T14-dreaming-optimization.md) | ~3 new | T12, T09 |
| T15 | Admin UI API Completion | `done` | [T15](T15-admin-api.md) | 1 new | all |

## Progress Tracking

```
TIER 1: [OK] T01  [OK] T02                               2/2  ✓
TIER 2: [OK] T03  [OK] T04  [OK] T05  [OK] T06  [OK] T07   5/5  ✓
TIER 3: [OK] T08  [OK] T09  [OK] T10                      3/3  ✓
TIER 4: [OK] T11  [OK] T12  [__] T13  [__] T16            2/4
TIER 5: [__] T14  [OK] T15                               1/2
─────────────────────────────────────────────────────
Total:                                                    14/16
```

## Source Documents

| Document | Location | Covers Tasks |
|----------|----------|-------------|
| Implementation Plan | `docs/plan/model-routing-and-embabel.md` | T01-T08 |
| Dreaming Analysis | `docs/analyzes/dreaming-agent-analysis.md` | T12, T14 |
| Inter-Agent Analysis | `docs/analyzes/inter-agent-communication-analysis.md` | T10, T13 |
| Harness Analysis | `docs/analyzes/harness-engineering-analysis.md` | T11 |
| Data Masking Analysis | `docs/analyzes/data-masking-analysis.md` | T06 |
| Task Decomposition Analysis | `docs/analyzes/task-decomposition-model-assignment-analysis.md` | T09 |
| Embabel Architecture | `docs/architecture/embabel-integration.md` | T04, T08 |
| Phase 4E (pending) | `.maister/tasks/development/2026-04-10-phase4e-embabel-memory/` | T08 |

## Rules

1. **Update this file** when starting or completing any task
2. **Never skip dependencies** — check "Depends On" before starting a task
3. **Each task file** has its own acceptance criteria and verification steps
4. **Mark progress** in the Progress Tracking section using `[OK]` / `[__]`
5. **Parallel work**: tasks within the same tier can run in parallel if dependencies allow
