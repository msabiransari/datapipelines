-- =============================================================================
-- V40 — the execution's own timing on the run row (#258, scheduler follow-ups)
--
-- A run's finished_at is the RECONCILER's stamp (when the terminal state was
-- recorded — up to one tick-interval after the execution actually ended), so a
-- duration computed from the run's own stamps over-states what the execution
-- took. The reconciler already reads the execution row; these columns carry the
-- execution's own started_at/completed_at onto the run, copied by the same
-- transition that records the terminal state (RunLedger -> ScheduleRunRepository
-- .transition), so rest-api §20.10–§20.12 answer "how long did it take" in one
-- read for every member who may read the schedule (no per-run execution read,
-- which a reader without execution.read could never make).
--
-- NULL until a terminal carrying the execution's timing is recorded: runs that
-- never launched, and runs finished before this migration, answer null — the
-- reader falls back to the execution read as before.
--
-- DOWN PATH (manual; additive columns only):
--   ALTER TABLE schedule_runs DROP COLUMN execution_started_at;
--   ALTER TABLE schedule_runs DROP COLUMN execution_completed_at;
--   DELETE FROM flyway_schema_history WHERE version = '40';
-- =============================================================================

ALTER TABLE schedule_runs ADD COLUMN execution_started_at   TIMESTAMPTZ;
ALTER TABLE schedule_runs ADD COLUMN execution_completed_at TIMESTAMPTZ;
