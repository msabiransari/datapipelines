# Draft: Ingestion and validation for Datapipelines

**Status:** discussion draft; not a normative contract or implementation plan.
**Date:** 2026-09-24.
**Purpose:** preserve the ingestion discussion for further design when dp-lake implementation is considered.
**Companion:** [Writable dp-lake draft](2026-09-22-ducklake-managed-lake-draft.md).
**Evidence:** primary documentation consulted during the discussion; no comparative product trials, ingestion prototype or failure-recovery tests were run.
**Distribution:** contributor design material under `docs/superpowers/specs/`, outside the product documentation packaging allowlist.

## 1. Owner direction and decision status

Muhammad wants organizations to configure ingestion from files or databases, schedule it,
and obtain useful tables in supported destinations. dp-lake is one destination within the
broader Datapipelines workflow. The target remains small and medium organizations with
bounded analytical workloads, self-hosted operation and understandable costs.

The owner agreed that each ingestion concern needs evaluation and design before
implementation. He explicitly emphasized that validation is a major workstream and that
designing a generic validation engine is substantial work. Validation must remain explicit
in the design agenda, even when a discussion asks what is needed beyond validation.

This document records the concerns and proposed approaches, not approval of a particular
engine, rule language, connector set, delivery guarantee or release scope. The owner asked
to preserve the discussion and revisit it with dp-lake; implementation is not requested here.

## 2. Market evidence and the product hypothesis

The claim that ingestion can already be configured without writing code is supported by
existing products. This applies to their supported connectors and configurations, not to
arbitrary sources, destinations or semantics.

