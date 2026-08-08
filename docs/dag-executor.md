# DAG Executor Specification

**Status:** v1.2 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract spec](pipeline-contract.md), [Templates spec](templates.md), [Datasources spec](datasources.md), [Staging spec](staging.md)
**Last updated:** 2026-08-07

---

## 1. Purpose

The Executor is the **runtime engine** that takes a validated Pipeline + input parameters and produces the result dataset. It is responsible for:

- Constructing an in-memory DAG from the Pipeline's `nodes` + `depends_on` declarations.
- Walking the DAG in dependency order, **parallelizing independent nodes** up to a configurable concurrency limit.
- For each node: rendering the template, executing SQL against the source (datasource or tempdb), dispatching on `type` (DQL/DML/DDL) and `output.target` (tempdb/caller/datasource).
- Materializing the **caller node's** ResultSet into the Redis result store, and emitting `data_ready` from it ([REST API §7](rest-api.md#7-result-delivery)).
- Propagating failures fail-fast (no partial results in v1).
- Honouring cancellation — explicit `DELETE /executions/{id}`, client disconnect beyond grace, server shutdown (§8.3).
- Collecting per-node execution stats for the response.
- Cleaning up staging (tempdb) and connection resources when done.

This spec defines the data structures, algorithms, concurrency model, and lifecycle. It does **not** define the wire protocol (see [REST API spec](rest-api.md)) or the pipeline shape (see [Pipeline Contract](pipeline-contract.md)).

---

## 2. Design Principles

1. **No Guava, no JGraphT.** The DAG data structure is small enough to roll our own ~150-line Kotlin implementation. The hard part is the parallel executor — that's coroutines, not a graph library.
2. **Structured concurrency.** Use Kotlin `coroutineScope` + `async` + `Deferred` for dependency satisfaction. Every coroutine launched by an execution is a child of one `coroutineScope`, so failure or cancellation propagates automatically.
3. **Fail-fast.** Any node failure cancels all sibling/parent work and aborts the pipeline. No partial-result mode in v1 (deferred to v2 — see §13).
4. **Resource cleanup is guaranteed.** Staging instances, connection pool leases, file handles — all released in `finally` blocks or via coroutine cancellation handlers. A failed or cancelled execution never leaks.
5. **Observability built in.** Every node emits `node_started`, then exactly one of `node_completed` (success) or `node_failed` (failure). `node_completed` is **never** emitted for a failed node. Stats are collected unconditionally — successful, failed, and aborted executions all report stats.
6. **Idempotent within an execution.** A node executes at most once per execution (no retries in v1). Caller-side idempotency (via `Idempotency-Key` on the REST call) handles client retries.
7. **Cancellable at any point.** Every in-flight node registers its JDBC `Statement`; a cancellation request interrupts the statement and then cancels the coroutine, so a caller that leaves never keeps a source database busy (§8.3).

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
        nodes.keys.filterTo(mutableSetOf()) { id in dependenciesOf(it) }

    fun topologicalOrder(): List<String>
    fun independentBatches(): List<Set<String>>   // diagnostic/UI only — see §3.3
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
    val inDegree = nodes.keys.associateWithTo(mutableMapOf()) { dependenciesOf(it).size }

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

**Independent batches** (nodes that could run in parallel at each wave — **diagnostic/UI API, not used by the executor**; see §3.3):

