# Staging (H2) Specification

**Status:** v1.9 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [Configuration spec](configuration.md)
**Last updated:** 2026-08-09

---

## 1. Purpose

**Staging** is the in-memory H2 database that holds intermediate result sets during a pipeline execution. Each pipeline execution gets its own isolated H2 instance, created when the execution starts and destroyed when it ends. No state survives between executions.

This spec defines:
- The H2 instance lifecycle (create, populate, query, destroy).
- Table naming, identifier safety, and creation rules.
- Type mapping from canonical types to H2 column types.
- Streaming-in / streaming-out behavior.
- Memory management and limits.
- Cleanup guarantees.
- The Staging abstraction (interface) so future staging engines (DuckDB) can be swapped in.

**In v1, H2 is the only supported staging engine.** The abstraction is designed to allow DuckDB as an alternative in future versions.

---

## 2. Design Principles

1. **Per-execution isolation.** Every execution gets its own H2 instance. No cross-execution data leakage. No cleanup race conditions. No need for namespace prefixes.
2. **In-memory only.** H2 runs in `MEMORY` mode — no disk I/O, no persistence. The cost is RAM; the benefit is speed. Executions that exceed memory limits fail explicitly (rather than silently swapping to disk).
3. **Created-on-demand, destroyed-on-completion.** The instance is created when the executor starts and destroyed deterministically when the executor finishes, regardless of success/failure. Destruction is an explicit table drop (enumerate + `DROP TABLE`, §3.4) plus a connection close in a `finally` block — **never** a reliance on garbage collection.
4. **Tables named per the Pipeline Contract.** Tables use the exact `output.table` names declared in the pipeline (`stg_orders`, `int_revenue`). No prefixes, no UUIDs. Downstream template SQL references these names directly.
5. **Single connection in v1, explicitly serialized.** One JDBC connection per instance, guarded by a `kotlinx.coroutines.sync.Mutex`. A JDBC `Connection` does not safely serialize concurrent callers on its own, and the executor runs nodes concurrently — the mutex is the mechanism, not an implementation detail (§9).
6. **Streaming, not buffering.** Source ResultSets stream into H2 via batched inserts — constant memory regardless of result size.
7. **Every generated identifier is validated and quoted.** Table names are validated at pipeline save time; column names arrive from user-authored SQL at runtime and are validated and double-quoted before they reach any generated DDL or DML (§4.5).

---

## 3. Lifecycle

### 3.1 Creation

```kotlin
enum class StagingEngine { H2 }   // v1: H2 only; see enums.md

interface StagingFactory {
    fun create(executionId: UUID, engine: StagingEngine = StagingEngine.H2): Staging
}

class H2StagingFactory(private val config: H2StagingProperties) : StagingFactory {
    override fun create(executionId: UUID, engine: StagingEngine): Staging {
        require(engine == StagingEngine.H2) { "v1 supports only StagingEngine.H2" }
        val jdbcUrl = "jdbc:h2:mem:exec_${executionId};MODE=PostgreSQL"
        // Two-phase, non-admin operational connection (§9.5). Bootstrap sa creates the
        // in-memory DB + a restricted user; the OPERATIONAL connection is opened as that
        // user BEFORE the bootstrap closes (a bootstrap closing first would take the DB
        // with it — §3.1 last-connection semantics), then the bootstrap is closed.
        val operational = openRestrictedConnection(jdbcUrl)   // authenticates as STAGING_EXEC
        return H2Staging(executionId, operational, config)
    }
}
```

