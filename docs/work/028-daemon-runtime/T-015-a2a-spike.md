---
id: T-015
title: "A2A spike → go/no-go for A2aExecutor"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-005]
blocks: []
plan_anchor: d5--external-agents-mcp-now-a2a-spike
---

# T-015 — A2A spike → go/no-go for A2aExecutor

## Scope

- spike notes in this task's `## Notes`; if go: new `A2aExecutor` behind the SPI

## Approach

- Half day with `io.github.a2asdk:a2a-java-sdk-client` (1.0.0.Alpha3) against a sample A2A agent: send task, stream/poll result, auth.
- If green: implement `A2aExecutor`; otherwise make the loader reject `kind: a2a` with a clear message.

## Acceptance

- Written go/no-go decision with evidence; loader behaviour matches the decision.

## Notes

### Spike result (2026-11-01): NO-GO — `kind: a2a` stays rejected

Evidence (Maven Central + isolated dependency resolution, outside the repo):

- **Maturity**: `io.github.a2asdk:a2a-java-sdk-client` — the 1.0 line is `1.0.0.Alpha1..Alpha3`; the
  artifact metadata was last updated 2026-02-19, i.e. no newer release in ~9 months. The only stable
  line is `0.3.x.Final`, which predates the 1.0 protocol changes we would build against.
- **Footprint**: the client resolves to 36 runtime coordinates, including `protobuf-java` 4.33,
  `protobuf-java-util`, `proto-google-common-protos`, `a2a-java-sdk-spec-grpc` (pulled in even for the
  JSON-RPC transport), `gson`, Guava and the Jakarta CDI / EL / interceptor APIs. The engine has no
  protobuf / gRPC / CDI today (it already has Guava 33 and gson 2.13); the parent POM is
  Quarkus-oriented (`quarkus-bom`, mutiny-zero).
- **Need**: no A2A agent exists in the target ecosystem (Astral and the sl-aipdlc toolkit expose
  MCP). Remote agents are reachable now through `kind: mcp` (T-014), under the same guards.

Decision: do not implement `A2aExecutor` now. The loader rejects `kind: a2a` with an explicit message
("A2A executor deferred after the T-015 spike — Java SDK 1.0 is still alpha; reach remote agents
through kind: mcp"), covered by `DaemonJobRegistryTest`. Revisit when the SDK ships 1.0 GA with a
transport that does not require the gRPC spec modules, or when a concrete A2A agent needs to be called.
The `DaemonExecutor` SPI needs no change to add it later.