```kotlin
fun independentBatches(): List<Set<String>> {
    val batches = mutableListOf<Set<String>>()
    val completed = mutableSetOf<String>()
    val remaining = nodes.keys.toMutableSet()

    while (remaining.isNotEmpty()) {
        val ready = remaining.filterTo(mutableSetOf()) { id ->
            dependenciesOf(id).all { it in completed }
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

### 3.3 API surface actually used by the executor

| Method | Used by the executor? | Purpose |
|---|---|---|
| `topologicalOrder()` | yes | Iteration order for scheduling node coroutines (§5.2) |
| `dependenciesOf(id)` / `node(id)` / `nodeIds` | yes | Dependency wiring and lookup |
| `detectCycle()` | yes | Defence-in-depth check at execution start (write-time validation is the primary guard) |
| `independentBatches()` | **no** | **Diagnostic/UI API only.** The executor does not schedule in waves — it starts every node coroutine up front and lets each await its own dependencies, which is strictly more parallel than wave scheduling. `independentBatches()` exists for the pipeline editor's layer layout and for `EXPLAIN`-style diagnostics. It is still unit-tested (§14), but no executor path calls it. |

---

## 4. Executor-Facing Model

Internal node representation (separate from the wire `Node` in [Pipeline Contract §4](pipeline-contract.md#4-node-schema)):

```kotlin
data class ExecutableNode(
    val id: String,
    val description: String,
    val type: NodeType,
    val source: NodeSource,
    val template: TemplateRef,
    val output: NodeOutput?,            // DQL: always non-null (omitted → Caller, resolved at deserialization); null for DML/DDL
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
    data object Caller : NodeOutput           // the caller node — result materialized to the Redis result store
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

### 4.1 Caller-node resolution

There is no terminal-node auto-detection and no `terminal_node_id` field. Per [Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node):

1. A DQL node with **no `output` block** deserializes to `NodeOutput.Caller`. The default is applied **at deserialization time**, so by the time the executor sees an `ExecutableNode`, every DQL node has a concrete `NodeOutput` and the executor never re-derives a default.
2. The **caller node** is simply the node whose `output` is `NodeOutput.Caller`. At most one exists per pipeline (`pipeline.validation.multiple_caller_nodes`, enforced at save time).
3. **Zero caller nodes is legal.** A pure write-back/ETL pipeline emits `pipeline_completed` with stats and **no `data_ready` event**.

The executor asserts **nothing** about DAG position: the caller node may be a sink or sit mid-DAG, and the presence of other sinks (write-back or DML side-effect nodes) is not an error. The executor's only caller-related behaviour is: *if* a node with `NodeOutput.Caller` exists, materialize its ResultSet (§6.4.2) and emit `data_ready`; otherwise skip that step.

---

## 5. Execution Lifecycle

### 5.1 High-level flow

```
1. Receive ExecuteRequest(pipeline, parameters, idempotencyKey?, resultTtlSeconds?)
2. Acquire execution slot (per-user + global semaphores; reject with
   pipeline.execution.concurrency_limit if at limit)
3. Generate execution_id (UUID)
4. Emit execution_started event
5. Build DAG from pipeline.nodes
6. Verify DAG acyclic (detectCycle) — already validated at write time, defense in depth
7. Resolve the caller node: the single node with output = NodeOutput.Caller, or none
   (§4.1; no topology inspection — zero caller nodes is legal)
8. Build initial ExecutionContext from parameters + defaults
9. Create per-execution tempdb instance via StagingFactory (engine from pipeline.settings.tempdb.engine)
10. Register the execution in the cancellation registry (§8.3) so DELETE /executions/{id},
    disconnect-grace expiry, and shutdown can reach it
11. Under withTimeout(executor.execution-timeout-seconds), walk the DAG with parallelism:
    every node gets a coroutine up front; each awaits its dependencies, then takes a
    node-parallelism permit:
      a. Emit node_started event
      b. Render template against ExecutionContext
      c. Acquire connection (datasource or tempdb)
      d. Register the node's Statement for cancellation; set the query timeout
      e. Dispatch on node.type:
         - DQL: executeQuery → ResultSet → branch on output.target:
           - Tempdb: stage ResultSet into tempdb table
           - Caller: materialize the ResultSet into the Redis result store (§6.4.2)
           - Datasource: stream ResultSet to external datasource (replace or append mode)
         - DML: executeUpdate → row count (no staging, no output)
         - DDL: execute → success/failure (no staging, no output)
      f. Emit node_completed event with stats (success only)
      g. Deregister the Statement, release connection and permit
12. On any node failure:
    a. Emit node_failed event with error (exactly once, at the failure site)
    b. Cancel all running sibling coroutines (Statement.cancel() then coroutine cancel)
    c. Skip all pending nodes (report ABORTED status in stats)
    d. Emit pipeline_failed event
    e. Cleanup
13. On cancellation (DELETE / disconnect-grace / shutdown):
    a. Cancel every registered Statement, then the execution scope
    b. Emit execution_aborted event with reason and stats — status ABORTED
    c. Cleanup
14. On timeout: withTimeout throws → PipelineTimeoutException → pipeline_failed with
    pipeline.execution.timeout (status FAILED, not ABORTED)
15. On success:
    a. Emit pipeline_completed event with stats
    b. If a caller node ran: emit data_ready built from the stored result
       (schema + inline first page + result_url) — REST API §6.4.7.
       If there is no caller node: no data_ready event at all.
16. Cleanup: deregister execution, DROP ALL OBJECTS + close the tempdb connection,
    release execution slot
```

### 5.2 Concurrency model

```kotlin
class PipelineExecutor(
    private val templateEngine: TemplateEngine,
    private val datasourceRegistry: DatasourceRegistry,
    private val stagingFactory: StagingFactory,
    private val writebackRunner: WritebackRunner,
    private val resultStore: ResultStore,               // Redis-backed — §6.4.2
    private val eventEmitter: EventEmitter,
    private val cancellationRegistry: CancellationRegistry,   // §8.3
    private val executionSlots: ExecutionSlots,        // per-user + global permits — §5.3
    private val dispatcher: ExecutorDispatcher,        // §15.2 — never Dispatchers.IO directly
    private val config: ExecutorConfig
) {
    suspend fun execute(request: ExecuteRequest): ExecutionResult {
        val executionId = UUID.randomUUID()

        // Step 2 of §5.1: execution-slot admission, held for the whole execution.
        // Throws PipelineConcurrencyLimitException → pipeline.execution.concurrency_limit.
        return executionSlots.withSlot(request.userId) {
            runExecution(executionId, request)
        }
    }

    private suspend fun runExecution(
        executionId: UUID,
        request: ExecuteRequest
    ): ExecutionResult {
        val dag = buildDag(request.pipeline)
        val callerNodeId: String? = dag.nodeIds.singleOrNull {   // §4.1 — may be absent
            dag.node(it).output is NodeOutput.Caller
        }
        val context = ExecutionContext.from(request.parameters, request.pipeline.parameters)
        val staging = stagingFactory.create(
            executionId,
            engine = request.pipeline.settings.tempdb.engine
        )
        val handle = cancellationRegistry.register(executionId)   // §8.3

        try {
            withTimeout(config.executionTimeoutSeconds.seconds) {
                coroutineScope {
                    handle.bind(coroutineContext.job)   // DELETE / disconnect / shutdown reach us here
                    eventEmitter.emit(ExecutionStarted(executionId, request.pipeline, context))

                    val nodeResults = ConcurrentHashMap<String, Deferred<NodeResult>>()
                    val nodePermits = Semaphore(config.maxParallelNodes)

                    for (nodeId in dag.topologicalOrder()) {
                        val node = dag.node(nodeId)
                        val deps = node.dependsOn.map { nodeResults.getValue(it) }

                        nodeResults[nodeId] = async(dispatcher.context) {
                            awaitAll(deps)                 // wait for dependencies FIRST — holding no permit
                            nodePermits.withPermit {       // only then occupy a parallelism slot
                                executeNode(node, context, staging, executionId, handle)
                            }
                        }
                    }

                    val results = nodeResults.mapValues { (_, d) -> d.await() }
                    val stats = nodeStatsSnapshot(results)
                    eventEmitter.emit(PipelineCompleted(executionId, stats))

                    // §4.1: no caller node → no data_ready. Legal, not an error.
                    val resultRef = callerNodeId?.let { results.getValue(it).callerResultRef }
                    if (resultRef != null) {
                        eventEmitter.emit(DataReady.from(executionId, request.pipeline.id, resultStore.describe(resultRef)))
                    }
                    ExecutionResult(executionId, ExecutionStatus.SUCCESS, stats, resultRef)
                }
            }
        } catch (e: NodeExecutionException) {
            // node_failed was already emitted at the failure site (executeNode) — not re-emitted here.
            eventEmitter.emit(PipelineFailed(executionId, e.nodeId, e.toApiError()))
            throw PipelineExecutionFailed(e.nodeId, e.errorCode, e.errorDetails)
        } catch (e: TimeoutCancellationException) {
            val timeout = PipelineTimeoutException(runningNodeId(executionId), config.executionTimeoutSeconds * 1000L)
            eventEmitter.emit(PipelineFailed(executionId, timeout.timedOutNodeId, timeout.toApiError()))
            throw timeout
        } catch (e: ExecutionAbortedException) {
            eventEmitter.emit(ExecutionAborted(executionId, e.reason, nodeStatsSnapshot(partialResults(executionId))))
            throw e
        } finally {
            cancellationRegistry.deregister(executionId)
            staging.close()          // DROP ALL OBJECTS + close the single connection — §9
        }
    }

    private suspend fun executeNode(
        node: ExecutableNode,
        context: ExecutionContext,
        staging: Staging,
        executionId: UUID,
        handle: CancellationHandle
    ): NodeResult {
        eventEmitter.emit(NodeStarted(executionId, node.id))

        val startTime = Instant.now()
        try {
            val sql = templateEngine.render(node.template, context.values)
            val connection = when (node.source) {
                is NodeSource.Datasource -> datasourceRegistry.connectionFor(node.source.name)
                is NodeSource.Tempdb -> staging.connection    // Mutex-guarded — §9
            }

            val result = connection.use { conn ->
                when (node.type) {
                    NodeType.DQL -> executeDql(conn, node, sql, staging, executionId, startTime, handle)
                    NodeType.DML -> executeDml(conn, node, sql, startTime, handle)
                    NodeType.DDL -> executeDdl(conn, node, sql, startTime, handle)
                }
            }
            // Success only. A failed node emits node_failed and NEVER node_completed.
            eventEmitter.emit(NodeCompleted(executionId, node.id, result))
            return result
        } catch (e: CancellationException) {
            throw e                                     // cancellation is not a node failure
        } catch (e: Exception) {
            val mapped = mapToErrorCode(e)              // §8.2
            eventEmitter.emit(NodeFailed(executionId, node.id, mapped))
            throw NodeExecutionException(node.id, mapped.code, mapped.details, e)
        }
    }

    private fun executeDql(
        conn: Connection,
        node: ExecutableNode,
        sql: String,
        staging: Staging,
        executionId: UUID,
        startTime: Instant,
        handle: CancellationHandle
    ): NodeResult =
        conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
            stmt.queryTimeout = config.nodeQueryTimeoutSeconds(node.source)
            handle.withStatement(node.id, stmt) {       // registered for Statement.cancel() — §8.3
                val rs = stmt.executeQuery(sql)
                when (val output = node.output!!) {     // non-null for DQL — §4.1
                    is NodeOutput.Tempdb ->
                        NodeResult.of(node.id, rowsOut = staging.stage(rs, output.table), startTime)
                    is NodeOutput.Caller -> {
                        // Fully materialized into Redis INSIDE connection.use — §6.4.2
                        val stored = resultStore.materialize(executionId, rs)
                        NodeResult.of(node.id, rowsOut = stored.totalRows, startTime,
                                      callerResultRef = stored.key, bytesOutEstimate = stored.bytes)
                    }
                    is NodeOutput.Datasource ->
                        NodeResult.of(node.id, rowsOut = writebackRunner.writeback(rs, output), startTime)
                }
            }
        }

    private fun executeDml(
        conn: Connection,
        node: ExecutableNode,
        sql: String,
        startTime: Instant,
        handle: CancellationHandle
    ): NodeResult =
        conn.prepareStatement(sql).use { stmt ->
            stmt.queryTimeout = config.nodeQueryTimeoutSeconds(node.source)
            handle.withStatement(node.id, stmt) {
                NodeResult.of(node.id, rowsOut = stmt.executeUpdate().toLong(), startTime)
            }
        }

    private fun executeDdl(
        conn: Connection,
        node: ExecutableNode,
        sql: String,
        startTime: Instant,
        handle: CancellationHandle
    ): NodeResult =
        conn.createStatement().use { stmt ->
            stmt.queryTimeout = config.nodeQueryTimeoutSeconds(node.source)
            handle.withStatement(node.id, stmt) {
                stmt.execute(sql)
                NodeResult.of(node.id, rowsOut = 0L, startTime)
            }
        }
}
```

`NodeResult` is defined in §7 (alongside `NodeStats`, which is derived from it).

**Why the permit is taken *after* `awaitAll(deps)`:** taking it before would let `maxParallelNodes` coroutines sit blocked on dependencies while holding every permit, so the dependencies they wait for can never acquire one. Any chain longer than `maxParallelNodes` would deadlock. Waiting first costs nothing — a suspended `awaitAll` occupies no thread — and the permit then bounds only nodes doing actual SQL work.

**Key properties of this model:**
- Executor dispatches on `node.type` to choose `executeQuery` (DQL), `executeUpdate` (DML), or `execute` (DDL).
- DQL further dispatches on `node.output`: tempdb stage, caller materialization, or external-datasource write-back — with no assertion about the node's DAG position.
- DML and DDL have no `output` block; they record row count (DML) or success (DDL) only.
- The caller node is read off `NodeOutput.Caller` (§4.1), never derived from topology. `callerNodeId` may legitimately be `null`.
- `node_failed` is emitted exactly once, at the failure site. The outer handler emits only `pipeline_failed`.
- Every statement runs inside `handle.withStatement(...)`, which is what makes §8.3 cancellation able to interrupt in-flight SQL.

### 5.3 Concurrency controls

All limits are configured in [Configuration §3.2](configuration.md#32-executor) — the authority for names, defaults, and env-var forms. This spec references keys by name and never restates their values.

| Control | Config key | Enforcement point |
|---|---|---|
| Max parallel nodes per execution | `datapipelines.executor.max-parallel-nodes` | `nodePermits` semaphore inside `runExecution` (§5.2) |
| Max concurrent executions per user | `datapipelines.executor.max-concurrent-executions-per-user` | `ExecutionSlots.withSlot(userId)`, step 2 of §5.1 |
| Max concurrent executions (global) | `datapipelines.executor.max-concurrent-executions-global` | `ExecutionSlots.withSlot(userId)`, step 2 of §5.1 |
| JDBC query timeout (per node) | `datapipelines.executor.node-query-timeout-seconds` | `Statement.queryTimeout` on every node statement. A datasource's own `query_timeout_seconds`, when set, overrides it for nodes on that datasource ([Datasources §5](datasources.md#55-query-timeout-precedence)) — this is what `config.nodeQueryTimeoutSeconds(node.source)` resolves. |
| Execution overall timeout | `datapipelines.executor.execution-timeout-seconds` | `withTimeout(...)` wrapping the execution scope (§5.2) |
| Disconnect grace before cancellation | `datapipelines.sse.disconnect-grace-seconds` | SSE layer's grace timer, which calls into the cancellation registry (§8.3) |

When limits are exceeded, the request is rejected with `pipeline.execution.concurrency_limit` (per-user/global). Blowing the overall timeout fails the execution with `pipeline.execution.timeout` (status `FAILED` — a timeout is a failure, not a cancellation; `ABORTED` is reserved for the three cancellation paths in §8.3).

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
- Pool-acquisition timeout is the datasource's own Hikari setting (`properties.hikari.connectionTimeout`); the executor does not impose a second one. Exceeding it → `pipeline.node.datasource_connection_failed`.

For `NodeSource.Tempdb`:
- Use the per-execution tempdb connection (already open, single-connection in v1).
- No pool, no acquisition delay — but access is serialized by the staging `Mutex` (§9). A node reading tempdb may therefore wait behind a concurrent staging write.

### 6.3 Behavior by node `type`

The executor dispatches on `node.type` after acquiring the connection. In every case the statement is created inside `handle.withStatement(nodeId, stmt) { ... }` (§8.3) so that a cancellation can interrupt it, and its `queryTimeout` is set from `datapipelines.executor.node-query-timeout-seconds` (or the datasource override — §5.3).

#### 6.3.1 `DQL` — `executeQuery`

```kotlin
conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY).use { stmt ->
    stmt.queryTimeout = config.nodeQueryTimeoutSeconds(node.source)
    handle.withStatement(node.id, stmt) {
        val rs = stmt.executeQuery(sql)
        dispatchOnOutput(rs, node.output!!)     // consumed before the connection closes
    }
}
```

Behavior then branches on `node.output` (see §6.4). **All ResultSet consumption happens inside the `use` block** — the executor never returns a live `ResultSet` past the closing brace.

#### 6.3.2 `DML` — `executeUpdate`

```kotlin
conn.prepareStatement(sql).use { stmt ->
    stmt.queryTimeout = config.nodeQueryTimeoutSeconds(node.source)
    handle.withStatement(node.id, stmt) {
        recordRowsAffected(stmt.executeUpdate())
    }
}
```

Returns affected row count. No staging, no output block. Side-effect only.

#### 6.3.3 `DDL` — `execute`

```kotlin
conn.createStatement().use { stmt ->
    stmt.queryTimeout = config.nodeQueryTimeoutSeconds(node.source)
    handle.withStatement(node.id, stmt) { stmt.execute(sql) }
}
```

Returns success/failure. No staging, no output block.

### 6.4 DQL output dispatch

For DQL nodes, behavior depends on `node.output`:

#### 6.4.1 `output.target: "tempdb"` — stage ResultSet

Stream the ResultSet into the tempdb table named by `output.table`. The staging implementation (DDL generation, identifier quoting and column-name validation, type mapping, batch sizing) is owned by the [Staging spec](staging.md); the executor only calls `staging.stage(rs, table)`. Shape, for orientation:

```kotlin
fun stage(resultSet: ResultSet, tableName: String): Long = mutex.withLock {
    val metadata = resultSet.metaData
    val columnCount = metadata.columnCount
    val columnTypes = (1..columnCount).map { mapper.map(it, metadata) }

    connection.createStatement().use { stmt ->
        stmt.execute(buildCreateTableSql(tableName, columnTypes))   // identifiers validated + quoted
    }

    connection.prepareStatement(buildInsertSql(tableName, columnCount)).use { insert ->
        var rowCount = 0L
        val batchSize = config.insertBatchSize    // datapipelines.staging.h2.insert-batch-size
        while (resultSet.next()) {
            for (i in 1..columnCount) {
                insert.setObject(i, readValue(resultSet, i, columnTypes[i - 1]))
            }
            insert.addBatch()
            if (++rowCount % batchSize == 0L) insert.executeBatch()
        }
        if (rowCount % batchSize != 0L) insert.executeBatch()
        rowCount
    }
}
```

Streaming — constant memory regardless of result size. Downstream nodes reference this table by name in their SQL.

#### 6.4.2 `output.target: "caller"` — materialize to the result store

The caller node's ResultSet is **fully materialized into the Redis result store before the source connection closes**. There is no inline-vs-claim-check split and no live `ResultSet` (or JDBC cursor) outliving the node — the uniform result-delivery model is [REST API §7](rest-api.md#7-result-delivery).

```kotlin
// called from executeDql, INSIDE conn.use { ... } — the connection is still open
is NodeOutput.Caller -> {
    val stored = resultStore.materialize(executionId, rs)
    NodeResult.of(node.id, rowsOut = stored.totalRows, startTime,
                  callerResultRef = stored.key, bytesOutEstimate = stored.bytes)
}
```

`ResultStore.materialize` must:

1. Read the schema from `ResultSetMetaData` and convert it to the canonical column descriptors ([Type System §7](type-system.md#7-schema-envelope-structure)).
2. Drain the ResultSet row-by-row into the Redis-backed result, encoding values per the Type System's egress rules.
3. Track encoded size as it goes. Crossing `datapipelines.result.max-size-bytes` **aborts immediately** — the partial result is discarded and the node fails with `result.too_large` (execution → `FAILED`). Size is checked during the drain, not after, so an oversized result never has to be fully buffered.
4. Set the fixed expiry from the effective TTL — `clamp(DP-Result-TTL-Seconds, datapipelines.result.ttl-min-seconds, datapipelines.result.ttl-max-seconds)`, defaulting to `datapipelines.result.ttl-default-seconds` ([REST API §7.4](rest-api.md#74-ttl--fixed-client-influenced-clamped)).
5. Return a `StoredResult(key, totalRows, bytes, expiresAt)`. Only the **key** travels onward, in `NodeResult.callerResultRef`.

Failure modes:

| Condition | Error code | Outcome |
|---|---|---|
| Encoded result exceeds `datapipelines.result.max-size-bytes` | `result.too_large` | Node fails → execution `FAILED`; partial result discarded |
| Redis unreachable / write rejected during materialization | `result.storage_unavailable` | Node fails → execution `FAILED`. **No fallback to inline delivery** — a second delivery path is exactly the hole D9 closed. |

`data_ready` is then built by the executor **from the stored result**, not from the ResultSet: schema, the inline first page (up to `datapipelines.result.page-size-rows`), `total_rows`, `result_url`, and `expires_at` ([REST API §6.4.7](rest-api.md#647-data_ready)). If the pipeline has no caller node, no `data_ready` event is emitted at all (§4.1).

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

As with staging, the ResultSet is consumed entirely inside the `use` block, and every identifier the runner interpolates (`output.table`, column names taken from ResultSet metadata) is validated and quoted per the identifier-safety rules in the [Staging spec](staging.md) — the sketch above elides the quoting for readability.

The target table must already exist (created by a preceding DDL node in the pipeline, or pre-existing in the datasource). v1.1 will add `output.auto_create: true` to emit `CREATE TABLE IF NOT EXISTS` from ResultSet metadata.

Failure modes:
- Target table missing → `pipeline.node.writeback_target_missing`.
- INSERT failure (constraint violation, type mismatch, etc.) → `pipeline.node.writeback_failed` (transaction rolls back).

### 6.5 Reading upstream data

When a downstream node's SQL references upstream tables (e.g., `SELECT * FROM stg_orders`), it runs against the per-execution tempdb instance. The table exists because the upstream DQL node created it in §6.4.1.

**No cross-node data passing via Context.** Upstream data lives in tempdb tables (or, for write-back nodes, in external datasource tables); downstream templates reference those tables by name. Context carries only input parameters and (future) calculator outputs.

---

## 7. Node Stats Collection

### 7.1 `NodeResult` — the executor's in-flight per-node value

`NodeResult` is what a node coroutine returns (§5.2). It is an **internal** type: it never crosses the wire.

```kotlin
data class NodeResult(
    val nodeId: String,
    val status: NodeStatus,             // SUCCESS for a returned NodeResult; FAILED/ABORTED are synthesized
    val rowsOut: Long,                  // rows staged / written back / materialized; 0 for DDL
    val bytesOutEstimate: Long,         // estimated encoded size; -1 when not measurable
    val startedAt: Instant,
    val durationMs: Long,
    val callerResultRef: String?        // Redis KEY of the stored caller result — never a live ResultSet
) {
    companion object {
        fun of(
            nodeId: String,
            rowsOut: Long,
            startedAt: Instant,
            callerResultRef: String? = null,
            bytesOutEstimate: Long = -1
        ): NodeResult = /* status = SUCCESS, durationMs computed from startedAt */
    }
}
```

**`callerResultRef` is a reference, not data.** It is the Redis key produced by `ResultStore.materialize` (§6.4.2). By the time a `NodeResult` exists, the node's source connection and `ResultSet` are already closed. Nothing downstream may hold a JDBC cursor.

### 7.2 `NodeResult` → `NodeStats`

`NodeStats` is the **wire-facing** projection reported in `pipeline_completed` / `pipeline_failed` / `execution_aborted` payloads and persisted to `pipeline_executions` ([Metadata DB](metadata-db.md)). One `NodeStats` per node in the DAG — including nodes that never produced a `NodeResult`:

| Node outcome | Source of the `NodeStats` row |
|---|---|
| Succeeded | Projected from its `NodeResult` (`rowsOut`, `bytesOutEstimate`, timings). `callerResultRef` is **not** projected — the result cursor is carried by `data_ready`, not by stats. |
| Failed | Synthesized from the `NodeExecutionException` — `status = FAILED`, `rowsOut`/`bytesOut` = `-1`, plus `errorCode` / `errorMessage`. |
| Never started (dependency failed, or execution cancelled first) | Synthesized — `status = ABORTED`, `rowsOut`/`bytesOut` = `-1`, no timings. |

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

`ABORTED` = node never started — a dependency failed, or the execution was cancelled (§8.3) before this node ran.

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

// `cause` is passed to Throwable's constructor — NOT redeclared as a `val`.
// Shadowing it would hide the real cause from stack traces and logging.
class NodeExecutionException(
    val nodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>,
    cause: Throwable
) : PipelineException("Node $nodeId failed ($errorCode): ${cause.message}", cause)

class PipelineExecutionFailed(
    val failedNodeId: String,
    val errorCode: String,
    val errorDetails: Map<String, Any?>
) : PipelineException("Pipeline aborted: node $failedNodeId failed ($errorCode)")

class PipelineTimeoutException(
    val timedOutNodeId: String?,          // null if no node was mid-flight when the timeout fired
    val elapsedMs: Long
) : PipelineException("Pipeline timed out after ${elapsedMs}ms")

class PipelineConcurrencyLimitException(
    val scope: LimitScope                 // PER_USER or GLOBAL
) : PipelineException("Execution slot unavailable ($scope)")

// Cancellation, not failure — see §8.3. Reason values match the SSE payload
// (REST API §6.4.8): client_disconnect | cancelled | shutdown.
class ExecutionAbortedException(
    val reason: AbortReason
) : CancellationException("Execution aborted: $reason")

enum class AbortReason { CLIENT_DISCONNECT, CANCELLED, SHUTDOWN }
```

Construction rules (these are the shapes §5.2 actually throws):

- `NodeExecutionException` is built at the failure site in `executeNode`, from the mapped error code (§8.2) and the original exception as `cause`.
- `PipelineExecutionFailed(failedNodeId, errorCode, errorDetails)` — the three-argument form is the only form; the outer handler builds it from the `NodeExecutionException` it caught.
- `PipelineTimeoutException` is produced when `withTimeout` throws `TimeoutCancellationException`, and maps to `pipeline.execution.timeout` (status `FAILED`).
- `ExecutionAbortedException` extends `CancellationException` so structured concurrency unwinds normally; it maps to no error code — the execution ends `ABORTED` and emits `execution_aborted`.

### 8.2 Error code mapping

`mapToErrorCode(e)` (§5.2) resolves an internal exception to a code from the single catalog, [Pipeline Contract §13](pipeline-contract.md#13-error-code-catalog). This doc defines no codes of its own.

| Internal condition | Error code |
|---|---|
| `SQLException` during connection acquire | `pipeline.node.datasource_connection_failed` |
| `SQLException` during query execution | `pipeline.node.query_execution_failed` |
| Freemarker render error | `pipeline.node.template_render_failed` |
| Template not found at runtime | `pipeline.node.template_not_found` |
| Datasource not found at runtime | `pipeline.node.datasource_not_found` |
| Staging insert failed (generic) | `pipeline.node.staging_failed` |
| Write-back target table missing | `pipeline.node.writeback_target_missing` |
| Write-back INSERT failed (constraint, type mismatch — transaction rolled back) | `pipeline.node.writeback_failed` |
| Staging type overflow | `pipeline.staging.value_overflow` |
| Staging precision overflow | `pipeline.staging.precision_overflow` |
| Staging instance creation failure | `pipeline.staging.creation_failed` |
| Staging cleanup failure in `finally` (logged, does not change execution status) | `pipeline.staging.cleanup_failed` |
| Configured staging engine not on the classpath | `pipeline.staging.engine_unavailable` |
| Per-execution staging memory limit hit | `pipeline.staging.memory_limit_exceeded` |
| Invalid/duplicate source column name for a staged table | `pipeline.staging.invalid_column_name` |
| Caller result exceeds `datapipelines.result.max-size-bytes` | `result.too_large` |
| Redis unavailable while materializing the caller result | `result.storage_unavailable` |
| Execution slot unavailable (per-user or global) | `pipeline.execution.concurrency_limit` |
| Overall execution timeout (`withTimeout` fired) | `pipeline.execution.timeout` |
| Executor-internal failure with no more specific code | `pipeline.execution.aborted` |

Cancellation (§8.3) is deliberately absent from this table: an aborted execution reports status `ABORTED` via `execution_aborted` and carries **no** error code.

### 8.3 Cancellation

Cancellation is a first-class path, not a side effect of failure. Three triggers produce it, all converging on the same mechanism:

> A fourth `ABORTED` source exists but is **not** a cancellation: the crash sweep ([Metadata DB §8](metadata-db.md#8-operational-jobs)) flips RUNNING executions whose instance died. Nothing runs anymore, so there is no statement to cancel and no `execution_aborted` event — only the persisted status changes (recorded as `pipeline.execution.instance_lost`).

| Trigger | Initiated by | `execution_aborted.reason` |
|---|---|---|
| `DELETE /api/v1/executions/{id}` ([REST API §10.4](rest-api.md#104-cancel-execution)) | The owner (or an `admin`) | `cancelled` |
| SSE consumer gone longer than `datapipelines.sse.disconnect-grace-seconds` ([REST API §6.8](rest-api.md#68-client-disconnect)) | The SSE layer's grace timer | `client_disconnect` |
| Server shutdown drain ([Deployment](deployment.md)) | Lifecycle hook | `shutdown` |

#### 8.3.1 The registry

```kotlin
interface CancellationRegistry {
    fun register(executionId: UUID): CancellationHandle
    fun deregister(executionId: UUID)
    fun cancel(executionId: UUID, reason: AbortReason): Boolean   // false if unknown/terminal
    fun cancelAll(reason: AbortReason)                            // shutdown drain
}

interface CancellationHandle {
    fun bind(job: Job)                        // the execution's root Job

    /** Registers [stmt] against [nodeId] for the duration of [body], then deregisters it. */
    fun <T> withStatement(nodeId: String, stmt: Statement, body: () -> T): T
}
```

Every in-flight node holds exactly one registered `Statement` (§6.3). The registry is per-instance and in-memory, but **cancellation requests travel through Redis** so `DELETE /executions/{id}` works from ANY instance (the standard deployment has no sticky sessions):

1. The instance receiving the `DELETE` verifies the execution exists and is `RUNNING`, writes `dp:cancel:{execution_id}` = `{reason}` to Redis (TTL = execution timeout), and returns `204`.
2. The **executing** instance checks that key on its existing per-execution heartbeat tick (`datapipelines.sse.heartbeat-interval-seconds`, default 15s) and at every node boundary, and on a hit runs the full local cancel below (§8.3.2) — `Statement.cancel()` included, since the registry is local to it.

Worst-case cancellation latency is therefore ~one heartbeat interval; the common same-instance case (SSE disconnect grace, local `DELETE`) cancels immediately. Push-based fan-out (Redis pub/sub) is a ROADMAP refinement, not needed for correctness.

#### 8.3.2 Order of operations

`cancel(executionId, reason)` does, in this order:

1. **`Statement.cancel()` on every registered statement**, from the caller's thread — *before* touching coroutines. This is what actually interrupts a long-running query on the source database; the driver raises an `SQLException` on the thread blocked in `executeQuery`. Cancelling the coroutine first would only unblock the JVM side and leave the query running on the source server.
2. **Cancel the execution's root `Job`** with `ExecutionAbortedException(reason)`. Structured concurrency unwinds every node coroutine; `use` blocks release connections; pending nodes never start.
3. **Emit `execution_aborted`** (terminal event, [REST API §6.4.8](rest-api.md#648-execution_aborted)) with `reason`, `status: ABORTED`, and the node-stats snapshot (running/pending nodes report `ABORTED`).
4. **Run the `finally` block**: deregister from the registry, `DROP ALL OBJECTS` + close the tempdb connection (§9), release the execution slot.

Statements that ignore `cancel()` (some drivers, some statement kinds) are not waited on — they finish or hit their own `queryTimeout`. The connection is returned to the pool by `use` either way; a driver that cannot interrupt is a driver-quality issue, not an executor leak.

#### 8.3.3 Failure-driven cancellation

Node failure uses the same coroutine unwinding, without the registry entry point:

- `awaitAll(deps)` in downstream nodes rethrows the dependency's `NodeExecutionException`.
- `coroutineScope` cancels all child coroutines; running siblings have their registered `Statement.cancel()` invoked by the same handle.
- Pending nodes never start; they report `ABORTED` in stats while the execution reports `FAILED`.

The `finally` block runs unconditionally in all paths — failure, timeout, cancellation, success — guaranteeing tempdb cleanup, statement deregistration, and execution-slot release.

---

## 9. Tempdb Lifecycle Integration

The executor creates a tempdb instance per execution via `StagingFactory`, choosing the engine from `pipeline.settings.tempdb.engine`:

```kotlin
interface StagingFactory {
    fun create(executionId: UUID, engine: StagingEngine = StagingEngine.H2): Staging
}

interface Staging : AutoCloseable {
    val connection: Connection       // single connection in v1, Mutex-guarded
    suspend fun stage(resultSet: ResultSet, tableName: String): StageResult
    suspend fun query(sql: String): ResultSet
    suspend fun execute(sql: String): Long     // DDL/DML against tempdb
    fun stats(): StagingStats
    override fun close()
}
```

(`StageResult` — `tableName`, `rowsStaged`, `columns: List<ColumnSchema>` — and `StagingStats` are defined in [Staging §10](staging.md#10-the-staging-interface), which owns the `Staging` interface; only the `StagingFactory.create` signature is canonical here.)

This `StagingFactory.create(executionId, engine)` signature is **canonical** — the [Staging spec](staging.md) aligns to it. `engine` defaults to `StagingEngine.H2`; the executor passes `pipeline.settings.tempdb.engine` explicitly. Pipeline-level `settings.tempdb.config` overrides the global `datapipelines.staging.*` keys ([Configuration §3.3](configuration.md#33-staging-tempdb)).

**Lifetime.** The executor opens the staging connection at execution start and holds it for the execution's duration. The JDBC URL carries **no** `DB_CLOSE_DELAY` — default H2 semantics (the in-memory database dies when its last connection closes) are exactly what we want for a per-execution scratch database. `close()` in the executor's `finally` block is belt-and-braces: it issues `DROP ALL OBJECTS` and then closes the connection, so the memory is reclaimed at a known point rather than whenever GC happens to run. See the [Staging spec](staging.md) for engine configuration details.

**Concurrency.** v1 uses a single tempdb connection per execution, and **concurrent access to it does happen**: up to `datapipelines.executor.max-parallel-nodes` node coroutines may reach for tempdb at the same time (two siblings staging their ResultSets, or one staging while another reads a previously staged table). A JDBC `Connection` does not safely serialize concurrent callers on its own, so the staging implementation guards it with an explicit `Mutex` — every `stage`/`query` call takes the lock, and tempdb work therefore serializes even though the nodes run in parallel.

This is a deliberate v1 trade-off, not an absence of contention:

1. **Single Mutex-guarded connection** — chosen. The slow work in a typical pipeline is fetching from sources (datasource connections, fully parallel); tempdb writes are local and fast.
2. **Connection pool for tempdb** — would allow parallel staging ops. v1.1, if profiling shows the Mutex is a real bottleneck.

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
data class NodeCompleted(...) : ExecutionEvent()      // success only — never for a failed node
data class NodeFailed(...) : ExecutionEvent()
data class PipelineCompleted(...) : ExecutionEvent()
data class PipelineFailed(...) : ExecutionEvent()
data class ExecutionAborted(...) : ExecutionEvent()   // terminal, D7 cancellations — §8.3
data class DataReady(...) : ExecutionEvent()          // only when a caller node ran — §4.1
```

Wire names, payloads, and ordering guarantees are owned by [REST API §6.4](rest-api.md#64-event-types) and [Enums §11](enums.md#11-sseeventtype--pipeline-execution-event-types); this spec only says which event the executor emits where.

Emission rules the executor must honour:

- Per node: exactly one `node_started`, then exactly one of `node_completed` **or** `node_failed`. Never both (§5.2).
- Exactly one terminal event per execution: `pipeline_completed` (optionally followed by `data_ready`), `pipeline_failed`, or `execution_aborted`.
- `data_ready` is built from the **stored** result (§6.4.2) and is skipped entirely for zero-caller pipelines.

The `EventEmitter` implementation routes events to:
- The active SSE stream, if a consumer is still attached.
- The post-completion event log in Redis (1h) plus the durable `execution_events` record ([REST API §6.8](rest-api.md#68-client-disconnect)).
- Optional webhook subscribers (future).

Events continue to be emitted while no consumer is attached — the emitter never blocks on a reader. But a **missing consumer is not indefinitely tolerated**: if the SSE stream drops before the terminal event, the SSE layer starts the `datapipelines.sse.disconnect-grace-seconds` timer and cancels the execution when it elapses (§8.3). Executions do not outlive the caller that asked for them.

---

## 11. Idempotency

### 11.1 Within a single execution

A node executes at most once. No internal retries.

### 11.2 Across client retries

Client-supplied `Idempotency-Key` on the REST request is hashed with `pipeline_id + version + parameters` to form a cache key. The cache stores the execution ID for that key ([REST API §3.5](rest-api.md#35-idempotency)).

- Same key + same request body → server returns the original execution instead of re-executing.
- Same key + different request body → server rejects with `idempotency.key_reused_for_different_request`.
- Cache TTL: `datapipelines.idempotency.ttl-seconds` ([Configuration](configuration.md)).

Idempotency deduplicates **executions**, not streams: a retry after a disconnect attaches to the original execution's record and result (if it is still within its TTL), but it does not resume a dropped SSE stream — there is no stream resumption, and a disconnected execution is on the abort clock (§8.3). A retry arriving after the original was aborted gets that aborted execution's status, not a fresh run.

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
| Two parallel nodes touching tempdb at once | **Concurrent access does occur** (up to `max-parallel-nodes` coroutines). The single staging connection is guarded by a `Mutex` (§9), so tempdb operations serialize; a JDBC `Connection` alone would not make this safe |
| Tempdb cleanup vs. an in-flight query | `staging.close()` runs in the executor's `finally`, i.e. after the `coroutineScope` has joined every node coroutine (including cancelled ones). No node can be mid-query when cleanup runs |
| Cancellation racing a node that is finishing | `Statement.cancel()` on an already-completed statement is a no-op; the node's `NodeResult` is discarded when the scope unwinds. Terminal-event emission is single-shot — whichever of `pipeline_completed` / `execution_aborted` wins, the other is suppressed |

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

- **Unit tests for `Dag<T>`**: every algorithm (topological sort, cycle detection, and `independentBatches()` — still tested despite being a diagnostic-only API, §3.3) against small synthetic graphs covering diamond dependencies, self-loops, disconnected components, large fan-outs. Include nodes with no entry in the `dependencies` map (the `emptySet()` default path).
- **Unit tests for `executeNode`**: mocked template engine, datasource, tempdb, result store. Every code path: DQL with each output (tempdb / caller / datasource), DML, DDL; success; template failure; connection failure; query failure; staging failure; writeback failure. Assert `node_completed` is emitted on success **only**, and `node_failed` exactly once on failure.
- **Caller-path tests**: result materialized before the connection closes; `result.too_large` triggered by a result over the cap; `result.storage_unavailable` with Redis down; `data_ready` payload built from the stored result; a **zero-caller** pipeline emits `pipeline_completed` and no `data_ready`.
- **Deadlock regression test**: a linear chain strictly longer than `max-parallel-nodes` (e.g. 6 nodes with `max-parallel-nodes: 2`) completes. This is the test that fails if the parallelism permit is ever taken before `awaitAll(deps)` (§5.2).
- **Integration tests** with real H2 + Testcontainers-backed sources (PG, MySQL, etc.): end-to-end pipelines of varying DAG shapes (linear, diamond, fan-out, fan-in, mixed).
- **Concurrency tests**: parallel executions, parallel nodes within one execution, execution-slot exhaustion (`pipeline.execution.concurrency_limit`), concurrent tempdb access through the staging `Mutex`, per-node query timeouts, and the overall `withTimeout` firing (`pipeline.execution.timeout`).
- **Cancellation tests** (§8.3): each of the three triggers ends the execution `ABORTED` with the right `reason`, emits exactly one `execution_aborted`, interrupts an in-flight statement (assert on a deliberately slow query), and releases connections + slot. Also: cancelling an already-terminal execution is a no-op.
- **Failure-path tests**: every error code in §8.2 exercised by a test that triggers it.
- **Resource-leak tests**: run 100 executions back-to-back (mixing success, failure, and cancellation), verify staging instances, connections, statements, and semaphore permits are all released (no leaks).

---

## 15. Implementation Notes

### 15.1 Where this lives in the codebase

Implemented in the `dag` Gradle module:

- `co.datapipelines.dag.Dag` — the data structure
- `co.datapipelines.dag.DagBuilder`
- `co.datapipelines.executor.PipelineExecutor`
- `co.datapipelines.executor.ExecutableNode`, `NodeSource`, `NodeType`
- `co.datapipelines.executor.NodeResult`, `NodeStats`, `NodeStatus`
- `co.datapipelines.executor.CancellationRegistry`, `CancellationHandle`
- `co.datapipelines.executor.ResultStore` (Redis-backed caller-result materialization)
- `co.datapipelines.events.EventEmitter` (interface)
- `co.datapipelines.events.ExecutionEvent` (sealed class)

### 15.2 Coroutine context

- All executor code runs on a dedicated `ExecutorDispatcher` — a bounded IO dispatcher owned by the `dag` module, sized from `datapipelines.executor.max-concurrent-executions-global` × `max-parallel-nodes`. Executor code **never** references `Dispatchers.IO` directly: sharing the JVM-wide IO pool with Spring's own blocking work makes executor throughput a function of unrelated load, and makes starvation impossible to attribute.
- Each execution has its own `coroutineScope` under `withTimeout(...)`, so failure, timeout, or cancellation of one execution never affects another.
- `Job` cancellation is honored throughout — the three cancellation triggers in §8.3 all resolve to cancelling that scope's root `Job`, after the registered statements have been interrupted. The disconnect trigger's grace period is `datapipelines.sse.disconnect-grace-seconds`, and the timer is owned by the SSE layer, not the executor.

### 15.3 Monitoring

The executor exports Micrometer metrics:
- `datapipelines.executions.total{status=success|failed|aborted, pipeline_id}` — counter (tag set is normative in [Observability §4](observability.md#4-metrics))
- `datapipelines.executions.duration{pipeline_id=...}` — timer
- `datapipelines.executions.concurrent` — gauge
- `datapipelines.nodes.duration{pipeline_id=...,node_id=...}` — timer
- `datapipelines.staging.rows` — counter (total rows staged across all executions)
- `datapipelines.executions.aborted{reason=client_disconnect|cancelled|shutdown}` — counter

See [Observability spec](observability.md) for the full metric catalog, naming rules, and the result-store metrics that pair with §6.4.2.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial DAG executor spec: ~150-line Dag<T>, parallel execution via coroutines, fail-fast, SSE integration, idempotency |
| 2026-08-07 | v1.2 | consistency campaign | Applied [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.6 — **D1**: terminal-node auto-detection replaced by caller-node resolution (§4.1, §5.1, §5.2); omitted `output` resolves to `NodeOutput.Caller` at deserialization; zero-caller executions emit no `data_ready`; executor asserts nothing about DAG position. **D5**: `pipeline.staging.h2_creation_failed` → `pipeline.staging.creation_failed`; `idempotency.key_reused_for_different_request`; §8.2 table completed and re-pointed at pipeline-contract §13. **D6**: `StagingFactory.create(executionId, engine)` declared canonical; no `DB_CLOSE_DELAY`; explicit `DROP ALL OBJECTS` + close in `finally`; single connection Mutex-guarded and §12.1's "no concurrent tempdb access" claim corrected. **D7**: new §8.3 Cancellation — per-node `Statement` registry, `Statement.cancel()` before coroutine cancel, three triggers (DELETE / disconnect grace / shutdown), `execution_aborted` terminal event. **D8**: §5.3 limits reference configuration.md keys instead of restating defaults. **D9**: §6.4.2 caller path materializes the ResultSet into the Redis result store inside `connection.use`, enforcing `result.max-size-bytes` (`result.too_large`) and failing with `result.storage_unavailable`; `data_ready` built from the stored result. **[M]**: semaphore permit now acquired after `awaitAll(deps)` (chain-deadlock fix); execution-slot acquisition added; `withTimeout(execution-timeout-seconds)`; `node_completed` success-only and single `NodeFailed` emission; `NodeResult` defined with `callerResultRef` and its projection to `NodeStats` (§7); `PipelineExecutionFailed` constructor aligned with §8.1; `NodeExecutionException` passes `cause` to `Throwable` instead of shadowing it; `Dispatchers.IO` → `ExecutorDispatcher`; `Dag` dead no-op loop removed and `dependencies[id]!!` → `emptySet()` default; `independentBatches()` marked diagnostic/UI-only (§3.3); §4 retitled "Executor-Facing Model" and §8.3 "Cancellation" to fix inbound anchors; "(future)" removed from the observability link. |
| 2026-08-05 | v1.1 | propagation | Aligned with v1.1 Pipeline Contract. `NodeType.SQL` → `NodeType.{DQL, DML, DDL}`. `NodeSource.Staging` → `NodeSource.Tempdb`. Replaced `outputTable: String?` with sealed `NodeOutput` (Tempdb/Caller/Datasource). `executeNode` now dispatches on `type` then `output.target`. Added DML/DDL execution paths. Added write-back execution path (WritebackRunner) for `output.target: "datasource"`. Terminal auto-detected via `detectTerminal(dag)` instead of read from `pipeline.terminalNodeId`. Renamed §9 from "H2 Lifecycle" to "Tempdb Lifecycle" (engine-agnostic). |
