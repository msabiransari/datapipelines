-- V22__lake_table_view_errors.sql
--
-- 109 §A — per-table view-creation outcomes on the dp-lake catalog (datasources.md §8C.2).
--
-- Until this migration a LAKE datasource's per-table `CREATE VIEW` statements rode ONE joined
-- `connectionInitSql`, so a single failing view (a bad prefix, a wrong format, a file that is
-- not Parquet) failed the whole pool build and made EVERY registered table unreachable. Views
-- are now created one at a time; a failing table's engine error is recorded HERE and the table
-- is skipped, so the connect succeeds with the surviving views and the detail page / the
-- lake-tables listing can say which table is broken and why.
--
--   * `last_error` is NULL when the table's view creation last SUCCEEDED — NULL is the healthy
--     spelling, so a column added to a previously-healthy registry needs no backfill. The text
--     is the engine's (or the SQL-emission boundary's) message, bounded to 2000 characters by
--     the writer — the same bound `ErrorCodeMapper.MAX_MESSAGE_CHARS` puts on reflected driver
--     text everywhere else.
--   * `last_error_at` is when that error was last NEWLY recorded. Recording is
--     transition-only — an unchanged error text writes nothing — so the timestamp reads as
--     "broken since", not "last observed". It is NULL exactly when `last_error` is NULL.
--
-- Both columns are maintained by `LakeTableRepository.recordViewOutcome` alone, from the pool
-- factory's per-connection view application; a successful re-creation after a failure clears
-- both in the same UPDATE.

ALTER TABLE lake_tables
    ADD COLUMN last_error    TEXT,
    ADD COLUMN last_error_at TIMESTAMPTZ;
