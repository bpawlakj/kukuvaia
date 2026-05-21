# Project Documentation

Layout:

- **`product-spec.md`** — what this product is (created by `/product-spec`).
- **`discover-notes.md`** — transient discovery notes (created by `/discover`, input to `/product-spec`).
- **`architecture/`** — design decisions and system docs (e.g. auth flow, data flow, integration designs).
- **`analyzes/`** — research and evaluation done BEFORE decisions (e.g. "should we adopt X?").
- **`reference/`** — operational specs (vendor configs, limits, model lists, API contracts).
- **`work/`** — in-flight initiatives. One folder per initiative: `NNN-<slug>/` with `plan.md`, `index.md`, and `T-NNN-*.md` task files.

## Conventions

- **Living docs** live at `docs/` root and in `architecture/`, `reference/` — edit-in-place as the project evolves. Don't create dated copies.
- **Research docs** in `analyzes/` are point-in-time snapshots — don't edit retroactively. Add a follow-up doc if findings change.
- **Work items** in `work/` follow folder-per-initiative: each gets a folder `NNN-<slug>/` with a `plan.md` (the "thinking doc"), task files `T-NNN-*.md` (atomic units of work), and an `index.md` (derived view of task status). Create via `/atomize`.
