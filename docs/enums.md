# Enumerations Reference

**Status:** v1.9 (living document — updated as enums evolve)
**Owner:** datapipelines.co core
**Purpose:** Single source of truth for every enum value used across the system. Prevents spelling drift across specs and across the codebase.

---

## How to use this document

- Every enum used in datapipelines.co is cataloged here.
- Specs may inline enum values for readability, but this document is the **authoritative reference**.
- If a value appears in code or a spec that doesn't match this document, the spec/code is wrong.
- When adding a new enum value: add it here first, then propagate to specs and code.
- Values are **additive-only** per the stability promises in individual specs.
- Each enum has exactly ONE authoring spec (see the cross-reference table). Other specs are consumers.
- Values marked **(reserved)** are registered for future use — they MUST NOT appear in generated code or be accepted by validators in v1.

### Case & serialization convention

The strings cataloged here are the **wire values** — what appears in JSON payloads, exactly as written (case included). Kotlin enum classes use UPPER_SNAKE_CASE constants with an explicit mapping to the wire value:

```kotlin
enum class WriteMode(@JsonValue val wire: String) {
    REPLACE("replace"),
    APPEND("append");

    companion object {
        @JsonCreator @JvmStatic
        fun fromWire(v: String) = entries.firstOrNull { it.wire == v }
            ?: throw IllegalArgumentException("Unknown WriteMode: $v")
    }
}
```

Where the cataloged value is already UPPER (`DQL`, `POSTGRES`, `SUCCESS`), wire and constant coincide. Never rely on default `Enum.name` serialization for lowercase/kebab/snake wire values — the explicit `@JsonValue` mapping is mandatory so the catalog string stays the single source of truth.

---

## 1. `LogicalType` — canonical data types

