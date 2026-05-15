-- Drop the V8 DB-backed harness tables. Harness rules now live as markdown files behind a
-- HarnessRuleStore (default: filesystem at the kukuvaia.harness.directory property). The DB
-- tables were never populated in production; the rewrite was driven by:
--   * Reproducibility for non-interactive agent runs (P23) — rules in git, not in mutable rows.
--   * Consistency with the .kukuvaia/ filesystem convention used for personas, tools, schedules.
--   * Operator UX — file diffs and PR review beat hand-edited Postgres rows; admin panel reads
--     the same store, so dynamic management is preserved.
--
-- CASCADE on rule_sets removes the dependent rules table automatically; user_groups depends on
-- groups via FK so it is dropped first. Order matters here even with IF EXISTS.

DROP TABLE IF EXISTS kukuvaia.user_groups;
DROP TABLE IF EXISTS kukuvaia.rules;
DROP TABLE IF EXISTS kukuvaia.rule_sets;
DROP TABLE IF EXISTS kukuvaia.groups;
