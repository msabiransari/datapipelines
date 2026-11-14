-- The managed lake's maintenance runs (#957, dp-lake design §5.2, §11, §11a call 34; P6 lane cut L2/L3).
-- One row per ADMITTED run of the `lake-maintenance` task for one table. The row is the run's exclusion and its
-- authority: it is written before the run reads or writes the table, its deadline_at is the deadline every lease
-- the run takes carries (§11), and at most one row per table is `running` (the partial unique index below).
-- A `running` row whose deadline has passed is closed `abandoned` by its successor's admitting statement, never by
-- its own late worker: every later write of a row is conditional on `state = 'running'`.
--   kind      maintenance (the parts in the record's order) | purge (a purged table's deletion part alone)
--   state     running | finished | failed | abandoned
--   parts_json the parts' outcomes, in order: [{"name", "outcome", "attempted", "until", "reason", "error_code",
--             "files", "bytes", "deferred_deletions"}]
--   settlement_json the settlement step's figures (design §8b at every pass): {"settled", "visited", "left_unsettled",
--             "deferred", "error_code"}; NULL until the step ran
--   error_code / error_message  a failed or abandoned run's code and a message with no connection string, no stack
-- Rows are kept indefinitely until #965 decides how long.
-- Down rehearsal: DROP TABLE lake_maintenance_runs; remove history 60.
CREATE TABLE lake_maintenance_runs (
    id                     UUID        PRIMARY KEY,
    table_id               UUID        NOT NULL REFERENCES lake_tables_managed(id) ON DELETE CASCADE,
    kind                   TEXT        NOT NULL,
    state                  TEXT        NOT NULL,
    deadline_at            TIMESTAMPTZ NOT NULL,
    scratch_reserved_bytes BIGINT      NOT NULL DEFAULT 0,
    files_touched          BIGINT      NOT NULL DEFAULT 0,
    bytes_touched          BIGINT      NOT NULL DEFAULT 0,
    deferred_deletions     BIGINT      NOT NULL DEFAULT 0,
    parts_json             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    settlement_json        JSONB,
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at               TIMESTAMPTZ,
    error_code             TEXT,
    error_message          TEXT,
    CONSTRAINT chk_lake_maintenance_runs_kind CHECK (kind IN ('maintenance', 'purge')),
    CONSTRAINT chk_lake_maintenance_runs_state CHECK (state IN ('running', 'finished', 'failed', 'abandoned')),
    CONSTRAINT chk_lake_maintenance_runs_ended CHECK ((state = 'running') = (ended_at IS NULL)),
    CONSTRAINT chk_lake_maintenance_runs_figures CHECK (
        scratch_reserved_bytes >= 0 AND files_touched >= 0 AND bytes_touched >= 0 AND deferred_deletions >= 0),
    CONSTRAINT chk_lake_maintenance_runs_parts CHECK (jsonb_typeof(parts_json) = 'array')
);

-- The exclusion (§11a call 34, rule 1): one running run per table, whatever the instance that admitted it.
CREATE UNIQUE INDEX uq_lake_maintenance_runs_running ON lake_maintenance_runs (table_id) WHERE state = 'running';

-- A table's run history, newest first (the planner's and the table page's read).
CREATE INDEX idx_lake_maintenance_runs_table_started ON lake_maintenance_runs (table_id, started_at DESC);
