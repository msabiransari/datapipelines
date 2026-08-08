# DAG Executor Specification

**Status:** v1.1 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract spec](pipeline-contract.md), [Templates spec](templates.md), [Datasources spec](datasources.md), [Staging spec](staging.md)
**Last updated:** 2026-08-05

---

## 1. Purpose

The Executor is the **runtime engine** that takes a validated Pipeline + input parameters and produces the result dataset. It is responsible for:

- Constructing an in-memory DAG from the Pipeline's `nodes` + `depends_on` declarations.
- Walking the DAG in dependency order, **parallelizing independent nodes** up to a configurable concurrency limit.
- For each node: rendering the template, executing SQL against the source (datasource or tempdb), dispatching on `type` (DQL/DML/DDL) and `output.target` (tempdb/caller/datasource).
- Propagating failures fail-fast (no partial results in v1).
- Collecting per-node execution stats for the response.
- Cleaning up H2 and connection resources when done.

This spec defines the data structures, algorithms, concurrency model, and lifecycle. It does **not** define the wire protocol (see [REST API spec](rest-api.md)) or the pipeline shape (see [Pipeline Contract](pipeline-contract.md)).

---

## 2. Design Principles

1. **No Guava, no JGraphT.** The DAG data structure is small enough to roll our own ~150-line Kotlin implementation. The hard part is the parallel executor — that's coroutines, not a graph library.
2. **Structured concurrency.** Use Kotlin `coroutineScope` + `async` + `Deferred` for dependency satisfaction. Every coroutine launched by an execution is a child of one `coroutineScope`, so failure or cancellation propagates automatically.
3. **Fail-fast.** Any node failure cancels all sibling/parent work and aborts the pipeline. No partial-result mode in v1 (deferred to v2 — see §13).
4. **Resource cleanup is guaranteed.** H2 instances, connection pool leases, file handles — all released in `finally` blocks or via coroutine cancellation handlers. A failed execution never leaks.
5. **Observability built in.** Every node emits `node_started` / `node_completed` / `node_failed` events to the SSE stream. Stats are collected unconditionally — successful pipelines and failed pipelines both report stats.
6. **Idempotent within an execution.** A node executes at most once per execution (no retries in v1). Caller-side idempotency (via `Idempotency-Key` on the REST call) handles client retries.

---

## 3. DAG Data Structure

A minimal `Dag<T>` implementation. Stored in the `dag` module.

### 3.1 Public API

```kotlin
package co.datapipelines.dag

class Dag<T> private constructor(
    private val nodes: Map<String, T>,
    private val dependencies: Map<String, Set<String>>
) {
    val nodeIds: Set<String> get() = nodes.keys
    fun node(id: String): T = nodes[id] ?: error("Unknown node id: $id")

    fun dependenciesOf(id: String): Set<String> = dependencies[id] ?: emptySet()
    fun dependentsOf(id: String): Set<String> =
        nodes.keys.filterTo(mutableSetOf()) { id in dependencies[it]!! }

    fun topologicalOrder(): List<String>
    fun independentBatches(): List<Set<String>>
    fun detectCycle(): List<String>?      // returns cycle path, or null if acyclic

    companion object {
        fun <T> build(block: DagBuilder<T>.() -> Unit): Dag<T>
    }
}

class DagBuilder<T> {
    fun addNode(id: String, value: T)
    fun addDependency(from: String, dependsOn: String)
    fun build(): Dag<T>     // throws on cycle, duplicate id, dangling dependency
}
```

### 3.2 Algorithms

