---
id: T-003
title: "Daemon provider policy: DAEMON → daemon role, refuse Copilot unless explicit"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: bca203e
depends_on: []
blocks: [T-005, T-012]
plan_anchor: d1--fail-closed-controls-no-new-features
---

# T-003 — Daemon provider policy: DAEMON → daemon role, refuse Copilot unless explicit

## Scope

- `kukuvaia-engine/kukuvaia-provider/src/main/java/ai/kukuvaia/provider/service/LlmProviderService.java` (`resolve`, `resolveByRole`)
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java` (tier path passes context)
- `kukuvaia-engine/kukuvaia-app/src/main/resources/application.yaml` (`kukuvaia.daemon.default-provider`, commented `roles.daemon` example)
- `docs/architecture/security-review.md` (#14 status text)

## Approach

- With `ExecutionContext.DAEMON` and no explicit provider: resolve role `kukuvaia.daemon.default-provider` (default `daemon`); if that role is not configured fall back to `supervisor`.
- After resolution under DAEMON: if the provider is Copilot (name/type `copilot` or host ending `githubcopilot.com`) and it was not explicitly requested, throw a clear exception (fail closed).
- The specialist `tier:` path must go through the same check.
- Update security-review #14 to describe what is actually implemented.

## Acceptance

- DAEMON + no override + `daemon` role configured → that role's model.
- DAEMON + no `daemon` role → supervisor model; if supervisor is Copilot → exception.
- DAEMON + explicit `provider: copilot` → allowed (opt-in).
- DAEMON + tier resolving to Copilot → exception.
- INTERACTIVE behaviour unchanged (existing `LlmProviderServiceTest` green).
