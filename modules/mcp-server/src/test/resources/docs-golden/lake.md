
# dp-lake — querying object storage in place

Open when the data is Parquet or Iceberg on S3 rather than in a database.

Part of the served manual — the operating core is the document `core`, and every document
answers to `docs_get` by its name here.

## dp-lake — register your bucket, register tables, ask

A **LAKE** datasource reads Parquet and Apache Iceberg tables on S3 or S3-compatible object
storage **in place**, without a persistent bulk load into a warehouse, and it is **read-only**.
DuckDB is the engine; the catalog is the server's own registry (dp-catalog): a LAKE
datasource exposes the tables you register. Querying still transfers data to the engine.

The workflow is three steps:

1. **Register the bucket — a HUMAN does this, not you.** Registering a datasource means
   handing over a live credential, and no credential travels through an agent (see
   `datasources-connecting`): there is no `datasources_create` tool. Ask the person to add
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

**One engine per datasource, warm across connections — but not across changes.** A LAKE
datasource's pool runs ONE embedded engine that every pooled connection shares: the engine's
memory and thread limits are one budget for the whole datasource (more connections do not add
capacity, they share it), and its cache of the remote objects it has scanned is kept across
the pool's routine connection replacement. Two consequences for how you read a timing: a
probe that ran fast may be fast because an earlier query already pulled those objects —
never infer the steady-state cost of a cold read from one warm measurement, and expect the
FIRST scan after a datasource edit or a table registration to be cold again, because those
rebuild the pool and start a fresh engine. Pruning and column selection (below) can reduce
cold-read work; caching may reduce repeat transfers, but one warm timing is not a cold-read budget.

**Bare vs qualified table names.** When ALL of the datasource's registered tables share
exactly ONE namespace, the server sets the search path at connect, so a template reads
`FROM events_by_day` bare. With several namespaces there is no default —
use the full three-part name, `FROM acme.analytics.events_by_day`.

**Iceberg: register the metadata FILE, not the table root.** DuckDB 1.5.5 cannot
`iceberg_scan` a pyiceberg table by its root (its version-hint filenames never match the
`%05d-<uuid>.metadata.json` files pyiceberg writes), so `location` is the table's CURRENT
metadata file — `s3://bucket/table/metadata/00042-<uuid>.metadata.json`. A table that still
receives commits gets a new metadata file per commit: re-register to follow it.

**Select fewer columns and prune where the question permits.** The table listing and
`datasources_get_table_stats` expose a registered `partition_column` (also an index entry
of kind `partition` in stats). `null` means no registered partition key; it does not tell
you the file count, prove the physical layout, or rule out data skipping.

- A compatible predicate on a Hive partition key can exclude files in other partitions.
  One partition may contain many files; one Parquet file may contain many row groups.
- Parquet column projection avoids reading unneeded column data. Predicate pushdown lets
  the reader use statistics to skip row groups when their bounds exclude a match, even
  without Hive partitioning. Layout and selectivity determine how much work this saves.
- Remote Parquet reads can fetch metadata and selected byte ranges. This is not a general
  row-level index lookup: metadata requests and relevant data still have to be read. A
  pushed `READ_PARQUET` filter alone proves neither skipped files nor bytes saved.

Inspect available file/partition counts and timings; compare cold and warm reads before
claiming an improvement. Sorting, repartitioning and file maintenance belong to the writer
or operator; current LAKE queries cannot change stored layout. A tempdb `CREATE INDEX`
indexes only staged data, after it has been transferred.

S3 bills storage, requests and applicable transfer; DuckDB also needs compute. S3 itself
does not impose a query charge per byte scanned. Same-region transfer from S3 to AWS
services is generally free, while other transfer paths and network services can cost money.
Measure placement and requests as well as bytes; do not call every S3 read paid egress.

Use a bound partition predicate when it matches the requested window, and preserve the
keys and aggregate components needed downstream (`pipelines-authoring` §4):

```sql
SELECT event_date, region_id, SUM(event_count) AS events
FROM events_by_day
WHERE event_date BETWEEN :start_date AND :end_date
GROUP BY event_date, region_id
```

Sources: [Hive partitioning](https://duckdb.org/docs/current/data/partitioning/hive_partitioning),
[Parquet projection and filter pushdown](https://duckdb.org/docs/current/data/parquet/overview),
[partial remote reads](https://duckdb.org/docs/current/core_extensions/httpfs/https),
[S3 pricing](https://aws.amazon.com/s3/pricing/).
