# Datasources Specification

**Status:** v1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md)
**Last updated:** 2026-08-05

---

## 1. Purpose

A **Datasource** is the environment-specific connection to an external database. Pipelines reference datasources by **stable name** (e.g., `pg-prod`); the Datasource Registry resolves that name to actual connection details (JDBC URL, credentials, pool size) per environment.

This separation is what makes **pipelines portable across environments**: the same pipeline JSON runs in dev, staging, and prod, with each environment's Datasource Registry providing different actual connections for the same names.

This spec defines:
- The Datasource entity model.
- Per-dialect JDBC adapter behavior.
- The Datasource Registry API.
- Connection pool configuration and lifecycle.
- Credential storage (encryption at rest).
- Connection testing and health checks.

---

## 2. Design Principles

1. **Name-stable, env-resolved.** Datasource names are the contract between pipelines and connections. Names like `pg-prod` are stable across envs; their underlying JDBC URLs differ.
2. **Credentials never in pipeline JSON.** Pipelines reference names only. Datasource credentials live in the Datasource Registry, encrypted at rest, never returned in GET responses.
3. **One dialect per datasource.** The `dialect` declares the JDBC driver, type mapper, SQL behavior. Pipelines validate at write time that their template's `dialect` matches the referenced datasource's `dialect`.
4. **Pooled connections, not per-query.** Each datasource has its own HikariCP connection pool. Pipeline node executions lease connections from the pool and return them.
5. **Health-checkable.** Every datasource can be tested via `/datasources/{name}/test`. Pipeline execution pre-checks that all referenced datasources are reachable.
6. **Fail loudly on missing datasource.** A pipeline referencing a datasource name not in the registry fails validation at write time (`pipeline.validation.unknown_datasource`). A datasource removed at runtime causes execution to fail (`pipeline.node.datasource_not_found`).

---

## 3. Datasource Entity

### 3.1 JSON structure (request — `POST /datasources`)

```json
{
  "name": "pg-prod",
  "display_name": "Production Postgres",
  "description": "Primary OLTP database.",
  "dialect": "POSTGRES",
  "jdbc_url": "jdbc:postgresql://pg-prod.internal:5432/app_db",
  "username": "datapipelines_app",
  "password": "...",                       // write-only; never returned in GET
  "properties": {
    "maximum_pool_size": 10,
    "minimum_idle": 2,
    "connection_timeout_seconds": 30,
    "idle_timeout_seconds": 600,
    "max_lifetime_seconds": 1800,
    "query_timeout_seconds": 60,
    "ssl": true,
    "ssl_mode": "verify-full",
    "ssl_root_cert_path": "/etc/ssl/certs/pg-prod-ca.pem"
  }
}
```

### 3.2 JSON structure (response — `GET /datasources/{name}`)

Identical to request, except:
- `password` is **never** returned. Replaced with `password_set: true | false`.
- `jdbc_url` is included (operators need it for debugging).

### 3.3 Field reference

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Stable identifier. `[a-z0-9_-]+`, length 1–63. |
| `display_name` | string | yes | Human-readable name. |
| `description` | string | yes | Long-form description. |
| `dialect` | string (enum) | yes | One of `POSTGRES`, `ORACLE`, `MSSQL`, `MYSQL`, `H2`, `DUCKDB`, `SQLITE`. Determines the JDBC driver, type mapper, and SQL behavior. |
| `jdbc_url` | string | yes | JDBC URL for the dialect. |
| `username` | string | yes | DB username. |
| `password` | string | yes on write, never returned | DB password. |
| `properties` | object | optional | Connection pool / SSL / dialect-specific config. See §5. |

---

## 4. Supported Dialects

### 4.1 Dialect catalog

| Dialect | JDBC driver (Maven coordinates) | License | Notes |
|---|---|---|---|
| `POSTGRES` | `org.postgresql:postgresql` | BSD-2-Clause | Clean license, ships in core. |
| `ORACLE` | `com.oracle.database.jdbc:ojdbc11` | OTN | **User-supplied via optional Gradle profile** — see §10. |
| `MSSQL` | `com.microsoft.sqlserver:mssql-jdbc` | MIT | Clean license, ships in core. |
| `MYSQL` | `com.mysql:mysql-connector-j` | GPL-2.0 with FOSS exception | **Verify redistribution terms before bundling** — likely user-supplied. |
| `H2` | `com.h2database:h2` | MPL 2.0 / EPL 1.0 | Clean license, ships in core (also used for staging). |
| `DUCKDB` | `org.duckdb:duckdb_jdbc` | MIT | Clean license, ships in core. |
| `SQLITE` | `org.xerial:sqlite-jdbc` | Apache 2.0 (with SQLite public-domain bundled) | Clean license, ships in core. |

