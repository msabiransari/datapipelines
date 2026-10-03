-- =============================================================================
-- V48 — parameter evaluations (#376, parameter-set workspace slice S3)
--
-- Authority: docs/superpowers/specs/2026-10-02-parameter-set-workspace-spec.md §2 (R2/R3) with the owner's §11
-- rulings of 2026-10-02 (11.7: ABORTED on both status lists; 11.10: all five callers, PIPELINE dormant). Recorded
-- as DDL in metadata-db.md §4.37 / §4.38. Two tables, purely additive:
--
-- 1. `parameter_evaluations` — one row per evaluation ATTEMPT, whoever ran it (the Parameter Sets page, a
--    dashboard, the REST route, the MCP tool). `id` is the observed route's client-minted evaluation id, else
--    server-minted — never re-issued. Inserted RUNNING when the evaluator admits the attempt (a request refused
--    before admission — an unknown selection key — writes no row) and finished exactly once at the one point that
--    owns its end. The principal is a person XOR a key (V43's CHECK shape). `principal_key_id` carries NO foreign
--    key: the keys purge deletes a revoked key once nothing references it, and a history row must neither hold a
--    key alive nor block that purge — the id stays as the text it was. NO foreign key to `parameter_set_versions`
--    either: purging a version must not delete the record that says it ran. The set FK cascades: a purged set's
--    history answers absent.
--
-- 2. `parameter_evaluation_queries` — one row per actual statement attempt of a template-backed selector (or a
--    database-fed INPUT source). `outcome` stays NULL while the statement is in flight and is written once when
--    it ends; REFUSED (never ran) is a value of the column, never an absence. Constants and free inputs produce no
--    row. No column can carry SQL text, a bind, a selection, a resolved value, a result row or a driver message.
--
-- Bounds: `outcomes_json` ≤ 8 KiB stored (pg_column_size); ≤ max-parameters-per-set query rows per evaluation.
-- Retention rides the executions' event retention on the hourly sweep (metadata-db §8.1); a RUNNING row the
-- sweep finds past the evaluate deadline plus its margin becomes INCOMPLETE (§8.5).
--
-- No trigger, no function, no extension, no existing table touched.
--
-- DOWN PATH (manual; the lane's evidence runs it on a copy of the demo database). The history is derived data —
-- dropping it loses only diagnostics — but refuse while an evaluation is in flight, then drop in dependency order:
--   DO $$ BEGIN IF EXISTS (SELECT 1 FROM parameter_evaluations WHERE status = 'RUNNING')
--     THEN RAISE EXCEPTION 'an evaluation is RUNNING; retry V48 rollback when none is'; END IF; END $$;
--   DROP TABLE parameter_evaluation_queries;
--   DROP TABLE parameter_evaluations;
--   DELETE FROM flyway_schema_history WHERE version = '48';
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. parameter_evaluations (metadata-db §4.37)
-- ---------------------------------------------------------------------------
CREATE TABLE parameter_evaluations (
    id                    UUID        PRIMARY KEY,
    workspace_id          UUID        NOT NULL REFERENCES workspaces(id),
    parameter_set_id      UUID        NOT NULL REFERENCES parameter_sets(id) ON DELETE CASCADE,
    parameter_set_version INTEGER     NOT NULL,
    caller                TEXT        NOT NULL,
    principal_user_id     UUID        NULL REFERENCES users(id),
    principal_key_id      TEXT        NULL,
    correlation_id        TEXT        NULL,
    status                TEXT        NOT NULL,
    outcome_code          TEXT        NULL,
    valid                 BOOLEAN     NULL,
    outcomes_json         JSONB       NULL,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at           TIMESTAMPTZ NULL,
    CONSTRAINT chk_parameter_evaluations_version CHECK (parameter_set_version >= 1),
    CONSTRAINT chk_parameter_evaluations_caller
        CHECK (caller IN ('PAGE', 'DASHBOARD', 'PIPELINE', 'REST', 'MCP')),
    CONSTRAINT chk_parameter_evaluations_principal
        CHECK ((principal_user_id IS NOT NULL AND principal_key_id IS NULL)
            OR (principal_user_id IS NULL AND principal_key_id IS NOT NULL)),
    CONSTRAINT chk_parameter_evaluations_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'ABORTED', 'TIMEOUT', 'FAILED', 'INCOMPLETE')),
    CONSTRAINT chk_parameter_evaluations_finished
        CHECK ((status = 'RUNNING' AND finished_at IS NULL) OR (status <> 'RUNNING' AND finished_at IS NOT NULL)),
    -- `valid` is the response's flag, so it exists exactly when a response was produced.
    CONSTRAINT chk_parameter_evaluations_valid CHECK ((status = 'COMPLETED') = (valid IS NOT NULL)),
    CONSTRAINT chk_parameter_evaluations_outcomes_size CHECK (pg_column_size(outcomes_json) <= 8192)
);

-- The History tab: one set's records, newest first.
CREATE INDEX idx_parameter_evaluations_set_started
    ON parameter_evaluations (workspace_id, parameter_set_id, started_at DESC);
-- The stale sweep reads RUNNING rows only; the partial index keeps that scan tiny.
CREATE INDEX idx_parameter_evaluations_running ON parameter_evaluations (started_at) WHERE status = 'RUNNING';
-- The retention step's cutoff scan: finished rows by started_at (spec §2.3).
CREATE INDEX idx_parameter_evaluations_finished ON parameter_evaluations (started_at) WHERE finished_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. parameter_evaluation_queries (metadata-db §4.38)
-- ---------------------------------------------------------------------------
CREATE TABLE parameter_evaluation_queries (
    id               UUID        PRIMARY KEY,
    evaluation_id    UUID        NOT NULL REFERENCES parameter_evaluations(id) ON DELETE CASCADE,
    parameter        TEXT        NOT NULL,
    datasource       TEXT        NOT NULL,
    template_id      TEXT        NOT NULL,
    template_version INTEGER     NOT NULL,
    queued_at        TIMESTAMPTZ NULL,
    started_at       TIMESTAMPTZ NULL,
    ended_at         TIMESTAMPTZ NULL,
    outcome          TEXT        NULL,
    refusal_code     TEXT        NULL,
    error_code       TEXT        NULL,
    row_count        INTEGER     NULL,
    CONSTRAINT chk_parameter_evaluation_queries_outcome
        CHECK (outcome IN ('EXECUTED', 'REFUSED', 'FAILED', 'TIMEOUT', 'ABORTED')),
    -- Each code belongs to its own outcome: a refusal code only on REFUSED, an error code only on FAILED, a row count
    -- only on EXECUTED (and never negative).
    CONSTRAINT chk_parameter_evaluation_queries_codes
        CHECK ((refusal_code IS NULL OR outcome = 'REFUSED')
           AND (error_code IS NULL OR outcome = 'FAILED')
           AND (row_count IS NULL OR (outcome = 'EXECUTED' AND row_count >= 0)))
);

-- The record detail's attempt list, and the cascade from a deleted evaluation (retention deletes by the parent).
CREATE INDEX idx_parameter_evaluation_queries_evaluation ON parameter_evaluation_queries (evaluation_id);
