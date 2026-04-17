# Findings: OpenClaw Security & Trust Model

## Summary

OpenClaw implements a comprehensive, layered security model built around a "personal assistant" trust paradigm -- one trusted operator per gateway instance, with security controls designed as operator guardrails rather than multi-tenant isolation. The model defines five explicit trust boundaries (channel access, session isolation, tool execution, external content, supply chain), enforces them through allowlists/approvals/sandboxing/content wrapping, and provides a `security audit` command for continuous verification.

## Key Findings

### Finding 1: One-User Personal Assistant Trust Model

**Source**: `SECURITY.md:96-118`, `docs/gateway/security/index.md:19-27`
**Evidence**: "OpenClaw does not model one gateway as a multi-tenant, adversarial user boundary. Authenticated Gateway callers are treated as trusted operators. Recommended mode: one user per machine/host, one gateway for that user. Session identifiers are routing controls, not per-user authorization boundaries."
**Relevance**: Kukuvaia's model (one Spring Boot server per user) already aligns. Key: explicitly document this trust model.

### Finding 2: Five-Layer Trust Boundary Architecture

**Source**: `docs/security/THREAT-MODEL-ATLAS.md:68-131`
**Evidence**: MITRE ATLAS threat model: TB1 (Channel Access: pairing, allowlists, auth), TB2 (Session Isolation: session keys, per-agent policies), TB3 (Tool Execution: Docker/SSH sandbox, approvals), TB4 (External Content: XML markers, security notices), TB5 (Supply Chain: moderation, scanning).
**Relevance**: Kukuvaia covers TB1 (ApiAuthFilter), TB2 (JdbcChatMemoryRepository), TB4 partial (ToolResultSanitizingAdvisor). TB3 and TB5 are gaps.

### Finding 3: Sandbox Execution System (Three Backends)

**Source**: `docs/gateway/sandboxing.md:1-473`
**Evidence**: `sandbox.mode: off|non-main|all`, `sandbox.backend: docker|ssh|openshell`. Docker: network none by default, blocked paths (docker.sock, /etc, /proc, ~/.aws, ~/.ssh), symlink resolution, non-root user.
**Relevance**: If kukuvaia adds shell/file tools, sandbox becomes critical. Docker backend maps to ProcessBuilder + Docker API.

### Finding 4: Exec Approval System (Allowlist + Ask + Security Levels)

**Source**: `src/infra/exec-approvals.ts:22-58`, `docs/cli/approvals.md:1-186`
**Evidence**: `ExecSecurity: deny|allowlist|full`, `ExecAsk: off|on-miss|always`. Approval binding includes exact argv, cwd, env hash, file operand SHA-256.
**Relevance**: Maps to Spring AI Advisor intercepting tool calls. `ToolApprovalAdvisor` with three-level security.

### Finding 5: Allowlist System (Sender Authorization)

**Source**: `src/plugin-sdk/allow-from.ts:1-164`
**Evidence**: DM access policies: `pairing` (default, 1-hour codes), `allowlist` (strict), `open` (requires explicit "*"), `disabled`.
**Relevance**: Relevant for multi-channel scenarios. Maps to Spring Security filter chain.

### Finding 6: External Content Wrapping (Injection Prevention) -- HIGH PRIORITY

**Source**: `src/security/external-content.ts:1-365`
**Evidence**: Random marker IDs via `crypto.randomBytes(8)`, Unicode homoglyph folding (30+ bracket lookalikes), invisible character stripping, suspicious pattern detection, source labeling.
**Relevance**: **HIGH-PRIORITY TRANSFER**. Kukuvaia has ToolResultSanitizingAdvisor but lacks content wrapping for external input. An `ExternalContentWrappingAdvisor` should implement boundary markers with UUID-based IDs + homoglyph folding.

### Finding 7: Security Audit Command

**Source**: `docs/cli/security.md`, `src/security/audit.ts:1-100`
**Evidence**: 70+ checkId values across: filesystem permissions, gateway exposure, tool blast radius, sandbox config, channel security, plugin trust, model hygiene. Severity levels: info/warn/critical. --fix option for auto-remediation.
**Relevance**: Maps to Spring Boot Actuator `HealthIndicator`. Each check becomes a health indicator or custom endpoint.

