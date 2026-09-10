-- ---------------------------------------------------------------------------
-- V21__execution_heartbeat.sql (108 §D, metadata-db §8.3)
--
-- The crash sweep's only signal was `started_at`, so a row an instance died holding
-- stayed RUNNING for `stale-timeout-minutes` — sixty of them by default. An agent in
-- T199 waited the full hour not knowing its run was already dead, which is the whole
-- failure: not that the row was wrong, but that nothing said so for an hour.
--
-- The owning instance now stamps `heartbeat_at` every `heartbeat-seconds` (15) while it
-- runs, and the sweep reaps a RUNNING row whose stamp is older than three beats (~45 s).
--
-- The column is NULLABLE and there is no backfill, deliberately. A row written by a
-- pre-V21 instance — or by one that died before its first beat — has no stamp, and
-- reaping it after 45 s on the strength of a column it never knew about would abort a
-- live execution. Those rows keep the sixty-minute age condition, which the sweep KEEPS
-- as a backstop for exactly this reason. The two conditions are OR'd; neither replaces
-- the other.
-- ---------------------------------------------------------------------------

ALTER TABLE pipeline_executions ADD COLUMN heartbeat_at TIMESTAMPTZ NULL;

-- The sweep's predicate is `status = 'RUNNING' AND (heartbeat_at < cutoff OR ...)`, and
-- the existing partial index (idx_executions_status_running, V1) is on `started_at`.
-- This one carries the heartbeat under the same partial condition, so the ~45-second tick
-- reads an index the size of the live set rather than the table.
CREATE INDEX idx_executions_heartbeat ON pipeline_executions(heartbeat_at)
    WHERE status = 'RUNNING';
