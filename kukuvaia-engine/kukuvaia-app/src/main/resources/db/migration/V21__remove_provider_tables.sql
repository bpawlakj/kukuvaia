-- V7: Remove DB-backed provider/model/role tables.
-- Provider config is now file-based (kukuvaia.llm-providers in application.yaml).
-- These tables were created by V6 and are no longer read at startup or request time.

DROP TABLE IF EXISTS kukuvaia.model_roles;
DROP TABLE IF EXISTS kukuvaia.models;
DROP TABLE IF EXISTS kukuvaia.providers;