### 4.2 Dialect adapter interface

```kotlin
interface DialectAdapter {
    val dialect: Dialect
    val jdbcDriverClassName: String
    val defaultProperties: Map<String, String>          // driver-specific defaults
    val typeMapper: IngressTypeMapper                   // JDBC types → canonical types
    fun validateJdbcUrl(url: String): ValidationResult  // dialect-specific URL validation
    fun buildHikariConfig(datasource: Datasource): HikariConfig
}
```

Each dialect has an implementation:
- `PostgresDialectAdapter`
- `OracleDialectAdapter`
- `MssqlDialectAdapter`
- `MysqlDialectAdapter`
- `H2DialectAdapter`
- `DuckdbDialectAdapter`
- `SqliteDialectAdapter`

### 4.3 Type mapper integration

Each dialect's `typeMapper` implements the per-dialect mapping tables in [Type System §5](type-system.md#5-source-to-canonical-mapping-tables). Adding a new dialect = writing a new `DialectAdapter` + `IngressTypeMapper` (~100–200 lines).

---

## 5. Connection Pool Configuration

HikariCP is the connection pool. Default properties per datasource:

| Property | Default | Description |
|---|---|---|
| `maximum_pool_size` | 10 | Max connections to the underlying DB. |
| `minimum_idle` | 2 | Idle connections kept warm. |
| `connection_timeout_seconds` | 30 | Max wait to acquire a connection from the pool. |
| `idle_timeout_seconds` | 600 | Idle connection max age. |
| `max_lifetime_seconds` | 1800 | Connection max age (forces reconnect). |
| `query_timeout_seconds` | 60 | JDBC `Statement.setQueryTimeout`. Per-query. |
| `ssl` | false | Enable TLS for connections. |
| `ssl_mode` | (dialect-specific) | `disable`, `prefer`, `require`, `verify-ca`, `verify-full` (PG); equivalent for others. |
| `ssl_root_cert_path` | (none) | Path to CA cert (server-side filesystem path). |

### 5.1 Pool sizing guidance

Per datasource, `maximum_pool_size` should be sized to:
- **Support concurrent pipeline node executions** against this datasource.
- **Stay under the source DB's connection limit.**

Default 10 is conservative. High-traffic datasources can be tuned up. The executor's `max-parallel-nodes` default (4) means up to 4 simultaneous queries against the same datasource within one execution; across executions, the pool is shared.

### 5.2 Pool lifecycle

- Pool created lazily on first lease.
- Pool kept alive for the datasource's lifetime.
- On datasource update (PUT), the old pool is drained and a new one initialized.
- On datasource delete (soft), the pool is drained and refused new leases.

### 5.3 Lease lifecycle

```kotlin
suspend fun <T> withConnection(datasourceName: String, block: (Connection) -> T): T {
    val datasource = registry.get(datasourceName) ?: throw DatasourceNotFoundException(...)
    val pool = poolFor(datasource)
    val connection = pool.connection    // blocks up to connection_timeout
    return try {
        block(connection)
    } finally {
        connection.close()              // returns to pool
    }
}
```

Acquisition timeout (30s default) exceeded → `pipeline.node.datasource_connection_failed`.

---

## 6. Datasource Registry

### 6.1 Interface

```kotlin
interface DatasourceRegistry {
    fun list(): List<Datasource>
    fun get(name: String): Datasource?       // null if not registered or soft-deleted
    fun exists(name: String): Boolean
    fun save(datasource: Datasource): Datasource   // create or update
    fun delete(name: String): DeleteResult         // soft delete; fails if in use
    fun poolFor(datasource: Datasource): ConnectionPool
    fun testConnection(name: String): TestResult
}
```

### 6.2 In-use check on delete

`DELETE /datasources/{name}` fails with `datasource.in_use` if any non-deleted pipeline references this name. The error response includes the list of pipelines using it, so the operator can clean up.

### 6.3 Cache

- Datasource metadata cached in memory (low churn).
- Cache invalidated on create/update/delete.
- Connection pools cached separately (lazy init, see §5.2).

---

## 7. Credential Storage

### 7.1 Encryption at rest

Passwords are **never** stored in plaintext. Encryption approach:

- AES-256-GCM with a master key.
- Master key sourced from one of (in priority order):
  1. `DATAPIPLEINES_DB_ENCRYPTION_KEY` environment variable (raw 32-byte base64).
  2. External KMS (AWS KMS, GCP KMS, HashiCorp Vault) — integration TBD per deployment.
  3. Generated on first run, stored at `${data_dir}/master.key` with `0600` permissions (single-node dev deployments only — flagged in startup logs).

### 7.2 Schema

```sql
CREATE TABLE datasources (
  name            VARCHAR(63) PRIMARY KEY,
  display_name    VARCHAR(255) NOT NULL,
  description     TEXT,
  dialect         VARCHAR(20) NOT NULL,
  jdbc_url        TEXT NOT NULL,
  username        TEXT NOT NULL,
  password_encrypted BYTEA NOT NULL,         -- AES-256-GCM(ciphertext + tag + nonce)
  properties      JSONB NOT NULL,
  is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
  created_by      UUID NOT NULL
);
```

### 7.3 Key rotation

Key rotation = decrypt every `password_encrypted` with old key, re-encrypt with new key, in a single transaction. Triggered by an admin CLI / endpoint. Documented in the runbook (future).

### 7.4 Audit log

Every read of `password_encrypted` (i.e., every connection lease that re-decrypts) logs:
- Timestamp
- Caller (user or service)
- Reason (pipeline execution ID + node ID, or connection test, or admin inspect)

Stored in the audit log table; retained per deployment policy.

---

## 8. Connection Testing

### 8.1 `POST /datasources/{name}/test`

```json
// Response (200 OK):
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "connected": true,
    "server_version": "PostgreSQL 16.2 on x86_64-pc-linux-gnu, ...",
    "tested_at": "2026-08-05T14:30:00Z",
    "latency_ms": 23
  }
}
```

Or on failure:

```json
{
  "schema_version": 1,
  "correlation_id": "uuid",
  "data": {
    "connected": false,
    "tested_at": "2026-08-05T14:30:00Z",
    "error": "Could not acquire connection: Connection refused.",
    "error_class": "java.net.ConnectException"
  }
}
```

Note: connection test failure is **not** an HTTP error. The caller asked "can I connect?" and got an honest answer. HTTP 200 always (provided the datasource exists).

### 8.2 Pre-execution check

Before pipeline execution begins, the executor pre-checks that every datasource referenced by the pipeline's nodes is configured and reachable. Failures here abort before any node runs, with error code `pipeline.execution.datasource_unreachable`.

Pre-check is a fast `SELECT 1` (or dialect-equivalent) against each datasource. Cost: milliseconds per datasource, parallelizable.

### 8.3 Background health checks (optional)

In v1.1+, the system can poll datasources on a schedule and surface health in the UI. Not in v1.

---

## 9. Validation Rules

| Code | Check |
|---|---|
| `datasource.validation.name_invalid` | `name` matches `[a-z0-9_-]+`, length 1–63 |
| `datasource.validation.dialect_invalid` | `dialect` in allowed enum |
| `datasource.validation.jdbc_url_malformed` | URL parses and matches dialect's expected pattern |
| `datasource.validation.jdbc_url_scheme_invalid` | URL begins with `jdbc:{dialect}:` |
| `datasource.validation.password_missing` | `password` required on create |
| `datasource.validation.properties_invalid` | `properties` JSON is well-formed and keys are valid for the dialect |
| `datasource.validation.duplicate_name` | Create with name that already exists (and not soft-deleted) |

---

## 10. JDBC Driver Packaging

### 10.1 The licensing problem

Some JDBC drivers have licenses that complicate redistribution in an OSS project:
- **Oracle ojdbc** — OTN license; redistributable on Maven Central but license terms bind the distributor.
- **MySQL Connector/J** — GPL-2.0 with FOSS exception; depends on the project's license compatibility.

### 10.2 Strategy

**Default distribution (clean licenses only):**
- Postgres, MSSQL, H2, DuckDB, SQLite ship in the default build.
- Default deployment can connect to PG, MSSQL, MySQL (if user supplies driver), H2, DuckDB, SQLite out of the box.

