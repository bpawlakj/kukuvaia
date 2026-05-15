---
key: jsonlogic-format
scope: platform
type: instruction
priority: 80
enabled: true
tags: [validation-engine, rule-editor, jsonlogic]
---
**JsonLogic format for `create_rule` / `update_rule` / `promote_rule`** — applies whenever you
write a `prerequisite` or `deterministicExpression` field for the validation-engine's MCP tools.

These fields are submitted as JSON strings, but the **content** of the string MUST be a valid
JsonLogic JSON object. The deterministic evaluator silently fail-closes on parse errors —
your rule will look accepted but emit zero findings until somebody manually inspects logs.
The validation-engine now rejects malformed input at create / update time; treat the rejection
as your signal to convert pseudo-syntax to a JsonLogic object before retrying.

**WRONG — pseudo-syntax that LLMs frequently produce. These get rejected by the server (400).**
- `"@metadata.combiContent == true"`
- `"section.booleanMetadata.X == true"`
- `"if combiContent then check X"`
- `"section.x AND section.y"`

**CORRECT — valid JsonLogic JSON objects:**
- Equality on a boolean metadata spec:
  `{"==": [{"var": "section.booleanMetadata.<spec-id>"}, true]}`
- AND of two checks:
  `{"and": [
      {"==": [{"var": "section.booleanMetadata.<id-A>"}, true]},
      {"!=": [{"var": "section.taxonomyMetadata.<id-B>"}, null]}
  ]}`
- Negation:
  `{"!": {"==": [{"var": "section.sectionType"}, "Lesson"]}}`

**Required workflow before authoring a metadata-touching rule:**
1. `find_outline_templates(...)` → resolve `outlineTemplateId`.
2. `get_template_metadata_specs(outlineTemplateId)` → look up the spec by `exportName`.
3. Read the spec's `id` (NOT the `exportName`) and its `dataType`.
4. Compose the var path according to `dataType`:
   - `BOOLEAN`  → `section.booleanMetadata.<id>`
   - `TAXONOMY` → `section.taxonomyMetadata.<id>`
   - `STRING`   → `section.stringMetadata.<id>`
   - `NUMBER`   → `section.numberMetadata.<id>`

**For content items, use `contentItem.*` instead of `section.*`** — same metadata sub-keys.

When unsure, read the existing test fixtures in `JsonLogicDeterministicEvaluatorTest` (validation-engine
`application/src/test/java`) — every accepted shape is exercised there.
