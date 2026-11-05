# The Managed Lake: Definitions, Deliveries and Publication

**Status:** v1.4 (#798, dp-lake P3; #810, #811, #812; #799, delivery and append)
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
(`lake_table_definition_versions`, one DRAFT per table). The body is a JSON object with three keys; any other key, at
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
  "identifier_field_ids": [1]
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
| `partition` | Optional, at most 8 objects `{field_id, transform}`. `transform` is `identity`, `year`, `month`, `day`, `hour`, `bucket[N]` or `truncate[N]` (N in 1–10,000, written without leading zeroes), and must be one Iceberg accepts for the column's type. The same field takes at most one time transform; no entry repeats |
| `identifier_field_ids` | Optional ids of required fields, each listed once; an approximate `DECIMAL` cannot be one |

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

**Not in the body:** the table's name and namespace (they are the table row's), sort order, Iceberg properties (DP
sets a closed list itself, `dp.definition.version` among them) and the retention and maintenance policy (the table
row's `policy_json`, filled from the lake's configured defaults when the table is created).

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

Release writes `lake.definition.released` in its transaction ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events));
the apply that follows writes its own event. A release's answer carries the apply's outcome; a refused apply never
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
| `applying` | the namespace or table lease is busy past the wait, or the catalog fenced or restored | `pending` |
| `applying` | the commit's outcome is unknown: the catalog connection failed while it was in flight, or the commit step failed with an error the catalog does not classify (`lake.definition.apply_unclassified`) | stays `applying`; a verify settles it |
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

**An unclassified failure of the commit.** The commit step can fail with an exception that is not one of the
catalog's refusals. That ends the attempt the same way: the row stays `applying` and the attempt's outcome is
`lake.definition.apply_unclassified`. A release that reaches it returns that outcome rather than throwing, because
its landing has already committed. A verify settles the row from the revision log. Some failures outside the commit
step still propagate after a release has landed
([#822](https://github.com/msabiransari/datapipelines/issues/822)).

**What `applied` writes,** in one transaction with the stamp: the column semantics projection
(`lake_column_semantics`, rebuilt from the definition: field id, name, DP and Iceberg type, required, doc, tags) and,
when the lake's datasource row exists, the `lake_tables` registry row (`origin = 'managed'`, namespace
`[lake, namespace]`, the current revision's metadata location and sequence, never moved backwards), and the
`lake.definition.applied` audit row. `failed` writes `lake.definition.apply_failed`.

---

## 4. The supported-change policy

An apply compares three shapes by field id: the Iceberg table's current one (S), the definition it was last applied
from (named by its `dp.definition.version` property) and the intent (the current definition).

1. S equals the intent and the property already names the version: nothing to do, `applied`.
2. S equals the intent but the property names another version, or none: one commit sets only the property (no
   schema, spec or data change), `applied`. A version that changes only tags takes this path too.
3. S differs from the definition it says it was applied from: someone changed the table outside DP.
   `failed`, `lake.definition.drift`, with `details.diff` listing the changes between that definition and S.
   The exit is rule 2: write the live shape as a new draft and release it. Build the draft on the version applied
   here; a rejected current release that holds other field ids is discarded first ([#820](https://github.com/msabiransari/datapipelines/issues/820)).
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

Files go under the table's `data/` prefix, using Zstandard Parquet, no declared sort order, and its applied
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

The recipe's replay flag reports whether input redaction already prevents exact replay. Recovery must also
verify that every recorded artifact hash remains available; a draft reference can change after execution.

Publication refreshes the table and checks permanent revision/attempt evidence before each bounded CAS retry.
One Iceberg transaction appends all files and sets `dp.delivery.key`, `dp.operation.id`, `dp.recipe` and
`dp.recipe.sha256`. A normal return stamps PUBLISHED. An in-memory CAS conflict may retry three times;
classified authority refusals fail, while an unconfirmed commit stays UNKNOWN. Classification uses exception
types and SQLSTATEs through the bounded cause chain, never localized messages. Periodic heartbeat preserves
only a live lease, capped by the admitted deadline; loss, deadline or cancellation stops further mutations.

## 8. Settlement and recovery

Permanent `lake_table_revisions` rows, joined with delivery attempts, are the commit evidence. Snapshot expiry
cannot erase it. `DeliverySettler.settle(tableId, deadline)` runs after publication and before retrying an
unresolved delivery. A live lease stays unresolved. For a dead PUBLISHING/UNKNOWN attempt, the settler first
takes over the table lease, waiting out the fence transaction, then reads the revision log in a **separate
statement**: evidence means PUBLISHED, absence means FAILED(publish). A lock timeout leaves it unresolved;
expiry alone never establishes failure. Conditional state and operation predicates protect newer attempts.

Normal execution cleanup marks unstarted RECEIVED attempts FAILED with listing, staging or validation phase.
After a crash, the sweep does so once the execution is terminal or gone. An unknown attempt is never treated
as safe to retry solely because its worker stopped. Recipes and data files remain for recovery/maintenance.

The `LakeSettlementScheduler` ticks every 60 seconds in normal mode, visiting a rotating page of up to 100
managed tables within a ten-second work budget. Each statement has a dedicated five-second timeout; a final
statement may overrun the work budget. It settles deliveries, verifies P3 `applying` rows, repairs semantics
under a table lease only when their applied definition differs, and records `lake_control.last_sweep_at`.
Restoring and replaying modes keep the sweep silent. Large catalogs may require several ticks for a full pass.

The sweep finalizes protection only for committed, visible supersessions, using the observation statement's
`now() + protection_interval`; NULL remains protected until observed. It never lowers an existing deadline.
Registry repair uses P3's monotone revision-sequence upsert. A changed row bumps `datasources.updated_at` in
the same transaction; local pool retirement and peer invalidation follow the commit. A no-op repair rebuilds
nothing. The authoritative catalog remains the source for managed reads; the registry is its display projection.

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-11-05 | v1.4 | 799 (#799) | Deliveries, publication and settlement (§6–§8), including sender immutability, admission and physical resource ownership, the validation barrier, effective authority, immutable recipes, opt-in redaction default false, permanent commit evidence and bounded normal-mode recovery. |
| 2026-11-04 | v1.3 | 810 merge review | §3 distinguishes the polling horizon from the final row-lock wait, which can overrun either the lease wait or the caller's deadline by one lock timeout. |
| 2026-11-04 | v1.2 | 810 (#810, #811, #812) | Drift has an exit: a definition whose shape equals the live table applies as one commit that sets only `dp.definition.version` (§2 row, §3 row, §4 rule 2). An unclassified failure of the commit step leaves the row `applying` as `lake.definition.apply_unclassified`, and the release returns that outcome (§3 row and paragraph). A lease takeover waits at most the catalog lock timeout on a lease row another transaction holds, then counts as busy (§3 "One attempt" and "Crash between commit and stamp"). |
| 2026-11-04 | v1.1 | 798 merge review | Serialize lifecycle eligibility with apply, refuse discard during any apply, require canonical partition parameters and complete JSON input; record the field-ID correction limitation (#820). |
| 2026-11-03 | v1.0 | 798 (#798) | New document: the definition body and its closed type list, the lifecycle, the apply states, the supported-change policy and the names, as dp-lake P3 built them. Not packaged in the product jar (the lake surfaces, P8, decide its audience). |