**Optional Gradle profile `-Poracle`** adds the Oracle driver as a dependency:
- Operator builds with `./gradlew -Poracle build` to include.
- Or: deploy-time, drop the JAR into `lib/` (Spring Boot's loader picks it up).

**Optional Gradle profile `-Pmysql`** does the same for MySQL Connector/J.

For self-hosted deployments where the operator has accepted the relevant licenses, both options work. Default build stays clean.

### 10.3 Driver class lookup

```kotlin
object JdbcDrivers {
    private val drivers = mapOf(
        Dialect.POSTGRES to "org.postgresql.Driver",
        Dialect.ORACLE   to "oracle.jdbc.OracleDriver",
        Dialect.MSSQL    to "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        Dialect.MYSQL    to "com.mysql.cj.jdbc.Driver",
        Dialect.H2       to "org.h2.Driver",
        Dialect.DUCKDB   to "org.duckdb.DuckDBDriver",
        Dialect.SQLITE   to "org.sqlite.JDBC"
    )

    fun classNameFor(dialect: Dialect): String =
        drivers[dialect] ?: error("No driver mapped for dialect $dialect")

    fun isAvailable(dialect: Dialect): Boolean = try {
        Class.forName(classNameFor(dialect)); true
    } catch (e: ClassNotFoundException) {
        false
    }
}
```

At datasource create time, validation calls `isAvailable(dialect)` and rejects with `datasource.validation.driver_not_loaded` if the driver JAR is missing.

---

## 11. CRUD Operations

| Operation | Method & Path | Notes |
|---|---|---|
| Register datasource | `POST /datasources` | Encrypts password, stores. |
| List datasources | `GET /datasources?dialect={d}` | Passwords never included. |
| Get datasource | `GET /datasources/{name}` | Password replaced with `password_set: bool`. |
| Update datasource | `PUT /datasources/{name}` | Password optional (omit to keep existing). Drains & rebuilds pool. |
| Delete datasource | `DELETE /datasources/{name}` | Soft delete; fails if `in_use`. |
| Test connection | `POST /datasources/{name}/test` | Returns `{connected, server_version?, error?}`. |

---

## 12. Stability Promise

### 12.1 Frozen in v1

- Datasource entity JSON shape.
- The 7 supported dialects and their identifiers.
- The separation of pipeline-name from connection-details.
- The encryption-at-rest requirement.
- The connection-pool configuration keys.

### 12.2 Not frozen

- Pool implementation (HikariCP) — could swap to AGPL-licensed alternatives if license concerns arise.
- The exact encryption scheme (AES-256-GCM today, could evolve to KMS-only).
- New dialects added non-breakingly.
- New connection-pool properties added non-breakingly.

---

## 13. Implementation Notes

### 13.1 Where this lives

`datasources` Gradle module:

- `co.datapipelines.datasources.Datasource` data class
- `co.datapipelines.datasources.Dialect` enum
- `co.datapipelines.datasources.DatasourceRegistry` — service interface
- `co.datapipelines.datasources.JdbcDrivers` — driver class lookup
- `co.datapipelines.datasources.pooling.ConnectionPoolManager` — HikariCP wrapper
- `co.datapipelines.datasources.crypto.CredentialEncryptor` — AES-256-GCM
- Per-dialect: `PostgresDialectAdapter`, `OracleDialectAdapter`, etc.

### 13.2 Testing

- Unit tests per `DialectAdapter` covering `validateJdbcUrl` and `buildHikariConfig`.
- Integration tests via Testcontainers: spin up real PG/MySQL/MSSQL/Oracle containers, register a datasource, test connection, run a query, verify type mapping matches [Type System §5](type-system.md#5-source-to-canonical-mapping-tables).
- Credential encryption tests: encrypt/decrypt round-trip, key-rotation flow, tamper detection (auth tag failure).
- Connection pool tests: lease timeout, max-pool-size enforcement, eviction on datasource delete.

---

## 14. Open Questions / Future Additions

Out of scope for v1:

- **KMS integration**: AWS KMS / GCP KMS / HashiCorp Vault as master-key sources.
- **Background health checks**: scheduled polling of datasources with UI health indicators.
- **Datasource groups / failover**: pair primary + replica, fail over on connection failure.
- **Read-only enforcement**: some datasources should be read-only by contract (we never write to sources, but enforcing at the datasource level adds defense).
- **Schema introspection tools**: `GET /datasources/{name}/schema`, `/tables`, `/tables/{t}/columns` — useful for LLM-assisted pipeline authoring. v1.1 candidate.
- **SSH tunnel / bastion host support**: for datasources reachable only via bastion. Common in enterprise.
- **OAuth / IAM auth for cloud databases**: Snowflake, BigQuery (when those dialects are added).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial datasources spec: entity, dialect adapters, pool config, credential encryption, driver packaging strategy |
