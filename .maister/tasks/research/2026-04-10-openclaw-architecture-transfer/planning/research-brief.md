# Research Brief: OpenClaw Architecture Transfer to Kukuvaia

## Research Question

Which architectural elements from OpenClaw are transferable to kukuvaia and how to adapt them to the Java/Spring AI stack?

## Research Type

**Mixed** — combines technical codebase analysis (OpenClaw patterns), requirements gathering (what kukuvaia needs), and literature/best practices (how to implement in Java/Spring ecosystem).

## Background

OpenClaw is a mature, open-source AI assistant platform (TypeScript/Node.js) with ~60+ skills, multi-channel messaging, plugin SDK, security model, ACP bridge for IDE integration, and multi-agent support. It has evolved through multiple names and has a large contributor base.

Kukuvaia is a newer, extensible conversational AI agent platform (Java/Spring Boot + Spring AI + Kotlin/Embabel for engine, Go/Charm for CLI) with agent orchestration, MCP tools, sessions, memory (pgvector), personas, and a planned web frontend. It is going open source (Apache 2.0).

The goal is NOT code porting — it is identifying architectural patterns, abstractions, and design decisions from OpenClaw that would strengthen kukuvaia's architecture, adapted to the Java/Spring AI ecosystem.

## Scope

### Included
- OpenClaw plugin architecture and SDK boundaries
- OpenClaw security model and trust boundaries (SECURITY.md, operator model)
- OpenClaw channel abstraction and messaging patterns
- OpenClaw skills and extensibility system (ClawHub marketplace)
- OpenClaw ACP/IDE integration (Agent Client Protocol bridge)
- OpenClaw multi-agent safety patterns (concurrent agent work rules)
- OpenClaw doctor/diagnostic command pattern
- OpenClaw build/test gate terminology and verification levels
- OpenClaw prompt cache stability patterns
- Kukuvaia current architecture (Spring AI ChatClient, MCP, personas, advisors)
- Kukuvaia extension points and planned extensibility (.kukuvaia/ directory)

### Excluded
- OpenClaw TypeScript-specific patterns (ESM, Bun, dynamic imports)
- OpenClaw platform-specific code (macOS, iOS, Android apps)
- OpenClaw CI/CD and release automation details
- Direct code porting (concept transfer only)
- OpenClaw Lit/Control UI implementation details

### Constraints
- Target stack: Java 21+ / Spring Boot 3.x / Spring AI 1.x / Kotlin 2.x (Embabel)
- CLI: Go 1.22+ / Charm stack (separate repo, thin client)
- Must maintain kukuvaia's existing architecture decisions (advisor chains, MCP tools, etc.)
- Open source direction (Apache 2.0 license)
- Single-user personal assistant model (same as OpenClaw)

## Success Criteria

1. Comprehensive inventory of transferable OpenClaw patterns with adaptation strategies
2. Prioritized list with effort/value assessment for each element
3. Clear mapping from OpenClaw TypeScript patterns to Java/Spring equivalents
4. Identification of patterns that DON'T transfer well (and why)
5. Actionable next steps for implementation

## Key Questions to Answer

1. Which OpenClaw architectural boundaries have direct Spring equivalents?
2. What plugin/extension model best fits Spring's DI and SPI mechanisms?
3. How should the trust/security model be adapted for kukuvaia's deployment model?
4. Which channel abstraction pattern works for future multi-channel support?
5. What developer experience patterns (doctor, gates, multi-agent safety) are stack-agnostic?
