package co.datapipelines.executor

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.staging.Staging
import co.datapipelines.staging.StagingMemoryLimitException
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.TypeMappingWarning
import kotlinx.coroutines.CancellationException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Per-execution collector for the non-fatal type-mapping warnings that `StageResult.warnings` and
 * the caller-result schema mapping produce (staging §8.2, type-system §8.2).
 *
 * Per **execution**, not per executor: node coroutines run in parallel and all write here, and a
 * collector shared across executions would leak one run's warnings into another's response.
 */
class WarningSink {
    private val warnings = ConcurrentLinkedQueue<TypeMappingWarning>()

    fun addAll(more: Collection<TypeMappingWarning>) {
        warnings.addAll(more)
    }

    /** Everything collected so far, in collection order. */
    fun collected(): List<TypeMappingWarning> = warnings.toList()
}

/** Everything one node execution needs that is fixed for the whole execution. */
data class NodeExecutionContext(
    val executionId: UUID,
    val staging: Staging,
    val handle: CancellationHandle,
    val values: Map<String, Any?>,
    val warnings: WarningSink,
    /** Already-clamped effective result TTL for this execution (REST §7.4). */
    val resultTtlSeconds: Long,
    /** The per-execution render output budget (§6.1 / Staging §8) — never the engine default. */
    val renderBudgetChars: Long,
    /** The effective `max_memory_mb` for this execution's tempdb (D6). */
    val stagingMaxMemoryMb: Long,
    /** The dialect of `source: "tempdb"` nodes, from `settings.tempdb.engine` (§12.6, D6). */
    val tempdbDialect: Dialect,
    /**
     * The principal this execution runs as ([ExecuteRequest.userId]). A PIPELINE node's child
     * inherits it (design D9): composition carries no new scopes, and authorization was checked
     * on the parent's execute call.
     */
    val userId: UUID,
    /**
     * The execution family's top ancestor (metadata-db §4.6): [ExecuteRequest.rootExecutionId],
     * or this execution's own id when it IS the root. A PIPELINE node's child request carries it
     * verbatim, which is what makes family-wide cancellation (design §4.3, D8) reach every
     * descendant.
     */
    val rootExecutionId: UUID,
    /**
     * How many PIPELINE-node hops sit above this execution ([ExecuteRequest.compositionDepth]) —
     * 0 for a root. The sub-pipeline runner refuses a child whose depth would exceed
     * `datapipelines.pipelines.max-composition-depth` (design §4.4's runtime backstop).
     */
    val compositionDepth: Int = 0,
    /**
     * The `direct` delivery target for this execution's caller result (design §4.2) — set on a
     * child execution spawned by a PIPELINE node, null on roots. When present, the caller node's
     * result streams here and the [ResultStore] is never touched.
     */
    val directSink: DirectResultSink? = null,
    /**
     * The id of the REQUEST that started this execution ([ExecuteRequest.correlationId]) — the one
     * field designed to join everything one request caused (rest-api §3.4, observability §3.3).
     *
     * A PIPELINE node's child request carries it verbatim, so a whole composition family — every
     * `pipeline_executions` row, every SSE payload, every log line — is joinable by a single id.
     * Null only when the surface that built the request supplied none; `web` always does.
     */
    val correlationId: UUID? = null,
    /**
     * The workspace this execution runs IN ([ExecuteRequest.workspaceId], design §5.3) —
     * the pipeline's workspace. Runtime datasource resolution is scoped by it: a node's
     * `source`/`output.target` datasource resolves through [DatasourceRegistry.getVisible],
     * so a datasource re-bound away from this workspace after the pipeline was saved fails
     * at the NEXT execution as `datasource_not_found` — the same answer save-time
     * validation would give, instead of silently executing against a row the workspace can
     * no longer see (025 A5).
     */
    val workspaceId: UUID,
)

