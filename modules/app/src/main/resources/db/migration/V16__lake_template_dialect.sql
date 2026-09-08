-- V16__lake_template_dialect.sql
--
-- `LAKE` joins the TEMPLATE dialect set, one round after it joined the datasource one
-- (089 §F — found by the MinIO suite going red: a `dialect: LAKE` template insert violates
-- `template_versions.chk_dialect`, which V8 last rewrote and 087's V14 did not touch — V14's
-- subject was `datasources.chk_datasource_dialect`).
--
-- A LAKE template is how a DQL node reads a lake datasource's per-table views (089 §B): the
-- 088 showcase content (`nyc/lake/rideshare_zone_day.sql`) declares exactly one, so the demo
-- seeding path needs this as much as the §F suites do. The dialect validation is
-- application-level too (TemplateValidator against the Dialect enum, which 087 made total);
-- this CHECK is the database twin of that rule, metadata-db §4.10's two-layer discipline —
-- same shape as V14, whose wording this migration deliberately mirrors.
--
-- Postgres has no ALTER for a CHECK's expression, so the constraint is dropped and recreated —
-- additive in effect: every value it admitted before, it admits now. `chk_type_dialect` is
-- untouched: a LAKE template is still type `sql` with a non-null dialect.
--
-- No data changes. No existing row can be a LAKE (the CHECK has refused the value since V1),
-- so there is nothing to backfill and nothing to migrate.

ALTER TABLE template_versions DROP CONSTRAINT chk_dialect;

ALTER TABLE template_versions
    ADD CONSTRAINT chk_dialect CHECK (
        dialect IS NULL OR dialect IN ('POSTGRES', 'ORACLE', 'MSSQL', 'MYSQL', 'H2', 'DUCKDB', 'SQLITE', 'LAKE')
    );
