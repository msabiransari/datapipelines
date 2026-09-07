-- V14__lake_dialect.sql
--
-- `LAKE` joins the datasource dialect set (datasources.md §4.1, enums.md §5, round 087).
--
-- A lake datasource reads object storage in place — Parquet and Iceberg on S3 — with DuckDB as
-- the engine. It is a DISTINCT dialect rather than a mode on `DUCKDB` because the two need
-- opposite §5.6 postures: the embedded DuckDB adapter locks `enable_external_access = false`
-- (no filesystem, no network — the control that stops author SQL loading native code inside the
-- app's own process), and a lake cannot read S3 with that lock on. Making the posture a function
-- of ROW DATA is exactly what §5.6's enum-total refusal lookup exists to prevent, so it is a
-- function of the dialect instead.
--
-- The only schema consequence is this CHECK. `chk_datasource_dialect` duplicates the
-- application-level validation on purpose (metadata-db §4.10): a bad dialect reaching this table
-- would break every pipeline referencing the datasource, and the database is the last place to
-- catch it. Postgres has no ALTER for a CHECK's expression, so the constraint is dropped and
-- recreated — additive in effect: every value it admitted before, it admits now.
--
-- No data changes. No existing row can be a LAKE (the value did not exist), so there is nothing
-- to backfill and nothing to migrate.

ALTER TABLE datasources DROP CONSTRAINT chk_datasource_dialect;

ALTER TABLE datasources
    ADD CONSTRAINT chk_datasource_dialect CHECK (
        dialect IN ('POSTGRES', 'ORACLE', 'MSSQL', 'MYSQL', 'H2', 'DUCKDB', 'SQLITE', 'LAKE')
    );
