# The Managed Lake: Definitions, Deliveries, Publication and Reads

**Status:** v1.27 (#798, dp-lake P3; #810, #811, #812; #799, delivery and append; #818, managed reads and reader leases; #845, the reads' egress proxy; #851, the abandoned metadata read's bound; #824, the managed retire ceiling; #858, the settlement tick's socket bound; #866, the abandoned read's derived end; #822, the apply returns an outcome on every path; #814, discard and purge-draft audited; #878, the catalog connection's socket bound; #939, the protection window from configuration; #935, the sort columns; #967, the truncate partition refusal; #958, the remover; #957, maintenance runs; #956, profiles)
**Owner:** datapipelines.co core
**Purpose:** What a managed lake table's definition is, how it moves through its lifecycle, how an environment brings
its Iceberg table to the released definition, which schema changes are applied, and how a pipeline publishes an
immutable delivery. The design is the dp-lake design record (§5–§9); definitions are §1–§5 and delivery/append is §6–§8.

**Scope at v1.0:** the definitions exist as a service in the `lake` module ([Module Structure §5.21](module-structure.md#521-lake)).
No REST route, MCP tool or screen reaches them yet; the lake surfaces (P8) add them with their roles. The storage is
[Metadata DB §4.48–§4.52](metadata-db.md#448-lake_namespaces); the vocabularies are [Enums §46–§48](enums.md#46-laketablestatus--a-managed-lake-tables-lifecycle);
the error codes are [Pipeline Contract §13.25](pipeline-contract.md#1325-managed-lake).

---

## 1. The definition body

A table's definition is versioned like every authored artifact: DRAFT, then RELEASED, then possibly DISCARDED
(`lake_table_definition_versions`, one DRAFT per table). The body is a JSON object with four keys; any other key, at
any level, is refused. Parsing consumes one complete JSON value; trailing values are refused.

```json
{
  "fields": [
    {"id": 1, "name": "id", "type": "BIGINTEGER", "required": true, "doc": "order id"},
    {"id": 2, "name": "customer", "type": "STRING", "tags": ["pii"]},
    {"id": 3, "name": "amount", "type": "DECIMAL", "precision": 12, "scale": 2},
    {"id": 4, "name": "ordered_at", "type": "TIMESTAMP", "required": true}
  ],
  "partition": [{"field_id": 4, "transform": "day"}],
  "identifier_field_ids": [1],
  "sort_field_ids": [4, 1]
}
```

| Key | Rule |
|---|---|
| `fields` | Required, 1–500 objects with keys `id`, `name`, `type`, `precision`, `scale`, `required`, `doc`, `tags`. `id`, `name` and `type` are required |
| `fields[].id` | The column's Iceberg field id, the same on every environment. A table's first definition numbers its fields 1..n in listed order (Iceberg numbers a new table that way). A later version keeps the released version's ids; a new column takes the next id after the highest, in listed order. Unique |
| `fields[].name` | `[a-z0-9_]{1,63}`, unique in the body |
| `fields[].type` | One of the closed type list below |
| `fields[].precision`, `scale` | Only on `DECIMAL` (precision 1–15, optional — absent means the approximate decimal) and `BIGDECIMAL` (precision 16–38, required); `scale` is in 0..precision and present exactly when `precision` is |
| `fields[].required` | Boolean, default `false` |
| `fields[].doc` | At most 1,000 characters; no control character but newline and tab |
| `fields[].tags` | At most 16 tags, each `[a-z0-9_]{1,63}`, unique. Tags are DP's semantics; they are not written to Iceberg |
| `partition` | Optional, at most 8 objects `{field_id, transform}`. `transform` is `identity`, `year`, `month`, `day`, `hour`, `bucket[N]` or `truncate[N]` (N in 1–10,000, written without leading zeroes), and must be one Iceberg accepts for the column's type. The same field takes at most one time transform; no entry repeats. A numeric `truncate[N]` stays valid, but an append refuses the lowest values of the column's range that it cannot hold ([§7](#7-append-publication)) |
| `identifier_field_ids` | Optional ids of required fields, each listed once; an approximate `DECIMAL` cannot be one |
| `sort_field_ids` | The table's sort columns: 1–4 field ids of the body, each listed once, in significant order; a sort column may be optional. Required while the table has never been released (`missing`); an empty array is `empty`, more than 4 `too_many`, a non-integer `wrong_type`, an id no field has `unknown_field`, a repeat `duplicate`, and a `BINARY` field `unsupported_type`. An approximate `DECIMAL` is refused `unsupported_type` too, for now: the engine that will write the rows in order returns every `-0.0` of a floating sort key as `0.0` ([#971](https://github.com/msabiransari/datapipelines/issues/971) lifts this). Once the table has been released, the list is fixed: see below (`locked`) |

Field-ID allocation for corrections after a rejected release has a remaining workflow limitation ([#820](https://github.com/msabiransari/datapipelines/issues/820)); the staged-shape check refuses a mismatch before commit.

The closed type list maps each DP logical type to exactly one Iceberg type:

| DP type | Iceberg type |
|---|---|
| `BOOLEAN` | `boolean` |
| `INTEGER` | `int` |
| `BIGINTEGER` | `long` |
| `DECIMAL` (no precision) | `double` |
| `DECIMAL(p,s)`, p ≤ 15 | `decimal(p, s)` |
| `BIGDECIMAL(p,s)`, 16 ≤ p ≤ 38 | `decimal(p, s)` |
| `STRING` | `string` |
| `BINARY` | `binary` |
| `DATE` | `date` |
| `TIME` | `time` |
| `TIMESTAMP` | `timestamptz` |

`NULL` is not a column type. Iceberg types with no DP spelling (`float`, `timestamp` without zone, `uuid`, `fixed`,
nested types) cannot be defined, and a table holding one cannot be read back as a definition.

**Sort columns are fixed by the table's first release.** The first version EVER released (the lowest version with a
release time, RELEASED or DISCARDED) fixes `sort_field_ids`: every later draft, and every later release, must carry
exactly its list, the same ids in the same order, or is refused `locked`; another order needs a replacement table.
Discarding that release frees nothing. Before the first release the author may change the list freely. A table first
released without the key (a legacy table, stored before the key existed) stays without it: a draft that adds the key
is `locked`, and the table stays unsorted. The order is always ascending with nulls first, per column; the first apply
writes it as the Iceberg table's sort order, and nothing changes it afterwards (§4). A sort column cannot be dropped
(its id would be `unknown_field`) and cannot leave the list (`locked`).

**Not in the body:** the table's name and namespace (they are the table row's), Iceberg properties (DP sets a closed
list itself, `dp.definition.version` among them, and one `write.metadata.metrics.column.<name>` = `truncate(16)` per
sort column, so every writer keeps the sort columns' bounds) and the table's maintenance and write tuning (its
profile, §11; the table row names it in `profile_id`, and its `policy_json` holds only the two pause keys).

**Validation before storage.** A body is parsed and checked before anything is written; the first broken rule is
refused as `lake.definition.invalid_body` with `details.path` (for example `fields[2].precision`) and `details.reason`
(`grammar`, `duplicate`, `id_sequence`, `unknown_key`, `unknown_type`, `out_of_range`, …). An invalid body never
reaches `body_json` or `body_hash`. A valid body is stored in canonical form; `body_hash` is the SHA-256 of the
stored JSON, and every later write names the hash it read (optimistic concurrency).

---

## 2. Lifecycle

`D` is a DRAFT, `R` RELEASED, `X` DISCARDED; `cur` is the table's current definition version. **dev** is an
authoring deployment (`datapipelines.deployment.authoring-enabled=true`); **hard** is any other. In a hard deployment every
verb below is refused `lake.definition.authoring_disabled`, with nothing changed: definitions reach it only by
promotion (P9). Lifecycle mutations lock the managed-table row before version rows, so eligibility checks serialize with apply claims and final stamps.

| Before | Event | Posture | Outcome | After |
|---|---|---|---|---|
| no table | create (namespace, table, body) | dev | allowed: the namespace row is created if absent, the table row and `1D`, `apply_state` NULL. Nothing in Iceberg yet | `1D cur=∅` |
| table exists | create, same names | dev | refused `lake.definition.name_taken` | unchanged |
| `1D cur=∅` | write (body, hash of `1D`) | dev | allowed: `1D` is overwritten in place | `1D cur=∅` |
| `1D cur=∅` | write with another hash | dev | refused `lake.definition.version_conflict` | unchanged |
| `1D cur=∅` | release(v1) | dev | allowed: `1R`, `cur=1`, `pending`; then applied (§3) | `1R cur=1` |
| any draft `nD` | release(vn) of a draft whose stored `sort_field_ids` break the sort-column rule (§1) against the table's first version ever released — a draft stored before the rule, or none when the table was never released (`missing`) | dev | refused `lake.definition.invalid_body` (`details.path` `sort_field_ids`); nothing released, no audit row. The other rules held when the draft was stored and are not re-run | unchanged |
| `1D cur=∅` | purge draft | dev | allowed: the draft goes; a table left with no version goes, and a namespace left empty goes | no table |
| `1R cur=1` | write (body, hash of `1R`) | dev | allowed: copy-on-write opens `2D` | `1R 2D cur=1` |
| `1R cur=1` | write with another hash | dev | refused `lake.definition.version_conflict` | unchanged |
| `1R cur=1` | release(v1) | dev | refused `lake.definition.not_draft` | unchanged |
| `1R 2D cur=1` | write (body, hash of `2D`) | dev | allowed: `2D` is overwritten in place | `1R 2D cur=1` |
| `1R 2D cur=1` | release(v2) | dev | allowed: `2R`, `cur=2`, `pending`; then applied (§3) | `1R 2R cur=2` |
| `1R 2D cur=1` | release(v2) naming a stale hash | dev | refused `lake.definition.version_conflict` | unchanged |
| `1R 2D cur=1` | purge draft | dev | allowed: `2D` goes, the table stays | `1R cur=1` |
| `1R cur=1` | purge draft | dev | refused `lake.definition.not_draft` | unchanged |
| `1R 2R cur=2`, applied here = 1 | discard(v2) | dev | allowed: `2X`; `cur` falls to the highest RELEASED (1); the state becomes `applied` (1 is applied here) | `1R 2X cur=1` |
| `1R 2R cur=2`, applied here = 2 | discard(v2) | dev | refused `lake.definition.applied_version`: this environment's Iceberg table holds v2 | unchanged |
| any current version, `applying` | discard(any version) | dev | refused `lake.definition.table_busy` | unchanged |
| `1R 2D cur=1` | discard(v2) | dev | refused `lake.definition.not_released` | unchanged |
| `1R cur=1`, the table changed outside DP (drift) | write the live shape (hash of `1R`), release(v2) | dev | allowed: `2R`, `cur=2`; the apply sets only the table's `dp.definition.version` (§4 rule 2) | `1R 2R cur=2`, applied here = 2 |
| any | any verb on an unknown table or version | both | refused `lake.definition.not_found` | unchanged |
| any | any verb | hard | refused `lake.definition.authoring_disabled` | unchanged |

Release writes `lake.definition.released`, discard `lake.definition.discarded` and purge-draft
`lake.definition.draft_purged`, each in its own transaction with the acting user and the surface it came through
([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)): a failed audit insert rolls the step back, and a
refused step writes no row. The apply that follows a release writes its own event. A release's answer carries the apply's outcome; a refused apply never
undoes the release (landed is not applied, dp-lake record ruling 8).

---

## 3. Apply states

`apply_state` says whether THIS environment's Iceberg table holds the table's current definition. It is NULL until
the first release.

```
release / promotion landing ──► pending ──(attempt owns the row)──► applying ──► applied
                                   ▲                                   │  └────► failed
                                   └──────── retryable give-up ◄────────┘
```

| From | Event | To |
|---|---|---|
| NULL, `applied`, `failed`, `pending` | a release makes a version current | `pending` (`applying` stays `applying`) |
| `pending`, `failed`, `applying` | an attempt marks the row with its operation id | `applying` |
| `applying` | the attempt finds the Iceberg table already at the definition (a crash after its commit) | `applied`, no commit |
| `applying` | the attempt finds the table's shape already equal to the definition under another version stamp, or none (drift adopted, a tags-only version) | `applied`, one commit that sets only `dp.definition.version` |
| `applying` | the attempt commits the change | `applied` |
| `applying` | the change is refused (§4), drifted, or refused by Iceberg before the commit | `failed`, `apply_error_json` holds `code` and the detail |
| `applying` | the namespace or table lease is busy past the wait, the catalog fenced or restored, or a step before the commit failed with an error the catalog does not classify (`lake.definition.apply_unclassified`) | `pending` |
| `applying` | the attempt cannot say what it left: the catalog connection failed while the commit was in flight, the commit step failed with an error the catalog does not classify, or DP's metadata store failed at or after the `applying` mark so the row could not be stamped (`lake.definition.apply_unclassified`) | stays `applying`; a verify settles it |
| `applying` | a verify finds no catalog revision by the attempt's operation | `pending` |
| `applying` | a verify finds the attempt's revision | `applied` |

**One attempt.** An attempt takes the NAMESPACE lease first when the Iceberg namespace does not exist yet, then the
TABLE lease `<namespace>.<table>`, polling until the earlier of `datapipelines.lake.admission.lease-wait-seconds`
and the attempt's deadline. A lease still held by another operation ends the attempt `table_busy` with nothing
changed. Each try waits at most `datapipelines.lake.catalog.lock-timeout-seconds` on a lease row that another
transaction holds, such as a dead attempt's catalog transaction that is still open, and then counts as busy (#812).
The final row-lock wait can therefore finish up to one lock timeout after the polling horizon, including the
attempt's deadline. It marks the row `applying` under its own operation id, creates the namespace if needed, heartbeats the
table lease, plans (§4), commits through the fenced catalog, and only then stamps the row. Every stamp is conditional
on `apply_state = 'applying'` AND the attempt's own operation id: an attempt that was overtaken (a newer release, a
verify) changes nothing. Leases are released when the attempt ends, whatever its outcome.

**Crash between commit and stamp.** The catalog's revision log records every commit with its operation id. If the
process dies after the commit, the row stays `applying`. A later attempt finds the table already at the definition
(same shape, `dp.definition.version` = the version) and stamps `applied` without a commit; a verify of the dead
attempt reads the revision log and stamps `applied` (a revision exists) or `pending` (none does). A verify leaves
alone a row whose table lease is still held, or whose lease row another transaction holds past the lock timeout.

**An unclassified failure at any step.** Any step of the apply can fail with an exception that is not one of the
catalog's refusals: the release's deadline read, opening or closing a catalog session, loading or planning the table,
the commit, or DP's own metadata store (the leases, the `applying` mark, the stamps). The attempt never throws it: the
failure is logged once at ERROR naming the step ([Observability §3.4R](observability.md#34r-the-lake-table-definition-apply-events-798))
and the attempt's outcome is `lake.definition.apply_unclassified`. Before the commit nothing was committed, so the row
is put back to `pending`, or stays `pending` when it was never marked. From the commit on, or wherever the row could
not be stamped, the outcome is unknown and the row is left for a verify, which settles an `applying` row from the
revision log; a row whose mark never landed stays `pending` for the retry verb (P8). A failed lease release or table
session close is logged and dropped: the leases end by their own deadline and the outcome stands. A namespace session
that fails to close ends the attempt `pending` before the table step. A release that reaches any of these returns the
outcome rather than throwing, because its landing has already committed; after the landing it refuses only a table or
version that does not exist (`lake.definition.not_found`).

**A catalog server that never answers (#878).** Every catalog connection is opened with pgjdbc's `socketTimeout` =
`datapipelines.lake.catalog.statement-timeout-seconds` + 5 s — B, 35 s as shipped — derived in one place (the fenced
pool) and read back from the connection before the lease settings are sent; a connection that does not report B is
closed and refused, never used. The server-side `statement_timeout` cannot end a statement on a server that accepts
and never answers: the catalog sets no JDBC `queryTimeout`, so the driver sends no cancel, and the read ends at B
alone with SQLState `08006` (measured: 8.0 s at a 3-second setting, pgjdbc 42.7.13, a paused server). An apply whose
session open, namespace create, table read or table create ends this way returns `pending` with
`lake.catalog.outcome_unknown`, and the next attempt reads the catalog again before it decides (a create that did land
is found by that read); a table commit that ends this way is unknown and stays `applying` for a verify (the table
above). Nothing is replayed (design record §8a).
**Opening a connection.** The bound is on the socket before the first handshake read, so a silent server ends the
open with `08001` (connect), not `08006` (an established session): within B under `sslmode=disable`, and within
`min(sslResponseTimeout, B) + B` under the default `sslmode=prefer` — the SSL request's wait, then the driver's own
attempt without TLS, which is handshake fallback and sends no catalog statement (measured at B = 6 s: 6.0 s and
11.0 s). Each TCP connect is bounded separately by `connectTimeout` (the driver's default 10 s), and
`gssEncMode=prefer` or `require` adds one `min(gssResponseTimeout, B)` wait per attempt. `sslmode`, `connectTimeout`
and `sslResponseTimeout` are the inputs of this envelope; a catalog URL carrying `socketTimeout`, or a zero
`connectTimeout`, `sslResponseTimeout` or `gssResponseTimeout` (zero means no timeout), is refused when a catalog
session opens ([Configuration §3.36](configuration.md#336-managed-lake-dp-lake-785)). The assumptions: pgjdbc honours
`socketTimeout` (read back on every connection) and applies it before the handshake (`ConnectionFactoryImpl.tryConnect`
at 42.7.13).

**What `applied` writes,** in one transaction with the stamp: the column semantics projection
(`lake_column_semantics`, rebuilt from the definition: field id, name, DP and Iceberg type, required, doc, tags) and,
when the lake's datasource row exists, the `lake_tables` registry row (`origin = 'managed'`, namespace
`[lake, namespace]`, the current revision's metadata location and sequence, never moved backwards), and the
`lake.definition.applied` audit row. `failed` writes `lake.definition.apply_failed`.

---

## 4. The supported-change policy

An apply compares three shapes by field id: the Iceberg table's current one (S: its columns, partition fields,
identifier fields and sort order, the order read whole, with each column's transform, direction and null order), the
definition it was last applied from (named by its `dp.definition.version` property) and the intent (the current
definition).

1. S equals the intent and the property already names the version: nothing to do, `applied`.
2. S equals the intent but the property names another version, or none: one commit sets only the property (no
   schema, spec or data change), `applied`. A version that changes only tags takes this path too.
3. S differs from the definition it says it was applied from: someone changed the table outside DP.
   `failed`, `lake.definition.drift`, with `details.diff` listing the changes between that definition and S.
   The exit is rule 2: write the live shape as a new draft and release it. Build the draft on the version applied
   here; a rejected current release that holds other field ids is discarded first ([#820](https://github.com/msabiransari/datapipelines/issues/820)).
   A sort order changed outside DP has no such exit, since the sort columns are fixed by the first release (§1): put
   the order back with the tool that changed it, and the next apply finds the shapes equal.
4. Otherwise the change from S to the intent is classified, and only a COMPATIBLE change is staged and committed.

| Change | Class | Refusal |
|---|---|---|
| add an optional column | compatible | — |
| add a required column to a table with no rows | compatible | — |
| widen `int`→`long`, `float`→`double`, a decimal's precision (same scale) | compatible | — |
| change a column doc; change tags | compatible | — |
| add a partition field (it applies to new data) | compatible | — |
| drop a column | reviewed | `lake.definition.reviewed_change` |
| rename a column | reviewed | `lake.definition.reviewed_change` |
| reorder columns (a new column placed before an existing one included) | reviewed | `lake.definition.reviewed_change` |
| make a column optional | reviewed | `lake.definition.reviewed_change` |
| remove or reorder partition fields | reviewed | `lake.definition.reviewed_change` |
| add a required column to a table with rows | breaking | `lake.definition.breaking_change` |
| narrow or otherwise change a type; change a decimal's scale | breaking | `lake.definition.breaking_change` |
| make a column required | breaking | `lake.definition.breaking_change` |
| change the identifier fields | breaking | `lake.definition.breaking_change` |
| change the sort columns | breaking | `lake.definition.breaking_change` (authoring refuses it earlier, `lake.definition.invalid_body` with reason `locked`, §1) |

Whether a table holds rows is read from its current snapshot. A table with no snapshot has none. Otherwise
the snapshot summary's `total-records` decides, and a summary without that key counts as rows, the conservative answer
for the required-column rule.

A version holding several changes takes the strictest class. Reviewed changes are refused in the first release; a
breaking change's remedy is a replacement table and deprecating the old one. A refusal's `apply_error_json` carries
`change_class`, `table_has_rows` and `changes`, each change `{kind, field_id, detail}`; a drift carries `reason`,
`applied_definition_version` and `diff` in the same change shape. The staged transaction is read back before the
commit: if it does not equal the intent, the apply fails as drift (`reason: staged_mismatch`) with nothing committed.
A change Iceberg itself refuses while staging fails as `breaking_change` (`reason: iceberg_validation`).

Every release that changes anything is one Iceberg commit, a doc- or tags-only release included (the version
property moves).

---

## 5. Names

| Name | Form | Where |
|---|---|---|
| Namespace | `[a-z0-9_]{1,63}`; one level | `lake_namespaces.name`, the Iceberg namespace |
| Table | `[a-z0-9_]{1,63}`, unique in its namespace | `lake_tables_managed.name`, the Iceberg table |
| Table location | `<storage root>/<namespace>/<table id>` (the table row's UUID, never a name: a dropped and re-created name gets a new location) | the Iceberg table's `location` |
| Table lease | `<namespace>.<table>` | `lake_leases` (kind `table`) |
| Registry namespace | `[<lake name>, <namespace>]` | `lake_tables.namespace` |
| Lease operation | `lake.definition.apply`, `lake.definition.verify` | `lake_leases.operation` |

Both names are checked before any catalog call or SQL; a name outside the grammar is refused
`lake.definition.invalid_body` with `details.path` `namespace` or `table`.

---

## 6. Deliveries

A delivery is one immutable folder under the configured landing prefix:
`<landing-prefix>/<feed>/<delivery-id>/`. The sender uploads one or more CSV files or one or more Parquet
files, then an **empty `_done` object last**. Mixing file formats is refused. Feed and delivery id each match
`[a-z0-9_-]{1,64}`; neither can contain a slash. Keep source folders for at least the recovery protection window
and enable bucket versioning. DP never moves or deletes landing objects.

The guarantee requires the sender to leave the folder unchanged after `_done`. DP records a trusted,
paginated S3 listing of exact keys, sizes and ETags; its fingerprint covers the sorted triples. It materializes
only those exact objects into `delivery` and exposes their metadata as `delivery_files(key, size, etag)`.
After the delivery node's SQL, DP lists again. A changed fingerprint fails as
`lake.delivery.changed_during_read` with phase `staging`. This detects changes after reading; it is not an
atomic conditional object read and cannot detect a sender changing an object and restoring its original ETag
between observations. Versioning helps the operator investigate; this release does not pin reads to version ids.

One pipeline may declare one delivery node, a DQL node using DuckDB tempdb:

```yaml
settings:
  tempdb:
    engine: DUCKDB
nodes:
  - id: land
    type: DQL
    source: tempdb
    delivery: {feed: orders, id: "$batch_id"}
    template: {id: ingestion/read_delivery.sql, version: 1}
    output: {target: tempdb, table: staged_orders}
```

The template reads `FROM delivery`; author SQL never needs a path. `id` may be literal or the standard
`$context_key` reference to a resolved parameter. The resolved value is validated before any S3 request.
DP loads extensions and writer credentials on its trusted root, materializes the tables, then locks the
database before author access. External reads and configuration changes remain refused on author connections.

Admission holds one ingestion slot and reserves
`max-delivery-bytes × staging-expansion-factor` scratch **before listing or opening the database**. Both share
one `lease-wait-seconds` wait budget; exhaustion is retryable `lake.capacity.busy`. Oversize input is refused
from the listing. The database closes at execution cleanup; a quarantined native lease keeps its scratch and
ingestion slot until physical finalization. The PostgreSQL admission clock fixes the job deadline once;
later lease acquisition and heartbeat do not extend it.

The permanent ledger key is `(table, feed, delivery-id)`. The same fingerprint after PUBLISHED skips that
table's append and reports **already published**. A different fingerprint under the same key always refuses
`lake.delivery.changed`; corrected input needs a new delivery id. A FAILED delivery may retry with a fresh
operation id; permanent attempts retain the older evidence. An unresolved live publication stays busy.
Targets must be ACTIVE: DEPRECATED refuses ingestion, and ARCHIVED or PURGED is unavailable.

## 7. Append publication

Each target is a DQL/tempdb node with
`output: {target: datasource, datasource: <managed-lake-name>, table: "namespace.table", mode: append}`.
There is at most one append per target table; `replace` is refused. Every append's transitive `depends_on`
must contain **every non-append node**, including the delivery and all validation nodes. A SCRIPT node with
rejects must be strict. Append nodes may run in parallel with each other; publication is atomic per target
table, not across several targets. An author who filters rows in SQL publishes the filtered result; those
omitted rows cannot be published later under the same delivery id.

The effective member or key role needs `lake.append` as well as execute permission. Authors, workspace admins,
super admins and the system actor hold it; viewers and promoter keys do not. Entry points stamp the grant,
children inherit it, schedule save checks it, and the runner refuses a missing grant before taking a lease.
The grant never derives from the user id alone. Draft execution on an authoring server is permitted and its
recipe records the actual draft body. Receivers may append to their applied definitions.

The DAG evaluates author SQL once into a stable native result and preserves its original column labels and
types. Under a TABLE lease, the runner conditionally claims RECEIVED → PUBLISHING and validates before creating files.
Names match using DuckDB's ASCII case-insensitive identifier rule; ambiguous or extra columns are refused.
Missing optional fields become null; missing required fields, required nulls and incompatible or narrowing
types are refused. Decimal precision/scale must fit the applied Iceberg schema. Two scans of the stable result
perform the required-null prepass and file writing without rerunning volatile author SQL.

**A value a `truncate` partition cannot hold (#967).** Iceberg's `truncate[N]` keeps the column's type and rounds
toward negative infinity, so at the lowest values of an `INTEGER`, `BIGINTEGER`, `DECIMAL(p,s)` or `BIGDECIMAL`
column's range its result leaves the type: a decimal gains a digit (`DECIMAL(15,3)` `truncate[2]` of
`-999999999999.999` is `-1000000000000.000`), which the catalog commit then fails on; an integer wraps far above
the row (`INTEGER` `truncate[10]` of `-2147483648` is `2147483646`), which would publish a partition that a reader's
pruning skips. Which values are hit depends on N (`truncate[3]` hits none of a decimal's; `truncate[2]` none of an
integer's). The first scan therefore also applies Iceberg's own function to every such value, as it would be written,
and refuses the delivery with `lake.append.partition_value_out_of_range` and a per-partition-field row count, after a
required-null refusal and before any file is written. Strings, `identity`, `bucket` and the time transforms are not
checked. The definition stays valid (owner ruling 2026-11-13). The refusal is temporary: it goes when Iceberg keeps
the value inside its type, decimal in apache/iceberg issue 17027, `int` and `long` in issue 13105 (closed as not
planned) and 17027's discussion; a tripwire test in the lake module goes red when either changes.

Files go under the table's `data/` prefix, using Zstandard Parquet, stamped unsorted (sort order id 0, also on a
table with sort columns: rows are written in arrival order until ordered appends ship, and a stamp never claims an
order the file was not written in), and its applied
partition transforms: identity, year, month, day, hour, bucket or truncate. Exceeding
`max-open-partitions-per-append` fails. An empty result still creates a publication snapshot and permanent
duplicate evidence, with no data file. The worker never deletes its files, even after a failure or unknown
outcome. Every Iceberg FileIO refuses and records library deletion requests for later maintenance.

Before committing, DP conditionally creates
`<table-location>/dp/recipes/<feed>/<delivery-id>/<operation-id>.json` with `If-None-Match: *`.
A precondition failure is `lake.append.recipe_exists`, a bug signal; DP neither overwrites nor retries that PUT.
The recipe contains the trusted manifest/fingerprint, actual pipeline body/hash and version or draft marker,
actual referenced template and child pipeline versions/hashes, resolved parameters, applied definition
version/body, operation id, worker, time and restore epoch. Raw execute inputs do not invent parameter-set pins;
the current execution request has no parameter-set provenance. The snapshot records the recipe key and SHA-256.

Parameters support optional **`redact_in_recipe: false` by default**. Opting in replaces that parameter's resolved
value, default/constraints and supplied child override with `<redacted>` in copied recipe content. Redaction
does not mutate the executed body or its recorded hash; omitted/false metadata preserves legacy canonical hashes.
`exact_replay_possible=false` and redacted paths identify recipes requiring
operator-supplied inputs. This is declared-input redaction, not a scrubber for secrets placed in SQL text,
descriptions or data. This parameter model has no separate secret logical type.

The recipe's replay flag is also false when a parallel sibling has not resolved its templates yet;
`unresolved_nodes` names those nodes. A recipe records only artifact hashes actually observed before publication,
and never guesses a future draft's contents. Such a recipe needs directed reprocessing. Once every node has
resolved, the flag can be true, provided no input was redacted. Recovery must still verify that every recorded
artifact hash remains available; a draft reference can change after execution.

Publication refreshes the table and checks permanent revision/attempt evidence before each bounded CAS retry.
One Iceberg transaction appends all files and sets `dp.delivery.key`, `dp.operation.id`, `dp.recipe` and
`dp.recipe.sha256`. A normal return stamps PUBLISHED. An in-memory CAS conflict may retry three times;
classified authority refusals fail, while an unconfirmed commit stays UNKNOWN. Classification uses exception
types and SQLSTATEs through the bounded cause chain, never localized messages. Periodic heartbeat preserves
only a live lease, capped by the admitted deadline; loss, deadline or cancellation stops further mutations.

**A catalog server that never answers (#878).** Publication's catalog session is bounded as §3 states: each read and
the commit end at B = `catalog.statement-timeout-seconds` + 5 s with `08006`, and opening the session ends with
`08001` within §3's envelope. Before the commit is entered — the session open, the table load, the first refresh —
the delivery ends `FAILED` in phase `publish`: this operation sent no commit, and its files stay for maintenance.
Once the commit is entered — its CAS, or a refresh before a later CAS retry — the delivery is `UNKNOWN` and settlement
(§8) decides it from the revision log; nothing is replayed. The ended client says nothing about the server: a server
that resumes before its own `statement_timeout` can still commit the statement the client gave up on, which is why the
outcome is unknown rather than failed (measured once: the interrupted commit left no revision row, its backend
cancelled by its `statement_timeout` when the server resumed). The bound changes no grace rule: commit completion was
never clock-bounded (design record §8a), so `orphan-grace-hours` keeps its argument.

## 8. Settlement and recovery

Permanent `lake_table_revisions` rows, joined with delivery attempts, are the commit evidence. Snapshot expiry
cannot erase it. A request settles only the delivery it waits on, through
`DeliverySettler.settleDelivery(deliveryId, deadline)`: after its own publication, and before retrying a delivery key
whose earlier attempt is unresolved; the sweep settles the rest of the table. A live lease stays unresolved. For a
dead PUBLISHING/UNKNOWN attempt, the settler first takes over the table lease, waiting out the fence transaction,
then reads the revision log in a **separate
statement**: evidence means PUBLISHED, absence means FAILED(publish). A lock timeout leaves it unresolved;
expiry alone never establishes failure. Conditional state and operation predicates protect newer attempts.

Normal execution cleanup marks unstarted RECEIVED attempts FAILED with listing, staging or validation phase.
After a crash, the sweep does so once the execution is terminal or gone. An unknown attempt is never treated
as safe to retry solely because its worker stopped. Recipes and data files remain for recovery/maintenance.

The `LakeSettlementScheduler` starts a tick 60 seconds after the previous one ends, in normal mode. A tick visits a
rotating page of up to 100 managed tables and, per table, one page of up to 50 unsettled deliveries after that
table's row cursor, under a ten-second work budget. The budget is minted from the database clock with a JVM
monotonic mirror: its work checks read the mirror and send no statement, while every lease and verdict still compares
against the database clock. It is checked before each of the protection step's two statements, the table page, each
table (before and after the table's mode read), the table's target and delivery page reads, each delivery and each statement group inside it,
the projection repair, and every statement group inside the P3 `applying` verification and the semantics repair. Once
the budget is spent nothing new starts: a verification or repair stopped this way reaches no verdict and stamps
nothing, releases any lease it took and leaves its row for a later tick.

**A tick ends at most 16 seconds after its budget**, with PostgreSQL answering and the default Redis timeouts:

| Part of the overrun | At most | Why |
|---|---|---|
| The unit in flight at the deadline | 6 s | A statement group's or lease acquire's five-second transaction. Spring rounds a transaction's remaining time up to whole seconds for each statement, so five seconds can run to almost six. Whichever timer ends a lease acquire's wait, its lock timeout or this one, the acquire answers busy with the row unchanged. |
| The statement that must follow it | 5 s | The release of a lease that unit holds, or a refused takeover's deadline comparison. A takeover that was refused holds no lease and releases nothing. |
| The `last_sweep_at` heartbeat | 5 s | One statement with its own five-second timeout. |

The tick's first mode read runs before the budget is minted, so a tick's whole wall clock is at most 31 seconds: 5, then
10, then 16. The protection step is two statements, the window alignment and the stamp, each with its own five-second
bound. The budget's clock starts before the statement that mints it, so that statement and the alignment can spend it
all; the budget is therefore checked again before the stamp, and a stamp in flight at the deadline is one five-second
statement, inside the first row of the table. A projection repair in flight at the deadline holds no lease: its transaction is followed by one
invalidation publish, at most 2 s by `datapipelines.redis.command-timeout`'s default and 2 s more to reconnect, and the
heartbeat, 15 s in all. Raising those Redis timeouts widens that one path.

The Iceberg metadata read leaves the tick's thread. Verification and repair read the table's `metadata_location` from
the catalog row with a five-second statement, open no catalog session, and read the metadata JSON on one dedicated
daemon thread, `dp-lake-metadata`, waiting at most what remains of the budget. So an object store that stops answering
adds nothing to the 16 seconds. A read that outlives the wait is abandoned and counted
(`lake.settle.metadata_read_stuck`). It holds no lease and no database connection, and it stops itself: the read
checks between every chunk the store returns, so an abandoned read ends at its next chunk. On a store that trickles
bytes it therefore ends within one gap between bytes; closing the stream then reads at most one more chunk, under the
same timeout, before the client aborts the connection. A read blocked on a store that has stopped answering sees the
stop only when the client gives up. Its conditional network-wait envelope is derived in
`SettlementObjectReadEnd.stalledReadEndByMillis`, in milliseconds starting at the first GetObject request.
The stalled-store model assumes prompt request writes and TLS setup, no throttles or redirects, and each request
either times out waiting for headers or returns complete headers promptly followed by a stalled body. Headers and
bodies do not trickle or make intermittent progress in this model. From the effective catalog properties and
Iceberg's configured SDK retry policy, each request allows six attempts at 2 000 ms connect plus 10 000 ms socket,
and five full-jitter backoff caps of 100 + 200 + 400 + 800 + 1 600 ms (100 ms base, 20 000 ms ceiling):
75 100 ms per request. Each answered stream adds three socket timeouts — body read, close's abort probe, and close.
Iceberg 1.12.0 permits three body-read retries, each closing the old stream and reopening with another request.
Conservatively counting four requests and four stream tails gives
4 × (75 100 + 3 × 10 000): **at most 420 400 ms — about 420 seconds — after its first request in this model**.
This is model arithmetic, not an observed stalled-store runtime upper bound. The earlier ~62 s measurement
characterized one unanswered request with real jitter; it did not prove a maximum.
DNS resolution, credential-chain fetches, blocked request writes, TLS setup, JVM scheduling and local processing
have no total bound here; the number is conditional on those phases completing promptly and is no arbitrary
network or end-to-end guarantee. Throttled responses use a separate backoff; trickling headers are outside this
model, and a body returning chunks follows the ordinary abandonment stop above. The SDK API-call and attempt
timeouts are unset, so no enclosing total timer covers these phases. The pinned private Iceberg reopen policy,
SDK version, configured retry/backoff policy, abort probes and close counts are guarded by
`SettlementObjectReadEndCompatTest`. Neither the 16 s scheduler overrun nor the 31 s whole-tick bound includes
this off-thread work. The file is read whole and capped at 64 MiB of JSON after decompression; a larger one fails the read,
so every tick defers that table as failed (`lake.settle.deferred`, cause `MetadataFileTooLarge`) until its metadata
file is back under the cap. The cap is this read's alone: the catalog's own table loads are not capped. While an
abandoned read runs, every table's verification and repair are deferred on every tick without sending a read: the one
thread is the cap, so one stuck read delays verification for the whole lake, while deliveries keep settling. Its result
is discarded; once it has ended (`lake.settle.metadata_read_ended`, `cause=abandoned`) the next tick reads the file
again from its first byte.

A tick cannot promise more than this. Against a stalled PostgreSQL every statement it started runs to its timeout;
a connection checkout is bounded by the metadata pool's `connection-timeout` (10 s since #837). Against a server that
accepts and never answers, a timeout's cancel does not end the blocked read: the cancel is a second connection that
server never reads. So every statement the runtime's template sends also carries a socket bound, like the other four
jobs' on this thread (#837, #858): before each statement the template sets its connection's network timeout to the
statement's 5 s plus 5 s. Against that server the statement ends with SQLState `08006` at the later of those 10 s and
its 5 s plus the driver's cancel wait (pgjdbc's `cancelSignalTimeout`, 10 s by default): **15 s after it began**,
observed on the real tick (`LakeSettlementMetadataBoundsE2eTest`), which then logs `lake.settle.sweep_deferred`. Inside
one of the runtime's five-second transactions the connection keeps that bound until it goes back to the pool, which
restores its own value, so the transaction's later statements, its joined audit row and its commit or rollback read
under it too. The limit assumes two things: that the driver honours the network timeout (one that refuses it fails the
statement rather than running it unbounded), and the driver's default cancel wait (a longer `cancelSignalTimeout`
moves the 15 s with it). The 16 and 31 seconds above hold with PostgreSQL answering; against a server that has
stopped, these per-statement and checkout bounds are what holds. A healthy tick still reads the store once per table
for the semantics repair's version check.

The row cursor passes over live, busy and failing deliveries and wraps when a page comes back short, so deliveries
behind them are reached on a later tick. Cursors are held in memory; after a restart each table starts again at its
first unsettled delivery. Large backlogs drain across ticks at up to 50 deliveries per table per tick: when every page
fits the budget, a table's N unsettled deliveries are all visited within ⌈N/50⌉ + 1 of its turns, and a pass over
more than 100 tables takes several ticks. The sweep settles deliveries, verifies P3 `applying` rows, repairs semantics
under a table lease only when their applied definition differs, and records `lake_control.last_sweep_at`. Restoring
and replaying modes keep the sweep silent.

The protection window is `datapipelines.lake.retention.protection-days`. In normal mode the sweep first makes
`lake_control.protection_interval` equal to it, writing only when the two differ and then logging
`lake.settle.protection_window_aligned`. It then finalizes protection only for committed, visible supersessions, using
the observation statement's `now()` plus the configured `retention.protection-days`; NULL remains protected until
observed. The column mirrors the setting for whoever reads the catalog; no deadline is computed from it, so an instance
stamps with its own setting even when another instance aligned the row in between (every instance must carry the same
value). A deadline already stamped is never changed, so lowering the setting affects only revisions finalized
afterwards.
Registry repair uses P3's monotone revision-sequence upsert. A changed row bumps `datasources.updated_at` in
the same transaction; local pool retirement and peer invalidation follow the commit. A no-op repair rebuilds
nothing. The authoritative catalog remains the source for managed reads; the registry is its display projection.

## 9. Reads

A managed table is read through the **managed lake datasource**, the one `LAKE` datasource DP creates from
configuration when the lake is enabled ([Datasources §8C.10](datasources.md#8c10-the-managed-lake-datasource), #818,
dp-lake P5). A query names a table `<lake>.<namespace>.<table>`, for example `warehouse.sales.orders` when
`datapipelines.lake.name` is `warehouse`.

**What a reader sees.** Each server reads through a generation: one DuckDB instance whose views were built from the
Iceberg catalog rows at one instant. A query reads the snapshot its table's catalog row named at that instant. A table's
state is read at that instant too: a table archived or restored afterwards keeps its old row below until the
generation is replaced.

| Table state | What a query sees |
|---|---|
| `active` | Its view |
| `deprecated` | Its view; the deprecation notice appears in the table listing's remarks |
| `archived`, `purged`, no catalog row, no lifecycle row, or the lake disabled | No view. A node that names it is refused before it runs with `lake.table.unavailable` (409, `details.table` and `details.reason`) |

**When it sees new data.** After a committed projection repair, the write path retires the local generation and
publishes peer invalidation. Each server also compares its generation with the authoritative catalog on the configured
fixed-delay freshness tick (60 s by default). The next query after retirement builds a new generation; queries already
running retain their original snapshot and its reader pins. If post-publication repair is deferred, the freshness tick
still detects the changed catalog pointer independently of the projection.

**The bound.** The pool reaper retires generations older than `reads.max-generation-age-seconds`,
including generations with no later borrower. It runs on `dp-reaper`, every 5 s, and issues no statement.
The reaper delay is 7 s (cadence plus twice its 1 s pass bound); closes run on `dp-pool-close` threads.
Boot checks the staleness rule: age + 2 × reaper delay + managed retire ceiling + freshness tick must fit
`retention.protection-days × 86400 / 24`. The managed retire ceiling is the one a retired managed generation is held to
(`datapipelines.datasources.managed-retire-ceiling-seconds`, else `node-query-timeout-max-seconds` + 30; #824), not the
general pool ceiling. With shipped values: 900 + 14 + 930 + 60 = 1904 ≤ 25 200 s.
This bounds the age handed to new queries. Reader termination is not a safety premise: DuckDB's
HTTP settings bound a silent store, but cannot bound a trickling response (duckdb-httpfs#519).

**The egress proxy (#845).** When `reads.egress-proxy` is set, every object-store request of the managed datasource
leaves through that proxy (DuckDB's `http_proxy`, set before the generation's lockdown, which then refuses any change).
`deploy/compose.yml` and the Helm chart ship one ON: Envoy, pinned by digest, which resets every stream at
`reads.egress-max-age-seconds` whatever its activity and retires every keep-alive connection at the same age.
A managed read's object-store connection lives at most `egress-max-age-seconds`; a trickling store's connection is
cut there, and the reader ends by the end-by bound below. This
is a hygiene bound, not a safety premise: reader leases are the safety premise. A healthy keep-alive connection older
than the max-age is also cut, and the next request retries on a fresh one (`http_retries`); measured 2026-11-07 with DuckDB 1.5.5,
a query's proxy connections close when the query ends, so within the max-age floor below a healthy query does not meet
one. The bound is per connection, not per query. httpfs retries a cut transfer too, so a store that trickles on EVERY
attempt ends a reader, counted from its first GET, by **end-by = (`http_retries` + 1) × max-age + slack**, where
`http_retries` is the value the LAKE adapter SETs and slack is one per-cut allowance per attempt (the proxy's reset was
measured landing 1.0 s after the max-age; the allowance adds 1 s for the fresh connection's setup) plus the sleeps
between retries. The formula lives in one place, `LakeHttpBounds.egressReaderEndByMillis`, whose test pins the shipped figure, and the
boot line `event=lake.egress_proxy_verified` prints it as `reader_end_by_ms` (measured at a 20 s max-age: three cut
attempts, the reader ended at 63.1 s; without the cap it was still reading at 80 s). The
max-age must be at least `datapipelines.executor.node-query-timeout-max-seconds` + 30 (startup refuses less), so a
healthy response is never cut before its own query's timeout would have ended it. Boot refuses with
`lake.boot.egress_proxy` when the proxy does not answer, declares a different max-age at `GET /dp-egress`, or cannot
reach the store. For a plain-`http://` store endpoint, the proxy sees the signed S3 requests in clear; for `https://`
it sees only `CONNECT host:443`. It holds no credential. The proxy is a temporary accommodation until
[duckdb-httpfs#519](https://github.com/duckdb/duckdb-httpfs/issues/519) lands; deployment:
[Deployment §4.3](deployment.md#43-network-egress).

**Reader pins.** Before serving, a managed generation acquires one reader lease per available table,
pinning the exact catalog metadata location its view reads. A failed pin refuses the build with
`lake.unavailable`. Its UUID identifies the operation. A dedicated daemon thread, `dp-reader-lease`,
heartbeats the instance's rows in one statement every 5 s while any physical handle remains, including
handles retiring, closing or blocked in `close()`. Pins are released only after physical closure is
confirmed. Graceful shutdown keeps the scheduler, scheduled-task registrar and metadata pool alive until every physical
handle is confirmed closed, then releases its rows. A stuck native reader can therefore hold graceful shutdown;
a killed process instead leaves rows that lapse at TTL.
A dead process's rows lapse at TTL; expired or deadlined rows cannot renew. The deadline is
the build instant plus the full protection window. A reader older than one twenty-fourth of that window
logs `lake.reader_lease_overlong`; it stays pinned until its deadline or confirmed close.

The TTL is derived from the metadata pool's actual acquisition timeout and the 5 s statement timeout:
`previous statement tail + 2 × (cadence + acquire wait + statement timeout)`, 85 s for the shipped 30 s acquisition timeout.
This covers a failed round followed by the next successful round under fixed-delay scheduling.
New admission also checks the monotonic age of the last confirmed heartbeat, so a delayed keeper cannot admit a read after protection lapses.
A short heartbeat or a failed round retires the generation, logs `lake.reader_lease_lost` and counts it;
existing readers can continue. A round exceeding its cadence logs `lake.reader_lease_round_slow`.

P6 must treat every live pinned metadata revision as protected regardless of `protected_until`.
`ReaderPins.pinnedLocations` supplies its pins; `SnapshotExpiryFloor.compute` returns both the expiry
floor (the minimum of now minus the window and every protected revision's current snapshot timestamp)
and the protected revisions for the orphan walker. The remover itself belongs to P6.

**Residual.** If an instance stays alive but cannot heartbeat for a full TTL, its pins lapse while
its readers may still be reading. Expiry may then remove a snapshot such a reader holds and the reader
fails with a missing-file error, never a wrong result: a planned scan names files, and a missing file
is an error. The same loss applies when the full protection-window deadline is reached.

**The operator knobs** ([Configuration §3.36](configuration.md#336-managed-lake-dp-lake-785)):

| Key (`datapipelines.lake.`) | Default | Effect |
|---|---|---|
| `reads.max-generation-age-seconds` | `900` | The age bound. Lower means fresher reads and more rebuilds |
| `reads.freshness-tick-seconds` | `60` | How often each server compares its generation with the catalog. Must be below the age bound |
| `reads.memory-limit` | `512MB` | The memory each managed instance may use. It cannot spill to disk, so a query that needs more fails |
| `storage.reader-access-key` / `reader-secret-key` | (empty) | A read-only key pair for the reads; without it the writer pair is used. Read-only is the operator's contract: DP cannot verify the pair's permissions |
| `name` | `lake` | The managed datasource's name and the first part of every table name. A datasource already holding the name refuses the boot (`lake.boot.datasource_name_taken`) |
| `reads.egress-proxy` | (empty) | The `host:port` every managed object-store request leaves through. Compose and the Helm chart set their own proxy. Empty = direct, uncapped |
| `reads.egress-max-age-seconds` | `960` | The age at which the proxy cuts a connection. At least the node query timeout maximum + 30; the proxy must declare the same value or the boot is refused |

---

## 10. Removal and cleanup

The remover (#958, dp-lake P6; `modules/lake`'s `maintenance/remove` package) is the only code that deletes an object
of the managed lake (design record §8b "One remover, one policy"). Iceberg never deletes: the catalog's FileIO refuses
every request (§7) and hands the key on as a hint. All S3 deletes are in one file, `LakeObjectDeleter.kt`, and
`LakeDeletionArchitectureTest` fails when `deleteObject`, `deleteObjects`, `DeleteObjectRequest` or
`DeleteObjectsRequest` appears in any other main source. **No production code calls the remover yet**: the maintenance
run (962) holds the table lease, calls it, and records its result.

**Two passes.** `removeOrphans` reclaims files of a live table that nothing protected reaches; `purge` empties a table
whose status is `purged`. Each takes the table id (resolved from `lake_tables_managed` to its location
`<storage root>/<namespace>/<table id>`, never a path from a request), the caller's held table lease and the run id.
The remover takes no lease of its own.

**The order inside a pass is fixed:**

1. Refuse unless `lake_control.mode` is `normal` and the caller's lease answers a heartbeat.
2. The clock-skew probe (below).
3. A fresh reachability read, inside the pass, on its own statements; nothing read before the call is reused.
4. The listing of every key under the table's location.
5. The candidates.
6. Delete batches of at most 1000 keys, each after a lease check and a mode check. A no stops the pass, deleting
   nothing more.
7. The pass's own marker, last.

**Reachable** (record §9). The protected revisions are the current pointer, every revision whose `protected_until` is
null or ahead of the catalog's `now()`, and every reader-pinned revision (§9 reader leases). From each revision's
metadata file the walker reaches:

- the file itself;
- the direct entries of its metadata log, one level only;
- for every snapshot: its manifest list, its manifests, and every live (`ADDED`/`EXISTING`) data and delete file;
- the statistics and partition-statistics files.

A metadata file or manifest it cannot read fails the pass, and nothing is deleted.

**A candidate** is a listed key under the location that is:

- not reached;
- older than the orphan grace (`retention.orphan-grace-hours`), its age being the catalog's clock minus the listing's
  `LastModified`;
- not under `dp/`.

Keys Iceberg handed the refusing FileIO are hints only. The same tests decide them, and a hint that fails one is counted
as deferred with its reason (`reached`, `young`, `under_dp`, `outside_location`, `not_listed`). A key outside the
location is never deleted.

**The `dp/` rule.** Nothing under `dp/recipes/` is ever deleted, by either pass, at any age. The only other `dp/` keys
deleted are clock markers, each by exact key:

- *The pass's own marker* `dp/clock/<run id>` is deleted at the end (and on a skew refusal), whatever its age, but only
  after a lease check and a mode check both answer yes. Otherwise it is left, and the result says why.
- *Another run's marker* is deleted only when it is older than grace AND its run is terminal (the `RunStates` port: an
  unknown run keeps it).

Any other `dp/` key is never deleted. It is logged once per pass (`lake.removal.unexpected_dp_key`) and keeps a purge at
*more to do*.

**The clock-skew probe.** The probe works in four steps:

1. Read the catalog clock (`clock_timestamp()`, the one function the remover reads time from).
2. PUT the marker.
3. Read the clock again.
4. Read the marker's `LastModified`.

The skew interval is [`LastModified − after`, `LastModified + 1 s − before`]. The pass runs only when both ends lie
within ±3600 s; otherwise it is refused with `lake.maintenance.clock_skew` ([Pipeline Contract
§13.25](pipeline-contract.md#1325-managed-lake)), nothing listed, and its one delete is its own marker under the rule
above.

**Purge** keeps every protection: a revision still protected or reader-pinned keeps its files. It answers *finished*
when every listed key, the pass's own marker included, was confirmed deleted or is a recipe. Otherwise it answers
*more to do*, with the keys left counted by reason: `young`, `reached`, `marker_run_live`, `marker_run_unknown`,
`other_dp_key`, `delete_failed`, `outcome_unknown`, `not_attempted`. Purge never stamps the table.

**What counts as deleted.** Only a key the store's response names in `deleted()`:

- a key in `errors()`, or named in neither collection, is `delete_failed`, and later batches still run;
- a call that throws or whose answer is lost leaves its whole batch `outcome_unknown` and the keys not yet sent
  `not_attempted`, and the pass stops (`stopped: delete_failed`);
- an unconfirmed marker delete leaves the marker.

Nothing throws out of a pass for a delete failure. A later pass lists again and finishes the work.

**The result** (the storage figures' input, 964):

- the counts;
- the stop reason and the marker's fate;
- the purge answer;
- the **inventory**: the listing (key, size, `LastModified`) minus the confirmed deletes;
- the **visits**: one per (key, revision, snapshot) the walker reached, with the kind it was reached as (`metadata`,
  `manifest_list`, `manifest`, `data`, `delete`, `statistics`, `partition_statistics`) and the snapshot id (none for a
  metadata file).

A refused pass returns neither. The events are [Observability §3.4T](observability.md#34t-the-lake-removers-events-958).

## 11. Profiles

A **profile** is a named set of maintenance and write values (design record §11a call 35; [Metadata DB
§4.56](metadata-db.md#456-lake_profiles)). Every managed table names exactly one (`lake_tables_managed.profile_id`,
NOT NULL); every maintenance run and every append of the table reads its tuning from that profile and from nowhere
else, so there is one place to read why a table behaves as it does. Profiles are per environment and never promoted.

**The built-ins.** The migration creates `production` (the default) and `development` (the same values, window
`00:00-00:00`, the whole day). A new table takes the default profile in its own `INSERT`; a new definition version
of a table leaves its profile unchanged. With the lake enabled, the first start copies
`datapipelines.lake.maintenance.window` and `.max-bytes-rewritten-per-run` into `production` once, in one statement
guarded by `seed_pending` (`lake.profile.seeded`); an edited profile is never overwritten, and nothing reads those two
keys afterwards ([Configuration §3.36](configuration.md#336-managed-lake-dp-lake-785)). The boot refuses a cap under
128 MiB, so the seeded profile passes its own rule.

**The keys** (the list is closed; every key is required; byte sizes are whole bytes):

| Key | Built-in | Rule at save |
|---|---|---|
| `maintenance.window` | `02:00-05:00` (`development`: `00:00-00:00`) | `HH:MM-HH:MM` in UTC; start = end is the whole day |
| `maintenance.max_bytes_rewritten_per_run` | 50 GiB | at least twice `maintenance.target_file_bytes` |
| `maintenance.target_file_bytes` | 64 MiB | 16 MiB – 512 MiB |
| `maintenance.compact_min_small_files` | 5 | at least 2 |
| `maintenance.compact_small_file_ratio` | 0.75 | above 0, below 1 |
| `maintenance.rewrite_manifests_min_count` | 100 | at least 2 |
| `maintenance.cleanup_interval_hours` | 24 | at least 1 |
| `write.row_group_bytes` | 1 MiB | at least 64 KiB, at most the target file size |
| `retention.keep` | `[]` | a list of `{period, keep_for: {n, unit}}`, below |
| `retention.keep_time_zone` | `UTC` | an IANA zone the JVM resolves |
| `reporting.lateness_sample` | 20 | 2 – 200 |
| `reporting.lateness_overdue_factor` | 2 | above 1, at most 100 |
| `reporting.lateness_min_deliveries` | 5 | 2 – the sample |

The two fractional rules hold for the number as the profile keeps it (a double) as well as for the number written: a
value nearer to a bound than a double can tell apart from it (`0.99999999999999999` for the ratio, `1e-400`, or
`1.00000000000000001` for the factor) is the bound itself once kept, and is refused `out_of_range`.

`retention.keep`: `period` is one of `day`, `week`, `month`, `quarter`, `year`, at most one rule per period; `unit` is
`days`, `months` or `years`, with `n` a whole number in 1–146,100, 1–4,800 or 1–400. Two limits, decided the same on
any save date and in any zone: `keep_for` is at least one period long (in days at least the period's longest length,
1/7/31/92/366; in months at least 1, or 3 for a quarter, 12 for a year; in years at least 1), and a rule keeps at most
400 live keeps (`keep_for` in months ÷ the period's months, for a month, quarter or year rule counted in months or
years; otherwise the longest `keep_for` in days over a 400-year Gregorian cycle ÷ the period's shortest length,
1/7/28/90/365; both rounded up).

A refusal is `lake.profile.invalid` with `details.path` (the key) and `details.reason` (`unknown_key`, `required`,
`type`, `whole_number`, `out_of_range`, `grammar`, `time_zone`, `below_twice_target`, `above_target`, `above_sample`,
`duplicate_period`, …), before anything is written; a profile that does not exist is `lake.profile.not_found`.

**Not in a profile** (record call 35): `retention.protection_days` and `retention.orphan_grace_hours` are one value per
deployment (configuration) and are refused as unknown keys; the admission values, the codec (zstd), commit attempts
and timeouts stay where they are.

**Changes.** `LakeProfiles` creates, edits, moves the default mark and assigns a table; each change is one transaction
with its audit row (`lake.profile.created`, `lake.profile.updated`, `lake.profile.assigned`; [Enums
§15](enums.md#15-authauditevent--auth-audit-log-events)), takes effect at the next read and rewrites nothing (no Iceberg commit, no revision). A
change that changes nothing writes nothing. No route, tool or screen reaches it yet (P8 adds them with their roles).

**Pauses** stay on the table: `policy_json` holds only `pause.compaction_until` and `pause.cleanup_until` (record call
38), each an instant at most 7 days after the catalog's `now()`, or absent. `LakeTablePauses` sets or clears one key at
a time with a `lake.table.pause_changed` audit row; a set beyond 7 days is refused `lake.profile.invalid` with the key's
path, and a `purged` table takes no pause (clearing is always allowed).

**The append writer** reads the table's profile at the start of each append (`LakeProfiles.forTable`): it rolls a data
file at `maintenance.target_file_bytes` and sets `write.parquet.row-group-size-bytes` from `write.row_group_bytes`. An
edit or a reassignment applies at the next append, with no restart.

**The guard** (`LakeTuningSourceGuardTest`, record M8) is a source scan, not a data-flow analysis. It is red when code
under `append/` or `maintenance/` (a) holds a numeric literal other than 0 and 1 that its allowlist does not name,
(b) refers to a numeric constant declared anywhere else in the main sources, `profiles/` included, whatever the case
of its name — a `const val`, or a `val` set from a number, a `Duration` or a `DataSize`, at top level, in an `object`
or in a companion object — read as `Owner.name`, `Class.Companion.name`, a fully qualified `package.name`, through an
import, or bare in the declaring package (a constructor parameter, an instance property such as every `LakeProfile`
property, and a local value are not constants), (c) reads an
Iceberg table property or a `LakeProperties` group its allowlist does not name; when (d) anything in the lake, web or
app main sources but the seed, its wiring and the boot rule reads the two seed keys; and when (e) code under
`maintenance/` reads protection days or the orphan grace from anything but `LakeProperties`. Each allowlist entry is
one line with its reason. What it cannot see: a value computed from an admitted constant or from 0 and 1 (a `val`
whose initialiser does not start with a number, a `Duration` or a `DataSize`, a getter or a function returning a
number); a number reached by reflection, a resource or a string parsed at run time; a constant reached through an
import alias of its owner; a `LakeProperties` read through a receiver not named `properties` or `lakeProperties`. M8
claims exactly these five rules.

---

## 11. Maintenance runs

A maintenance run is the frame every maintenance operation executes in (#957; dp-lake record §11, §11a call 34). This
section describes the frame; the operations (cleanup, manifest rewrite, compaction, the purged table's deletion) and
the planner that enqueues runs are their own lanes, and until they land no part is registered and a run records
`nothing_to_do`.

**One task per table.** `lake-maintenance` is a keyed scheduler task whose key is the table id
([Scheduler: system jobs](scheduler.md#85-system-jobs)): enqueueing an instance that already exists changes nothing, so a
table has at most one task, cluster-wide. Its beans exist only when `datapipelines.lake.enabled=true`. The run
executes on the scheduler thread that took the task.

**The order, and what may be read when.** Rows of the metadata database may be read before admission; the table
itself (its Iceberg metadata, its files) only after.

1. The permission `lake.maintain` ([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)), held only by the system identity with
   no workspace (`SystemActorPrincipals.forInstance`); refused → `lake.maintenance.refused`, no row. Then the
   catalog's mode: not `normal` → no row.
2. A slot: one of `datapipelines.lake.admission.max-concurrent-maintenance`, tried without waiting; none free → the
   task comes again in 30 s, no row.
3. Each registered part declares, from database rows only, whether it expects to be due and the scratch it would need
   (zero for none). A part is handed the table's row, never an Iceberg handle.
4. Scratch for the sum of the declarations; a sum of zero reserves nothing. Refused → nothing is reserved and the run
   goes on without scratch.
5. **The run row** ([Metadata DB §4.55](metadata-db.md#455-lake_maintenance_runs)), one statement: it closes an
   earlier `running` row of the table whose deadline has passed as `abandoned` and inserts the new `running` row with
   `deadline_at = now() + admission.job-timeout` on the catalog's clock. An earlier `running` row not yet past its
   deadline → no row, the slot and scratch are released, the task comes again at that deadline.
6. Settlement: one page of the table's unsettled deliveries ([§8](#8-settlement-and-recovery)), from the first, with
   the sweep's page size and a budget capped at the run's deadline. No maintenance lease is held while it runs (the
   settler takes the table lease itself). A failure is logged (`lake.maintenance.settlement_deferred`) and recorded in
   the row's `settlement_json`; the parts run either way.
7. The parts, in the record's order (§54's outcomes, [Enums](enums.md#54-maintenanceoutcome--one-maintenance-parts-outcome)).
   Before each one the worker checks the run's deadline and the thread's interrupt flag, then asks the gate (it may
   hold a part `outside_window` or `paused_until`, with a reason; the others still run). A part not due now records
   `nothing_to_do`; a part that needs scratch the run does not hold records `capacity` and is not started.
8. The row is closed `finished` or `failed`, then the scratch and the slot are released, in a `finally`.

**The run's deadline is every lease's deadline.** Every commit goes through the run: it acquires the table lease with
the run's `deadline_at`, never a fresh job timeout, loads the table inside the lease, commits once, releases. The
catalog fence ([§7](#7-append-publication)) refuses a write whose catalog transaction starts after the deadline.
A write that already passed the fence may finish later, even after a successor marked the run `abandoned`; the
successor's lease acquire waits behind that transaction's lease-row lock. A deadline already passed →
`lake.maintenance.deadline` and the part ends `deadline`. A refused lease
(another operation holds the table) → the part ends `busy`. Either ends the RUN: no later part is started, each later
part the declarations expected to be due is recorded with the same outcome and `attempted: false`, the row closes
`finished`, and the table's next run comes from a later plan. A part that throws, an unknown commit outcome or an
interrupt ends the run `failed` with the error's code and the failure's class name, never a message or a stack.

**Nothing is concluded from an abandoned row.** Every write after admission is conditional on `state = 'running'`. A
worker whose row its successor marked `abandoned` keeps nothing: its close changes no row, it logs
`lake.maintenance.close_refused` and its audit event says `state: abandoned`, `outcome_confirmed: false`. A row left
`running` by a process that died is closed only by the next admission of its table.

**Threads.** With the lake and the scheduler enabled, `datapipelines.scheduler.threads` must be at least
`max-concurrent-maintenance` + 2 (one per admitted run, one for the planner, one always free for the dispatcher, the
reconciler and the notifier); the boot refuses otherwise, naming both keys
([Configuration §7](configuration.md#7-config-validation)). The default is 3.

**Records.** One audit event per admitted run when it ends, `lake.maintenance.run_finished`
([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)), actor the system user; log events
[Observability §3.4U](observability.md#34u-lake-maintenance-run-events-957).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-11-14 | v1.27 | 956 (#956, dp-lake P6) — renumbered at the spec merge after #957's v1.26 | New **§11 Profiles**: the profile table and its two built-ins, the start-up seed of `production` from the two `maintenance.*` keys, the closed key list with its rules (incl. `retention.keep`'s two limits and the three `reporting.*` keys), what is not in a profile, the service changes and their audit, the two pause keys left on the table, the append writer reading the profile at each append, and the tuning-source guard with its limits (rule (b) sees a numeric constant whatever the case of its name, and a fully qualified top-level read); a fractional value that rounds onto its bound is refused. §1 "Not in the body": the table's tuning is its profile; `policy_json` holds only the pause keys. |
| 2026-11-14 | v1.26 | 957 (#957) — renumbered at the spec merge after #958's v1.25 | New **§11 Maintenance runs** (§10 at the lane's base; #958's §10 landed first): one `lake-maintenance` task per table; the admission order (permission and mode, slot, declarations, scratch, one-statement run row, settlement, parts); the run's deadline on every lease; `busy` and `deadline` end the run; nothing concluded from an abandoned row; threads ≥ maintenance slots + 2. |
| 2026-11-14 | v1.25 | 958 (#958) | New §10 **Removal and cleanup**: the remover's two passes (orphans, purge), their fixed order, the reachable set, the candidates and hints, the `dp/` rule and its two marker cases, the clock-skew probe (`lake.maintenance.clock_skew`), the purge answer, what counts as deleted, and the result (inventory and visits). No production caller before 962; no configuration key or migration. |
| 2026-11-13 | v1.24 | 967 (#967) — renumbered at the spec sync after #935's v1.23 | §1 `partition` row and §7: the append's first scan refuses a value a numeric `truncate[N]` partition cannot hold (`lake.append.partition_value_out_of_range`, counts per partition field, never a value), after a required-null refusal and before any file is written; the definition stays valid. Temporary until apache/iceberg 17027 (decimal) and 13105 (int, long). |
| 2026-11-13 | v1.23 | 935 (#935) — renumbered at the spec sync after #939's v1.22 | §1: the body's fourth key, `sort_field_ids` (1–4 field ids, required before the first release, refused for `BINARY` and, for now, the approximate `DECIMAL`), fixed by the table's first release ever, also after its discard (`locked`); a legacy keyless table stays unsorted; the order (ascending, nulls first) is written to Iceberg at creation with one `write.metadata.metrics.column.<name>` = `truncate(16)` property per sort column. §2: a release is refused `invalid_body` when its stored draft breaks the sort-column rule. §4: S includes the sort order; changing the sort columns is breaking, a sort order changed outside DP is drift whose exit is to put it back. §7: files stay stamped unsorted on a sorted table (dp-lake record §11a call 36). |
| 2026-11-13 | v1.22 | 939 (#939) | §8: the protection stamp is `now()` plus `datapipelines.lake.retention.protection-days`, no longer `lake_control.protection_interval`, which the normal-mode sweep keeps equal to the setting (a write only when they differ, `lake.settle.protection_window_aligned`); a stamped deadline is never changed. The budget is checked before each of the protection step's two statements; the 16 s overrun and the 31 s wall clock are unchanged. No migration. |
| 2026-11-13 | v1.21 | 878 (#878) — renumbered at merge after #814's v1.20 | §3 and §7: every catalog connection carries pgjdbc's `socketTimeout` = `catalog.statement-timeout-seconds` + 5 s, read back before use; against a server that never answers a statement ends at that bound alone with `08006` (no cancel is sent: the catalog sets no `queryTimeout`), and opening ends with `08001` within B (`sslmode=disable`) or `min(sslResponseTimeout, B) + B` (default `prefer`). States what each apply and publication step leaves (pending, applying, FAILED, UNKNOWN), that nothing is replayed, and the URL settings refused when a session opens. |
| 2026-11-13 | v1.20 | 814 (#814) | §2: discard writes `lake.definition.discarded` and purge-draft `lake.definition.draft_purged` in their own transactions (both verbs now take the acting user and `via`); an audit failure rolls the step back, a refusal writes no row (dp-lake record §12, erratum 2026-11-12). |
| 2026-11-13 | v1.19 | 822 (#822) | §3: every failure of an apply step is returned as the attempt's outcome, never thrown after a release's landing: `pending` before the commit, unknown (left for a verify) from the commit on or when the row could not be stamped; a failed lease release or table session close keeps the outcome; a failed namespace session close ends the attempt `pending`. The state table's `pending` and unknown rows and the paragraph (now "An unclassified failure at any step") say so; the #822 sentence goes. |
| 2026-11-08 | v1.18 | 866 (#866) | §8: the abandoned metadata read's conditional stalled-store network-wait envelope is derived in `SettlementObjectReadEnd.stalledReadEndByMillis` from effective connect/socket values and Iceberg's configured retry policy: four request allowances plus all body and close reads, 420 400 ms with the shipped inputs. Pins the library assumptions and states residual phases without an end-to-end timer. Corrects the v1.15 row's one-timeout claim; the measured ~62 s remains an observation. |
| 2026-11-08 | v1.17 | 858 (#858) | §8: the settlement tick's statements carry a socket bound (5 s + 5 s) through the runtime's own template, so against a server that accepts and never answers a statement ends with `08006` at max(10, 5 + 10) = 15 s; the exception for this tick is gone, and the paragraph states the limit's two assumptions and that a transaction's later statements and commit read under the bound. The conditional 16 s overrun and 31 s tick are unchanged. |
| 2026-11-08 | v1.16 | 833 (#824) managed retire ceiling | §9 "The bound.": the staleness rule's ceiling term is the managed retire ceiling (`managed-retire-ceiling-seconds`, else `node-query-timeout-max-seconds` + 30); shipped 900 + 14 + 930 + 60 = 1904 ≤ 25 200 s. |
| 2026-11-08 | v1.15 | 851 (#851) | §8: an abandoned metadata read stops itself at its next chunk, or after the store's 10-second socket timeout; the read is capped at 64 MiB of JSON after decompression; the trickle is no longer unbounded. |
| 2026-11-07 | v1.14 | 837 (#837) | §8: the sentence on what a tick cannot promise states the connection checkout's bound (10 s) and that the other jobs on `dp-scheduled` end a statement against an unresponsive server at a socket bound while this tick's statements do not yet (#858). |
| 2026-11-07 | v1.13 | #845 managed reads egress proxy | §9 "The egress proxy": the managed reads leave through a proxy that cuts each object-store connection at `reads.egress-max-age-seconds`, a hygiene bound and not a safety premise; httpfs's retries make a trickling store's reader bound (`http_retries` + 1) × max-age + slack, derived in one place (`LakeHttpBounds.egressReaderEndByMillis`) and printed at boot; the boot refusal; what the proxy sees; a temporary accommodation until duckdb-httpfs#519. The key table gains both keys. |
| 2026-11-07 | v1.12 | #818 merge review | §9 describes the now-integrated P4 projection-repair invalidation and its independent freshness-tick fallback. |
| 2026-11-06 | v1.11 | 818e (#818) | §9: reader leases pin metadata revisions through physical close; five-term staleness rule, dedicated heartbeat, expiry/orphan consumer contract and stalled-metadata residual. |
| 2026-11-05 | v1.10 | 818d (#818) | §9 "The bound.": the reaper runs on its own `dp-reaper` thread and hands each close to a close thread, so the reaper delay is 7 s; the bound and boot rule gain the reader termination (92 s, derived from the managed lake's object-store request bounds). |
| 2026-11-05 | v1.9 | 818c (#818) | §9 "The bound.": the pool reaper retires an over-age generation nobody leases from, and the bound and boot rule gain 2 × the reaper delay (260 s, derived from the scheduled jobs). |
| 2026-11-04 | v1.8 | 818b (#818) | §9: a table's state, like its snapshot, is the one the reader's generation was built with. |
| 2026-11-04 | v1.7 | 818 (#818) | New **§9 Reads**: what a reader sees per table state, when new data becomes visible (the committing-server half waits for #799), the age bound and the boot rule that keeps it inside the snapshot protection window, and the operator keys. |
| 2026-11-06 | v1.6 | 799c (#799, #843) | The settlement tick is bounded as a whole (§8): the budget is checked inside verification and the semantics repair, the Iceberg metadata read leaves the tick's thread and opens no catalog session, one lease acquire is a five-second transaction, and the stated overrun is 16 seconds, derived term by term; a stuck read defers verification for every table until it ends. |
| 2026-11-06 | v1.5 | 799b (#799) | Settlement work is bounded per tick (§8): a page of 50 deliveries per table behind an in-memory row cursor, the ten-second budget checked before every step, delivery and statement group, and a stated overrun of at most 20 seconds. A request settles only the delivery it waits on. The applying verification and semantics repair keep a separate clause (#843); the 20 seconds assume the pool hands out connections and the server answers its cancels (#837). |
| 2026-11-05 | v1.4 | 799 (#799) | Deliveries, publication and settlement (§6–§8), including sender immutability, admission and physical resource ownership, the validation barrier, effective authority, immutable recipes, opt-in redaction default false, permanent commit evidence and bounded normal-mode recovery. |
| 2026-11-04 | v1.3 | 810 merge review | §3 distinguishes the polling horizon from the final row-lock wait, which can overrun either the lease wait or the caller's deadline by one lock timeout. |
| 2026-11-04 | v1.2 | 810 (#810, #811, #812) | Drift has an exit: a definition whose shape equals the live table applies as one commit that sets only `dp.definition.version` (§2 row, §3 row, §4 rule 2). An unclassified failure of the commit step leaves the row `applying` as `lake.definition.apply_unclassified`, and the release returns that outcome (§3 row and paragraph). A lease takeover waits at most the catalog lock timeout on a lease row another transaction holds, then counts as busy (§3 "One attempt" and "Crash between commit and stamp"). |
| 2026-11-04 | v1.1 | 798 merge review | Serialize lifecycle eligibility with apply, refuse discard during any apply, require canonical partition parameters and complete JSON input; record the field-ID correction limitation (#820). |
| 2026-11-03 | v1.0 | 798 (#798) | New document: the definition body and its closed type list, the lifecycle, the apply states, the supported-change policy and the names, as dp-lake P3 built them. Not packaged in the product jar (the lake surfaces, P8, decide its audience). |