**Topological sort (Kahn's algorithm):**

```kotlin
fun topologicalOrder(): List<String> {
    val inDegree = nodes.mapValues { (_, _) -> 0 }.toMutableMap()
    dependencies.forEach { (_, deps) -> deps.forEach { _ -> } }
    nodes.forEach { (id, _) -> inDegree[id] = dependencies[id]!!.size }

    val queue: ArrayDeque<String> = ArrayDeque(inDegree.filter { it.value == 0 }.keys)
    val result = mutableListOf<String>()

    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        result.add(id)
        dependentsOf(id).forEach { dep ->
            inDegree[dep] = inDegree[dep]!! - 1
            if (inDegree[dep] == 0) queue.add(dep)
        }
    }

    if (result.size != nodes.size) error("Cycle detected")
    return result
}
```

**Independent batches** (nodes that can run in parallel at each wave):

```kotlin
fun independentBatches(): List<Set<String>> {
    val batches = mutableListOf<Set<String>>()
    val completed = mutableSetOf<String>()
    val remaining = nodes.keys.toMutableSet()

    while (remaining.isNotEmpty()) {
        val ready = remaining.filterTo(mutableSetOf()) { id ->
            dependencies[id]!!.all { it in completed }
        }
        if (ready.isEmpty()) error("Cycle detected among: $remaining")
        batches.add(ready)
        completed += ready
        remaining -= ready
    }
    return batches
}
```

**Cycle detection** (returns the offending path for diagnostics):

```kotlin
fun detectCycle(): List<String>? {
    val WHITE = 0; val GRAY = 1; val BLACK = 2
    val color = nodes.keys.associateWith { WHITE }.toMutableMap()
    val parent = mutableMapOf<String, String?>()
    var cycleStart: String? = null
    var cycleEnd: String? = null

    fun dfs(u: String): Boolean {
        color[u] = GRAY
        for (v in dependentsOf(u)) {
            when (color[v]) {
                GRAY -> { cycleStart = v; cycleEnd = u; return true }
                WHITE -> {
                    parent[v] = u
                    if (dfs(v)) return true
                }
            }
        }
        color[u] = BLACK
        return false
    }

    for (id in nodes.keys) if (color[id] == WHITE && dfs(id)) {
        // Reconstruct cycle path
        val path = mutableListOf<String>()
        var current: String? = cycleEnd
        while (current != null && current != cycleStart) {
            path.add(current)
            current = parent[current]
        }
        path.add(cycleStart!!)
        path.reverse()
        return path + cycleStart
    }
    return null
}
```

The whole class is ~150 lines. **No external dependencies.** Tested independently of the pipeline module.

---

## 4. Node Model

Internal node representation (separate from the wire `Node` in [Pipeline Contract §4](pipeline-contract.md#4-node-schema)):

```kotlin
data class ExecutableNode(
    val id: String,
    val description: String,
    val type: NodeType,
    val source: NodeSource,
    val template: TemplateRef,
    val output: NodeOutput?,            // required for DQL; null for DML/DDL
    val dependsOn: Set<String>
)

enum class NodeType { DQL, DML, DDL }
// future: EXPRESSION, HTTP

sealed interface NodeSource {
    data class Datasource(val name: String) : NodeSource
    data object Tempdb : NodeSource          // maps to "tempdb" literal in pipeline JSON
}

sealed interface NodeOutput {
    data class Tempdb(val table: String) : NodeOutput
    data object Caller : NodeOutput           // terminal — returns ResultSet to caller
    data class Datasource(
        val datasource: String,
        val table: String,
        val mode: WriteMode                   // REPLACE or APPEND
    ) : NodeOutput
}

enum class WriteMode { REPLACE, APPEND }

data class TemplateRef(val id: String, val version: Int)
```

See [Enums §2–4](enums.md#2-nodetype--pipeline-node-sql-category) for the canonical value lists.

Conversion: `Pipeline.nodes` → `List<ExecutableNode>` is mechanical. Validated upfront (datasource names resolve, templates exist, output block presence matches type).

**Terminal detection** (see [Pipeline Contract §9](pipeline-contract.md#9-terminal-node-auto-detection)): the framework computes sinks (nodes with no downstream consumers), and identifies the single DQL sink with `output = NodeOutput.Caller` as the terminal. The executor captures that node's ResultSet as the pipeline output.

---

## 5. Execution Lifecycle

### 5.1 High-level flow

```
1. Receive ExecuteRequest(pipeline, parameters, idempotencyKey?)
2. Acquire execution slot (concurrency semaphore; reject if at limit)
3. Generate execution_id (UUID)
4. Emit execution_started event
5. Build DAG from pipeline.nodes
6. Verify DAG (acyclic, terminal node reachable) — already validated at write time, but defense in depth
7. Auto-detect terminal node (single DQL sink with output.target=Caller) — see Pipeline Contract §9
8. Build initial ExecutionContext from parameters + defaults
9. Create per-execution tempdb instance via StagingFactory (engine from pipeline.settings.tempdb.engine)
10. Walk DAG in topological order with parallelism:
    for each node when dependencies satisfied:
      a. Emit node_started event
      b. Render template against ExecutionContext
      c. Acquire connection (datasource or tempdb)
      d. Dispatch on node.type:
         - DQL: executeQuery → ResultSet → branch on output.target:
           - Tempdb: stage ResultSet into tempdb table
           - Caller: capture as terminal ResultSet
           - Datasource: stream ResultSet to external datasource (replace or append mode)
         - DML: executeUpdate → row count (no staging, no output)
         - DDL: execute → success/failure (no staging, no output)
      e. Emit node_completed event with stats
      f. Release connection
11. On any node failure:
    a. Emit node_failed event with error
    b. Cancel all running sibling coroutines
    c. Skip all pending nodes (emit ABORTED status in stats)
    d. Emit pipeline_failed event
    e. Cleanup
12. On success:
    a. Emit pipeline_completed event with stats
    b. Emit data_ready event (inline or claim-check) from terminal node's captured ResultSet
13. Cleanup tempdb instance, release execution slot
```

### 5.2 Concurrency model

```kotlin
class PipelineExecutor(
    private val templateEngine: TemplateEngine,
    private val datasourceRegistry: DatasourceRegistry,
    private val stagingFactory: StagingFactory,
    private val writebackRunner: WritebackRunner,
    private val eventEmitter: EventEmitter,
    private val config: ExecutorConfig
) {
    suspend fun execute(request: ExecuteRequest): ExecutionResult = coroutineScope {
        val executionId = UUID.randomUUID()
        val dag = buildDag(request.pipeline)
        val terminalNodeId = detectTerminal(dag)        // §4: single DQL sink with output=Caller
        val context = ExecutionContext.from(request.parameters, request.pipeline.parameters)
        val staging = stagingFactory.create(
            executionId,
            engine = request.pipeline.settings.tempdb.engine
        )

        try {
            eventEmitter.emit(ExecutionStarted(executionId, request.pipeline, context))

            val nodeResults = ConcurrentHashMap<String, Deferred<NodeResult>>()
            val semaphore = Semaphore(config.maxParallelNodes)

            for (nodeId in dag.topologicalOrder()) {
                val node = dag.node(nodeId)
                val deps = node.dependsOn.map { nodeResults.getValue(it) }

                nodeResults[nodeId] = async(Dispatchers.IO) {
                    semaphore.withPermit {
                        awaitAll(deps)   // propagate failure if any dep failed
                        executeNode(node, context, staging, executionId)
                    }
                }
            }

            val terminalResult = nodeResults.getValue(terminalNodeId).await()
            val terminalResultSet = terminalResult.callerResultSet
                ?: error("Terminal node $terminalNodeId did not capture a Caller ResultSet")

            eventEmitter.emit(PipelineCompleted(executionId, nodeStatsSnapshot(nodeResults)))
            eventEmitter.emit(DataReady(executionId, terminalResultSet))
            terminalResult.toExecutionResult()
        } catch (e: NodeExecutionException) {
            eventEmitter.emit(NodeFailed(executionId, e.nodeId, e))
            eventEmitter.emit(PipelineFailed(executionId, e.nodeId, e.toApiError()))
            throw PipelineExecutionFailed(e)
        } finally {
            staging.close()
        }
    }

    private suspend fun executeNode(
        node: ExecutableNode,
        context: ExecutionContext,
        staging: Staging,
        executionId: UUID
    ): NodeResult {
        eventEmitter.emit(NodeStarted(executionId, node.id))

        val startTime = Instant.now()
        try {
            val sql = templateEngine.render(node.template, context.values)
            val connection = when (node.source) {
                is NodeSource.Datasource -> datasourceRegistry.connectionFor(node.source.name)
                is NodeSource.Tempdb -> staging.connection
            }

            return connection.use { conn ->
                when (node.type) {
                    NodeType.DQL -> executeDql(conn, node, sql, staging, startTime)
                    NodeType.DML -> executeDml(conn, node, sql, startTime)
                    NodeType.DDL -> executeDdl(conn, node, sql, startTime)
                }
            }
        } catch (e: Exception) {
            eventEmitter.emit(NodeFailed(executionId, node.id, e.toApiError()))
            throw NodeExecutionException(node.id, e)
        } finally {
            eventEmitter.emit(NodeCompleted(executionId, node.id, Duration.between(startTime, Instant.now())))
        }
    }

    private fun executeDql(
        conn: Connection,
        node: ExecutableNode,
        sql: String,
        staging: Staging,
        startTime: Instant
    ): NodeResult {
        val rs = executeQuery(conn, sql)
        val output = node.output ?: error("DQL node ${node.id} missing output block")

        return when (output) {
            is NodeOutput.Tempdb -> {
                val rowsStaged = staging.stage(rs, output.table)
                NodeResult(node.id, rowsStaged, null, NodeStatus.SUCCESS, elapsed(startTime))
            }
            is NodeOutput.Caller -> {
                val captured = TerminalResultSet(rs)
                NodeResult(node.id, captured.rowCount, captured, NodeStatus.SUCCESS, elapsed(startTime))
            }
            is NodeOutput.Datasource -> {
                val rowsWritten = writebackRunner.writeback(rs, output)
                NodeResult(node.id, rowsWritten, null, NodeStatus.SUCCESS, elapsed(startTime))
            }
        }
    }

    private fun executeDml(
        conn: Connection,
        node: ExecutableNode,
        sql: String,
        startTime: Instant
    ): NodeResult {
        val rowsAffected = conn.prepareStatement(sql).use { stmt ->
            stmt.setQueryTimeout(config.nodeQueryTimeoutSeconds)
            stmt.executeUpdate()
        }
        return NodeResult(node.id, rowsAffected, null, NodeStatus.SUCCESS, elapsed(startTime))
    }

    private fun executeDdl(
        conn: Connection,
        node: ExecutableNode,
        sql: String,
        startTime: Instant
    ): NodeResult {
        conn.createStatement().use { stmt ->
            stmt.setQueryTimeout(config.nodeQueryTimeoutSeconds)
            stmt.execute(sql)
        }
        return NodeResult(node.id, 0L, null, NodeStatus.SUCCESS, elapsed(startTime))
    }

    private fun elapsed(start: Instant) = Duration.between(start, Instant.now())
}
```

**Key changes vs. the single-type `SQL` model:**
- Executor dispatches on `node.type` to choose `executeQuery` (DQL), `executeUpdate` (DML), or `execute` (DDL).
- DQL further dispatches on `node.output.target`: tempdb stage, caller capture, or external-datasource write-back.
- DML and DDL have no `output` block; they record row count (DML) or success (DDL) only.
- Terminal is auto-detected by `detectTerminal(dag)` rather than read from `pipeline.terminalNodeId` (which doesn't exist on the v1.1 contract).

### 5.3 Concurrency controls

| Control | Default | Where configured |
|---|---|---|
| Max parallel nodes per execution | 4 | `executor.max-parallel-nodes` |
| Max concurrent executions per user | 10 | `executor.max-concurrent-executions-per-user` |
| Max concurrent executions (global) | 100 | `executor.max-concurrent-executions-global` |
| JDBC query timeout (per node) | 60s | `executor.node-query-timeout-seconds` |
| Execution overall timeout | 600s | `executor.execution-timeout-seconds` |

When limits are exceeded, the request is rejected with `pipeline.execution.concurrency_limit` (per-user/global) or `pipeline.execution.timeout` (overall).

### 5.4 Why fail-fast (not partial)

In v1, a single node failure cancels the whole execution. Reasons:

1. **Simple semantics.** Clients see SUCCESS or FAILED, nothing in between. No "you got some data but it might be wrong" mental model.
2. **Predictable cleanup.** All resources (H2, connections, semaphores) release on abort.
3. **Honest stats.** Every node reports a status: SUCCESS / FAILED / ABORTED. No ambiguity.
4. **Simpler UI.** Pipeline editor doesn't need to render "partial success" states.

Partial-result mode is a v2 candidate (§13) — useful for analytics use cases where partial data is better than none, but adds significant complexity.

---

## 6. Node Execution Details

### 6.1 Template rendering

The template engine (see [Templates spec](templates.md)) renders the template body against `context.values` (a `Map<String, Any?>`). The result is a SQL string.

```kotlin
val sql: String = templateEngine.render(
    ref = TemplateRef("fetch_orders.sql", 2),
    context = mapOf(
        "start_date" to LocalDate.of(2026, 1, 1),
        "end_date" to LocalDate.of(2026, 1, 31)
    )
)
```

Render failures (undefined variable, malformed Freemarker) throw `TemplateRenderException` → wrapped as `NodeExecutionException` → fails the node → fails the pipeline.

### 6.2 Connection acquisition

For `NodeSource.Datasource`:
- Look up datasource by name in the registry.
- Acquire connection from the datasource's connection pool (HikariCP — see [Datasources spec](datasources.md)).
- Pool-acquisition timeout: 30s (configurable). Exceeding → `pipeline.node.datasource_connection_failed`.

For `NodeSource.Tempdb`:
- Use the per-execution tempdb connection (already open, single-connection in v1).
- No pool, no acquisition delay.

### 6.3 Behavior by node `type`

The executor dispatches on `node.type` after acquiring the connection.

#### 6.3.1 `DQL` — `executeQuery`

```kotlin
conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
    stmt.setQueryTimeout(config.nodeQueryTimeoutSeconds)
    val rs = stmt.executeQuery(sql)
    dispatchOnOutput(rs, node.output)
}
```

Behavior then branches on `node.output.target` (see §6.4).

#### 6.3.2 `DML` — `executeUpdate`

```kotlin
conn.prepareStatement(sql).use { stmt ->
    stmt.setQueryTimeout(config.nodeQueryTimeoutSeconds)
    val rowsAffected = stmt.executeUpdate()
    recordRowsAffected(rowsAffected)
}
```

Returns affected row count. No staging, no output block. Side-effect only.

#### 6.3.3 `DDL` — `execute`

```kotlin
conn.createStatement().use { stmt ->
    stmt.setQueryTimeout(config.nodeQueryTimeoutSeconds)
    stmt.execute(sql)
}
```

Returns success/failure. No staging, no output block.

### 6.4 DQL output dispatch

For DQL nodes, behavior depends on `node.output.target`:

#### 6.4.1 `output.target: "tempdb"` — stage ResultSet

Stream the ResultSet into the tempdb table named by `output.table`:

```kotlin
fun stage(resultSet: ResultSet, tableName: String): Long {
    val metadata = resultSet.metaData
    val columnCount = metadata.columnCount
    val columnTypes = (1..columnCount).map { mapper.map(it, metadata) }
    val h2Types = columnTypes.map { h2TypeMapper.toH2Type(it) }

    h2Connection.createStatement().use { stmt ->
        stmt.execute(buildCreateTableSql(tableName, h2Types))
    }

    h2Connection.prepareStatement(buildInsertSql(tableName, columnCount)).use { insert ->
        var rowCount = 0L
        val batchSize = 1000
        while (resultSet.next()) {
            for (i in 1..columnCount) {
                insert.setObject(i, readValue(resultSet, i, columnTypes[i - 1]))
            }
            insert.addBatch()
            if (++rowCount % batchSize == 0L) insert.executeBatch()
        }
        if (rowCount % batchSize != 0L) insert.executeBatch()
        return rowCount
    }
}
```

Batch size 1000 (configurable). Streaming — constant memory regardless of result size. Downstream nodes reference this table by name in their SQL.

#### 6.4.2 `output.target: "caller"` — terminal capture

The ResultSet is **captured** (not staged): converted to the wire response format (per Type System) and either inlined in the `data_ready` event or stored in Redis for claim-check retrieval. Exactly one node per pipeline may use this target — the auto-detected terminal (§9 of Pipeline Contract).

#### 6.4.3 `output.target: "datasource"` — write-back

The ResultSet is streamed to the external datasource's table via batch INSERT, mediated by `WritebackRunner`:

```kotlin
fun writeback(rs: ResultSet, output: NodeOutput.Datasource): Long {
    val targetPool = datasourceRegistry.poolFor(output.datasource)
    targetPool.connection.use { targetConn ->
        targetConn.autoCommit = false
        try {
            if (output.mode == WriteMode.REPLACE) {
                targetConn.createStatement().use { it.execute("TRUNCATE TABLE ${output.table}") }
                // or DELETE FROM ${output.table} if TRUNCATE not supported by dialect
            }
            val rowsWritten = streamInsert(rs, targetConn, output.table)
            targetConn.commit()
            return rowsWritten
        } catch (e: Exception) {
            targetConn.rollback()
            throw e
        }
    }
}
```

The target table must already exist (created by a preceding DDL node in the pipeline, or pre-existing in the datasource). v1.1 will add `output.auto_create: true` to emit `CREATE TABLE IF NOT EXISTS` from ResultSet metadata.

Failure modes:
- Target table missing → `pipeline.node.writeback_target_missing`.
- INSERT failure (constraint violation, type mismatch, etc.) → `pipeline.node.writeback_failed` (transaction rolls back).

### 6.5 Reading upstream data

When a downstream node's SQL references upstream tables (e.g., `SELECT * FROM stg_orders`), it runs against the per-execution tempdb instance. The table exists because the upstream DQL node created it in §6.4.1.

**No cross-node data passing via Context.** Upstream data lives in tempdb tables (or, for write-back nodes, in external datasource tables); downstream templates reference those tables by name. Context carries only input parameters and (future) calculator outputs.

---

## 7. Node Stats Collection

Every node records:

```kotlin
data class NodeStats(
    val nodeId: String,
    val status: NodeStatus,             // SUCCESS / FAILED / ABORTED
    val startedAt: Instant?,
    val completedAt: Instant?,
    val durationMs: Long,
    val rowsOut: Long,                  // -1 for failed/aborted
    val bytesOut: Long,                 // -1 for failed/aborted; estimated
    val errorCode: String?,             // present on FAILED
    val errorMessage: String?
)

enum class NodeStatus { SUCCESS, FAILED, ABORTED }
```

`ABORTED` = node never started (one of its dependencies failed).

The pipeline aggregates these into the response:

```json
"node_stats": [
  {"node_id": "fetch_orders", "status": "SUCCESS", "started_at": "...", "completed_at": "...", "duration_ms": 1266, "rows_out": 12453, "bytes_out": 4567890},
  {"node_id": "fetch_customers", "status": "SUCCESS", "started_at": "...", "completed_at": "...", "duration_ms": 850, "rows_out": 5400, "bytes_out": 1200000},
  {"node_id": "revenue_by_customer", "status": "SUCCESS", "started_at": "...", "completed_at": "...", "duration_ms": 200, "rows_out": 4500, "bytes_out": 800000},
  {"node_id": "final_report", "status": "FAILED", "started_at": "...", "completed_at": "...", "duration_ms": 60, "rows_out": -1, "bytes_out": -1, "error_code": "pipeline.node.query_execution_failed", "error_message": "..."}
]
```

---

## 8. Error Propagation

### 8.1 Exception hierarchy

```kotlin
sealed class PipelineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NodeExecutionException(
    val nodeId: String,
    val cause: Throwable
) : PipelineException("Node $nodeId failed: ${cause.message}", cause)

class PipelineExecutionFailed(
    val failedNodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>
) : PipelineException("Pipeline aborted: node $failedNodeId failed ($errorCode)")

class PipelineTimeoutException(
    val timedOutNodeId: String?,
    val elapsedMs: Long
) : PipelineException("Pipeline timed out after ${elapsedMs}ms")
```

### 8.2 Error code mapping

Each internal exception maps to an error code from [Pipeline Contract §11](pipeline-contract.md#11-error-code-catalog-initial-set):

| Internal exception | Error code |
|---|---|
| `SQLException` during connection acquire | `pipeline.node.datasource_connection_failed` |
| `SQLException` during query execution | `pipeline.node.query_execution_failed` |
| `Freemarker` render error | `pipeline.node.template_render_failed` |
| Template not found | `pipeline.node.template_not_found` |
| Datasource not found at runtime | `pipeline.node.datasource_not_found` |
| Staging type overflow | `pipeline.staging.value_overflow` |
| Staging precision overflow | `pipeline.staging.precision_overflow` |
| H2 instance creation failure | `pipeline.staging.h2_creation_failed` |
| Query timeout | `pipeline.execution.timeout` |

### 8.3 Coroutine cancellation behavior

When a node fails, its `NodeExecutionException` propagates up the coroutine hierarchy:

- `awaitAll(deps)` in downstream nodes throws the exception.
- `coroutineScope` cancels all child coroutines.
- Pending nodes (not yet started) never start.
- Running sibling nodes are cancelled mid-execution. JDBC queries that support cancellation (`Statement.cancel()`) are cancelled explicitly; others complete or hit their own query timeout.

The `finally` block in `execute(...)` runs unconditionally, ensuring tempdb cleanup and execution slot release.

---

## 9. Tempdb Lifecycle Integration

The executor creates a tempdb instance per execution via `StagingFactory`, choosing the engine from `pipeline.settings.tempdb.engine`:

```kotlin
interface StagingFactory {
    fun create(executionId: UUID, engine: StagingEngine = StagingEngine.H2): Staging
}

interface Staging : AutoCloseable {
    val connection: Connection       // single connection in v1
    fun stage(resultSet: ResultSet, tableName: String): Long
    fun query(sql: String): ResultSet
    override fun close()
}
```

The tempdb instance lives for the duration of the execution only. `close()` is called in the executor's `finally` block. See [Staging spec](staging.md) for H2 configuration details.

**Concurrency note:** v1 uses a single tempdb connection per execution. Multiple nodes can technically run in parallel (per `maxParallelNodes`), but staging operations on the same connection would serialize. Two options:

1. **Single connection, accept serialization on staging ops** — simpler, fine for v1 since the slow work is fetching from sources (which uses datasource connections, not tempdb).
2. **Connection pool for tempdb** — allows parallel staging ops. v1.1 if profiling shows serialization is a bottleneck.

Default: option 1. Revisit if benchmarks show it's a problem.

---

## 10. SSE Event Integration

The executor emits events via `EventEmitter`:

```kotlin
interface EventEmitter {
    suspend fun emit(event: ExecutionEvent)
}

sealed class ExecutionEvent {
    abstract val executionId: UUID
    abstract val timestamp: Instant
}

data class ExecutionStarted(...) : ExecutionEvent()
data class NodeStarted(...) : ExecutionEvent()
data class NodeCompleted(...) : ExecutionEvent()
data class NodeFailed(...) : ExecutionEvent()
data class PipelineCompleted(...) : ExecutionEvent()
data class PipelineFailed(...) : ExecutionEvent()
data class DataReady(...) : ExecutionEvent()
```

The `EventEmitter` implementation routes events to:
- Active SSE stream (if the client is still connected).
- Event log (for replay via `/executions/{id}/events`).
- Optional webhook subscribers (future).

Events are emitted **even if no client is connected** — executions complete regardless of SSE state. Disconnected clients can replay the event stream later.

---

## 11. Idempotency

### 11.1 Within a single execution

A node executes at most once. No internal retries.

### 11.2 Across client retries

Client-supplied `Idempotency-Key` on the REST request is hashed with `pipeline_id + version + parameters` to form a cache key. The cache stores the execution ID and the cached SSE event stream.

- Same key + same request body → server returns cached execution_id and replays the cached event stream.
- Same key + different request body → server rejects with `idempotency_key.reuse_for_different_request`.
- Cache TTL: 24 hours (configurable).

This means an agent that times out mid-SSE and retries can pick up where it left off — same execution ID, same events.

### 11.3 Storage

Idempotency cache stored in Redis. Key: `idem:{user_id}:{idempotency_key_hash}`. Value: `{execution_id, request_hash, expires_at}`.

---

## 12. Concurrency Safety Analysis

### 12.1 Race conditions considered

| Race | Mitigation |
|---|---|
| Two clients submit same idempotency key simultaneously | Redis atomic SETNX on idempotency key — first wins, second waits and returns the in-flight execution_id |
| Pipeline deleted while executing | Snapshot pipeline body at execution start; deletion doesn't affect in-flight executions |
| Template deleted while pipeline referencing it executes | Template versions are immutable; deletion is soft; runtime fetch is consistent |
| Datasource deleted while pipeline using it executes | Connection already acquired at node start; deletion doesn't release acquired connections |
| User runs out of execution slots mid-pipeline | Slots acquired at execution start (one per execution, not per node); held until completion |
| H2 cleanup vs. concurrent query | H2 instance is single-connection; cleanup happens after coroutineScope completes; no concurrent access possible |

### 12.2 Things explicitly NOT safe (and documented)

- **Concurrent executions of the same pipeline with the same parameters**: this is allowed and produces independent executions. If the pipeline writes side-effects to source databases, the side-effects happen N times. Pipeline authors must design for idempotency at the source level.
- **Modifying a pipeline while it's executing**: the executing version is a snapshot; in-flight executions are unaffected. Subsequent executions use the new version.

---

## 13. Future Additions (Out of Scope for v1)

- **Partial-result mode**: return whatever data was staged before a node failed. Complex because partial data may violate downstream assumptions.
- **Streaming between nodes**: pipe rows between nodes instead of full materialization. Useful for memory-constrained environments.
- **Per-node retries**: declarable retry policy per node (`{"retries": 3, "backoff": "exponential"}`).
- **Conditional execution**: `{"when": "${include_cancelled} == true"}` skip nodes based on Context expressions.
- **Calculator nodes**: pre-execution transformers that add Context keys (`quarter` from `date`, etc.).
- **Cycle support (iterative pipelines)**: allow bounded loops for algorithms that converge (ML scoring, etc.). Very different execution model.
- **Async/scheduled execution**: trigger pipelines on cron schedules, return immediately, deliver results via webhook later.
- **H2 connection pooling**: parallel staging ops if benchmarks show single-connection serialization is a bottleneck.

---

## 14. Testing Requirements

The DAG module and executor must have:

- **Unit tests for `Dag<T>`**: every algorithm (topological sort, cycle detection, independent batches) against small synthetic graphs covering diamond dependencies, self-loops, disconnected components, large fan-outs.
- **Unit tests for `executeNode`**: mocked template engine, datasource, tempdb. Every code path: DQL with each `output.target` (tempdb / caller / datasource), DML, DDL; success; template failure; connection failure; query failure; staging failure; writeback failure.
- **Integration tests** with real H2 + Testcontainers-backed sources (PG, MySQL, etc.): end-to-end pipelines of varying DAG shapes (linear, diamond, fan-out, fan-in, mixed).
- **Concurrency tests**: parallel executions, parallel nodes within one execution, execution-slot exhaustion, query timeouts firing during execution.
- **Failure-path tests**: every error code in §8.2 exercised by a test that triggers it.
- **Resource-leak tests**: run 100 executions back-to-back, verify H2 instances, connections, semaphores all released (no leaks).

---

## 15. Implementation Notes

### 15.1 Where this lives in the codebase

Implemented in the `dag` Gradle module:

- `co.datapipelines.dag.Dag` — the data structure
- `co.datapipelines.dag.DagBuilder`
- `co.datapipelines.executor.PipelineExecutor`
- `co.datapipelines.executor.ExecutableNode`, `NodeSource`, `NodeType`
- `co.datapipelines.executor.NodeStats`, `NodeStatus`
- `co.datapipelines.events.EventEmitter` (interface)
- `co.datapipelines.events.ExecutionEvent` (sealed class)

### 15.2 Coroutine context

- All executor code runs in a dedicated `ExecutorDispatcher` (Dispatchers.IO bounded to a configured max — separate from Spring's default).
- Each execution has its own `coroutineScope`, so failure of one execution never affects another.
- `Job` cancellation is honored — a cancelled client SSE stream cancels the execution (after a configurable grace period for late event emission).

### 15.3 Monitoring

The executor exports Micrometer metrics:
- `datapipelines.executions.total{status=success|failed}` — counter
- `datapipelines.executions.duration{pipeline_id=...}` — timer
- `datapipelines.executions.concurrent` — gauge
- `datapipelines.nodes.duration{pipeline_id=...,node_id=...}` — timer
- `datapipelines.staging.rows` — counter (total rows staged across all executions)

See [Observability spec](observability.md) (future) for details.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial DAG executor spec: ~150-line Dag<T>, parallel execution via coroutines, fail-fast, SSE integration, idempotency |
| 2026-08-05 | v1.1 | propagation | Aligned with v1.1 Pipeline Contract. `NodeType.SQL` → `NodeType.{DQL, DML, DDL}`. `NodeSource.Staging` → `NodeSource.Tempdb`. Replaced `outputTable: String?` with sealed `NodeOutput` (Tempdb/Caller/Datasource). `executeNode` now dispatches on `type` then `output.target`. Added DML/DDL execution paths. Added write-back execution path (WritebackRunner) for `output.target: "datasource"`. Terminal auto-detected via `detectTerminal(dag)` instead of read from `pipeline.terminalNodeId`. Renamed §9 from "H2 Lifecycle" to "Tempdb Lifecycle" (engine-agnostic). |
