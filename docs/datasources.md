# Datasources Specification

**Status:** v1.9 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md) · [Enums](enums.md) · [Configuration](configuration.md) · [Metadata DB](metadata-db.md) · [Pipeline Contract](pipeline-contract.md)
**Last updated:** 2026-08-09

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
3. **One dialect per datasource.** The `dialect` declares the JDBC driver, type mapper, SQL behavior. Pipelines validate at write time that their template's `dialect` matches the referenced datasource's `dialect`. The `Dialect` value set has a single authority: [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) (mirrored, non-normatively, by [Enums §5](enums.md#5-dialect--supported-source-database-dialects)). This spec is a *consumer* of that list — §4.1 below maps each value to its driver, it does not define the set.
4. **Pooled connections, not per-query.** Each datasource has its own HikariCP connection pool. Pipeline node executions lease connections from the pool and return them.
5. **Health-checkable.** Every datasource can be tested via `POST /api/v1/datasources/{name}/test`. Pipeline execution pre-checks that all referenced datasources are reachable.
6. **Fail loudly on missing datasource.** A pipeline referencing a datasource name not in the registry fails validation at write time (`pipeline.validation.unknown_datasource`). A datasource removed at runtime causes execution to fail (`pipeline.node.datasource_not_found`).
7. **Validate on write, universally.** No invalid datasource ever reaches the database. Every create/update runs the full §9 rule set *plus* a **test pool build** (§5.4) before the row is written — the same cross-cutting principle as [Pipeline Contract §2, principle 8](pipeline-contract.md#2-design-principles). Connection *properties* are therefore validated by HikariCP and the driver themselves, not by an allowlist maintained in this doc.
8. **Passthrough over allowlist.** Pool and driver tuning is expressed as two namespaced passthrough maps (`properties.hikari.*`, `properties.jdbc.*`). Every HikariCP property and every driver connection property is reachable without a spec change; correctness is enforced at pool build, not by enumeration.

---

## 3. Datasource Entity

### 3.1 JSON structure (request — `POST /api/v1/datasources`)

```json
{
  "name": "pg-prod",
  "display_name": "Production Postgres",
  "description": "Primary OLTP database.",
  "dialect": "POSTGRES",
  "jdbc_url": "jdbc:postgresql://pg-prod.internal:5432/app_db",
  "username": "datapipelines_app",
  "password": "...",                       // write-only; never returned in GET
  "query_timeout_seconds": 60,
  "introspection_include_schemas": ["apex_reporting"],   // OPTIONAL — §7A escape hatch for the
                                                          // system-schema exclusion floors
  "properties": {
    "hikari": {
      "maximumPoolSize": 10,
      "minimumIdle": 2,
      "connectionTimeout": 30000,
      "idleTimeout": 600000,
      "maxLifetime": 1800000
    },
    "jdbc": {
      "ssl": "true",
      "sslmode": "verify-full",
      "sslrootcert": "/etc/ssl/certs/pg-prod-ca.pem",
      "ApplicationName": "datapipelines"
    }
  }
}
```

`properties` has exactly two reserved namespaces — `hikari` (pool properties, applied verbatim to `HikariConfig`) and `jdbc` (driver connection properties). Both are optional, both default to `{}`, and neither is allowlisted by this spec. See §5.

### 3.2 JSON structure (response — `GET /api/v1/datasources/{name}`)

Identical to request, except:
- `password` is **never** returned. Replaced with `password_set: true | false`.
- `jdbc_url` is included (operators need it for debugging).

### 3.3 Field reference

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Stable identifier and primary key. `[a-z0-9_-]+`, length 1–63. **Immutable** — see §11.1. |
| `display_name` | string | yes | Human-readable name. |
| `description` | string | **optional** | Long-form description. Absent or empty is legal; nothing in the system requires it. |
| `dialect` | string (enum) | yes | A `Dialect` value — authority is [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) ([Enums §5](enums.md#5-dialect--supported-source-database-dialects)). Determines the JDBC driver, type mapper, and SQL behavior. |
| `jdbc_url` | string | yes | JDBC URL for the dialect. |
| `username` | string | yes | DB username. |
| `password` | string | yes on write, never returned | DB password. |
| `query_timeout_seconds` | integer | optional | `Statement.setQueryTimeout` for every node executing against this datasource. When set, it **overrides** `datapipelines.executor.node-query-timeout-seconds` — see §5.5. |
| `introspection_include_schemas` | array of strings | optional | §7A escape hatch for the dialect's system-schema exclusion: a schema named here is exempt from the exclusion in **all three** introspection operations. Exact names, **no wildcard patterns** (an entry carrying `*` is rejected at save — `datasource.validation.properties_invalid`), lowercased at bind; absent/empty = the exclusion floors apply unchanged. See §7A. |
| `properties` | object | optional | Two namespaced passthrough maps: `properties.hikari.*` and `properties.jdbc.*`. See §5. |

---

## 4. Supported Dialects

### 4.1 Dialect catalog

The `Dialect` value set is owned by [Type System §5](type-system.md#5-source-to-canonical-mapping-tables); this table is the driver/licensing view of it. Which drivers actually ship in the published image versus opt-in profile versus `lib/` drop-in is stated once in the [Deployment spec](deployment.md) driver matrix.

| Dialect | JDBC driver (Maven coordinates) | License | Notes |
|---|---|---|---|
| `POSTGRES` | `org.postgresql:postgresql` | BSD-2-Clause | Clean license, ships in core. |
| `ORACLE` | `com.oracle.database.jdbc:ojdbc11` | OTN | **User-supplied via optional Gradle profile** — see §10. |
| `MSSQL` | `com.microsoft.sqlserver:mssql-jdbc` | MIT | Clean license, ships in core. |
| `MYSQL` | `com.mysql:mysql-connector-j` | GPL-2.0 with FOSS exception | **Verify redistribution terms before bundling** — treated as user-supplied (`-Pmysql` profile) until verified. See the [Deployment](deployment.md) driver matrix. |
| `H2` | `com.h2database:h2` | MPL 2.0 / EPL 1.0 | Clean license, ships in core (also used for staging). |
| `DUCKDB` | `org.duckdb:duckdb_jdbc` | MIT | Clean license, ships in core. |
| `SQLITE` | `org.xerial:sqlite-jdbc` | Apache 2.0 (with SQLite public-domain bundled) | Clean license, ships in core. |

### 4.2 Dialect adapter interface

```kotlin
interface DialectAdapter {
    val dialect: Dialect
    val jdbcDriverClassName: String
    val defaultProperties: Map<String, String>          // driver-level defaults; overridable by properties.jdbc.*
    val typeMapper: IngressTypeMapper                   // JDBC types → canonical types
    val refusedPropertyKeys: Set<String>                // dialect additions to the §5.6 refusal set (may add, never shrink)
    fun validateJdbcUrl(url: String): ValidationResult  // dialect-specific URL validation (§6.1)
    fun buildHikariConfig(datasource: Datasource): HikariConfig   // entity fields + defaults + properties.* (§5)
}
```

`buildHikariConfig` is the single place the two passthrough maps are applied, so the save-time test pool build (§5.4) and the runtime pool build (§5.2) cannot diverge.

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

HikariCP is the connection pool. Tuning is expressed as **two namespaced passthrough maps** under `properties` — there is no allowlist of supported keys in this spec.

**`properties.hikari.*` — pool properties.** Every entry is applied **verbatim** to `HikariConfig` using HikariCP's own property names and units (camelCase names; all durations in **milliseconds**, as HikariCP defines them). Any property HikariCP supports is therefore usable without a spec change. Illustrative — *not* exhaustive, *not* an allowlist:

| `properties.hikari` key | Server default when omitted | Description |
|---|---|---|
| `maximumPoolSize` | 10 | Max connections to the underlying DB. |
| `minimumIdle` | 2 | Idle connections kept warm. |
| `connectionTimeout` | 30000 | Max wait (ms) to acquire a connection from the pool. |
| `idleTimeout` | 600000 | Idle connection max age (ms). |
| `maxLifetime` | 1800000 | Connection max age (ms) — forces reconnect. |
| … any other HikariCP property | HikariCP's own default | e.g. `keepaliveTime`, `validationTimeout`, `leakDetectionThreshold`, `connectionInitSql`, `readOnly`, `transactionIsolation`. |

**Server-managed keys.** `jdbcUrl`, `username`, `password`, `driverClassName`, `dataSourceClassName`, `poolName`, `metricRegistry`, and `healthCheckRegistry` are derived from the entity and from the dialect adapter. Supplying any of them under `properties.hikari` is a validation failure (`datasource.validation.properties_invalid`) rather than a silent override.

**`properties.jdbc.*` — driver connection properties.** Every entry is passed through as a JDBC connection property (`HikariConfig.addDataSourceProperty`), i.e. what the driver would read from the `Properties` argument of `DriverManager.getConnection`. Values are strings. This is where TLS and driver-specific behavior live; the meaningful keys are the **driver's**, and each dialect adapter contributes `defaultProperties` (§4.2) that callers may override.

Postgres TLS example (`sslmode` values are the [`SslMode`](enums.md#14-sslmode--datasource-tls-mode) catalog):

| `properties.jdbc` key (PG) | Example | Description |
|---|---|---|
| `ssl` | `"true"` | Enable TLS. |
| `sslmode` | `"verify-full"` | `disable` \| `prefer` \| `require` \| `verify-ca` \| `verify-full`. |
| `sslrootcert` | `"/etc/ssl/certs/pg-prod-ca.pem"` | CA cert path on the **server** filesystem. |

Other dialects use their own equivalents (MSSQL `encrypt`/`trustServerCertificate`, MySQL `useSSL`/`sslMode`, Oracle wallet properties); this spec does not restate driver documentation.

**Rationale.** An allowlist of pool keys guarantees the one property an operator needs is the one we forgot. Passthrough plus save-time pool construction (§5.4) gives full coverage *and* fails invalid configuration before the row is written.

### 5.1 Pool sizing guidance

Per datasource, `properties.hikari.maximumPoolSize` should be sized to:
- **Support concurrent pipeline node executions** against this datasource.
- **Stay under the source DB's connection limit.**

Default 10 is conservative. High-traffic datasources can be tuned up. `datapipelines.executor.max-parallel-nodes` ([Configuration §3.2](configuration.md#32-executor), default 4) means up to 4 simultaneous queries against the same datasource within one execution; across executions, the pool is shared, so the practical upper bound is `max-parallel-nodes × concurrent executions touching this datasource`.

### 5.2 Pool lifecycle

- Pool created lazily on first lease.
- Pool kept alive for the datasource's lifetime.
- On datasource update (PUT), the old pool is drained and a new one initialized.
- On datasource delete (soft), the pool is drained and refused new leases.

**Concurrency.** `poolFor(datasource)` (§6.1) is called from many executor coroutines at once, so lazy initialization must be **atomic**: pools live in a `ConcurrentHashMap<String, ConnectionPool>` keyed by datasource name and are created with `computeIfAbsent`, so exactly one `HikariDataSource` is constructed per datasource even under a concurrent first-lease burst. Two consequences the implementation must respect:

- The mapping function does no blocking I/O beyond `HikariDataSource` construction (Hikari fills the pool asynchronously; `initializationFailTimeout` is left at Hikari's default for runtime pools, so an unreachable DB surfaces as a lease failure, not a map-wide stall).
- Replacement on update/delete is `remove()`-then-`close()` on the **evicted** pool, never `close()` on a pool still reachable from the map — in-flight leases drain against the old instance while new leases go to the new one.

### 5.3 Lease lifecycle

```kotlin
suspend fun <T> withConnection(datasourceName: String, block: (Connection) -> T): T {
    val datasource = registry.get(datasourceName) ?: throw DatasourceNotFoundException(...)
    val pool = poolFor(datasource)
    val connection = pool.connection    // blocks up to hikari connectionTimeout
    return try {
        block(connection)               // caller sets Statement.setQueryTimeout per §5.5
    } finally {
        connection.close()              // returns to pool
    }
}
```

No credential decryption happens on this path — the pool already holds the credential from its build (§7.4).

Acquisition timeout (`properties.hikari.connectionTimeout`, 30 000 ms default) exceeded → `pipeline.node.datasource_connection_failed`.

### 5.4 Test pool build (save-time validation)

Datasource create and update run a **test pool build** before the row is written — this is how the passthrough model of §5 stays safe without an allowlist, and it is this entity's instance of the universal validate-on-write principle ([Pipeline Contract §2](pipeline-contract.md#2-design-principles)).

Sequence:

1. The dialect adapter builds a `HikariConfig` from the entity fields plus `defaultProperties`.
2. Every `properties.hikari.*` entry is applied to that `HikariConfig`. HikariCP resolves property names reflectively — an unknown name, a wrong value type, or an out-of-range value throws here.
3. Every `properties.jdbc.*` entry is added via `addDataSourceProperty`.
4. `HikariConfig.validate()` runs, then a `HikariDataSource` is constructed with `initializationFailTimeout = -1` so **no connection to the source database is required** to save the entity.
5. The test pool is closed. Nothing from it is retained.

Any failure in steps 2–4 rejects the save with `datasource.validation.properties_invalid`; the error `details` carry the offending key and the underlying message.

**Limits of this check, stated honestly:** it validates *pool* configuration completely and *driver* property **names** only to the extent the driver rejects unknowns at `Properties` parse time. Driver properties that are only interpreted while opening a socket (a bad `sslrootcert` path, an unsupported `sslmode` value) surface at first connection and via `POST /api/v1/datasources/{name}/test` (§8.1) — not at save. Save-time validation guarantees a *constructible* pool, not a *reachable* database; reachability is deliberately not a save precondition (a datasource may legitimately be registered before its network path exists).

### 5.5 Query timeout precedence

Per-node JDBC statement timeouts resolve in exactly one order:

1. The datasource's `query_timeout_seconds`, **when set** — it wins for every node executing against this datasource.
2. Otherwise `datapipelines.executor.node-query-timeout-seconds` (default 60), defined in [Configuration §3.2](configuration.md#32-executor).

The executor applies the resolved value with `Statement.setQueryTimeout` per node. This is the only place the precedence is stated; other docs reference it. Note this is a *per-statement* timeout and is independent of `datapipelines.executor.execution-timeout-seconds`, which bounds the whole execution.

### 5.6 Refused property keys (normative security exception to passthrough)

Passthrough (§2 principle 8) has one bounded exception: a key is **refused** when the pinned driver treats its value as a class name to instantiate, a file path to read or write, connect-time SQL, or a TLS-verification switch. Refusal applies to **both carriers identically**: a key rejected under `properties.jdbc.*` (`datasource.validation.properties_invalid`) must also be rejected when smuggled into `jdbc_url`'s query/property segment (`datasource.validation.jdbc_url_malformed`) — the URL and the property map are validated against the same union of the server-managed set and the dialect's refusal set.

**Credentials are refused in the URL outright**: `user`, `password`, and driver aliases (e.g. MSSQL `userName`) must arrive via the dedicated `username`/`password` fields — `jdbc_url` is stored plaintext and returned to `read`-scope principals (§3.2), so a credential embedded there defeats §7.1 encryption at rest. This covers a credential smuggled as a query/property key **and** a **userinfo authority in any position** — not only a leading `//user:pw@host` but Oracle's native `jdbc:oracle:thin:user/pw@//host` and H2's `jdbc:h2:tcp://user:pw@host` forms, whose scheme prefix precedes the authority. The authority scan must find the `user[:/]…@` segment wherever it appears, not key off a `//` prefix.

**Secret-valued properties are refused in both carriers**, regardless of whether they also load a class or name a file. `properties.jdbc` is stored plaintext in `properties_json` and returned to `read` scope (§3.2) exactly like `jdbc_url`, so any property whose *value* is credential material is a plaintext-secret exposure. Beyond the enumerated per-dialect keys, a **suffix predicate** over the key name refuses `*password`, `*passwd`, `*pwd`, `*secret`, and `*clientkey` (case-insensitive) — layered on top of the tabled sets so a new driver version's secret key is covered by construction. Named instances that the predicate would otherwise miss (e.g. MSSQL `keyVaultProviderClientKey`) are also listed in the dialect set.

The TLS-verification **switches** refused below (`trustServerCertificate`, `verifyServerCertificate`, `allowPublicKeyRetrieval`) are best-effort: the `sslmode` / `useSSL` / `sslMode` family is deliberately left operator-controlled (§5, [Enums §14](enums.md#14-sslmode--datasource-tls-mode)) because operators legitimately select TLS modes. Refusing a hard "trust anything" switch on one dialect while permitting a mode selector on another is intended, not an inconsistency.

The authoritative enumeration is the module's per-dialect refusal sets, pinned by tests against the driver versions in `libs.versions.toml`; **a driver upgrade must re-review its dialect's set**. Each set must at minimum refuse:

| Dialect | Minimum refused keys (case-insensitive) |
|---|---|
| POSTGRES | `socketFactory`, `socketFactoryArg`, `sslfactory`, `sslfactoryarg`, `sslhostnameverifier`, `authenticationPluginClassName`, `sslkey`, `sslpassword`, `loggerFile`, `loggerLevel` |
| MSSQL | `socketFactoryClass`, `socketFactoryConstructorArg`, `trustStore`, `trustStorePassword`, `trustStoreType`, `keyStoreLocation`, `keyStoreSecret`, `keyStoreAuthentication`, `keyStorePrincipalId`, `clientCertificate`, `clientKey`, `clientKeyPassword`, `trustServerCertificate`, `keyVaultProviderClientKey`, `keyVaultProviderClientId` |
| MYSQL | `allowLoadLocalInfile`, `autoDeserialize`, `allowPublicKeyRetrieval`, plus every `*FactoryClass`/plugin-class property of the pinned Connector/J |
| H2 | `INIT` (RUNSCRIPT vector) |
| SQLITE | `enable_load_extension`, `limit_attached` |
| DUCKDB | the session-init-SQL-file option family of the pinned driver (connect-time fetch-and-run SQL) |
| ORACLE | reviewed set of the pinned ojdbc when built with `-Poracle` (class-loading and file-path properties at minimum) |

Under `properties.hikari`, `exceptionOverrideClassName` joins the server-managed refusal set (arbitrary class instantiation). The refusal sets are part of every dialect adapter's contract — an adapter without a reviewed set is a defect, and the validation path must fail **closed** (an unknown or non-conforming adapter yields no exemption from refusal, never an empty set).

**Embedded in-process dialects harden at the adapter, not just the refusal set (normative).** DuckDB and SQLite run **inside the server JVM**, so author-authored SQL against such a datasource executes in-process — a loaded native extension is arbitrary code in the server, not in a remote database. The refusal set governs `properties.jdbc`/`jdbc_url` keys, but DuckDB **autoloads** known/community extensions with no property involvement at all (`allow_community_extensions` and `autoload_known_extensions` default `true`). Therefore `DuckdbDialectAdapter.defaultProperties` sets, at connect (exact set verified against the pinned driver): `allow_unsigned_extensions=false`, `allow_community_extensions=false`, `autoload_known_extensions=false`, `autoinstall_known_extensions=false`, `enable_external_access=false`. The **load-bearing lock is `enable_external_access=false`**: it is non-overridable by session SQL (a `SET … = true` from author SQL fails — "cannot enable external access while database is running"), and with the filesystem and network off, no `INSTALL`/`LOAD`/`ATTACH`/`read_csv`/`COPY` path is reachable regardless of the autoload toggles. (Verified: `autoload_known_extensions`/`autoinstall_known_extensions` remain settable at runtime, but are **inert** — every actual load path is closed by the external-access lock; a `LOAD json` succeeds only because that extension is statically linked into the pinned jar, not fetched.) These five keys are **additionally refused in `properties.jdbc` / `jdbc_url`** (the DUCKDB entry of the §5.6 refusal set) — because `properties.jdbc` is applied *after* `defaultProperties` (§4.2), an operator could otherwise set `enable_external_access=true` and re-open the RCE surface; for an in-process engine that operator foot-gun is refused, not merely defaulted. `SqliteDialectAdapter.defaultProperties` sets, at connect: `enable_load_extension=false` (explicit hardening; already the driver default) and `limit_attached=0`. The **load-bearing lock is `limit_attached=0`**: this sets `SQLITE_LIMIT_ATTACHED` via the xerial driver's `sqlite3_limit()` call, which runs before author SQL and prevents any `ATTACH DATABASE` — a filesystem-access primitive that would let an attacker open and query any file on the server filesystem. Both keys are **additionally refused in `properties.jdbc` / `jdbc_url`** (the SQLITE entry of the §5.6 refusal set) so an operator cannot set `limit_attached=10` and re-open the surface. This is the datasource analogue of Staging §9.5's de-privileging: an in-process engine must not give author SQL — or an operator's `properties.jdbc` — a code-execution or filesystem-access primitive.

---

## 6. Datasource Registry

### 6.1 Interface

```kotlin
interface DatasourceRegistry {
    fun list(dialect: Dialect? = null): List<Datasource>   // optional filter backs GET /datasources?dialect= (§11)
    fun get(name: String): Datasource?       // null if not registered or soft-deleted
    fun exists(name: String): Boolean
    fun save(datasource: Datasource, actor: UUID): Datasource   // create or update; validates first (§5.4, §9). actor: created_by (Metadata DB §4.10) + the §7.4 audit actor
    fun delete(name: String): DeleteResult         // soft delete; fails if in use
    fun poolFor(datasource: Datasource): ConnectionPool   // lazy, thread-safe (§5.2)
    fun testConnection(name: String): TestResult?  // null = no such datasource (caller maps to 404); §8.1's "HTTP 200 always" applies only when it exists
    fun validate(datasource: Datasource): ValidationResult
}
```

(v1.4: `save` gained the required `actor` parameter — `created_by` is `NOT NULL` and §7.4 audit events record an actor, so the v1.1 signature was unimplementable; `list` gained the optional dialect filter §11 already promises; `testConnection` returns `null` for an unknown name instead of a synthetic failed `TestResult` whose `errorClass` was not an FQCN.)

Return types:

```kotlin
/** Outcome of a soft delete. Never throws for the in-use case — the caller needs the list. */
data class DeleteResult(
    val deleted: Boolean,
    val name: String,
    val errorCode: String? = null,          // 'datasource.in_use' when deleted = false
    val referencingPipelines: List<String> = emptyList()   // pipeline ids blocking the delete
)

/** Outcome of a live connectivity probe (§8.1). Failure is data, not an exception. */
data class TestResult(
    val connected: Boolean,
    val testedAt: Instant,
    val latencyMs: Long? = null,            // present when connected
    val serverVersion: String? = null,      // DatabaseMetaData.getDatabaseProductVersion(), when connected
    val error: String? = null,              // message, when not connected
    val errorClass: String? = null          // exception FQCN, when not connected
)

/** Outcome of save-time validation (§5.4, §9). Also returned by DialectAdapter.validateJdbcUrl. */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError> = emptyList()
) {
    data class ValidationError(
        val code: String,                   // a §9 code, e.g. 'datasource.validation.properties_invalid'
        val field: String?,                 // JSON pointer-ish path, e.g. 'properties.hikari.maximumPoolSize'
        val message: String                 // human-readable; safe to surface (never contains credentials)
    )
    companion object { fun ok() = ValidationResult(true) }
}
```

`ValidationResult.errors` is **complete, not first-failure** — a save returns every rule that failed so the UI can render one form pass. `TestResult.error` and `ValidationResult.ValidationError.message` are redaction-scrubbed: neither ever carries `password` or the credential portion of a JDBC URL (redaction rules: [Observability spec](observability.md)).

### 6.2 In-use check on delete

`DELETE /api/v1/datasources/{name}` fails with `datasource.in_use` if any non-deleted pipeline references this name. The error response includes the list of pipelines using it (`DeleteResult.referencingPipelines`), so the operator can clean up.

### 6.3 Cache

- Datasource metadata cached in memory (low churn).
- Cache invalidated on create/update/delete — **local instance only**.
- Entries carry a **short TTL** (default 60s, matching the auth liveness cache) so a change made on one instance becomes visible on every other instance within the TTL. The local invalidation is an immediacy optimization for the instance that made the change; the TTL is what bounds cross-instance staleness in the multi-instance deployment model (auth §8) — without it, an operator repointing a datasource would be invisible to sibling instances until restart.
- **Negative lookups are never cached** (a miss re-reads), so the cache cannot be grown by `GET`s for non-existent names and is bounded by the number of real datasources.
- Connection pools cached separately (lazy init, see §5.2); the pool-build path bypasses this cache (it must reload the encrypted credential).

---

## 7. Credential Storage

### 7.1 Encryption at rest

Passwords are **never** stored in plaintext. Encryption approach:

- **AES-256-GCM** with a single master key. Stored value = nonce ‖ ciphertext ‖ auth tag; a fresh random 96-bit nonce per encryption.
- **The datasource `name` is bound as GCM associated data (AAD)** on both encrypt and decrypt. A stored ciphertext therefore decrypts only under the name it was sealed with: a row copied or renamed at the database level fails the tag rather than silently decrypting, closing the lift-a-ciphertext-to-another-row attack (`name` is immutable, §11.1, so this never obstructs legitimate use). Any code path that decrypts — pool build, connection test, and the §7.3 rotation pass — MUST pass the row's name as AAD.
- The master key has exactly **one source**: the `datapipelines.db.encryption-key` config key (`DATAPIPELINES_DB_ENCRYPTION_KEY`), defined in [Configuration §2](configuration.md#2-required-configuration) — exactly 32 bytes, base64-encoded.
- The key is **required and fail-fast**: if it is missing, not valid base64, or not exactly 32 bytes, the application **does not start**. There is no fallback chain — no KMS lookup, no generated key file.

**Why no fallback.** A silently generated key file is how credentials become undecryptable on the next redeploy (new container, new file, every stored password now garbage) — the failure appears long after the deploy that caused it, and no backup of the metadata DB can repair it. Refusing to start is the cheap failure. KMS-sourced keys (AWS KMS / GCP KMS / HashiCorp Vault) are a deliberate **v1.1** item, tracked in [ROADMAP §2](ROADMAP.md#2-v11-candidates) — when added, KMS becomes an *alternative explicit* source, never an implicit fallback.

### 7.2 Schema

**DDL authority: [Metadata DB §4.10](metadata-db.md#410-datasources).** No DDL block lives in this spec — `metadata-db.md` is the only doc that writes DDL, so there is exactly one table definition to keep true.

The semantics this spec depends on, which the DDL must satisfy:

- `name` is the **primary key** (`TEXT`), constrained to 63 characters and to the identifier regex of §9 via `CHECK` — pipelines reference datasources by this value, so it is also the immutability anchor (§11.1).
- `description` is **optional** (nullable / no `NOT NULL` requirement) — matching §3.3.
- `password_encrypted` is `BYTEA` — AES-256-GCM output per §7.1, never plaintext, never returned by any endpoint.
- `properties_json` is `JSONB` and holds the §5 object verbatim (`{"hikari": {...}, "jdbc": {...}}`), defaulting to `{}`.
- `dialect` is `TEXT` with a `CHECK` constraint enumerating the [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) dialect values — a database-level guard duplicating the §9 application check on purpose.
- `created_at` / `updated_at` are `TIMESTAMPTZ` (UTC); `updated_at` is set by the application in every UPDATE.
- `created_by` is a `UUID` **foreign key to `users(id)`**.
- `is_deleted` supports soft delete (§6.2); lookups filter it.

### 7.3 Key rotation

Key rotation = for each row, decrypt `password_encrypted` with the old key **using that row's `name` as AAD (§7.1)**, then re-encrypt with the new key **under the same `name` AAD**, in a single transaction. Triggered by an admin CLI / endpoint, with both keys supplied explicitly (the old key is never inferred). The `name` must be carried through both halves — a rotation that decrypts/re-encrypts without it fails every GCM tag. All pools are drained afterwards so the next build decrypts under the new key. Documented in the runbook (future).

**v1 scope:** the rotation *flow* is deferred to v1.1 ([ROADMAP](ROADMAP.md)) — no v1 surface triggers it (no REST endpoint, no CLI). What v1 ships is the primitive it needs (`CredentialEncryptor` accepts an explicit raw key, so two encryptors can coexist during a rotation pass) and the registered `datasource.key_rotation` audit event ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)), which is emitted by nothing until the flow lands.

### 7.4 Decryption points and audit log

**Decryption happens once per pool build — not once per connection lease.** HikariCP necessarily holds the credential for the pool's lifetime (it opens new physical connections on its own schedule, without the caller present), so a per-lease decrypt would be theatre: the plaintext is already resident in the pool. The credential is decrypted exactly at:

1. **Pool build / rebuild** — lazy first lease (§5.2), or a rebuild after datasource update or key rotation.
2. **Connection test** (§8.1) when it constructs a throwaway pool for a datasource with no live pool.
3. **Key rotation** (§7.3), which decrypts every row in one pass.

An audit event is written at each of those points — **never per lease**. The module emits them through an injected audit sink (a `fun interface` sibling of `DatasourceReferences`, with a no-op default so the module stays dependent on `typesystem` alone); the application wires the sink onto the shared `audit_log` writer at assembly (v1.4). The earlier "audit every lease" model produced unbounded event volume (one row per query, per node, per execution) that recorded nothing the execution record did not already contain, and it implied a decrypt that does not happen.

**`pool_build` vs `pool_rebuild` timing.** A datasource update evicts the live pool immediately (synchronously, with the operator's identity in hand) but decrypts nothing then — the lazy rebuild on the next lease is what decrypts, and it emits its own `pool_build` (actor = system, executor-initiated). So an update to a datasource with a live pool produces `pool_rebuild` (operator actor, marks the eviction) followed later by `pool_build` (system actor, marks the actual decryption). `pool_rebuild` exists to capture the operator who triggered the change — an identity that is gone by the time the lazy build runs — not to mark a decryption of its own. `actor` is the system principal for executor-initiated pool builds and the operator's user id for operator-initiated actions (update, connection test); `cause` (execution id + node id) is populated only for a `pool_build` triggered from an execution, where the executor supplies the context.

Each event records:
- Timestamp (`TIMESTAMPTZ`, UTC)
- Datasource name
- Event name: `datasource.pool_build` | `datasource.pool_rebuild` | `datasource.connection_test` | `datasource.key_rotation` (registered in [Enums §15](enums.md#15-authauditevent--auth-audit-log-events) alongside the auth audit events)
- Actor (user id for operator-initiated actions, or the system principal for executor-initiated pool builds)
- Cause, when the trigger is `pool_build` from an execution: the execution id and node id that took the first lease

Stored in the audit log table ([Metadata DB §4.3](metadata-db.md#43-audit_log)); retained per `datapipelines.audit.retention-days` ([Configuration §3.12](configuration.md#312-audit)).

Per-node datasource *usage* remains observable without any credential-audit event: it is already recorded on the execution and its per-node stats.

---

## 7A. Schema Introspection

Shipped in v1.1 (was datasources §14 future work). Read-only live schema metadata over a registered datasource's JDBC `DatabaseMetaData`, so agents can enumerate real schemas, tables and columns instead of hallucinating them when authoring SQL templates.

Three read operations, all served by the module's `SchemaIntrospector` through the existing `DatasourceRegistry` pool (`poolFor`, §5.2 — introspection opens a live connection, exactly like a connection test). They form the **only introspection flow**: schemas → tables → columns — list the schemas, list one schema's tables, then read columns for only the tables the SQL needs. Nothing bundles columns into a table listing; table listings stay lightweight so more tables fit in one response.

| Operation | Returns | Notes |
|---|---|---|
| Schemas | `{"schemas": ["name", ...], "truncated": bool}` | The flow's entry point: the driver-reported schema names as a plain list, engine system schemas excluded. On MySQL the databases arrive as JDBC catalogs (Connector/J defaults), so the listing reads `getCatalogs()`/TABLE_CAT — the same [DialectAdapter.schemaArrivesInCatalog] routing the other operations use; `getSchemas()` there reports a single blank schema. **An empty list is a valid result**, not an error: a schemaless dialect (SQLite, single-db DuckDB) has no schemas to list. `getSchemas()` carries no remarks, so none are returned. Capped at **2000 schemas** (`truncated: true` when the cap dropped any) — the listing walks `getCatalogs()`/`getSchemas()` under the pooled lease, and on MySQL catalog routing that is every database the server grants, so the walk and the payload are bounded like the tables listing. |
| Tables | `{"tables": [{schema, name, type, remarks?}], "truncated": bool}` | Tables and views; `type` is the driver's raw JDBC table type (`TABLE`, `VIEW`, `BASE TABLE`, ...); `remarks` is the engine-stored comment (JDBC REMARKS), omitted when the driver/database has none. Optional schema filter; without one the listing **spans schemas** — pass each table's reported `schema` to the columns operation (a datasource that reports **no current schema** — e.g. a database-less MySQL URL, where unfiltered would span every database the server grants — fails the unfiltered listing with the catalogued `pipeline.execution.parameter_required` instead; the caller lists schemas and passes one). Capped at **2000 tables**; `truncated: true` when the cap dropped some. Nothing bundles columns into this listing — it stays lightweight so more tables fit in one response; columns are read per table. |
| Columns (one table) | `[{name, type, precision?, scale?, nullable?, source_type, warnings, remarks?}]` | `type` is the canonical Type System type, mapped through the dialect's ingress type mapper ([Type System §5](type-system.md#5-source-to-canonical-mapping-tables)); `source_type` is the driver's own type name; `warnings` carries the mapper's warning messages (§8.2/§10.5), empty when the mapping was clean; `remarks` is the engine-stored column comment (JDBC REMARKS), omitted when there is none. Pass the table name exactly as the tables operation returned it — JDBC metadata name matching is case-sensitive. System-schema rows are excluded. Without a schema filter the read defaults to the connection's **current schema** (routed per dialect like an explicit filter — see below), so same-named tables in different schemas cannot merge their columns; a datasource that reports **no current schema** (or the JDBC blank sentinel, which means "objects without a catalog/schema", not a schema named `""`) cannot honor that default, and the unfiltered read it would fall back to is exactly the merge the contract forbids — such a read **fails** with the catalogued `pipeline.execution.parameter_required` (the caller lists schemas and passes one explicitly; the schemas operation keeps its unfiltered-minus-system listing, which is how the caller recovers). The schemaless dialects (SQLite: no JDBC schema dimension at all, so same-named tables cannot exist in different schemas) are the deliberate exception and keep the unqualified read. |

The table-type vocabulary and the system-schema exclusion are **per-dialect properties on the `DialectAdapter`** (next to the type mapper): every dialect that has an `information_schema` excludes it (case-insensitive); Postgres additionally lists `PARTITIONED TABLE`, `MATERIALIZED VIEW` and `FOREIGN TABLE`, and excludes `pg_catalog` as well. System catalogs that report under the dedicated JDBC types (`SYSTEM TABLE`, `SYSTEM VIEW`) are kept out by the type vocabulary itself — but that mechanism has holes: some engines report their system schemas under the plain types (MySQL's Connector/J reports `sys`, `performance_schema` and `mysql` as ordinary TABLE/VIEW rows), which is why the per-dialect schema lists carry more: MySQL excludes `mysql`, `performance_schema`, `sys`; Oracle excludes `SYS`, `SYSTEM`, `OUTLN`, `XDB`, `CTXSYS`, `MDSYS`, `ORDSYS`, `DBSNMP`, `WMSYS`, `AUDSYS`, `OLAPSYS`, `XS$NULL` and `APEX_*` (a prefix entry — Oracle versions its APEX schemas, e.g. `APEX_240100`); SQL Server excludes `sys` alongside `INFORMATION_SCHEMA`, plus the built-in fixed-role/special schemas every SQL Server database carries (`db_owner`, `db_accessadmin`, `db_securityadmin`, `db_ddladmin`, `db_backupoperator`, `db_datareader`, `db_datawriter`, `db_denydatareader`, `db_denydatawriter`, `guest` — `dbo` is deliberately NOT excluded: it is the database's default user schema); DuckDB, being Postgres-lineage, excludes `pg_catalog` beside `information_schema`. The MSSQL and DuckDB lists are floors, deliberately known-incomplete exactly like Oracle's — no arm64 containers exist for either dialect (pre-existing), so both are unit-verified against mocked metadata rather than container-verified. **These lists are a floor, explicitly known-incomplete** — they name the schemas the pinned drivers verifiably report as plain user rows, not every schema an engine ships; extending them is additive.

**The floors can over-exclude, and `introspection_include_schemas` is the escape hatch.** A prefix entry cannot tell the engine's schemas from a customer's own: any Oracle schema starting `APEX_` — including a team's own `APEX_REPORTING` reporting schema — is invisible to all three operations, with no warning, and the authoring agent is then told the data does not exist. A datasource registered with `introspection_include_schemas: ["apex_reporting"]` (§3.3) exempts exactly that name from the exclusion in all three operations — every other floor entry, including the rest of the `apex_*` family, stays hidden. Exact names only, no patterns (the prefix language belongs to the floors; an allowlist pattern would look like it exempts a family while exempting nothing — rejected at save); lowercase-normalized at bind; matching case-insensitive like the exclusion itself; absent/empty = today's behavior.

Identifier routing is a dialect property too ([DialectAdapter.schemaArrivesInCatalog]): on Connector/J defaults the database arrives in the JDBC **catalog** (TABLE_CAT) and TABLE_SCHEM is null, so for MySQL the schema filter routes to the catalog argument of `getTables`/`getColumns` and TABLE_CAT is read as the schema — otherwise a filter selects nothing and every table reports a null schema.

Rules:

- **Scope: `author`** on every surface (REST and MCP), matching the [§8.1](#81-post-api-v1-datasourcesnametest) connection-test precedent — introspection opens a live connection against a production datasource, and its stated consumer (authoring agents) holds `author` ([Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)).
- **Read-only by construction**: only `DatabaseMetaData` calls, no statements.
- **`table` and `schema` filters are exact-match identifiers, not LIKE patterns** — `_` and `%` in a name are escaped with the driver's `getSearchStringEscape()`, so a filter for `order_items` cannot match a sibling table like `order1items`. The escape applies only to the true pattern arguments (`schemaPattern`, `tableNamePattern`); the JDBC **catalog argument is a literal** ("must match the catalog name as it is stored") and is never escaped — an escaped catalog would match nothing for a MySQL database whose stored name carries `_`/`%`.
- **An unknown table or schema filter is not an error** — it matches nothing and returns an empty list (the house filter philosophy; see `datasources_list`'s dialect filter in [MCP §6.2.10](mcp-server.md#6210-datasources_list)).
- **An unknown datasource name is `datasource.not_found`** ([Pipeline Contract §13.8](pipeline-contract.md#138-datasource)).
- **A connection failure during introspection is `pipeline.execution.datasource_unreachable`** ([Pipeline Contract §13.8](pipeline-contract.md#138-datasource); HTTP 502 on REST, an `isError` envelope on MCP) — a customer database being down is not a server error: no raw 500, no `-32603`, logged at WARN without a stack. The translation happens at the introspector's lease boundary and covers **both** failure families: the `SQLException` of a refused/timed-out lease or a connection that died mid-read, and the RuntimeException family of pool construction (`PoolInitializationException` at first lease on a down database, a missing driver class, a property rejected at parse time). Post-lease the SQLException translation narrows to the **connection family only** — SQLState class 08 (checked on the exception itself and along its `cause`/`nextException` chains, because some drivers carry the state only on a wrapped exception), the JDBC connection-exception subclasses, `SQLRecoverableException`, `SQLTimeoutException`, and SQLite's connection-loss result codes (`BUSY`, `IOERR`, `CANTOPEN`, `NOTADB` — the vendored driver reports `SQLiteException` with a null SQLState, so the state-based branches cannot see it; classification is by primary code, never a blanket "null SQLState means down"); any other `SQLException` from a metadata read is a defect in this module or a driver bug and propagates as-is rather than being masked as "database unreachable". Driver text never reaches the wire (the caller can run the §8.1 connection test for the scrubbed failure detail).
- Credentials are never part of any introspection payload — the operations read schema metadata only.

Surfaces: REST `GET /api/v1/datasources/{name}/schemas`, `/tables`, `/tables/{table}/columns` ([REST API §9.7](rest-api.md#97-schema-introspection)); MCP `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns` ([MCP §6.2.16–18](mcp-server.md#6216-datasources_get_schemas)).

---

## 8. Connection Testing

### 8.1 `POST /api/v1/datasources/{name}/test`

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

Note: connection test failure is **not** an HTTP error. The caller asked "can I connect?" and got an honest answer. HTTP 200 always (provided the datasource exists). The `data` object is the wire form of `TestResult` (§6.1).

### 8.2 Pre-execution check

Before pipeline execution begins, the executor pre-checks that every datasource referenced by the pipeline's nodes is configured and reachable. Failures here abort before any node runs, with error code `pipeline.execution.datasource_unreachable` (registered in the central catalog, [Pipeline Contract §13.8](pipeline-contract.md#138-datasource) — HTTP 502).

Pre-check is a fast `SELECT 1` (or dialect-equivalent) against each datasource. Cost: milliseconds per datasource, parallelizable.

### 8.3 Background health checks (optional)

In v1.1+, the system can poll datasources on a schedule and surface health in the UI. Not in v1.

---

## 9. Validation Rules

Every rule below runs on **create and update**, before the row is written (§2 principle 7; [Pipeline Contract §2](pipeline-contract.md#2-design-principles)). All failures are collected, not short-circuited (§6.1 `ValidationResult`). HTTP mappings live in the central catalog, [Pipeline Contract §13.8](pipeline-contract.md#138-datasource).

| Code | Check |
|---|---|
| `datasource.validation.name_invalid` | `name` matches `[a-z0-9_-]+`, length 1–63 |
| `datasource.validation.dialect_invalid` | `dialect` is a value of the [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) dialect set |
| `datasource.validation.jdbc_url_malformed` | URL parses, matches the dialect's expected pattern (`DialectAdapter.validateJdbcUrl`), and carries no server-managed, refused (§5.6), or credential key in its query/property segment |
| `datasource.validation.jdbc_url_scheme_invalid` | URL begins with `jdbc:{dialect}:` |
| `datasource.validation.password_missing` | `password` required on create |
| `datasource.validation.properties_invalid` | The **test pool build** (§5.4) succeeded: `properties.hikari.*` names/values are accepted by `HikariConfig`, `properties.jdbc.*` is a flat string map, no server-managed key (`jdbcUrl`, `username`, `password`, `driverClassName`, `dataSourceClassName`, `poolName`, `exceptionOverrideClassName`, …) is present under `hikari`, no refused key (§5.6) or server-managed/credential key is present under `jdbc`, and `properties` contains no namespace other than `hikari` / `jdbc`; and `introspection_include_schemas`, when present, lists non-blank schema names without wildcard patterns (§3.3). The offending key and the underlying Hikari/driver message are returned in `details`. |
| `datasource.validation.query_timeout_invalid` | `query_timeout_seconds`, when present, is an integer ≥ 1 |
| `datasource.validation.duplicate_name` | Create with a name that already exists. `name` is the PRIMARY KEY ([Metadata DB §4.10](metadata-db.md#410-datasources)), so uniqueness is GLOBAL including soft-deleted rows — a deleted datasource's name is not reusable until hard-deleted (corrected 2026-08-08: pipelines reference datasources by name, so silent reuse would repoint history; consistent with pipeline `duplicate_name`). No reactivate-on-recreate path in v1. |
| `datasource.driver_not_loaded` | The JDBC driver class for `dialect` is on the classpath (§10.3) |

`datasource.driver_not_loaded` is deliberately **not** in the `datasource.validation.*` namespace: it reports a deployment/packaging state (a missing driver JAR), not a defect in the submitted entity — the same payload becomes valid after the operator rebuilds with the right profile. The code is canonical in [Enums §16](enums.md#16-error-code-domains-prefix-catalog) / [Pipeline Contract §13.8](pipeline-contract.md#138-datasource).

`datasource.in_use` (delete blocked by referencing pipelines, §6.2) is a lifecycle error rather than a save-time rule, and is likewise catalogued in §13.8.

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

At datasource create/update time, validation calls `isAvailable(dialect)` and rejects with **`datasource.driver_not_loaded`** if the driver JAR is missing. This check runs *before* the test pool build (§5.4) — a missing driver would otherwise surface as a confusing `properties_invalid`.

---

## 11. CRUD Operations

Wire contracts (envelopes, status codes, examples) live in [REST API §9](rest-api.md#9-datasource-endpoints); required scopes live in the [Auth §7.6 scope matrix](auth.md#76-scope--operation-matrix-authoritative) (read = `read`, test = `author`, create/update/delete = `admin`). This table is the operation inventory.

| Operation | Method & Path | Notes |
|---|---|---|
| Register datasource | `POST /api/v1/datasources` | Validates (§9) + test pool build (§5.4), encrypts password, stores. |
| List datasources | `GET /api/v1/datasources?dialect={d}` | Passwords never included. |
| Get datasource | `GET /api/v1/datasources/{name}` | Password replaced with `password_set: bool`. |
| Update datasource | `PUT /api/v1/datasources/{name}` | Password optional (omit to keep existing). `name` may not change (§11.1). Drains & rebuilds pool. |
| Delete datasource | `DELETE /api/v1/datasources/{name}` | Soft delete; fails with `datasource.in_use` if referenced. |
| Test connection | `POST /api/v1/datasources/{name}/test` | Returns a `TestResult` (§6.1); HTTP 200 even when `connected: false`. |

### 11.1 Rename semantics — `name` is immutable

`name` is the primary key **and** the reference pipelines carry in their JSON (§2 principle 1). Renaming it would silently break every pipeline pointing at the old value, in every environment, with no write-time signal — so **there is no rename operation**:

- `PUT /api/v1/datasources/{name}` ignores no field silently: a body whose `name` differs from the path segment is rejected with `datasource.validation.name_invalid` (`field: "name"`, message stating immutability). Every other field, including `dialect`, `jdbc_url`, credentials, `query_timeout_seconds` and `properties`, is updatable.
- A rename is therefore **delete + create**: create the new datasource, repoint the referencing pipelines (a new pipeline version per [Pipeline Contract §14](pipeline-contract.md#14-pipeline-lifecycle-operations)), then delete the old one.
- The delete step is the guard that makes this safe: it is **blocked while any non-deleted pipeline references the name** (`datasource.in_use`, §6.2), so the old datasource cannot disappear until the repointing is complete. Order matters — create → repoint → delete.

---

## 12. Stability Promise

### 12.1 Frozen in v1

- Datasource entity JSON shape, including the two `properties` namespaces (`hikari`, `jdbc`).
- `name` immutability and its role as the pipeline-facing reference.
- The 7 supported dialects and their identifiers.
- The separation of pipeline-name from connection-details.
- The encryption-at-rest requirement, and the single required key source (fail-fast).
- Save-time validation including the test pool build.

### 12.2 Not frozen

- Pool implementation (HikariCP) — could swap to AGPL-licensed alternatives if license concerns arise. Note this would change the meaning of `properties.hikari.*`; a swap therefore requires a migration path, which is why the namespace is named after the pool rather than being generic.
- The exact encryption scheme (AES-256-GCM today; KMS as an additional explicit key source in v1.1 — [ROADMAP §2](ROADMAP.md#2-v11-candidates)).
- New dialects added non-breakingly.
- Which pool/driver properties are *useful* — the passthrough model means no spec change is needed to adopt new ones.

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
- Passthrough tests: a valid `properties.hikari` entry reaches `HikariConfig` verbatim; an unknown key, a wrong-typed value, and a server-managed key each fail the test pool build with `datasource.validation.properties_invalid` and name the offending key.
- Test pool build does **not** require a reachable database (`initializationFailTimeout = -1`): saving a datasource pointing at a dead host succeeds; `POST .../test` on it returns `connected: false`.
- Pool concurrency: N coroutines calling `poolFor()` simultaneously for a cold datasource construct exactly one `HikariDataSource`.
- Query-timeout precedence: datasource `query_timeout_seconds` set → used; unset → global `node-query-timeout-seconds`.
- Immutability: `PUT` with a differing `name` is rejected; delete while referenced returns `datasource.in_use` with the pipeline list.
- Startup: a missing / malformed / wrong-length `DATAPIPELINES_DB_ENCRYPTION_KEY` fails application startup (no fallback path exists to test).
- Integration tests via Testcontainers: spin up real PG/MySQL/MSSQL/Oracle containers, register a datasource, test connection, run a query, verify type mapping matches [Type System §5](type-system.md#5-source-to-canonical-mapping-tables).
- Credential encryption tests: encrypt/decrypt round-trip, key-rotation flow, tamper detection (auth tag failure).
- Connection pool tests: lease timeout, max-pool-size enforcement, eviction on datasource delete.

---

## 14. Open Questions / Future Additions

Out of scope for v1 (v1.1 candidates are tracked in [ROADMAP §2](ROADMAP.md#2-v11-candidates)):

- **KMS integration**: AWS KMS / GCP KMS / HashiCorp Vault as an additional **explicit** master-key source (never an implicit fallback — §7.1).
- **Background health checks**: scheduled polling of datasources with UI health indicators.
- **Datasource groups / failover**: pair primary + replica, fail over on connection failure.
- **Read-only enforcement**: some datasources should be read-only by contract (we never write to sources, but enforcing at the datasource level adds defense).
- **SSH tunnel / bastion host support**: for datasources reachable only via bastion. Common in enterprise.
- **OAuth / IAM auth for cloud databases**: Snowflake, BigQuery (when those dialects are added).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial datasources spec: entity, dialect adapters, pool config, credential encryption, driver packaging strategy |
| 2026-08-07 | v1.1 | consistency campaign | Applied [SPEC-REVIEW-2026-08 §2.9](SPEC-REVIEW-2026-08.md#29-datasourcesmd): encryption key required + fail-fast, typo fixed, master-key fallback chain deleted (KMS → ROADMAP) [D8]; `properties` becomes `hikari`/`jdbc` passthrough maps validated by a test pool build [D7/D2]; §7.2 DDL replaced by a pointer to metadata-db [D4]; `description` optional; `datasource.driver_not_loaded` renamed + added to §9, `datasource_unreachable` linked to the central catalog [D5]; §7.4 per-lease decrypt/audit corrected to per pool build; `DeleteResult`/`TestResult`/`ValidationResult` field lists; `poolFor()` thread-safety; query-timeout precedence stated once; §11 paths get `/api/v1` + `name` immutability and rename procedure |
| 2026-08-08 | v1.2 | P3 build | §9 name uniqueness made GLOBAL — includes soft-deleted rows; recreating a deleted name is rejected with `datasource.duplicate_name`, never reactivates the old row. (Row recorded retroactively 2026-08-09 — the amendment landed in commit 1b07b49 without its Change Log row.) |
| 2026-08-09 | v1.3 | P3 build (Gate C testing review) | §7.3 v1-scope note: the key-rotation *flow* is deferred to v1.1 (no v1 trigger surface); v1 ships the encryptor primitive and the registered-but-unemitted `datasource.key_rotation` audit event. |
| 2026-08-09 | v1.4 | P3 build (Gate C security + API reviews) | New **§5.6 refused property keys**: the bounded normative exception to §2's passthrough principle — class-loading / file-path / connect-time-SQL / TLS-switch keys refused in BOTH carriers (`properties.jdbc.*` and the `jdbc_url` query segment, same union), credentials refused in the URL outright, per-dialect minimum sets tabled, fail-closed adapter contract; §9 rows updated to reference it. §6.1: `save` gains required `actor` (created_by + §7.4 audit actor — v1.1 signature was unimplementable), `list` gains optional `dialect` filter, `testConnection` returns `null` for unknown names. §7.4: audit events emitted through an injected sink (no-op default), wired by the app. |
| 2026-08-09 | v1.5 | P3 build (Gate C API re-review) | Doc-sync of behavior the fix cycle added: §4.2 `DialectAdapter` gains `refusedPropertyKeys` (DS-API-12); §7.1 records the datasource-`name`-as-GCM-AAD binding and §7.3 rotation recipe now carries it through both halves (DS-API-13 — a literal reading of the old recipe would fail every tag); §7.4 states the `pool_build`/`pool_rebuild` timing + actor/cause rules, enums §15 gloss matched (DS-API-10). |
| 2026-08-09 | v1.9 | P3 build (sqlite ATTACH hardening) | §5.6: `SqliteDialectAdapter.defaultProperties` now sets `limit_attached=0` at connect (closes SQLite `ATTACH DATABASE` filesystem-access vector — an in-process engine must not let author SQL open and query any server file). `enable_load_extension=false` made explicit in `defaultProperties` (was driver-default only). Both keys added to the SQLITE §5.6 refusal set so `properties.jdbc` cannot override. |
| 2026-08-09 | v1.8 | P3 build (round-3 escalation) | §5.6 DuckDB hardening sharpened on empirical evidence: `enable_external_access=false` is the load-bearing, runtime-non-overridable lock (the two `autoload_*` toggles stay runtime-settable but are inert — no load path without external access); the five extension keys are ALSO refused in `properties.jdbc`/`jdbc_url` (properties override defaults §4.2, so an operator could otherwise re-open the RCE — refused, not merely defaulted). |
| 2026-08-09 | v1.7 | P3 build (datasources round-2 finding) | §5.6: **embedded in-process dialects** (DuckDB, SQLite run in the server JVM) must harden extension-loading at the adapter — `DuckdbDialectAdapter.defaultProperties` disables native-extension load + external autoload (`allow_community_extensions`/`autoload_known_extensions` default TRUE, reachable with no `properties.jdbc` at all → in-process RCE), non-overridable by session SQL. The datasource analogue of Staging §9.5. |
| 2026-08-09 | v1.6 | P3 build (Gate C security re-review) | §5.6 hardened: credential refusal now covers a **userinfo authority in any position** (Oracle `thin:user/pw@`, H2 `tcp://user:pw@`, not only `//`-prefixed) (DS-SEC-13); a **secret-valued-property category rule** refuses any `properties.jdbc`/URL key whose value is credential material via a suffix predicate (`*password`/`*passwd`/`*pwd`/`*secret`/`*clientkey` + named dialect secrets) since `properties.jdbc` is plaintext + `read`-visible like `jdbc_url` (DS-SEC-14); MySQL `allowPublicKeyRetrieval` (DS-SEC-16), MSSQL `keyVaultProviderClientKey`/`keyVaultProviderClientId`/`keyStorePrincipalId` (DS-SEC-14/18) added; TLS-switch refusals declared best-effort with the `sslmode` family operator-controlled. §6.3: metadata cache carries a short TTL (60s) for cross-instance coherence, negatives never cached (DS-SEC-15). |
| 2026-08-14 | v2.0 | v1.1 introspection build | New **§7A Schema Introspection**: three read-only `DatabaseMetaData` operations (tables / columns / 200-table-capped snapshot) served through the registry pool with dialect canonical type mapping, `author` scope on every surface, empty-list-for-unknown-filter rule, `datasource.not_found` for unknown names; §14 future-work line removed (shipped). |
| 2026-08-15 | v2.1 | surface restructure (part 1) | §7A: the **Schema snapshot operation is removed** (`datasources_get_schema` / `GET /datasources/{name}/schema`) — bundling columns into a table listing made responses heavy; the tables listing stays lightweight so more tables fit in one response, and columns are read per table. Tables row documents the lightweight rule. |
| 2026-08-15 | v2.2 | surface restructure (part 2) | §7A: new **Schemas operation** — the flow's entry point (`datasources_get_schemas` / `GET /datasources/{name}/schemas`): plain list of driver-reported schema names, system schemas excluded, `getCatalogs()` under MySQL's catalog routing, **empty list valid** on schemaless dialects. Flow declared: schemas → tables → columns; tables row now documents that the unfiltered listing spans schemas (pass each table's schema to columns). |
| 2026-08-15 | v2.3 | semantics via remarks | §7A: tables and columns rows gain `remarks` — the engine-stored comment from JDBC REMARKS, null-omitted on the wire when the driver/database has none. Schemas carry none (`getSchemas()` has no REMARKS). |
| 2026-08-15 | v2.4 | surface restructure (part 3) | §7A: the flow description made explicit — schemas → tables → columns, with `datasources_get_columns` reading only the tables the SQL needs (mirrors mcp-server §8.2's rewritten walkthrough, same commit). |
| 2026-08-15 | v2.5 | R4 hardening | §7A: Oracle's exclusion list corrected — `information_schema` removed (Oracle has no such schema) and the common Oracle-maintained set added (`CTXSYS`, `MDSYS`, `ORDSYS`, `DBSNMP`, `WMSYS`, `AUDSYS`, `OLAPSYS`, `XS$NULL`, `APEX_*` as a prefix entry for the versioned APEX schemas). The per-dialect lists are now marked a **floor, explicitly known-incomplete**. |
| 2026-08-15 | v2.6 | hardening round 3 (005 review fix-cycle) | §7A: post-lease connection-loss family widened (SQLTimeoutException; SQLite's null-SQLState SQLiteException classified by primary result codes BUSY/IOERR/CANTOPEN/NOTADB — never blanket null-SQLState; cause/nextException chains walked, bounded and cycle-safe); a blank CALLER schema filter now means absent (current-schema default / spanning listing, REST==MCP); blank remarks are omitted on the wire (Connector/J reports \"\" for uncommented rows); **unknown current schema + no schema argument fails tables()/columns()** with the reused `pipeline.execution.parameter_required` instead of an unfiltered merge (schemaless SQLite exempt — no schema dimension, nothing to merge; new `DialectAdapter.introspectionSchemaless`); the schemas listing is **capped at 2000 with a `truncated` flag** (`{\"schemas\": [...], \"truncated\": bool}` — was a bare array); MSSQL floor gains the ten built-in fixed-role/special schemas (dbo deliberately kept visible) and DuckDB gains `pg_catalog` — both floors; **new optional `introspection_include_schemas` allowlist** (§3.3) exempts exact lowercase names from the exclusion in all three operations (escape hatch for the `apex_*` over-exclusion; V2 column, patterns rejected at save); §9 `properties_invalid` row extended accordingly. |
