-- =============================================================================
-- V43 — dashboard refreshes (#10, dashboards round one, lane L2)
--
-- Authority: docs/superpowers/specs/2026-09-28-dashboard-implementation-spec.md §2.2 as narrowed by §18 (premise 9:
-- V43 is L2's only — the api_keys kind/role CHECKs, executed_by_key_kind = 'dashboard' and dashboard_key_bindings are
-- L5's V44). Recorded as DDL in metadata-db.md §4.6 (the trigger CHECK), §4.34 and §4.35. Three changes:
--
-- 1. `pipeline_executions.triggered_via` gains 'DASHBOARD' (V38's list plus one; enums.md §18). The constraint is
--    dropped and re-added under the same name, exactly as V38 did for 'SCHEDULE'.
--
-- 2. `dashboard_refreshes` — one row per refresh of a released dashboard (spec §8.3, §9). `id` is the client-minted
--    refresh id (UUID v4, validated in code; a reused id is refused before any insert). The row is inserted RUNNING
--    only AFTER admission holds the slots (§18 premise 3: a refused refresh leaves no row, and the status CHECK has no
--    value for a refusal). The principal is a person XOR a key (exactly one non-null): `principal_key_id` is L5's
--    column, present now so V44 needs no rewrite. `selections_json` is capped at 64 KiB by a CHECK on the stored
--    size (the reader enforces the same bound first). The dashboard FK cascades: a purged draft-only dashboard drops
--    its refreshes (§18 premise 7).
--
-- 3. `dashboard_refresh_executions` — the link D52 requires, refresh → the executions it started. The events pane
--    joins here and never copies. `execution_id` references `pipeline_executions`; the row is written by the
--    launcher's `onRecorded` hook, which fires right after the execution's RUNNING insert and before its first
--    event, so "linked before its first event" is true by construction (§18 premise 4).
--
-- No trigger, no function, no extension: additive apart from the one CHECK widening.
--
-- DOWN PATH (manual; the lane's evidence runs it on a copy of the demo database). Refuse if any refresh was written
-- or any execution was triggered by a dashboard, then reverse in dependency order:
--   DO $$ BEGIN IF EXISTS (SELECT 1 FROM dashboard_refreshes)
--       OR EXISTS (SELECT 1 FROM pipeline_executions WHERE triggered_via = 'DASHBOARD')
--     THEN RAISE EXCEPTION 'dashboard refreshes exist; V43 cannot be rolled back'; END IF; END $$;
--   DROP TABLE dashboard_refresh_executions;
--   DROP TABLE dashboard_refreshes;
--   ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
--   ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
--       CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE', 'ENDPOINT', 'SCHEDULE'));
--   DELETE FROM flyway_schema_history WHERE version = '43';
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. pipeline_executions.triggered_via gains 'DASHBOARD' (metadata-db §4.6, enums.md §18)
-- ---------------------------------------------------------------------------
ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
    CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE', 'ENDPOINT', 'SCHEDULE', 'DASHBOARD'));

-- ---------------------------------------------------------------------------
-- 2. dashboard_refreshes (metadata-db §4.34)
-- ---------------------------------------------------------------------------
CREATE TABLE dashboard_refreshes (
    id                 UUID        PRIMARY KEY,
    dashboard_id       UUID        NOT NULL REFERENCES dashboards(id) ON DELETE CASCADE,
    dashboard_version  INTEGER     NOT NULL,
    workspace_id       UUID        NOT NULL REFERENCES workspaces(id),
    instance_id        UUID        NOT NULL,
    principal_user_id  UUID        NULL REFERENCES users(id),
    principal_key_id   TEXT        NULL REFERENCES api_keys(id),
    scope              TEXT        NOT NULL,
    targets_json       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    parameter_revision INTEGER     NOT NULL,
    selections_json    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status             TEXT        NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at        TIMESTAMPTZ NULL,
    summary_json       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT chk_dashboard_refreshes_principal
        CHECK ((principal_user_id IS NOT NULL AND principal_key_id IS NULL)
            OR (principal_user_id IS NULL AND principal_key_id IS NOT NULL)),
    CONSTRAINT chk_dashboard_refreshes_scope CHECK (scope IN ('ALL', 'TARGETS')),
    CONSTRAINT chk_dashboard_refreshes_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'ABORTED', 'TIMED_OUT')),
    CONSTRAINT chk_dashboard_refreshes_selections_size CHECK (pg_column_size(selections_json) <= 65536),
    CONSTRAINT chk_dashboard_refreshes_finished
        CHECK ((status = 'RUNNING' AND finished_at IS NULL) OR (status <> 'RUNNING' AND finished_at IS NOT NULL))
);

-- The caller's own refreshes of one dashboard, newest first (GET /{id}/refreshes; dashboards_get's last_refresh).
CREATE INDEX idx_dashboard_refreshes_dashboard_started
    ON dashboard_refreshes (workspace_id, dashboard_id, started_at DESC);
-- The stale-refresh sweeper reads RUNNING rows only; the partial index keeps that scan tiny.
CREATE INDEX idx_dashboard_refreshes_running ON dashboard_refreshes (started_at) WHERE status = 'RUNNING';
-- The retention step deletes by finished_at.
CREATE INDEX idx_dashboard_refreshes_finished ON dashboard_refreshes (finished_at) WHERE finished_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. dashboard_refresh_executions (metadata-db §4.35)
-- ---------------------------------------------------------------------------
CREATE TABLE dashboard_refresh_executions (
    refresh_id   UUID    NOT NULL REFERENCES dashboard_refreshes(id) ON DELETE CASCADE,
    source_name  TEXT    NOT NULL,
    execution_id UUID    NOT NULL REFERENCES pipeline_executions(execution_id),
    shared       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_dashboard_refresh_executions PRIMARY KEY (refresh_id, source_name)
);

CREATE INDEX idx_dashboard_refresh_executions_execution ON dashboard_refresh_executions (execution_id);