| Product | Capability documented by its provider |
|---|---|
| [Airbyte](https://airbyte.com/data-replication) | UI-configured sources, destinations, schedules and replication modes; cloud and self-managed options. |
| [Fivetran](https://fivetran.com/docs/getting-started/fivetran-dashboard/connectors) | Guided connector setup followed by automated synchronization into supported destinations. |
| [Apache NiFi](https://nifi.apache.org/nifi-docs/overview.html) | Visual dataflows with buffering, backpressure and data provenance. |
| [AWS Glue Studio](https://docs.aws.amazon.com/glue/latest/dg/edit-nodes-chapter.html) | Visual ingestion/transformation jobs, including combining files and writing to S3. |
| [Azure Data Factory](https://learn.microsoft.com/en-us/azure/data-factory/quickstart-create-data-factory-copy-data-tool) | A guided Copy Data tool for configuring source, destination and data movement. |

These sources establish capability, not equivalent usability, price, reliability or fitness
for our users. No-code setup still requires decisions about access, identity, updates,
deletes, types and recovery. Self-hosting also leaves operational responsibilities.

The product hypothesis is a simple, self-hosted workflow from existing data to maintained
analytical tables and useful outputs, with predictable operating costs. Ingestion without
code alone is insufficient differentiation. Proposed demand validation: compare the same
real workflow in Datapipelines and an existing alternative with prospective users, measuring
setup time, correctness, recovery effort and total cost. No customer preference or savings
claim has been established by this discussion.

## 3. Complete ingestion design agenda

Each area below needs its intended behavior, failure cases, state/recovery model, user
controls, resource costs and acceptance evidence. This is a discussion agenda, not a
delivery-status board; GitHub remains authoritative for implementation work.

### 3.1 Destination meaning and write semantics

Define whether a table is an accumulating event history, the latest state of records, a
periodic snapshot or a derived analytical dataset. Specify append, replace and merge
behavior, record identity, duplicate handling, update ordering and deletion policy.
An execution can succeed while producing the wrong kind of table.

Open questions include destination creation, ownership, schema mapping, atomic replacement,
and whether multiple table outputs share any publication boundary. A supported database
connection does not establish support for every ingestion/write mode.

### 3.2 Safe interruption, retry and checkpointing

Design for a worker that commits a batch and crashes before acknowledging success. A retry
must determine what committed, then resume without unintended duplication or omission.
Logical batch identity, durable progress, idempotency and recovery belong together.
Do not infer exactly-once results from retries, an execution ID or a saved cursor alone.

AWS documents that rewinding/resetting Glue bookmarks does not clean destination files;
source progress and destination effects are separate concerns.
[Glue bookmarks](https://docs.aws.amazon.com/glue/latest/dg/monitor-continuations.html).

DuckLake does not enforce primary-key or unique constraints. A transactional table format
does not itself supply application-level deduplication or resolve an ambiguous acknowledgment.
[DuckLake limitations](https://ducklake.select/docs/stable/duckdb/unsupported_features).

### 3.3 Change detection and completeness

For cursor-based ingestion, define cursor reliability, equal-value ordering, late commits,
backdated changes, initial-load boundaries and physical-delete detection. Airbyte documents
that a modification can be missed when its cursor field is not updated appropriately.
[Incremental synchronization](https://github.com/airbytehq/airbyte/blob/master/docs/platform/using-airbyte/core-concepts/sync-modes/incremental-append-deduped.md).

For files, define identity, how completion is signaled, overwritten paths, duplicate
discovery, late arrivals and retention. Neither a filename nor a timestamp alone should be
assumed sufficient for every source. CDC is an option to evaluate, not an agreed v1 feature.

### 3.4 Source consistency and protection

Specify whether extraction represents one source snapshot or a documented sequence of
reads while the source changes. Define acceptable effects on operational databases:
connections, concurrent queries, query duration, bandwidth and replica use where available.

CDC also has source-side operating costs. PostgreSQL warns that replication slots can
retain enough transaction logs to fill disk. Recovery must consider source-log retention
and the consequences of a consumer falling behind.
[PostgreSQL replication slots](https://www.postgresql.org/docs/17/warm-standby.html#STREAMING-REPLICATION-SLOTS).

### 3.5 Schema evolution and type fidelity

Distinguish adding a nullable column from renaming/dropping a column, changing a key or
changing a type. Decide what is automatic, what pauses ingestion and what needs migration.
Preserve decimal precision, timestamp/time-zone meaning, nulls, leading-zero identifiers
and nested structures. A small preview cannot prove the whole dataset's schema.

Malformed records need an explicit fail/quarantine policy with visible counts and replay.
Detection and policy execution connect to the validation engine (§4); silently coercing,
dropping or repairing data must not be an undocumented side effect.

### 3.6 Scheduling, backfills and publication

Define overlapping-run policy, missed schedules, time zones, cancellation, retries and
historical reloads. A backfill must coexist safely with current ingestion. Reprocessing
older data must not accidentally overwrite newer state or duplicate an append history.

Define the reader-visible boundary: a whole batch, table, partition or another documented
unit. Partial publication must be intentional and observable. A successfully scheduled job
is not proof that its output is complete, validated or safe for downstream consumption.

### 3.7 Bounded resources and cost

Use bounded transfer batches and explicit memory, scratch, concurrency and retry budgets.
Avoid requiring the entire ingestion to pass through tempdb or the result cache. Account
for failed/repeated extraction, source load, network placement and destination write cost.

For lake destinations, evaluate partitioning, sorting, file/row-group sizes, compaction and
snapshot retention together. The [dp-lake draft §5.3](2026-09-22-ducklake-managed-lake-draft.md#53-physical-layout-guidance-for-future-ingestion-authoring)
preserves that research. These are future ingestion-authoring concerns, not instructions
to invoke write/maintenance capabilities that current query authoring does not expose.

### 3.8 Observability, lineage and recovery operations

Report read, committed and rejected records; processed source ranges; destination identity;
and the last successfully published data boundary. Distinguish job execution time from data
freshness. Counts need a defined population, especially when transformations alter grain.

Associate output with source identity, ingestion definition/version, rules and execution.
Operators need diagnosis, replay and repair without editing internal state by hand.
Backup/restore must cover the relevant progress/configuration state and destination data;
the lake design must also restore catalog and objects coherently.

### 3.9 Connector lifecycle, security and self-hosted operations

Maintain a capability matrix per source/destination combination: types, extraction modes,
delete handling, write modes and recovery guarantees. Account for changing drivers, APIs,
authentication, pagination, rate limits and connector upgrades. Reuse existing connectors
where suitable; connector breadth creates a continuing maintenance commitment.

Design credential rotation, least-privilege access, safe diagnostic evidence, resource
isolation, installation, upgrades and backup/restore. State which roles may configure,
run, change rules, approve exceptions, inspect quarantine or replay data when those actions
are designed. No new role allocation or permission contract is decided here.

### 3.10 Validation as a major workstream

Validation is more than parsing rows or checking a sample. It covers plan validity,
structural correctness, business rules, dataset relationships, completeness and delivery.
It influences resource planning, error handling, checkpointing and publication. Design its
generic contract early, while keeping the first implemented rule set deliberately bounded.

## 4. Generic validation engine: coverage and open decisions

### 4.1 Coverage map

| Concern | Examples and boundaries to define |
|---|---|
| Configuration | Connections, mappings, rule definitions and write modes form an executable plan. |
| Structure and types | Required fields, parsing, nullability, precision, schema compatibility and nested shapes. |
| Record-level rules | Ranges, allowed values, patterns and relationships between fields. |
| Dataset-level rules | Uniqueness, duplicates, expected coverage and aggregate conditions. |
| Cross-dataset rules | Referential integrity and reconciliation against an authoritative table/source. |
| Delivery | Intended data reached the destination completely and correctly, with the agreed transformations. |
| Freshness and drift | Data arrived within expectations; schema/distribution changes are detected under an explicit policy. |

This map is not a promise that every category will be supported initially. Existing pipeline
checks must be assessed for reuse; their existence alone does not prove that they can
enforce an ingestion publication boundary or validate a destination after commit.

### 4.2 Rule contract and outcomes

Define rule identity/version, inputs, parameters, scope, applicability, null semantics,
severity and the population evaluated. Specify behavior for empty inputs and missing
reference data. Proposed outcomes include passed, failed, errored and not evaluated;
names and wire representation are undecided. A timeout or evaluation error is not a pass.

Keep severity separate from operational disposition: a rule result is evidence; policy
decides whether to block, warn, reject records or quarantine. Thresholds and allowed
exceptions need explicit meaning rather than implicit defaults.

### 4.3 Execution location, scope and resources

Evaluate source pushdown, streaming evaluation, staged evaluation and destination checks.
A per-record range check differs from exact global uniqueness or a cross-source join.
Define when exact checks need retained state/spill and when a declared approximation is
acceptable. Dialect, type and null differences must not silently change a rule's meaning.

Make scope explicit: current batch, partition, time window or destination history.
Unique within a batch is not unique across the table. Incremental checks require a design
for historical state and reference-data versions. Sample-based evidence must be labeled
with its coverage; it must not silently satisfy a full-dataset requirement.

### 4.4 Publication and failure policy

Choose which failures block a batch, reject individual rows, quarantine records or warn.
A dataset-wide mismatch may not identify a particular bad row. Quarantine needs enough
context to diagnose and replay safely, bounded retention and appropriate access controls.

The central design question is how to prevent invalid data becoming visible when a check
can finish only after the whole batch has been read. Evaluate isolated staging followed
by publication, destination transactions, or another explicit mechanism against each
destination's capabilities. No particular mechanism is selected here.

Distinguish pre-publication checks from post-publication reconciliation. A later failed
check needs a defined response; it cannot retroactively imply that readers never saw data.
Do not advance progress in a way that silently loses blocked or quarantined records.
Publication and checkpoint acknowledgment need a tested recovery protocol, including the
case where data committed but the acknowledgment was lost.

### 4.5 Replay, versioning and evidence

Decide whether replay uses the original rule version or a newer one, and record the choice.
Correcting a record, changing a rule and re-evaluating old evidence are different actions.
Preserve enough source/destination identity to reconcile effects without assuming a raw
source remains available forever.

Results should include evaluated/failed counts, denominators, scope, coverage, bounded
examples, rule version and execution identity. Sensitive record values must not become
uncontrolled logs. Evidence should distinguish a data failure from an engine/connector
failure and give the operator an actionable next step.

### 4.6 Extensibility and transformation boundary

Proposed starting point: reusable rule types with a common contract, plus controlled SQL
or expression extensions where justified. No rule language or validation library has been
selected. Compare reuse/integration with building our own execution layer before deciding.
User expressions need resource and data-access boundaries appropriate to their engine.

Keep detecting an invalid value distinct from trimming, coercing, imputing, dropping or
otherwise repairing it. Those transformations alter the dataset and require explicit
policy and lineage. A generic engine should enable extension without making arbitrary
unbounded code the default rule implementation.

## 5. Proposed first scope and agent responsibility

Assistant recommendation, pending design and customer evidence: start with scheduled
batch ingestion, a small connector set and clearly bounded write modes. Include safe
retry/recovery, schema policy, validation, run history and publication behavior in that
scope. Add CDC and broad SaaS coverage when concrete workflows justify their cost.

The agent can discover schemas, propose mappings/rules, explain trade-offs and author a
saved, versioned ingestion definition. Deterministic server behavior must enforce
execution, validation policy, permissions, checkpoints and recovery. Prompting and skills
support authoring; they cannot guarantee delivery correctness.

Keep general analytical correctness in the current authoring skill. Future ingestion
skills should describe the physical-layout and write/validation capabilities that actually
ship, including their limits. Do not present this draft as an executable feature catalog.

## 6. Resume discussion before implementation

For each of the ten areas in §3, agree its behavior and limits, compare implementation
options, record the decision and identify acceptance evidence. Use the dp-lake companion
for table-format, catalog, storage and staging decisions rather than defining them twice.

Proposed acceptance scenarios to refine include:

- Crash before destination commit, after commit but before acknowledgment, and during replay.
- Re-discovered/overwritten files, equal cursors, late updates, deletion and source changes during extraction.
- Breaking schema/type changes and preservation of decimal, time-zone and null semantics.
- Duplicate keys spanning batches, missing reference data and cross-dataset reconciliation failures.
- A dataset-wide validation failure found at the end of a large batch; readers observe the documented publication boundary.
- Rule errors/timeouts, empty inputs and sampled evaluation cannot masquerade as full successful validation.
- Quarantine/replay under original and changed rule versions without unintended duplication or loss.
- Overlapping schedules, backfills, bounded memory/disk, connector interruption and coherent restore.

These are proposed evidence requirements, not tests already run. GitHub Issues and the
Roadmap project remain the implementation tracker; search existing work and connect
accepted implementation scope there when design is ready. The earlier #219 skill change
does not authorize or deliver this ingestion/validation engine.