**Source:** [Type System §3](type-system.md#3-canonical-types-v1)
**Used by:** every spec — this is the foundational type vocabulary.

| Value | Wire | Description |
|---|---|---|
| `NULL` | `null` | All-null column; type could not be inferred |
| `BOOLEAN` | `boolean` | Two-valued logic: true / false / null |
| `INTEGER` | `number` | Exact integer, int32 range (≤ 2^31 − 1) |
| `BIGINTEGER` | `string` | Exact integer, int64 range (≤ 2^63 − 1). Exceeds IEEE 754 double safe integer range. |
| `DECIMAL` | `number` | Numeric with precision ≤ 15. Scale present = exact origin; scale omitted = approximate origin (REAL → `DECIMAL(7)`, DOUBLE → `DECIMAL(15)`) — see [Type System §3.4](type-system.md#34-why-realdouble-collapse-into-decimal) |
| `BIGDECIMAL` | `string` | Numeric with precision > 15 (or unbounded — precision omitted) |
| `STRING` | `string` | Variable-length text. Includes source UUIDs, JSON, XML, enums, intervals. |
| `BINARY` | `string` (base64) | Variable-length bytes |
| `DATE` | `string` (ISO 8601 date) | Calendar date, no time |
| `TIME` | `string` (ISO 8601 time) | Time of day, no date, no timezone |
| `TIMESTAMP` | `string` (ISO 8601 datetime, UTC) | Date and time, normalized to UTC on ingest |

**Excluded from `parameters` declarations:** `NULL` (only the other 10 may be parameter types).

---

## 2. `NodeType` — pipeline node SQL category

**Source:** [Pipeline Contract §4.6](pipeline-contract.md#46-field-reference)
**Used by:** pipeline-contract, dag-executor.

| Value | Description |
|---|---|
| `DQL` | Data Query Language — `SELECT`. Produces a ResultSet. May have an `output` block (tempdb / caller / datasource). |
| `DML` | Data Manipulation Language — `INSERT`, `UPDATE`, `DELETE`, `MERGE`. Produces a row count. No `output` block. |
| `DDL` | Data Definition Language — `CREATE`, `ALTER`, `DROP`, `TRUNCATE`. Produces success/failure. No `output` block. |
| `PIPELINE` | Executes another pipeline as a child execution (pipeline composition). Carries a `pipeline` ref `{name, version}`, never `source`/`template`; may carry an `output` block only when the pinned child has a caller node ([Pipeline Contract §4.9](pipeline-contract.md#49-json-structure-pipeline-node), §8.5). |
| `CALCULATOR` | Evaluates a catalog calculator and writes ONE typed value into the execution Context. Carries `kind`, `inputs` and `context_key`, never `source`/`template`/`output` — it runs no SQL and produces no table ([Pipeline Contract §4.10](pipeline-contract.md#410-json-structure-calculator-node), [Calculators](calculators.md)). |

**Reserved for future:** `EXPRESSION`, `HTTP` (non-SQL node types — see [ROADMAP](ROADMAP.md)).

---

## 3. `OutputTarget` — where a DQL node's ResultSet goes

**Source:** [Pipeline Contract §4.7](pipeline-contract.md#47-output-block-reference)
**Used by:** pipeline-contract, dag-executor, staging.

| Value | Required fields | Description |
|---|---|---|
| `tempdb` | `table` | Stage ResultSet into in-memory tempdb table for downstream nodes to query. |
| `caller` | (none) | Return ResultSet as the pipeline's result. **Default if the `output` block is omitted.** At most one node per pipeline may resolve to `caller`; zero is legal (pure write-back pipelines emit no `data_ready`). |
| `datasource` | `datasource`, `table`, `mode` | Stream ResultSet to an external datasource's table. |

**Reserved for future:** `kafka`, `s3`, `email`, `webhook` (see [ROADMAP](ROADMAP.md)).

---

## 4. `WriteMode` — for `output.target: "datasource"`

**Source:** [Pipeline Contract §4.7](pipeline-contract.md#47-output-block-reference)
**Used by:** pipeline-contract, dag-executor.

| Value | Description |
|---|---|
| `replace` | TRUNCATE (or DELETE) + INSERT in one transaction |
| `append` | INSERT only; existing rows preserved |

---

## 5. `Dialect` — supported source database dialects

**Source:** [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) (single authority)
**Used by:** datasources (driver dispatch), templates (template targets a dialect), pipeline-contract (validation: template dialect must match datasource dialect).

| Value | JDBC driver |
|---|---|
| `POSTGRES` | `org.postgresql:postgresql` (bundled) |
| `ORACLE` | `com.oracle.database.jdbc:ojdbc11` (optional `-Poracle` profile) |
| `MSSQL` | `com.microsoft.sqlserver:mssql-jdbc` (bundled) |
| `MYSQL` | `com.mysql:mysql-connector-j` (optional `-Pmysql` profile) |
| `H2` | `com.h2database:h2` (bundled; also used for staging) |
| `DUCKDB` | `org.duckdb:duckdb_jdbc` (bundled) |
| `SQLITE` | `org.xerial:sqlite-jdbc` (bundled) |
| `LAKE` | `org.duckdb:duckdb_jdbc` (bundled) — object storage read in place (Parquet/Iceberg); DuckDB is the engine, with a different §5.6 posture from `DUCKDB`. See [Datasources §4.1](datasources.md#41-dialect-catalog). |

**Reserved for future:** `SNOWFLAKE`, `BIGQUERY`, `REDSHIFT` (see [ROADMAP](ROADMAP.md)).

---

## 5A. `CredentialKind` — what a datasource's stored credential IS

**Source:** [Datasources §3.4](datasources.md#34-credential-kinds) (single authority)
**Used by:** datasources (validation, pool build, encryption), rest-api (`POST /api/v1/datasources`), mcp-server (`datasources_get` reports the kind; there is no MCP create — [MCP Server §6.2.22](mcp-server.md#6222-removed--no-datasource-writes-on-this-surface)), metadata-db (`datasources.credential_kind`).

Wire values are lowercase snake_case, so the `@JsonValue` mapping is explicit per the case convention above.

| Value | Wire | `username` | `secret` | Description |
|---|---|---|---|---|
| `PASSWORD` | `password` | required | required on create | A database login. The default when a payload names no kind, and what the legacy top-level `username`/`password` pair means. |
| `TOKEN` | `token` | optional | required on create | A bearer or personal access token, placed where the pinned driver wants it by the adapter. |
| `PRIVATE_KEY` | `private_key` | absent | required on create | A PEM private key. Catalogued for the reference targets; no shipped dialect accepts it yet. |
| `SERVICE_ACCOUNT_JSON` | `service_account_json` | absent | required on create | One service-account JSON document. Catalogued for the reference targets; no shipped dialect accepts it yet. |
| `NONE` | `none` | absent | absent | Nothing is stored — an IAM role, OS auth, or an embedded file database with no authentication. |

These are **not** `(reserved)` values in the enums.md sense: all five are accepted by the validator and stored by the column. What a shipped dialect can USE is narrower and is the adapter's declaration (`supportedCredentialKinds`, [Datasources §3.4](datasources.md#34-credential-kinds)) — a kind outside a dialect's set is refused at save with `datasource.validation.properties_invalid`, never stored and ignored.

---

## 6. `TemplateEngine` — template language

**Source:** [Templates §4](templates.md#4-freemarker-integration)
**Used by:** templates (engine dispatch).

| Value | Description |
|---|---|
| `freemarker` | Apache Freemarker template engine. Default and only supported engine in v1. |

**Reserved for future:** `pebble`, `handlebars`, `thymeleaf-sql`, `none` (raw SQL with no template processing — see [ROADMAP](ROADMAP.md)).

---

## 6A. `TemplateType` — template kind

**Source:** [Template Hierarchy §5](template-hierarchy-design.md)
**Used by:** templates (engine-configuration dispatch, type/dialect consistency), pipeline-contract (reference legality).

| Value | Description |
|---|---|
| `sql` | The template renders SQL for pipeline nodes. Requires a `dialect`. Default, and the only kind that existed before 2026-09-02 (046) — every stored template backfilled to it. |
| `html` | The template renders HTML through a second, auto-escaping engine configuration (design §6). Declares no `dialect`; **no pipeline node may reference it** (`pipeline.validation.template_type_mismatch`). |

Fixed at template **create** and identical on every version of a template (`template.validation.type_immutable`). Serialization is the lowercase wire value (`"sql"` / `"html"`), per the case convention above. There are no reserved future values — component sub-typing (kpi, aggrid, svg, form, …) belongs to the future dashboard abstraction and is deliberately absent here (design §2).

---

## 7. `StagingEngine` — tempdb engine

**Source:** [Pipeline Contract §5](pipeline-contract.md#5-settings)
**Used by:** pipeline-contract (`settings.tempdb.engine`), staging (factory dispatch), dag-executor (instantiation).

| Value | Description |
|---|---|
| `H2` | In-memory H2 database. Default. Only supported engine in v1. |

**Reserved for future:** `DUCKDB` (in-memory DuckDB — better for analytical workloads; see [ROADMAP](ROADMAP.md)).

> **Declaration reality (2026-08-08).** The frozen module dependency graph ([module-structure §4.2](module-structure.md#42-the-dependency-rule-machine-checkable)) makes the "pipeline-contract authors, staging consumes" line above impossible: `staging` depends only on `typesystem`, so it cannot see a type declared in `pipeline-contract`. In v1 the enum has a single value (`H2`), so `pipeline-contract` declares it (for the `settings.tempdb.engine` wire value, with `@JsonValue`) and `staging` declares an identical local `enum class StagingEngine { H2 }` for `StagingFactory` dispatch; `dag` (which depends on both) maps between them — trivial while there is one value. **Consolidation is a P4/dag decision:** when a second engine (`DUCKDB`) lands, move `StagingEngine` into `typesystem` (the shared lower layer, exactly as `Dialect` resolved the same class of problem) so there is one authority. Until then the duplication is bounded to a single constant and dag owns the mapping.

---

## 8. `Scope` — API key authorization scope

**Source:** [Auth §7.5](auth.md#75-scopes)
**Used by:** auth, every endpoint and MCP tool (scope enforcement — see the scope↔operation matrix in auth.md).

Hierarchical: `admin ⊃ author ⊃ execute ⊃ read`. A key with a higher scope has all lower scopes too.

| Value | Includes | Description | Issuable to a key? |
|---|---|---|---|
| `read` | — | Read pipelines, templates, datasources (metadata), executions | yes |
| `execute` | `read` | Execute pipelines; retrieve execution results | yes |
| `author` | `execute`, `read` | Create / modify pipelines and templates | yes |
| `admin` | `author`, `execute`, `read` | Manage datasources, users, system config | **no** — RBAC round 1 removed it from the key wire (O-2): it was the only scope that bought a key an INSTANCE verb, and instance verbs are human |

**This is the CREDENTIAL axis, and since RBAC round 1 it is an API-key property only** — a session JWT carries no `scopes` claim. What a signed-in person may do is [`Capability`](#8b-capability--what-a-membership-may-do) in the active workspace. Both axes are enforced for a key: its scope AND its issuer's current role ([Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative)).

---

## 8B. `Capability` — what a membership may do

**Source:** [Auth §11A](auth.md#11a-roles) (the role table), [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative) (the per-operation minimums)
**Used by:** auth (`ScopeMatrix.allowed`), every REST handler and MCP tool.

The ROLE axis. It travels with a **membership**, not with a credential (RBAC design D-R1): the same person is a viewer in one workspace and an author in another, which is why it cannot live on the user.

**Deliberately NOT a hierarchy**, unlike [`Scope`](#8-scope--api-key-authorization-scope). An author may not `release` and a promoter may not author, so neither dominates the other — each value is a predicate over the membership row's three flags (`author`, `promoter`, `admin`; all false = viewer), never an ordinal comparison.

| Value | Held by | Description |
|---|---|---|
| `view` | any member | Read everything in the workspace |
| `execute` | any member | Execute pipelines, read results, cancel own runs, run capped read-only SQL probes (D-R3: "viewers execute") |
| `author` | `author` | Create/edit drafts, discard, restore, purge, publish endpoints, register lake tables, issue own keys |
| `switch` | `author` OR `promoter` | Switch the served version — the rollback lever (O-1). Its own value because it is the one verb both hold and neither implies |
| `promote` | `promoter` OR `admin` | `release` and `promote` — the DevOps verbs |
| `ws_admin` | `admin` | Members and roles, workspace-bound datasource registration, the workspace audit trail |
| `super_admin` | `users.is_admin` | Instance verbs: create/deactivate workspaces, users, config, datasource grants. Implicitly a member of every workspace (D-R8), audited as `auth.super_admin_acting` |

`admin → author` is a database CHECK (`chk_workspace_member_admin_authors`, [metadata-db §4.12](metadata-db.md#412-workspace_members)), so `author` reads straight off the row rather than re-spelling the implication at each predicate.

**Where the two axes disagree, and neither is redundant:** `execute` is the second SCOPE but the viewer-level CAPABILITY — a `read` key may not execute, a viewer's session may. The datasource probes run the other way: `author` scope (a `read` key must not reach row data) but `view` capability (a viewer gets capped read-only SELECT by design).

---

## 8A. `ApiKeyKind` — what an API key IS

**Source:** [Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)
**Used by:** auth, web, mcp-server, persistence (`api_keys.kind`).

| Value | Description |
|---|---|
| `user` | Every key that existed before round 074, and the default for any key minted without an explicit kind: scopes, a pinned workspace, and the whole API surface those scopes allow |
| `endpoint` | A credential for published endpoints only: no scopes are consulted, workspace-pinned, and it authorises exactly the endpoints its bindings cover plus the result cursor of executions it started |
| `server` | The promotion peer's credential (091): minted by an `admin`, presented as `DP-Promotion-Key` by a SENDING deployment, and accepted on the promotion receiver's routes and nowhere else. No scopes are consulted; its authority is that route family |

> A kind is **not** a scope and is deliberately not modelled as one. Scopes answer "how much may this credential do?" along one hierarchy; a kind answers "what kind of credential is this?", and the two axes do not compose — an endpoint key is not "a user key with fewer scopes". The wire form is the lowercase name, as with [`Scope`](#8-scope--api-key-authorization-scope).

> **A `server` key authenticates nothing outside the promotion routes.** Presented as an ordinary `DP-API-Key` it is refused on every route — REST, htmx partials, `/mcp` and every UI page — with `endpoint.key_kind_refused`. Same rule as the endpoint kind, different family.

> **An endpoint key with no binding on any ancestor of the path it presents at authorises nothing.** The absence of a binding is never a fall-through to the user-key rule; if it were, publishing a new endpoint would silently widen every existing endpoint key's reach at the moment of publication.

---

## 9. `NodeStatus` — per-node execution outcome

**Source:** [DAG Executor §7](dag-executor.md#7-node-stats-collection)
**Used by:** dag-executor (node_stats), rest-api (response envelope).

| Value | Description |
|---|---|
| `SUCCESS` | Node completed without error |
| `FAILED` | Node threw an exception; pipeline aborted |
| `ABORTED` | Node never started because a dependency failed |
| `RUNNING` | The node is executing **right now**. Appears only in the LIVE progress snapshot a `RUNNING` execution's row carries (108 §D, [Metadata DB §8.3](metadata-db.md#83-stale-execution-sweep)); a TERMINAL snapshot never contains it — a node that had started and never reported is `ABORTED` there, which is [DAG Executor §7.2](dag-executor.md#72-noderesult--nodestats)'s row and is unchanged. A node that has not started yet is ABSENT from the live snapshot rather than given a status |

---

## 10. `ExecutionStatus` — whole-pipeline execution outcome

**Source:** [REST API §6.4](rest-api.md#64-event-types), [DAG Executor §5](dag-executor.md#5-execution-lifecycle)
**Used by:** rest-api, dag-executor, mcp-server, persistence (`pipeline_executions.status`).

| Value | Description |
|---|---|
| `RUNNING` | Execution in progress |
| `SUCCESS` | All nodes completed; result returned |
| `FAILED` | A node failed; execution aborted |
| `ABORTED` | Execution cancelled: client disconnect beyond `sse.disconnect-grace-seconds`, explicit `DELETE /api/v1/executions/{id}`, server shutdown, or the crash sweep ([Metadata DB §8](metadata-db.md#8-operational-jobs)) |

**Reserved for future:** `PARTIAL` (partial-result mode where some nodes succeeded but a non-critical path failed — see [ROADMAP](ROADMAP.md)).

> **Declaration reality (2026-08-10).** Declared in the `dag` module, for the same layering reason `SseEventType` is (§11): `web` implements rest-api at layer 5 and depends on `dag` at layer 3, never the reverse ([module-structure §4.2](module-structure.md#42-the-dependency-rule-machine-checkable)). The executor is what produces the status and what writes `pipeline_executions.status`, so the enum lives at the lowest layer that needs it and `web` consumes it. This document, [rest-api](rest-api.md) and [metadata-db](metadata-db.md) remain the **wire authorities** — the declaration site is an implementation consequence, not a change of ownership.

---

## 11. `SseEventType` — pipeline execution event types

**Source:** [REST API §6.4](rest-api.md#64-event-types), [DAG Executor §10](dag-executor.md#10-sse-event-integration)
**Used by:** rest-api (SSE endpoint), dag-executor (event emitter), mcp-server (event forwarding).

| Value | Emitted when | Order |
|---|---|---|
| `execution_started` | Execution begins | First event, exactly once |
| `node_started` | A node begins executing | After its dependencies completed |
| `node_completed` | A node finishes successfully | After matching `node_started` |
| `node_failed` | A node fails | After matching `node_started`; pipeline then halts |
| `pipeline_completed` | All nodes succeeded; final result imminent | After all `node_completed` |
| `pipeline_failed` | Execution halts due to node failure | After the matching `node_failed` |
| `execution_aborted` | Execution cancelled (explicit `DELETE`, disconnect grace elapsed, shutdown) | Terminal; replaces `pipeline_completed`/`pipeline_failed` |
| `data_ready` | Result stored; payload carries schema, inline first page, and `result_url` cursor | Last event when a caller node exists; follows `pipeline_completed` |

**Order guarantees:** see [REST API §6.5](rest-api.md#65-event-ordering-guarantee).

**Reserved for future:** `data_chunk` (streaming row chunks for incremental processing — see [ROADMAP](ROADMAP.md)).

---

## 12. `ResultDelivery` — REMOVED (v1.1)

**Removed 2026-08-07** ([SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md), decision D9). The inline-vs-claim-check split no longer exists: every caller result is stored in Redis and `data_ready` always carries schema + inline first page + `result_url` cursor. See [REST API §7](rest-api.md#7-result-delivery). Section number retained so later sections keep their numbering.

---

## 13. `ResultFormat` — wire format for result data

**Source:** [REST API §7](rest-api.md#7-result-delivery)
**Used by:** rest-api, mcp-server.

| Value | MIME type | Description |
|---|---|---|
| `json` | `application/json` | JSON array-of-arrays with separate schema. Default. |
| `arrow` | `application/vnd.apache.arrow.ipc` | Apache Arrow IPC binary stream with embedded schema. Efficient for large analytical clients. |
| `csv` | `text/csv` | CSV with header row. All values as their wire-encoded strings. |

---

## 14. `SslMode` — datasource TLS mode

**Source:** [Datasources §5](datasources.md#5-connection-pool-configuration)
**Used by:** datasources (PG-idiomatic; analogous config for other dialects).

| Value | Description |
|---|---|
| `disable` | No TLS |
| `prefer` | TLS if available, plain if not |
| `require` | TLS required; certificate not verified |
| `verify-ca` | TLS required; CA verified |
| `verify-full` | TLS required; CA + hostname verified (recommended for production) |

---

## 15. `AuthAuditEvent` — auth audit log events

**Source:** [Auth §10.1](auth.md#101-events)
**Used by:** auth, observability.

| Value | Trigger |
|---|---|
| `auth.login.success` | Login succeeded, JWT issued (OIDC or local — the details' `provider` names the method) |
| `auth.login.domain_not_allowed` | User's email domain not in allowlist |
| `auth.login.user_inactive` | User account is deactivated (OIDC or local — same event) |
| `auth.login.oidc_error` | OIDC provider returned an error |
| `auth.login.bad_credentials` | Local login failed: unknown email, OIDC-only account, or wrong password — deliberately indistinguishable ([Auth §5A.5](auth.md#5a5-enumeration-resistance-and-the-password-policy)) |
| `auth.login.locked` | Local account locked after `lockout.max-failures` consecutive failures ([Auth §5A.3](auth.md#5a3-lockout)) |
| `auth.password.seeded` | Config seeded the bootstrap admin's one-time local credential ([Auth §5A.2](auth.md#5a2-seeding-the-first-admin)) |
| `auth.password.changed` | User changed their own password (self-service or forced, [Auth §5A.4](auth.md#5a4-forced-password-change)) |
| `auth.password.reset` | Admin reset a user's password — new one-time credential ([Auth §5A.1](auth.md#5a1-accounts)) |
| `auth.password.disabled` | Admin disabled a user's local access — account is OIDC-only ([Auth §5A.1](auth.md#5a1-accounts)) |
| `auth.password.change_failed` | Self-service password change failed (wrong current password) — counted on the same §5A.3 lockout counter as `POST /login` ([Auth §5A.4](auth.md#5a4-forced-password-change)) |
| `auth.password.change_locked` | Self-service change refused because the account's §5A.3 lockout was already engaged — consulted before any Argon2 work ([Auth §5A.4](auth.md#5a4-forced-password-change)) |
| `auth.user.created` | Admin created a local account ([Auth §5A.1](auth.md#5a1-accounts); details carry the acting admin) |
| `auth.user.unlocked` | Admin cleared a local account's lockout ([Auth §5A.3](auth.md#5a3-lockout)) |
| `auth.logout` | User logged out (cookie cleared) |
| `auth.api_key.created` | New API key issued |
| `auth.api_key.revoked` | API key revoked |
| `auth.api_key.used` | API key validated (sampled 1/100) |
| `auth.api_key.rejected` | API key validation failed |
| `auth.scope.denied` | Request rejected for insufficient scope |
| `auth.user.deactivated` | Admin deactivated a user |
| `auth.user.activated` | Admin reactivated a user |
| `auth.user.admin_granted` | Admin granted admin scope to user |
| `auth.user.admin_revoked` | Admin revoked admin scope from user |
| `auth.workspace.created` | Workspace created — by a super admin through the service path, or by the boot seeder for `demo` (details carry `actor: system`) |
| `auth.workspace.updated` | A workspace's display name was changed |
| `auth.workspace.deleted` | A workspace was soft-deleted (empty only) — distinct from `workspace.deactivated`, which purges nothing |
| `auth.workspace.stranded_content` | Content committed into a workspace concurrently with its deletion and is now invisible with its name held — the detector, not a refusal |
| `auth.workspace.header_rejected` | `DP-Workspace` presented on an API-key request |
| `auth.super_admin_acting` | A super admin acted in a workspace they hold no explicit membership in (RBAC design D-R8). Emitted at the scope interceptor's one choke point on every governed handler, READS INCLUDED — the 404 rule's whole promise is that a workspace is invisible from outside, and the one principal exempt from it is the one whose reads most need to be on the record. `details` carry the operation, the workspace, the path and `acting_via: super_admin` |
| `workspace.member_added` | A member was added, with their capability flags (`details.flags`). The first-login demo join carries `reason: first_login_demo_viewer` |
| `workspace.member_invited` | An invitation was created — or an existing one's flags replaced by a re-invite (the latest admin decision wins, audited every time; [Auth §4.6](auth.md#46-invitations)). `details` carry workspace, email, flags |
| `workspace.invitation_revoked` | A pending invitation was revoked ([Auth §4.6](auth.md#46-invitations)). `details` carry workspace and email |
| `workspace.invitation_materialised` | A pending invitation became a real membership at login — the inviter's decision being executed by the invitee's first sign-in ([Auth §4.6](auth.md#46-invitations)). `details` carry workspace, email, flags and `inviter` |
| `workspace.member_removed` | A member was removed |
| `workspace.member_flags_changed` | A member's capability flags were replaced — `details.from` and `details.to` carry both sets, because a membership row keeps no history of its own |
| `workspace.deactivated` | A workspace was deactivated (RBAC design D-R10). Nothing it owns is purged; the five effects follow from readers consulting its state |
| `workspace.reactivated` | A deactivated workspace was restored |
| `datasource.granted` | A datasource was granted to a workspace (D-R7) — the verb that decides who can see a live database credential's data. `details.already_granted` distinguishes a new grant from an idempotent re-grant |
| `datasource.revoked` | A datasource's grant to a workspace was removed. The datasource itself is untouched |
| `endpoint.served` | A published endpoint served a request (074). Details carry the endpoint id, the execution id and the outcome; the key id is the row's own `key_id`. This row is also what proves an endpoint key may read that execution's result |
| `endpoint.key_bound` | An API key was bound to a node of the endpoint tree |
| `endpoint.key_unbound` | An API key's binding to a node was removed |
| `endpoint.published` | An endpoint was published over a released pipeline |
| `endpoint.unpublished` | An endpoint was deleted |

> Password and lockout events exist only for the optional local accounts ([Auth §5A](auth.md#5a-local-password-accounts-optional)); an OIDC-only deployment never writes them. No event in this table ever carries credential material.

**Datasource audit events** (same `audit_log` table, defined in [Datasources §7.4](datasources.md#74-decryption-points-and-audit-log)):

| Value | Trigger |
|---|---|
| `datasource.pool_build` | Credential decrypted to build a connection pool |
| `datasource.pool_rebuild` | Update-triggered eviction of a live pool; carries the initiating operator (the decryption itself is the subsequent `pool_build`) — see [Datasources §7.4](datasources.md#74-decryption-points-and-audit-log) |
| `datasource.connection_test` | Explicit connection test (`POST .../test`) |
| `datasource.key_rotation` | Master-key rotation re-encryption pass |

**Promotion audit events** (same `audit_log` table, defined in [Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)):

| Value | Trigger |
|---|---|
| `auth.promotion.rejected` | A request on `/api/v1/promotion/**` was refused by the peer-credential gate — wrong key, missing key, or no key configured on this receiver. `details` carries the path and a `reason` of `key_mismatch` or `no_key_configured`; it never carries the credential ([Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01)) |
| `auth.promotion.accepted` | A promotion batch was applied by the receiver, in one transaction. The rejected/accepted pair is the promotion CHANNEL's two outcomes, which is why both sit in the auth domain beside the credential that gates them. `details` carries `source_env` (the sender's `deployment.name`), `key_fingerprint` (a truncated SHA-256 of the key presented, never the key), the target workspace, and the template/pipeline counts. The actor is the system service account every promoted row is stamped with ([Auth §4.5](auth.md#45-the-system-service-account-r7)) |

**MCP audit events** (same `audit_log` table, defined in [MCP §14](mcp-server.md#14-audit)):

| Value | Trigger |
|---|---|
| `mcp.tool.called` | Every MCP tool call, success or failure — tool, actor (key id + owner), target, outcome, `elapsed_ms`, correlation id. Emitted by the dispatcher for every outcome including the §7.6 scope refusal ([MCP §14](mcp-server.md#14-audit)) |
| `mcp.tool.write` | Every call to a tool the MCP catalog declares **mutating** — a write to stored definitions or to customer data, `pipelines_execute_node` node runs included — one event after the tool returns, on success and on failure (the failure carries the error code). The trace that an `author`-scoped key exercised a write path ([MCP §14](mcp-server.md#14-audit)) |
| `mcp.execution.launched` | One per `pipelines_execute` launch (107), emitted BEFORE the blocking run begins — key id, correlation id, pipeline/execution id. `executions_cancel`'s same-credential rule joins on it, because the dispatcher's end-of-call `mcp.tool.called` row cannot exist while the launching call is still blocking ([MCP §14](mcp-server.md#14-audit)) |

**Version lifecycle audit events** (same `audit_log` table, defined in [Versioning §3](versioning.md#3-version-lifecycle); emitted by the 101 REST verbs, all session-only — an API key cannot produce one):

| Value | Trigger |
|---|---|
| `pipeline.version.discarded` | A RELEASED pipeline version was discarded (`POST /pipelines/{id}/versions/{v}/discard`). `details` carries the pipeline id, version, and the pointer before/after ([Versioning §3.1](versioning.md#31-statuses-and-verbs)) |
| `pipeline.version.restored` | A DISCARDED pipeline version was restored to RELEASED. `details` carries the pointer before/after — restore moving the pointer is the interesting case ([Versioning §3.1](versioning.md#31-statuses-and-verbs)) |
| `pipeline.version.purged` | A DRAFT pipeline version was purged — the row and its executions deleted, irreversible. `details` carries the execution-row count that went with it ([Versioning §3.1](versioning.md#31-statuses-and-verbs)) |
| `pipeline.purged` | A pipeline ENTITY was purged (only-draft, no inbound edges) — the entity row went with its draft. `details` carries `exclusive_draft_templates` offered/purged ([Versioning §3.2](versioning.md#32-entity-status-is-derived-names-are-unique-forever)) |
| `pipeline.current_switched` | The sticky pointer was moved by the manual switch verb (`POST /pipelines/{id}/current`) — including on a promotion receiver, where this is the rollout/rollback lever. `details` carries from/to versions ([Versioning §3.4](versioning.md#34-current_version-is-sticky-and-event-driven-d60)) |
| `pipeline.version.released` | A DRAFT pipeline version was RELEASED — the D4 human step, on every surface that offers it (REST `POST /pipelines/{id}/release`, the explorer dialog, the editor). `details` carries `pipeline_id`, `pipeline_name`, `version`, and `via` (`session` or `api_key`; `key_id` is on the row) — the record of WHO released and THROUGH WHAT ([Versioning D4](versioning.md#2-decision-log)). Added at T187 (2026-09-10): releases were the one lifecycle verb 101 left unaudited. |
| `template.version.released` | The template twin of `pipeline.version.released`: `template_id`, `version`, `via`. |
| `template.version.discarded` / `template.version.restored` / `template.version.purged` / `template.purged` / `template.current_switched` | The template twins, by name — same triggers, template surfaces ([Versioning §3.5's notation rule](versioning.md#35-the-lifecycle-table)) |

**Learned-semantics audit events** (same `audit_log` table, defined in the [learned-semantic-layer design record](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) §9; emitted by `SemanticsService` for the `semantics_record` / `semantics_retire` MCP tools — 118):

| Value | Trigger |
|---|---|
| `semantics.recorded` | One learned fact was recorded. `details` carries `fact_id`, `kind`, `scope`, `datasource`, `refs` (as `table.column` keys), `trust`, `via` (`mcp` \| `session` \| `api_key`), `evidence` (whether a probe backed it), and `supersedes` / `source_pipeline_id` when present — never the fact text or the evidence SQL. The row the §9 acceptance counts ("facts recorded per session") |
| `semantics.retired` | One learned fact was retired (`semantics_retire`). `details` carries `fact_id`, `kind`, `scope`, `datasource`, `reason`, and `recorded_in_this_workspace` — false when a workspace admin retired a DATASOURCE fact another workspace established |

---

## 16. Error Code Domains (prefix catalog)

**Source:** [Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog) — the ONLY catalog of concrete error codes. This section registers domains; deliberately no code list here, so there is exactly one place a code can drift from.
**Used by:** every spec that defines error codes.

Error codes follow `{domain}.{entity}.{failure}` — three segments, all lowercase snake_case, dot-separated, ASCII. Two-segment codes exist only where the domain has no entity dimension (`datasource.in_use`, `datasource.driver_not_loaded`, `datasource.not_found`, `datasource.lease_in_transaction`, `template.not_found`, `rate_limit.exceeded`, `rate_limit.unavailable`, and every `semantics.*` code — a learned fact has no sub-entity). Additive-only — never reused, never renamed.

| Domain | Description | Catalog section |
|---|---|---|
| `pipeline.validation.*` | Pipeline JSON validation failures (write-time) | pipeline-contract §13.1 |
| `pipeline.import.*` | Pipeline import failures | pipeline-contract §13.2 |
| `pipeline.execution.*` | Pipeline execution failures (run-time) | pipeline-contract §13.3 |
| `pipeline.node.*` | Individual node execution failures | pipeline-contract §13.4 |
| `pipeline.staging.*` | Tempdb / staging failures | pipeline-contract §13.5 |
| `type_mapping.*` | Type mapping warnings (not errors — in response `warnings` array) | pipeline-contract §13.6 |
| `auth.api_key.*`, `auth.scope.*`, `auth.session.*`, `auth.login.*`, `auth.csrf.*`, `auth.password.*` | Authentication / authorization errors | pipeline-contract §13.7 (defined in [Auth §9](auth.md#9-auth-errors)) |
| `datasource.*` (incl. `datasource.validation.*`) | Datasource CRUD, validation, driver availability | pipeline-contract §13.8 (defined in [Datasources §9](datasources.md#9-validation-rules)) |
| `template.*` (incl. `template.validation.*`) | Template CRUD, validation failures (incl. import cycles: `template.validation.import_cycle`) | pipeline-contract §13.9 (defined in [Templates §7](templates.md#7-validation-rules)) |
| `result.*` | Result cursor retrieval failures | pipeline-contract §13.10 (defined in [REST API §7](rest-api.md#7-result-delivery)) |
| `rate_limit.exceeded` | Rate limit hit (single code for all layers) | pipeline-contract §13.11 |
| `rate_limit.unavailable` | The limiter could not decide; the request is refused (fail closed) | pipeline-contract §13.11 |
| `idempotency.*` | Idempotency-key conflicts | pipeline-contract §13.11 |
| `workspace.*` | Workspace resolution, membership and provisioning refusals | pipeline-contract §13.12 (defined in [Auth §5](auth.md#5-oidc-login-flow)) |
| `pipeline.version.*`, `pipeline.release.*`, `pipeline.promotion.*` | Draft/release version lifecycle and environment promotion | pipeline-contract §13.13 (defined in [Versioning](versioning.md)) |
| `template.version.*` | Template draft/release lifecycle | pipeline-contract §13.9 (defined in [Versioning](versioning.md)) |
| `semantics.*` | The learned semantic layer: recording, evidence, duplicate and drift refusals | pipeline-contract §13.15 (defined in the [learned-semantic-layer design record](superpowers/specs/2026-09-11-learned-semantic-layer-design.md)) |

**Removed 2026-08-07** (D5): the `auth.rate_limit.*` domain (folded into `rate_limit.exceeded`), the `template.import.*` domain (folded into `template.validation.*`), the `idempotency_key.*` spelling (now `idempotency.*`), and `result.claim_check_expired` (now `result.expired` under the D9 result model).

---

## 17. HTTP Status Code Conventions

**Source:** [REST API §2](rest-api.md#2-design-principles)
**Used by:** every spec that defines HTTP behavior.

| Code | Meaning | When used |
|---|---|---|
| `200 OK` | Success (synchronous); SSE stream established | GET, PUT (update), successful POST |
| `201 Created` | Resource created | POST that creates a new entity |
| `204 No Content` | Success, no body | DELETE |
| `400 Bad Request` | Client-side validation failure | All `pipeline.validation.*`, `template.validation.*`, `datasource.validation.*`, `result.format_unsupported` |
| `401 Unauthorized` | Auth missing or invalid | `auth.api_key.missing`, `auth.api_key.invalid`, `auth.session.*` |
| `403 Forbidden` | Auth valid but insufficient scope | `auth.scope.insufficient`, `auth.csrf.*` |
| `404 Not Found` | Resource doesn't exist | `pipeline.execution.not_found`, `result.execution_not_found`, etc. |
| `409 Conflict` | State conflict | `pipeline.import.version_conflict`, `result.execution_incomplete`, `idempotency.key_reused_for_different_request` |
| `410 Gone` | Resource expired / terminally unavailable | `result.expired`, `result.execution_failed` |
| `429 Too Many Requests` | Rate limited | `rate_limit.exceeded`, `rate_limit.unavailable`, `pipeline.execution.concurrency_limit` |
| `500 Internal Server Error` | Server error | Uncaught exceptions, `pipeline.staging.*`, `result.storage_unavailable` |
| `502 Bad Gateway` | Upstream failure | `pipeline.node.datasource_connection_failed`, `pipeline.node.query_execution_failed` |
| `503 Service Unavailable` | Service not ready | Readiness check failure |
| `504 Gateway Timeout` | Execution timeout | `pipeline.execution.timeout`, `pipeline.node.query_timeout` |

---

## 18. `ExecutionTrigger` — how execution was initiated

**Source:** [REST API §10.2](rest-api.md#102-get-execution-metadata)
**Used by:** rest-api, mcp-server, persistence.

| Value | Description |
|---|---|
| `UI` | User clicked "Run" in the pipeline editor |
| `REST` | Direct REST API call (programmatic client) |
| `MCP` | MCP tool invocation (agent) |
| `PIPELINE` | Spawned by a parent execution's PIPELINE node (pipeline composition; metadata-db §4.6 lineage columns link the family) |
| `ENDPOINT` | A published endpoint served a `GET /api/x/…` request (074). The execution runs in-process as the endpoint's workspace, `triggered_by` is the key's owner, and the serve's audit row carries the key id |
| `SCHEDULED` | (Future) Cron-triggered execution |
| `WEBHOOK` | (Future) External webhook trigger |

> **Declaration reality (2026-08-10).** Declared in the `dag` module, for the same layering reason as `ExecutionStatus` (§10) and `SseEventType` (§11): the executor owns the execution repository that persists `pipeline_executions.trigger`, and it sits below `web`. This document, [rest-api](rest-api.md) and [metadata-db](metadata-db.md) remain the wire authorities.

---

## Cross-Reference: Where Each Enum Is Authored

| Enum | Authoring spec | Consuming specs |
|---|---|---|
| `LogicalType` | type-system | pipeline-contract, templates, dag-executor, staging, mcp-server |
| `NodeType` | pipeline-contract | dag-executor |
| `OutputTarget` | pipeline-contract | dag-executor, staging |
| `WriteMode` | pipeline-contract | dag-executor |
| `Dialect` | type-system | datasources, templates, pipeline-contract, mcp-server |
| `CredentialKind` | [datasources.md §3.4](datasources.md#34-credential-kinds) | metadata-db, rest-api, mcp-server |
| `TemplateEngine` | templates | templates |
| `TemplateType` | template-hierarchy-design | templates, pipeline-contract |
| `StagingEngine` | pipeline-contract | staging, dag-executor |
| `Scope` | auth | every endpoint (API keys only since RBAC round 1) |
| `Capability` | [auth.md §11A](auth.md#11a-roles) | auth, every endpoint and MCP tool |
| `NodeStatus` | dag-executor | rest-api, mcp-server |
| `ExecutionStatus` | rest-api | dag-executor, mcp-server, persistence |
| `SseEventType` | rest-api | dag-executor, mcp-server |
| `ResultFormat` | rest-api | mcp-server |
| `SslMode` | datasources | datasources |
| `AuthAuditEvent` | auth (+ datasource/mcp event tables in §15) | observability |
| `ExecutionTrigger` | rest-api | mcp-server, persistence |
| `ApiKeyKind` | [auth.md §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings) | metadata-db, rest-api, mcp-server |

---

## Validation Discipline

When a spec or code change introduces or renames an enum value:

1. **Update this document first.** This is the source of truth.
2. **Search all specs** for the old spelling — `grep` for the value across `docs/*.md`.
3. **Search the codebase** — `grep` for the value across `modules/**/*.kt`.
4. **Bump the spec's `schema_version`** if the change is non-additive (per the spec's stability promise).
5. **Document the change** in the spec's Change Log appendix and in [ROADMAP](ROADMAP.md) if it was previously tracked there.
6. **Run `scripts/docs-audit.sh`** — it mechanically enforces steps 2's doc sweep (cross-references, error codes, config keys, forbidden legacy spellings) and must exit 0 before the change lands.

This document itself is **additive-only** — values are never removed (only marked deprecated). The authoring spec governs its own stability promise; this doc tracks usage.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-09 | v1.9 | T202 node query timeout | §17's 504 row gains `pipeline.node.query_timeout`. |
| 2026-09-08 | v1.8 | 091 keys | §8A `ApiKeyKind` gains **`server`** — the promotion peer's credential as a stored key (auth.md §7.7, V15). Three kinds now, and the note that a scopeless kind is refused everywhere off its own family, `/mcp` and the UI pages included. |
| 2026-09-02 | v1.7 | 046 typed templates | New §6A `TemplateType` (`sql` \| `html`, template-hierarchy-design §5) beside `TemplateEngine` — a template's kind, chosen at create and immutable across versions; the cross-reference table gains its row. |
| 2026-08-05 | v1.0 | initial draft | Initial enums reference: 18 enum categories cataloged, cross-reference table, validation discipline |
| 2026-08-07 | v1.1 | consistency campaign | Case/serialization convention added; `OutputTarget` default → `caller` (D1); `ResultDelivery` removed (D9); `execution_aborted` SSE event added (D7); `AuthAuditEvent` synced to auth §10.1 (no password/lockout events); §16 reduced to domain registry pointing at the single concrete catalog (pipeline-contract §13), D5 renames applied; single authority per enum; broken source links fixed. See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-11 | v1.2 | gate C review | §16: registered `template.not_found` / `datasource.not_found` as two-segment codes (read/mutate-path misses; pipeline-contract §13 v1.3); template domain row widened to `template.*`. |
| 2026-08-16 | v1.3 | pipeline composition | §18 `ExecutionTrigger` gains `PIPELINE` — a child execution spawned by a parent's PIPELINE node (V3 migration widens `chk_triggered_via` to match). |
| 2026-08-17 | v1.4 | pipeline composition | §2 `NodeType` gains `PIPELINE` — a node that executes a version-pinned pipeline as a child execution (pipeline-contract §4.9/§8.5; guarded by the new `NodeTypeSpecDriftTest` in pipeline-contract). |
| 2026-08-31 | v1.6 | 026 post-merge follow-up | §15 registers `auth.password.change_failed` / `auth.password.change_locked` — the self-service change path's failure and lockout events added by the session-only credential fix (22be7b2) after the v1.5 sync. Unregistered, docs-audit check C flagged them at auth.md:487; they are audit events, not pipeline-contract error codes. |
| 2026-08-30 | v1.5 | local password auth | §15 `AuthAuditEvent` gains the local-account events: `auth.login.bad_credentials`, `auth.login.locked`, `auth.password.{seeded,changed,reset,disabled}`, `auth.user.{created,unlocked}`; `auth.login.success`/`user_inactive` re-described as shared OIDC/local. The "no password or lockout events" note is replaced — they exist for the optional local accounts only (auth.md §5A). |
| 2026-09-02 | v1.7 | MCP audit (052) | §15 gains the **MCP audit events** table: `mcp.tool.called` (registered here for the first time — the dispatcher has emitted it since the original mcp-server build) and `mcp.tool.write` (new, 052/R4: one event per mutating tool call, node runs included). Authority for both: MCP §14; same `audit_log` sink as the auth/datasource events. Cross-reference row widened to name the §15 sub-tables. |
| 2026-09-08 | v1.8 | 101 version lifecycle | §15 gains the **version lifecycle audit events** table: `pipeline.version.discarded`/`restored`/`purged`, `pipeline.purged`, `pipeline.current_switched`, and the template twins — the first lifecycle audit events anywhere (release and draft writes were previously unaudited). Authority: Versioning §3/§7; emitted by the 101 REST verbs, all session-only. `scripts/docs-audit.sh`'s §15 event extraction widened to the `pipeline`/`template` domains (the endpoint/074 and mcp/052 precedent) so these are recognised as events, not demanded as §13 error codes. |
| 2026-09-07 | v1.5 | 087 connector seams | New **§5A `CredentialKind`** (`password` \| `token` \| `private_key` \| `service_account_json` \| `none`) — what a datasource's stored credential IS, authored by [Datasources §3.4](datasources.md#34-credential-kinds); the cross-reference table gains its row. §5 `Dialect` gains **`LAKE`** (object storage read in place; DuckDB is the engine, with a different §5.6 posture from `DUCKDB`) — not a reserved value: it ships with an adapter, a driver mapping and a CHECK. |
| 2026-09-10 | v1.9 | 112 RBAC round 1 | New **§8B `Capability`** — the ROLE axis, carried by the workspace MEMBERSHIP rather than by a credential (D-R1), and deliberately not a hierarchy: an author may not `release` and a promoter may not author, so each value is a predicate over the membership row, never an ordinal. §8 `Scope` gains an "issuable to a key?" column: `admin` left the key wire (O-2), and a session carries no scopes at all. §15 gains the round's audit events — `auth.super_admin_acting` (D-R8, emitted on READS too, because the 404 rule's promise is that a workspace is invisible from outside and the one principal exempt from it is the one whose reads most need recording), `workspace.member_added`/`removed`/`flags_changed`, `workspace.deactivated`/`reactivated`, `datasource.granted`/`revoked` — and loses `auth.workspace.provisioned` with the `auto-per-user` mode that emitted it. Cross-reference table gains the `Capability` row. |
| 2026-09-10 | v1.10 | 113 workspace invitations | §15 gains the invitation audit events — `workspace.member_invited` (also fired on every RE-INVITE, because the upsert is the latest admin decision winning and it is audited every time), `workspace.invitation_revoked`, and `workspace.invitation_materialised` (fired at login, carrying `inviter` — the actor whose decision the invitee's sign-in is executing). Authority: [Auth §4.6](auth.md#46-invitations); emitted by `WorkspaceService`. |
