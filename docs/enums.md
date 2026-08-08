# Enumerations Reference

**Status:** v1 (living document — updated as enums evolve)
**Owner:** datapipelines.co core
**Purpose:** Single source of truth for every enum value used across the system. Prevents spelling drift across specs and across the codebase.

---

## How to use this document

- Every enum used in datapipelines.co is cataloged here.
- Specs may inline enum values for readability, but this document is the **authoritative reference**.
- If a value appears in code or a spec that doesn't match this document, the spec/code is wrong.
- When adding a new enum value: add it here first, then propagate to specs and code.
- Values are **additive-only** per the stability promises in individual specs.

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
| `DECIMAL` | `number` | Exact numeric with precision ≤ 15 |
| `BIGDECIMAL` | `string` | Exact numeric with precision > 15 |
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

**Reserved for future:** `EXPRESSION`, `HTTP` (non-SQL node types — see [ROADMAP](ROADMAP.md)).

---

## 3. `OutputTarget` — where a DQL node's ResultSet goes

**Source:** [Pipeline Contract §4.7](pipeline-contract.md#47-output-block-reference)
**Used by:** pipeline-contract, dag-executor, staging.

| Value | Required fields | Description |
|---|---|---|
| `tempdb` | `table` | Stage ResultSet into in-memory tempdb table. Default if `output` block is omitted. |
| `caller` | (none) | Return ResultSet as pipeline output. **Exactly one node** per pipeline may use this. |
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

**Source:** [Type System §5](type-system.md#5-source-to-canonical-mapping-tables), [Datasources §4](datasources.md#4-supported-dialects)
**Used by:** type-system (per-dialect type mappers), datasources (driver dispatch), templates (template targets a dialect), pipeline-contract (validation: template dialect must match datasource dialect).

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

---

## 8. `Scope` — API key authorization scope

**Source:** [Auth §5](auth.md#5-scopes)
**Used by:** auth, every endpoint (scope enforcement).

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
| `ABORTED` | Execution cancelled externally (client disconnect beyond grace period, admin kill, shutdown) |

**Reserved for future:** `PARTIAL` (partial-result mode where some nodes succeeded but a non-critical path failed — see [ROADMAP](ROADMAP.md)).

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
| `data_ready` | Result data is available (inline or claim-check) | Last event on success; follows `pipeline_completed` |

**Order guarantees:** see [REST API §6.5](rest-api.md#65-event-ordering-guarantee).

**Reserved for future:** `data_chunk` (streaming row chunks for incremental processing — see [ROADMAP](ROADMAP.md)).

---

## 12. `ResultDelivery` — how `data_ready` payload is delivered

**Source:** [REST API §6.4.7](rest-api.md#647-data_ready)
**Used by:** rest-api, dag-executor, mcp-server.

| Value | Description |
|---|---|
| `inline` | Schema + rows in the `data_ready` event payload. Used when result size ≤ `LARGE_RESULT_THRESHOLD` (default 1 MB). |
| `claim_check` | Schema + `result_url` in the `data_ready` event; rows fetched via separate paginated endpoint from Redis. Used for large results. |

---

## 13. `ResultFormat` — wire format for result data

**Source:** [REST API §7](rest-api.md#7-claim-check-result-retrieval)
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

**Source:** [Auth §9.1](auth.md#91-events-logged)
**Used by:** auth, observability.

| Value | Trigger |
|---|---|
| `auth.login.success` | Successful login |
| `auth.login.failure` | Failed login (bad password, unknown user) |
| `auth.login.locked` | Account locked from failed attempts |
| `auth.logout` | Explicit logout |
| `auth.api_key.created` | New API key issued |
| `auth.api_key.revoked` | API key revoked |
| `auth.api_key.used` | API key successfully authenticated (sampled 1/100) |
| `auth.api_key.rejected` | API key failed validation (revoked, expired, invalid) |
| `auth.scope.denied` | Request rejected for insufficient scope |
| `auth.user.created` | New user account (admin action) |
| `auth.user.deactivated` | User account deactivated |
| `auth.password.changed` | Password change |

---

## 16. Error Code Domains (prefix catalog)

**Source:** [Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog), [Auth §7](auth.md#7-auth-errors)
**Used by:** every spec that defines error codes.

Error codes follow `{domain}.{entity}.{failure}`. Domains:

| Domain | Description | Source spec |
|---|---|---|
| `pipeline.validation.*` | Pipeline JSON validation failures (write-time) | pipeline-contract §13.1 |
| `pipeline.import.*` | Pipeline import failures | pipeline-contract §13.2 |
| `pipeline.execution.*` | Pipeline execution failures (run-time) | pipeline-contract §13.3 |
| `pipeline.node.*` | Individual node execution failures | pipeline-contract §13.4 |
| `pipeline.staging.*` | Tempdb / staging failures | pipeline-contract §13.5 |
| `type_mapping.*` | Type mapping warnings (not errors — in response `warnings` array) | pipeline-contract §13.6 |
| `auth.*` | Authentication / authorization errors | auth §7 |
| `auth.api_key.*` | API-key-specific auth events | auth |
| `auth.scope.*` | Authorization scope checks | auth |
| `auth.session.*` | Session token (JWT) checks | auth |
| `auth.login.*` | Login flow events | auth |
| `auth.csrf.*` | CSRF protection | auth |
| `auth.rate_limit.*` | Auth-related rate limiting | auth |
| `datasource.validation.*` | Datasource CRUD validation | datasources §9 |
| `datasource.in_use` | Delete blocked by pipeline references | datasources |
| `datasource.driver_not_loaded` | JDBC driver JAR missing for dialect | datasources |
| `template.validation.*` | Template validation failures | templates §7 |
| `template.import.*` | Template import failures | templates |
| `rate_limit.exceeded` | General API rate limit hit | rest-api §12 |
| `idempotency_key.reuse_for_different_request` | Same key, different body | dag-executor §11.2 |
| `result.claim_check_expired` | Large result TTL expired | rest-api §7.6 |
| `result.execution_not_found` | Execution ID unknown | rest-api §7.6 |
| `result.execution_incomplete` | Execution not yet at `data_ready` | rest-api §7.6 |
| `result.execution_failed` | Execution ended in failure | rest-api §7.6 |
| `result.format_unsupported` | Unknown `format` parameter | rest-api §7.6 |

**Convention:** all lowercase, dot-separated, ASCII. Additive-only — never reused, never renamed.

---

## 17. HTTP Status Code Conventions

**Source:** [REST API §2.6](rest-api.md#26-http-status-codes-used-correctly)
**Used by:** every spec that defines HTTP behavior.

| Code | Meaning | When used |
|---|---|---|
| `200 OK` | Success (synchronous); SSE stream established | GET, PUT (update), successful POST |
| `201 Created` | Resource created | POST that creates a new entity |
| `204 No Content` | Success, no body | DELETE |
| `400 Bad Request` | Client-side validation failure | All `pipeline.validation.*`, `template.validation.*`, `datasource.validation.*` |
| `401 Unauthorized` | Auth missing or invalid | All `auth.api_key_missing`, `auth.api_key_invalid`, `auth.session.*` |
| `403 Forbidden` | Auth valid but insufficient scope | `auth.scope_insufficient`, `auth.datasource_forbidden`, `auth.csrf.*` |
| `404 Not Found` | Resource doesn't exist | `pipeline.execution.not_found`, etc. |
| `409 Conflict` | State conflict | `pipeline.import.version_conflict`, `result.execution_incomplete` |
| `410 Gone` | Resource expired | `result.claim_check_expired`, `result.execution_failed` |
| `429 Too Many Requests` | Rate limited | `rate_limit.exceeded`, `pipeline.execution.concurrency_limit`, `auth.rate_limit.exceeded` |
| `500 Internal Server Error` | Server error | Uncaught exceptions, `pipeline.execution.aborted`, `pipeline.staging.*` |
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
| `SCHEDULED` | (Future) Cron-triggered execution |
| `WEBHOOK` | (Future) External webhook trigger |

---

## Cross-Reference: Where Each Enum Is Authored

| Enum | Authoring spec | Consuming specs |
|---|---|---|
| `LogicalType` | type-system | pipeline-contract, templates, dag-executor, staging, mcp-server |
| `NodeType` | pipeline-contract | dag-executor |
| `OutputTarget` | pipeline-contract | dag-executor, staging |
| `WriteMode` | pipeline-contract | dag-executor |
| `Dialect` | datasources (canonical list), type-system (per-dialect mappers) | templates, pipeline-contract, mcp-server |
| `TemplateEngine` | templates | templates |
| `StagingEngine` | pipeline-contract | staging, dag-executor |
| `Scope` | auth | every endpoint |
| `NodeStatus` | dag-executor | rest-api, mcp-server |
| `ExecutionStatus` | rest-api | dag-executor, mcp-server, persistence |
| `SseEventType` | rest-api | dag-executor, mcp-server |
| `ResultDelivery` | rest-api | dag-executor, mcp-server |
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

This document itself is **additive-only** — values are never removed (only marked deprecated). The authoring spec governs its own stability promise; this doc tracks usage.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial enums reference: 18 enum categories cataloged, cross-reference table, validation discipline |
