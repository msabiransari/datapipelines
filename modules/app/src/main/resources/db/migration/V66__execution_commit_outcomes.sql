-- =============================================================================
-- V66 — execution commit outcomes (#1035, pipeline-write-transactions §3.5, §4)
--
-- A family that shares one local write transaction settles at ONE moment: the root's commit. Four
-- facts the history must keep honest around that moment:
--
-- 1. status gains `OUTCOME_UNKNOWN` — terminal, for an execution whose commit was ATTEMPTED and whose
--    acknowledgement was lost (a refused/unanswered commit, or the instance died inside the window).
--    The database may or may not hold the writes; the engine never replays them to find out.
--
-- 2. `commit_attempted_at` — stamped by an autocommit statement on THIS table BEFORE the customer commit
--    is called (no metadata transaction is ever held across customer I/O). `commit_confirmed_at` —
--    stamped after the driver confirmed the commit. The stale sweep reads the pair: confirmed → SUCCESS,
--    attempted-but-unconfirmed → OUTCOME_UNKNOWN, neither → ABORTED (`instance_lost`, nothing was ever
--    attempted, so the abort is a fact). NULL on every row written before this migration and on every
--    execution that enrolled no transaction.
--
-- 3. `settlement_pending` — a CHILD of an enrolled family finished its work but its writes are not
--    durable until the root settles. Its row stays `RUNNING` (so every reader — history, cursor, MCP,
--    the stream — withholds it) with this flag set; the root's terminal write settles the whole family
--    at once and the child inherits the root's final status. The stale sweep never reaps a pending row on
--    its own heartbeat.
--
-- 4. `result_unavailable` — the commit is durably confirmed (history says SUCCESS) but the caller result
--    could not be delivered (instance lost after the confirmation, result store gone). Reported separately
--    from the status: a transport failure never changes an execution's status.
--
-- Down path (documented, not shipped — Flyway runs forward only): docs/metadata-db.md §7.5 "Rolling back
-- V66", behind the repeatable downgrade barrier db/downgrade-barrier/R__execution_commit_outcome_downgrade_barrier.sql
-- (a binary older than V66 cannot read OUTCOME_UNKNOWN and would sweep an attempted commit to ABORTED).

ALTER TABLE pipeline_executions DROP CONSTRAINT chk_status;
ALTER TABLE pipeline_executions ADD CONSTRAINT chk_status
    CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'ABORTED', 'OUTCOME_UNKNOWN'));

ALTER TABLE pipeline_executions ADD COLUMN commit_attempted_at  TIMESTAMPTZ NULL;
ALTER TABLE pipeline_executions ADD COLUMN commit_confirmed_at  TIMESTAMPTZ NULL;
ALTER TABLE pipeline_executions ADD COLUMN settlement_pending   BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE pipeline_executions ADD COLUMN result_unavailable   BOOLEAN     NOT NULL DEFAULT FALSE;

-- The family settlement's access path: pending rows only, by their root.
CREATE INDEX idx_executions_settlement_pending ON pipeline_executions(root_execution_id)
    WHERE settlement_pending;