/**
 * Runs one node: render → connect → dispatch on `type` → dispatch on `output`
 * (dag-executor.md §6).
 *
 * Failures are raised as [NodeFailedSignal] carrying an already-mapped catalog code (§8.2); the
 * caller ([PipelineExecutor]) owns event emission, so this class never emits and can be tested
 * without an emitter.
 *
 * ## Where this departs from §5.2's sketch, and why
 *
 * §5.2 reads `staging.connection` and calls `staging.stage(rs, table)` from inside a cursor over
 * the same connection. Neither is reachable against the shipped `Staging` contract: the
 * connection is private behind `withConnection`, and the staging mutex is **not reentrant** —
 * calling `stage` from inside `withQuery` deadlocks by construction (staging.md §3.3, §9.2 and
 * the `Staging` KDoc say so explicitly). So a `tempdb` → `tempdb` DQL node runs as a single
 * `CREATE TABLE … AS <sql>` on the staging connection instead of cursor-plus-stage: one
 * statement, one lock acquisition, and the copy never leaves H2.
 *
 * Every tempdb statement this class issues — the CTAS, DML/DDL, and the read cursors of
 * [tempdbCursor] — runs through `withConnection` on a statement the executor owns, so all of them
 * carry `node-query-timeout-seconds` and are registered for `Statement.cancel()` (§6.3, §8.3.1).
 * `withQuery` is deliberately unused: a statement created inside `staging` cannot be registered,
 * and an unregistered statement is an uncancellable one.
 */
