-- V15__lake_tables.sql
--
-- The dp-lake catalog: which tables a LAKE datasource serves (datasources.md §4.1, the
-- 2026-09-07 lake-datasource design record §2, round 089 section A).
--
-- A LAKE datasource (V14) reads object storage in place — Parquet and Iceberg on S3 — but the
-- engine cannot LIST a bucket: introspection needs a catalog, and this table is it. A LAKE
-- datasource's tables are exactly these rows, registered through
-- `LakeTableRegistryService` (REST `POST /api/v1/datasources/{name}/tables`, the
-- `lake_tables_*` MCP tools); nothing writes here directly.
--
-- Column notes:
--
--   * `datasource_id` is TEXT referencing `datasources(name)` — the datasource table's primary
--     key IS its name (metadata-db §4.10: PK, GCM AAD anchor, cross-env contract), and it has no
--     surrogate id to reference. The column keeps the design record's name; what it holds is the
--     datasource NAME. No ON DELETE action is needed: datasources are soft-deleted
--     (`is_deleted`), the row stays, and a soft-deleted datasource's tables stay registered for
--     the same reason the row does — a name is never reused.
--   * `namespace` is 087's `List<String>` (V14's NamespaceShape world) as a Postgres TEXT[]:
--     `{"nyc","mobility"}` for the table a template reads as `nyc.mobility.hvfhv_zone_day`. It is
--     NOT NULL — the array itself is always present, with one to nine segments. The segment
--     grammar (`[a-z0-9][a-z0-9_.-]{0,63}`, the pipeline/template §4.1 production, minus `.`
--     inside a segment) is the application validator's job; a CHECK over array elements is
--     expressible but unreadable, and this table's only writer is the validating service.
--   * `format` is CHECKed because a bad value here generates bad SQL later: phase B's views are
--     `read_parquet(...)` for one value and `iceberg_scan(...)` for the other, and the database
--     is the last place to catch a third (the chk_datasource_dialect precedent, metadata-db
--     §4.10).
--   * `location` is the object-storage address — `s3://bucket/prefix/` (Parquet: a directory or
--     glob; Iceberg: the table root holding `metadata/`) or a `file://` path for an on-prem
--     volume. The scheme allowlist and the injection refusal (no quotes, no backslash, no
--     control characters — the value is later interpolated into CREATE VIEW statements) are the
--     application validator's, and they are TOTAL: there is no escaping rule, because a value
--     that needs one is refused instead.
--   * `partition_column` is nullable: an unpartitioned table (the sample set's `hvfhv_zone_day`)
--     has none, and NULL is the truthful spelling of that.
--   * `registered_by` / `registered_at` follow the house `created_by`/`created_at` shape:
--     a real FK to `users(id)`, TIMESTAMPTZ, DB-defaulted.
--
-- `uq_lake_tables_datasource_namespace_name` is the table's one rule beyond the CHECK: a table
-- is its (datasource, namespace, name) triple, registered once. It is NAMED so the service maps
-- its violation to the catalogued `datasource.lake_table_duplicate` (the
-- `uq_pipelines_workspace_name` precedent, §4.4) — and its index doubles as the access path for
-- the registry's hot read, "list the tables of one datasource", so no separate index on
-- `datasource_id` is created (metadata-db §5's duplicate-index rule).

CREATE TABLE lake_tables (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    datasource_id    TEXT        NOT NULL REFERENCES datasources(name),
    namespace        TEXT[]      NOT NULL,
    name             TEXT        NOT NULL,
    format           TEXT        NOT NULL,
    location         TEXT        NOT NULL,
    partition_column TEXT,
    registered_by    UUID        NOT NULL REFERENCES users(id),
    registered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_lake_table_format CHECK (format IN ('parquet', 'iceberg')),
    CONSTRAINT uq_lake_tables_datasource_namespace_name UNIQUE (datasource_id, namespace, name)
);
