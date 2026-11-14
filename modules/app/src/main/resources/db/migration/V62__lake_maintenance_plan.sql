-- =============================================================================
-- V62 — the lake maintenance planner's rows (#959, dp-lake P6; design record §11a call 34, §11b call 46;
-- lane cut L8, L16)
--
-- 1. lake_table_measurements — ONE row per managed table: the planner's latest figures, read from the current
--    snapshot's manifests (no data file is opened), and its latest ATTEMPT. Every attempt stamps attempt_id,
--    attempted_at and the profile pair it ran under (attempt_profile_id, attempt_profile_updated_at), failed ones
--    too; a failure or a timeout also stamps error_code and keeps the last good figures. A success writes the
--    figures, measured_at and the profile pair the profile-dependent figures were computed under
--    (figures_profile_id, figures_profile_updated_at), and clears error_code, conditional on attempt_id still
--    being this attempt's (a later attempt by a restart or another instance wins).
--      profile-independent: snapshot_id, current_bytes, current_rows, data_files, delete_files, manifests,
--        unsorted_files (data files not stamped with the table's sort order)
--      profile-dependent: small_files (small files in candidate partitions), unsorted_over_cap (unsorted files
--        larger than the profile's bytes cap), candidate_bytes (every candidate file counted once, cap-blocked
--        files left out)
--    A measured table with no current snapshot has measured_at set and every figure NULL: a figure whose source
--    was not read is NULL, never zero (call 46). The figures are valid for decisions only while the figures'
--    profile pair equals the table's current profile id and updated_at (brief 959 D3a).
-- 2. lake_storage_usage — one row per table per UTC day: the day's latest current bytes, rows and files with the
--    time they were measured (cut L8). Growth per week reads the newest row measured at least 7 days ago. Rows
--    measured more than 35 days ago are deleted by the planner's pass in one statement.
-- 3. lake_maintenance_plan — ONE row (id = 1), overwritten by every plan: its time and its counts.
-- 4. lake_tables_managed.purge_deleted_at — NULL until a purge deletion has finished (record call 34, "When a purge
--    deletion is finished"); 962 stamps it, the planner only reads it.
-- 5. idx_lake_maintenance_runs_finished — the planner's run-history statement (brief 959 D5) reads a table's
--    FINISHED runs newest first, by end time, start time and id.
--
-- Down rehearsal (documented, not shipped — Flyway runs forward only):
--   DROP INDEX idx_lake_maintenance_runs_finished;
--   ALTER TABLE lake_tables_managed DROP COLUMN purge_deleted_at;
--   DROP TABLE lake_maintenance_plan; DROP TABLE lake_storage_usage; DROP TABLE lake_table_measurements;
--   DELETE FROM flyway_schema_history WHERE version = '62';
-- =============================================================================

CREATE TABLE lake_table_measurements (
    table_id                    UUID        PRIMARY KEY REFERENCES lake_tables_managed(id) ON DELETE CASCADE,
    attempt_id                  UUID        NOT NULL,
    attempted_at                TIMESTAMPTZ NOT NULL,
    attempt_profile_id          UUID        NOT NULL,
    attempt_profile_updated_at  TIMESTAMPTZ NOT NULL,
    error_code                  TEXT        NULL,
    measured_at                 TIMESTAMPTZ NULL,
    snapshot_id                 BIGINT      NULL,
    current_bytes               BIGINT      NULL,
    current_rows                BIGINT      NULL,
    data_files                  BIGINT      NULL,
    delete_files                BIGINT      NULL,
    manifests                   BIGINT      NULL,
    unsorted_files              BIGINT      NULL,
    small_files                 BIGINT      NULL,
    unsorted_over_cap           BIGINT      NULL,
    candidate_bytes             BIGINT      NULL,
    figures_profile_id          UUID        NULL,
    figures_profile_updated_at  TIMESTAMPTZ NULL,
    CONSTRAINT chk_lake_table_measurements_figures CHECK (
        coalesce(current_bytes, 0) >= 0 AND coalesce(current_rows, 0) >= 0 AND coalesce(data_files, 0) >= 0
        AND coalesce(delete_files, 0) >= 0 AND coalesce(manifests, 0) >= 0 AND coalesce(unsorted_files, 0) >= 0
        AND coalesce(small_files, 0) >= 0 AND coalesce(unsorted_over_cap, 0) >= 0 AND coalesce(candidate_bytes, 0) >= 0),
    CONSTRAINT chk_lake_table_measurements_measured CHECK (
        (measured_at IS NULL) = (figures_profile_id IS NULL) AND (measured_at IS NULL) = (figures_profile_updated_at IS NULL)),
    CONSTRAINT chk_lake_table_measurements_snapshot CHECK (
        snapshot_id IS NOT NULL OR (current_bytes IS NULL AND current_rows IS NULL AND data_files IS NULL
            AND delete_files IS NULL AND manifests IS NULL AND unsorted_files IS NULL AND small_files IS NULL
            AND unsorted_over_cap IS NULL AND candidate_bytes IS NULL))
);

CREATE TABLE lake_storage_usage (
    table_id      UUID        NOT NULL REFERENCES lake_tables_managed(id) ON DELETE CASCADE,
    usage_day     DATE        NOT NULL,
    measured_at   TIMESTAMPTZ NOT NULL,
    current_bytes BIGINT      NOT NULL,
    current_rows  BIGINT      NOT NULL,
    current_files BIGINT      NOT NULL,
    PRIMARY KEY (table_id, usage_day),
    CONSTRAINT chk_lake_storage_usage_figures CHECK (current_bytes >= 0 AND current_rows >= 0 AND current_files >= 0)
);

-- The pass's prune of rows measured more than 35 days ago.
CREATE INDEX idx_lake_storage_usage_measured ON lake_storage_usage (measured_at);

CREATE TABLE lake_maintenance_plan (
    id                  INT         PRIMARY KEY DEFAULT 1,
    planned_at          TIMESTAMPTZ NOT NULL,
    tables_with_work    INT         NOT NULL,
    tasks_enqueued      INT         NOT NULL,
    tasks_moved         INT         NOT NULL,
    tasks_still_queued  INT         NOT NULL,
    tables_measured     INT         NOT NULL,
    tables_not_measured INT         NOT NULL,
    tables_stale        INT         NOT NULL,
    tables_rule_silent  INT         NOT NULL,
    CONSTRAINT chk_lake_maintenance_plan_one CHECK (id = 1),
    CONSTRAINT chk_lake_maintenance_plan_counts CHECK (
        tables_with_work >= 0 AND tasks_enqueued >= 0 AND tasks_moved >= 0 AND tasks_still_queued >= 0
        AND tables_measured >= 0 AND tables_not_measured >= 0 AND tables_stale >= 0 AND tables_rule_silent >= 0)
);

ALTER TABLE lake_tables_managed ADD COLUMN purge_deleted_at TIMESTAMPTZ NULL;

CREATE INDEX idx_lake_maintenance_runs_finished
    ON lake_maintenance_runs (table_id, ended_at DESC, started_at DESC, id DESC) WHERE state = 'finished';