@Suppress("LongParameterList")
class NodeRunner(
    private val templateEngine: TemplateEngine,
    private val datasourceRegistry: DatasourceRegistry,
    private val writebackRunner: WritebackRunner,
    private val resultStore: ResultStore,
    private val config: ExecutorConfig,
    private val auditSink: ExecutionAwareAuditSink? = null,
    /** `datapipelines.staging.rows` had zero call sites before F10 — it was permanently 0. */
    private val metrics: ExecutorMetrics = ExecutorMetrics.inMemory(),
    /**
     * The composition port (design §4.1) a PIPELINE node dispatches to. Null means this runtime
     * has no composition wired — a PIPELINE node then fails with
     * `pipeline.node.child_execution_failed` rather than reaching render or source resolution,
     * which it has no fields for.
     */
    private val subPipelineRunner: SubPipelineRunner? = null,
) {
    /** Executes [node] and returns its result. Throws [NodeFailedSignal] on any failure. */
    suspend fun run(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant = Instant.now(),
    ): NodeResult {
        // BEFORE render/source dispatch (design §4.1): a PIPELINE node carries neither a
        // template nor a source, so it never enters the SQL paths below.
        if (node.type == NodeType.PIPELINE) {
            return subPipelineRunner?.run(node, ctx)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Node.CHILD_EXECUTION_FAILED,
                    message = "Pipeline composition is not wired in this runtime.",
                    details = mapOf("node" to node.id),
                )
        }
        val sql = phase(NodePhase.RENDER, node.id) { render(node, ctx) }
        // 057: everything from the translator on is a failure AT OR AFTER RENDER — the
        // rendered SQL exists and belongs on the failure record. The wrapper only fills
        // facts still missing: a signal the datasource path already decorated (dialect in
        // hand) passes through untouched, same as [asNodeFailure] re-labels nothing.
        return try {
            // 042 C1/C2: translate the rendered SQL once, before any connection is leased — a
            // `:name` the context does not declare fails loudly HERE (`sql_parameter_missing`),
            // never on a statement that half-executed with a silent null.
            val bound = phase(NodePhase.RENDER, node.id) { SqlBindTranslator.translate(sql, ctx.values) }
            when (node.source) {
                is NodeSource.Tempdb -> runOnTempdb(node, bound, ctx, startedAt)
                is NodeSource.Datasource -> runOnDatasource(node, node.source.name, bound, ctx, startedAt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw decorateFailure(e, node, ctx, sql)
        }
    }

    /**
     * The outer 057 decorator: stamps the failure record's node context (dialect: the tempdb
     * engine's for a tempdb source, else whatever the datasource path attached) and the
     * rendered SQL onto an escaping signal — [MappedError.withNodeFacts] fills only what is
     * still null, so an inner decoration always wins.
     */
    private fun decorateFailure(
        error: Exception,
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        sql: String,
    ): Exception =
        when (error) {
            is NodeFailedSignal ->
                NodeFailedSignal(
                    error.error.withNodeFacts(node, tempdbDialectOf(node, ctx), sql, config.errorDetail),
                    error.cause ?: error,
                )
            else -> error
        }

    private fun tempdbDialectOf(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ): String? = if (node.source is NodeSource.Tempdb) ctx.tempdbDialect.name else null

    /**
     * The per-execution output budget is passed explicitly (§6.1): letting the engine-wide
     * default apply would render against a backstop that knows nothing about this execution's
     * staging memory budget.
     */
    private fun render(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ): String = templateEngine.render(node.template, ctx.values, ctx.renderBudgetChars)

    // ---------------------------------------------------------------- tempdb

    private suspend fun runOnTempdb(
        node: ExecutableNode,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
    ): NodeResult {
        // tempdb is not a datasource, so there is no per-datasource override to consider (§5.5).
        val timeout = config.queryTimeoutSecondsFor(null)
        return when (node.type) {
            NodeType.DQL -> tempdbQuery(node, bound, ctx, startedAt, timeout)
            NodeType.DML, NodeType.DDL -> tempdbWrite(node, bound, ctx, startedAt, timeout)
            NodeType.PIPELINE -> error("unreachable: PIPELINE dispatched before source resolution")
        }
    }

    private suspend fun tempdbQuery(
        node: ExecutableNode,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        timeout: Int,
    ): NodeResult =
        when (val output = requireOutput(node)) {
            is NodeOutput.Tempdb -> {
                tempdbCreateTableAs(node, output, bound, ctx, startedAt, timeout)
            }

            is NodeOutput.Caller -> {
                phase(NodePhase.MATERIALIZE, node.id) {
                    tempdbCursor(node, bound, ctx, timeout) { rs -> deliverToCaller(node, rs, ctx, startedAt, ctx.tempdbDialect) }
                }
            }

            is NodeOutput.Datasource -> {
                phase(NodePhase.WRITEBACK, node.id) {
                    tempdbCursor(node, bound, ctx, timeout) { rs ->
                        NodeResult.of(node.id, writebackRunner.writeback(rs, output, ctx.tempdbDialect, ctx.workspaceId), startedAt)
                    }
                }
            }
        }

    /**
     * Opens a read cursor over tempdb for [block], on a statement **this class owns**.
     *
     * ## Why not `staging.withQuery` (B4b)
     *
     * `withQuery` creates the statement inside `staging`, so the executor never sees it — and a
     * statement the executor cannot see is a statement it cannot register with
     * [CancellationHandle.withStatement]. The consequence was concrete: a tempdb-sourced caller or
     * write-back node was **uncancellable**. `DELETE /executions/{id}`, the disconnect-grace timer
     * and the execution timeout all reached the coroutine and none of them reached the query, so a
     * long tempdb read ran to completion with nobody waiting for it — exactly the hole §8.3 exists
     * to close, and inconsistent with the sibling `tempdbCreateTableAs`/`tempdbWrite` paths that
     * already did this correctly.
     *
     * ## The §6.4.2 lock-across-drain guarantee is preserved, not traded away
     *
     * `withConnection` holds the *same* serialization mutex for the whole block, so the cursor and
     * the caller's suspending drain to the result store are still covered end to end: a concurrent
     * `stage`/`execute` on the shared connection cannot interleave with an open cursor. The two
     * methods differ only in who creates the statement, which is the one thing that matters here.
     *
     * The statement is `TYPE_FORWARD_ONLY`/`CONCUR_READ_ONLY` (§6.3.1) and carries the node query
     * timeout, so tempdb reads now honour `node-query-timeout-seconds` — which, through
     * `withQuery`, they never did.
     */
    private suspend fun <T> tempdbCursor(
        node: ExecutableNode,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        timeout: Int,
        block: suspend (ResultSet) -> T,
    ): T =
        ctx.staging.withConnection { connection ->
            statementFor(connection, bound).use { statement ->
                statement.queryTimeout = timeout
                ctx.handle.withStatement(node.id, statement) {
                    query(statement, bound).use { rs -> block(rs) }
                }
            }
        }

    /**
     * `CREATE TABLE <table> AS <sql>` on the staging connection.
     *
     * Run through `withConnection` rather than `Staging.execute` so the statement gets a
     * `queryTimeout` and is registered for `Statement.cancel()`: staging sets **no** timeout on
     * `execute`/`withConnection`, so author tempdb SQL could otherwise hold the staging mutex for
     * as long as it liked with the execution timeout as the only backstop. The memory budget
     * `execute` would have checked is checked explicitly afterwards ([checkStagingBudget]).
     *
     * ## Why the row count is a second statement
     *
     * `executeUpdate` on `CREATE TABLE … AS …` returns **0** on H2 (verified against the pinned
     * 2.3.232 driver — `NodeRunnerTest` caught it): the JDBC contract only promises a count for
     * DML, and a CTAS is DDL. Reporting that 0 as `rows_out` would put a silent lie in every
     * `node_completed` payload and in `node_stats_json` for the most common node shape there is.
     * So the freshly created table is counted, inside the **same** `withConnection` block — one
     * lock acquisition, so no *concurrent* node can write to the table between the create and the
     * count. The honest bound stops there: `sql` is author-authored and H2 accepts multiple
     * statements, so an author who appends their own `INSERT` after the projection can still
     * influence the number this reports. `rows_out` is therefore "rows in the table when the node
     * finished", which is the useful quantity anyway — not a tamper-proof count of the projection.
     */
    private suspend fun tempdbCreateTableAs(
        node: ExecutableNode,
        output: NodeOutput.Tempdb,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        timeout: Int,
    ): NodeResult {
        // Phase code, not the save-time validation code (§8.2 coherence): this is the STAGE phase.
        val table =
            SqlIdentifiers.quote(
                SqlIdentifiers.requireValidTable(output.table, PipelineErrorCodes.Node.STAGING_FAILED),
            )
        val rows =
            phase(NodePhase.STAGE, node.id) {
                // 042 C1: the executed statement is the assembled `CREATE TABLE … AS <sql>`, so the
                // translation runs over the FULL text here — the run()-level BoundSql carried the
                // node SQL alone, and a prepared statement's placeholders must line up with the
                // statement actually sent. The missing-parameter gate already ran in [run], so a
                // name cannot fail it twice.
                val fullBound = SqlBindTranslator.translate("CREATE TABLE $table AS ${bound.originalSql}", ctx.values)
                ctx.staging.withConnection { connection ->
                    statementFor(connection, fullBound).use { statement ->
                        statement.queryTimeout = timeout
                        ctx.handle.withStatement(node.id, statement) {
                            update(statement, fullBound)
                            // The row count runs on its OWN statement: H2 (and other drivers)
                            // refuse Statement-level `executeQuery(String)` on a prepared
                            // statement, so the count cannot ride the one that executed the CTAS.
                            connection.createStatement().use { countStatement ->
                                countStatement.queryTimeout = timeout
                                ctx.handle.withStatement(node.id, countStatement) {
                                    countRows(countStatement, table)
                                }
                            }
                        }
                    }
                }
            }
        checkStagingBudget(node, ctx)
        // `datapipelines.staging.rows` counts rows staged across ALL executions, and a
        // tempdb→tempdb CTAS stages just as surely as `stage()` does — it simply does it inside H2
        // rather than through a cursor. Counting only the `stage()` path left the metric blind to
        // the commonest multi-node shape there is, which is worse than leaving it at zero: a
        // half-populated counter reads as a real number.
        metrics.rowsStaged(rows)
        return NodeResult.of(node.id, rows, startedAt)
    }

    /** `SELECT COUNT(*)` over a table this class just created — always exactly one row. */
    private fun countRows(
        statement: Statement,
        quotedTable: String,
    ): Long =
        statement.executeQuery("SELECT COUNT(*) FROM $quotedTable").use { rs ->
            if (rs.next()) rs.getLong(1) else 0L
        }

    /** DML/DDL against tempdb — same timeout and cancellation reasoning as [tempdbCreateTableAs]. */
    private suspend fun tempdbWrite(
        node: ExecutableNode,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        timeout: Int,
    ): NodeResult {
        val affected =
            phase(NodePhase.EXECUTE, node.id) {
                ctx.staging.withConnection { connection ->
                    statementFor(connection, bound).use { statement ->
                        statement.queryTimeout = timeout
                        ctx.handle.withStatement(node.id, statement) {
                            if (node.type == NodeType.DML) {
                                update(statement, bound).toLong()
                            } else {
                                executeDdl(statement, bound)
                            }
                        }
                    }
                }
            }
        checkStagingBudget(node, ctx)
        return NodeResult.of(node.id, affected, startedAt)
    }

    /**
     * The measured-footprint check staging performs after each of its own writes (staging §8.2),
     * re-applied here because this class issues tempdb DML through `withConnection`, which does
     * not check it. Skipping it would let a `CREATE TABLE AS SELECT` blow through the budget the
     * `stage()` path enforces.
     */
    private suspend fun checkStagingBudget(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
    ) {
        val usedBytes = phase(NodePhase.STAGE, node.id) { ctx.staging.stats().memoryUsedBytes }
        if (usedBytes / BYTES_PER_KB > ctx.stagingMaxMemoryMb * KB_PER_MB) {
            val overflow = StagingMemoryLimitException(usedBytes, ctx.stagingMaxMemoryMb)
            throw NodeFailedSignal(ErrorCodeMapper.map(overflow, NodePhase.STAGE, node.id), overflow)
        }
    }

    // ------------------------------------------------------------ datasource

    private suspend fun runOnDatasource(
        node: ExecutableNode,
        name: String,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
    ): NodeResult {
        // Workspaces design §5.3 at EXECUTION time (025 A5): resolve through the
        // execution's workspace, not by bare name — the same visibility save-time
        // validation applied. A datasource re-bound away from this workspace after the
        // pipeline was saved is the same `datasource_not_found` an unknown name gets (no
        // existence oracle), instead of executing against a row the workspace cannot see.
        val datasource =
            phase(NodePhase.CONNECT, node.id) {
                datasourceRegistry.getVisible(name, ctx.workspaceId) ?: throw datasourceNotFound(name)
            }
        // 057: from resolution on, the failure record can name the DIALECT — the one fact only
        // this leg holds. This wrapper decorates first (inner), so [decorateFailure] above
        // keeps whatever it attached. Everything a failed CONNECT can report — the T85 shape:
        // registry resolved, pool init failed — happens inside this block.
        return try {
            runOnResolvedDatasource(node, datasource, bound, ctx, startedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw decorateWithDialect(e, node, datasource.dialect)
        }
    }

    private suspend fun runOnResolvedDatasource(
        node: ExecutableNode,
        datasource: Datasource,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
    ): NodeResult {
        // Workspaces design §6 layer 2a (D10): the save-time readonly check read the registry
        // as of the SAVE; this backstop re-reads the LIVE entry (past the metadata cache) at
        // node execution time, so a datasource flagged readonly after this version was saved
        // fails HERE instead of shipping its DML/DDL. DQL reads are untouched — the check is
        // on the node type, never on the datasource alone. Deliberately name-keyed, not
        // visibility-scoped: `name` is the datasource PK, so the row the gate resolved one
        // line above is the same row this reads — scoping here would duplicate the read for
        // no new decision.
        //
        // Fail-closed (044 F2/F3): null from the live read (the row was soft-deleted out of
        // band — the D10 channel) refuses as datasource_not_found, never "no signal"; a
        // metadata-DB failure during the read refuses naming the METADATA db (carried code
        // `pipeline.execution.aborted`), never the healthy target. See [ReadonlyBackstop].
        if (node.type == NodeType.DML || node.type == NodeType.DDL) {
            phase(NodePhase.CONNECT, node.id) { enforceSourceReadonly(datasource.name, node) }
        }
        // A DQL node whose output is a datasource target is a write too (the third §5.7 shape),
        // and its refusal must not wait for the SOURCE query to finish (020 F9): during a flip
        // window the old shape ran the full — possibly long — SELECT and only then refused in
        // the write-back shell. The same fail-closed backstop, applied to the TARGET at the
        // same CONNECT phase, mirrors the DML/DDL check; the write-back shell re-checks at
        // write time (that check is authoritative — this one is the cheap early refusal).
        (node.output as? NodeOutput.Datasource)?.takeIf { node.type == NodeType.DQL }?.let { target ->
            phase(NodePhase.CONNECT, node.id) { enforceWritebackTargetReadonly(target) }
        }
        val timeout = config.queryTimeoutSecondsFor(datasource.queryTimeoutSeconds)
        val connection =
            phase(NodePhase.CONNECT, node.id) {
                // B5: `poolFor` must be INSIDE `withCause`. `pool_build` is emitted from inside
                // `poolFor`'s `computeIfAbsent`, not from `leaseConnection` — resolving the pool
                // first meant the ThreadLocal was still unset when the event fired, so
                // `DatasourceAuditEvent.cause` was null on every executor-triggered pool build and
                // the whole carry-forward was inert. The audit trail could not answer "which
                // execution caused this credential to be decrypted", which is the one question
                // datasources §7.4 exists to answer.
                auditSink?.withCause(ctx.executionId, node.id) {
                    datasourceRegistry.poolFor(datasource).leaseConnection()
                } ?: datasourceRegistry.poolFor(datasource).leaseConnection()
            }
        return connection.use { conn ->
            when (node.type) {
                NodeType.DQL -> datasourceQuery(node, conn, bound, ctx, startedAt, timeout, datasource.dialect)
                NodeType.DML -> datasourceUpdate(node, conn, bound, ctx, startedAt, timeout)
                NodeType.DDL -> datasourceDdl(node, conn, bound, ctx, startedAt, timeout)
                NodeType.PIPELINE -> error("unreachable: PIPELINE dispatched before source resolution")
            }
        }
    }

    /**
     * The inner 057 decorator: stamps the resolved dialect onto the failure record's node
     * context, filling the whole context when the signal carries none yet. Rebuilding the
     * signal (not mutating it — [MappedError] is immutable by design) preserves the original
     * cause chain, which [PipelineExecutor.failNode] unwraps for the exception detail.
     */
    private fun decorateWithDialect(
        error: Exception,
        node: ExecutableNode,
        dialect: Dialect,
    ): Exception =
        when (error) {
            is NodeFailedSignal -> {
                val context = error.error.node?.copy(dialect = dialect.name) ?: NodeErrorContext.of(node, dialect.name)
                NodeFailedSignal(error.error.copy(node = context), error.cause ?: error)
            }
            else -> error
        }

    /** The DML/DDL source leg of the layer-2a backstop — see [ReadonlyBackstop] for the semantics. */
    private fun enforceSourceReadonly(
        name: String,
        node: ExecutableNode,
    ) {
        when (ReadonlyBackstop.signal(datasourceRegistry, name)) {
            ReadonlySignal.READONLY -> throw datasourceReadonly(name, node.type)
            ReadonlySignal.ABSENT -> throw datasourceNotFound(name)
            ReadonlySignal.WRITABLE -> Unit
        }
    }

    /** The write-back TARGET leg (020 F9): same backstop, at CONNECT, before the source query. */
    private fun enforceWritebackTargetReadonly(target: NodeOutput.Datasource) {
        when (ReadonlyBackstop.signal(datasourceRegistry, target.datasource)) {
            ReadonlySignal.READONLY -> throw writebackTargetReadonly(target)
            ReadonlySignal.ABSENT -> throw datasourceNotFound(target.datasource)
            ReadonlySignal.WRITABLE -> Unit
        }
    }

    private suspend fun datasourceQuery(
        node: ExecutableNode,
        conn: Connection,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        timeout: Int,
        dialect: Dialect,
    ): NodeResult =
        statementFor(conn, bound).use { statement ->
            statement.queryTimeout = timeout
            ctx.handle.withStatement(node.id, statement) {
                val rs = phase(NodePhase.EXECUTE, node.id) { query(statement, bound) }
                // Every branch consumes the cursor INSIDE this `use` — no live ResultSet escapes.
                dispatchOutput(node, rs, ctx, startedAt, dialect)
            }
        }

    private suspend fun dispatchOutput(
        node: ExecutableNode,
        rs: ResultSet,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        dialect: Dialect,
    ): NodeResult =
        when (val output = requireOutput(node)) {
            is NodeOutput.Tempdb -> {
                val staged =
                    phase(NodePhase.STAGE, node.id) {
                        // The SOURCE node's dialect, never H2's (staging §3.2) — mapping a Postgres
                        // or Oracle cursor through H2's table picks the wrong storage type and
                        // loses data before egress re-derivation can see it.
                        ctx.staging.stage(rs, output.table, dialect).also { ctx.warnings.addAll(it.warnings) }
                    }
                // B2 (second half): staging enforces the budget it was CONSTRUCTED with — the
                // operator global — because `StagingFactory.create(executionId, engine)` has no
                // budget parameter, so a *lower* per-pipeline `max_memory_mb` never reaches it.
                // Re-checking here closes that without a staging signature change: the effective
                // (already clamped, possibly lower) budget is enforced by the executor after every
                // staged write, exactly as it is after every `withConnection` write.
                checkStagingBudget(node, ctx)
                metrics.rowsStaged(staged.rowsStaged)
                NodeResult.of(node.id, staged.rowsStaged, startedAt)
            }

            is NodeOutput.Caller -> {
                phase(NodePhase.MATERIALIZE, node.id) { deliverToCaller(node, rs, ctx, startedAt, dialect) }
            }

            is NodeOutput.Datasource -> {
                phase(NodePhase.WRITEBACK, node.id) {
                    NodeResult.of(node.id, writebackRunner.writeback(rs, output, dialect, ctx.workspaceId), startedAt)
                }
            }
        }

    /**
     * The caller-output fork (design §4.2): with a [NodeExecutionContext.directSink] the result
     * streams straight to the in-process consumer and the [ResultStore] is never touched; without
     * one it materializes into the store as it always has.
     */
    private suspend fun deliverToCaller(
        node: ExecutableNode,
        rs: ResultSet,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        dialect: Dialect,
    ): NodeResult =
        ctx.directSink
            ?.let { streamToSink(node, rs, ctx, startedAt, dialect, it) }
            ?: materialize(node, rs, ctx, startedAt, dialect)

    /**
     * `direct` delivery: the caller result streams to the execution's [DirectResultSink] instead
     * of the result store — nothing is written to Redis, there is no cursor, and the result is not
     * re-fetchable afterwards; re-running the child is the recovery path.
     *
     * Schema and row decoding go through [ResultRowReader] — the exact path
     * [ResultStore.materialize] uses — so `direct` and cursor deliveries see identical wire
     * values. The sequence is lazy over the still-open cursor and is consumed inside
     * [DirectResultSink.accept]; [NodeResult.rowsOut] counts the rows actually delivered.
     */
    private suspend fun streamToSink(
        node: ExecutableNode,
        rs: ResultSet,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        dialect: Dialect,
        sink: DirectResultSink,
    ): NodeResult {
        val schema = ResultRowReader.schemaOf(rs.metaData, dialect)
        var rowsOut = 0L
        val rows =
            sequence {
                while (rs.next()) {
                    yield(schema.columns.mapIndexed { index, column -> ResultRowReader.readValue(rs, index + 1, column) })
                    rowsOut++
                }
            }
        sink.accept(schema.columns, rows)
        ctx.warnings.addAll(schema.warnings)
        return NodeResult.of(nodeId = node.id, rowsOut = rowsOut, startedAt = startedAt, callerResultRef = null)
    }

    private suspend fun materialize(
        node: ExecutableNode,
        rs: ResultSet,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        dialect: Dialect,
    ): NodeResult {
        val stored = resultStore.materialize(ctx.executionId, rs, dialect, ctx.resultTtlSeconds)
        ctx.warnings.addAll(stored.warnings)
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = stored.totalRows,
            startedAt = startedAt,
            callerResultRef = stored.key,
            bytesOutEstimate = stored.bytes,
        )
    }

    private suspend fun datasourceUpdate(
        node: ExecutableNode,
        conn: Connection,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        timeout: Int,
    ): NodeResult =
        conn.prepareStatement(bound.sql).use { statement ->
            statement.queryTimeout = timeout
            // This path always prepared even before the round, so the statement shape is
            // unchanged; binding is the one thing the parameter case adds (042 C1/C4).
            if (bound.hasBindParameters) SqlBindTranslator.bind(statement, bound.bindValues)
            ctx.handle.withStatement(node.id, statement) {
                val affected = phase(NodePhase.EXECUTE, node.id) { statement.executeUpdate().toLong() }
                NodeResult.of(node.id, affected, startedAt)
            }
        }

    private suspend fun datasourceDdl(
        node: ExecutableNode,
        conn: Connection,
        bound: SqlBindTranslator.BoundSql,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        timeout: Int,
    ): NodeResult =
        statementFor(conn, bound).use { statement ->
            statement.queryTimeout = timeout
            ctx.handle.withStatement(node.id, statement) {
                phase(NodePhase.EXECUTE, node.id) { executeDdl(statement, bound) }
                NodeResult.of(node.id, 0L, startedAt)
            }
        }

    /** DDL reports success, not rows (§6.3.3). */
    private fun executeDdl(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Long {
        executeOf(statement, bound)
        return 0L
    }

    // ---------------------------------------------------- bound execution helpers

    /**
     * The statement [bound] executes on [connection] (042 C1): prepared and bound when the
     * rendered SQL uses `:name` parameters, otherwise created exactly as this class created
     * statements before the round. Parameterless templates therefore keep their existing
     * statement semantics byte-for-byte — including multi-statement author SQL, which a
     * prepared statement would refuse.
     */
    private fun statementFor(
        connection: Connection,
        bound: SqlBindTranslator.BoundSql,
        resultSetType: Int = ResultSet.TYPE_FORWARD_ONLY,
        resultSetConcurrency: Int = ResultSet.CONCUR_READ_ONLY,
    ): Statement =
        if (bound.hasBindParameters) {
            connection
                .prepareStatement(bound.sql, resultSetType, resultSetConcurrency)
                .also { SqlBindTranslator.bind(it, bound.bindValues) }
        } else {
            connection.createStatement(resultSetType, resultSetConcurrency)
        }

    /** `executeQuery` in the shape [bound] needs — a prepared statement already carries its SQL. */
    private fun query(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): ResultSet =
        if (bound.hasBindParameters) {
            (statement as PreparedStatement).executeQuery()
        } else {
            statement.executeQuery(bound.sql)
        }

    /** `executeUpdate` in the shape [bound] needs. */
    private fun update(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Int =
        if (bound.hasBindParameters) {
            (statement as PreparedStatement).executeUpdate()
        } else {
            statement.executeUpdate(bound.sql)
        }

    /** `execute` in the shape [bound] needs. */
    private fun executeOf(
        statement: Statement,
        bound: SqlBindTranslator.BoundSql,
    ): Boolean =
        if (bound.hasBindParameters) {
            (statement as PreparedStatement).execute()
        } else {
            statement.execute(bound.sql)
        }

    /** DQL always has a concrete output by deserialization time (§4.1). */
    private fun requireOutput(node: ExecutableNode): NodeOutput =
        requireNotNull(node.output) { "DQL node '${node.id}' reached the executor with no output block" }

    private fun datasourceNotFound(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND,
            message = "Datasource '$name' is not registered in this environment.",
            details = mapOf("datasource" to name),
        )

    /**
     * §13.4 sibling of `datasource_not_found`: the datasource resolved at write-time, but its
     * live entry is readonly now — the stored version predates the flag, or the flag flipped
     * between save and run (D10). Same HTTP class and shape as its sibling.
     */
    private fun datasourceReadonly(
        name: String,
        type: NodeType,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Node.DATASOURCE_READONLY,
        message =
            "Datasource '$name' is readonly — its ${type.wire} use is forbidden " +
                "(the flag was set after this pipeline version was saved, or the version predates it).",
        details = mapOf("datasource" to name, "node_type" to type.wire),
    )

    /**
     * The write-back shape of [datasourceReadonly] (§13.4, 020 F9): raised at the CONNECT-phase
     * pre-check of a DQL node's `output.target: "datasource"`, with the same message the
     * write-back shell's own authoritative check raises — the author gets the identical error
     * either side of the source query.
     */
    private fun writebackTargetReadonly(target: NodeOutput.Datasource) =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.DATASOURCE_READONLY,
            message =
                "Write-back target datasource '${target.datasource}' is readonly — writing '${target.table}' to it is forbidden " +
                    "(the flag was set after this pipeline version was saved, or the version predates it).",
            details = mapOf("datasource" to target.datasource, "table" to target.table),
        )

    /** Runs [body], converting any failure into a [NodeFailedSignal] with this phase's §8.2 code. */
    private suspend fun <T> phase(
        phase: NodePhase,
        nodeId: String,
        body: suspend () -> T,
    ): T =
        try {
            body()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw asNodeFailure(e, phase, nodeId)
        }

    /**
     * Cancellation passes through untouched — it is **not** a node failure (§5.2), and mapping it
     * to an error code would turn an `ABORTED` execution into a `FAILED` one. An inner phase's
     * already-mapped signal passes through too, so the outermost phase cannot re-label it.
     */
    private fun asNodeFailure(
        error: Exception,
        phase: NodePhase,
        nodeId: String,
    ): Exception =
        when (error) {
            is CancellationException -> error
            is NodeFailedSignal -> error
            else -> NodeFailedSignal(ErrorCodeMapper.map(error, phase, nodeId), error)
        }

    private companion object {
        const val BYTES_PER_KB = 1024L
        const val KB_PER_MB = 1024L
    }
}

/**
 * A node failure that already knows its catalog code — thrown by [NodeRunner], caught by
 * [PipelineExecutor], which emits `node_failed` exactly once and rethrows it as a
 * [NodeExecutionException].
 *
 * It is not itself the public failure type because §5.2 puts the `node_failed` emission at the
 * failure site's caller; one exception used for both would make "was this already emitted?"
 * ambiguous, which is exactly the duplicate-emission bug SPEC-REVIEW 2.6.2 removed.
 */
class NodeFailedSignal(
    val error: MappedError,
    cause: Throwable,
) : RuntimeException(error.message, cause)
