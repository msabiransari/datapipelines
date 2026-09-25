# Enumerations Reference

**Status:** v1.18 (living document — updated as enums evolve)
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
| `CALCULATOR` | Evaluates a catalog calculator and writes ONE typed value — or, on a multi-output kind (121), a named set of them — into the execution Context. Carries `kind`, `inputs` and `context_key` (single) or `context_keys` (multi — never both, never neither), never `source`/`template`/`output` — it runs no SQL and produces no table ([Pipeline Contract §4.10](pipeline-contract.md#410-json-structure-calculator-node), [Calculators](calculators.md)). |
| `TRANSFORM` | Evaluates a pinned `jsonata`/`javascript` template as a pure function over staged data and the Context. Carries `template`, `inputs`, `output` (`row`/`table` modes) or `context_key` (`value` mode) and `strict`, never `source` — tempdb-only by construction ([Pipeline Contract §4.12](pipeline-contract.md#412-json-structure-transform-node), [DAG Executor §6.3](dag-executor.md#63-behavior-by-node-type)). |

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
| `freemarker` | Apache Freemarker template engine — the engine of `sql` and `html` templates. |
| `none` | No rendering engine — the engine of the transform types (`jsonata`, `javascript`), whose body is evaluated by the scripting engine seam as a pure function of its input (transform-nodes design §2.1, since 7b). The pairing is enforced both ways: `engine` must match the template's `type` (`template.validation.engine_unsupported`). |

**Reserved for future:** `pebble`, `handlebars`, `thymeleaf-sql` (see [ROADMAP](ROADMAP.md)).

---

## 6A. `TemplateType` — template kind

**Source:** [Template Hierarchy §5](template-hierarchy-design.md); the transform types: [transform-nodes design §2.1](superpowers/specs/2026-09-09-transform-nodes-design.md) (7b)
**Used by:** templates (engine-configuration dispatch, type/dialect consistency), pipeline-contract (reference legality).

| Value | Description |
|---|---|
| `sql` | The template renders SQL for pipeline nodes. Requires a `dialect`. Default, and the only kind that existed before 2026-09-02 (046) — every stored template backfilled to it. |
| `html` | The template renders HTML through a second, auto-escaping engine configuration (design §6). Declares no `dialect`; **no pipeline node may reference it** (`pipeline.validation.template_type_mismatch`). |
| `jsonata` | A transform: the body is one JSONata expression evaluated as a pure function of its input object (7b). `engine: none`, no `dialect`, no `imports`, `is_library` false; the version carries `contract` / `invariants` / `tests` blocks inside its `body_hash`. |
| `javascript` | A transform in JavaScript (GraalJS isolate) — the enum value, the CHECKs and the wire vocabulary are final, but **save is refused with `transform.js.unavailable` until round two's engine ships** (7b; §4.4 of the record). |

Fixed at template **create** and identical on every version of a template (`template.validation.type_immutable`). Serialization is the lowercase wire value (`"sql"` / `"html"` / `"jsonata"` / `"javascript"`), per the case convention above. There are no reserved future values — component sub-typing (kpi, aggrid, svg, form, …) belongs to the future dashboard abstraction and is deliberately absent here (design §2).

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

## 8. `UserKind` — what a `users` row is

**Source:** [Auth §4.7](auth.md#47-key-identities); [metadata-db §4.1](metadata-db.md#41-users) (`users.kind`, V34, CHECK `chk_users_kind`)
**Used by:** auth (`UserKind`, `User.isHuman`, `UserService`, `UserRepository`), the session filter, every user-administration route, `ActorNames` (history's "<key name> (API key)").

| Value | Description |
|---|---|
| `human` | A person — the only kind that can log in, be linked to an OIDC identity, hold a membership, be invited or be administered on the admin users page |
| `service` | An `endpoint` or `server` key's own identity (#215, PK5): provider `key`, email `<key id>@keys.invalid`, no password, never an admin. Created and deactivated with its key; managed only through it |
| `system` | The System service account (auth §4.5, R7) — the actor for writes no human made; nothing authenticates as it |

One predicate — `kind = 'human'` — guards every login, linking and administration path (auth §4.7). V34 backfilled `system` on the System row and `human` on every other; the wire and database token is the lowercase name.

(Until #215 slice (b) this section was `Scope`, the API-key credential axis; scopes were removed — see §8D.)

---

## 8B. `Permission` — an action a role may perform (was `Capability`)

**Source:** [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative) — **the catalog table is the single authority** (the value set, the roles each value admits, the surfaces placed on it); the [permissions and keys record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §2 (#215, ratified 2026-09-23) is its design.
**Used by:** auth (`Permission`, `RolePermissions`, `ScopeMatrix.allowed`, `AuthenticatedPrincipal.holds`), every REST handler (`@RequiredScope(Permission.X)`), every MCP tool (its catalog entry, `McpToolCatalog.Entry.permission`), and the service checks that ask for one (`execution.read_all`, `server_key.create`, …).

Since #215 slice (a) a permission is a **`<functionality>.<permission>` catalog value** — `pipeline.read`, `template.release`, `workspace.members.manage` — one per piece of functionality a person can take separately (PK1); 65 on this version. The wire form is the dotted token; the Kotlin constant is its upper-snake spelling (`PIPELINE_READ`). The seven coarse values of v1.16 (`view`, `execute`, `author`, `promotion_read`, `promote`, `ws_admin`, `super_admin`) are retired: every one maps onto the catalog values whose surfaces it covered, with each surface's roles unchanged. The values are not restated here — a second list is a second thing to keep true; read them in auth §7.6.

It is the ROLE axis. It travels with a **membership**, not with a credential: the same person is a viewer in one workspace and an author in another. **Not a hierarchy**: the roles are not a chain, so the ONE role table (`RolePermissions`) lists each role's permissions outright. A super admin (`users.is_admin`) holds every value in every workspace (D7) except the two **fenced** promotion-receiving values, which no member role holds — only the `promotion_receiver` key role (§8D) does. Code asks whether a principal holds a permission; it never compares role names (PK1).

**One axis since slice (b):** scopes were removed (#215, PK8), so a permission is held by ROLES only — the four member roles and super admin (the §7.6 member columns), and the two key roles (§8D).

---

## 8C. `WorkspaceRole` — the ONE role a membership holds

**Source:** [Auth §11A](auth.md#11a-roles); [metadata-db §4.12](metadata-db.md#412-workspace_members) (`workspace_members.role`, V29) and §4.17 (`workspace_invitations.role`)
**Used by:** auth (`WorkspaceRole`, the role table `RolePermissions`), the REST `role` field of the workspace surface (rest-api §17), the members dropdown (ui-screens §4.13).

Exactly one per membership (roles design D1, 2026-09-20). Super admin is NOT a value: it is `users.is_admin`, a property of the user, held in every workspace. The wire and database token is the lowercase name; the UI prints it with a space (`workspace admin`).

| Value | Description |
|---|---|
| `viewer` | Reads the workspace and executes pipelines (D3). Nothing else |
| `author` | Viewer + creates, edits, releases, switches, publishes, registers lake tables, records facts (D4, D8) |
| `promoter` | The ops role (D5): reads datasources and (R2) released-and-newer objects; promotes. Executes nothing, reads no executions, authors nothing, releases nothing |
| `workspace_admin` | Everything in the workspace, members and promotion included (D6) |

V23's three additive booleans (`author` / `promoter` / `admin`) were folded back into this value by V29 with the precedence `admin → workspace_admin, else promoter → promoter, else author → author, else viewer`; the database CHECK (`chk_workspace_member_role`, `chk_workspace_invitation_role`) keeps the set closed.

---

## 8A. `ApiKeyKind` — what an API key IS

**Source:** [Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)
**Used by:** auth, web, mcp-server, persistence (`api_keys.kind`).

| Value | Description |
|---|---|
| `mcp` (V35, renamed from `user` — A19; kind is the transport and the word says which) | The agent's key: CREATED on the Keys page (A15 — the login mint is retired) by someone holding `mcp_key.create`, with the role chosen at creation under the subset rule (A14); acts as its own `service` identity holding that member role (A13); presented on `/mcp` and nowhere else (#215 B2) |
| `endpoint` | A credential for published endpoints only: acts as its own identity with the `api_caller` role (§8D), workspace-pinned, and serves exactly the endpoints its bindings cover plus the metadata and result cursor of executions it started |
| `server` | The promotion peer's credential (091): created by a super admin, presented as `DP-Promotion-Key` by a SENDING deployment, and accepted on the promotion receiver's routes and nowhere else. Acts as its own identity with the `promotion_receiver` role (§8D), for any workspace (B6) |

> A kind answers "where may this credential be presented?"; its ROLE (§8D, or the chosen member role for the `mcp` key) answers "what may it do there?". Each kind carries exactly one role — the database CHECK (`chk_api_keys_role`) makes it a fact. The wire form is the lowercase name.

> **A `server` key authenticates nothing outside the promotion routes.** Presented as an ordinary `DP-API-Key` it is refused on every route — REST, htmx partials, `/mcp` and every UI page — with `endpoint.key_kind_refused`. Same rule as the endpoint kind, different family.

> **An endpoint key with no binding on any ancestor of the path it presents at authorises nothing.** The absence of a binding is never a fall-through (since #215 B3 an unbound path serves no key at all); if it were, publishing a new endpoint would silently widen every existing endpoint key's reach at the moment of publication.

---

## 8D. `KeyRole` — the role a key carries

**Source:** [Auth §7.5](auth.md#75-key-roles) and the key-role columns of the §7.6 catalog; the [permissions and keys record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §3.2
**Used by:** auth (`KeyRole`, `RolePermissions.of(KeyRole)`, `AuthenticatedPrincipal.keyRole`), persistence (`api_keys.role`, V34, CHECK `chk_api_keys_role`), the REST `role` field of key creation (rest-api §16.1), the Keys page's Role column.

| Value | Carried by | Permissions |
|---|---|---|
| `author` | an `mcp` key CHOSEN at creation (A13/A14 — the subset rule decides which member roles a creator may offer) | the §7.6 `mcp:author` column — the author member's permissions, exactly |
| `promoter` | an `mcp` key CHOSEN at creation | the §7.6 `mcp:promoter` column — the promoter member's permissions, exactly |
| `workspace_admin` | an `mcp` key CHOSEN at creation | the §7.6 `mcp:workspace_admin` column — the workspace-admin member's permissions, exactly |
| `api_caller` | every `endpoint` key | `endpoint.serve` (the paths bound to the key), `execution.read` and `execution.result.read` (the executions it started) |
| `promotion_receiver` | every `server` key (and the deprecated config-value peer) | `promotion.inventory.read`, `promotion.push` — for any workspace (B6) |

Pre-created and fixed: the three member roles are the ONLY roles an `mcp` key can carry (A15 — viewer is never a key role; B1 — `super_admin` is neither offerable nor acceptable), and no key role holds workspace admin or super admin AUTHORITY over people (PK3). `snake_case` everywhere (A5); the UI prints it with a space (`api caller`, `workspace admin`).

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
| `node_progress` | A measured sample of the node's operation — state, destination, cumulative counts, per-state wall time ([REST API §6.4.9](rest-api.md#649-node_progress)) | Zero or more, strictly between the node's `node_started` and its `node_completed`/`node_failed`; never terminal |
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
| `auth.login.email_verified_assumed` | OIDC login accepted WITHOUT an `email_verified` claim under the provider's `trust-email-without-verified-claim` knob — the acceptance was configuration's decision, not the IdP's vouching (#187, [Auth §4.2](auth.md#42-user-provisioning)) |
| `auth.login.identity_mismatch` | OIDC login refused: the email's stored identity belongs to a different sign-in — nothing was updated; `details` name the two PROVIDERS, never the subjects (#187, [Auth §4.2](auth.md#42-user-provisioning)) |
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
| `auth.api_key.revoked_by_admin` | A workspace admin or super admin revoked a member's login-minted key — with the membership's removal (`reason: member_removed`) or as an explicit act that keeps the member (`reason: admin_revoked`; the member's session keeps working, the next sign-in mints fresh) (#200, [Auth §7.4](auth.md#74-issuance)) |
| `auth.api_key.used` | API key validated (sampled 1/100) |
| `auth.api_key.rejected` | API key validation failed |
| `auth.scope.denied` | Request refused by the authorization layer — a role that lacks the permission, an undeclared handler, or a key off its kind's surface (the event keeps its historical name) |
| `auth.user.deactivated` | Admin deactivated a user |
| `auth.user.activated` | Admin reactivated a user |
| `auth.user.admin_granted` | Admin granted admin scope to user |
| `auth.user.admin_revoked` | Admin revoked admin scope from user |
| `auth.user.identity_reset` | Admin reset a user's linked sign-in identity to the bootstrap placeholder so the next OIDC sign-in with that email claims the row — the explicit answer to `auth.login.identity_mismatch` (#187, [Auth §4.2](auth.md#42-user-provisioning)) |
| `auth.workspace.created` | Workspace created — by a super admin through the service path, or by the boot seeder for `demo` (details carry `actor: system`) |
| `auth.workspace.updated` | A workspace's display name was changed |
| `auth.workspace.deleted` | A workspace was soft-deleted (empty only) — distinct from `workspace.deactivated`, which purges nothing |
| `auth.workspace.stranded_content` | Content committed into a workspace concurrently with its deletion and is now invisible with its name held — the detector, not a refusal |
| `auth.workspace.header_rejected` | `DP-Workspace` presented on an API-key request |
| `auth.super_admin_acting` | A super admin acted in a workspace they hold no explicit membership in (RBAC design D-R8). Emitted at the scope interceptor's one choke point on every governed handler, READS INCLUDED — the 404 rule's whole promise is that a workspace is invisible from outside, and the one principal exempt from it is the one whose reads most need to be on the record. `details` carry the operation, the workspace, the path and `acting_via: super_admin` |
| `workspace.member_added` | A member was added, with their role (`details.role`). The first-login demo join carries `reason: first_login_demo_viewer` |
| `workspace.member_invited` | An invitation was created — or an existing one's role replaced by a re-invite (the latest admin decision wins, audited every time; [Auth §4.6](auth.md#46-invitations)). `details` carry workspace, email, role |
| `workspace.invitation_revoked` | A pending invitation was revoked ([Auth §4.6](auth.md#46-invitations)). `details` carry workspace and email |
| `workspace.invitation_materialised` | A pending invitation became a real membership at login — the inviter's decision being executed by the invitee's first sign-in ([Auth §4.6](auth.md#46-invitations)). `details` carry workspace, email, role and `inviter` |
| `workspace.member_removed` | A member was removed |
| `workspace.member_role_changed` | A member's role was replaced (D1, 2026-09-20; was `workspace.member_flags_changed`) — `details.from` and `details.to` carry both roles, because a membership row keeps no history of its own |
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
| `mcp.resource.read` | Every `resources/read`, success or failure (120) — emitted at `McpResourceReader.read`, the one place every read passes through. `details` carries `uri` (never the content), `outcome`, the failure's `code` as a name (`resource_not_found` \| `forbidden` \| `internal_error`), `elapsed_ms` and the correlation id ([MCP §14](mcp-server.md#14-audit)) |

**Version lifecycle audit events** (same `audit_log` table, defined in [Versioning §3](versioning.md#3-version-lifecycle); emitted by the 101 REST verbs, all session-only — an API key cannot produce one):

| Value | Trigger |
|---|---|
| `pipeline.version.discarded` | A RELEASED pipeline version was discarded (`POST /pipelines/{id}/versions/{v}/discard`). `details` carries the pipeline id, version, and the pointer before/after ([Versioning §3.1](versioning.md#31-statuses-and-verbs)) |
| `pipeline.version.restored` | A DISCARDED pipeline version was restored to RELEASED. `details` carries the pointer before/after — restore moving the pointer is the interesting case ([Versioning §3.1](versioning.md#31-statuses-and-verbs)) |
| `pipeline.version.purged` | A DRAFT pipeline version was purged — the row and its executions deleted, irreversible. `details` carries the execution-row count that went with it ([Versioning §3.1](versioning.md#31-statuses-and-verbs)) |
| `pipeline.purged` | A pipeline ENTITY was purged (only-draft, no inbound edges) — the entity row went with its draft. `details` carries `exclusive_draft_templates` offered/purged ([Versioning §3.2](versioning.md#32-entity-status-is-derived-names-are-unique-forever)) |
| `pipeline.current_switched` | The sticky pointer was moved by the manual switch verb (`POST /pipelines/{id}/current`) — including on a promotion receiver, where this is the rollout/rollback lever. `details` carries from/to versions ([Versioning §3.4](versioning.md#34-current_version-is-sticky-and-event-driven-d60)) |
| `pipeline.version.released` | A DRAFT pipeline version was RELEASED — the D4 human step, on every surface that offers it (REST `POST /pipelines/{id}/release`, the explorer dialog, the editor). `details` carries `pipeline_id`, `pipeline_name`, `version`, and `via` (`session` or `api_key`; `key_id` is on the row) — the record of WHO released and THROUGH WHAT ([Versioning D4](versioning.md#2-decision-log)). Added at T187 (2026-09-10): releases were the one lifecycle verb 101 left unaudited. Since 140, a release past failing checks also carries `checks_overridden: [check ids]` and `override_reason` — the escape hatch is on the record, never silent. Since 142 it always carries `templates_released: [{template_id, version}]` — the DRAFT template versions the release cascaded to with the promoter's consent (versioning §5.3), an empty list when none |
| `template.version.released` | The template twin of `pipeline.version.released`: `template_id`, `version`, `via`. Since 142 a release the PIPELINE cascade made (versioning §5.3) carries the same shape plus `cascade_from_pipeline_id` and `cascade_from_version` — "who released template X v2 and why" reads off this one event. |
| `template.version.discarded` / `template.version.restored` / `template.version.purged` / `template.purged` / `template.current_switched` | The template twins, by name — same triggers, template surfaces ([Versioning §3.5's notation rule](versioning.md#35-the-lifecycle-table)) |

**Learned-semantics audit events** (same `audit_log` table, defined in the [learned-semantic-layer design record](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) §9; emitted by `SemanticsService` for the `semantics_record` / `semantics_retire` MCP tools — 118):

| Value | Trigger |
|---|---|
| `semantics.recorded` | One learned fact was recorded. `details` carries `fact_id`, `kind`, `scope`, `datasource`, `refs` (as `table.column` keys), `trust`, `via` (`mcp` \| `session` \| `api_key`), `evidence` (whether a probe backed it), and `supersedes` / `source_pipeline_id` when present — never the fact text or the evidence SQL. The row the §9 acceptance counts ("facts recorded per session") |
| `semantics.retired` | One learned fact was retired (`semantics_retire`). `details` carries `fact_id`, `kind`, `scope`, `datasource`, `reason`, and `recorded_in_this_workspace` — false when a workspace admin retired a DATASOURCE fact another workspace established |

**Mail audit events** (same `audit_log` table, defined in [Auth §5A.8](auth.md#5a8-mail-the-welcome-mail-and-the-new-user-notice); emitted by `MailNotifier` off the request thread, after the transport answered — 137):

| Value | Trigger |
|---|---|
| `mail.sent` | The transport accepted a notice — the welcome / password-reset mail to a user or the "New user" notice to sys-ops. `details` carries `kind` (`welcome` \| `password_reset` \| `new_user`), `to`, `act_id` (the `mail_sends` claim's act) and `message_id` (the `Message-ID` it went out under). NEVER the body, NEVER the one-time password — the password exists in exactly one place, the message body handed to the transport |
| `mail.failed` | The transport refused or failed a notice. `details` carries `kind`, `to`, `act_id` and `error` (the exception's class and message — a relay's refusal line, never a body). The `mail_sends` row carries the same error; the admin screen shows it |

---

## 16. Error Code Domains (prefix catalog)

**Source:** [Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog) — the ONLY catalog of concrete error codes. This section registers domains; deliberately no code list here, so there is exactly one place a code can drift from.
**Used by:** every spec that defines error codes.

Error codes follow `{domain}.{entity}.{failure}` — three segments, all lowercase snake_case, dot-separated, ASCII. Two-segment codes exist only where the domain has no entity dimension (`datasource.in_use`, `datasource.driver_not_loaded`, `datasource.not_found`, `datasource.lease_in_transaction`, `datasource.table_not_found`, `datasource.table_forbidden`, `template.not_found`, `rate_limit.exceeded`, `rate_limit.unavailable`, every `semantics.*` code — a learned fact has no sub-entity — and `mcp.doc_not_found`). Additive-only — never reused, never renamed.

| Domain | Description | Catalog section |
|---|---|---|
| `pipeline.validation.*` | Pipeline JSON validation failures (write-time) | pipeline-contract §13.1 |
| `pipeline.import.*` | Pipeline import failures | pipeline-contract §13.2 |
| `pipeline.execution.*` | Pipeline execution failures (run-time) | pipeline-contract §13.3 |
| `pipeline.node.*` | Individual node execution failures | pipeline-contract §13.4 |
| `pipeline.staging.*` | Tempdb / staging failures | pipeline-contract §13.5 |
| `type_mapping.*` | Type mapping warnings (not errors — in response `warnings` array) | pipeline-contract §13.6 |
| `auth.api_key.*`, `auth.permission.*`, `auth.session.*`, `auth.login.*`, `auth.csrf.*`, `auth.password.*` | Authentication / authorization errors | pipeline-contract §13.7 (defined in [Auth §9](auth.md#9-auth-errors)) |
| `datasource.*` (incl. `datasource.validation.*`) | Datasource CRUD, validation, driver availability | pipeline-contract §13.8 (defined in [Datasources §9](datasources.md#9-validation-rules)) |
| `template.*` (incl. `template.validation.*`) | Template CRUD, validation failures (incl. import cycles: `template.validation.import_cycle`) | pipeline-contract §13.9 (defined in [Templates §7](templates.md#7-validation-rules)) |
| `result.*` | Result cursor retrieval failures | pipeline-contract §13.10 (defined in [REST API §7](rest-api.md#7-result-delivery)) |
| `rate_limit.exceeded` | Rate limit hit (single code for all layers) | pipeline-contract §13.11 |
| `rate_limit.unavailable` | The limiter could not decide; the request is refused (fail closed) | pipeline-contract §13.11 |
| `idempotency.*` | Idempotency-key conflicts | pipeline-contract §13.11 |
| `workspace.*` | Workspace resolution, membership and provisioning refusals | pipeline-contract §13.12 (defined in [Auth §5](auth.md#5-oidc-login-flow)) |
| `pipeline.version.*`, `pipeline.release.*`, `pipeline.promotion.*` | Draft/release version lifecycle and environment promotion | pipeline-contract §13.13 (defined in [Versioning](versioning.md)) |
| `pipeline.check.*` | Release checks — the server-run cross-checks gating release | pipeline-contract §13.17 |
| `pipeline.transform.*` | TRANSFORM node execution-time refusals (input contract, pool, gate, invariants, strict) | pipeline-contract §13.18 |
| `template.version.*` | Template draft/release lifecycle | pipeline-contract §13.9 (defined in [Versioning](versioning.md)) |
| `semantics.*` | The learned semantic layer: recording, evidence, duplicate and drift refusals | pipeline-contract §13.15 (defined in the [learned-semantic-layer design record](superpowers/specs/2026-09-11-learned-semantic-layer-design.md)) |
| `mcp.*` | The MCP surface's own refusals (the resource surface's not-found is the JSON-RPC protocol's, not a code) | pipeline-contract §13.16 (defined in [MCP §6.2](mcp-server.md#62-tool-definitions)) |

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
| `403 Forbidden` | Auth valid but the role does not hold the permission, or the route declares none | `auth.role_required`, `auth.permission.undeclared`, `auth.csrf.*` |
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
| `ENDPOINT` | A published endpoint served a `GET` request on the published tree (074; re-rooted to `/api/<category>/<version>/<path…>` by 172). The execution runs in-process as the endpoint's workspace, `executed_by` is the key's owner with `executed_by_key_kind = endpoint` (§18A — the run lists for admins only, D11), and the serve's audit row carries the key id |
| `SCHEDULED` | (Future) Cron-triggered execution |
| `WEBHOOK` | (Future) External webhook trigger |

> **Declaration reality (2026-08-10).** Declared in the `dag` module, for the same layering reason as `ExecutionStatus` (§10) and `SseEventType` (§11): the executor owns the execution repository that persists `pipeline_executions.trigger`, and it sits below `web`. This document, [rest-api](rest-api.md) and [metadata-db](metadata-db.md) remain the wire authorities.

---

---

## 18A. `ExecutedByKeyKind` — which kind of credential started an execution

**Source:** [metadata-db §4.6](metadata-db.md#46-pipeline_executions) (`pipeline_executions.executed_by_key_kind`, V30), [REST API §10.2](rest-api.md#102-get-execution-metadata)
**Used by:** dag (`ExecutedByKeyKind`, the own-runs predicate), rest-api, mcp-server.

Roles design D11 (2026-09-20). `executed_by` names the user a run belongs to — the session's user, or a key's OWNER. This value says which KIND of credential started it when a key did; **null means a signed-in session**. The values mirror [`ApiKeyKind`](#8a-apikeykind--what-an-api-key-is)'s wire names, but the enum is `dag`'s own: the executor does not depend on auth and does not care what a key IS, only how the run is attributed.

| Value | Description |
|---|---|
| `user` | A user API key (REST or MCP): the run is the key owner's, listed as their own |
| `endpoint` | A published endpoint's key. `executed_by` still names the key's owner (the concurrency slot and the audit trail need a user), but the run is NOT that person's: the own-runs filter excludes it, so it lists for workspace admins only — and for the endpoint key itself through the serve audit row (auth.md §7.7) |
| `server` | Reserved for the promotion peer's credential; nothing writes it today |

The CHECK (`chk_executions_executed_by_key_kind`) admits these three and NULL. V30 backfilled `ENDPOINT`-triggered rows to `endpoint` and `MCP` rows to `user`; REST and UI rows stayed NULL (a REST run may have been a key or a cookie, and NULL reads as "a person's run", which is what those rows meant).

---

## 19. `LearnedFactKind` — what a learned fact is about

**Source:** [Learned semantic layer design §4](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) (the ruling); the `chk_learned_facts_kind` CHECK of [Metadata DB §4.18](metadata-db.md#418-learned_facts) is the enforcing copy.
**Used by:** datasources (`LearnedFactKind`, the recorder), mcp-server (`semantics_record`'s `kind` enum, derived), the introspection enrichment, the UI.

**Closed.** No `type`, `nullable`, `key`, `comment`, `partition` or `row_count` kind exists and none will (D-S2: nothing JDBC metadata already provides is stored — introspection stays the source for those and is what facts are checked against). Each kind belongs to exactly one scope; the database states the same rule in `chk_learned_facts_kind_scope`. Three sources — this table, the CHECK's list, the Kotlin enum — are held to one truth by `LearnedFactKindSpecDriftTest`.

| Value | Scope | Meaning | Example fact |
|---|---|---|---|
| `unit` | `DATASOURCE` | The unit of a numeric column | "reading is in the standard unit named by `unit` (°C, mm, m/s) — never tenths" |
| `time_zone` | `DATASOURCE` | What a timestamp's wall-clock means | "occurred_at is naive local time (Europe/Berlin); no UTC offset" |
| `sampling` | `DATASOURCE` | The population relation of a table | "1-in-16 deterministic hash sample of all events; multiply counts by 16 to estimate" |
| `grain` | `DATASOURCE` | One row is one what | "one row per (sensor, day, element)" |
| `window` | `DATASOURCE` | The data's coverage in time | "2024-01-01 → 2025-12-31 inclusive" |
| `enum_meaning` | `DATASOURCE` | What a coded value means | "status 3 = shipped, 4 = returned" |
| `join` | `DATASOURCE` | How two tables relate | "orders.customer_id → customers.id, many-to-one" |
| `caveat` | `DATASOURCE` | A trap | "cal_date is ISO-8601 TEXT — cast to DATE before joining a DATE column" |
| `format` | `DATASOURCE` | Encoding of a text column | "holiday_name is '' (never NULL) on a non-holiday" |
| `definition` | `WORKSPACE` | A business measure or entity | "revenue = SUM(amount); tips and tolls excluded" |
| `exclusion` | `WORKSPACE` | What a business question leaves out | "regions means the five named ones; drop 'Unknown' and 'N/A'" |
| `preference` | `WORKSPACE` | How this organisation wants a thing computed | "share comparisons within mode only — the events feed is a sample" |

**Trust** (the companion state, design §5 — not an enum a caller supplies): `asserted` (no evidence — or a `definition`/`exclusion`/`preference`, a choice, whatever it carries; C.2 2026-09-13) → `observed` (evidence ran at record time, data-kind facts only) → `verified` (a human confirmed); the mechanical demotions `needs_review` (the table's column set changed around a still-resolving ref) and `stale` (a referenced column or table no longer exists); and `retired` (explicit, with a reason — never a delete). Written as the `chk_learned_facts_trust` CHECK.

## 20. `CheckRunVerdict` — a release check's outcome (140)

**Source:** [pipeline-contract §13.17](pipeline-contract.md#1317-release-checks); the `chk_pipeline_check_runs_verdict` CHECK of [Metadata DB §4.20](metadata-db.md#420-pipeline_check_runs) is the enforcing copy; the Kotlin enum is `CheckRunVerdict` (pipeline-contract `ReleaseCheckGate.kt`).
**Used by:** pipeline-contract (the release gate), application (the check runner), mcp-server (`pipelines_run_checks`), rest-api (§5.16), the UI.

| Value | Meaning |
|---|---|
| `pass` | The server's observed value satisfied the check's expectation |
| `fail` | The run produced a value and it did NOT satisfy the expectation |
| `error` | No verdict could be formed — datasource unreachable, statement refused, or a result shape the expectation cannot compare (a `value`/`range` check requires exactly one row and one column). The run row's `message` says which. Never silently a `fail` |

**Closed.** A release refuses on `fail` OR `error` alike (versioning §5.3 precondition 4) — the distinction exists for the human reading the dialog, not for the gate.

---

## 21. `CheckRunVia` — who commissioned a check run (140)

**Source:** [pipeline-contract §13.17](pipeline-contract.md#1317-release-checks); the `chk_pipeline_check_runs_via` CHECK of [Metadata DB §4.20](metadata-db.md#420-pipeline_check_runs); the Kotlin enum is `CheckRunVia`.
**Used by:** application (the check runner), mcp-server, rest-api, the UI.

| Value | Meaning |
|---|---|
| `mcp` | The `pipelines_run_checks` tool |
| `rest` | `POST /pipelines/{id}/versions/{version}/checks/run` |
| `ui` | The release dialog / version page's run action |
| `release` | The release gate's own fresh run (versioning §5.3 precondition 4) |

**Closed.** The run rows are append-only; `via` is the record of WHICH surface commissioned each one.

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
| `UserKind` | [auth.md §4.7](auth.md#47-key-identities) | auth, metadata-db (the V34 CHECK) |
| `KeyRole` | [auth.md §7.5](auth.md#75-key-roles) | auth, metadata-db (the V34 CHECK), rest-api §16.1 |
| `Permission` | [auth.md §11A](auth.md#11a-roles) / §7.6 | auth, every endpoint and MCP tool |
| `WorkspaceRole` | [auth.md §11A](auth.md#11a-roles) | auth, metadata-db (the V29 CHECKs), rest-api §17, the members dropdown |
| `NodeStatus` | dag-executor | rest-api, mcp-server |
| `ExecutionStatus` | rest-api | dag-executor, mcp-server, persistence |
| `SseEventType` | rest-api | dag-executor, mcp-server |
| `ResultFormat` | rest-api | mcp-server |
| `SslMode` | datasources | datasources |
| `AuthAuditEvent` | auth (+ datasource/mcp event tables in §15) | observability |
| `ExecutionTrigger` | rest-api | mcp-server, persistence |
| `ExecutedByKeyKind` | metadata-db §4.6 (`dag` declares it; §18A here is the wire table) | rest-api, mcp-server, persistence |
| `ApiKeyKind` | [auth.md §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings) | metadata-db, rest-api, mcp-server |
| `LearnedFactKind` | [learned-semantic-layer design §4](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) (§19 here is the wire table) | datasources, metadata-db (the V25 CHECK), mcp-server, rest-api |
| `CheckRunVerdict` | pipeline-contract (`ReleaseCheckGate.kt`; §20 here is the wire table) | metadata-db (the V28 CHECK), application, mcp-server, rest-api, the UI |
| `CheckRunVia` | pipeline-contract (`ReleaseCheckGate.kt`; §21 here is the wire table) | metadata-db (the V28 CHECK), application, mcp-server, rest-api, the UI |

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
| 2026-09-24 | v1.18 | 215b (#215) key identities, key roles | **§8 `Scope` is replaced by §8 `UserKind`** (`human` / `service` / `system`, V34) and new **§8D `KeyRole`** (`api_caller`, `promotion_receiver`) — scopes were removed (PK8). §8A's kinds restated as where a credential may be presented, the role as what it may do; §8B is one axis. §15's `auth.scope.denied` keeps its name, now for every authorization refusal; §16/§17 name `auth.permission.undeclared`. Cross-reference rows for the two new enums. |
| 2026-09-24 | v1.17 | 215a (#215) the permission catalog | **§8B `Permission` is the catalog**: the seven coarse values (`view` … `super_admin`) are replaced by the 65 `<functionality>.<permission>` values of auth §7.6, which is now their single authority — this section points there instead of restating them. §8 and §8C name the new code homes (`RolePermissions`, the permission key-scope table). Status caught up with the change log (it read v1.15 while the rows had reached v1.16). |
| 2026-09-21 | v1.14 | 179 (#179) keys | §8A `user`: minted ONLY by the login/switch hook, one per user per workspace (D16) — the "default for any key minted without an explicit kind" sentence is gone with on-demand minting. No value added, removed or renamed; the wire set is unchanged |
| 2026-09-20 | v1.16 | 177 (#177) roles R1 | §8B `Capability` → **`Permission`** (D21), redefined as the role SETS of the ratified matrix: `switch` gone (release and switch are the author's, D8), **`promotion_read`** new (owner rule 13), `execute` no longer admits the promoter (D5), `ws_admin` gains the workspaces page (D13). New **§8C `WorkspaceRole`** — the ONE role a membership holds (V29; `viewer` \| `author` \| `promoter` \| `workspace_admin`). New **§18A `ExecutedByKeyKind`** (`user` \| `endpoint` \| `server`, V30) beside the `executed_by` rename in §18. §15: `workspace.member_flags_changed` → `workspace.member_role_changed`; the member-added / invited / materialised rows carry `role`, not `flags`. |
| 2026-09-19 | v1.15 | 172 (#172) | §18's `ENDPOINT` row reworded for the re-rooted published-endpoint URL shape (R-EP5); the enum and its wire value are unchanged. |
| 2026-09-16 | v1.14 | 149 / #125 node_progress | §11 gains **`node_progress`** — the measured per-node operation sample ([REST API §6.4.9](rest-api.md#649-node_progress)); zero or more between a node's `node_started` and its terminal event, never terminal. |
| 2026-09-15 | v1.12 | 142 release cascade | §15's `pipeline.version.released` row gains `templates_released`; `template.version.released` gains the cascade source (`cascade_from_pipeline_id`, `cascade_from_version`) when the pipeline release made it. |
| 2026-09-14 | v1.11 | 140 release checks | New **§20 `CheckRunVerdict`** (`pass` \| `fail` \| `error`) and **§21 `CheckRunVia`** (`mcp` \| `rest` \| `ui` \| `release`) — the wire values of `pipeline_check_runs` (metadata-db §4.20, V28), authored in pipeline-contract `ReleaseCheckGate.kt`. §15's `pipeline.version.released` row gains the override record (`checks_overridden`, `override_reason`); §16 registers the `pipeline.check.*` domain (pipeline-contract §13.17). |
| 2026-09-14 | v1.10 | 137 mail notices | §15 gains the **mail audit events** sub-table: `mail.sent` / `mail.failed` (`MailAuditEvents`, drift-guarded by `MailAuditEventsSpecDriftTest`) — kind, recipients, act and Message-ID or error in `details`; never a body, never a password. |
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
| 2026-09-11 | v1.11 | 118 learned semantic layer | New **§19 `LearnedFactKind`** — the closed kind list of the learned semantic layer (twelve kinds, each with its scope; no JDBC-provided kind by design, D-S2) and its companion trust states; three sources (this table, the V25 CHECK, the Kotlin enum) drift-tested to one truth. §15 gains `semantics.recorded` / `semantics.retired`; §16 registers the `semantics.*` error domain (§13.15, two-segment). |
| 2026-09-10 | v1.10 | 113 workspace invitations | §15 gains the invitation audit events — `workspace.member_invited` (also fired on every RE-INVITE, because the upsert is the latest admin decision winning and it is audited every time), `workspace.invitation_revoked`, and `workspace.invitation_materialised` (fired at login, carrying `inviter` — the actor whose decision the invitee's sign-in is executing). Authority: [Auth §4.6](auth.md#46-invitations); emitted by `WorkspaceService`. |
| 2026-09-12 | v1.12 | 120 docs as tools + resource-read audit | §16 registers the `mcp.*` error domain (§13.16, two-segment `mcp.doc_not_found` — the tool-surface answer to a resource read's not-found, which is the JSON-RPC protocol's RESOURCE_NOT_FOUND and carries no §13 code). §15's MCP table gains `mcp.resource.read` — every `resources/read` audited at the reader's one choke point; until now the read side of the MCP surface left no trace at all. |
| 2026-09-13 | v1.13 | 123 table-not-found | §16's two-segment enumeration gains `datasource.table_not_found` and `datasource.table_forbidden` (§13.8 — the introspector's table resolution; the datasource domain has no entity dimension, same shape as its siblings). |
