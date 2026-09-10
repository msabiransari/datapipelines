-- V20__version_write_surface.sql (102, owner ruling 2026-09-09; versioning §3.7)
--
-- `created_via` / `updated_via` on BOTH version tables: which surface the write arrived on
-- ('session' | 'api_key' | 'mcp'), while `created_by` / `updated_by` keep naming the PERSON
-- (a key principal's writes are its owner's — no key id is ever stored on a row).
--
-- Stamped at the entry point (REST controllers map the auth method; the MCP tools pass
-- 'mcp'; import/seed paths keep the default). Release stamps NOTHING new: D4 already says a
-- release is a human in a session — asserted by test, not recorded.
--
-- Existing rows backfill to 'session' by the DEFAULT itself — no UPDATE, no migration
-- semantics: every pre-102 row predates the distinction, and 'session' is the honest
-- majority answer for the workspaces that existed before MCP keys did.

ALTER TABLE pipeline_versions
    ADD COLUMN created_via TEXT NOT NULL DEFAULT 'session',
    ADD COLUMN updated_via TEXT NOT NULL DEFAULT 'session',
    ADD CONSTRAINT chk_pipeline_versions_via CHECK (
        created_via IN ('session', 'api_key', 'mcp')
        AND updated_via IN ('session', 'api_key', 'mcp')
    );

ALTER TABLE template_versions
    ADD COLUMN created_via TEXT NOT NULL DEFAULT 'session',
    ADD COLUMN updated_via TEXT NOT NULL DEFAULT 'session',
    ADD CONSTRAINT chk_template_versions_via CHECK (
        created_via IN ('session', 'api_key', 'mcp')
        AND updated_via IN ('session', 'api_key', 'mcp')
    );
