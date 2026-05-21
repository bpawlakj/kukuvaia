# Reference

Source material and operational specs that downstream skills (`/discover`, `/research`, `/plan`, `/product-spec`, `/atomize`) can pull context from. **Any file type is welcome** — not just markdown.

## What goes here

Two broad categories:

### 1. Operational specs (factual reference data)

- `<vendor>-models.md` — list of models available from a provider, with capabilities
- `<vendor>-timeout.md` — rate limits and timeouts
- `<api>-contracts.md` — API contract spec
- `<config>-schema.md` — config file schema reference

### 2. Source documents for analysis / planning

Material that downstream skills should read when shaping a decision, researching a topic, or planning a feature. Examples:

- `existing-product-brief.pdf` — a stakeholder-provided document the next feature should build on
- `requirements-from-client.docx` — pre-existing requirements doc
- `competitor-analysis.xlsx` — data backing a /research investigation
- `architecture-diagram.png` / `.drawio` — visual artifacts to reference during /plan
- `links-payments-integration.md` — a curated index of external URLs (vendor docs, RFCs, blog posts) for a planned feature
- `<topic>-sources.md` — markdown file collecting links + short annotations, used as a starting point by /research

## File-type policy

- **Any format allowed**: `.md`, `.pdf`, `.docx`, `.xlsx`, `.csv`, `.png`, `.drawio`, `.json`, `.yaml`, etc.
- For binary / non-text files, **pair them with a sibling `.md` index** that names the file, summarizes what it contains, and notes when it should be consulted. This gives skills a readable entry point without forcing them to parse binaries blindly. Example:
  ```
  docs/reference/
  ├── client-requirements.pdf
  └── client-requirements.md     # "Source: client email 2026-04-10. Covers FR scope for v2 module. Read before /discover on payments."
  ```
- For link collections, use a `.md` file with one link per bullet plus a one-line annotation. Don't just dump URLs.

## Conventions

- These are **reference material** — not decisions (`analyzes/`), not plans (`work/`), not design docs (`architecture/`).
- Update when the underlying source changes; for snapshots that should not change, prefix with a date: `2026-05-20-client-brief.pdf`.
- If a referenced external link is load-bearing for a decision, also archive a copy of the content (PDF print or markdown extract) — external URLs rot.
