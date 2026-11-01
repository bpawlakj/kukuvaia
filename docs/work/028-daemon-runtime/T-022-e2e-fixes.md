---
id: T-022
title: "Follow-up: defects found by end-to-end verification"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-008, T-016]
blocks: []
plan_anchor: verification
---

# T-022 — Follow-up: defects found by end-to-end verification

Follow-up to the implemented initiative. Booting the real application (isolated Postgres, stub
OpenAI-compatible LLM, local `sl-aipdlc` MCP server) per the plan's Verification section surfaced
defects the unit/integration suites did not.

## Scope

- Whatever the E2E run exposes; each defect gets a regression test.

## Approach

1. `DaemonJobRegistry` had two public constructors and none was `@Autowired` → the application failed
   to start ("No default constructor found"). Unit tests always called a constructor directly.
   Fix: `@Autowired` on the Spring constructor; regression test builds the bean in a real context.
2. Job definitions are validated while the context starts, but Embabel deploys its agents later
   (after singletons are created) → every `kind: embabel` job was rejected at boot ("no Embabel agent
   named 'PingAgent' (available: [])"). Fix: until the platform has deployed agents, validation reads the
   `@Agent` beans with Embabel's own `AgentMetadataReader` (same names and tool groups); runs still use
   the deployed platform. Regression test: validation against an empty platform.
3. `DaemonController` signals 400/403/404/409 with `ResponseStatusException`, but the global
   `ErrorSanitizer` advice's catch-all turned every one into **500 internal_error** (e.g. manual run of an
   unknown job; the dev-mode refusal). Controller tests used `standaloneSetup` without the advice, so
   they never saw it. Fix: a `ResponseStatusException` handler that keeps the status and sanitizes the
   reason; `DaemonControllerTest` now runs with the production advice (3 tests went red before the fix).
4. Specialist audit rows stored `model = null` (only a `daemon-default` provider label), so the audit
   trail did not show which model answered. Fix: `SubAgentResult` carries the response's model id and
   `SpecialistExecutor` records it.

## Acceptance

- The application boots with the daemon runtime enabled; every defect found has a regression test.
- Full suite green.

## Notes

### E2E verification (2026-11-01)

Real application jar, isolated Postgres (pgvector 17, Flyway V1→V25 from empty), stub OpenAI-compatible
LLM (fixed usage 120/30, `SLOW` = 30 s), signed-notification receiver, and the real `sl-aipdlc` MCP server
(read-only, token-gated, 127.0.0.1). Dev mode off (API key auth), webhook HMAC secret set.

| Assumption | Result |
|---|---|
| Admin API: no auth → 401; literal secret header → 400; link-local URL → 400; `env:` header → 201, literal values masked | pass |
| `/api/daemon/jobs` lists built-ins + project jobs; `/api/daemon` needs auth | pass |
| Unsigned webhook → 401; `input.match` miss → 200 ignored; signed failed-CI → 202 + task id | pass |
| CI run COMPLETED, 120 + 30 tokens, model recorded, provider not Copilot; budget `usedToday` +150 | pass (after fix 4) |
| Prompt: declared fields only, injection redacted, unknown field absent, `<event_data>` fence, safety boundary sent, `max_tokens` = min(spec, job) | pass |
| `overlap: skip` → concurrent second run SKIPPED (409) | pass |
| Slow model call → TIMEOUT at 3.1 s, charged `token_budget` | pass |
| `kind: embabel` PingAgent → COMPLETED, 0 tokens; cron `*/20 s` ran repeatedly | pass (after fix 2) |
| PG NOTIFY: 51 events in separate transactions → one run, `batch_size` 50, non-matching event excluded | pass |
| Webhook sink: signatures over `<ts>.<body>` verify; payload = first-line summary only | pass |
| `kind: mcp` `toolkit/check_access` over Streamable HTTP with `env:` Authorization → COMPLETED, 0 tokens | pass |
| Non-allowlisted MCP tool → job rejected at load; manual run → 404 | pass (after fix 3) |
| `kill -9` mid-run → restart marks the run CANCELLED (conservative charge) | pass |
| Copilot-named provider → daemon run FAILED before any model call, 0 tokens | pass |
| `daily-token-budget: 1` → SKIPPED at admission, no model call; token-free `mcp` job still runs | pass |

Observations (not fixed): restart recovery charges the default `token_budget` (20000), not the job's own
(conservative by design); `/api/daemon/tasks/{id}` returns `output` as a JSON string, not an object.
