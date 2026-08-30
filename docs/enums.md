# Enumerations Reference

**Status:** v1.4 (living document — updated as enums evolve)
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

**Reserved for future:** `SNOWFLAKE`, `BIGQUERY`, `REDSHIFT` (see [ROADMAP](ROADMAP.md)).

---

## 6. `TemplateEngine` — template language

**Source:** [Templates §4](templates.md#4-freemarker-integration)
**Used by:** templates (engine dispatch).

| Value | Description |
|---|---|
| `freemarker` | Apache Freemarker template engine. Default and only supported engine in v1. |

**Reserved for future:** `pebble`, `handlebars`, `thymeleaf-sql`, `none` (raw SQL with no template processing — see [ROADMAP](ROADMAP.md)).

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

| Value | Includes | Description |
|---|---|---|
| `read` | — | Read pipelines, templates, datasources (metadata), executions |
| `execute` | `read` | Execute pipelines; retrieve execution results |
| `author` | `execute`, `read` | Create / modify pipelines and templates |
| `admin` | `author`, `execute`, `read` | Manage datasources, users, system config |

---

## 9. `NodeStatus` — per-node execution outcome

**Source:** [DAG Executor §7](dag-executor.md#7-node-stats-collection)
**Used by:** dag-executor (node_stats), rest-api (response envelope).

| Value | Description |
|---|---|
| `SUCCESS` | Node completed without error |
| `FAILED` | Node threw an exception; pipeline aborted |
| `ABORTED` | Node never started because a dependency failed |

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
| `auth.workspace.provisioned` | Personal workspace auto-created on first login (`auto-per-user` mode) |
| `auth.workspace.created` | Workspace created through the service path |
| `auth.workspace.header_rejected` | `DP-Workspace` presented on an API-key request |

> Password and lockout events exist only for the optional local accounts ([Auth §5A](auth.md#5a-local-password-accounts-optional)); an OIDC-only deployment never writes them. No event in this table ever carries credential material.

**Datasource audit events** (same `audit_log` table, defined in [Datasources §7.4](datasources.md#74-decryption-points-and-audit-log)):

| Value | Trigger |
|---|---|
| `datasource.pool_build` | Credential decrypted to build a connection pool |
| `datasource.pool_rebuild` | Update-triggered eviction of a live pool; carries the initiating operator (the decryption itself is the subsequent `pool_build`) — see [Datasources §7.4](datasources.md#74-decryption-points-and-audit-log) |
| `datasource.connection_test` | Explicit connection test (`POST .../test`) |
| `datasource.key_rotation` | Master-key rotation re-encryption pass |

---

## 16. Error Code Domains (prefix catalog)

**Source:** [Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog) — the ONLY catalog of concrete error codes. This section registers domains; deliberately no code list here, so there is exactly one place a code can drift from.
**Used by:** every spec that defines error codes.

Error codes follow `{domain}.{entity}.{failure}` — three segments, all lowercase snake_case, dot-separated, ASCII. Two-segment codes exist only where the domain has no entity dimension (`datasource.in_use`, `datasource.driver_not_loaded`, `datasource.not_found`, `template.not_found`, `rate_limit.exceeded`). Additive-only — never reused, never renamed.

| Domain | Description | Catalog section |
|---|---|---|
| `pipeline.validation.*` | Pipeline JSON validation failures (write-time) | pipeline-contract §13.1 |
| `pipeline.import.*` | Pipeline import failures | pipeline-contract §13.2 |
| `pipeline.execution.*` | Pipeline execution failures (run-time) | pipeline-contract §13.3 |
| `pipeline.node.*` | Individual node execution failures | pipeline-contract §13.4 |
| `pipeline.staging.*` | Tempdb / staging failures | pipeline-contract §13.5 |
| `type_mapping.*` | Type mapping warnings (not errors — in response `warnings` array) | pipeline-contract §13.6 |
| `auth.api_key.*`, `auth.scope.*`, `auth.session.*`, `auth.login.*`, `auth.csrf.*` | Authentication / authorization errors | pipeline-contract §13.7 (defined in [Auth §9](auth.md#9-auth-errors)) |
| `datasource.*` (incl. `datasource.validation.*`) | Datasource CRUD, validation, driver availability | pipeline-contract §13.8 (defined in [Datasources §9](datasources.md#9-validation-rules)) |
| `template.*` (incl. `template.validation.*`) | Template CRUD, validation failures (incl. import cycles: `template.validation.import_cycle`) | pipeline-contract §13.9 (defined in [Templates §7](templates.md#7-validation-rules)) |
| `result.*` | Result cursor retrieval failures | pipeline-contract §13.10 (defined in [REST API §7](rest-api.md#7-result-delivery)) |
| `rate_limit.exceeded` | Rate limit hit (single code for all layers) | pipeline-contract §13.11 |
| `idempotency.*` | Idempotency-key conflicts | pipeline-contract §13.11 |
| `workspace.*` | Workspace resolution, membership and provisioning refusals | pipeline-contract §13.12 (defined in [Auth §5](auth.md#5-oidc-login-flow)) |

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
| `429 Too Many Requests` | Rate limited | `rate_limit.exceeded`, `pipeline.execution.concurrency_limit` |
| `500 Internal Server Error` | Server error | Uncaught exceptions, `pipeline.staging.*`, `result.storage_unavailable` |
| `502 Bad Gateway` | Upstream failure | `pipeline.node.datasource_connection_failed`, `pipeline.node.query_execution_failed` |
| `503 Service Unavailable` | Service not ready | Readiness check failure |
| `504 Gateway Timeout` | Execution timeout | `pipeline.execution.timeout` |

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
| `TemplateEngine` | templates | templates |
| `StagingEngine` | pipeline-contract | staging, dag-executor |
| `Scope` | auth | every endpoint |
| `NodeStatus` | dag-executor | rest-api, mcp-server |
| `ExecutionStatus` | rest-api | dag-executor, mcp-server, persistence |
| `SseEventType` | rest-api | dag-executor, mcp-server |
| `ResultFormat` | rest-api | mcp-server |
| `SslMode` | datasources | datasources |
| `AuthAuditEvent` | auth | observability |
| `ExecutionTrigger` | rest-api | mcp-server, persistence |

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
| 2026-08-05 | v1.0 | initial draft | Initial enums reference: 18 enum categories cataloged, cross-reference table, validation discipline |
| 2026-08-07 | v1.1 | consistency campaign | Case/serialization convention added; `OutputTarget` default → `caller` (D1); `ResultDelivery` removed (D9); `execution_aborted` SSE event added (D7); `AuthAuditEvent` synced to auth §10.1 (no password/lockout events); §16 reduced to domain registry pointing at the single concrete catalog (pipeline-contract §13), D5 renames applied; single authority per enum; broken source links fixed. See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-11 | v1.2 | gate C review | §16: registered `template.not_found` / `datasource.not_found` as two-segment codes (read/mutate-path misses; pipeline-contract §13 v1.3); template domain row widened to `template.*`. |
| 2026-08-16 | v1.3 | pipeline composition | §18 `ExecutionTrigger` gains `PIPELINE` — a child execution spawned by a parent's PIPELINE node (V3 migration widens `chk_triggered_via` to match). |
| 2026-08-17 | v1.4 | pipeline composition | §2 `NodeType` gains `PIPELINE` — a node that executes a version-pinned pipeline as a child execution (pipeline-contract §4.9/§8.5; guarded by the new `NodeTypeSpecDriftTest` in pipeline-contract). |
| 2026-08-30 | v1.5 | local password auth | §15 `AuthAuditEvent` gains the local-account events: `auth.login.bad_credentials`, `auth.login.locked`, `auth.password.{seeded,changed,reset,disabled}`, `auth.user.{created,unlocked}`; `auth.login.success`/`user_inactive` re-described as shared OIDC/local. The "no password or lockout events" note is replaced — they exist for the optional local accounts only (auth.md §5A). |
