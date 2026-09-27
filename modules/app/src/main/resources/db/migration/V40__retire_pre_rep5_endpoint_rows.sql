-- =============================================================================
-- V40 — Retire published_endpoints rows saved before R-EP5 (#274)
--
-- Authority: docs/superpowers/specs/2026-09-05-published-endpoints-design.md
-- (R-EP5), metadata-db.md §4.13.
--
-- R-EP5 (2026-09-19, lane 172 / #172) made the served shape
-- /api/<category>/<version>/<path…> — at least three segments — and
-- EndpointPath.parse refuses anything shorter. A row written before that day
-- under the then-legal two-segment grammar parses against nothing: the row
-- mapper threw inside the demo seeder's conflict check and the application
-- refused to boot (#274). The rule this migration encodes is the record's own:
-- a stored row that fails a later grammar is retired, never fatal.
--
-- Every row whose stored path has fewer than three segments is DISABLED and
-- records why in `retired_reason` (NULL = a normal row; the column is the
-- reason a NULLable TEXT adds nothing to a normal row's meaning). Rows are
-- never deleted — the workspace's listing shows them, flagged with the reason,
-- and unpublishing one is the operator's deliberate act.
--
-- This is the database-side half of a fail-closed pair: the repository maps a
-- row whose path no longer parses as legacy and keeps it out of the serve
-- registry and the conflict check regardless of `is_enabled`, so a database
-- this migration has not reached yet still boots (and the seeder still
-- publishes); the migration is what makes the row read back as an ordinary
-- disabled row everywhere else.
--
-- DOWN PATH (manual — Flyway runs forward only):
--   UPDATE published_endpoints SET is_enabled = TRUE WHERE retired_reason = 'pre-R-EP5 path';
--   ALTER TABLE published_endpoints DROP COLUMN retired_reason;
--   The UPDATE re-enables EVERY row the reason names. V40 cannot distinguish
--   "disabled by V40" from "disabled by an operator before V40" — the reason
--   column did not exist to record the difference — so the down path restores
--   the state the column found, in which every such row was enabled (that is
--   exactly why it could break a later boot). The column drop is lossless.
-- =============================================================================

ALTER TABLE published_endpoints ADD COLUMN retired_reason TEXT NULL;

UPDATE published_endpoints
   SET is_enabled = FALSE,
       retired_reason = 'pre-R-EP5 path',
       updated_at = NOW()
 WHERE array_length(string_to_array(trim(both '/' from path_pattern), '/'), 1) < 3;
