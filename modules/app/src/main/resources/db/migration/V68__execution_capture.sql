-- =============================================================================
-- V68 — runtime capture of published mutations (#1043, published-mutation-apis design record §6, §7)
--
-- Detailed capture is OFF by default. A workspace administrator opens a time-limited capture SESSION for one
-- published route entity and one method, optionally filtered by an exact request correlation id; the
-- decision whether a new execution is captured is taken ONCE, at admission, and stored on its reservation.
-- Captured values live in their own private tables with their own expiry, apart from every ordinary
-- projection (execution row, events, logs, polling).
--
-- 1. `execution_capture_sessions` — one row per opened window. The half-open window is
--    [started_at, COALESCE(stopped_at, deadline_at)). `deadline_at` is fixed at start from
--    `datapipelines.endpoints.capture-window-seconds`, so a later configuration change cannot extend it.
--    The partial UNIQUE index admits ONE open session per (workspace, route entity, method); a session past
--    its deadline is closed (`end_reason = 'EXPIRED'`, `stopped_at = deadline_at`) by the next start in the
--    same transaction, so an expired row never blocks a new window. The route ENTITY id, not its URL, keys
--    the session: a route deleted and recreated under the same text is a new id and inherits nothing. No
--    foreign key to published_endpoints (deleting a route must not erase the record of who captured it).
--
-- 2. `published_mutation_reservations.capture_session_id` / `capture_expires_at` — the admission decision.
--    The reserving INSERT reads the matching open session under FOR SHARE in the same statement, so a stop
--    (an UPDATE of that session row) is ordered strictly before or after it: admitted before the stop is
--    captured, admitted after is not. The winning reservation owns the decision; every retry reads it back.
--    `capture_expires_at` = admission + `capture-detail-retention-hours`, fixed at admission.
--
-- 3. `execution_capture_values` — one row per declared value-mode output of a captured execution, keyed by
--    (execution, node, declared output). Either the JSON text of the value, or an OMITTED marker with its
--    reason and no preview. `expires_at` is copied from the reservation; reads refuse at or after it and the
--    hourly retention tick deletes it physically in bounded batches. The INSERT is conditional on the
--    reservation having been captured and not yet expired, so the store itself refuses a late or an
--    uncaptured write.
--
-- 4. `execution_capture_runs` — the closing summary of a captured run (counts, bytes, store failures), so
--    the diagnostic view can say whether the capture is complete. Same expiry and purge as the values.
--
-- Values and summaries cascade with their reservation (a purged credential or workspace takes them).
--
-- Down path (documented, not shipped — Flyway runs forward only): docs/metadata-db.md §7.7. A binary older
-- than V68 never reads these tables or columns: it admits every mutation uncaptured (its reservation INSERT
-- names no capture column, which default to NULL) and shows no capture console. No barrier needed.

CREATE TABLE execution_capture_sessions (
    id              UUID        PRIMARY KEY,
    workspace_id    UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    endpoint_id     UUID        NOT NULL,
    method          TEXT        NOT NULL,
    path_pattern    TEXT        NOT NULL,
    correlation_id  UUID        NULL,
    started_by      UUID        NOT NULL REFERENCES users(id),
    started_at      TIMESTAMPTZ NOT NULL,
    deadline_at     TIMESTAMPTZ NOT NULL,
    stopped_at      TIMESTAMPTZ NULL,
    stopped_by      UUID        NULL REFERENCES users(id),
    end_reason      TEXT        NULL,
    CONSTRAINT chk_execution_capture_sessions_method CHECK (method IN ('POST', 'PUT', 'DELETE')),
    CONSTRAINT chk_execution_capture_sessions_window CHECK (deadline_at > started_at),
    CONSTRAINT chk_execution_capture_sessions_end CHECK (
        (stopped_at IS NULL AND end_reason IS NULL AND stopped_by IS NULL)
        OR (stopped_at IS NOT NULL AND end_reason IN ('STOPPED', 'EXPIRED')
            AND stopped_at >= started_at AND stopped_at <= deadline_at)
    )
);

-- One open window per (workspace, route entity, method): a second start is refused by this index.
CREATE UNIQUE INDEX uq_execution_capture_sessions_open
    ON execution_capture_sessions (workspace_id, endpoint_id, method) WHERE stopped_at IS NULL;

-- A route's capture history, newest first (the console's list).
CREATE INDEX idx_execution_capture_sessions_endpoint
    ON execution_capture_sessions (workspace_id, endpoint_id, started_at DESC);

ALTER TABLE published_mutation_reservations
    ADD COLUMN capture_session_id UUID        NULL REFERENCES execution_capture_sessions(id),
    ADD COLUMN capture_expires_at TIMESTAMPTZ NULL,
    ADD CONSTRAINT chk_published_mutation_reservations_capture CHECK (
        (capture_session_id IS NULL) = (capture_expires_at IS NULL)
    );

-- The session's captured executions (the console's "runs captured" count) without a sequential scan.
CREATE INDEX idx_published_mutation_reservations_capture
    ON published_mutation_reservations (capture_session_id) WHERE capture_session_id IS NOT NULL;

CREATE TABLE execution_capture_values (
    execution_id    UUID        NOT NULL REFERENCES published_mutation_reservations(execution_id) ON DELETE CASCADE,
    node_id         TEXT        NOT NULL,
    output_key      TEXT        NOT NULL,
    workspace_id    UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    state           TEXT        NOT NULL,
    omitted_reason  TEXT        NULL,
    value_text      TEXT        NULL,
    value_bytes     BIGINT      NULL,
    recorded_at     TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (execution_id, node_id, output_key),
    CONSTRAINT chk_execution_capture_values_state CHECK (
        (state = 'CAPTURED' AND value_text IS NOT NULL AND omitted_reason IS NULL AND value_bytes IS NOT NULL)
        OR (state = 'OMITTED' AND value_text IS NULL
            AND omitted_reason IN ('byte_budget_exhausted', 'unserializable'))
    )
);

-- The retention tick's batch: the oldest expired rows first.
CREATE INDEX idx_execution_capture_values_expires ON execution_capture_values (expires_at);

CREATE TABLE execution_capture_runs (
    execution_id     UUID        PRIMARY KEY REFERENCES published_mutation_reservations(execution_id) ON DELETE CASCADE,
    workspace_id     UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    session_id       UUID        NOT NULL REFERENCES execution_capture_sessions(id),
    values_captured  INT         NOT NULL,
    values_omitted   INT         NOT NULL,
    write_failures   INT         NOT NULL,
    bytes_used       BIGINT      NOT NULL,
    sealed_at        TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_execution_capture_runs_counts CHECK (
        values_captured >= 0 AND values_omitted >= 0 AND write_failures >= 0 AND bytes_used >= 0
    )
);

CREATE INDEX idx_execution_capture_runs_expires ON execution_capture_runs (expires_at);
