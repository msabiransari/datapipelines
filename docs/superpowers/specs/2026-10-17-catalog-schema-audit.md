# Catalog schema audit and DP Lake foundation

**Date:** 2026-10-17 UTC.
**Status:** source audit and schema recommendation, for review before implementation.
**Tracking:** [Catalog foundation #572](https://github.com/msabiransari/datapipelines/issues/572).
**Direction:** fix the shared catalog foundation first, including the semantic layer, then
implement writable DP Lake using the [Execution approach](2026-09-22-ducklake-managed-lake-draft.md#13-execution-approach).
**Baseline:** local main `d22ddc8155be007077e37e170797a34fdf7c1a22`; the execution-section
addition was present as an uncommitted design change. No database migration was applied.

DP currently has a useful lake registration service, live database introspection, and learned
semantic facts. It does not yet have a shared, durable catalog of namespaces, datasets and
fields to which semantics and operational history can bind. **Build that identity and
relationship model before adding lake writes.** Preserve the working read connector,
datasource permissions and semantic history while migrating their references.

The catalog should cover existing databases and external files as well as DP-managed Iceberg
tables. DP owns discovery, descriptions, governance, meaning and operation records. The source
database or Iceberg catalog remains authoritative for physical schemas and committed data.
Persisted observations must carry provenance and freshness rather than becoming another
editable source of physical truth.

## Audit scope and limits

The migration inventory contains **48 SQL files, numbered V1 through V49 with no V35 file,
and 37 CREATE TABLE statements**. This is a source inventory, not a count of tables on a live
deployment. All migration filenames and CREATE/ALTER targets were inventoried before tracing
the catalog-related stores and their consumers.

The audit followed datasource registration/grants; lake registration, bootstrap, pooling,
introspection and statistics; REST/MCP/UI consumers; learned-fact recording, drift and
visibility; template citations; and the existing execution, check and scheduler ledgers.
Relevant implementation and test sources were inspected. Tests, runtime failure experiments
and live-database inspection were not run. Findings about race/failure behavior below are
source-derived risks with proposed reproduction tests, not observed incidents.

Reproducible inventory entry points:

```sh
git rev-parse HEAD
rg --files modules/app/src/main/resources/db/migration -g '*.sql'
rg -n '^CREATE TABLE|^ALTER TABLE' modules/app/src/main/resources/db/migration
rg --files modules tests | rg '(Lake|lake|semantics|Semantics|LearnedFact|SchemaFingerprint|TemplateImplements|Datasource|Schedule|JobExecutor)'
rg -n 'learned_facts|template_implements|lake_tables|datasource_workspaces' modules --glob '*.sql' --glob '*.kt'
```

Absence findings are bounded to the migration inventory and the traced production stores and
interfaces. A property name, historical draft or test fixture is not evidence that an
external catalog, ingestion or maintenance implementation exists.

## What exists and should be retained

| Foundation | Verified current behavior | Evidence |
|---|---|---|
| Datasource identity and credentials | Globally unique, immutable `datasources.name`; environment-specific connection configuration and encrypted credentials. Encryption/decryption uses the datasource name. | [Datasource schema][datasource-ddl]; [registry][registry] |
| Ownership and visibility | `owner_workspace_id` distinguishes workspace-owned from instance datasources; `datasource_workspaces` records explicit grants. Null owner does not mean public access. | [V23 grants][grants] |
| Lake registry | `lake_tables.id` is a UUID; rows contain datasource name, namespace segments, table name, format, location, one optional partition column, registering actor/time and last view error. | [V15][lake-ddl]; [V22][lake-errors]; [LakeTable][lake-model] |
| Lake read runtime | Registered Parquet/Iceberg locations become DuckDB views. Pool generation ownership, per-table error isolation and invalidation already exist. | [view generation][lake-views]; [registry service][lake-service] |
| Database discovery | Namespace-aware live schemas, tables, columns and catalog statistics through shared introspection. The source engine owns this metadata. | [introspection][introspection]; [payloads][schema-payloads] |
| Learned meaning | Twelve fact kinds, UUIDs, datasource/workspace scope, evidence SQL and summary, provenance, trust, supersession and retirement. | [V25][facts-ddl]; [fact model][fact-model] |
| Semantic discovery | Facts enrich datasource/table/column discovery. Provenance and implementing-template links respect visibility. | [enricher][enricher]; [semantic service][semantic-service] |
| Reusable semantic implementations | `template_implements` links a specific template version to fact UUIDs and supports reverse lookup. | [V36][implements-ddl]; [citation repository][implements] |
| Execution and scheduling | Execution records, parent/child execution lineage, release-check outcomes, generic scheduler executor and durable schedule runs exist. | [V3][execution-lineage]; [V28][checks]; [V38][schedules]; [executor port][job-executor] |

These are substantial foundations. Do not replace them with a second scheduler, a separate
permissions system, unversioned free-text descriptions, or a new semantic SQL compiler.

## Missing pieces against the new direction

**Foundation** means design and deliver with the catalog repair. **Before writes** means
design its connection to catalog identity now and deliver it before the first managed write.
**Later** means retain the extension point without making it a prerequisite.

| Gap | Current limitation and user impact | Recommended change | Stage |
|---|---|---|---|
| Catalog provider identity | `catalog.kind`/`catalog.ref` properties are accepted, but the traced LAKE runtime reads registered file locations; they do not establish a REST/Glue catalog integration. | Separate a metadata-provider connection from a query datasource; model provider configuration, credentials, bindings and capability state explicitly. | Foundation; provider execution before writes |
| Namespace lifecycle | Namespace arrays live on table rows; schema listings and UI folders derive from those rows. Empty namespaces, namespace descriptions and lifecycle do not exist. | First-class namespace IDs and canonical segment paths, descriptions, ownership/default policies and lifecycle. | Foundation |
| Dataset identity across surfaces | The registry UUID is omitted from the lake wire/runtime shapes. Mutation and lookup use datasource/namespace/name. | Stable asset IDs throughout catalog API, MCP, semantic bindings and future dependency records; names remain addresses. | Foundation |
| Refresh without identity loss | Iceberg reads an explicit metadata file. The MCP description instructs unregister/re-register after a commit; manifest import skips existing triples. | Refresh/update with identity preserved, expected revision, authoritative external identity and observed metadata revision. | Foundation |
| Field identity and schema evolution | Columns are live/cache projections with no durable field identity; native nested structure and source IDs are not a catalog contract. | Stable field IDs plus immutable observed schema revisions; preserve source type details and provider field IDs. | Foundation |
| Rich physical layout | One `partition_column` cannot describe multi-field transforms, evolved partition specs or sort orders. Iceberg stats return `stats_source: none`. | Provider-owned layout and snapshot-aware statistics exposed as dated observations, with supported/unknown states. | Foundation schema; populated by provider integration |
| Management authority | A registration says where data is read, not who may create, rewrite, drop or purge it. | Explicit external versus DP-managed mode, storage binding and lifecycle; distinguish unregister, drop and purge. | Foundation |
| Coherent metadata refresh | Local eviction/pub-sub exist; reconnect reconciliation compares datasource timestamps, not lake-table revisions. | Durable catalog revision included in pool/cache reconciliation, with complete/incomplete refresh scope recorded. | Foundation |
| Workspace-wide catalog discovery | Discovery is per datasource. No durable cross-source asset inventory/search model exists in these stores. | Shared catalog read model, paginated/searchable under existing grants, enriched with semantic meaning. | Foundation |
| Structural semantic subjects | Fact refs are name-based JSON under one datasource. V26 permits empty refs to express cross-source workspace rules in prose. | Normalized fact-to-asset/field bindings and genuinely workspace-owned rules with optional multiple subjects. | Foundation |
| Structured business meaning | Most meaning is fact text plus a closed kind. Measure formulas, grain keys, join cardinality, dimensions and aggregation behavior are not typed definitions. | Versioned typed semantic payloads attached to existing fact revisions and bound to stable subjects. | Foundation, bounded initial payload kinds |
| Semantic approval and evidence | Verification columns exist but no verification writer was found. Evidence is a bounded probe/summary, without source snapshot or repeatable evaluation identity. | Explicit review decisions and append-only evidence records; keep observed data separate from approved business choices. | Foundation |
| Frozen execution meaning | Template citations may change after release and are outside `body_hash`; they cannot explain exactly what a past run relied on. | Separate immutable run/release semantic bindings from mutable authoring citations. | Contract now; before writes |
| Asset and field lineage | V3 records execution nesting, not datasets read/written or column derivation. | Typed dependency edges and execution input/output observations tied to asset/data revisions. | Identity contract now; before writes |
| Reliable ingestion | Registry manifest import registers locations; it does not parse/copy files, track source versions or publish managed tables. | Versioned ingestion contract, durable batch/attempt/source identity, commit reconciliation, checkpoints and replay. | Before writes |
| Data quality and completeness | Release-check runs exist; they are not ingestion publication gates or a durable quality history for a table snapshot. | Versioned quality rules and evaluation records, rejected-data references, coverage and publication disposition. | Before writes |
| Data freshness | View errors and last job success do not represent source coverage, watermarks or data publication. | Distinct source/data/observation timestamps, completeness evidence and freshness objectives. | Contract now; before writes |
| Maintenance and recovery | Scheduler can dispatch jobs but no lake policy/run/restore model exists in the catalog stores. | Versioned policy, operation records, stable targets, resource budgets, conflict/recovery state and coherent restore references. | Before writes |
| UI and MCP operation parity | Shared registration service exists; namespaces, refresh, managed creation, import preview, repair and maintenance are new actions. | One application service contract with durable operation IDs and role-aware UI/MCP projections. | Foundation operations first; extend with writes |
| Performance optimization | Query/pool infrastructure exists, but no catalog record ties a workload objective to layout recommendations and measured outcomes. | Workload observations, freshness/latency/concurrency objectives and explicit optimization proposals. | Later, after measurement |
| Portable catalog and semantics | Datasource names support environment mapping; local asset/fact IDs and semantic transfer need a defined import/rebinding contract. | Separate logical references from environment-local bindings; preserve original evidence and mark re-verification needs. | Foundation design; transfer with its feature |

The durable catalog must not claim complete discovery from capped introspection results.
Track refresh scope, pagination, authority, observed time and completion explicitly; an
unreachable or omitted object is not proven dropped. Preserve existing guards that avoid
drift demotion from a truncated listing.

## Recommended schema boundaries

### Authority and identity

Use three related layers:

1. **Catalog identity and governance:** what assets exist, stable IDs, names, ownership,
   lifecycle and how an authorized datasource reaches them.
2. **Observed physical metadata:** what the upstream system reported at a particular version
   and time, including schema, layout, statistics and availability.
3. **Meaning and operations:** learned facts, typed semantic definitions, evidence, lineage,
   ingestion/quality/maintenance contracts and their results.

The distinction refines the existing semantic rule “never store what introspection says.”
Semantic facts must still not duplicate physical types or partitions as editable claims.
Historical/catalog projections may cache them only as explicitly versioned, source-owned
observations. This requires an explicit amendment to D-S2 when the schema spec is ratified.

For Iceberg, retain the authoritative table UUID, schema ID and field IDs. Iceberg identifies
fields using IDs that are not reused within a table. Use that evidence to distinguish an
identity-preserving rename from a drop followed by an unrelated create. For JDBC/file sources
without equivalent identity, keep conservative unresolved/review behavior; never infer a
rename from equal names or compatible types.
[Iceberg schemas](https://iceberg.apache.org/docs/latest/schemas/),
[Iceberg specification](https://iceberg.apache.org/spec/).

Do not mirror manifests or implement a second commit pointer in the DP metadata DB. A stored
snapshot/metadata location is an observation or an operation receipt. The Iceberg catalog
remains the commit authority. Its REST API defines catalog operations; a particular provider's
capabilities still require validation. [REST catalog specification](https://iceberg.apache.org/rest-catalog-spec/).

### Foundation entities

The names below are proposed logical entities, not executable DDL or reserved migration
numbers. Freeze their invariants before choosing the final physical table split.

| Entity | Principal fields and relationships |
|---|---|
| `catalog_connections` | UUID, provider kind, logical name, owner workspace or instance ownership, typed non-secret configuration, credential/storage references, supported capability record, lifecycle, revision, creator/update provenance. A legacy registry is a provider mode. |
| `datasource_catalog_bindings` | Datasource-name FK, catalog ID, native catalog selection/mount configuration and authorization scope. Query connection and metadata-provider identity are distinct. Start with one binding per legacy datasource; support deliberate shared bindings without auto-merging similar endpoints. |
| `catalog_namespaces` | UUID, catalog ID, canonical segment array, display/description metadata, lifecycle and revision. Empty namespaces are representable. Root/flat namespaces follow provider capabilities; do not impose `main` or flatten nested names universally. |
| `catalog_assets` | UUID, namespace ID, current native name, asset kind (table/view/external dataset), format, management mode, lifecycle, description/steward metadata and revision. Preserve legacy lake UUIDs where their one-to-one meaning is retained. |
| `catalog_asset_bindings` | Asset ID, provider-native identity, native qualified identifier, upstream table UUID where available, location role (table root versus explicit metadata file versus file set), storage reference and binding revision. History retains previous bindings. |
| `catalog_fields` | UUID, asset ID, native field ID where available, parent field identity for nested structures, active/retired state and identity provenance. Field identity survives supported renames; dropped identities are never recycled. |
| `catalog_schema_observations` and field memberships | Asset ID, upstream schema/version marker, observation time/source, fingerprint and immutable schema representation. Membership binds fields to their names, paths, order, native/canonical types, nullability and precision for that observation. |
| `catalog_refresh_runs` and revisions | Catalog/binding ID, requested scope, started/completed times, outcome, pagination/completeness, source revision and bounded diagnostic. Successful metadata mutations advance a durable revision used by peers and caches. |
| Semantic subject and payload records | Bind existing fact UUIDs to catalog assets/fields and typed meaning as described below. |
| Semantic evidence and review records | Append-only evidence/decisions with actor, subject revision, result/coverage and provenance. Existing verification fields may remain current-state projections. |

Description, steward, tags and classification need a defined home on these subjects; avoid
duplicating them across three catalog layers. Workspace-specific annotations must remain
separate from shared source facts. Preserve legacy datasource grants during migration. For
managed catalog objects, introduce workspace ownership and explicit read sharing as defined
below, with actions governed by the existing permission matrix. A binding to a shared catalog
connection must not grant access to every asset reachable through its credentials.

The current self-service boundary is stricter than the ordinary table-management role row:
all LAKE datasource registration/repointing requires super admin because the engine runs
in-process with external access; changing an instance datasource's lake registrations also
requires super admin. See [datasource workspace rules][workspace-rules] and [lake wiring][lake-wiring].
Recommend operator-provisioned catalog/storage connections followed by separately authorized
workspace namespace/table operations. Preserve the existing gate until that constrained
operation boundary is implemented; do not achieve self-service by weakening datasource access.

Catalog authentication and storage authentication may differ. Model their references
separately and reuse the encryption/key-rotation discipline. Do not place tokens in generic
JSON properties or invent a foreign key to a credential table that does not currently exist.
Selecting a credential-profile store versus dedicated encrypted fields is part of the schema
specification. Provider capability declarations must be checked against the adapter and its
version; a stored boolean cannot grant a capability or permission.

Storage bindings need a logical contract of their own: provider/bucket or root, permitted
managed prefix, credential reference, workspace authority, overlap policy and lifecycle.
Before writes, validate that managed destinations stay within authorized roots and that
cleanup cannot traverse another table's or an external registration's storage. Record the
resolved upstream location; do not derive it from the display name. Decide whether these
bindings are reusable storage profiles or owned catalog configuration in the schema spec.

### Constraints that make these entities useful

- Unique active namespace path within a catalog, and unique active asset name within a
  namespace, using provider-aware identifier case rules. Do not lowercase all upstream names.
- Typed namespace segments with one canonical representation; SQL qualification and URL
  encoding must preserve segment boundaries. Current registry depth 1–9 versus runtime depth
  at most two must be reconciled through adapter capabilities, not silent flattening.
- Foreign keys and composite consistency checks that keep a field, its schema observation,
  asset, namespace and catalog in the same identity chain. Parent-field links must not cross
  assets or form cycles.
- Unique native field IDs within their upstream table incarnation. An Iceberg table UUID
  helps detect identity but does not justify deduplicating across independent catalogs or
  restored environments without an explicit binding decision.
- Optimistic revisions on mutable catalog configuration and aliases. Immutable observations,
  fact content and operation receipts retain history rather than being rewritten.
- Tombstone/detach before purge, with dependency checks and explicit scope. Recreating a
  table under an old name must not inherit the old table's semantic approval or data history.
- Indexes for catalog/path lookup, reverse asset/field-to-fact lookup, dependency traversal
  and recent operations. Avoid redundant indexes already covered by a primary/unique key.
- Retention rules that distinguish disposable cache projections from historical evidence.
  A disconnected datasource must not erase an explanation attached to a completed run.

Do not change `datasources.name` to a UUID primary key as part of this work. It is an immutable
external contract, an environment mapping key and the credential encryption context. New
catalog IDs can coexist with it; a separate datasource re-key would create unrelated risk.

## Environment isolation and workspace ownership

**Recorded direction:** use a separate logical catalog for each environment, shared by that
environment's workspaces. Keep workspaces as the strict ownership boundary, and permit
cross-workspace consumption through explicit read grants. This is the target schema and
behavior; the current implementation does not yet provide these catalog operations.

### Environment boundaries

| Boundary | Responsibility | Initial contract |
|---|---|---|
| Environment | Isolate development, staging and production | Independent catalog state, credentials and storage destinations. |
| Workspace | Own and manage objects | Each managed namespace has one owning workspace; its tables initially inherit that ownership. |
| Sharing grant | Permit consumption by another workspace | Explicit read access without transfer of ownership or mutation rights. |

Start with one default managed DP Lake catalog per environment, while allowing additional
catalog connections. A logical catalog does not require a dedicated server: a small
organization may use one catalog service hosting isolated environments if the provider
enforces their separation. Separate infrastructure provides stronger failure isolation.
Production requires separate credentials and storage roots at minimum; an environment name
or namespace prefix under broadly shared credentials is insufficient. Catalog environment
bindings must follow deployment configuration, not inferred meaning from names or posture.

Promote versioned pipeline and semantic definitions using logical references that resolve to
destination-local assets. Keep production credentials, sharing grants, physical table
identities and approval state independently controlled. Promotion must not silently copy data
or make development a writer to production. Any cross-environment read connection is explicit,
separately authorized and read-only by default.

### Ownership and cross-workspace reads

Ownership belongs to the workspace, not the individual creator. A member still needs the
appropriate role for each action; workspace ownership does not make every member a writer.
Keep ownership transfers explicit and audited. Platform administration remains a separate,
audited authority, never an implicit privilege of another team's workspace admin.

For example, Sales owns `sales.orders`, Finance owns `finance.exchange_rates`, and both
share those tables with Analytics. An execution in Analytics may join them. If materialization
is permitted, it may write the result into an Analytics-owned namespace. It may not change
either source's data, schema, permissions, retention or maintenance policy.

| Operation | Appropriately authorized role in owning workspace | Receiving workspace with read grant |
|---|---|---|
| Discover and query the shared object | Allowed | Allowed, subject to the caller's query permissions |
| Join with other accessible objects | Allowed | Allowed within the execution's granted scope |
| Modify data or schema | Allowed | Refused |
| Drop the object or change retention | Allowed | Refused |
| Manage maintenance policy | Allowed | Refused |
| Share onward | Requires explicit sharing permission | Refused |

Keep one active workspace per execution. Membership in several workspaces must not union
their privileges: every source must be owned by or explicitly shared with the execution's
workspace. Apply the same rule to workspace-pinned MCP keys and scheduled execution identities.
Cross-workspace writes are outside the first version.

Start with read grants at table or namespace scope. A namespace grant must state whether it
includes future tables. Separate permission to create a grant from permission to read data;
the exact sharing role rows remain part of the schema/API specification. Existing datasource
grants need an explicit compatibility mapping rather than silently expanding or narrowing
access during migration.

### Catalog experience and enforcement

Provide one Catalog experience with three views:

- **Owned by this workspace:** the default view of its managed objects.
- **Shared with this workspace:** accessible references showing their owner and read-only status.
- **All accessible:** the combined inventory for search, query composition and AI discovery.

Unshared objects remain hidden. Sharing references the same asset identity; it does not clone
the table or change its owner. UI and MCP expose the same authorized inventory and operations.
Search, semantic descriptions, lineage, previews and errors must obey that visibility too.

Enforce grants below the UI in query execution, scheduled jobs, maintenance, catalog calls
and object-storage access. Broad storage credentials or unrestricted engine access must not
bypass the catalog boundary. Pools, cached metadata and results must not leak data across
authorization contexts. Define revocation behavior for new operations, running work and
cached results; validate the chosen credential and engine isolation with the provider proof.

Databricks provides a precedent for workspace-catalog bindings that restrict access and can
make a catalog read-only in a receiving workspace. Its workspace represents a processing
environment, so DP retains its own team-ownership semantics rather than adopting that hierarchy.
[Databricks workspace-catalog bindings](https://docs.databricks.com/aws/en/data-governance/unity-catalog/access-control/workspace-catalog-binding).

### Semantic ownership and derived data

The owning workspace can publish semantic definitions with a shared dataset. A receiving
workspace can define its own metric or interpretation referencing that dataset without
overwriting the owner's definition. Preserve attribution, revisions and the distinction
between source meaning and workspace-specific business choices. Sharing a semantic definition
does not grant access to its underlying data or inaccessible evidence.

Specify whether a read grant permits materialization into another workspace and which
retention or onward-sharing restrictions apply to the derivative. Record that dependency in
lineage. Revoking source access can stop future reads; it cannot retroactively erase copies
or results already delivered. Do not promise that revocation deletes derived data.

### Schema and acceptance implications

Make environment-local catalog bindings, owning-workspace relationships, recipient read
grants, storage ownership and grant/transfer audit history explicit. Keep these relationships
separate from connection credentials and user roles. This bounded sharing model is part of
the catalog foundation; arbitrary row/column policy administration remains separate scope.

Acceptance must prove an allowed cross-workspace join alongside refused source mutation,
refused access to unshared objects, no privilege union for multi-workspace members, equivalent
UI/MCP/job enforcement, no broad-credential bypass, and the specified revocation behavior.
Environment-isolation checks must prove that development identities cannot mutate production.

## Semantic layer changes

### Preserve learned facts and make their subjects structural

Keep existing fact UUIDs, immutable text, scope, evidence, supersession and retirement. Keep
the existing template citations pointing to those UUIDs. Add a normalized subject relation,
for example `semantic_subjects(fact_id, asset_id, field_id?, schema_observation_id?, role,
resolution_state, original_ref)`. Resolution provenance records whether a binding came from
an authoritative source ID, a verified legacy name match or a human decision.

An unresolved legacy reference retains its original spelling and reason; it is not attached
to the first matching table. Field and schema references must belong to the bound asset.
Cross-source relationships need multiple subjects with roles such as left/right or
input/output, rather than one datasource name and the other sources hidden in prose.

Make workspace business definitions discoverable at workspace scope even when they have no
dataset subjects. Relax the mandatory datasource anchor for that case through a deliberate
API/schema migration. Shared datasource facts retain their source access rules. For a
workspace rule with several subjects, require access to its referenced resources before
exposing or executing its detailed bindings; define a redacted response if the product needs
to acknowledge a definition without revealing its inaccessible subjects.

The current kind/scope matrix makes `join` a DATASOURCE fact. Cross-source relationships need
an explicit extension to that matrix or a workspace-definition payload representing the
relationship. Do not bypass the closed-kind constraint or silently widen the visibility of a
shared fact. The original distinction between source meaning and workspace business choices
remains valid.

### Typed meaning without a competing semantic engine

Add a versioned structured payload keyed to a fact UUID, retaining explanatory prose. The
fact UUID is already an immutable content revision; do not manufacture a second mutable
version of the same assertion. A named semantic concept can later group revisions if users
need a stable business name, with an explicitly approved selection rather than a silent
latest-wins rule.

Payload format version is distinct from semantic revision. Adding or correcting typed meaning
creates an immutable successor fact/payload revision; it must not overwrite the meaning under
a UUID/hash already cited by a release or run. The first structured interpretation of a legacy
prose fact is also a new reviewed assertion, linked to its predecessor.

Define schemas for the initial supported payload kinds:

| Meaning | Required structure beyond prose |
|---|---|
| Entity and grain | What one row represents, identity/key field bindings and scope. |
| Unit and currency | Unit, scale, currency and conversion assumptions, tied to fields. |
| Time | Timezone, business calendar/day boundary and event-time field. |
| Relationship | Endpoint field bindings, key mapping, cardinality and applicability. |
| Measure | Inputs, aggregation/additivity, exclusions, time dimension and reusable implementation reference; preserve numerator/denominator for ratios. |
| Dimension and enum | Field bindings, code-to-meaning mapping and applicable versions. |
| Business rule | Typed parameters and applicability, supported expression/component reference and declared outcome. |

The schema needs a payload type and format version with validation. Unsupported definitions
remain prose facts until reviewed; do not pretend to extract an executable contract losslessly
from historical text. Existing source types, nullability and partition details remain physical
observations, not additional semantic fact kinds.

This follows the [recorded owner direction][semantic-research]: agents learn structured
meaning, metadata discovery is the primary surface, and reusable versioned components supply
execution. Reuse templates and pipelines. A new measure compiler, vector store and automatic
semantic harvesting are not prerequisites for the catalog repair.

### Evidence and approval are different records

Separate three questions:

1. What meaning was asserted, and which fact revision states it?
2. What data or execution supports it, with which coverage and limitations?
3. Who approved using that meaning for this workspace and purpose?

Evidence should identify its fact/rule revision, asset and schema/data revision, source
version or time window, execution/probe identity, full-versus-sampled coverage, result status,
observation time, result digest/summary and protected evidence location. A digest proves
integrity only when the retained evidence and inputs can be retrieved. Historical facts
without those values must say unknown; no fabricated snapshot or verification backfill.

Review decisions record actor, time, action/reason, fact revision and evidence considered.
The existing #158 request assigns human verification to workspace admins; preserve that floor
in the proposed verify action. Define permissions for new reject/review actions explicitly,
and preserve the current retirement rules. Retain decision history. A successful
probe can establish an observed property of data; it cannot approve a business definition.
Conflicting definitions coexist and require an explicit decision. Retain the existing rule
that definition/exclusion/preference facts start asserted even when accompanied by SQL.

[#158](https://github.com/msabiransari/datapipelines/issues/158) already tracks the missing
human verification/UI lifecycle. Its title is broader than current code: MCP retirement
exists in `SemanticsService.retire`; a verification writer and UI mutation flow are the gaps.
Reuse that issue rather than recreating it.

### Pin meaning for execution without changing citation semantics

`template_implements` is a mutable authoring citation, explicitly outside the template body
hash and editable after release. Preserve that contract. A separate immutable release binding
freezes chosen fact revisions, structured payload hashes, component versions and the validation
contract. Each execution then records the input asset/schema/data revisions it actually used
against that contract; future source snapshots cannot be known at release time. A later
citation edit must not alter the explanation of a completed ingestion run.

[#181](https://github.com/msabiransari/datapipelines/issues/181) records an older owner direction
that learned semantics serve authoring and need a separate development-to-development transfer
spec. The new runtime validation/lineage direction needs an explicit reconciliation with that
boundary. Recommendation: keep learning and mutable approval workflows on authoring-enabled
deployments, and deliver the necessary immutable executable contract and evidence identifiers
to runtime deployments. Do not silently require the entire mutable learned store in production.
Authoring capability and deployment posture are distinct; see [environments](../../environments.md).

When source data becomes a DP Lake table, do not copy every fact unchanged. A cents-to-dollars
conversion changes the output unit; a line-item aggregation changes grain; a timezone conversion
changes time interpretation. Record the output's appropriate meaning and its derivation, with
the transformation version and supporting evidence.

## Operational schema to design now and deliver with lake writes

These records depend on catalog identity, but their complete storage model should follow the
write/recovery contract. Creating empty speculative tables now would not establish safety.

| Record family | Contract to settle before first managed writes |
|---|---|
| Ingestion definitions and revisions | Source bindings, destination asset, schema mapping, append/replace/merge mode, identity keys, schema policy, validation and released component versions. |
| Source items and logical batches | Stable file/object version or cursor range, readiness/completeness evidence, definition revision, deduplication scope, replay identity and source-retention policy. |
| Operations and attempts | Durable operation ID, actor/workspace, stable target, idempotency key, expected external version, attempt state, scheduler/execution link, cancellation and resource class. |
| Commit receipts and checkpoints | Intended effect, confirmed upstream commit/snapshot, uncertain/reconciling outcome and destination-confirmed progress. A local receipt alone cannot make a remote commit atomic. |
| Quality rules and evaluations | Immutable rule definition, scope, severity versus disposition, evaluated population, sample/full coverage, pass/fail/error/not-evaluated and publication boundary. |
| Rejection and replay references | Protected payload location, source record/file locator, reason, applied rule version, retention and repair/replay outcome. |
| Dependency and lineage records | Distinguish describes, implements, reads, writes and derives-from; bind to artifact version and asset/field identity. Execution observations add actual input/output data versions and capture completeness. |
| Freshness and completeness observations | Source watermark/coverage, arrival time, committed publication time, expected/missing inputs and observation timestamp, independently of job outcome. |
| Maintenance policies and runs | Policy revision, eligible target, compaction/expiration/cleanup kind, retained history, locks/conflicts, budgets, before/after versions/statistics and outcome. |
| Recovery manifests | Coordinated catalog/object/progress/configuration recovery point, verification evidence and dependency retention. Snapshot retention alone is not a complete backup. |

Use existing execution and scheduler IDs rather than another job universe. Their presence does
not establish batch idempotency: test a worker that committed data and died before writing its
DP receipt. Also test simultaneous scheduled and manual operations on the same table; the
scheduler's per-schedule overlap rule does not serialize all table operations.

Retention must distinguish historical explanation from guaranteed replay/queryability.
An execution receipt naming a snapshot does not preserve its files. Define protected recovery
windows and any explicit snapshot retention for replay or reproducible reads, then make
expiration/cleanup respect those protections. If the promise is explanation only, report when
the underlying data is no longer retained rather than offering a broken replay action.

For lineage, start with declared and observed dataset dependencies plus provenance/coverage.
Do not promise complete field lineage for arbitrary SQL, JSONata and JavaScript without a
supported extraction or explicit declaration contract. Similarly, live federation across
independent sources has no automatic common snapshot; record per-source observations and the
consistency actually provided.

## Source risks to reproduce during catalog repair

| Risk | Source evidence and failure mechanism | Required check |
|---|---|---|
| Namespace collision in semantic enrichment | `LearnedFactsEnricher.forTables` builds a set/map by bare table name; `againstTables` checks bare names. `FactRef.matches` accepts a last-segment match or null wildcard. Same-named tables can receive ambiguous facts or conceal removal in another namespace. | Two namespaces with the same table name and different facts; remove one and verify only its facts become stale, including full namespace and null legacy refs. |
| Concurrent trust demotions can reverse severity | `markTrust` updates any non-retired row to the supplied state; the earlier in-memory verdict cannot protect against a later concurrent stale read. | Interleave a needs-review verdict with a persisted stale verdict; stale must not become needs-review. Use a legal transition/CAS predicate. |
| Fact duplication/supersession is not transactionally established | Duplicate check precedes insert without a matching DB uniqueness guard; supersession inserts the successor then separately retires the predecessor. No surrounding transaction was found in the traced semantic path. | Concurrent identical records; failure between insert and retirement; concurrent successors. Enforce the chosen identity/idempotency and transaction boundary without forbidding genuine conflicting meanings. |
| Missed lake invalidation can survive reconciliation | Table mutations leave the datasource row unchanged; `reconcilePools` compares only datasource `updated_at`. Pub/sub recovery can therefore lack a durable signal that the registry changed. | Drop the invalidation, reconnect the subscriber, then verify rebuilt views using a catalog revision. A successful pub/sub test does not cover this. |
| Manifest prevalidation is not durable batch atomicity | Import validates/preflights all new rows, then loops individual inserts and invalidates after the loop. A mid-loop DB failure can leave earlier inserts committed. | Inject failure on a later insert; prove rollback or explicitly recorded partial outcome and correct invalidation/retry behavior. |

These are repair acceptance items, not claims that the existing runtime tests reproduced the
failures. The implementation must trace actual transaction boundaries and concurrency before
choosing each fix.

## Migration order and acceptance gates

### Step 1 Freeze identity and authority contracts

Specify provider versus query connection, namespace addressing, asset/field identity, management
mode and the observed-metadata boundary. Apply the environment isolation, workspace ownership
and read-sharing direction above. Decide the minimum typed
semantic payloads and runtime-versus-authoring boundary. Specify keys, FK constraints, state
transitions, wire compatibility and role rows. Select a real catalog adapter for the later
Iceberg proof; do not let a generic provider enum substitute for that decision.

Resolve operator provisioning versus workspace self-service and storage-prefix ownership
alongside those role rows. Separate DP organizational namespaces from upstream namespace
provisioning; a pending or failed provisioning state must never appear physically ready.

**Exit:** a reviewable schema/API specification with example legacy and new records, every
new action's role mapping, and explicit treatment of ambiguous legacy references. This audit
is the input to that specification; it is not migration SQL.

### Step 2 Add catalog identity and migrate the current registry

Use new forward-only migrations; do not edit released V15/V25/V26/V36. Backfill a registry
provider/binding per current LAKE datasource, namespaces from distinct paths and assets from
existing lake rows. Retain table UUIDs or a deterministic, verified one-to-one mapping. Mark
legacy registrations external/read-only; never assume they are managed simply because their
format is Iceberg. Preserve explicit metadata-file locations and existing read behavior.

Discover other database assets through bounded, authorized refresh jobs, not network calls
inside Flyway. Unknown fields, upstream identities and historic schemas remain unresolved
until observed. Reconcile duplicate bindings deliberately; credentials that reach the same
endpoint do not prove identical scope or ownership.

Implement compatible service writes in the same local transaction while both old and new
representations exist, or freeze legacy mutations during the cutover. There must be one
defined write authority at every phase. Add durable revisions to pool/cache reconciliation.

**Exit:** migration fixtures preserve all legacy registrations, grants and reads; empty
namespace lifecycle works; refresh preserves IDs; peer recovery works after a missed message;
and external data remains protected from managed operations. UI/MCP can inspect stable IDs
and revision/availability without losing existing names and response fields.

At this step, an empty namespace is a DP registry namespace, an observed existing upstream
namespace, or an explicit pending-provisioning request. Record which it is. Creating a local
row must not imply that an upstream physical namespace was created; that operation is proven
with the provider adapter in Step 4.

### Step 3 Migrate semantic bindings and strengthen meaning

Add normalized subjects and typed payload/evidence/review support while preserving fact UUIDs
and `template_implements` FKs. Resolve legacy refs only when datasource, complete namespace,
table and column match unambiguously. Same-name tables, missing namespace, dropped assets and
retired sources need a durable unresolved/review result. Never infer meanings or snapshots
from prose during migration. Keep a compatibility resolver for old MCP requests.

Make datasource-less workspace definitions a supported discovery path; add cross-source
bindings with the agreed visibility rules. Fix the source risks above through meaningful
concurrency and identity tests. Update constraints, kinds, wire documentation, permission
rows and drift guards together where the new contract changes them.

**Exit:** existing facts and citations remain readable; namespace collisions do not cross-bind;
Iceberg rename with preserved identity differs from drop/recreate; inaccessible references
cannot leak; approval/evidence history is explicit; concurrent trust/supersession behavior is
correct; and cents-to-dollars and grain-changing transformations can express distinct output
meaning. No writable lake prerequisite is needed to prove this foundation.

### Step 4 Implement DP Lake on the repaired catalog

Deliver the chosen catalog adapter and table/namespace management, then reliable ingestion,
quality/publication, operation reconciliation, lineage and maintenance using the entities
above. Validate exact versions, object storage, resource budgets, failure recovery and restore
as required by the execution approach. Implement the immutable runtime semantic contract
before relying on it for ingestion validation or historical explanations.

**Exit:** one complete file-to-table-to-cross-source-result journey, including recurring runs,
failed-batch recovery, maintenance and UI/MCP inspection. Measured performance targets follow
that correctness proof; catalog rows alone do not make writes or real-time serving reliable.

### Cutover and rollback discipline

Before retiring legacy fields/endpoints, migrate every consumer: introspection/enrichment,
pool/view construction, stats, REST/MCP/UI, bootstrap manifests, template citations and
import/export. Recount the migration mappings and unresolved refs; do not claim completeness
from a handful of fixtures. Before managed writes begin, rollback may return to the legacy
reader only while compatible projections are maintained. Once new features create data or
history the old version cannot represent, require a forward fix or a tested coordinated
restore; do not advertise an unconditional database-only rollback.

## Validation required before schema approval

Existing coverage includes registry persistence/preflight, view isolation, pool ownership,
successful two-instance invalidation, explicit Iceberg metadata-file reads, semantic
record/list/drift/supersession, grant filtering and transform citations. Tests include
`LakeTableRepositoryIntegrationTest`, `LakeTableRegistryServiceTest`,
`LakeRegistryTwoInstanceE2eTest`, `LakeMinioE2eTest`, `LearnedFactRepositoryIntegrationTest`,
`LearnedFactRecorderTest`, `LearnedFactDriftTest`, `LearnedFactsEnricherTest`,
`LearnedSemanticsE2eTest` and `TransformImplementsE2eTest`. Their source was inspected; no
fresh passing result is asserted by this audit.

The new acceptance set must cover:

- Full old-schema-to-new-schema migration with registered files, existing UUID citations,
  same-named tables, workspace-global definitions, inaccessible and unresolved refs.
- Native type/precision/nested-field preservation, schema evolution, nullable changes and
  provider identity unavailable versus authoritative identity available.
- Empty namespaces, namespace-depth capability refusal, renames, detach/drop/recreate,
  dependency refusal and prevention of purging externally owned objects.
- Scoped complete/partial refresh, source unavailable, deletion detection, optimistic
  revisions and recovery from dropped invalidation messages.
- Concurrent duplicate records, supersession, review and drift demotion; historical meaning
  unchanged when current citations or aliases change.
- Same UI/MCP outcomes and permission enforcement for every new operation, including
  cross-source semantic discovery and protected evidence.
- Later, remote commit acknowledgment loss, overlapping ingestion/maintenance, quality
  errors versus failures, deterministic replay, and catalog/data/progress restore.

## Explicit exclusions and next decisions

Do not build a custom Iceberg transaction/catalog implementation, replicate its manifests into
PostgreSQL, re-key datasources, introduce another scheduler, or replace learned facts with a
prose-only catalog. Do not require a custom semantic compiler, graph database, vector search,
global arbitrary-SQL lineage, broad CDC or arbitrary row/column policy administration for the
first catalog repair. Workspace ownership and explicit table/namespace read sharing remain
in scope. These exclusions preserve the current working architecture while
leaving deliberate extension points.

The environment and workspace direction above settles the logical isolation and initial
ownership model. Remaining specification decisions include the provider/query-binding details,
sharing role rows, namespace-grant inheritance, materialization and revocation policy,
minimum typed meaning and approval workflow, the runtime semantic contract relative to #181,
and the specific catalog provider/credential integration to prove. Performance and delivery
dates remain outside this schema decision.

## Evidence references

Key code anchors are the DDL and production consumers below. They establish current behavior;
the proposed entity names and migration steps above are recommendations.

[datasource-ddl]: ../../../modules/app/src/main/resources/db/migration/V1__initial_schema.sql
[grants]: ../../../modules/app/src/main/resources/db/migration/V23__rbac_core.sql
[lake-ddl]: ../../../modules/app/src/main/resources/db/migration/V15__lake_tables.sql
[lake-errors]: ../../../modules/app/src/main/resources/db/migration/V22__lake_table_view_errors.sql
[lake-model]: ../../../modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeTable.kt
[lake-views]: ../../../modules/datasources/src/main/kotlin/co/datapipelines/datasources/LakeViewStatements.kt
[lake-service]: ../../../modules/application/src/main/kotlin/co/datapipelines/application/datasources/LakeTableRegistryService.kt
[registry]: ../../../modules/datasources/src/main/kotlin/co/datapipelines/datasources/DefaultDatasourceRegistry.kt
[introspection]: ../../../modules/datasources/src/main/kotlin/co/datapipelines/datasources/SchemaIntrospector.kt
[schema-payloads]: ../../../modules/datasources/src/main/kotlin/co/datapipelines/datasources/SchemaPayloads.kt
[facts-ddl]: ../../../modules/app/src/main/resources/db/migration/V25__learned_facts.sql
[fact-model]: ../../../modules/datasources/src/main/kotlin/co/datapipelines/datasources/semantics/LearnedFact.kt
[enricher]: ../../../modules/application/src/main/kotlin/co/datapipelines/application/semantics/LearnedFactsEnricher.kt
[semantic-service]: ../../../modules/application/src/main/kotlin/co/datapipelines/application/semantics/SemanticsService.kt
[implements-ddl]: ../../../modules/app/src/main/resources/db/migration/V36__template_implements.sql
[implements]: ../../../modules/templates/src/main/kotlin/co/datapipelines/templates/TemplateImplements.kt
[execution-lineage]: ../../../modules/app/src/main/resources/db/migration/V3__execution_lineage.sql
[checks]: ../../../modules/app/src/main/resources/db/migration/V28__pipeline_check_runs.sql
[schedules]: ../../../modules/app/src/main/resources/db/migration/V38__scheduler_core.sql
[job-executor]: ../../../modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/JobExecutor.kt
[semantic-research]: ../../semantic-layer-research.md#part-five--owner-direction-2026-08-13-discussion-resume-point
[workspace-rules]: ../../../modules/web/src/main/kotlin/co/datapipelines/web/datasources/DatasourceWorkspaceRules.kt
[lake-wiring]: ../../../modules/web/src/main/kotlin/co/datapipelines/web/config/LakeConfiguration.kt
