# dp-lake — querying object storage in place

Open when the data is Parquet or Iceberg on S3 rather than in a database.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

## dp-lake — register your bucket, register tables, ask

A **LAKE** datasource reads Parquet and Apache Iceberg tables on S3 or S3-compatible object
storage **in place** — no warehouse, no load step, nothing copied — and it is **read-only**.
DuckDB is the engine; the catalog is the server's own registry (dp-catalog), because the
engine cannot list a bucket: a LAKE datasource's tables are exactly the rows you register.

The workflow is three steps:

1. **Register the bucket — a HUMAN does this, not you.** Registering a datasource means
   handing over a live credential, and no credential travels through an agent (see
   `references/connecting.md`): there is no `datasources_create` tool. Ask the person to add
   it in the UI (or over REST) with `dialect: "LAKE"`,
   `jdbc_url: "jdbc:duckdb::memory:"`, and `properties.dialect` naming how the data is
   addressed — `catalog.kind: "s3"` + `region` for AWS, plus `endpoint` (and
   `url_style: "path"`) for an S3-compatible store like MinIO. Credentials:
   `credential: {"kind": "none"}` is the IAM credential chain; `{"kind": "password",
   "username": "<key-id>", "secret": "<secret>"}` is an explicit key pair; and a PUBLIC
   bucket adds `unsigned: "true"` — NO S3 secret at all, because the credential chain
   validates at create time and fails on a credentials-free box. Then confirm it yourself
   with `datasources_test`.
2. **Register the tables.** `lake_tables_register` for one (`namespace`, `table`, `format`,
   `location`, optional `partition_column`), or `lake_tables_import` for a manifest's
   `tables[]` — inline, or a `manifest_url` fetched server-side from the datasource's OWN
   bucket/endpoint only (arbitrary URLs are refused). Import is idempotent; re-running it is
   safe.
3. **Ask.** `datasources_get_tables` lists the registered tables (reported as type `VIEW`,
   with the format in remarks) — then author a template with `dialect: "LAKE"` and a normal
   pipeline over it. A lake node stages into tempdb and joins Postgres/MySQL/SQLite nodes in
   the same pipeline like any other source.

**Bare vs qualified table names.** When ALL of the datasource's registered tables share
exactly ONE namespace, the server sets the search path at connect, so a template reads
`FROM hvfhv_zone_day` bare (the demo's choice). With several namespaces there is no default —
use the full three-part name, `FROM nyc.mobility.hvfhv_zone_day`.

**Iceberg: register the metadata FILE, not the table root.** DuckDB 1.5.5 cannot
`iceberg_scan` a pyiceberg table by its root (its version-hint filenames never match the
`%05d-<uuid>.metadata.json` files pyiceberg writes), so `location` is the table's CURRENT
metadata file — `s3://bucket/table/metadata/00042-<uuid>.metadata.json`. A table that still
receives commits gets a new metadata file per commit: re-register to follow it.

**Every query prunes on the partition column — egress is real.** A lake table's bytes cross
the network from S3 when the engine scans them, and you pay for what you scan: a predicate on
the partition column (`WHERE pickup_date = DATE '2024-06-01'` or
`WHERE pickup_date BETWEEN :start_date AND :end_date`) makes the engine read only the
matching partitions, while an unfiltered `SELECT *` over a partitioned table downloads every
partition. Write the predicate into the template by default, not as an afterthought:

```sql
SELECT pickup_date, pu_location_id, SUM(trip_count) AS trips
FROM hvfhv_zone_day
WHERE pickup_date BETWEEN :start_date AND :end_date
GROUP BY pickup_date, pu_location_id
```
