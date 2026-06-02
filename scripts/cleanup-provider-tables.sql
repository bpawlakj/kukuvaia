-- cleanup-provider-tables.sql
-- Run AFTER deploying the file-based provider config refactor (002-kukuvaia-provider-refactor).
-- Removes the now-unused provider/model/role DB tables from the kukuvaia schema.
--
-- Prerequisites:
--   1. kukuvaia-engine is running with config-backed ChatModelCache (no DB reads at startup)
--   2. No running instance still uses the old DB-backed provider lookup
--
-- The Flyway migration V7__remove_provider_tables.sql performs the same DROP if you prefer
-- to track this via the migration history.

BEGIN;

-- FK-safe order: model_roles references models, models references providers
DROP TABLE IF EXISTS kukuvaia.model_roles;
DROP TABLE IF EXISTS kukuvaia.models;
DROP TABLE IF EXISTS kukuvaia.providers;

COMMIT;
