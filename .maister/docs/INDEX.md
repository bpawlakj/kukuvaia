# Documentation Index

**IMPORTANT**: Read this file at the beginning of any development task to understand available documentation and standards.

## Quick Reference

### Project Documentation
Project-level documentation covering vision, goals, architecture, and technology choices.

### Technical Standards
Coding standards, conventions, and best practices organized by domain.

---

## Project Documentation

Located in `.maister/docs/project/`

### Vision (`project/vision.md`)
Project purpose, current state, goals, and evolution history. Kukuvaia is an extensible conversational AI agent platform with secure multi-provider LLM architecture, targeting production readiness with expanded test coverage, CI/CD, observability, and web frontend.

### Roadmap (`project/roadmap.md`)
Development priorities organized by urgency. High priority: test coverage expansion (80%+ security-critical), CI/CD automation, API contract documentation, provider data classification warnings. Medium priority: observability, daemon persona isolation, structured error codes, schema-level PG user separation. Future: web frontend, distributed deployment, external MCP servers.

### Tech Stack (`project/tech-stack.md`)
Technology choices and rationale. Java 21+ (core, memory, app) and Kotlin 2.1.20 (agents/Embabel) with Spring Boot 3.4.4 and Spring AI 1.1.0 for the engine. Go 1.22+ with Charm stack for the CLI. Embabel Agent Framework 0.3.4 for GOAP agent orchestration. PostgreSQL + pgvector (sessions, memory, conversations) and MongoDB (read-only legacy). JUnit 5, AssertJ, Mockito for testing. Multi-provider LLM via GitHub Copilot (OAuth) and SmartGate (JWT).

### Architecture (`project/architecture.md`)
System architecture documentation. Multi-module engine (kukuvaia-core Java, kukuvaia-agents Kotlin/Embabel, kukuvaia-memory Java, kukuvaia-app Boot entry). Thin clients (CLI, future web) connect via HTTP/SSE. Core subsystems: Agent Core (ChatClient + Advisors), Embabel Agent Orchestration (GOAP planning), Memory System (pgvector semantic search, JSONB conversations, 3 memory types), Sub-Agent System, Daemon System, Security Layer, Provider Routing. See also: `docs/architecture/embabel-integration.md`, `docs/architecture/memory-architecture.md`.

### Implementation Plan (`project/implementation-plan.md`)
Three-phase implementation plan for kukuvaia-server (Java 21 / Spring Boot 3.x / Spring AI 1.x). Phase 1 (Foundation): Spring configuration (ChatClientConfig, MemoryConfig, ToolRegistryConfig), data tools (@McpTool for PostgreSQL, MongoDB, Authorsuite), agent core (AgentService, CommandRouter, PersonaService), output protocol (sealed OutputBlock hierarchy), Web API (ChatController, SessionController, CommandController), slash commands, and tool registry resolution for SubAgentFactory. Phase 2 (Document Agent Capabilities): document file tools with sandboxed workspace, persistent memory (agent_memory table with full-text search, dual auto/explicit strategy), planning tools (agent_plans table), self-verification tools, system prompt architecture (static/dynamic boundary), extended OutputBlock types, and tool hook dispatcher. Phase 3 (Intelligence Layer): layered compaction memory (microcompact/snip/auto-compact/collapse), intent-driven retrieval advisor, structured workflows (3-tier routing), tool concurrency (readOnly parallel execution), token budget advisor, permission modes per persona, sandboxed command execution (allowlisted binaries), and user extensibility (.kukuvaia/ loader). Progress tracking: security (8 classes), daemon (7 classes), sub-agent (4 classes), provider (2 classes), and webhook (1 class) are COMPLETE; remaining packages (config, tools, agent/core, commands, extensions, output, api, advisors) are TODO. Includes detailed class-level specifications, SQL migrations (V2 agent_memory, V3 agent_plans), advisor chain execution order with precedence levels, full YAML configuration reference, security compatibility matrix for all new components, and target file inventory (~57 total files).