The signature is shared with [DAG Executor §9](dag-executor.md#9-tempdb-lifecycle-integration) — that doc's call site is canonical; this spec conforms to it. An unsupported engine (e.g. `DUCKDB` requested but not on the classpath) fails with `pipeline.staging.engine_unavailable`; any other creation failure fails with `pipeline.staging.creation_failed` (§7.2).

Key points:
- **JDBC URL**: `jdbc:h2:mem:exec_{execution_id}` — per-execution, isolated, in-memory.
- **No `DB_CLOSE_DELAY`.** Default H2 semantics apply: the in-memory database exists only while at least one connection to it is open, and is discarded when the last connection closes. This is exactly the lifetime we want (§3.4). `DB_CLOSE_DELAY=-1` would keep the database alive **until JVM exit**, which in a long-lived server is an unbounded leak — one abandoned staging DB per execution, forever.
- **`MODE=PostgreSQL`**: H2's PostgreSQL compatibility mode. Makes H2's SQL syntax closer to PG (which most users know), enables some PG-specific functions. **This is a SQL-syntax choice, not a type-system choice** — H2 still uses its own type system internally; we map canonical → H2 explicitly per [Type System §6](type-system.md#6-h2-staging-type-mapping-canonical--h2).
- **The operational connection is a non-admin user, not `sa`** (§9.5). A transient `sa` bootstrap creates the database and the restricted user, then closes; author SQL runs de-privileged so it cannot reach the host. (The bootstrap `sa` itself keeps its empty password for the DB's lifetime — acceptable because author SQL can never open a *new* connection to reclaim it: the functions that would let it, `LINK_SCHEMA`/`CREATE ALIAS`, are exactly what the restricted user is refused. The containment target is author SQL, not arbitrary in-JVM code, which is already game-over independent of staging.)
- **Memory limit resolution.** The effective per-execution limit is the pipeline's `settings.tempdb.config.max_memory_mb` ([Pipeline Contract §5.1](pipeline-contract.md#51-settingstempdb--staging-engine-configuration)) when present, otherwise the global `datapipelines.staging.h2.max-memory-mb` ([Configuration §3.3](configuration.md#33-staging-tempdb)). The factory resolves this once at creation and stores it on the instance; nothing re-reads global config mid-execution.

### 3.2 Population

For each node whose `output.target` is `tempdb`, the executor stages the source ResultSet:

```kotlin
suspend fun stage(resultSet: ResultSet, tableName: String, sourceDialect: Dialect): StageResult = mutex.withLock {
    val metadata = resultSet.metaData
    val indices = 1..metadata.columnCount

    // Column names come from user SQL — validate before they touch generated DDL (§4.5).
    val columnNames = validateColumnNames(indices.map { metadata.getColumnLabel(it) })

    // CRITICAL: the SOURCE dialect's mapper, not H2's. A Postgres/Oracle/MySQL source's
    // JDBC type codes and type names mean different things than H2's — Oracle DATE (91) is a
    // TIMESTAMP, MySQL bit(n>1) is BINARY, etc. Mapping source metadata through H2IngressMapper
    // silently picks the wrong H2 storage type and loses data (e.g. Oracle DATE's time
    // component) BEFORE egress re-derivation can see it. The executor knows the dialect from
    // node.source; for a tempdb→tempdb node it passes Dialect.H2.
    val dialectMapper = TypeMappers.forDialect(sourceDialect)
    // mapColumn (not map) so an unknown source type's §8.2 warning names the column.
    val mapped = columnNames.mapIndexed { j, name ->
        dialectMapper.mapColumn(name, metadata.getColumnType(j + 1), metadata.getPrecision(j + 1),
            metadata.getScale(j + 1), metadata.getColumnTypeName(j + 1))    // → MappedColumn(column, warnings)
    }
    val warnings = mapped.flatMap { it.warnings }   // surfaced on StageResult; dag rolls them into the execution result
    val columns: List<ColumnSchema> = columnNames.zip(mappings) { name, m -> m.toColumnSchema(name) }

    val h2ColumnDecls = columns.map { c -> "\"${c.name}\" ${H2EgressMapper.toH2Type(c)}" }

    createTable(tableName, h2ColumnDecls)                       // rejects a duplicate table — §4.5
    val rowsStaged = batchInsert(tableName, columns, mappings, resultSet)
    checkMemoryBudget()                                          // §8.2

    StageResult(tableName, rowsStaged, columns)
}
```

`StageResult.columns` is `List<ColumnSchema>` — the canonical, wire-facing descriptor defined in [Type System §7.1](type-system.md#71-column-descriptor-json-schema). `LogicalTypeMapping` is the internal ingress artifact (source JDBC type + precision/scale + resolved canonical type); it stays inside the staging layer and is never returned across the interface.

### 3.3 Querying

For nodes with `source: "tempdb"`, the executor runs the rendered SQL through `withQuery`, which holds the serialization lock for the **entire** consumption of the cursor — creation, execution, and the caller's row-by-row drain — so the cursor is never read while the lock is free:

```kotlin
suspend fun <T> withQuery(sql: String, block: suspend (ResultSet) -> T): T = mutex.withLock {
    connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
        stmt.setQueryTimeout(config.queryTimeoutSeconds)
        stmt.fetchSize = config.resultBatchSize
        block(stmt.executeQuery(sql))
    }
}
```

The `block` is where the executor does the downstream work:
- A downstream node's stage operation (streaming into a new H2 table).
- The caller node's result capture ([Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node)) — materialized to the result store per [REST API §7](rest-api.md#7-result-delivery). That materialization is suspending Redis I/O, and it runs **inside** the lock: on a single shared connection, correctness requires no other statement execute against that connection until the cursor is fully drained.

This closes by construction the interleaving §9.2 warns about — earlier drafts returned a live `ResultSet` and relied on the caller's discipline to consume it before the next staging op, a guarantee the type system could not enforce (v1.5). The cost is that a large caller-node drain serializes staging for its duration; that is the §9.3 single-writer trade, and the caller node is typically terminal, so little else contends.

### 3.4 Destruction

```kotlin
class H2Staging(
    override val executionId: UUID,
    private val connection: Connection,
    private val config: H2StagingProperties,
) : Staging {
    private val mutex = Mutex()

    override fun close() {
        try {
            // Non-admin cleanup: DROP ALL OBJECTS requires admin in H2 2.3.232 (§9.5), so
            // enumerate this schema's tables from INFORMATION_SCHEMA and DROP TABLE each —
            // both available to the restricted user. This is a belt anyway (see below).
            dropAllStagedTables(connection)
        } catch (e: SQLException) {
            log.warn(
                "tempdb table cleanup failed for execution {}: {}",
                executionId, e.message,
            )   // pipeline.staging.cleanup_failed — logged, never rethrown from close()
        } finally {
            try {
                connection.close()   // last connection closing destroys the in-memory DB
            } catch (e: SQLException) {
                log.warn("tempdb connection close failed for execution {}: {}", executionId, e.message)
            }
        }
    }
}
```

Two independent mechanisms, in order:

1. **Enumerate-and-drop** (`INFORMATION_SCHEMA.TABLES` → `DROP TABLE` per table) — releases every staged table's memory immediately and deterministically, before the connection close. Belt. (`DROP ALL OBJECTS` would be simpler but is admin-gated in H2 2.3.232, and the staging user is non-admin by §9.5; the enumerate-and-drop uses only non-admin operations.)
2. **`connection.close()`** — with default close semantics (§3.1), closing the only connection destroys the in-memory database itself. Braces. This is the primary guarantee; step 1 only accelerates memory release within a long-lived JVM.

Neither step depends on garbage collection. `close()` never throws: it is invoked from the executor's `finally` block, where an exception would mask the execution's real failure. A failed cleanup is logged and surfaces as `pipeline.staging.cleanup_failed` (§7.2) in the execution's error detail when the execution is otherwise successful.

### 3.5 Lifecycle guarantee

The H2 instance **cannot outlive the execution**:

- **Opened at execution start.** The executor calls `StagingFactory.create(executionId)` and holds the single connection open for the whole execution — including the periods when no node is touching staging. This is what keeps the database alive: default H2 semantics discard an in-memory DB the moment its last connection closes, so a "open a connection per operation" model would destroy the staged tables between nodes.
- **Held in a local** inside `PipelineExecutor.execute(...)`; no reference escapes to a long-lived object, cache, or registry.
- **Closed in the `finally`** of that same function ([DAG Executor §5](dag-executor.md#5-execution-lifecycle), [§9](dag-executor.md#9-tempdb-lifecycle-integration)) — on success, on node failure, on execution timeout, and on cancellation (client disconnect beyond grace, `DELETE /api/v1/executions/{id}`, or shutdown).
- **No GC dependency anywhere.** The previous version of this spec claimed the `-1` close-delay flag tied the database's lifetime to open connections. That was factually wrong (§3.1), and the flag is gone.

A JVM crash mid-execution abandons the in-memory DB, which is fine — it is process memory, reclaimed by the OS.

---

## 4. Table Naming, Identifier Safety, and Creation

### 4.1 Table names

Table names come directly from the pipeline's `output.table` declarations:
- `stg_orders`, `stg_customers` — staging from source
- `int_revenue`, `int_customer_summary` — intermediate transformations

Rules are defined and validated at pipeline save time — see [Pipeline Contract §10](pipeline-contract.md#10-output-table-naming-rules). Summary (that doc is authoritative):
- `[a-z0-9_]+`, length 1–63.
- Unique among all `tempdb` targets in the pipeline (the `tempdb` namespace shares one staging database).
- Not `tempdb`, and not any name starting and ending with `__` (reserved namespace).

Because no pipeline can be saved with an invalid or colliding tempdb table name ([Pipeline Contract §2](pipeline-contract.md#2-design-principles), universal save-time validation), the staging layer treats a violation at runtime as a defect and fails loudly rather than sanitizing (§4.5).

### 4.2 CREATE TABLE generation

For a ResultSet with columns `[id INTEGER, name VARCHAR(100), total_amount NUMERIC(18,2)]`:

```sql
CREATE TABLE "stg_orders" (
  "id" INTEGER,
  "name" VARCHAR,
  "total_amount" DECIMAL(18, 2)
)
```

Notes:
- Every identifier is double-quoted (§4.5). Table names are already lowercase-only by contract, so quoting does not change how templates reference them; column names may be mixed-case, and quoting makes them exact.
- H2 `VARCHAR` without length spec = unbounded (practical limit 1GB). We don't propagate source length to H2 — source-DB length limits are not our concern (the source data is what it is).
- H2 `DECIMAL(p, s)` preserves exact precision.
- H2 `INTEGER` is 32-bit, `BIGINT` is 64-bit.

### 4.3 Batch inserts

```kotlin
private fun batchInsert(
    tableName: String,
    columns: List<ColumnSchema>,
    mappings: List<LogicalTypeMapping>,
    rs: ResultSet,
): Long {
    val columnList = columns.joinToString(",") { "\"${it.name}\"" }
    val placeholders = columns.joinToString(",") { "?" }
    val sql = "INSERT INTO \"$tableName\" ($columnList) VALUES ($placeholders)"

    return connection.prepareStatement(sql).use { stmt ->
        var rowCount = 0L
        val batchSize = config.insertBatchSize

        while (rs.next()) {
            mappings.forEachIndexed { i, m ->
                val value = readValue(rs, i + 1, m)
                stmt.setObject(i + 1, value, H2EgressMapper.h2SqlType(columns[i]))
            }
            stmt.addBatch()

            if (++rowCount % batchSize == 0L) {
                stmt.executeBatch()
            }
        }
        if (rowCount == 0L || rowCount % batchSize != 0L) {
            stmt.executeBatch()
        }
        rowCount
    }
}
```

The column list is written explicitly (not positional `INSERT INTO t VALUES (...)`) so the statement is independent of H2's column ordering. `insertBatchSize` comes from configuration ([§7.1](#71-configuration-keys)).

**Streaming:** the loop reads one row at a time from the source ResultSet. Memory footprint of the *transfer* is bounded by the batch size (batch rows × row size); the staged table itself is accounted against the memory budget (§8). A 10M-row source ResultSet stages with constant transfer memory.

### 4.4 Value reading

Per canonical type, the value is read from the source ResultSet and converted to the appropriate Java type for the H2 insert (`readValue`, signature in §5.3). Two normative read rules (2026-08-08):

- **`STRING`-canonical columns are read with `getString(index)`, never `getObject`.** Several §5.x mappings assign binary-coded or driver-object JDBC columns to canonical `STRING` (MySQL geometry as WKT, PG arrays, Oracle XMLType, CLOBs); `getObject(...).toString()` on those yields Java identity text (`[B@6d06d69c`) shipped as a plausible-looking value with no warning. `getString` makes the driver do the conversion.
- **Temporal columns are read with JDBC 4.2 `getObject(index, OffsetDateTime/LocalDate/LocalTime::class.java)`, never `getTimestamp`/`getDate`/`getTime`** — the `java.sql` temporal types convert through the JVM default zone, and the typesystem's `JsonEncoder`/`UtcNormalization` reject them by design (§8.4 machine-independence).

| Canonical | Read from source as | Insert into H2 as |
|---|---|---|
| `INTEGER` | `getInt` (check `wasNull`) | `Integer` or null |
| `BIGINTEGER` | `getLong` (check `wasNull`) | `Long` or null |
| `DECIMAL(p,s)` exact | `getBigDecimal` | `BigDecimal` (preserved) |
| `DECIMAL(p)` approx | `getDouble` (check `wasNull`) | `Double` or null |
| `BIGDECIMAL(p,s)` | `getBigDecimal` | `BigDecimal` (preserved) |
| `BOOLEAN` | `getBoolean` (check `wasNull`) | `Boolean` or null |
| `STRING` | `getString` | `String` |
| `BINARY` | `getBytes` | `byte[]` |
| `DATE` | `getObject(i, LocalDate::class.java)` | `LocalDate` |
| `TIME` | `getObject(i, LocalTime::class.java)` | `LocalTime` |
| `TIMESTAMP` | `getObject(i, OffsetDateTime::class.java)` → UTC-normalized | `OffsetDateTime` at `Z` |
| `NULL` | `getObject` (returns null) | null |

### 4.5 Identifier safety (normative)

Two classes of identifier reach generated SQL, and they have different threat models.

**Table names — trusted by construction.** They are pipeline-declared and fully validated at save time by [Pipeline Contract §10](pipeline-contract.md#10-output-table-naming-rules). No pipeline with an invalid tempdb table name can exist in the database (D2 universal save-time validation). The staging layer re-quotes them but does not re-derive the rule.

**Column names — attacker-adjacent.** They come from the *result set metadata of user-authored SQL* (`SELECT x AS "whatever the author typed"`), which is rendered from a template with pipeline parameters. They are never trusted. Before a column name is interpolated into any generated DDL or DML, the staging layer MUST:

1. **Validate the shape.** Each column label must match `[A-Za-z_][A-Za-z0-9_]{0,62}` (leading letter or underscore; letters, digits, underscores thereafter; total length 1–63, H2's identifier limit). An empty, null, over-long, or otherwise non-matching label fails the node.
2. **Reject duplicates.** Column labels must be unique within one staged result set. Comparison is case-insensitive, matching H2's unquoted-identifier folding — `total` and `TOTAL` collide. (SQL happily produces duplicate labels — `SELECT a.id, b.id FROM ...` — so this is a routine authoring mistake, not just an attack.)
3. **Double-quote unconditionally.** Every identifier — table and column — is emitted as `"name"` in generated `CREATE TABLE`, `INSERT`, and `DROP` statements. Validation is the security boundary; quoting is the second layer, and it also makes mixed-case labels exact rather than folded.

Failure of (1) or (2) → the node fails with **`pipeline.staging.invalid_column_name`**, with the offending label and its ordinal position in the error details. **Sanitizing is explicitly forbidden**: renaming a bad column to `col_3` would silently change the schema the caller receives and the names downstream `source: tempdb` templates must use. The author must fix the alias in their SQL.

```kotlin
private val COLUMN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")

/** Validates and returns the labels in order; throws StagingInvalidColumnNameException otherwise. */
private fun validateColumnNames(labels: List<String?>): List<String> {
    val seen = mutableSetOf<String>()
    return labels.mapIndexed { i, raw ->
        val label = raw ?: throw StagingInvalidColumnNameException(ordinal = i + 1, label = null)
        if (!COLUMN_NAME.matches(label)) throw StagingInvalidColumnNameException(i + 1, label)
        if (!seen.add(label.uppercase())) throw StagingInvalidColumnNameException(i + 1, label)
        label
    }
}
```

**Duplicate staged table (defensive).** `createTable` issues a bare `CREATE TABLE` — never `CREATE TABLE IF NOT EXISTS`, never an implicit `DROP`. If the table already exists in this execution's staging database, the node fails with **`pipeline.staging.table_already_exists`**. Save-time uniqueness validation (§4.1) is the primary guard and should make this unreachable; reaching it means either a validation gap or a node executing twice, and both are bugs worth surfacing loudly rather than papering over by overwriting a table another node is about to read.

---

## 5. Type Mapping to H2

See [Type System §6](type-system.md#6-h2-staging-type-mapping-canonical--h2) for the full canonical → H2 mapping table. Summary:

| Canonical | H2 type |
|---|---|
| `NULL` | `VARCHAR` (placeholder; all values will be null) |
| `BOOLEAN` | `BOOLEAN` |
| `INTEGER` | `INTEGER` |
| `BIGINTEGER` | `BIGINT` |
| `DECIMAL(p, s)` exact | `DECIMAL(p, s)` |
| `DECIMAL(p)` approx | `DOUBLE` |
| `BIGDECIMAL(p, s)` | `DECIMAL(p, s)` |
| `STRING` | `VARCHAR` |
| `BINARY` | `VARBINARY` |
| `DATE` | `DATE` |
| `TIME` | `TIME` |
| `TIMESTAMP` | `TIMESTAMP WITH TIME ZONE` |

### 5.1 Why TIMESTAMP WITH TIME ZONE in H2

Canonical TIMESTAMP is always UTC (per [Type System §8.4](type-system.md#84-timestamp-timezone-normalization)). H2's `TIMESTAMP WITH TIME ZONE` preserves the timezone (UTC). When we read back for egress, we get a UTC value directly, no normalization needed.

Plain H2 `TIMESTAMP` (without TZ) is a candidate, but `TIMESTAMP WITH TIME ZONE` makes the UTC assumption explicit and prevents accidental TZ-conversion bugs in H2's own functions.

### 5.2 Precision overflow handling

H2 2.x supports `DECIMAL` precision up to 100000 ([Type System §6](type-system.md#6-h2-staging-type-mapping-canonical--h2)). Source precisions from any supported dialect fit. If a source declares precision beyond that limit, staging fails with `pipeline.staging.precision_overflow`.

### 5.3 Mappers and helper signatures

The H2 type translation is **two directions, two objects** — they are not inverses of one another in practice (egress must pick a DDL type string and a `java.sql.Types` code; ingress must recover a canonical descriptor from H2 metadata), and conflating them in one object hid that asymmetry.

```kotlin
/** Canonical → H2. Used when generating DDL and binding insert parameters (§4.2, §4.3). */
object H2EgressMapper {
    /** H2 column type as written in CREATE TABLE, e.g. "DECIMAL(18, 2)", "VARCHAR". */
    fun toH2Type(column: ColumnSchema): String

    /** java.sql.Types constant for PreparedStatement.setObject(index, value, targetSqlType). */
    fun h2SqlType(column: ColumnSchema): Int
}

/** H2 → canonical. Used when reading staged data back out (§6). */
object H2IngressMapper {
    /**
     * Builds the canonical descriptor for one column of an H2 ResultSet.
     * jdbcType/precision/scale come from ResultSetMetaData; label is already validated (§4.5).
     */
    fun fromH2(label: String, jdbcType: Int, precision: Int, scale: Int): ColumnSchema
}

/**
 * Reads one value from the SOURCE ResultSet per the canonical mapping table (§4.4),
 * applying wasNull checks and UTC normalization for TIMESTAMP. Returns null for SQL NULL.
 */
private fun readValue(rs: ResultSet, index: Int, mapping: LogicalTypeMapping): Any?
```

Both mapper names are the ones used in [Module Structure §5.1](module-structure.md#51-typesystem) — `H2IngressMapper` and `H2EgressMapper`. There is no `H2TypeMapper`.

---

## 6. Streaming-Out

For the caller node's ResultSet ([Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node)), the executor streams rows from the H2 ResultSet into the wire format (JSON / Arrow / CSV):

```kotlin
fun streamResult(rs: ResultSet, format: WireFormat): StreamedResult {
    val metadata = rs.metaData
    val columns: List<ColumnSchema> = (1..metadata.columnCount).map { i ->
        H2IngressMapper.fromH2(
            metadata.getColumnLabel(i),
            metadata.getColumnType(i),
            metadata.getPrecision(i),
            metadata.getScale(i),
        )
    }

    when (format) {
        WireFormat.JSON -> {
            val rowStream = sequence {
                while (rs.next()) {
                    yield((1..metadata.columnCount).map { i ->
                        encodeValue(rs, i, columns[i - 1])
                    })
                }
            }
            return StreamedResult.Json(columns, rowStream)
        }
        // Arrow, CSV similar
    }
}
```

The H2 → canonical mapping is the inverse of the source → H2 mapping (covered by the H2 row in [Type System §5.5](type-system.md#55-h2-staging-layer--used-internally)).

### 6.1 Memory-bounded result handling

Result delivery has a single path: every caller result is materialized to the result store (Redis) and read back through the cursor — see [REST API §7](rest-api.md#7-result-delivery). There is no inline-vs-claim-check branch and no size threshold that switches modes.

For the staging layer that means:

1. The H2 ResultSet is consumed row-by-row at `datapipelines.staging.h2.result-batch-size` rows per fetch ([Configuration §3.3](configuration.md#33-staging-tempdb)).
2. Rows are written straight through to the result store as they are read, inside the executor's `connection.use` block ([DAG Executor §6.4](dag-executor.md#64-dql-output-dispatch)) — never buffered whole in JVM memory.
3. The `data_ready` event's inline first page is read back from the stored result, not held aside during streaming.
4. If the accumulated result exceeds `datapipelines.result.max-size-bytes` ([Configuration §3.5](configuration.md#35-results)), the execution fails with `result.too_large` — a result-delivery error, not a staging error.

A 100M-row caller ResultSet therefore does not OOM the JVM; it either streams into the result store at steady memory cost or trips the size cap.

---

## 7. Configuration and Error Codes

### 7.1 Configuration keys

Staging reads its settings from `datapipelines.staging.h2.*`, defined — names, defaults, and descriptions — in [Configuration §3.3](configuration.md#33-staging-tempdb). That document is the single authority; no defaults are restated here.

Keys consumed by this spec:

| Key | Used by |
|---|---|
| `datapipelines.staging.h2.mode` | JDBC URL `MODE=` parameter (§3.1) |
| `datapipelines.staging.h2.max-memory-mb` | Per-execution memory budget (§8) |
| `datapipelines.staging.h2.insert-batch-size` | Rows per INSERT batch (§4.3) |
| `datapipelines.staging.h2.result-batch-size` | Fetch size when reading staged data out (§3.3, §6.1) |
| `datapipelines.staging.h2.query-timeout-seconds` | `Statement.setQueryTimeout` on staging queries (§3.3) |

**Per-pipeline override.** `settings.tempdb.engine` selects the engine and `settings.tempdb.config.max_memory_mb` overrides `max-memory-mb` for that pipeline ([Pipeline Contract §5.1](pipeline-contract.md#51-settingstempdb--staging-engine-configuration), precedence per [Configuration §4](configuration.md#4-precedence)). No other staging key is per-pipeline overridable in v1.

### 7.2 Error codes

Staging error codes are cataloged centrally in [Pipeline Contract §13.5](pipeline-contract.md#135-staging). The codes this layer raises:

| Code | Raised when |
|---|---|
| `pipeline.staging.creation_failed` | The staging instance could not be created (§3.1) |
| `pipeline.staging.engine_unavailable` | The requested `settings.tempdb.engine` is not available (§3.1) |
| `pipeline.staging.invalid_column_name` | A source column label fails validation or duplicates another (§4.5) |
| `pipeline.staging.table_already_exists` | `CREATE TABLE` targets a name already staged in this execution (§4.5) |
| `pipeline.staging.memory_limit_exceeded` | The staged footprint exceeds the effective memory budget (§8.2) |
| `pipeline.staging.precision_overflow` | Source DECIMAL precision exceeds H2's limit (§5.2) |
| `pipeline.staging.value_overflow` | A source value exceeds the staged column's capacity (§4.3) |
| `pipeline.staging.cleanup_failed` | table cleanup (enumerate + `DROP TABLE`, §3.4) or connection close failed; logged, never masks a node failure |

There is no `pipeline.staging.h2_creation_failed` — the engine-neutral `creation_failed` is the canonical code.

---

## 8. Memory Management

### 8.1 Per-execution memory limit

Each staging instance has a memory budget, resolved once at creation (§3.1): the pipeline's `settings.tempdb.config.max_memory_mb` when present, otherwise the global `datapipelines.staging.h2.max-memory-mb`. H2 does not enforce a hard cap on an in-memory database, so the staging layer measures and aborts.

### 8.2 Memory accounting — measured, not estimated

The staging layer does **not** estimate footprint from row counts and average row widths — that arithmetic is unreliable for VARCHAR/VARBINARY-heavy tables and was wrong in both directions.

Accounting is a direct measurement of JVM heap, read **in-process** — not via H2's `MEMORY_USED()`:

```kotlin
fun usedHeapKb(): Long {
    System.gc()   // match MEMORY_USED()'s post-GC semantics; coarse guard, not per-poll hot path
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) / 1024
}
```

- **Why not `SELECT MEMORY_USED()`:** in H2 2.3.232, `MEMORY_USED()` requires **admin rights** (SQLState 90040) — and the staging connection is deliberately a *non-admin* user so author SQL cannot reach the host (§9.5). Empirically, `MEMORY_USED()` and `(totalMemory − freeMemory)` return the **same number** (both ~14271 KB after a 50k-row fill in the same instant): H2's `MEMORY_USED()` is itself "run a GC, then return used heap", not a measure of the database's own allocation. So the in-process reading is the identical quantity with no admin dependency.
- Polled **once after each staging operation completes** (after `batchInsert` returns for a table, inside the same mutex-held section — `checkMemoryBudget()` in §3.2) and after each `execute(sql)` that writes to staging. Not per batch: per-table granularity is enough to stop a runaway pipeline within one node.
- Reading is in kilobytes; budget in megabytes. Compare as `usedHeapKb > maxMemoryMb * 1024`.
- Exceeding the budget fails the current staging operation with `pipeline.staging.memory_limit_exceeded`, carrying the measured value and the budget in the error details.
- The same reading backs `StagingStats.memoryUsedBytes` (§10).

Rows staged per table and the table count are tracked as plain counters for observability; they are reported, not used to decide the limit.

**Known limit — the reading is JVM-heap-wide, not per-execution (v1).** Because it measures used heap for the whole JVM, it includes heap held by in-flight ResultSets, other executions' staging DBs, wire buffers, and the result-store writer — everything, not just this execution's tables. With **concurrent executions** every staging instance reads the same global number, so a single execution's `max_memory_mb` is in practice a **shared JVM-heap ceiling**, not an isolated per-execution budget: one heavy execution can trip a lighter one's check. This is a deliberate v1 simplification (it is the cheapest guard that reliably stops a genuine runaway before OOM), true regardless of which H2 user runs it; real per-execution memory isolation is a v1.1+ item (§13.2 lists the accounting model as not frozen). §8.4 is the hard JVM backstop underneath it.

### 8.3 Failure handling

On memory-limit failure:
1. The current staging operation throws `StagingMemoryLimitException`.
2. The executor catches it, wraps as `NodeExecutionException`, fails the node ([DAG Executor §8.2](dag-executor.md#82-error-code-mapping)).
3. The execution fails fast; `pipeline_failed` SSE event sent.
4. Cleanup runs in `finally` (§3.4); the staging DB is dropped and closed; memory freed.

### 8.4 JVM-level safety net

The JVM-level safety net is configured via:
- Container memory limit (Docker / k8s).
- JVM heap size (`-Xmx`) — sized as staging max-memory × max-concurrent-executions + baseline (see the resource-sizing guidance in [Deployment](deployment.md)).
- Off-heap buffer pool limits (for Arrow / large BLOB handling).

If the JVM OOMs mid-execution, the in-memory staging database dies with the process. No persistent state corruption (staging is in-memory only).

---

## 9. Single-Connection Model (v1)

### 9.1 The choice

Each staging instance is backed by **one** operational JDBC connection, opened at execution start and held until the executor's `finally` (§3.5). This means:
- Every node that touches staging — staging in, querying out, DML against tempdb — uses that one connection.
- The connection is also what keeps the in-memory database alive; it cannot be opened and closed per operation.

That operational connection authenticates as a **non-admin H2 user** (§9.5) — the transient admin connection that creates the database also creates the restricted user, and is closed before the module does any work.

### 9.2 Serialization is explicit — `Mutex`, not the driver

The executor runs nodes **concurrently** (up to `datapipelines.executor.max-parallel-nodes`), so concurrent access to the staging connection genuinely happens; two nodes can complete their source fetches at the same time and both try to stage. A JDBC `Connection` is **not** required by the JDBC spec to serialize concurrent callers safely, and H2's connection is not a safe multiplexing point: interleaved statement execution on one connection can corrupt statement state, scramble results, or throw obscure driver errors.

Therefore:

- `H2Staging` owns a `kotlinx.coroutines.sync.Mutex` (`kotlinx.coroutines.sync.Mutex`, **not** a `java.util.concurrent.locks.Lock` — the callers are coroutines and must suspend, not block an executor thread).
- Every method that touches the connection — `stage`, `withQuery`, `execute`, `stats` — acquires the mutex. These methods are `suspend` functions for that reason (§10).
- Consumption discipline for cursors is enforced by construction, not convention: `withQuery(sql) { rs -> … }` (§3.3, §10) holds the mutex for the whole lifetime of the cursor, including the caller node's suspending drain to the result store (§6.1). There is no API that returns a live `ResultSet` to be read after the lock is released, so a downstream `stage`/`execute` on the shared connection cannot interleave with an open cursor — the state-corruption case this section warns about is unreachable.
- Direct SQL access goes through `withConnection(block)` (§10), which acquires this same mutex for the duration of the block: the connection is never handed out unguarded, and the mutex itself is not reachable — or even observable — from outside the implementation. (The v1.2 contract exposed a `connection` property and made callers "responsible for taking the mutex"; that contract was unsatisfiable — the mutex is private, so no caller could ever take it — and is corrected here.) Two rules bind the block: it must not re-enter any staging operation (the lock is not reentrant, so `stage`/`query`/`execute`/`stats`/`withConnection` from inside the block deadlocks), and nothing derived from the connection (statements, cursors) may outlive the block.

This corrects the earlier claim (and [DAG Executor §12.1](dag-executor.md#121-race-conditions-considered)) that there is "no concurrent tempdb access." There is; it is serialized by this mutex.

### 9.3 Why single-connection for v1

- **Simpler semantics.** One writer at a time; no isolation-level questions, no in-database deadlocks between our own nodes.
- **Sufficient performance for v1.** The slow part of a node is fetching from source databases (network + remote DB processing). Staging into H2 is local and fast; serializing it adds little wall-clock time.
- **Cleaner lifecycle.** One connection to hold and close — and holding exactly one connection is what defines the database's lifetime (§3.5).

The cost is real and named: with N source nodes finishing simultaneously, their staging inserts run one after another. That is the trade accepted for v1.

### 9.4 When to revisit

If profiling shows staging serialization is a bottleneck (likely only on pipelines with many parallel source nodes and small per-source data), v1.1 can switch to a small H2 connection pool. Note this changes the lifecycle rule too: with a pool, the database lives as long as *any* pooled connection is open, so the pool — not a single connection — becomes the lifetime owner, and the `finally` must close the pool. The `Staging` interface does not change.

### 9.5 Privilege containment — author SQL runs de-privileged (normative)

The rendered SQL that `withQuery`/`execute` run is **author-authored** (a pipeline author's template body, §4.4 of [Templates](templates.md)). H2's admin-only surface reaches the host: `FILE_READ`/`FILE_WRITE`/`CSVWRITE`/`CSVREAD` read and write server files, `CREATE ALIAS`/`CREATE TRIGGER … AS` load JVM classes, `RUNSCRIPT`/`LINK_SCHEMA` fetch and execute. An `sa` (admin) staging session therefore turns "author may write tempdb SQL" into "author may read `/proc/self/environ`" — where `DATAPIPELINES_DB_ENCRYPTION_KEY` and `DATAPIPELINES_JWT_SECRET` live ([Configuration §2](configuration.md#2-required-configuration)). That is privilege escalation from `author` to all-datasource-credentials and session forgery, and it is **not** an accepted trade (contrast §4.4's SQL-injection note, which concerns the author's *own* authorized datasources, not the server's secrets).

Therefore:
- The database is created by a **transient bootstrap** admin (`sa`) connection, which immediately creates a restricted user (`CREATE USER STAGING_EXEC PASSWORD '<256-bit random hex>'` + `GRANT ALTER ANY SCHEMA` — no admin right) and is then **closed**. The one operational connection the module holds (§9.1) authenticates as that restricted user, and is what keeps the in-memory database alive thereafter. (`ALTER ANY SCHEMA` is the least grant that lets the user do PUBLIC DDL — `GRANT ALL ON SCHEMA PUBLIC` alone leaves `CREATE TABLE` refused; a user-owned schema forces `SET SCHEMA` and rewrites. It also permits DDL inside `INFORMATION_SCHEMA`, which is accepted: the database is a throwaway per-execution in-memory instance with no host reach, and author SQL can already `CREATE TABLE` in it — a junk table in a schema about to be dropped is not an escalation.)
- Under that user, every host-reaching function is refused with SQLState 90040 ("Admin rights are required") — **empirically verified against H2 2.3.232**: `FILE_READ`, `FILE_WRITE`, `CSVREAD`, `CSVWRITE`, `CREATE ALIAS`, `RUNSCRIPT`, `LINK_SCHEMA`, `CREATE TRIGGER … AS`, plus the self-escalation routes `ALTER USER … ADMIN TRUE` / `CREATE USER` / `SET`. So author SQL cannot reach the host filesystem, load a class, or grant itself admin.
- **The staging layer avoids the two admin-gated operations it would otherwise use.** `MEMORY_USED()` and `DROP ALL OBJECTS` are *also* admin-gated (90040) in this H2 version — so accounting uses an in-process heap reading (§8.2) and cleanup enumerates `INFORMATION_SCHEMA.TABLES` and drops each table (§3.4), both non-admin. `CREATE`/`INSERT`/`SELECT`/`DROP TABLE` and reading `INFORMATION_SCHEMA` — everything the staging layer needs — are available to the restricted user.
- The admin-gating is **guarded by a test** (`h2` in `libs.versions.toml`): it runs `FILE_READ`, `CSVWRITE`, and `CREATE ALIAS` as the restricted user and asserts each is refused. A driver upgrade re-runs it; if a future H2 un-gates one for non-admin users, the test fails and the containment is revisited before shipping.
- Optional deployment-level belt: launching the JVM with `-Dh2.allowedClasses=` (empty) denies `CREATE ALIAS` class loading independent of user rights. It must be a **launch arg** — `SysProperties.ALLOWED_CLASSES` is captured at H2 class-init, so a runtime `System.setProperty` is a no-op — and it is redundant once the operational user is non-admin (which already refuses `CREATE ALIAS`), so it is not required by this spec, only noted for deployments that want it.

---

## 10. The Staging Interface

The interface is engine-agnostic, allowing DuckDB (or other engines) to be plugged in later.

```kotlin
interface Staging : AutoCloseable {
    val executionId: UUID
    // Direct SQL for SQL nodes: runs block with the serialization lock held throughout (§9.2).
    // block must not re-enter staging operations (the lock is not reentrant), and nothing
    // derived from the Connection may escape the block.
    suspend fun <T> withConnection(block: suspend (Connection) -> T): T

    suspend fun stage(resultSet: ResultSet, tableName: String, sourceDialect: Dialect): StageResult
    // Runs block against the cursor with the serialization lock held for the WHOLE consumption
    // (§3.3/§9.2). The cursor is never handed out to be read after the lock is released, so a
    // concurrent stage()/execute() on the shared connection cannot interleave. block must fully
    // consume (or abandon) the cursor before it returns; nothing derived from it escapes.
    suspend fun <T> withQuery(sql: String, block: suspend (ResultSet) -> T): T
    suspend fun execute(sql: String): Long    // INSERT/UPDATE/DELETE/DDL against staging; returns row count

    suspend fun stats(): StagingStats         // current tables/rows/measured memory

    override fun close()                      // not suspend: called from finally, must not throw (§3.4)
}

data class StageResult(
    val tableName: String,
    val rowsStaged: Long,
    val columns: List<ColumnSchema>,
    val warnings: List<TypeMappingWarning> = emptyList()   // §8.2 warnings from the source→canonical mapping
)

data class StagingStats(
    val tableCount: Int,
    val totalRows: Long,
    val memoryUsedBytes: Long        // measured JVM heap, in-process (§8.2), not estimated
)
```

`ColumnSchema` is the canonical column descriptor from [Type System §7.1](type-system.md#71-column-descriptor-json-schema).

### 10.1 Future: DuckDB staging

For analytical workloads (large joins, aggregations on wide tables), DuckDB would outperform H2. The interface above is designed so that `DuckDbStaging` could be a drop-in replacement. Differences:
- JDBC URL: `jdbc:duckdb:memory:exec_{id}`.
- Type mapping: similar but DuckDB has `HUGEINT`, native nested types.
- Parallelism: DuckDB is internally parallel (one connection parallelizes queries), so the mutex could be relaxed — but only after verifying DuckDB's JDBC connection is documented thread-safe for concurrent statements.
- Memory accounting: DuckDB has its own `memory_limit` setting and `duckdb_memory()` view; `MEMORY_USED()` is H2-specific.

Marked as v2 candidate, not v1. Requesting an engine that is not on the classpath fails with `pipeline.staging.engine_unavailable`.

---

## 11. H2-Specific Concerns

### 11.1 SQL compatibility

H2 in `MODE=PostgreSQL` accepts most PG syntax: `::` casts, `LIMIT n OFFSET m`, array literals (limited), `RETURNING`, JSON operators (partial). Templates authored for staging (`source: "tempdb"`) should target H2's PG mode syntax, not raw PG syntax. Document this for template authors.

Differences from real PG to be aware of:
- Some PG functions not implemented (e.g., `generate_series` has limitations).
- Indexes: H2 supports but rarely needed for in-memory staging.
- Stored procedures: not supported in staging (we don't define any).

### 11.2 Functions available

H2 provides standard SQL functions: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `COALESCE`, `CASE WHEN`, `JOIN`s (all kinds), window functions, common table expressions (CTEs). All available for staging templates. Note `MEMORY_USED()` is **not** used by the staging layer (it is admin-gated in this H2 version, and the operational user is non-admin) — accounting reads JVM heap in-process (§8.2). The admin-only host-reaching functions (`FILE_READ`/`FILE_WRITE`/`CSVREAD`/`CSVWRITE`/`RUNSCRIPT`/`CREATE ALIAS`/`LINK_SCHEMA`) are **not** available to author SQL — it runs as a non-admin user (§9.5).

### 11.3 Known gotchas

- **Timestamp arithmetic**: H2's PG mode handles `INTERVAL` differently from PG. Test before relying on it.
- **Case sensitivity**: H2 folds unquoted identifiers to upper case; PG folds to lower case. Staged tables are created with quoted identifiers (§4.2), so a mixed-case source column keeps its exact case and template SQL must quote it to match. Table names are lowercase by contract, so unquoted references to them work. Template authors should prefer lowercase aliases in source SQL to avoid needing quotes downstream.
- **NULL ordering**: H2 defaults to `NULLS FIRST` for ASC; PG defaults to `NULLS LAST`. Use explicit `NULLS FIRST/LAST` in `ORDER BY` for predictable cross-engine behavior.

These are documented in the authoring guide (future).

---

## 12. Testing

- **Unit tests** for `H2EgressMapper` (every canonical type → correct H2 DDL type string and `java.sql.Types` code) and `H2IngressMapper` (every H2 metadata shape → correct `ColumnSchema`).
- **Identifier-safety tests**: labels that are empty, 64+ chars, start with a digit, contain a space/quote/semicolon/`--`, or contain a `DROP TABLE` payload → all rejected with `pipeline.staging.invalid_column_name`; case-insensitive duplicates rejected; a valid mixed-case label round-trips with its case preserved. An injection-shaped label must not create, drop, or alter any object.
- **Duplicate-table test**: staging the same table name twice in one execution → `pipeline.staging.table_already_exists`, first table's rows intact.
- **Integration tests** for staging: stage a ResultSet from a mock source, query it back, verify round-trip (type fidelity + row count + value equality).
- **Streaming tests**: stage 1M rows with limited JVM heap; verify constant transfer memory.
- **Concurrency test**: two coroutines calling `stage()` on the same instance simultaneously complete correctly and serialize (both tables present, correct row counts, no driver errors) — the test must fail if the mutex is removed.
- **Lifecycle tests**: after `close()`, `connection.isClosed` is true AND a fresh connection to the same `jdbc:h2:mem:exec_{id}` URL finds the **`STAGING_EXEC` user gone** (`SELECT COUNT(*) FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME='STAGING_EXEC'` = 0) — this is the regression test for `DB_CLOSE_DELAY=-1` ever returning. It must key on something the §3.4 cleanup does **not** remove: an *empty* fresh database no longer distinguishes "destroyed" from "survived-but-emptied" now that cleanup drops the tables before closing, so the old "sees an empty database" assertion is satisfied even if the DB survived — the user (dropped only when the DB itself dies) is the falsifiable signal. Separately, prove the §3.4 enumerate+`DROP TABLE` belt actually runs: hold a second peer connection open to the same URL so the DB survives `connection.close()`, `close()` the instance, then assert **through the peer** that the staged tables are gone. Also: `close()` on a connection already broken does not throw.
- **Memory-limit test**: stage past a deliberately small `max_memory_mb`; assert `pipeline.staging.memory_limit_exceeded` and that the measured in-process JVM-heap reading (not an estimate) drove the decision — with the budget anchored to a measured baseline + headroom, since the reading is JVM-heap-wide (§8.2).
- **Type round-trip tests** for every canonical type:
  - Source value → staged → queried back → wire-encoded → asserted equal to source.
  - Covers BIGINTEGER, BIGDECIMAL precision, TIMESTAMP UTC normalization, etc.

---

## 13. Stability Promise

### 13.1 Frozen in v1

- The `Staging` interface and `StagingFactory.create(executionId, engine)` signature.
- The lifecycle: single (non-admin, §9.5) connection opened at execution start, held for the execution, table drop (enumerate + `DROP TABLE`, §3.4) + close in `finally`, no GC reliance.
- The single-connection + explicit-`Mutex` serialization model.
- Identifier safety: column-name regex, duplicate rejection, unconditional double-quoting, no sanitizing.
- The canonical → H2 type mapping (per [Type System §6](type-system.md#6-h2-staging-type-mapping-canonical--h2)).
- Table names = `output.table` from pipeline nodes (no prefixes).

### 13.2 Not frozen

- H2 mode (`PostgreSQL` today, could switch).
- Batch sizes and timeouts (configuration, per [Configuration §3.3](configuration.md#33-staging-tempdb)).
- Single-connection model (could become a pool in v1.1 — see §9.4 for the lifecycle consequence).
- Memory-polling granularity (per staging operation today; could tighten).
- The H2-specific class names (only the `Staging` interface is the contract).

---

## 14. Open Questions / Future Additions

Out of scope for v1:

- **DuckDB as alternative staging**: switch on per-pipeline or globally. Useful for analytical workloads.
- **Hybrid staging**: H2 for small state, DuckDB for large joins. Complex; only if profiling justifies.
- **Spill-to-disk**: when in-memory limit hit, allow H2 to spill to disk (with severe perf warning) rather than fail. Useful for exploratory queries on large data.
- **Indexing hints**: let templates declare `CREATE INDEX` for staging tables to speed up specific JOINs.
- **Persistent staging for debugging**: opt-in mode where staging is preserved for N minutes after execution so developers can inspect intermediate tables. Note this requires an explicit holder for the connection (or a bounded positive H2 close-delay plus a reaper) — the v1 lifecycle deliberately has no such holder.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial staging spec: per-execution H2 lifecycle, table naming, type mapping, streaming, single-connection model, engine-agnostic interface |
| 2026-08-05 | v1.1 | propagation | Renamed `__staging__` → `tempdb` throughout to match v1.1 Pipeline Contract. Updated reserved-identifier namespace reference. |
| 2026-08-07 | v1.2 | spec review | Per [SPEC-REVIEW-2026-08 §2.7](SPEC-REVIEW-2026-08.md#27-stagingmd) (D6, D5, D8, D1, D9): removed `DB_CLOSE_DELAY=-1` and rewrote §3.1/§3.4/§3.5 lifecycle (explicit `DROP ALL OBJECTS` + close in `finally`, no GC reliance); explicit `Mutex` serialization (§9); new identifier-safety rules §4.5 (`invalid_column_name`, `table_already_exists`); `StageResult.columns: List<ColumnSchema>`; `StagingFactory.create(executionId, engine)` aligned with dag-executor + per-pipeline `max_memory_mb` precedence; `H2TypeMapper` split into `H2IngressMapper`/`H2EgressMapper` with real helper signatures (§5.3); memory accounting switched to polled `MEMORY_USED()` (§8.2); §7 config replaced by references to configuration.md §3.3 and error codes to pipeline-contract §13.5; §4.1 link fixed to pipeline-contract §10; §6.1 claim-check language replaced by the uniform result-delivery model; terminal-node language → caller node. |
| 2026-08-08 | v1.3 | P3 build (API HIGH-1) | `stage()` takes `sourceDialect: Dialect` and maps source columns through the **source dialect's** `mapColumn` — not `H2IngressMapper` (§3.2, §10); non-fatal mapping warnings surface on `StageResult.warnings` (§8.2); §4.4 value-reading table added. (Row recorded retroactively 2026-08-09 — the amendment landed in commit 1b07b49 without its Change Log row.) |
| 2026-08-09 | v1.4 | P3 build (security MEDIUM-1) | `Staging.connection` property removed; direct SQL goes through `suspend fun <T> withConnection(block)` which holds the internal serialization lock for the whole block (§3.4, §9.2, §10). The v1.2 contract — callers of a `connection` property "responsible for taking the mutex" — was unsatisfiable (the mutex is private) and is corrected, not extended. |
| 2026-08-10 | v1.9 | P3 build (staging re-review, testing MEDIUM-2 / security LOW-4) | §12 lifecycle-test bullet made falsifiable: the DB-destruction (`DB_CLOSE_DELAY=-1` regression) proof now keys on the `STAGING_EXEC` user being gone, not an empty DB — cleanup drops tables before close, so "empty" no longer distinguishes destroyed from survived-but-emptied; plus a peer-connection test proving the §3.4 enumerate+DROP belt actually runs. |
| 2026-08-09 | v1.8 | P3 build (staging re-review doc-drift) | Propagated the v1.6/v1.7 accounting+cleanup change to the mirror references the amendments missed (spec-internal drift, all LOW): §7.2 cleanup_failed row, §9.2 method list (`query`→`withQuery`), §10 execute comment (+`/DDL`) + StagingStats comment, §11.2 (`MEMORY_USED()` is NOT used — admin-gated), §12 memory-limit test bullet, §13.1 frozen-lifecycle line — all now say enumerate+DROP TABLE / in-process JVM-heap reading. No behavior change; removes reader-facing contradictions for the P4 implementer. |
| 2026-08-09 | v1.7 | P3 build (staging A2 impl) | §3.1 code block + "username sa" bullet corrected to the two-phase §9.5 non-admin creation (they still showed the pre-§9.5 single-`sa` connection — stale after the v1.6 amendment). §9.5 grant recorded as the empirically-least `GRANT ALTER ANY SCHEMA` (GRANT ALL ON SCHEMA PUBLIC alone leaves CREATE TABLE refused); its INFORMATION_SCHEMA-DDL breadth and the bootstrap `sa` keeping its empty password both accepted (throwaway per-execution DB, no host reach; author SQL cannot open a new connection). |
| 2026-08-09 | v1.6 | P3 build (staging round-2, empirical H2 finding) | §9.5 mechanism corrected against the pinned H2 2.3.232: `MEMORY_USED()` **and** `DROP ALL OBJECTS` are admin-gated (SQLState 90040), so a non-admin staging user cannot call them — §8.2 accounting switched to an in-process JVM-heap reading (identical quantity: H2's `MEMORY_USED()` is itself post-GC used-heap, not DB allocation — the old "not JVM heap" note was inverted and is corrected), §3.4 cleanup switched to enumerate-`INFORMATION_SCHEMA`-then-`DROP TABLE`; both verified non-admin. §9.5 records the verified 90040 refusals + drops the runtime `allowedClasses` bullet (no-op after class-init; redundant once non-admin). §8.2 now states honestly that the reading is JVM-heap-wide, not per-execution isolated (shared ceiling under concurrent executions) — a v1 simplification, per-execution isolation is v1.1+ (§13.2). |
| 2026-08-09 | v1.5 | P3 build (Gate C security re-check) | `query(sql): ResultSet` replaced by `withQuery(sql) { rs -> … }` holding the lock for the whole cursor consumption incl. the caller-node Redis drain (§3.3/§9.2/§10) — closes the §6.1-vs-§9.2 interleaving contradiction by construction (ST-SEC-1). New **§9.5 privilege containment**: author SQL runs as a non-admin H2 user (transient `sa` bootstrap creates DB + restricted user, then closes) so `FILE_READ`/`CSVWRITE`/`CREATE ALIAS` etc. cannot reach host files/classes — closes author→`/proc/self/environ`→encryption-key/JWT-secret escalation; gating verified against the pinned H2 driver + `-Dh2.allowedClasses=`. |
