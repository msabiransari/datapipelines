# DAG Executor Specification

**Status:** v1.3 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract spec](pipeline-contract.md), [Templates spec](templates.md), [Datasources spec](datasources.md), [Staging spec](staging.md)
**Last updated:** 2026-09-03

---

## 1. Purpose

The Executor is the **runtime engine** that takes a validated Pipeline + input parameters and produces the result dataset. It is responsible for:

- Constructing an in-memory DAG from the Pipeline's `nodes` + `depends_on` declarations.
- Walking the DAG in dependency order, **parallelizing independent nodes** up to a configurable concurrency limit.
- For each node: rendering the template, executing SQL against the source (datasource or tempdb), dispatching on `type` (DQL/DML/DDL/PIPELINE) and `output.target` (tempdb/caller/datasource).
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

**Two internal refinements in the shipped `Dag<T>` — the public surface above is unchanged.**

1. **The reverse-edge index is precomputed.** §3.2's `dependentsOf` scans every node on each call, which makes `topologicalOrder()` O(n²). The edge set is fixed at construction, so the implementation builds `dependents: Map<String, Set<String>>` once in the constructor and `dependentsOf(id)` is a lookup. Same semantics, linear walk. (`pipeline.validation.pipeline_too_large` caps a pipeline at 1000 nodes, so this is not load-bearing — it is simply free.)
2. **Cycle detection is an iterative three-colour DFS.** The algorithm is §3.2's exactly; the recursion is replaced by an explicit stack. The executor also builds DAGs from paths that never passed save-time validation (defence-in-depth checks), and a `StackOverflowError` is not a diagnosable failure mode.

Also note two shapes §3.2's sketches get wrong and the implementation does not: `dependenciesOf(id)` returns `emptySet()` for a node with no entry in the dependency map (the sketch's `dependencies[id]!!` was an NPE waiting for the first root node), and `DagBuilder.build()` runs `detectCycle()` itself — so an acyclic graph is a **construction invariant**, which is what actually satisfies §5.1 step 6 rather than a separate call the executor could forget.

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

enum class NodeType { DQL, DML, DDL, PIPELINE }   // PIPELINE: runs a pinned child pipeline (§6.6)
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
   -------- pre-stream: no events have been emitted yet --------
4. Build DAG from pipeline.nodes and resolve the caller node — the single node with
   output = NodeOutput.Caller, or none (§4.1; no topology inspection — zero caller nodes
   is legal). The acyclicity check §5.1 asks for is performed by DagBuilder.build()
   itself (§3.1), so it cannot be skipped.
5. Bind parameters into the ExecutionContext (defaults + coercion, pipeline-contract §7.1).
   A rejected parameter is a 400-class failure with NO execution stream at all — §8.2
   catalogues no executor code for it.
   -------- the stream opens here --------
6. Emit execution_started event
7. Register the execution in the cancellation registry (§8.3) so DELETE /executions/{id},
   disconnect-grace expiry, and shutdown can reach it
8. Create per-execution tempdb instance via StagingFactory (engine from
   pipeline.settings.tempdb.engine) — INSIDE the try, so its catalogued failures
   (pipeline.staging.creation_failed, pipeline.staging.engine_unavailable) surface as
   execution_started + pipeline_failed rather than as zero events
9. Under withTimeout(executor.execution-timeout-seconds), walk the DAG with parallelism:
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
10. On any node failure:
    a. Emit node_failed event with error (exactly once, at the failure site)
    b. Cancel all running sibling coroutines (Statement.cancel() then coroutine cancel)
    c. Skip all pending nodes (report ABORTED status in stats)
    d. Emit pipeline_failed event
    e. Cleanup
11. On cancellation (DELETE / disconnect-grace / shutdown):
    a. Cancel every registered Statement, then the execution scope
    b. Emit execution_aborted event with reason and stats — status ABORTED
    c. Cleanup
12. On timeout: withTimeout throws → PipelineTimeoutException → pipeline_failed with
    pipeline.execution.timeout (status FAILED, not ABORTED). The registered statements
    are cancelled on the way out, so the source query stops too (§5.3).
13. On a catalogued setup failure (step 8): pipeline_failed with the raising module's own
    code, failed_node_id = null, every node ABORTED in the snapshot.
14. On success:
    a. Read the stored caller result back (resultStore.describe) — BEFORE the terminal
       event, so a vanished result fails the execution instead of degrading silently (§6.4.2)
    b. Emit pipeline_completed event with stats
    c. If a caller node ran: emit data_ready built from the stored result
       (schema + inline first page + result_url) — REST API §6.4.7.
       If there is no caller node: no data_ready event at all.
15. Cleanup: deregister execution, clear the Redis cancel flag, drop the staged tables and
    close the tempdb connection (§9), release execution slot
