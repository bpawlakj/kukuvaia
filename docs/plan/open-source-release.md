# Open-Source Release Plan

## Direction

Kukuvaia is being prepared for an open-source release under **Apache 2.0**. The positioning is: a self-hosted AI agent platform with first-class user extensibility (YAML tools, Lua scripting, custom personas).

## Why open-source

- Kukuvaia occupies a distinct position versus closed-source agent products (Claude Code, Cursor) — self-hosted, inspectable, extensible.
- Extensibility benefits from community contributions: YAML/Lua tool recipes, persona presets, integrations (k8s, AWS, git, Jira).
- The platform's core value (agent orchestration, memory, planning) is generic; keeping it proprietary would gate adoption without a proportional commercial upside.

## Release deliverables

- **License** — Apache 2.0, `LICENSE` file in repository root.
- **README** — quick-start with `docker compose up`, architecture diagram, one worked tool example.
- **Demo asset** — 30-second GIF or short video: install → add a YAML tool → the agent uses it.
- **CI/CD** — GitHub Actions for build, test, lint across all three components (engine, CLI, admin).
- **First release tag** — `v0.1.0` covering the engine + CLI MVP. Admin UI can follow in `v0.2.0` if not ready.
- **Issue templates** — bug report, feature request, security disclosure.
- **Contribution guide** — `CONTRIBUTING.md` describing branch model, commit style, test expectations.

## Target audiences

- Individual developers who want a self-hosted agent they can extend.
- Small teams building internal automation agents (ops, platform engineering, content workflows).
- Enterprises with data-sensitivity constraints that rule out SaaS agents.

## Open questions (to resolve before tagging)

- Telemetry: opt-in usage ping vs. fully offline by default.
- Release artefact distribution: GitHub Releases binaries for the CLI, container image for the engine.
- Hosted demo environment: optional, may be deferred past v0.1.0.

## Related documentation

- `README.md` — user-facing quick-start and prerequisites.
- `docs/architecture/` — system design for inbound contributors.
- `docs/architecture/project-history.md` — why kukuvaia exists at all.