### CLI Implementation Plan (`project/cli-implementation-plan.md`)
Three-phase CLI implementation plan. Phase 1 (Foundation): Go project setup, MCP Server with local filesystem tools (read_file, write_file, find_files, search_content, bash_run, git_status), SSE client for server communication, minimal Bubbletea TUI. Phase 2 (Full TUI): OutputBlock renderers for all 7 types, session management, slash command routing, persona switching, keyboard shortcuts, theme integration. Phase 3 (Polish): authentication flow (device code + JWT), streaming word-by-word rendering, workspace detection, multi-panel layout. Key architecture: CLI operates as MCP Server exposing local tools -- kukuvaia-server connects as MCP Client, LLM sees local and server tools uniformly.

### Work Log (`project/work-log.md`)
Implementation work log tracking orchestrated execution progress, standards reading log (which standards were consulted during each phase), and discoveries made during implementation.

---

## Technical Standards

### Global Standards

Located in `.maister/docs/standards/global/`

#### Error Handling (`standards/global/error-handling.md`)
Clear user messages, fail-fast validation, typed exceptions, centralized handling at boundaries, graceful degradation, retry with backoff, and resource cleanup.

#### Validation (`standards/global/validation.md`)
Server-side validation always, client-side for feedback, early validation, specific error messages, allowlists over blocklists, type/format checks, input sanitization, business rule validation, and consistent enforcement.

#### Development Conventions (`standards/global/conventions.md`)
Predictable file structure, up-to-date documentation, clean version control, environment variables for config, minimal dependencies, consistent reviews, testing standards, feature flags, changelog updates, building only what is needed. Javadoc on all classes, single source theme file (kukuvaia-theme.yaml), HTTP/SSE API contract.

#### Coding Style (`standards/global/coding-style.md`)
Naming consistency, automatic formatting, descriptive names, focused functions, uniform indentation, no dead code, no unnecessary backward compatibility, DRY principle. Java naming: PascalCase classes, camelCase methods/fields, SCREAMING_SNAKE_CASE constants.

#### Commenting (`standards/global/commenting.md`)
Let code speak through structure and naming, comment sparingly when logic is not self-evident, and avoid change-log-style comments.

#### Minimal Implementation (`standards/global/minimal-implementation.md`)
Build only what is needed, clear purpose for every method, delete exploration artifacts, no future stubs, no speculative abstractions, review before commit, and treat unused code as debt.

### Security Standards

Located in `.maister/docs/standards/security/`

#### Credentials (`standards/security/credentials.md`)
Credentials file chmod 600 enforcement via CredentialsFileGuard, never hardcode secrets (env vars or secure files only), no credentials in logs/prompts/errors.

#### Authentication (`standards/security/authentication.md`)
Auth required on all API endpoints (ApiAuthFilter for Bearer/JWT), webhook HMAC-SHA256 validation with constant-time comparison (WebhookAuthFilter).

#### Injection Prevention (`standards/security/injection-prevention.md`)
Tool results treated as data with boundary markers (ToolResultSanitizingAdvisor), sub-agent anti-injection directives (SubAgentGuard), webhook payload sanitization with field allowlisting (PayloadSanitizer).

#### Runtime Security (`standards/security/runtime.md`)
Never run as root, no shell expansion (ProcessBuilder only), error sanitization stripping sensitive data (ErrorSanitizer), sliding window rate limiting on webhooks (10/min default, 429 + Retry-After).

#### Agent Isolation (`standards/security/agent-isolation.md`)
Sub-agent isolation with separate ChatClient instances and max depth=1, daemon daily token budget with 80% alert threshold, skip-if-running cron guard (AtomicBoolean), notification summary-only payloads, provider audit logging for all LLM calls.

### Backend Standards

Located in `.maister/docs/standards/backend/`

#### API Design (`standards/backend/api.md`)
RESTful principles, consistent naming, versioning, plural nouns for resources, limited URL nesting, query parameters for filtering/sorting/pagination, proper HTTP status codes, rate limit headers. Typed tools via @McpTool (no raw SQL), deterministic slash commands (no LLM), SSE streaming for chat (Flux<OutputBlock>), sealed OutputBlock hierarchy, /api/ prefix with plural resources.

