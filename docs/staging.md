# Staging (H2) Specification

**Status:** v1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md)
**Last updated:** 2026-08-05

---

## 1. Purpose

**Staging** is the in-memory H2 database that holds intermediate result sets during a pipeline execution. Each pipeline execution gets its own isolated H2 instance, created when the execution starts and destroyed when it ends. No state survives between executions.

This spec defines:
- The H2 instance lifecycle (create, populate, query, destroy).
- Table naming and creation rules.
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
3. **Created-on-demand, destroyed-on-completion.** The H2 instance is created when the executor starts and is destroyed (closed + GC'd) when the executor finishes, regardless of success/failure.
4. **Tables named per the Pipeline Contract.** Tables use the exact `output.table` names declared in the pipeline (`stg_orders`, `int_revenue`). No prefixes, no UUIDs. Downstream template SQL references these names directly.
5. **Single connection in v1.** One JDBC connection per H2 instance. Simpler model, no locking complexity. Parallel staging operations serialize on the connection; this is rarely a bottleneck in v1 because source-query latency dominates.
6. **Streaming, not buffering.** Source ResultSets stream into H2 via batched inserts — constant memory regardless of result size.

---

## 3. Lifecycle

### 3.1 Creation

```kotlin
interface StagingFactory {
    fun create(executionId: UUID): Staging
}

class H2StagingFactory(private val config: H2Config) : StagingFactory {
    override fun create(executionId: UUID): Staging {
        val jdbcUrl = "jdbc:h2:mem:exec_${executionId};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        val connection = DriverManager.getConnection(jdbcUrl, "sa", "")
        return H2Staging(executionId, connection, config)
    }
}
```

Key points:
- **JDBC URL**: `jdbc:h2:mem:exec_{execution_id}` — random, isolated, in-memory.
- **`DB_CLOSE_DELAY=-1`**: keeps the DB alive as long as the JVM has a connection (default would close it when the last connection closes — we want explicit lifecycle control).
- **`MODE=PostgreSQL`**: H2's PostgreSQL compatibility mode. Makes H2's SQL syntax closer to PG (which most users know), enables some PG-specific functions. **This is a SQL-syntax choice, not a type-system choice** — H2 still uses its own type system internally; we map canonical → H2 explicitly per [Type System §6](type-system.md#6-h2-staging-type-mapping-canonical--h2).
- **Username `sa`, empty password**: H2 in-memory has no network exposure; auth is meaningless. (Defense-in-depth: even loopback-only, the JDBC URL is per-execution and unknown to outsiders.)

### 3.2 Population

For each non-terminal node, the executor stages the source ResultSet:

```kotlin
fun stage(resultSet: ResultSet, tableName: String): StageResult {
    val metadata = resultSet.metaData
    val columnCount = metadata.columnCount
    val columnMappings = (1..columnCount).map { i ->
        dialectMapper.map(metadata.getColumnType(i), metadata.getPrecision(i), metadata.getScale(i), metadata.getColumnTypeName(i))
    }
    val h2ColumnDecls = columnMappings.mapIndexed { i, m ->
        val h2Type = H2TypeMapper.toH2Type(m)
        val columnName = metadata.getColumnName(i + 1) ?: "col_${i + 1}"
        "$columnName $h2Type"
    }

    createTable(tableName, h2ColumnDecls)
    val rowsStaged = batchInsert(tableName, resultSet, columnMappings)
    return StageResult(tableName, rowsStaged, columnMappings)
}
```

### 3.3 Querying

For nodes with `source: "tempdb"`, the executor runs the rendered SQL against the staging connection directly:

```kotlin
fun query(sql: String): ResultSet {
    val stmt = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
    stmt.setQueryTimeout(config.queryTimeoutSeconds)
    return stmt.executeQuery(sql)
}
```

The returned ResultSet is consumed by either:
- A downstream node's stage operation (streaming into a new H2 table).
- The terminal node's result capture (converting to wire format for return).

### 3.4 Destruction

```kotlin
class H2Staging(...) : AutoCloseable {
    override fun close() {
        try {
            dropAllTables()              // explicit cleanup
            connection.close()           // releases the connection
            // H2 in-memory DB is GC'd when no connections remain
        } catch (e: Exception) {
            log.warn("H2 cleanup failed for execution {}: {}", executionId, e.message)
            // Don't rethrow — close() is in finally blocks
        }
    }
}
```

Cleanup runs in the executor's `finally` block (see [DAG Executor §5](dag-executor.md#5-execution-lifecycle)). Even on failure, the staging instance is closed.

### 3.5 Lifecycle guarantee

The H2 instance **cannot outlive the execution**:
- Created by `StagingFactory.create(executionId)` at execution start.
- Held in a local variable inside `PipelineExecutor.execute(...)`.
- Closed in the `finally` block of the same function.
- No reference leaks to long-lived objects.

A JVM crash mid-execution abandons the in-memory DB, which is fine — it's garbage on restart anyway.

---

## 4. Table Naming and Creation

### 4.1 Table names

Table names come directly from the pipeline's `output.table` declarations:
- `stg_orders`, `stg_customers` — staging from source
- `int_revenue`, `int_customer_summary` — intermediate transformations
- `final_report` — terminal table (rare; usually the terminal node returns directly without staging)

Rules (validated at pipeline write time, see [Pipeline Contract §6](pipeline-contract.md#6-output-table-naming-rules)):
- `[a-z0-9_]+`, length 1–63.
- Unique within the pipeline.
- Not in reserved namespace (`tempdb`, anything matching `__.*__`).

### 4.2 CREATE TABLE generation

For a ResultSet with columns `[id INTEGER, name VARCHAR(100), total_amount NUMERIC(18,2)]`:

```sql
CREATE TABLE stg_orders (
  id INTEGER,
  name VARCHAR,
  total_amount DECIMAL(18, 2)
)
```

Notes:
- H2 `VARCHAR` without length spec = unbounded (practical limit 1GB). We don't propagate source length to H2 — source-DB length limits are not our concern (the source data is what it is).
- H2 `DECIMAL(p, s)` preserves exact precision.
- H2 `INTEGER` is 32-bit, `BIGINT` is 64-bit.

### 4.3 Batch inserts

```kotlin
private fun batchInsert(tableName: String, rs: ResultSet, mappings: List<LogicalTypeMapping>): Long {
    val placeholders = (1..mappings.size).joinToString(",") { "?" }
    val sql = "INSERT INTO $tableName VALUES ($placeholders)"

    return connection.prepareStatement(sql).use { stmt ->
        var rowCount = 0L
        val batchSize = config.insertBatchSize    // default 1000

        while (rs.next()) {
            mappings.forEachIndexed { i, m ->
                val value = readValue(rs, i + 1, m)
                stmt.setObject(i + 1, value, h2SqlType(m))
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

**Streaming:** the loop reads one row at a time from the source ResultSet. Memory footprint is bounded by the batch size (1000 rows × row size). A 10M-row source ResultSet stages in constant memory.

### 4.4 Value reading

Per canonical type, the value is read from the source ResultSet and converted to the appropriate Java type for the H2 insert:

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
| `DATE` | `getDate` → normalized | `java.sql.Date` |
| `TIME` | `getTime` | `java.sql.Time` |
| `TIMESTAMP` | `getTimestamp` → UTC normalized | `java.sql.Timestamp` (UTC) |
| `NULL` | `getObject` (returns null) | null |

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

H2's `DECIMAL` supports precision up to ~100,000+. Source precisions from any supported dialect fit. If somehow a source declares precision beyond H2's limit (impossible in practice — none of our supported dialects go that high), staging fails with `pipeline.staging.precision_overflow`.

---

## 6. Streaming-Out

For the terminal node's ResultSet, the executor streams rows from the H2 ResultSet to the wire format (JSON / Arrow / CSV):

```kotlin
fun streamResult(rs: ResultSet, format: WireFormat): StreamedResult {
    val metadata = rs.metaData
    val columnMappings = (1..metadata.columnCount).map { i ->
        h2TypeMapper.fromH2(metadata.getColumnType(i), metadata.getPrecision(i), metadata.getScale(i))
    }

    when (format) {
        WireFormat.JSON -> {
            val rowStream = sequence {
                while (rs.next()) {
                    yield((1..metadata.columnCount).map { i ->
                        encodeValue(rs, i, columnMappings[i - 1])
                    })
                }
            }
            return StreamedResult.Json(columnMappings, rowStream)
        }
        // Arrow, CSV similar
    }
}
```

The H2 → canonical mapping is the inverse of the source → H2 mapping (covered by H2 row in [Type System §5.5](type-system.md#55-h2-staging-layer--used-internally)).

### 6.1 Memory-bounded result handling

For large results:
1. The stream is consumed row-by-row.
2. For inline SSE: stream is collected into a single `data_ready` event payload only if under `LARGE_RESULT_THRESHOLD` (1MB default). If it exceeds, the streaming aborts and switches to claim-check mode.
3. For claim-check: stream is written directly to Redis without buffering in JVM memory.

This means: a 100M-row terminal ResultSet doesn't OOM the JVM — it streams into Redis at a steady memory cost.

---

## 7. Configuration

```yaml
datapipelines:
  staging:
    h2:
      mode: "PostgreSQL"                  # H2 compatibility mode
      insert-batch-size: 1000             # rows per INSERT batch
      query-timeout-seconds: 60           # per-query timeout
      max-memory-mb: 1024                 # soft limit per execution (see §8)
      result-batch-size: 10000            # rows per claim-check page
```

All settings configurable per deployment; defaults shown.

---

## 8. Memory Management

### 8.1 Per-execution memory limit

Each H2 instance has a soft memory limit (default 1GB, configurable). H2 itself doesn't enforce a hard limit, but we monitor JVM heap usage and abort the execution if it exceeds the per-execution budget.

### 8.2 Memory accounting

The staging layer tracks:
- Rows staged per table (sum).
- Approximate bytes per table (column count × average row size × row count, with periodic recomputation).
- Total estimated memory.

If estimated total exceeds `max-memory-mb`, the staging step fails with `pipeline.staging.memory_limit_exceeded`. The pipeline aborts.

### 8.3 Failure handling

On memory-limit failure:
1. Current staging operation throws `StagingMemoryLimitException`.
2. The executor catches it, wraps as `NodeExecutionException`, fails the node.
3. Pipeline aborts; `pipeline_failed` SSE event sent.
4. Cleanup runs; H2 instance closed; memory freed.

### 8.4 JVM-level safety net

The JVM-level safety net is configured via:
- Container memory limit (Docker / k8s).
- JVM heap size (`-Xmx`).
- Off-heap buffer pool limits (for Arrow / large BLOB handling).

If the JVM OOMs mid-execution, the H2 instance is GC'd along with everything else. No persistent state corruption (H2 is in-memory).

---

## 9. Single-Connection Model (v1)

### 9.1 The choice

Each H2 staging instance is backed by **one** JDBC connection. This means:
- Multiple nodes referencing staging share the same connection.
- Parallel staging operations (two nodes staging different tables simultaneously) serialize at the connection level.

### 9.2 Why single-connection for v1

- **Simpler semantics.** No locking, no isolation-level questions, no deadlocks.
- **Sufficient performance for v1.** The slow part of a node is fetching from source databases (network + remote DB processing). Staging into H2 is local + fast; serialization on the single connection adds little wall-clock time.
- **Cleaner cleanup.** One connection to close; no orphaned connections.

### 9.3 When to revisit

If profiling shows staging serialization is a bottleneck (likely only on pipelines with many parallel source nodes + small per-source data), v1.1 can switch to a tiny H2 connection pool (4 connections). The Staging abstraction supports this without changing the interface.

---

## 10. The Staging Interface

The interface is engine-agnostic, allowing DuckDB (or other engines) to be plugged in later.

```kotlin
interface Staging : AutoCloseable {
    val executionId: UUID
    val connection: Connection        // direct access for SQL nodes

    fun stage(resultSet: ResultSet, tableName: String): StageResult
    fun query(sql: String): ResultSet
    fun execute(sql: String): Long    // for INSERT/UPDATE/DELETE in staging; returns row count

    fun stats(): StagingStats         // current rows/tables/memory estimate

    override fun close()
}

data class StageResult(
    val tableName: String,
    val rowsStaged: Long,
    val columns: List<ColumnSchema>
)

data class StagingStats(
    val tableCount: Int,
    val totalRows: Long,
    val estimatedMemoryBytes: Long
)
```

### 10.1 Future: DuckDB staging

For analytical workloads (large joins, aggregations on wide tables), DuckDB would outperform H2. The interface above is designed so that `DuckDbStaging` could be a drop-in replacement. Differences:
- JDBC URL: `jdbc:duckdb:memory:exec_{id}`.
- Type mapping: similar but DuckDB has `HUGEINT`, native nested types.
- Parallelism: DuckDB is internally parallel (one connection parallelizes queries), so single-connection concerns don't apply.

Marked as v2 candidate, not v1.

---

## 11. H2-Specific Concerns

### 11.1 SQL compatibility

H2 in `MODE=PostgreSQL` accepts most PG syntax: `::` casts, `LIMIT n OFFSET m`, array literals (limited), `RETURNING`, JSON operators (partial). Templates authored for staging (`source: "tempdb"`) should target H2's PG mode syntax, not raw PG syntax. Document this for template authors.

Differences from real PG to be aware of:
- Some PG functions not implemented (e.g., `generate_series` has limitations).
- Indexes: H2 supports but rarely needed for in-memory staging.
- Stored procedures: not supported in staging (we don't define any).

### 11.2 Functions available

H2 provides standard SQL functions: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `COALESCE`, `CASE WHEN`, `JOIN`s (all kinds), window functions, common table expressions (CTEs). All available for staging templates.

### 11.3 Known gotchas

- **Timestamp arithmetic**: H2's PG mode handles `INTERVAL` differently from PG. Test before relying on it.
- **Case sensitivity**: H2 identifiers are case-insensitive by default; PG is case-sensitive (lowercases unquoted). Templates should use lowercase identifiers to be safe in both.
- **NULL ordering**: H2 defaults to `NULLS FIRST` for ASC; PG defaults to `NULLS LAST`. Use explicit `NULLS FIRST/LAST` in `ORDER BY` for predictable cross-engine behavior.

These are documented in the authoring guide (future).

---

## 12. Testing

- **Unit tests** for `H2TypeMapper` (every canonical type → correct H2 column type).
- **Integration tests** for staging: stage a ResultSet from a mock source, query it back, verify round-trip (type fidelity + row count + value equality).
- **Streaming tests**: stage 1M rows with limited JVM heap; verify constant memory.
- **Cleanup tests**: stage tables, close staging, verify `connection.isClosed` and that no references remain (leak detection).
- **Type round-trip tests** for every canonical type:
  - Source value → staged → queried back → wire-encoded → asserted equal to source.
  - Covers BIGINTEGER, BIGDECIMAL precision, TIMESTAMP UTC normalization, etc.

---

## 13. Stability Promise

### 13.1 Frozen in v1

- The `Staging` interface.
- The H2 lifecycle (create at execution start, close in finally).
- The single-connection model.
- The canonical → H2 type mapping (per [Type System §6](type-system.md#6-h2-staging-type-mapping-canonical--h2)).
- Table names = `output.table` from pipeline nodes (no prefixes).

### 13.2 Not frozen

- H2 mode (`PostgreSQL` today, could switch).
- Insert batch size (configurable).
- Single-connection model (could become pool in v1.1).
- The H2-specific class names (only the `Staging` interface is the contract).

---

## 14. Open Questions / Future Additions

Out of scope for v1:

- **DuckDB as alternative staging**: switch on per-pipeline or globally. Useful for analytical workloads.
- **Hybrid staging**: H2 for small state, DuckDB for large joins. Complex; only if profiling justifies.
- **Spill-to-disk**: when in-memory limit hit, allow H2 to spill to disk (with severe perf warning) rather than fail. Useful for exploratory queries on large data.
- **Indexing hints**: let templates declare `CREATE INDEX` for staging tables to speed up specific JOINs.
- **Persistent staging for debugging**: opt-in mode where staging is preserved for N minutes after execution so developers can inspect intermediate tables. Useful for pipeline debugging.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial staging spec: per-execution H2 lifecycle, table naming, type mapping, streaming, single-connection model, engine-agnostic interface |
| 2026-08-05 | v1.1 | propagation | Renamed `__staging__` → `tempdb` throughout to match v1.1 Pipeline Contract. Updated reserved-identifier namespace reference. |