### Finding 8: Tool Policy System (Allow/Deny + Groups) -- HIGH PRIORITY

**Source**: `docs/gateway/sandbox-vs-tool-policy-vs-elevated.md:56-103`
**Evidence**: `tools: { profile: "messaging", deny: ["gateway", "cron"], allow: ["group:fs"] }`. Groups: runtime, fs, sessions, web, ui, automation, openclaw. Rule: deny always wins.
**Relevance**: **HIGH-PRIORITY TRANSFER**. Kukuvaia's persona-based tool filtering can use group-based policy with deny-always-wins. Implement as `ToolFilteringAdvisor` with `@ConfigurationProperties`.

### Finding 9: Context Visibility Controls

**Source**: `src/security/context-visibility.ts:1-59`
**Evidence**: `ContextVisibilityMode: all|allowlist|allowlist_quote`. Filters supplemental context based on sender allowlist.
**Relevance**: Relevant for multi-channel/multi-user scenarios. Maps to `ContextVisibilityAdvisor`.

### Finding 10: Dangerous Config Flag Naming Convention

**Source**: `docs/gateway/security/index.md:369-412`
**Evidence**: All security-weakening flags use `dangerous*`/`dangerously*` prefixes. Security audit warns when enabled.
**Relevance**: Easy to adopt, high value. Any kukuvaia config weakening security should follow this naming.

### Finding 11: SSRF Protection

**Source**: `src/plugin-sdk/ssrf-policy.ts:1-80`
**Evidence**: DNS pinning + private IP blocking. `dangerouslyAllowPrivateNetwork` as explicit opt-in.
**Relevance**: Relevant when kukuvaia adds web fetch tools. Custom `DnsResolver` for RestTemplate/WebClient.

### Finding 12: Out-of-Scope Definitions

**Source**: `SECURITY.md:129-149`
**Evidence**: Extensive exclusions: prompt-injection-only, multi-tenant assumptions, trusted-state write access, symlink pre-requisites, dangerous flag usage, heuristic parity drift.
**Relevance**: Template for kukuvaia's own SECURITY.md.

### Finding 13: Formal Verification (TLA+/TLC)

**Source**: `docs/security/formal-verification.md:1-100`
**Evidence**: Machine-checked models for gateway exposure, exec pipeline, pairing, ingress gating.
**Relevance**: Aspirational. Define security invariants as integration tests first.

## Priority Classification

### Directly Transferable (High Priority)
1. External Content Wrapping with boundary markers + homoglyph folding
2. Tool Policy Groups with deny-always-wins
3. Dangerous Config Flag Naming (`dangerously*` prefix)
4. Trust Model Documentation (SECURITY.md template)
5. Out-of-Scope Definitions

### Partially Transferable (Medium Priority)
6. Security Audit as Spring Boot Actuator HealthIndicators
7. Exec Approval System (when shell tools added)
8. Five Trust Boundary Architecture (organizing framework)
9. SSRF Protection (when web fetch tools added)

### Conceptually Transferable (Lower Priority)
10. Docker Sandbox for future document agent tools
11. Formal Security Claims as integration tests
12. Context Visibility for multi-channel scenarios
13. Allowlist + Pairing for messaging channel support

## Source Citations

| File | Notes |
|------|-------|
| `SECURITY.md` | Full security policy, trust model, out-of-scope |
| `docs/gateway/security/index.md` | Security guide, audit, hardening |
| `docs/gateway/sandboxing.md` | Sandbox architecture |
| `docs/gateway/sandbox-vs-tool-policy-vs-elevated.md` | Three security controls |
| `docs/security/THREAT-MODEL-ATLAS.md` | MITRE ATLAS threat model |
| `docs/security/formal-verification.md` | TLA+/TLC models |
| `src/security/external-content.ts` | Content wrapping implementation |
| `src/security/context-visibility.ts` | Context filtering |
| `src/security/dangerous-tools.ts` | HTTP tool deny list |
| `src/security/audit.ts` | Audit types |
| `src/security/safe-regex.ts` | ReDoS prevention |
| `src/plugin-sdk/allow-from.ts` | Allowlist implementation |
| `src/plugin-sdk/ssrf-policy.ts` | SSRF policy |
| `src/infra/exec-approvals.ts` | Exec approval types |
| `Dockerfile.sandbox` | Sandbox container image |