```

**Why `execution_started` comes before the allocations.** Steps 4–5 are pre-stream by construction: a malformed DAG or a rejected parameter produces a 400-class failure with no `execution_id` handed out and **no events**, and §8.2 catalogues no executor code for either. Everything the §8.2 catalogue *does* name — `pipeline.staging.creation_failed`, `pipeline.staging.engine_unavailable` — happens at step 8, **after** the emit. Creating staging first meant such a failure escaped with zero events on an execution the caller had already been given an id for; a client watching the stream saw nothing at all. The registry registration (step 7) sits between the two: it cannot fail (a concurrent-map put), and it must be in place before any node can start.

### 5.2 Concurrency model

```kotlin
class PipelineExecutor(
    private val nodeRunner: NodeRunner,                // groups templateEngine + datasourceRegistry
                                                       // + writebackRunner + resultStore — see below
    private val stagingFactory: StagingFactory,
    private val resultStore: ResultStore,               // Redis-backed — §6.4.2
    private val eventEmitter: EventEmitter,
    private val cancellationRegistry: CancellationRegistry,   // §8.3 — in-memory, per-instance
    private val cancellationFlags: CancellationFlags,  // §8.3.1 — the Redis cross-instance half
    private val executionSlots: ExecutionSlots,        // per-user + global permits — §5.3
    private val dispatcher: ExecutorDispatcher,        // §15.2 — never Dispatchers.IO directly
    private val config: ExecutorConfig,
    private val metrics: ExecutorMetrics,              // §15.3
    private val resultUrls: ResultUrlFactory           // absolute data_ready.result_url — REQUIRED,
                                                       // no default (rest-api §6.4.7)
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
        // Pre-stream (§5.1 steps 4-5): a rejected parameter is a 400 with no events at all.
        val context = ExecutionContext.from(request.parameters, request.pipeline.parameters)

        // §5.1 step 6 — the stream opens BEFORE any allocation whose failure §8.2 names.
        eventEmitter.emit(ExecutionStarted(executionId, request.pipeline, context))
        val handle = cancellationRegistry.register(executionId)   // §8.3

        var staging: Staging? = null
        try {
            // §5.1 step 8, inside the try: staging.creation_failed / staging.engine_unavailable
            // now reach the caller as execution_started + pipeline_failed.
            staging = stagingFactory.create(
                executionId,
                engine = request.pipeline.settings.tempdb.engine
            )
            withTimeout(config.executionTimeoutSeconds.seconds) {
                coroutineScope {
                    handle.bind(coroutineContext.job)   // DELETE / disconnect / shutdown reach us here
                    launch { pollCancelFlag(executionId, handle) }   // Redis cancel flag — §8.3.1

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

                    // §4.1: no caller node → no data_ready. Legal, not an error.
                    // The stored result is resolved BEFORE the terminal event: a vanished
                    // result must fail the execution, never degrade to a silent SUCCESS
                    // with no data_ready (§6.4.2).
                    val resultRef = callerNodeId?.let { results.getValue(it).callerResultRef }
                    val view = resultRef?.let {
                        resultStore.describe(it) ?: throw NodeExecutionException(
                            callerNodeId, "result.storage_unavailable", mapOf("result_ref" to it), ...)
                    }

                    val stats = nodeStatsSnapshot(results)
                    eventEmitter.emit(PipelineCompleted(executionId, stats))
                    view?.let {
                        eventEmitter.emit(DataReady.from(request.pipeline.id, it,
                                                         resultUrls.urlFor(executionId), ttlSeconds))
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
        } catch (e: DatapipelinesException) {
            // §5.1 step 13 — a catalogued setup failure (today: staging creation). Reaches the
            // stream as pipeline_failed with the raising module's code, failedNodeId = null.
            eventEmitter.emit(PipelineFailed(executionId, null, e.toApiError()))
            throw e
        } finally {
            cancellationRegistry.deregister(executionId)
            cancellationFlags.clear(executionId)   // §8.3.1 — the Redis flag is ours to clean up
            staging?.close()         // drops the staged tables, then closes the single
                                     // connection, which destroys the in-memory DB — §9
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
            // Third argument = this execution's output budget (§6.1), never the engine default.
            val sql = templateEngine.render(node.template, context.values, context.renderBudgetChars)
            // A tempdb node does NOT get a bare Connection: `staging.withConnection { conn -> ... }`
            // is the only route to it and holds the serialization mutex for the whole block (§9).
            val result = openConnection(node.source).use { conn ->
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
                    is NodeOutput.Tempdb ->             // sourceDialect, never H2's — §6.4.1
                        NodeResult.of(node.id, rowsOut = staging.stage(rs, output.table, dialect).rowsStaged, startTime)
                    is NodeOutput.Caller -> {
                        // Fully materialized into Redis INSIDE connection.use — §6.4.2
                        val stored = resultStore.materialize(executionId, rs, dialect, context.resultTtlSeconds)
                        NodeResult.of(node.id, rowsOut = stored.totalRows, startTime,
                                      callerResultRef = stored.key, bytesOutEstimate = stored.bytes)
                    }
                    is NodeOutput.Datasource ->
                        NodeResult.of(node.id, rowsOut = writebackRunner.writeback(rs, output, dialect), startTime)
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

**The collaborator list, as shipped.** The sketch above keeps `executeNode`/`executeDql`/`executeDml`/`executeDdl` inline for readability, but the implementation groups the four node-execution collaborators — `templateEngine`, `datasourceRegistry`, `writebackRunner`, `resultStore` — behind a single `NodeRunner`, so node execution is unit-testable with no event emitter and no slot pool. A `pipelineExecutor(...)` factory function preserves this spec's construction shape: it takes exactly the collaborators named here, in this order, and assembles the `NodeRunner` itself. Three collaborators are additions to the v1.2 list:

| Collaborator | Why it is here |
|---|---|
| `cancellationFlags: CancellationFlags` | The Redis half of §8.3.1. The registry is per-instance; the flag is what lets a `DELETE` landing on another instance reach this one. Also cleared in the executor's `finally`. |
| `metrics: ExecutorMetrics` | §15.3. Defaults to an in-memory registry so a directly-constructed executor needs no Micrometer wiring in tests. |
| `resultUrls: ResultUrlFactory` | Builds `data_ready.result_url`. **Required, no default** — [REST API §6.4.7](rest-api.md#647-data_ready) requires an absolute URL, and the executor cannot know its own host; a relative default would ship a wire-invalid payload to every caller that forgot to override it. `web` supplies the absolute builder from its configured base URL. |

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
| Max concurrent executions (per instance — 050/R2) | `datapipelines.executor.max-concurrent-executions-per-instance` | `ExecutionSlots.withSlot(userId)`, step 2 of §5.1 |
| JDBC query timeout (per node) | `datapipelines.executor.node-query-timeout-seconds` | `Statement.queryTimeout` on every node statement. A datasource's own `query_timeout_seconds`, when set, overrides it for nodes on that datasource ([Datasources §5](datasources.md#55-query-timeout-precedence)) — this is what `config.nodeQueryTimeoutSeconds(node.source)` resolves. |
| Execution overall timeout | `datapipelines.executor.execution-timeout-seconds` | `withTimeout(...)` wrapping the execution scope (§5.2). On expiry the executor also calls `Statement.cancel()` on every registered statement (§8.3.1) — see below. |
| Disconnect grace before cancellation | `datapipelines.sse.disconnect-grace-seconds` | SSE layer's grace timer, which calls into the cancellation registry (§8.3) |

When limits are exceeded, the request is rejected with `pipeline.execution.concurrency_limit` (per-user/global). Blowing the overall timeout fails the execution with `pipeline.execution.timeout` (status `FAILED` — a timeout is a failure, not a cancellation; `ABORTED` is reserved for the three cancellation paths in §8.3).

**The timeout reaches the source query, not just the coroutine.** `withTimeout` cancels the execution scope, but a node blocked inside a blocking JDBC call observes nothing until that call returns — so on its way out the executor invokes `CancellationHandle.cancelStatements()` (§8.3.1), interrupting every registered statement exactly as a cancellation would. This is `cancelStatements()` and **not** `CancellationRegistry.cancel(...)`: the latter would set an abort reason and relabel the timeout as `ABORTED`. The interrupt is hung off the cancel-flag poller's own cancellation rather than a second timer, so there is one deadline, not two that can fire in either order.

**Residual overshoot.** A driver that ignores `Statement.cancel()` (some drivers, some statement kinds) is not waited on: it finishes or hits its own `queryTimeout`. So the worst case is `execution-timeout-seconds` plus up to one `node-query-timeout-seconds` (or the datasource's own `query_timeout_seconds` override) of overshoot on the source server, not an unbounded one. The connection is returned to the pool by `use` either way.

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
    ),
    maxOutputChars = executionRenderBudgetChars     // see below — always passed explicitly
)
```

**The third argument is the per-execution output budget.** `render(ref, context, maxOutputChars)` is the shipped signature; the two-argument call is the engine-wide backstop's default and the executor never relies on it. The executor computes the budget from this execution's *effective* staging memory — `min(effective max_memory_mb × 1 MB ÷ Char.SIZE_BYTES, engine backstop)` — because rendered SQL larger than the whole tempdb budget cannot usefully be executed anyway. The `min` matters in both directions: on a default deployment the staging budget is far larger than the engine backstop, so passing a per-execution budget must never *raise* the global ceiling.

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

Stream the ResultSet into the tempdb table named by `output.table`. Everything — DDL generation, identifier quoting and column-name validation, source-dialect type mapping, batch sizing, the serialization lock — is owned by the [Staging spec](staging.md); the executor only calls

```kotlin
val result = staging.stage(rs, output.table, node.source.dialect)   // Staging §3.2/§10
// result.warnings (Staging §8.2) are folded into the execution result's warnings; never fatal.
```

passing the **source node's dialect** so source columns are mapped by that dialect's mapper, not H2's. `stage` is `suspend`, takes the internal mutex itself, and streams in constant memory regardless of result size; the executor never touches the staging connection directly. Downstream nodes reference this table by name in their SQL.

After the staged write returns, the executor re-checks the execution's effective memory budget itself. Staging enforces the budget it was *constructed* with — the operator-global `datapipelines.staging.h2.max-memory-mb`, because `StagingFactory.create(executionId, engine)` carries no budget parameter — so a **lower** per-pipeline `settings.tempdb.config.max_memory_mb` would otherwise never reach it. Crossing it is `pipeline.staging.memory_limit_exceeded`.

##### `tempdb` → `tempdb`: a single `CREATE TABLE … AS`

A DQL node whose source **and** output are both tempdb does **not** run cursor-plus-`stage()`. It cannot: `withConnection`/`withQuery` hold a serialization mutex that is **not reentrant** ([Staging §9.2](staging.md#92-serialization-is-explicit--mutex-not-the-driver)), so calling `stage()` from inside a cursor over the same connection deadlocks by construction. Such a node runs as one statement instead:

```kotlin
staging.withConnection { conn ->                       // one lock acquisition for the whole block
    conn.createStatement().use { stmt ->
        stmt.queryTimeout = nodeQueryTimeoutSeconds    // staging sets none on withConnection
        handle.withStatement(node.id, stmt) {          // registered for Statement.cancel() — §8.3
            stmt.executeUpdate("CREATE TABLE $quotedTable AS $sql")
            countRows(stmt, quotedTable)               // SELECT COUNT(*) — see §7.1
        }
    }
}
// then, explicitly, the budget check `stage()` would have done for us:
checkStagingBudget(node, ctx)                          // → pipeline.staging.memory_limit_exceeded
```

Three consequences worth stating, because they are not obvious from the `stage()` path:

1. **The row count is a second statement.** `executeUpdate` on a CTAS returns `0` on H2 (verified against the pinned 2.3.232 driver) — the JDBC contract only promises a count for DML, and a CTAS is DDL. The `SELECT COUNT(*)` runs inside the **same** `withConnection` block, so no concurrent node can write to the table between the create and the count (§7.1).
2. **The memory budget is re-checked explicitly.** `withConnection` performs none of the accounting `stage()` does, so without this a `CREATE TABLE AS SELECT` over a generated range would blow straight through the ceiling the staged path enforces.
3. **Five §8.2 rows are unreachable on this shape.** The copy never leaves H2, so no row-by-row insert and no column mapping happens in the executor: `pipeline.staging.invalid_column_name`, `pipeline.staging.value_overflow`, `pipeline.staging.precision_overflow`, and `pipeline.node.staging_failed`-via-a-failed-insert cannot arise. Nor can `pipeline.staging.table_already_exists` — a duplicate target yields a raw H2 "table already exists" error, which maps by phase to `pipeline.node.staging_failed`; `pipeline.validation.duplicate_output_table` catches that case at save time anyway. Those rows remain reachable on the datasource→tempdb path, which does go through `stage()`.

#### 6.4.2 `output.target: "caller"` — materialize to the result store

The caller node's ResultSet is **fully materialized into the Redis result store before the source connection closes**. There is no inline-vs-claim-check split and no live `ResultSet` (or JDBC cursor) outliving the node — the uniform result-delivery model is [REST API §7](rest-api.md#7-result-delivery).

```kotlin
// called from executeDql. For a tempdb source, the drain runs INSIDE staging.withConnection
// (Staging §10, §9.2) so the staging lock is held for the whole materialization — a
// concurrent stage()/execute() on the shared connection cannot interleave with the open
// cursor. For an external-datasource source, the drain runs inside that source's conn.use{}.
is NodeOutput.Caller -> {
    val stored = resultStore.materialize(executionId, rs, sourceDialect, ttlSeconds)
    NodeResult.of(node.id, rowsOut = stored.totalRows, startTime,
                  callerResultRef = stored.key, bytesOutEstimate = stored.bytes)
}
```

The shipped signature is `materialize(executionId, resultSet, sourceDialect, ttlSeconds)`. Both added parameters are load-bearing: `sourceDialect` is the dialect the cursor came from (`Dialect.H2` only when the source really is tempdb), and `ttlSeconds` is the **already-clamped** effective TTL, resolved once per execution rather than re-derived inside the store.

`ResultStore.materialize` must:

1. Read the schema from `ResultSetMetaData` and convert it to the canonical column descriptors ([Type System §7](type-system.md#7-schema-envelope-structure)), mapping every column through the **source dialect's** ingress mapper — never H2's. This is the same rule [Staging §3.2](staging.md#32-population) states for ingress and for the same reason: a source dialect's JDBC codes and type names do not mean what another's mean, so mapping them through the wrong table mislabels the wire schema.
2. Drain the ResultSet row-by-row into the Redis-backed result, encoding values per the Type System's egress rules.
3. Track encoded size as it goes. Crossing `datapipelines.result.max-size-bytes` **aborts immediately** — the partial result is discarded and the node fails with `result.too_large` (execution → `FAILED`). Size is checked during the drain, not after, so an oversized result never has to be fully buffered.
4. Set the fixed expiry from the effective TTL it was handed — `clamp(DP-Result-TTL-Seconds, datapipelines.result.ttl-min-seconds, datapipelines.result.ttl-max-seconds)`, defaulting to `datapipelines.result.ttl-default-seconds` ([REST API §7.4](rest-api.md#74-ttl--fixed-client-influenced-clamped)). The clamp is applied by the executor before the call; the store applies the value, it does not re-derive it.
5. Return a `StoredResult(key, totalRows, bytes, expiresAt, warnings)`. Only the **key** travels onward, in `NodeResult.callerResultRef`; the non-fatal type-mapping warnings are folded into the execution result's warnings alongside staging's.

**Ordering rule — the stored result is read back before `pipeline_completed`.** On the success path the executor calls `resultStore.describe(resultRef)` *first*, and only then emits the terminal event. If `describe` returns null — the key is gone or its TTL elapsed between the drain and here — the execution **fails** with `result.storage_unavailable`; it never degrades to a silent no-`data_ready`.

This ordering is the whole point. Emitting `pipeline_completed` first and only then discovering the result had vanished leaves exactly one legal-looking outcome: `SUCCESS` with no `data_ready`, which is **wire-identical** to a legal zero-caller run (§4.1, §10). A caller pipeline that silently returns no data is the worst available failure mode, and D9's "no fallback, fail loud" rules it out. The failure is raised against the caller node and lands on the ordinary `pipeline_failed` path — deliberately with **no** `node_failed`, because the node itself succeeded and already emitted `node_completed`, and §10 permits exactly one of the two per node.

Failure modes:

| Condition | Error code | Outcome |
|---|---|---|
| Encoded result exceeds `datapipelines.result.max-size-bytes` | `result.too_large` | Node fails → execution `FAILED`; partial result discarded |
| Redis unreachable / write rejected during materialization | `result.storage_unavailable` | Node fails → execution `FAILED`. **No fallback to inline delivery** — a second delivery path is exactly the hole D9 closed. |
| Stored result gone / expired when read back for `data_ready` | `result.storage_unavailable` | Execution `FAILED` via `pipeline_failed` (no `node_failed` — see the ordering rule above). |
| Stored header present but unreadable (corrupt or foreign payload) | `result.storage_unavailable` | Same — a parse fault is treated as "the result is unavailable", not as a bare 500. |

`data_ready` is then built by the executor **from the stored result**, not from the ResultSet: schema, the inline first page (up to `datapipelines.result.page-size-rows`), `total_rows`, `result_url`, and `expires_at` ([REST API §6.4.7](rest-api.md#647-data_ready)). If the pipeline has no caller node, no `data_ready` event is emitted at all (§4.1).

#### 6.4.3 `output.target: "datasource"` — write-back

The ResultSet is streamed to the external datasource's table via batch INSERT, mediated by `WritebackRunner`:

```kotlin
fun writeback(rs: ResultSet, output: NodeOutput.Datasource, sourceDialect: Dialect): Long {
    val targetPool = datasourceRegistry.poolFor(output.datasource)
    targetPool.connection.use { targetConn ->
        targetConn.autoCommit = false
        try {
            if (output.mode == WriteMode.REPLACE) clearTarget(targetConn, output.table)
            val rowsWritten = streamInsert(rs, targetConn, output.table)
            targetConn.commit()
            return rowsWritten
        } catch (e: SQLException) {
            targetConn.rollback()
            throw mapWriteFailure(e, output)
        }
    }
}
```

`sourceDialect` is the third parameter for the same reason `Staging.stage` takes it ([Staging §3.2](staging.md#32-population)): values must be read through the **source** dialect's canonical mapping, or a `getObject` on a driver-object column ships Java identity text into the target table.

**The `REPLACE` truncate runs under a savepoint.** `TRUNCATE TABLE` falls back to `DELETE FROM` for a dialect that does not support it — but on Postgres a failed statement poisons the entire transaction, so a bare try/catch fallback would commit nothing and report "current transaction is aborted" instead of the real outcome. The runner therefore takes a savepoint before the `TRUNCATE` attempt, releases it on success, and rolls back **to the savepoint** before trying `DELETE`. A missing-table error is rethrown rather than retried as `DELETE`, so it surfaces as itself.

**Target-missing is detected by SQLState, not by message text.** The set is `42S02` (the XOPEN/ODBC spelling — MySQL, MSSQL and H2), `42P01` (Postgres' own `undefined_table`), and `42S03` (H2's table-not-found-with-candidates, raised when a name resolves to nothing but a case-variant of it exists — exactly what a quoted lowercase `output.table` hits against a target whose DDL was upper-folded). Anything else in class 42 is a different syntax/access error and must **not** be reported as a missing table.

As with staging, the ResultSet is consumed entirely inside the `use` block, and every identifier the runner interpolates (`output.table`, column names taken from ResultSet metadata) is validated and quoted per the identifier-safety rules in [Staging §4.5](staging.md#45-identifier-safety-normative) — the sketch above elides the quoting for readability. Rows written are the **sum of the per-statement counts** returned by `executeBatch()`, not the length of that array (`SUCCESS_NO_INFO` counts as one row; `EXECUTE_FAILED` counts as none).

The target table must already exist (created by a preceding DDL node in the pipeline, or pre-existing in the datasource). v1.1 will add `output.auto_create: true` to emit `CREATE TABLE IF NOT EXISTS` from ResultSet metadata.

Failure modes:
- Target table missing → `pipeline.node.writeback_target_missing`.
- INSERT failure (constraint violation, type mismatch, etc.) → `pipeline.node.writeback_failed` (transaction rolls back).
- Target datasource not registered at runtime → `pipeline.node.datasource_not_found`.
- A rollback that itself fails is logged and dropped — letting it escape would replace the real cause with a secondary one the author cannot act on.

### 6.5 Reading upstream data

When a downstream node's SQL references upstream tables (e.g., `SELECT * FROM stg_orders`), it runs against the per-execution tempdb instance. The table exists because the upstream DQL node created it in §6.4.1.

**No cross-node data passing via Context.** Upstream data lives in tempdb tables (or, for write-back nodes, in external datasource tables); downstream templates reference those tables by name. Context carries only input parameters and (future) calculator outputs.

### 6.6 Pipeline composition: `direct` delivery, slots, and cancellation

A `PIPELINE` node runs its child as a real, separate execution through the internal execution service — never HTTP — with three executor-level consequences, all carried on `ExecuteRequest` (lineage fields `parentExecutionId` / `parentNodeId` / `rootExecutionId` / `compositionDepth`, and `directSink`; the persisted columns are [Metadata DB §4.6](metadata-db.md)).

**`direct` result delivery.** A child request can carry a `DirectResultSink`. When it does, the child caller node's ResultSet is **not** materialized into the Redis result store (§6.4.2): it streams synchronously to the sink instead, with schema and rows decoded through the same `ResultRowReader` path `ResultStore.materialize` uses, so both modes see identical wire values. Nothing is written to Redis, there is no cursor, and the result is not re-fetchable after consumption — re-running the child is the recovery path. The node reports `rowsOut` = the delivered row count with `callerResultRef = null`, so the child's success path emits no `data_ready` (§4.1). The mode is internal-only; REST/MCP callers already have SSE + the cursor. The store side of the contract is `ResultStore.materializeRows(executionId, schema, rows, ttlSeconds)`, which stores an **already-decoded** schema + rows under exactly the §6.4.2 rules — same key layout, during-drain size cap, fixed expiry — so a parent PIPELINE node whose own `output.target` is `"caller"` can re-publish a child's streamed rows as an ordinary cursor result.

**Root-only concurrency slots.** Only a *root* execution takes an execution slot (§5.1 step 2, §5.3). A child request — one carrying `rootExecutionId` — skips slot admission entirely: a parent holding a slot while its children queued for their own would deadlock any family larger than the cap. Composition volume is instead bounded by the composition-depth guard and the per-pipeline node cap.

**Family cancellation.** Cancellation flags stay keyed per execution id (`dp:cancel:{execution_id}`, §8.3.1). A child execution reads **two** flags on the poll tick and at every node boundary — its own and its root's. Cancelling any ancestor therefore stops every descendant (children are never left running after their parent stops), while a child's own flag still stops it alone.

**The wiring (2026-08-17).** `web`'s `SubPipelineExecutionRunner` implements the `SubPipelineRunner` port: it loads the pinned child body by name+version (soft-deleted pipelines still resolve — D7), builds the child `ExecuteRequest` with the parent's principal (D9), the lineage fields, `triggeredVia = PIPELINE` and `compositionDepth = parent + 1` (refusing beyond `datapipelines.pipelines.max-composition-depth` with `pipeline.node.composition_depth_exceeded` — the runtime backstop to §12.9's static check), and a recording emitter so the child's rows land in execution history (D6). The `directSink` adapter per the node's own `output` target: `tempdb` stages the child's decoded rows into the PARENT's tempdb (`Staging.stageRows`, staging §10), `caller` re-publishes them under the parent's execution id (`ResultStore.materializeRows`) — or, when the parent execution is itself a `direct` child, streams them onward to its own invoker's sink, exactly like a DQL caller node under `direct` delivery — `datasource` writes them back (`WritebackRunner.writebackRows`). A failed child fails the parent node fail-fast with `pipeline.node.child_execution_failed`; the detail carries the child's error code, its failed node, and the child execution id. The node's `NodeResult`/`NodeStats` gain `childExecutionId` (serialized `child_execution_id`, omitted for every other node type), which is also how the parent's `node_completed` SSE event links to the child's stream ([REST API §6.4.3](rest-api.md#643-node_completed)).

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
    val completedAt: Instant,
    val durationMs: Long,
    val callerResultRef: String?,       // Redis KEY of the stored caller result — never a live ResultSet
    val childExecutionId: UUID? = null  // PIPELINE nodes only: the spawned child execution (§6.6)
) {
    companion object {
        fun of(
            nodeId: String,
            rowsOut: Long,
            startedAt: Instant,
            callerResultRef: String? = null,
            bytesOutEstimate: Long = NOT_MEASURED,   // = -1
            completedAt: Instant = Instant.now()
        ): NodeResult = /* status = SUCCESS, durationMs computed from startedAt..completedAt */

        const val NOT_MEASURED = -1L    // the "not measured" sentinel for rowsOut / bytesOut
    }
}
```

**Where `rowsOut` comes from, by node shape.** Staged (`stage()`) → `StageResult.rowsStaged`. Caller → `StoredResult.totalRows`. Write-back → the summed batch counts (§6.4.3). DML → `executeUpdate()`. DDL → `0`. **A `tempdb`→`tempdb` CTAS node is the exception**: `executeUpdate` on `CREATE TABLE … AS` returns `0` on H2, so `rowsOut` there is a post-create `SELECT COUNT(*)` over the new table, taken inside the same `withConnection` block (§6.4.1). Reporting the driver's `0` would put a silent lie in every `node_completed` payload and in `node_stats_json` for the commonest node shape there is. The honest bound: because the rendered SQL is author-authored and H2 accepts multiple statements, an author who appends their own `INSERT` can still influence that number — so `rowsOut` on this shape means "rows in the table when the node finished", not a tamper-proof count of the projection.

**`callerResultRef` is a reference, not data.** It is the Redis key produced by `ResultStore.materialize` (§6.4.2). By the time a `NodeResult` exists, the node's source connection and `ResultSet` are already closed. Nothing downstream may hold a JDBC cursor.

### 7.2 `NodeResult` → `NodeStats`

`NodeStats` is the **wire-facing** projection reported in `pipeline_completed` / `pipeline_failed` / `execution_aborted` payloads and persisted to `pipeline_executions` ([Metadata DB](metadata-db.md)). One `NodeStats` per node in the DAG — including nodes that never produced a `NodeResult`:

| Node outcome | Source of the `NodeStats` row |
|---|---|
| Succeeded | Projected from its `NodeResult` (`rowsOut`, `bytesOutEstimate`, timings). `callerResultRef` is **not** projected — the result cursor is carried by `data_ready`, not by stats. |
| Failed | Synthesized from the `NodeExecutionException` — `status = FAILED`, `rowsOut`/`bytesOut` = `-1`, plus `errorCode` / `errorMessage`. |
| Never started (dependency failed, or execution cancelled first) | Synthesized — `status = ABORTED`, `rowsOut`/`bytesOut` = `-1`, no timings. |
| Stopped mid-flight by the execution ending | Synthesized — `status = ABORTED`, `rowsOut`/`bytesOut` = `-1`, `startedAt` retained, **plus `errorCode`/`errorMessage`** carrying what the node actually hit. See below. |

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
    val errorMessage: String?,
    val childExecutionId: UUID? = null  // PIPELINE nodes only (§6.6); serialized child_execution_id, omitted otherwise
)

enum class NodeStatus { SUCCESS, FAILED, ABORTED }
```

`ABORTED` = the node did not fail on its own merits. Usually that means it never started — a dependency failed, or the execution was cancelled (§8.3) before it ran.

**An `ABORTED` row MAY carry `error_code` / `error_message`.** When the executor stops a node that was *mid-flight* — `Statement.cancel()` fired to serve a `DELETE`, a disconnect, or the timeout unwind — the driver raises on the thread blocked in `executeQuery`. That exception is a *consequence* of the decided outcome, not an independent failure, so no `node_failed` event is emitted for it (§8.3 says a cancellation carries no error code, and §10 allows exactly one terminal event). But the reason is still recorded in stats: without it, a terminal snapshot shows a bare `ABORTED` and an operator cannot tell a clean interrupt from one that hit something else on the way out.

The two are distinguishable on the wire: **`started_at` is non-null** on an aborted-with-cause row and null on a never-started one. `completed_at` is null and `duration_ms` is `0` on both. The status stays `ABORTED` deliberately — reporting `FAILED` with `pipeline.node.query_execution_failed` would be the same mislabel at stats level that §8.3 forbids at event level.

It is fired from exactly two places, both in the executor: the abort funnel (a failure raised while `CancellationHandle.abortReason` is already set), and the suppressed cancel-driver-error path (a failure raised while the scope is unwinding with an outcome already decided). Every other failure is a plain `FAILED` row with its `node_failed` event.

The pipeline aggregates these into the response:

```json
"node_stats": [
  {"node_id": "fetch_orders", "status": "SUCCESS", "started_at": "...", "completed_at": "...", "duration_ms": 1266, "rows_out": 12453, "bytes_out": 4567890},
  {"node_id": "fetch_customers", "status": "SUCCESS", "started_at": "...", "completed_at": "...", "duration_ms": 850, "rows_out": 5400, "bytes_out": 1200000},
  {"node_id": "revenue_by_customer", "status": "SUCCESS", "started_at": "...", "completed_at": "...", "duration_ms": 200, "rows_out": 4500, "bytes_out": 800000},
  {"node_id": "final_report", "status": "FAILED", "started_at": "...", "completed_at": "...", "duration_ms": 60, "rows_out": -1, "bytes_out": -1, "error_code": "pipeline.node.query_execution_failed", "error_message": "..."}
]
```

### 7.3 The Context snapshot — `pipeline_executions.parameters_json`

072 (calculators design §0.5). The terminal UPDATE writes the **fully resolved execution Context** into `parameters_json`: org config, the platform keys, the declared parameters after defaulting, the execute-time inputs, and every `CALCULATOR` node's output — everything the nodes actually saw.

**The column name is historical.** It held the request's `parameters` object as the caller sent it, which was the whole Context when the Context was only parameters. It is not renamed because a rename is a migration, a re-read of every consumer and a break for anything already querying it, in exchange for a better name — and this sentence is cheaper. [Metadata DB §4.6](metadata-db.md#46-pipeline_executions) says the same thing beside the column.

Why the snapshot is worth a column at all: without it a completed execution cannot answer *what fiscal quarter did this run report on?* The parameters the caller sent do not contain the answer — a calculator computed it, from configuration that may since have changed. An execution record that cannot reproduce its own inputs is a record you cannot audit, and a promoted pipeline whose numbers are questioned a month later is exactly when it is asked.

Written from the terminal event (`pipeline_completed` / `pipeline_failed` / `execution_aborted`), each of which carries the snapshot, so the executor stays free of the database. A serialization failure leaves the insert-time value in place rather than losing the terminal UPDATE with it.

Per-node, a `CALCULATOR` node's `NodeStats` also carries `context_key` and `context_value` (§7.2), so the run detail page and `executions_get` show what each calculator produced without reading the whole snapshot.

---

## 8. Error Propagation

### 8.1 Exception hierarchy

```kotlin
// Extends the shared base from `typesystem` (module-structure §4.3), NOT RuntimeException
// directly: DatapipelinesException is what carries the `code` / `details` the unified error
// response is built from, and every module's exceptions extend it. Extending RuntimeException
// would leave the executor as the one module whose failures the global handler cannot render
// structurally. (DatapipelinesException is itself a RuntimeException, so §8.1's original
// intent is preserved.)
sealed class PipelineException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null
) : DatapipelinesException(code, message, details, cause)

// `cause` is passed to the base constructor — NOT redeclared as a `val`.
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
    val scope: LimitScope,                // PER_USER or GLOBAL
    val limit: Int                        // the limit that refused it — the operator's next question
) : PipelineException(/* pipeline.execution.concurrency_limit */)

enum class LimitScope(val wire: String) { PER_USER("per_user"), GLOBAL("global") }

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
| Datasource readonly at runtime (live-entry backstop) | `pipeline.node.datasource_readonly` |
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
| Staged `CREATE TABLE` targets a name already staged in this execution | `pipeline.staging.table_already_exists` |
| Caller result exceeds `datapipelines.result.max-size-bytes` | `result.too_large` |
| Redis unavailable while materializing the caller result | `result.storage_unavailable` |
| Execution slot unavailable (per-user or global) | `pipeline.execution.concurrency_limit` |
| Overall execution timeout (`withTimeout` fired) | `pipeline.execution.timeout` |
| Executor-internal failure with no more specific code | `pipeline.execution.aborted` |

**The table applies only to raw driver and unknown exceptions.** A collaborator that raises a `DatapipelinesException` has already chosen its catalog code — the template engine's `template_not_found` vs `template_render_failed` split, staging's `value_overflow` / `memory_limit_exceeded` / `invalid_column_name`, the result store's `too_large` / `storage_unavailable`. That code always wins; re-deriving one from the exception's Java type would silently overwrite a precise code with a coarser one. What the mapper adds in that case is only the structured detail (`node_id`, `phase`).

For anything else, the **phase** the node was in is what disambiguates: a `SQLException` is the same class whether it surfaced acquiring a connection (`datasource_connection_failed`), executing (`query_execution_failed`), staging (`staging_failed`) or writing back (`writeback_failed`), so the phase is carried explicitly rather than guessed from the message. Reflected driver text is bounded at 2000 characters before it is copied into `MappedError` — H2, MSSQL and Oracle append the whole failing statement to `SQLException.message`, and that string otherwise propagates into `node_stats_json`, both SSE payloads, the Postgres `error_json`, and every log line that prints it.

**Runtime identifier refusals report a *phase* code, never a validation one.** A generated identifier that fails the identifier rule mid-execution surfaces as `pipeline.node.staging_failed` (the tempdb CTAS table name) or `pipeline.node.writeback_failed` (the write-back table name); a bad staged **column** label surfaces as `pipeline.staging.invalid_column_name`. There is deliberately **no** `pipeline.validation.invalid_identifier` row in this table: that code is save-time and HTTP-400 ([Pipeline Contract §12](pipeline-contract.md#12-validation-rules)), and raising it from a running execution would make §8.2 incoherent and send an operator looking for a bad request that does not exist. Save-time validation remains the primary guard — these paths are defence in depth, and when defence in depth fires it must speak the executor's vocabulary.

**The readonly backstop exists FOR the flip window (workspaces design §6 layer 2a, D10).** A pipeline is saved against a datasource that is writable *at save time*; the operator (or a restore, or manual SQL) flags the datasource `is_readonly` afterwards; the stored version keeps running. Blocking the flip on references would make readonly un-adoptable on any datasource in use — so the executor re-checks the **live** registry entry at node execution time, deliberately past the datasources §6.3 metadata cache (a row-level flip never crosses the save boundary that invalidates the cache, so the cached entry would otherwise outlive the flip by up to the 60s TTL). The check fires on the three and only three write shapes — a `DML`/`DDL` node's `source`, resolved in the node runner before a connection is leased, and any node's `output.target: "datasource"`, resolved in the write-back shell both row sources (cursor and already-decoded composition rows) funnel through — and raises `pipeline.node.datasource_readonly`, an HTTP-500 sibling of `datasource_not_found` with the same shape. DQL reads and everything `tempdb` are untouched. A PIPELINE node's children need no separate mechanism: each child node executes through the same node runner and write-back shell in its own execution, so it passes the same backstop. This is the runtime half of a contract whose save-time half ([Pipeline Contract §12.5](pipeline-contract.md#125-datasource-validations)) the stored version may legitimately predate — which is exactly why the backstop reads the registry per execution rather than trusting anything captured at build or validation time.

Cancellation (§8.3) is deliberately absent from this table: an aborted execution reports status `ABORTED` via `execution_aborted` and carries **no** error code.

### 8.3 Cancellation

Cancellation is a first-class path, not a side effect of failure. Three triggers produce it, all converging on the same mechanism:

> A fourth `ABORTED` source exists but is **not** a cancellation: the crash sweep ([Metadata DB §8](metadata-db.md#8-operational-jobs)) flips RUNNING executions whose instance died. Nothing runs anymore, so there is no statement to cancel and no `execution_aborted` event — only the persisted status changes (recorded as `pipeline.execution.instance_lost`).

| Trigger | Initiated by | `execution_aborted.reason` |
|---|---|---|
| `DELETE /api/v1/executions/{id}` ([REST API §10.4](rest-api.md#104-cancel-execution)) | The owner (or an `admin`) | `cancelled` |
| SSE consumer gone longer than `datapipelines.sse.disconnect-grace-seconds` ([REST API §6.8](rest-api.md#68-client-disconnect)) | The SSE layer's grace timer | `client_disconnect` |
| Server shutdown drain ([Deployment](deployment.md)) | Lifecycle hook | `shutdown` |

**A descendant stopped by an ancestor ends ABORTED, never FAILED.** Cancelling an
execution cancels the coroutine scope its children's executions run in, so an
in-flight PIPELINE node's child is stopped by machinery it did not decide for itself:
the child records `ABORTED`, carrying the family's abort reason when the ancestor was
cancelled (a `DELETE`'s `cancelled` survives onto every descendant's row), and
`cancelled` when the ancestor *timed out* — there is no catalogued reason for "an
ancestor timed out", and `FAILED` would misattribute the stop to the child's own
pipeline. The same holds for an ancestor's expired deadline arriving as a plain
cancellation: the descendant distinguishes "my deadline" (timeout → FAILED) from "an
ancestor's" (scope no longer active → ABORTED) by scope liveness, not by exception
shape.

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

    /** The reason this execution was cancelled, or null while it is still running. */
    val abortReason: AbortReason?

    /** Interrupts every registered Statement WITHOUT marking the execution aborted — §5.3. */
    fun cancelStatements()

    /** Registers [stmt] against [nodeId] for the duration of [body], then deregisters it. */
    suspend fun <T> withStatement(nodeId: String, stmt: Statement, body: suspend () -> T): T
}

/** The cross-instance half: the Redis flag a DELETE writes and the executing instance polls. */
interface CancellationFlags {
    fun request(executionId: UUID, reason: AbortReason, ttlSeconds: Long)
    fun read(executionId: UUID): AbortReason?     // null when unset OR when Redis could not be read
    fun clear(executionId: UUID)                  // the executing instance's cleanup, in `finally`
}
```

Three notes on this surface, all load-bearing:

- **`withStatement`'s `body` is `suspend () -> T`**, where v1.2 wrote `() -> T`. It has to be: the caller node's drain into the result store runs inside this block (§6.4.2) and is suspending Redis I/O. A non-suspending signature would force a `runBlocking` inside a coroutine.
- **`abortReason` is public on the handle** because the failure funnel reads it: a failure raised *outside* a registered statement — staging, the result-store drain, write-back — can land while the execution is already aborting, and `node_failed` must not be emitted for it (§10 allows one terminal event, and an `execution_aborted` is already on its way). The node is then recorded `ABORTED`-with-cause (§7.2) rather than `FAILED`.
- **`cancelStatements()` is step 1 of §8.3.2 without step 2** — statements interrupted, no abort reason set. It is what the timeout path uses (§5.3) and what the failure path uses to stop running siblings (§8.3.3). Reusing `cancel(...)` there would relabel every timeout as a cancellation.

`withStatement` also refuses to register a statement for an execution that has *already* been cancelled (re-checking after the put, so a `cancel()` that swept the map cannot leave an uninterruptible statement behind), and converts a cancel-induced driver error into `ExecutionAbortedException` — carrying the original as a **suppressed** exception, so §7.2 can still record what the node hit.

Every in-flight node holds exactly one registered `Statement` (§6.3). The registry is per-instance and in-memory, but **cancellation requests travel through Redis** so `DELETE /executions/{id}` works from ANY instance (the standard deployment has no sticky sessions):

1. The instance receiving the `DELETE` verifies the execution exists and is `RUNNING`, writes the key `dp:cancel:{execution_id}` = the `AbortReason` wire value to Redis (TTL = `datapipelines.executor.execution-timeout-seconds`), and returns `204`. It then also tries the local registry, so the common same-instance case cancels with no poll latency at all.
2. The **executing** instance reads that key on its per-execution poll tick (`datapipelines.sse.heartbeat-interval-seconds`, default 15s) and at every node boundary, and on a hit runs the full local cancel below (§8.3.2) — `Statement.cancel()` included, since the registry is local to it.
3. The executing instance clears the key in its `finally` (§5.1 step 15). A failed clear is logged and dropped: the key carries a TTL and expires on its own, and failing an otherwise-successful execution's cleanup over it would be strictly worse. Symmetrically, a Redis fault while *reading* the flag is not "cancel" — it logs and the next tick retries.

Worst-case cancellation latency is therefore ~one heartbeat interval; the common same-instance case (SSE disconnect grace, local `DELETE`) cancels immediately. Push-based fan-out (Redis pub/sub) is a ROADMAP refinement, not needed for correctness.

#### 8.3.2 Order of operations

`cancel(executionId, reason)` does, in this order:

1. **`Statement.cancel()` on every registered statement**, from the caller's thread — *before* touching coroutines. This is what actually interrupts a long-running query on the source database; the driver raises an `SQLException` on the thread blocked in `executeQuery`. Cancelling the coroutine first would only unblock the JVM side and leave the query running on the source server.
2. **Cancel the execution's root `Job`** with `ExecutionAbortedException(reason)`. Structured concurrency unwinds every node coroutine; `use` blocks release connections; pending nodes never start.
3. **Emit `execution_aborted`** (terminal event, [REST API §6.4.8](rest-api.md#648-execution_aborted)) with `reason`, `status: ABORTED`, and the node-stats snapshot (running/pending nodes report `ABORTED`).
4. **Run the `finally` block**: deregister from the registry, clear the Redis cancel flag, drop the staged tables and close the tempdb connection (§9), release the execution slot. Cleanup runs `NonCancellable` — it must complete even though we got here *by* cancellation.

Statements that ignore `cancel()` (some drivers, some statement kinds) are not waited on — they finish or hit their own `queryTimeout`. The connection is returned to the pool by `use` either way; a driver that cannot interrupt is a driver-quality issue, not an executor leak.

#### 8.3.3 Failure-driven cancellation

Node failure uses the same coroutine unwinding, without the registry entry point:

- `awaitAll(deps)` in downstream nodes rethrows the dependency's `NodeExecutionException`.
- `coroutineScope` cancels all child coroutines; running siblings have their registered `Statement.cancel()` invoked by the same handle.
- Pending nodes never start; they report `ABORTED` in stats while the execution reports `FAILED`.

The `finally` block runs unconditionally in all paths — failure, timeout, cancellation, success — guaranteeing tempdb cleanup, statement deregistration, and execution-slot release.

---

### 8.4 The failure record

**What the engineer sees when a node fails is the failure itself** (057/T85: three demo executions failed with `datasource_connection_failed` / `FATAL: password authentication failed` and the screen said only "Pipeline failed." — the owner opened the database to learn why). The `MappedError` therefore carries, besides code/message/details:

- `node` — `{id, type, datasource, dialect, template, template_version}`: the node half. The node runner decorates an escaping failure with it at the failure site, where the facts live (the datasource dialect once the registry resolved it; the tempdb engine's dialect for a `tempdb` source). Facts are attached **where they exist**: the datasource path stamps the dialect, the outer wrapper stamps the rendered SQL, and `PipelineExecutor.failNode` — the recording site — fills anything still missing.
- `sql` — the rendered SQL in `:name` form, when the failure is at or after RENDER. Never the positional form, never a bound value: the values live only in the executor's bind array (042's contract), and a test pins that a bound value appears nowhere in the failure events or the error record. Bounded at 16 384 characters with an explicit truncation marker — the render budget permits megabytes, and an unbounded echo would be the same amplifier the 2000-char message bound removes.
- `exception` — the ORIGINAL failure (`cause.cause ?: cause` unwraps the `NodeFailedSignal`): `{class, message, frames, caused_by}`. `caused_by` is a flat list, outermost-first, **root cause last** (the orientation `getCause` walks; display reverses it). Frames are capped at 40 per level, the chain walked to 16 levels (`ConnectionLease.CHAIN_WALK_LIMIT` is the house precedent for a bounded walk); both bounds exist so a pathological chain cannot turn the record into an amplifier across the SSE frame, the `execution_events` row and `error_json` — all of which carry it, because it is the same driver text the server log already prints.

The record is completed **once**, at `failNode`, and carried unchanged into the `node_failed` event, the terminal `pipeline_failed` event, `error_json` (which `web` serializes from the same projected error object) and the thrown `PipelineExecutionFailed` (which the MCP surface reads). Timeout and setup failures carry the same exception detail, minus `node`/`sql` — neither exists for them.

`datapipelines.executions.error-detail` (Configuration §3.11) gates the two raw halves: `full` (default — a self-hosted product whose users are engineers; the stack IS the diagnostic) carries `exception` and `sql`; `structured` omits both and keeps the catalogued code, message, details and node context. Every consumer downstream of the capture sees the same level — there is no per-surface stripping.

## 9. Tempdb Lifecycle Integration

The executor creates a tempdb instance per execution via `StagingFactory`, choosing the engine from `pipeline.settings.tempdb.engine`:

```kotlin
interface StagingFactory {
    fun create(executionId: UUID, engine: StagingEngine = StagingEngine.H2): Staging
}
```

This `StagingFactory.create(executionId, engine)` signature is **canonical here** — the [Staging spec](staging.md) aligns to it. `engine` defaults to `StagingEngine.H2`; the executor passes `pipeline.settings.tempdb.engine` explicitly. Pipeline-level `settings.tempdb.config` overrides the global `datapipelines.staging.*` keys ([Configuration §3.3](configuration.md#33-staging-tempdb)), and a pipeline `max_memory_mb` is **clamped to ≤ the global** — an override may lower the operator's ceiling, never raise it.

**The `Staging` interface itself is owned by [Staging §10](staging.md#10-the-staging-interface)** — this spec no longer restates it, because a second copy is a second thing to drift. What the executor depends on, and what changed the shape of §5.2/§6.4:

| Contract point | Consequence for the executor |
|---|---|
| `withConnection(block)` is the **only** route to the raw `Connection` | There is no `staging.connection` property to read. Every tempdb statement the executor issues is created inside this block. |
| `stage(rs, tableName, sourceDialect)` | The source node's dialect is passed explicitly (§6.4.1). |
| `withQuery(sql, block)` holds the lock across the **whole** cursor drain | The §6.4.2 lock-across-drain guarantee; `withConnection` gives the same coverage, which is why the executor can use it instead (below). |
| The serialization mutex is **not reentrant** | `stage()` from inside a cursor over the same connection deadlocks — which is why a `tempdb`→`tempdb` DQL node is a single CTAS (§6.4.1). |
| `stats()` is `suspend`; `close()` is not, and must not throw | `close()` is safe to call from `finally` without masking the real outcome. |

**A tempdb read runs through `withConnection` + a registered statement, not `withQuery`.** `withQuery` creates the statement *inside* staging, so the executor never sees it — and a statement the executor cannot see is one it cannot register with `CancellationHandle.withStatement`. The consequence was concrete: a tempdb-sourced caller or write-back node was **uncancellable**, with `DELETE`, the disconnect-grace timer and the execution timeout all reaching the coroutine and none of them reaching the query. The executor therefore opens its own `TYPE_FORWARD_ONLY`/`CONCUR_READ_ONLY` cursor inside `withConnection`. Two differences, both accounted for: it does **not** set `fetchSize`/`closeOnCompletion` (immaterial on in-memory H2 — there is no server round-trip to batch and the statement is closed by its own `use` block, so nothing leaks), and it **does** register the statement for `Statement.cancel()` and apply `node-query-timeout-seconds`, which tempdb reads through `withQuery` never honoured.

**Lifetime.** The executor opens the staging instance at execution start and holds it for the execution's duration. The JDBC URL carries **no** `DB_CLOSE_DELAY` — default H2 semantics (the in-memory database dies when its last connection closes) are exactly what we want for a per-execution scratch database. `close()` in the executor's `finally` block is belt-and-braces: it drops every staged table (enumerated from the catalog and dropped schema-qualified, so a table parked outside `PUBLIC` is still reclaimed) and then closes the connection, so memory is reclaimed at a known point rather than whenever GC happens to run. A drop that fails is logged as `pipeline.staging.cleanup_failed` and never rethrown. See the [Staging spec](staging.md) for engine configuration details.

**Concurrency.** v1 uses a single tempdb connection per execution, and **concurrent access to it does happen**: up to `datapipelines.executor.max-parallel-nodes` node coroutines may reach for tempdb at the same time (two siblings staging their ResultSets, or one staging while another reads a previously staged table). A JDBC `Connection` does not safely serialize concurrent callers on its own, so the staging implementation guards it with an explicit `Mutex` — every `stage` / `withQuery` / `withConnection` / `execute` call takes the lock and holds it for the whole block, and tempdb work therefore serializes even though the nodes run in parallel. The mutex is **not reentrant**, which is a contract the executor must respect rather than a detail (§6.4.1).

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
    abstract val type: SseEventType       // the SSE `event:` name this payload publishes under
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

Each event also carries the `SseEventType` it publishes under; that enum is declared in the `dag` module for the same layering reason `staging.StagingEngine` is (the executor is what emits the events and what writes `execution_events.event_type`, and `web` sits *above* `dag`), while [Enums §11](enums.md#11-sseeventtype--pipeline-execution-event-types) and [REST API §6.4](rest-api.md#64-event-types) remain the wire authorities.

Wire names, payloads, and ordering guarantees are owned by [REST API §6.4](rest-api.md#64-event-types) and [Enums §11](enums.md#11-sseeventtype--pipeline-execution-event-types); this spec only says which event the executor emits where.

**Every event payload carries `correlation_id`** on the wire — that is normative in [REST API §6.4](rest-api.md#64-event-types) and [Observability §3.3](observability.md#33-correlation-id-propagation). It is **not** a field on every executor event type: `ExecuteRequest` carries the correlation id, `ExecutionStarted` carries it through, and `web` threads the request's correlation id onto every other event when it projects to the wire. The projecting layer, not the executor, is where the guarantee is met.

**The `error` object of `node_failed` and `pipeline_failed` is the §8.4 failure record** (057), carried unchanged; the projecting layer also stamps `correlation_id` INSIDE it and serializes the same object into `error_json` — one record, every surface, no rebuilds.

**Wire projection — what `web` derives rather than reads.** The executor's event objects are not the wire payloads; three fields are computed at projection time:

| Wire field | Where it comes from |
|---|---|
| `node_failed.failed_at` | `NodeStats.completedAt` on the event's stats — the executor does not carry a separate failure timestamp. |
| `status` on `pipeline_completed` / `pipeline_failed` / `execution_aborted` | Derived from the event type (`SUCCESS` / `FAILED` / `ABORTED`). It is not a field on the event: the type already determines it, and two sources for one value is one too many. |
| `correlation_id` (all events) | Threaded from the request, as above. |

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
- `co.datapipelines.executor.NodeRunner` (render → connect → dispatch, §6; grouped collaborator — §5.2)
- `co.datapipelines.executor.CancellationRegistry`, `CancellationHandle`, `CancellationFlags` (§8.3.1)
- `co.datapipelines.executor.ResultStore` (Redis-backed caller-result materialization)
- `co.datapipelines.executor.ExecutorMetrics` (§15.3), `ExecutionSlots` (§5.3), `ExecutorConfig`
- `co.datapipelines.executor.ExecutionStatus`, `ExecutionTrigger` — declared here for the layering
  reason [Enums §11](enums.md#11-sseeventtype--pipeline-execution-event-types) records; enums.md,
  rest-api and metadata-db remain the wire authorities
- `co.datapipelines.events.EventEmitter` (interface)
- `co.datapipelines.events.ExecutionEvent` (sealed class), `SseEventType` (§10)

### 15.2 Coroutine context

- All executor code runs on a dedicated `ExecutorDispatcher` — a bounded IO dispatcher owned by the `dag` module, sized from `datapipelines.executor.max-concurrent-executions-per-instance` × `max-parallel-nodes`. Executor code **never** references `Dispatchers.IO` directly: sharing the JVM-wide IO pool with Spring's own blocking work makes executor throughput a function of unrelated load, and makes starvation impossible to attribute.
- Each execution has its own `coroutineScope` under `withTimeout(...)`, so failure, timeout, or cancellation of one execution never affects another.
- `Job` cancellation is honored throughout — the three cancellation triggers in §8.3 all resolve to cancelling that scope's root `Job`, after the registered statements have been interrupted. The disconnect trigger's grace period is `datapipelines.sse.disconnect-grace-seconds`, and the timer is owned by the SSE layer, not the executor.

### 15.3 Monitoring

The executor exports Micrometer metrics. **[Observability §4.1](observability.md#41-metric-naming) is the authority for names and tag sets** — a tag added here that is not catalogued there is a spec change, not an implementation detail. The list below is what the executor actually publishes:

| Metric | Type | Tags |
|---|---|---|
| `datapipelines.executions.total` | counter | `status` (success/failed/aborted), `pipeline_id` |
| `datapipelines.executions.duration` | timer | `pipeline_id` |
| `datapipelines.executions.concurrent` | gauge | (none) — bound to the live execution-slot count |
| `datapipelines.executions.aborted` | counter | `reason` (`client_disconnect`/`cancelled`/`shutdown`) |
| `datapipelines.nodes.duration` | timer | `pipeline_id`, `node_id`, `source` |
| `datapipelines.nodes.rows_out` | counter | `pipeline_id`, `node_id` |
| `datapipelines.staging.rows` | counter | (none) |
| `datapipelines.result.bytes_written` | counter | (none) |
| `datapipelines.result.writes` | counter | `outcome` (`stored`/`too_large`/`storage_unavailable`) |

Four points where the shipped instruments are sharper than v1.2's list:

- **`nodes.duration` carries a `source` tag; `nodes.rows_out` does not.** That asymmetry is observability §4.1's choice, not an oversight — reusing one tag set for both would add an uncatalogued dimension to the counter, which is the same drift class as an uncatalogued value. `source` is bounded by construction: the literal `tempdb` or a registered datasource name.
- **`nodes.rows_out` publishes only real counts.** `-1` is §7.1's "not measured" sentinel and is never added to the counter — doing so would walk it backwards.
- **`staging.rows` is emitted on both staging paths** — the cursor `stage()` path *and* the `tempdb`→`tempdb` CTAS path (§6.4.1). A CTAS stages just as surely; counting only `stage()` would leave the metric blind to the commonest multi-node shape, and a half-populated counter reads as a real number, which is worse than zero.
- **`datapipelines.result.writes`'s success `outcome` value is `stored`, not `success`** — observability §4.1 is the single authority for tag values, and a dashboard written against the doc would otherwise have matched nothing. The two failure outcomes correspond 1:1 to the `result.too_large` and `result.storage_unavailable` error codes.

See the [Observability spec](observability.md) for the full metric catalog, naming rules, and the cardinality discipline the `pipeline_id` / `node_id` tags are allowed under.

---

## 16. Consistency Model

**Ratified 2026-09-02 (ruling R6 §E); implemented in round 056.**

Everything below is true of the code today. It is written down because the most important
sentence in it is one a customer must have read BEFORE filing the bug, and until 056 it existed
nowhere:

> **Each node is atomic on its own database; the pipeline as a whole is not, and a node that fails
> does not undo the committed work of the nodes before it.**

### 16.1 One transaction, one database

There is exactly **one** Spring transaction manager in this application — `metadataTransactionManager`,
over the metadata database — and there are N Hikari pools for customer databases which are **not**
Spring transaction resources and must never become one.

No XA, no JTA, no two-phase commit. The customer databases are not ours; SQLite and DuckDB have no
meaningful XA; and an in-doubt distributed transaction left on a customer's database is a worse
failure than any it would prevent. Every seam between two databases is therefore handled by design,
and every mechanism in the table below already exists in the code.

| Seam | Mechanism | Where |
|---|---|---|
| metadata ↔ metadata, multi-statement | `@Transactional("metadataTransactionManager")` on the service method | the service layer (`PipelineService` and its siblings) |
| customer DB, within one node | per-connection `autoCommit=false` + commit/rollback, savepoint for `REPLACE` | `WritebackRunner` (§6.4.3) |
| customer DB ↔ customer DB, across nodes | **none — each node is atomic, the pipeline is not** | §16.2 below |
| metadata ↔ customer DB (status vs. work) | the terminal status write runs in `NonCancellable`; the stale sweeper reconciles what a dead instance left | §5.1, §8.3 |
| metadata ↔ Redis (idempotency) | claim-before-work via `SET NX` | §11 |
| sender ↔ receiver (promotion) | the receiver applies a batch in ONE metadata transaction; the sender stores nothing and re-derives from inventory; `body_hash` makes a retry a no-op | `PromotionReceiveService` |

Two rules make this mechanical rather than remembered:

1. **The manager is always named.** A bare `@Transactional` binds to whichever manager Spring
   finds — correct by accident with one manager, a trap with two. `ArchitectureGuardTest` fails
   the build on a bare one.
2. **No customer-datasource I/O inside a metadata transaction.** `ConnectionLease` refuses to
   lease while `TransactionSynchronizationManager.isActualTransactionActive()` is true, with the
   catalogued `datasource.lease_in_transaction` (pipeline-contract §13.8). Holding a metadata
   transaction — and its row locks — open across arbitrary customer SQL is the failure being
   prevented; and its rollback could not undo the customer-side effect anyway. Orchestration runs
   OUTSIDE the transaction; status writes are short, separate transactions.

### 16.2 What a partial failure leaves behind

A pipeline is a DAG of nodes, each running against whatever database its `source` names. A node
that writes back (`output.target: "datasource"`, §6.4.3) commits on ITS OWN connection when it
finishes. If a later node then fails, that commit stands: there is no transaction spanning the two,
by the design above, and there cannot be one when the two nodes write to different databases.

So a failed execution can leave a target table populated by the nodes that ran before the failure.
That is not a defect and there is no rollback coming. Design for it the way any at-least-once
pipeline is designed:

- prefer **idempotent** write-backs — `REPLACE` (which truncates under a savepoint, §6.4.3) over
  `APPEND` where a re-run must not double rows;
- stage into a tempdb table and write back in ONE final node, so the multi-write window closes;
- read `node_stats` on a failed execution to see exactly which nodes committed
  (`GET /executions/{id}`, rest-api §10.2).

The parts that ARE atomic: each node's own write-back against its own database (§6.4.3); each
metadata service method that carries `@Transactional` (§16.1); the tempdb, which is per-execution
and discarded whole (§9).

### 16.3 Why not "just" wrap the pipeline

Every alternative was considered and rejected in the same ruling:

- **XA / two-phase commit** — needs the customer's database to participate, needs a recovery log we
  would have to operate, and leaves in-doubt transactions holding locks on a database that is not
  ours when our process dies. Two of the five supported dialects cannot do it at all.
- **A compensating-transaction (saga) layer** — the compensation for "this INSERT already
  committed" is a DELETE we cannot write safely without knowing the customer's schema semantics,
  and a wrong compensation destroys data the pipeline did not create.
- **Buffering every write to the end** — turns a streaming executor into one bounded by the size of
  the largest write-back, which is the constraint the tempdb design exists to avoid.

The honest position is the one stated: node-level atomicity, an explicit consistency model, and a
document a customer can read before they need it.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-02 | v1.4 | 051 auth/config sweep | §8.3 gains the descendant sentence (T20): an in-flight child stopped by an ancestor’s cancellation or expired deadline ends ABORTED — carrying the family’s abort reason (a DELETE’s `cancelled` survives onto every descendant’s row; an ancestor timeout also records `cancelled`, since no catalogued reason exists for it) — never FAILED, which would misattribute the stop to the child’s own pipeline. Scope liveness, not exception shape, tells “my deadline” from “an ancestor’s” |
| 2026-08-05 | v1.0 | initial draft | Initial DAG executor spec: ~150-line `Dag<T>`, parallel execution via coroutines, fail-fast, SSE integration, idempotency |
| 2026-08-07 | v1.2 | consistency campaign | Applied [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.6 — **D1**: terminal-node auto-detection replaced by caller-node resolution (§4.1, §5.1, §5.2); omitted `output` resolves to `NodeOutput.Caller` at deserialization; zero-caller executions emit no `data_ready`; executor asserts nothing about DAG position. **D5**: `pipeline.staging.h2_creation_failed` → `pipeline.staging.creation_failed`; `idempotency.key_reused_for_different_request`; §8.2 table completed and re-pointed at pipeline-contract §13. **D6**: `StagingFactory.create(executionId, engine)` declared canonical; no `DB_CLOSE_DELAY`; explicit `DROP ALL OBJECTS` + close in `finally`; single connection Mutex-guarded and §12.1's "no concurrent tempdb access" claim corrected. **D7**: new §8.3 Cancellation — per-node `Statement` registry, `Statement.cancel()` before coroutine cancel, three triggers (DELETE / disconnect grace / shutdown), `execution_aborted` terminal event. **D8**: §5.3 limits reference configuration.md keys instead of restating defaults. **D9**: §6.4.2 caller path materializes the ResultSet into the Redis result store inside `connection.use`, enforcing `result.max-size-bytes` (`result.too_large`) and failing with `result.storage_unavailable`; `data_ready` built from the stored result. **[M]**: semaphore permit now acquired after `awaitAll(deps)` (chain-deadlock fix); execution-slot acquisition added; `withTimeout(execution-timeout-seconds)`; `node_completed` success-only and single `NodeFailed` emission; `NodeResult` defined with `callerResultRef` and its projection to `NodeStats` (§7); `PipelineExecutionFailed` constructor aligned with §8.1; `NodeExecutionException` passes `cause` to `Throwable` instead of shadowing it; `Dispatchers.IO` → `ExecutorDispatcher`; `Dag` dead no-op loop removed and `dependencies[id]!!` → `emptySet()` default; `independentBatches()` marked diagnostic/UI-only (§3.3); §4 retitled "Executor-Facing Model" and §8.3 "Cancellation" to fix inbound anchors; "(future)" removed from the observability link. |
| 2026-08-10 | v1.3 | P4 Gate C doc-sync | Aligned the frozen spec with the merged `dag` module (P4 Gate C fix cycle). Additive/corrective only — no anchor renamed, no code changed. **§3.1**: shipped `Dag<T>` precomputes the reverse-edge index and detects cycles with an iterative three-colour DFS; `dependenciesOf` defaults to `emptySet()`; `DagBuilder.build()` runs the cycle check itself. **§5.1**: admission re-ordered — parameter binding stays pre-stream (400-class, no events), `execution_started` is emitted **before** staging creation and registry registration, so `pipeline.staging.creation_failed`/`engine_unavailable` surface as `execution_started` + `pipeline_failed` instead of zero events; step 13 added for catalogued setup failures; step 14 resolves the stored result before the terminal event. **§5.2**: collaborators grouped behind `NodeRunner` with a `pipelineExecutor(...)` factory preserving the spec's construction shape; `cancellationFlags`, `metrics` and `resultUrls` (required, no default) added. **§5.3**: the execution timeout now cancels registered statements, with the residual driver-`queryTimeout` overshoot stated. **§6.1**: `render(ref, context, maxOutputChars)` — the third argument is the per-execution output budget. **§6.4.1**: new `tempdb`→`tempdb` subsection (single `CREATE TABLE … AS` under `withConnection`, non-reentrant mutex, `SELECT COUNT(*)` row count, explicit budget re-check, five §8.2 rows unreachable on that shape). **§6.4.2**: `materialize(executionId, resultSet, sourceDialect, ttlSeconds)`; source-dialect column mapping; the stored result is read back **before** `pipeline_completed` and a vanished result fails with `result.storage_unavailable`. **§6.4.3**: `writeback(rs, output, sourceDialect)`; `REPLACE` truncate under a savepoint; target-missing by SQLState `42S02`/`42S03`/`42P01`; batch counts summed. **§7.1**: `completedAt` added; `rowsOut` for a CTAS node is a post-create `SELECT COUNT(*)`. **§7.2**: an `ABORTED` row may carry `error_code`/`error_message` when the executor stopped a mid-flight node, distinguishable by non-null `started_at`. **§8.1**: `PipelineException` extends the shared `DatapipelinesException` (module-structure §4.3); `PipelineConcurrencyLimitException(scope, limit)`. **§8.2**: `pipeline.staging.table_already_exists` row added; a collaborator's own catalog code always wins (the phase table covers raw driver/unknown exceptions only); runtime identifier refusals report phase codes, never `pipeline.validation.invalid_identifier`. **§8.3.1**: `withStatement` body is `suspend`; `abortReason` and `cancelStatements()` added to `CancellationHandle`; `CancellationFlags` and the `dp:cancel:{execution_id}` key documented. **§9**: the `Staging` interface block replaced by a pointer to [Staging §10](staging.md#10-the-staging-interface) (only `StagingFactory.create` stays canonical here), plus why tempdb reads run through `withConnection` + a registered statement rather than `withQuery`. **§10**: `correlation_id` is a wire guarantee met by the projecting layer; wire-projection note for `web`. **§15.3**: metric table aligned to [Observability §4.1](observability.md#41-metric-naming) as the tag authority (`nodes.rows_out` has no `source` tag; `staging.rows` covers both staging paths; `result.bytes_written`/`result.writes{outcome}` added). Throughout: `DROP ALL OBJECTS` corrected to the catalog-driven staged-table drop the shipped `close()` performs. |
| 2026-08-05 | v1.1 | propagation | Aligned with v1.1 Pipeline Contract. `NodeType.SQL` → `NodeType.{DQL, DML, DDL}`. `NodeSource.Staging` → `NodeSource.Tempdb`. Replaced `outputTable: String?` with sealed `NodeOutput` (Tempdb/Caller/Datasource). `executeNode` now dispatches on `type` then `output.target`. Added DML/DDL execution paths. Added write-back execution path (WritebackRunner) for `output.target: "datasource"`. Terminal auto-detected via `detectTerminal(dag)` instead of read from `pipeline.terminalNodeId`. Renamed §9 from "H2 Lifecycle" to "Tempdb Lifecycle" (engine-agnostic). |
| 2026-08-17 | v1.4 | pipeline composition substrate | New §6.6, executor-only (no wire or catalog change): `direct` result delivery — a child execution carries a `DirectResultSink` on its `ExecuteRequest` and its caller ResultSet streams to the parent executor, bypassing §6.4.2's store; `ResultStore.materializeRows` stores an already-decoded schema + rows under the §6.4.2 contract so a parent PIPELINE node can re-publish a child's result to its own caller; only ROOT executions take a concurrency slot (a child request carries `rootExecutionId` and skips §5.1 step 2); family cancellation — a child reads its root's `dp:cancel:` flag as well as its own. |
| 2026-08-17 | v1.5 | pipeline composition wiring | The §6.6 runtime lands: `NodeExecutionContext` carries the principal, the family root and the depth counter; the `pipelineExecutor(...)` factory takes the `SubPipelineRunner` port (implemented by `web`'s `SubPipelineExecutionRunner` — child invocation, per-target `directSink` adapters, runtime depth backstop, child-failure mapping); `Staging.stageRows` / `WritebackRunner.writebackRows` are the already-decoded write paths the adapters reuse; `NodeResult`/`NodeStats` gain `child_execution_id` (§7.1/§7.2 sketches updated). Doc fixes: §1's dispatch enumeration and §4's `NodeType` sketch gained `PIPELINE`. |
| 2026-08-27 | v1.6 | workspaces readonly slice | §8.2: new row `pipeline.node.datasource_readonly` (500) and the flip-window paragraph — the executor's per-node readonly backstop (workspaces design 2026-08-16 §6 layer 2a, D10) re-reads the LIVE registry entry past the datasources §6.3 cache, covers all three write shapes (DML/DDL source in the node runner pre-lease; `output.target: "datasource"` in the write-back shell both row sources share) and composed children (same runner, own execution). No classification change to any existing row. |