#### Models (`standards/backend/models.md`)
Clear naming conventions, timestamps for auditing, database-level constraints, appropriate data types, indexed foreign keys, multi-layer validation, clear relationship definitions, and practical normalization.

#### Database Queries (`standards/backend/queries.md`)
Parameterized queries always, avoid N+1 with eager loading, select only needed columns, strategic index placement, transactions for related operations, query timeouts, and caching expensive queries.

#### Database Migrations (`standards/backend/migrations.md`)
Reversible migrations, small and focused changes, zero-downtime awareness, separate schema and data migrations, careful indexing on large tables, descriptive naming, and version-controlled migrations.

#### Dependency Injection (`standards/backend/dependency-injection.md`)
Constructor injection only (all fields final, no @Autowired on fields). Spring stereotype annotations: @Component, @Service, @Configuration, @RestController, @RestControllerAdvice for their designated roles.

#### Logging (`standards/backend/logging.md`)
SLF4J logger pattern (private static final, variable named 'log'), structured parameterized {} messages with context IDs, log level conventions (INFO=ops, WARN=security, ERROR=failures, DEBUG=dev).

#### Backend Architecture (`standards/backend/architecture.md`)
Base package ai.kukuvaia with domain sub-packages, Spring Boot 3.x + Spring AI 1.x stack, dual provider routing (Copilot for interactive, SmartGate for daemon), Spring AI advisor pattern with ordered chains, kukuvaia.* config prefix with defaults.

#### Java Patterns (`standards/backend/java-patterns.md`)
Records for DTOs/value types with compact constructors, immutable collections (Set.of/List.of), modern Java 17+ features (pattern matching, switch expressions, text blocks), custom exceptions as static inner classes, import organization (project > third-party > JDK, no wildcards), thread-safe shared state (ConcurrentHashMap, AtomicBoolean).

### Frontend Standards

Located in `.maister/docs/standards/frontend/`

#### CSS (`standards/frontend/css.md`)
Consistent methodology (Tailwind/BEM/modules), work with the framework, design tokens for colors/spacing/typography, minimize custom CSS, production optimization with purging. Styles generated from theme via go generate (no hardcoded values), Matrix aesthetic (phosphor green #00FF41 on black #000000), design tokens in YAML format with comments.

#### Components (`standards/frontend/components.md`)
Single responsibility, reusability with configurable props, composability over monoliths, clear prop interfaces, encapsulation, consistent naming, local state management, minimal props, documentation. CLI thin client (no agent logic/LLM/DB), Go Charm stack (Bubbletea + Lipgloss + Bubbles + Glamour), Bubbletea Elm architecture (Model/Update/View).

#### Accessibility (`standards/frontend/accessibility.md`)
Semantic HTML, keyboard navigation with visible focus, color contrast (4.5:1), alt text and labels, screen reader testing, ARIA attributes when needed, proper heading structure, and focus management.

#### Responsive Design (`standards/frontend/responsive.md`)
Mobile-first approach, standard breakpoints, fluid layouts, relative units (rem/em), cross-device testing, touch-friendly targets (44x44px minimum), mobile performance optimization, readable typography, and content priority on small screens.

### Testing Standards

Located in `.maister/docs/standards/testing/`

#### Test Writing (`standards/testing/test-writing.md`)
Test behavior not implementation, clear descriptive test names, mock external dependencies, fast unit test execution, risk-based testing priorities, balanced coverage and velocity, critical path focus, appropriate depth matching risk profile. JUnit 5 + AssertJ (no Hamcrest), test naming convention (methodName_scenario_expectedBehavior), package-private test classes with Test suffix, @BeforeEach with real objects, 80%+ coverage for security-critical code, static imports for AssertJ.

---

## How to Use This Documentation

1. **Start Here**: Always read this INDEX.md first to understand what documentation exists
2. **Project Context**: Read relevant project documentation before starting work
3. **Standards**: Reference appropriate standards when writing code
4. **Keep Updated**: Update documentation when making significant changes
5. **Customize**: Adapt all documentation to your project's specific needs

## Updating Documentation

- Project documentation should be updated when goals, tech stack, or architecture changes
- Technical standards should be updated when team conventions evolve
- Always update INDEX.md when adding, removing, or significantly changing documentation
