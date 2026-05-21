# Architecture

Design decisions and system documentation. These are LIVING docs — edit in place as the system evolves.

Examples of what goes here:

- `auth-and-providers.md` — how authentication and provider integrations work
- `chat-flow.md` — request/response flow through the system
- `memory-architecture.md` — how memory is structured and retrieved
- `tool-filtering.md` — how tools are selected per request
- `security-review.md` — security posture, threat model
- `integration-<name>.md` — how a third-party system is integrated

Naming: kebab-case, descriptive. No prefixes (these aren't sequential).

When something is fully superseded (replaced rather than refined), move it to `docs/_archive/YYYY-MM-DD-<doc>.md` and write the replacement at the original path.
