# Draft: A lightweight writable lake for Datapipelines

**Status:** design (not yet normative); conversation and research checkpoint, not an implementation plan.
**Date:** 2026-09-22; discussion update 2026-09-24.
**Next step:** validate a small, reliable workflow and settle the open write/recovery contracts before implementation.
**Evidence:** official documentation consulted during the discussions; initial repository contracts inspected at `09b0e715`, skill update based on `1aac294e`. No lake prototype, benchmarks or recovery tests were run.
**Related work:** [#219](https://github.com/msabiransari/datapipelines/issues/219) tracks the current skill correction and this draft update, not implementation of writable dp-lake.
**Distribution:** contributor design material under `docs/superpowers/specs/`; not part of the product documentation packaging allowlist.

This document preserves the substantive discussion, including corrections and unresolved
choices. Owner direction, externally documented facts and assistant recommendations are
separated below. A recommendation is not an approved decision. This draft does not amend
the current pipeline, datasource, staging or authorization contracts.

## 1. Owner direction and motivation

Muhammad wants an open-source lake capability for small and medium organizations that
cannot, and should not need to, spend heavily on lake infrastructure and compute. There
is no customer at present. The purpose is a lightweight layer that makes the plumbing
easy and works naturally within the Datapipelines ecosystem.

The intended starting user has data in existing databases and no lake. They should be
able to configure object storage and move useful data into analytical tables through
Datapipelines. Existing open-source technology should supply storage/query fundamentals;
Datapipelines should integrate those fundamentals rather than recreate them.

Direction explicitly expressed or accepted by the owner:

- Keep the system simple but powerful, and reuse the existing pipeline architecture.
- Integrate with Datapipelines features, including scheduling, dashboards, parameters and
  transformers as those capabilities develop. This is not a claim they are all shipped.
- Make the mapping between a table and its S3 location understandable and visible.
- Investigate whether namespaces or similar abstractions are actually necessary.
- Investigate whether DuckLake removes the need for Iceberg in the new offering.
- Research reading and writing compute requirements, including RAM and fast scratch disk.
- Offer DuckDB as a staging choice alongside H2 for large-data use cases.
- Treat lake writing as the final data-writing step in the envisioned pipeline workflow.
- Consider read/write support through the dp-lake dialect instead of a separate node type.
- Keep writable-lake implementation/design finalization pending further learning and validation.

The earlier assistant framing around customers and a separate managed commercial offering
was corrected by the owner. In this document, “managed tables” means tables whose lifecycle
Datapipelines helps operate; it does not imply a hosted service or a pricing model.

### 1.1 Workload and product direction clarified on 2026-09-24

Datapipelines builds analytical datasets in steps: filter and aggregate data using each
source's engine, combine manageable intermediate results, and deliver reports, dashboards
or APIs. dp-lake provides an economical home for analytical data that organizations want
to retain and maintain themselves. This is the intended product direction, not a claim
that every part of that workflow ships today.

The target includes organizations with substantial historical data that usually query a
bounded period or business slice. A typical workflow aggregates facts in one database or
lake query, stages those summaries, then joins data from other databases and rolls up again.
Reading millions of rows at the source does not imply transferring millions into tempdb
or returning them to the caller. Source-side reduction must preserve downstream keys,
average components, distinctness, join cardinality and snapshot semantics; the current
[authoring playbook](../../../.agents/skills/datapipelines/references/authoring-playbook.md)
owns those correctness rules.

Scheduled ingestion from files and databases should yield a usable table in a supported
destination database, with dp-lake as one destination. “Any database” is an aspiration,
not a compatibility guarantee. Large ingestion transfers need bounded streaming and
reliable commit/retry behavior; they are distinct from returning small analytical results.
Reuse shared scheduling and consumption surfaces as they develop.

Capacity should be expressed in selected columns/bytes, working-set size, intermediate
rows and width, join/group cardinality, and concurrent jobs. Total historical row count
alone is insufficient. A few instances may serve the intended workload, but that remains
a benchmark hypothesis. H2 can remain useful for reduced intermediates; DuckDB staging
is an option to validate, not a prerequisite for every such pipeline.

## 2. Fact check of the original thesis

Sources were consulted on 2026-09-22 and 2026-09-24. Their current/stable URLs can change; verify exact
versions and APIs again before implementation.

| Claim | Finding and qualification | Evidence |
|---|---|---|
| DuckDB is good at reading Parquet and other files. | DuckDB queries Parquet directly, parallelizes scans and pushes column selection and filters into the reader. File statistics can avoid reading irrelevant row groups. This supports the thesis but is not a performance measurement of Datapipelines. | [Querying Parquet](https://duckdb.org/docs/current/guides/file_formats/query_parquet), [File formats](https://duckdb.org/docs/current/guides/performance/file_formats) |
| DuckDB can read concurrently. | Both internal query parallelism and concurrent queries are possible; CPU, RAM and bandwidth remain shared resources. DuckDB emphasizes larger analytical queries over many small concurrent requests. | [Concurrency](https://duckdb.org/docs/current/connect/concurrency), [Workload tuning](https://duckdb.org/docs/current/guides/performance/how_to_tune_workloads) |
| Parquet supports partitioning on write. | More precisely, Parquet is the file format; a writer or table format partitions the dataset. DuckDB can write Hive-style partition directories, while DuckLake defines partitioning on tables. | [Partitioned writes](https://duckdb.org/docs/stable/data/partitioning/partitioned_writes), [DuckLake partitioning](https://ducklake.select/docs/stable/duckdb/advanced_features/partitioning) |
| DuckDB and DuckLake are a suitable combination. | DuckDB executes queries; DuckLake supplies transactional tables and metadata over files. PostgreSQL is the recommended catalog backend for multiple remote clients. | [DuckLake overview](https://ducklake.select/docs/stable/), [Catalog selection](https://ducklake.select/docs/stable/duckdb/usage/choosing_a_catalog_database) |
| S3 makes it inexpensive. | Potentially, but total cost also includes compute, catalog hosting, requests, network transfer, maintenance and operator effort. Compute placement matters. AWS documents no S3 transfer charge to AWS services within the same region; other networking charges can still apply. | [S3 pricing](https://aws.amazon.com/s3/pricing/) |

An unpartitioned Parquet dataset does **not** necessarily require reading every byte:
column selection and row-group statistics can still reduce reads. Likewise, an absent
registered partition column does not establish that the dataset consists of one file.
The skill correction in #219 removes those overstatements; preserve the distinction in
future ingestion guidance rather than carrying that premise into the new architecture.

### 2.1 Scale and concurrency boundaries

Large stored volume, large query working sets and high query concurrency are different
requirements. A selective query over terabytes may be easier than a much smaller query
with a large many-to-many join.

DuckDB can spill grouping, joining, sorting and windowing work to disk, although some
query shapes and aggregate states still cannot complete within available resources.
DuckDB's ordinary execution model is single-node; DuckLake coordinates shared table access
and does not automatically distribute one query across several DuckDB workers.
[Hardware guidance](https://duckdb.org/docs/current/guides/performance/environment),
[Workload tuning](https://duckdb.org/docs/current/guides/performance/how_to_tune_workloads),
[Single-node execution context](https://duckdb.org/2026/08/25/table-functions-in-java).

Native DuckDB file locking must not be confused with DuckLake concurrency. A shared
writable `.duckdb` file is not the proposed multi-worker storage design. DuckLake clients
can coordinate through PostgreSQL, with conflicts/retries governed by its transaction
implementation. Quack, DuckDB's newer remote protocol, was also found in the documentation;
it is not a dependency proposed for this design.
[DuckDB concurrency](https://duckdb.org/docs/current/connect/concurrency),
[DuckLake transactions](https://ducklake.select/docs/stable/duckdb/advanced_features/transactions).

### 2.2 Maturity, licensing and adjacent ecosystem

DuckLake 1.0 was released on 2026-04-13. Its maintainers describe the specification and
reference implementation as production-ready; that is an upstream statement, not evidence
that our intended integration has passed acceptance. DuckDB and the DuckLake specification
and extension are MIT-licensed.
[DuckLake 1.0 announcement](https://ducklake.select/2026/04/13/ducklake-10/),
[DuckLake license](https://github.com/duckdb/ducklake/blob/main/LICENSE),
[DuckDB FAQ](https://github.com/duckdb/duckdb-web/blob/main/faq.md).

DuckLake lists DataFusion, Spark, Trino and PostgreSQL clients at varying maturity levels.
Iceberg has an established ecosystem across multiple engines. Both can use Parquet, but
that does not make their metadata or table semantics interchangeable.
[DuckLake clients](https://ducklake.select/docs/stable/),
[Iceberg](https://iceberg.apache.org/).

MotherDuck offers a hosted DuckLake service described as public preview in the sources
consulted. It is ecosystem context, not a required component or the intended product model.
[MotherDuck DuckLake](https://motherduck.com/product/ducklake/).

The combination is not unique: Shaper describes self-hosted DuckDB/DuckLake analytics
with scheduled ingestion and dashboards; dlt documents a DuckLake ingestion destination.
These are concrete alternatives to evaluate, not evidence that they solve every intended
Datapipelines workflow. The product case must rest on usability, cross-source composition,
governance and measured operational reliability rather than “nobody offers this.” No
comparative product benchmark or customer-demand validation has been completed here.
[Shaper](https://taleshape.com/blog/simple-self-hosted-data-lake-platform-with-duckdb-ducklake-and-shaper/),
[dlt DuckLake destination](https://dlthub.com/docs/dlt-ecosystem/destinations/ducklake).

## 3. Repository baseline

The following is established from repository documentation, not a fresh runtime test:

- Current dp-lake uses DuckDB to read registered Parquet and Iceberg tables on S3 or
  compatible storage. The existing `dp-catalog` is a registry, not DuckLake's transactional
  catalog. The documented lake path is read-only.
- A LAKE datasource has a shared embedded engine across its pooled connections. Its
  connections share resource budgets; extra connections do not add compute capacity.
- Current staging is isolated per execution and uses in-memory H2. The pipeline contract
  already has `settings.tempdb.engine`, with DuckDB anticipated as a future option.
- DQL nodes already support output to `caller`, `tempdb` or a registered `datasource`.
- Datasource write-back supports `append` and `replace`; its target table must exist or be
  created by an earlier DDL step. The present contract describes streaming a ResultSet.
- DML and DDL node types already exist for explicit data/schema changes.
- Each write-back commits independently. The entire DAG is not one transaction.
- Existing templates are dialect-specific; choosing DuckDB staging will affect tempdb SQL.
- Governed pipelines, API publishing and MCP access provide integration surfaces to reuse.

Sources: [README](../../../README.md), [Datasources](../../datasources.md),
[Pipeline contract](../../pipeline-contract.md), [Staging](../../staging.md),
[DAG executor](../../dag-executor.md),
[Existing connector design](2026-09-05-warehouse-and-lake-connectors-design.md).

The repository was also undergoing unrelated changes when this draft was saved. This note
records the inspected baseline, not a release/deployment assertion. Re-read those contracts
on resumption.

## 4. Proposed responsibility boundary

This is the assistant's recommended design, pending owner review.

| Component | Responsibility |
|---|---|
| Object storage | Durable data files |
| DuckLake | Table metadata, snapshots, transactions and file membership |
| DuckDB | SQL execution and data reading/writing |
| Datapipelines | Connections, permissions, pipeline definitions, parameters, scheduling, execution history, maintenance orchestration and presentation |

Datapipelines should reference DuckLake's table state rather than implement a competing
transactional catalog. The existing registry may continue to describe external files and
reference managed tables; the exact schema and discovery integration are undecided.

Keep lake support optional. A small deployment could reuse its PostgreSQL service with a
separate catalog database and credentials. Avoid requiring Kubernetes or a distributed
cluster. Separate workers remain an option for resource isolation and growth, not a
confirmed first-version requirement.

The value is a coherent workflow: database extraction, maintained analytical tables,
transformations and consumption through the same Datapipelines surfaces. An example is
daily sales ingestion joined with product/inventory data to produce a profitability table
that a parameterized pipeline can serve to an API or dashboard.

## 5. Proposed user model and storage mapping

Start with a lake connection and its tables. A lake connection owns a catalog and one
storage root, which may be a bucket or a prefix within a bucket. Multiple roots can be
separate connections. Catalog provisioning could be handled by Datapipelines setup.

Illustrative display, **not a promise of an exact generated path**:

```text
Lake: company-lake
Storage root: s3://company-data/analytics/
Schema: main
Table: orders
Actual table location: s3://company-data/analytics/main/orders/
```

The user configures the root. DuckLake manages underlying paths. Pipelines identify the
registered destination table and do not each declare another S3 location for it.

DuckLake records paths at root, schema, table and file levels. Paths can be relative or
absolute; default names can involve names or UUIDs. Show the resolved catalog mapping,
not a path guessed from today's table name. Path movement, renaming, reuse after dropping
a table, and arbitrary per-table placement remain research questions.
[Paths](https://ducklake.select/docs/stable/duckdb/usage/paths),
[Connecting](https://ducklake.select/docs/stable/duckdb/usage/connecting).

The proposed UI should expose storage location and the files for a selected snapshot.
A directory listing is not the authoritative current table: old files and deletion state
also exist. DuckLake supplies snapshot-aware file listing.
[List files](https://ducklake.select/docs/stable/duckdb/metadata/list_files).

### 5.1 Schema versus namespace versus bucket

Recommendation: reuse DuckLake's native schema, default to `main`, and make another schema
optional. Do not invent a separate namespace object merely to support this feature.
Schemas can organize imports such as `sales.orders` and `support.tickets` without making
small installations configure a hierarchy first.

| Concept | Meaning |
|---|---|
| Lake connection | Catalog, storage root and access configuration |
| Schema | Optional logical grouping of tables |
| Table | Logical schema, data and write destination |
| Partitioning | Physical organization for efficient access |

A schema is not a bucket or an authorization boundary. Existing workspace/role governance
still applies. Whether to expose the term “schema” prominently is not settled.

### 5.2 Partitioning and inlining

DuckLake supports table partition keys and evolution for subsequent writes; changing the
definition does not automatically reorganize old files. Its catalog tracks partition
information even when paths do not encode it. Initial choices should follow access
patterns and data volume, avoiding excessive tiny partitions.
[Partitioning](https://ducklake.select/docs/stable/duckdb/advanced_features/partitioning),
[Partitioned writes](https://duckdb.org/docs/stable/data/partitioning/partitioned_writes).

DuckLake can keep small inserts/deletes in the SQL catalog and flush them later. Thus
“all table rows are immediately in S3” is not true with inlining enabled. Assistant
proposal: consider disabling inlining for the initial bulk-offload workflow, accepting
the small-file/compaction trade-off. This is **not decided**; preserving upstream behavior
and clearly displaying it is another option. Verify effective defaults for the pinned
release, rather than relying on differing documentation examples.
[Data inlining](https://ducklake.select/docs/stable/duckdb/advanced_features/data_inlining).

### 5.3 Physical layout guidance for future ingestion authoring

This belongs to a future ingestion skill once the corresponding writer capabilities
exist. The current query skill should explain how to use and measure the existing layout,
without instructing agents to invoke unimplemented maintenance or write operations.

- Partition for common selective predicates and adequate data per partition. A partition
  is a logical grouping, not necessarily one file. Parquet row groups also permit parallel
  scanning within a file; parallelism does not require a high-cardinality partition key.
- Sort on frequently filtered non-partition columns when measurements justify the write
  cost. Narrower min/max ranges can improve file/row-group skipping. DuckLake supports
  declared sort keys for writes and maintenance; changing the declaration does not itself
  rewrite historical files or guarantee one globally sorted, non-overlapping dataset.
- DuckLake file-column statistics and Parquet row-group statistics are metadata for data
  skipping. Optional Parquet Bloom filters can rule out equality matches when present and
  supported; a possible match still needs checking. Verify the chosen writer, reader,
  column type and settings instead of assuming every file contains usable Bloom filters.
- Parquet metadata gives byte locations for column chunks, allowing remote range reads.
  This avoids downloading irrelevant chunks where supported; it is not an arbitrary
  row-level B-tree on S3. DuckLake does not currently support ordinary table indexes or
  enforced primary-key/unique constraints. Do not build a custom secondary-index service
  as an assumed first-version requirement.
- Plan file/row-group sizing, compaction, retention and late-data handling together.
  Validate bytes/requests and cold/warm latency against representative predicates before
  choosing defaults. No universal file size, partition column or sort key is decided.

Sources: [Parquet tips](https://duckdb.org/docs/current/data/parquet/tips),
[sorted tables](https://ducklake.select/docs/stable/duckdb/advanced_features/sorted_tables),
[file statistics](https://ducklake.select/docs/stable/specification/tables/ducklake_file_column_stats),
[Bloom filters](https://duckdb.org/2025/03/07/parquet-bloom-filters-in-duckdb),
[remote reads](https://duckdb.org/docs/current/core_extensions/httpfs/https),
[unsupported features](https://ducklake.select/docs/stable/duckdb/unsupported_features).

## 6. DuckLake versus Iceberg

Recommendation: support DuckLake as the first writable managed-table format. The target
organization is starting a lake, so it need not choose between multiple table formats.
Keep existing Iceberg reads for compatibility; do not expand Iceberg writes in this first
design. This is a scope recommendation, not a decision to remove existing functionality.

Revisit writable Iceberg if concrete interoperability requirements justify it. Do not
promise that reading DuckLake's underlying Parquet as ordinary files preserves updates,
deletes, snapshots or schema evolution.

## 7. Pipeline integration: dialect and output before a new node type

Recommendation: retain the `LAKE` wire dialect (dp-lake is the product name), add an
explicit supported writable backend/capability, and reuse DQL datasource output.
Existing external Parquet/Iceberg registrations must not become writable merely because
the dialect gains DuckLake support. Exact backend configuration and capabilities are open.

Illustrative node fragment using the existing output shape; it does **not** run against
today's read-only LAKE implementation:

```json
{
  "type": "DQL",
  "source": "production-postgres",
  "template": {"id": "sales/export/orders.sql", "version": 1},
  "output": {
    "target": "datasource",
    "datasource": "company-lake",
    "table": "orders",
    "mode": "append"
  }
}
```

The source dialect selects how the query runs; the destination selects how rows are
written. An editor can present “Write to lake” without inventing a new backend node type.
Schema-qualified destination naming still needs a contract; the example uses the default
schema deliberately.

Three execution paths should be considered:

| Use case | Proposed data movement |
|---|---|
| Copy a database query | Source cursor → bounded bulk transfer → DuckLake |
| Transform across databases | Sources → H2 or DuckDB staging → DuckLake |
| Transform lake data | DuckDB reads → computes → writes DuckLake directly |

The first path should not require staging the entire input. The third should avoid moving
all rows through Java or Redis just to return them to DuckDB. Preserve one logical output
contract while selecting an efficient execution strategy internally.

DuckDB's Java documentation recommends its Appender for bulk loading. Compatibility,
catalog addressing, types and transaction behavior for our exact DuckLake/driver versions
need a spike. Do not assume generic JDBC batches or a per-row INSERT loop will suffice.
[Java import](https://duckdb.org/docs/current/clients/java/data_import),
[Appender](https://duckdb.org/docs/current/data/appender).

Large writes should report destination, committed snapshot, counts and outcome, with
bounded previews where appropriate. The full written dataset need not become a caller
result or Redis cursor.

### 7.1 Write semantics to settle

- **Append:** publish a batch of new rows.
- **Replace:** atomically publish a complete replacement, with no empty or partial state
  visible to readers. Define behavior, then select supported DuckLake SQL; do not assume
  the existing generic TRUNCATE implementation transfers unchanged.
- **Retry:** recognize a committed logical batch even if acknowledgment was lost.
- **Creation:** assistant preference is an explicit “Create destination from query schema”
  action followed by ordinary writes. Pre-created tables, DDL steps and controlled
  create-on-first-run remain alternatives.
- **Schema mismatch:** refuse or require an explicit evolution policy; do not silently
  accept arbitrary shape changes.
- **Incremental extraction:** define source snapshot consistency, watermarks, late updates,
  deletes and checkpoints. Moving rows is not by itself reliable database replication.
- **Updates/merges:** valuable later, but not required for the first append/replace spike.
- **Source retention:** “offload” initially means copy. Source deletion is a separate,
  explicitly designed archival operation, not implied by a successful lake write.

DuckLake provides ACID transactions and snapshot isolation, but no enforced primary-key
or unique constraints. Pipeline deduplication and exactly-once claims therefore require
their own proven commit/recovery design. A separately updated application record alone
does not resolve an ambiguous commit.
[Transactions](https://ducklake.select/docs/stable/duckdb/advanced_features/transactions),
[Unsupported features](https://ducklake.select/docs/stable/duckdb/unsupported_features).

A final writing step matches the owner's intended workflow. Whether this is a structural
leaf-node restriction or an editor convention is open. Multiple sink nodes would still
commit independently under the current contract; a later failure cannot undo a previous
commit. Define outcome reporting for “data committed, execution acknowledgment failed.”

## 8. DuckDB as a staging choice

Keep H2 and add DuckDB through the existing engine choice. This requires more than swapping
a connection URL:

- Validate tempdb templates against the selected engine's dialect.
- Preserve per-execution isolation and connection ownership.
- Account for native memory, rather than relying on JVM heap measurements alone.
- Budget memory, threads, local scratch capacity and concurrent executions together.
- Ensure cancellation and close clean up execution-owned scratch resources.
- Investigate using the same execution DuckDB instance for staging and an approved
  DuckLake attachment, enabling direct INSERT/SELECT without a second large copy.
- Keep attachments, credentials and external access under server control; arbitrary
  user SQL must not bypass the existing datasource boundaries.

DuckDB can spill in both persistent and in-memory modes. “In-memory staging” must not be
interpreted as “requires all analytical intermediates to fit in RAM.” That also does not
mean every query or arbitrary staged dataset has unlimited out-of-core behavior.
[Environment](https://duckdb.org/docs/current/guides/performance/environment),
[Workload tuning](https://duckdb.org/docs/current/guides/performance/how_to_tune_workloads).

## 9. Compute and cost research

These workload expectations are engineering inferences from upstream guidance, not
Datapipelines benchmark findings.

| Workload | Likely pressure | What to investigate |
|---|---|---|
| Database extraction and append | Source throughput, network, encoding/compression CPU | Bounded streaming, source load, batch and file formation |
| Selective Parquet reads | Network latency and bytes transferred | Pruning, column selection, placement and caching |
| Broad scans and aggregation | CPU, bandwidth, aggregation state | Cores/RAM balance and cardinality |
| Large joins, sorts, windows and merges | RAM and spill I/O | Fast SSD/NVMe, intermediate expansion, OOM limits |
| Compaction and sorted writes | Read/write bandwidth, CPU, sometimes scratch | Maintenance scheduling and interference with queries |

DuckDB documents approximately 1–4 GB RAM per thread as performance guidance, with
aggregation-heavy work nearer 1–2 GB and join-heavy work nearer 3–4 GB. These are heuristics,
not minimum product requirements. SSD/NVMe is recommended for disk-heavy work. More RAM
reduces spilling; fast scratch helps when spilling occurs. Neither solves slow networking.
[Hardware guidance](https://duckdb.org/docs/current/guides/performance/environment).

`memory_limit` is not a hard cap on total process memory. Leave capacity for native
allocations, the JVM and other services. More connections/threads are not automatically
faster; admission control must consider aggregate budgets across jobs and datasources.
[OOM guidance](https://duckdb.org/docs/current/guides/performance/oom).

Cost model to measure: storage + compute + catalog + requests + transfer + maintenance
and retained snapshots + operational effort. No monthly price, universal savings claim
or capacity promise has been established. Put compute near object storage and the catalog;
also account for the source database's location and extraction traffic.

### 9.1 Deployment and storage choices

Recommended starting point: a small VM/container deployment with S3 for durable lake
objects, an appropriate catalog database, and fast local or EBS scratch for compute.
Kubernetes remains an option for organizations already operating it; it does not pool
several pods' memory into one DuckDB query. Budget native/JVM memory and concurrent work
inside each worker's limits. The worker topology and admission-control design remain open.

Moving all lake data to AWS network file storage is not automatically faster or cheaper.
DuckLake can use local files, NFS/SMB or object storage, but each has different throughput,
latency, provisioned-capacity and operational costs. A shared writable native `.duckdb`
file on NAS is a different design and is discouraged by DuckDB's performance guidance.
[Storage selection](https://ducklake.select/docs/stable/duckdb/usage/choosing_storage),
[DuckDB environment](https://duckdb.org/docs/current/guides/performance/environment).

For AWS, evaluate same-region EC2/S3 placement and an S3 gateway endpoint before treating
network transfer as a per-scan charge. The endpoint has no additional endpoint charge and
can avoid a NAT path for S3 traffic. EBS scratch and EKS orchestration add their own costs;
compare complete deployments at current regional prices, not storage price alone.
[S3 pricing](https://aws.amazon.com/s3/pricing/),
[S3 gateway endpoints](https://docs.aws.amazon.com/vpc/latest/privatelink/vpc-endpoints-s3.html),
[EBS pricing](https://aws.amazon.com/ebs/pricing/), [EKS pricing](https://aws.amazon.com/eks/pricing/).

### 9.2 Evidence needed before capacity promises

Proposed benchmark starting points: 4 vCPU/16 GB and 8 vCPU/32 GB workers with SSD scratch.
These are experiment configurations, not approved deployment defaults. Do not assign
scratch capacity from an arbitrary percentage of lake size.

Measure direct copy, selective reads, larger-than-memory joins/aggregations, append,
replace and compaction. Compare cold/warm runs and single/concurrent jobs. Record input
shape, versions, settings, hardware and storage placement with throughput, latency, peak
process memory, peak scratch, object requests, transferred bytes, file counts/sizes and
estimated operating cost. Include bounded failure behavior at disk and memory limits.

Include the intended workflow: filter/aggregate a historical slice at the source, join
another database's lookup, and produce a small result; run it alongside ingestion and
maintenance. Verify result correctness as well as latency. Pair throughput measurements
with restart/retry, overlapping scheduled runs, late data, schema changes and coherent
catalog/object restore exercises. A large demo's row count alone proves none of these.

## 10. Operations and security belong in the design

DuckLake provides maintenance functions, but Datapipelines must decide when to invoke
them. Small files need compaction; snapshot retention and unreferenced-file cleanup need
explicit policy. Dropping a table does not immediately remove its files. Frequent deletes
can also require rewriting files. Reuse the planned scheduling infrastructure where
appropriate rather than inventing a separate scheduler.
[Maintenance](https://ducklake.select/docs/stable/duckdb/maintenance/recommended_maintenance).

Recovery must cover catalog and objects coherently; bucket contents alone are not a full
table backup. Test restore, interrupted uploads, uncertain commits and safe cleanup with
active readers before describing writes as reliable.

Reuse workspace governance and encrypted datasource credentials. Bucket/catalog access
outside Datapipelines can bypass application-level permissions. Define storage-prefix
ownership and least-privilege access alongside table creation, writing and maintenance.

Before implementation, specify which of viewer, author, promoter, workspace admin and
super admin may perform each new action, and which guard enforces it. No role allocation
was decided in this conversation. Follow the repository's ScopeMatrix, auth documentation
and RoleWalk expectations when actions are added; a shared dialect must not accidentally
widen existing read-only access.

## 11. Proposed first scope and explicit deferrals

Assistant recommendation, not an approved release plan:

1. Connect/provision a DuckLake catalog and its storage root.
2. Register/create destination tables with visible resolved locations.
3. Write pipeline results through existing output semantics, starting with append/replace.
4. Read managed tables through normal Datapipelines surfaces.
5. Add DuckDB staging and efficient bulk/direct execution paths where required.
6. Include retry correctness, maintenance policy and tested recovery in acceptance.

Defer distributed query execution, a broad streaming/CDC platform, writable Iceberg,
automatic arbitrary schema evolution and source deletion. Dashboards/scheduling/transformers
remain shared Datapipelines features rather than parallel lake-specific subsystems.

## 12. Resume checklist and learning path

Start with the owner's request to learn before finalizing the architecture:

1. Walk through DuckDB connections, queries, Parquet scans, parallelism, memory and spill.
2. Build a small disposable DuckLake to understand catalog versus files, paths, schemas,
   partitions, snapshots, append/replace and inlining.
3. Observe concurrent reads/writes and deliberately interrupt a write; inspect both the
   visible snapshot and leftover files.
4. Run maintenance and restore exercises; establish what each operation promises.
5. Revisit the proposed user model, naming and storage mapping with those observations.
6. Validate a Java bulk transfer and a DuckDB-staging-to-DuckLake direct write against
   exact pinned versions, including types and transaction scope.
7. Benchmark the workloads in §9, then decide deployment defaults and worker isolation.
8. Settle node/output semantics, destination creation, schema policy, retry identity,
   checkpointing, terminal-write behavior, permissions and recovery guarantees.

These are future research/design questions, not an implementation tracker. #219 covers
the current skill correction and preservation of this design discussion only. Before
writable-lake implementation, search existing GitHub work and connect the accepted scope
and acceptance criteria to its issue/project; early public discussion belongs in Discussions.

No code was changed for the lake proposal, no production resources were touched, and no
benchmark or compatibility result should be inferred from this draft.
