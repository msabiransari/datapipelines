-- =============================================================================
-- V30 — Roles R1 (#177), D11: who EXECUTED a run, and through what kind of credential
--
-- Authority: docs/superpowers/specs/2026-09-20-roles-permissions-design.md (RATIFIED),
-- D11 / §3.2; recorded as DDL in metadata-db.md §4.6.
--
-- `triggered_by` becomes `executed_by` — the same NOT NULL FK to users(id), the same
-- index — because the column answers "whose run is this" and the read path now filters
-- on it: a member reads `executed_by = self`, a workspace admin reads all, a promoter
-- reads none. A rename, not a new column, so no row loses its actor.
--
-- `executed_by_key_kind` says which KIND of credential started the run, when a key did:
--   NULL       — a signed-in session (the UI, or REST with a cookie);
--   'user'     — a user API key (REST or MCP; the key's owner is `executed_by`);
--   'endpoint' — a published endpoint's key. The FK still names the key's OWNER (the
--                concurrency slot and the audit trail need a user), but the run is NOT
--                that person's: the own-runs filter excludes it, so an endpoint-key run
--                lists for workspace admins only — and for the endpoint key itself,
--                through the serve audit row (auth.md §7.7), as before;
--   'server'   — reserved for the promotion peer's credential; nothing writes it today.
-- A CHECK keeps the value set closed, like every other kind column in this schema.
--
-- Backfill from the trigger the row already carries: ENDPOINT runs → 'endpoint', MCP runs
-- → 'user' (MCP authenticates with a user key only). REST and UI rows stay NULL — a REST
-- run may have been a key or a cookie and the row cannot tell, and NULL is read as "a
-- person's run", which is the behaviour those rows had.
--
-- DOWN PATH (manual): ALTER TABLE pipeline_executions DROP CONSTRAINT chk_executions_executed_by_key_kind,
--   DROP COLUMN executed_by_key_kind; ALTER TABLE pipeline_executions RENAME COLUMN executed_by TO triggered_by;
-- The rename is lossless. The kind column is lossless for every row THIS migration backfilled
-- (down then up re-derives 'endpoint' and 'user' from the trigger), but a `user` kind the
-- application wrote at runtime on a REST run (a user key over REST) cannot be re-derived from
-- `triggered_via = 'REST'` and comes back NULL — measured on the 177 lane's demo DB.
-- =============================================================================

ALTER TABLE pipeline_executions RENAME COLUMN triggered_by TO executed_by;

ALTER TABLE pipeline_executions ADD COLUMN executed_by_key_kind TEXT NULL;

ALTER TABLE pipeline_executions
    ADD CONSTRAINT chk_executions_executed_by_key_kind
        CHECK (executed_by_key_kind IS NULL OR executed_by_key_kind IN ('user', 'endpoint', 'server'));

UPDATE pipeline_executions SET executed_by_key_kind = 'endpoint' WHERE triggered_via = 'ENDPOINT';
UPDATE pipeline_executions SET executed_by_key_kind = 'user'     WHERE triggered_via = 'MCP';
