-- =============================================================================
-- V28 — pipeline_check_runs: the server-side record of every release check run
-- (140)
--
-- Authority: the release-check design recorded in pipeline-contract.md §3.3 /
-- §12.12; documented in metadata-db.md §4.20.
--
-- One row per CHECK PER RUN — append-only, never updated, never cleaned up: a
-- check run is an observation the SERVER produced (there is deliberately no
-- field on a pipeline's `checks[]` declaration a caller could carry an observed
-- value in on), and the history of those observations is what a release
-- refusal, the UI's latest-run list and an operator all read. `verdict` is
-- three-valued by design: `pass` / `fail` are clean comparisons, `error` is
-- "no verdict could be formed" (datasource unreachable, statement refused, a
-- shape the expectation cannot compare, parameters that did not bind) — the
-- truth, recorded, never silently a `fail`.
--
-- `pipeline_id` references pipelines(id) plainly, NOT the composite
-- (pipeline_id, version) FK pipeline_executions carries: a check run is a
-- fact about a version NUMBER, and a DRAFT version row is deleted by a purge
-- — a composite FK would make purging a checked draft refuse for history it
-- should keep, not protect integrity it needs. `ran_by` is nullable with no
-- FK (the audit_log precedent): the run outlives the user.
-- =============================================================================

CREATE TABLE pipeline_check_runs (
    id              UUID        PRIMARY KEY,
    pipeline_id     UUID        NOT NULL REFERENCES pipelines(id),
    version         INT         NOT NULL,
    check_id        TEXT        NOT NULL,
    ran_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ran_by          UUID,
    via             TEXT        NOT NULL,
    parameters_json JSONB       NOT NULL DEFAULT '{}',
    observed_json   JSONB,
    verdict         TEXT        NOT NULL,
    message         TEXT,
    correlation_id  TEXT,
    duration_ms     BIGINT,
    CONSTRAINT chk_pipeline_check_runs_via CHECK (via IN ('mcp', 'rest', 'ui', 'release')),
    CONSTRAINT chk_pipeline_check_runs_verdict CHECK (verdict IN ('pass', 'fail', 'error'))
);

-- The read every surface makes: "the latest run of each check of this version".
CREATE INDEX idx_pipeline_check_runs_latest
    ON pipeline_check_runs (pipeline_id, version, check_id, ran_at DESC);
