-- =============================================================================
-- DOWNGRADE BARRIER for V66 (#1035, pipeline-write-transactions design record §4, §7)
--
-- A binary that predates V66 cannot read what V66 lets the engine write: `OUTCOME_UNKNOWN` is a status it does
-- not parse (history, the stream and the scheduler's outcome read would fail on the row), and its stale sweep
-- labels a RUNNING row ABORTED `instance_lost` with no knowledge of `commit_attempted_at` — the exact label the
-- design forbids on an execution whose customer commit may have happened. A jar already shipped cannot be
-- changed, so this migration is the refusal.
--
-- HOW: a REPEATABLE migration, the mechanism of the V65 barrier (R__published_endpoint_method_downgrade_barrier):
-- a Flyway that has applied it but does not RESOLVE it (every binary built before it existed) fails validation
-- with "Detected applied migration not resolved locally" and the application does not start. The current
-- binary resolves it, so it starts.
--
-- ROLLBACK is therefore a deliberate operator procedure (docs/metadata-db.md, "Rolling back V66"): drain
-- executions, run the documented down path — which REFUSES while any OUTCOME_UNKNOWN row or unsettled commit
-- attempt exists, so no unknown outcome is ever rewritten into a label an older reader invents — delete this
-- migration's and V66's rows from flyway_schema_history, then start the previous binary. The statement below is
-- only a marker visible in the schema; it is idempotent, and changing it re-runs it.
-- =============================================================================

COMMENT ON COLUMN pipeline_executions.commit_attempted_at IS
    'When the customer commit was ATTEMPTED (#1035, V66), stamped before the commit is called. A binary built before V66 must not run on this schema: see the downgrade barrier migration and docs/metadata-db.md "Rolling back V66".';
