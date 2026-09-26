-- =============================================================================
-- V38 — the scheduler core (#9, scheduler lane 1)
--
-- Authority: docs/superpowers/specs/2026-09-22-scheduler-design-revision.md (RATIFIED
-- 2026-09-25) §7, §7.1; recorded as DDL in metadata-db.md §4.22–§4.25 and §5. Five facts:
--
-- 1. `schedules` — one row per schedule, workspace-scoped, named by the pipeline path grammar
--    (enforced in code: the name rule is PipelineNameGrammar's, record §6). The executor
--    payload and parameters are OPAQUE jsonb (record §5): nothing pipeline-shaped is a column.
--    JSONB columns carry the `_json` suffix (metadata-db §2). SOFT delete (D-9.6/B17): the name
--    is unique among LIVE schedules only, and a deleted schedule keeps its runs. `blocked_*` and `enabled` are independent operational controls
--    (§1.1). The create `Idempotency-Key` is durable here (L1, A15).
--
-- 2. `schedule_runs` — one row per occurrence or manual request, with its frozen context and
--    the executor-owned `prepared_json` snapshot (R5: for the pipeline executor, the fired version
--    and its body's SHA-256). `state` + `reason` per R6's table (§7.1). Three uniquenesses:
--    one row per (schedule, occurrence instant) — the dispatcher's ON CONFLICT DO NOTHING; one
--    manual run per (schedule, requester, Idempotency-Key) — L1; and at most ONE active run
--    per schedule (a partial unique index — the durable overlap guard, record §3).
--
-- 3. `schedule_run_events` — the scheduler's append-only trail (R10, §5.4). `seq` is drawn
--    from `schedule_runs.trail_seq` under the run row's lock. Append-only BY CONSTRUCTION: no code
--    path updates or deletes a row (`ScheduleTrailAppendOnlyTest` scans the sources), and no
--    trigger enforces it — this schema has none (metadata-db §2/§7.2, "emits no triggers").
--
-- 4. `scheduled_tasks` — db-scheduler 16.12.0's EXACT PostgreSQL DDL
--    (db-scheduler/src/test/resources/postgresql_tables.sql at tag v16.12.0), copied verbatim
--    with its three indexes. The library owns the shape; this file only creates it.
--
-- 5. `pipeline_executions.chk_triggered_via` gains 'SCHEDULE' (record §7, A16) — the widening
--    V3 and V11 performed before it. No existing row changes.
--
-- DOWN PATH (manual; the evidence of the lane runs it on a copy of the demo database): refuse if
-- any execution was scheduled, then drop in dependency order and restore V11's CHECK —
--   DO $$ BEGIN IF EXISTS (SELECT 1 FROM pipeline_executions WHERE triggered_via = 'SCHEDULE')
--     THEN RAISE EXCEPTION 'scheduled executions exist; V38 cannot be rolled back'; END IF; END $$;
--   DROP TABLE schedule_run_events;
--   DROP TABLE schedule_runs; DROP TABLE schedules; DROP TABLE scheduled_tasks;
--   ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
--   ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
--     CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE', 'ENDPOINT'));
--   DELETE FROM flyway_schema_history WHERE version = '38';
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. schedules (metadata-db §4.22)
-- ---------------------------------------------------------------------------
CREATE TABLE schedules (
    id                      UUID        PRIMARY KEY,
    workspace_id            UUID        NOT NULL REFERENCES workspaces(id),
    name                    TEXT        NOT NULL,
    revision                INT         NOT NULL DEFAULT 1,
    executor_id             TEXT        NOT NULL,
    payload_schema_version  INT         NOT NULL,
    payload_json            JSONB       NOT NULL,
    parameters_json         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    target_ref              TEXT        NOT NULL,
    cron                    TEXT        NOT NULL,
    timezone                TEXT        NOT NULL,
    missed_run_policy       TEXT        NOT NULL DEFAULT 'skip',
    enabled                 BOOLEAN     NOT NULL DEFAULT TRUE,
    blocked_reason          TEXT,
    blocked_at              TIMESTAMPTZ,
    blocked_run_id          UUID,
    next_due_at             TIMESTAMPTZ,
    created_by              UUID        NOT NULL REFERENCES users(id),
    updated_by              UUID        NOT NULL REFERENCES users(id),
    idempotency_key         TEXT,
    idempotency_hash        TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMPTZ,
    deleted_by              UUID        REFERENCES users(id),
    CONSTRAINT chk_schedules_missed_run_policy CHECK (missed_run_policy IN ('skip', 'latest')),
    -- A block is a reason AND an instant, never one without the other.
    CONSTRAINT chk_schedules_blocked CHECK ((blocked_reason IS NULL) = (blocked_at IS NULL)),
    -- The key and its request hash travel together (a replay compares the hash).
    CONSTRAINT chk_schedules_idempotency CHECK ((idempotency_key IS NULL) = (idempotency_hash IS NULL)),
    CONSTRAINT chk_schedules_revision CHECK (revision >= 1)
);

-- The name is unique among LIVE schedules of a workspace (soft delete keeps the row).
CREATE UNIQUE INDEX uq_schedules_workspace_name_live ON schedules (workspace_id, name) WHERE deleted_at IS NULL;
-- L1: one schedule per (workspace, creator, Idempotency-Key).
CREATE UNIQUE INDEX uq_schedules_create_idempotency ON schedules (workspace_id, created_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- The dispatcher's read: due, enabled, unblocked, live — partial, so a paused fleet costs nothing.
CREATE INDEX idx_schedules_due ON schedules (next_due_at)
    WHERE enabled AND blocked_at IS NULL AND deleted_at IS NULL;
-- B15: "which schedules run this target?" without the scheduler parsing a payload.
CREATE INDEX idx_schedules_target ON schedules (workspace_id, target_ref) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 2. schedule_runs (metadata-db §4.23)
-- ---------------------------------------------------------------------------
CREATE TABLE schedule_runs (
    id                      UUID        PRIMARY KEY,
    schedule_id             UUID        REFERENCES schedules(id),       -- NULL only for slice 6's ad-hoc runs
    workspace_id            UUID        NOT NULL REFERENCES workspaces(id),
    origin                  TEXT        NOT NULL,
    scheduled_at            TIMESTAMPTZ,                                 -- the UTC occurrence; NULL for a manual run
    reference_at            TIMESTAMPTZ NOT NULL,
    reference_timezone      TEXT        NOT NULL,
    admit_by                TIMESTAMPTZ NOT NULL,
    schedule_revision       INT,
    executor_id             TEXT        NOT NULL,
    payload_schema_version  INT         NOT NULL,
    payload_json            JSONB       NOT NULL,
    parameters_json         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    prepared_json           JSONB,                                       -- executor-owned snapshot (R5), set in the claim
    actor_user_id           UUID        NOT NULL REFERENCES users(id),  -- the system identity (R2)
    requested_by            UUID        REFERENCES users(id),           -- a manual run's person
    execution_id            UUID,                                        -- opaque reference, minted at the claim; no FK
    state                   TEXT        NOT NULL,
    reason                  TEXT,
    worker                  TEXT,
    attempts                INT         NOT NULL DEFAULT 0,
    idempotency_key         TEXT,
    idempotency_hash        TEXT,
    trail_seq               INT         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at              TIMESTAMPTZ,
    started_at              TIMESTAMPTZ,
    finished_at             TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedule_runs_origin CHECK (origin IN ('cron', 'catch_up', 'manual')),
    CONSTRAINT chk_schedule_runs_state CHECK (state IN (
        'queued', 'starting', 'running', 'succeeded', 'failed', 'cancelled', 'aborted',
        'unknown', 'not_started', 'skipped')),
    -- A cron or catch-up run IS an occurrence; a manual run is not one.
    CONSTRAINT chk_schedule_runs_occurrence CHECK ((origin = 'manual') = (scheduled_at IS NULL)),
    -- A claim records its execution reference in the same UPDATE (record §2.1): past `queued`,
    -- a run that ever reached `starting` carries one. (`not_started`/`skipped` may have none.)
    CONSTRAINT chk_schedule_runs_claimed CHECK (state NOT IN ('starting', 'running') OR execution_id IS NOT NULL),
    CONSTRAINT chk_schedule_runs_idempotency CHECK ((idempotency_key IS NULL) = (idempotency_hash IS NULL)),
    CONSTRAINT uq_schedule_runs_occurrence UNIQUE (schedule_id, scheduled_at)
);

-- L1: one manual run per (schedule, requester, Idempotency-Key).
CREATE UNIQUE INDEX uq_schedule_runs_manual_idempotency ON schedule_runs (schedule_id, requested_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- The durable overlap guard (record §3): at most one active run per schedule.
CREATE UNIQUE INDEX uq_schedule_runs_one_active ON schedule_runs (schedule_id)
    WHERE state IN ('queued', 'starting', 'running');
-- A schedule's history, newest first.
CREATE INDEX idx_schedule_runs_schedule ON schedule_runs (schedule_id, created_at DESC);
-- The reconciler's read: the runs whose execution it still watches.
CREATE INDEX idx_schedule_runs_open ON schedule_runs (updated_at) WHERE state IN ('starting', 'running', 'unknown');
-- The run of an execution (the merged Messages read, and "was this execution scheduled?").
CREATE INDEX idx_schedule_runs_execution ON schedule_runs (execution_id) WHERE execution_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. schedule_run_events (metadata-db §4.24) — append-only
-- ---------------------------------------------------------------------------
CREATE TABLE schedule_run_events (
    run_id      UUID        NOT NULL REFERENCES schedule_runs(id),
    seq         INT         NOT NULL,
    kind        TEXT        NOT NULL,
    reason      TEXT,
    at          TIMESTAMPTZ NOT NULL,
    worker      TEXT,
    details_json JSONB      NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (run_id, seq),
    CONSTRAINT chk_schedule_run_events_kind CHECK (kind IN (
        'recorded', 'capacity_retry', 'claimed', 'execution_started', 'not_started', 'skipped',
        'finished', 'unknown', 'updated_after_unknown', 'unblocked')),
    CONSTRAINT chk_schedule_run_events_seq CHECK (seq >= 1)
);

-- ---------------------------------------------------------------------------
-- 4. scheduled_tasks — db-scheduler 16.12.0's own DDL, verbatim (metadata-db §4.25)
-- ---------------------------------------------------------------------------
create table scheduled_tasks (
  task_name text not null,
  task_instance text not null,
  task_data bytea,
  execution_time timestamp with time zone not null,
  picked BOOLEAN not null,
  picked_by text,
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures INT,
  last_heartbeat timestamp with time zone,
  version BIGINT not null,
  priority SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx on scheduled_tasks (priority desc, execution_time asc);

-- ---------------------------------------------------------------------------
-- 5. pipeline_executions.triggered_via gains 'SCHEDULE' (metadata-db §4.6, enums.md §18)
-- ---------------------------------------------------------------------------
ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
    CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE', 'ENDPOINT', 'SCHEDULE'));
