package co.datapipelines.executor

import co.datapipelines.datasources.ResultRowReader
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.CanonicalJson
import co.datapipelines.scripting.CompiledScript
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.ScriptEngine
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptLanguage
import co.datapipelines.scripting.TypeGate
import co.datapipelines.staging.StagingMemoryLimitException
import co.datapipelines.templates.ContractColumn
import co.datapipelines.templates.TransformContract
import co.datapipelines.templates.TransformInput
import co.datapipelines.templates.TransformInvariant
import co.datapipelines.templates.TransformMode
import co.datapipelines.templates.TransformOutput
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.DatapipelinesException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The pinned transform version the executor resolved for one node (§4.12): the body to
 * compile, the contract to check against, the invariants to run, and the lifecycle status
 * that decides whether the compiled script may be cached (a RELEASED version is immutable;
 * the sole DRAFT is overwritten in place — templates.md §5.1).
 */
class ResolvedTransform(
    val type: TemplateType,
    val status: PipelineVersionStatus,
    val body: String,
    val contract: TransformContract,
    val invariants: List<TransformInvariant>,
)

/** Resolves the pinned `{id, version}` of a TRANSFORM node inside the execution's workspace. */
fun interface TransformVersionResolver {
    fun resolve(
        workspaceId: UUID,
        ref: TemplateRef,
    ): ResolvedTransform?
}

/**
 * The TRANSFORM-node collaborators [NodeRunner] does not otherwise have (§4.12): the version
 * resolver, 7a's bounded evaluation pool, the engines, and the `datapipelines.transform.*`
 * bounds (record §4.3 — 7b declares the keys; this class is what the executor reads them
 * through). [readBatchSize] is the staging engine's `result-batch-size`, the row-mode batch.
 */
class TransformSupport(
    val resolver: TransformVersionResolver,
    val pool: ScriptEvaluationPool,
    val engines: Map<ScriptLanguage, ScriptEngine>,
    val maxInputRows: Long = 100_000,
    val maxValueBytes: Long = 1_048_576,
    val maxStringBytes: Long = 1_048_576,
    val maxDepth: Int = 100,
    val readBatchSize: Int = 10_000,
) {
    init {
        require(maxInputRows > 0) { "maxInputRows must be positive, was $maxInputRows" }
        require(maxValueBytes > 0) { "maxValueBytes must be positive, was $maxValueBytes" }
        require(maxStringBytes > 0) { "maxStringBytes must be positive, was $maxStringBytes" }
        require(maxDepth > 0) { "maxDepth must be positive, was $maxDepth" }
        require(readBatchSize > 0) { "readBatchSize must be positive, was $readBatchSize" }
    }
}

/** One gated reject: the row in storable form and its non-empty reason (§5.3). */
internal data class RejectedRow(
    val row: Map<String, Any?>,
    val reason: String,
)

/** A gated table output: the accepted rows and the rejects, both in storable form (§5.3). */
internal data class GatedTable(
    val rows: List<Map<String, Any?>>,
    val rejects: List<RejectedRow>,
)

/**
 * The TRANSFORM-node leg of [NodeRunner] (pipeline-contract §4.12, transform-nodes design §5).
 *
 * One run: resolve the pinned version and its contract, check the inputs (§5.1), drive the
 * function per mode (§5.2) through 7a's pool — never on a `dag-executor` thread — gate every
 * returned row (§5.3, R1), write tempdb / the caller / the Context, then invariants and
 * strict (§5.4), atomically (§5.5).
 *
 * ## Why this does not call `TransformTestRunner`
 *
 * 7b's runner is the executor's TWIN for the evaluate-gate-invariants core, and its pieces
 * were considered first, as the lane brief instructs. They are not reusable here for three
 * measured reasons, not preference: its §5.1 input check demands the EXACT contract column
 * set (a test case's `rows` ARE the input), where the production check is COVER — a staged
 * table may carry more columns than the contract names, and the node reads only the declared
 * ones; its evaluation runs under the fixed `evaluate-timeout-seconds`, where the node's
 * budget is its own wall-clock deadline minus `cancel-grace-seconds` (record §4.3); and its
 * gate refusals carry a case-local row number, where §5.3 wants the batch number too. What IS
 * reused is the layer both share: `TypeGate`, `CanonicalJson`, the pool and its typed
 * refusals.
 *
 * ## Row mode passes the input twice when rejects are declared
 *
 * The output table and the rejects table are two `stageRows` calls, each an atomic
 * create-drain-rollback, and each drain's sequence pulls one batch at a time — the table
 * never exists whole in the JVM. Driving both from one evaluation pass would need either an
 * unbounded rejects buffer or two drains holding cross-coroutine locks on the executor's
 * threads; neither is acceptable. The function is a pure function of its inputs (D-T4) with
 * the clock pinned to the execution's `current_timestamp`, so a second pass over the same
 * staged input re-derives the identical partition, at the price of evaluating twice. The
 * price is stated, the correctness is a theorem.
 *
 * ## The lease answer (the lane brief's fence question)
 *
 * A `withQuery` read MAY be open while `stageRows` writes on the same staging instance:
 * `drainBatches` pulls its sequence holding NO lease and takes a short INTERNAL write lease
 * per batch, so the read lease (AUTHOR, held for the whole drain) and each write lease run on
 * different connections of the execution's pool. This is the "independent operations run on
 * other connections meanwhile" case of the `Staging` contract, not its never-nest case — and
 * it needs `max-connections` ≥ 2, which every shipped default satisfies (4). At a configured
 * floor of 1 the write lease's admission suspends until the node's wall-clock deadline
 * cancels it — a bounded failure with `pipeline.node.timeout`, never a deadlock the operator
 * cannot see. Recorded in dag-executor.md §6.3.
 */
internal object TransformNodeRuns {
    /**
     * Compiled scripts of RELEASED pins, keyed `"id@version"` — a released body never varies,
     * so it compiles once (record §2.1); a DRAFT recompiles every run. Bounded: past the cap
     * the map is dropped whole rather than evicting one entry at a time under a lock.
     */
    private val compiledCache = ConcurrentHashMap<String, CompiledScript>()

    @Suppress("LongParameterList") // the run's fixed collaborators — the runner hands them through once
    suspend fun run(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        support: TransformSupport,
        config: ExecutorConfig,
        resultStore: ResultStore,
    ): NodeResult {
        val resolved =
            phase(ctx, NodePhase.RENDER, node.id) {
                support.resolver.resolve(ctx.workspaceId, node.template)
                    ?: throw DatapipelinesException(
                        code = PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND,
                        message = "Template '${node.template.key}' is not in the template registry.",
                        details = mapOf("template" to node.template.key, "node" to node.id),
                    )
            }
        val engine = engineFor(resolved.type, support, node)
        val script = compile(node, resolved, engine)
        val limits = limitsOf(node, ctx, support, config)
        val meta = mapOf("node_id" to node.id, "context_key" to node.contextKey)

        // §3.1's implicit optional execute input, the calculator rule (078 A5): the caller
        // supplied the key, so the node does NOT evaluate — the supplied value is gated
        // against the contract's declared output and the stats carry provided_by: "caller".
        val contextKey = node.contextKey
        if (resolved.contract.mode == TransformMode.VALUE && contextKey != null && contextKey in ctx.values.callerSupplied) {
            return callerSuppliedSkip(node, ctx, startedAt, support, resolved.contract, contextKey)
        }

        val run =
            TransformRun(
                node = node,
                ctx = ctx,
                startedAt = startedAt,
                support = support,
                resolved = resolved,
                engine = engine,
                script = script,
                limits = limits,
                meta = meta,
                resultStore = resultStore,
            )
        try {
            return when (resolved.contract.mode) {
                TransformMode.ROW -> run.rowMode()
                TransformMode.TABLE -> run.singleShot(tableMode = true)
                TransformMode.VALUE -> run.singleShot(tableMode = false)
            }
        } catch (e: CancellationException) {
            run.dropWritten()
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // §5.5: any refusal after a write began drops the COMPLETED tables — staging rolls
            // back the partial one it is writing itself (staging §4.3); the sibling is ours.
            run.dropWritten()
            throw e
        }
    }

    private fun engineFor(
        type: TemplateType,
        support: TransformSupport,
        node: ExecutableNode,
    ): ScriptEngine {
        val language =
            when (type) {
                TemplateType.JSONATA -> ScriptLanguage.JSONATA
                TemplateType.JAVASCRIPT -> ScriptLanguage.JAVASCRIPT
                else -> error("unreachable: §12.13 pins a transform type on '${node.id}'")
            }
        return support.engines[language]
            ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Transform.JS_UNAVAILABLE,
                message = "No engine for type '${type.wire}' yet (javascript ships in round two).",
                details = mapOf("node" to node.id, "type" to type.wire),
            )
    }

    /** Compiles the pinned body — cached for RELEASED (immutable), recompiled for a DRAFT (record §2.1). */
    private fun compile(
        node: ExecutableNode,
        resolved: ResolvedTransform,
        engine: ScriptEngine,
    ): CompiledScript =
        if (resolved.status == PipelineVersionStatus.RELEASED) {
            if (compiledCache.size >= COMPILED_CACHE_CAP) compiledCache.clear()
            compiledCache.getOrPut(node.template.key) { engine.compile(resolved.body) }
        } else {
            engine.compile(resolved.body)
        }

    /**
     * The evaluation's bounds for this node (record §4.3): the node's own wall-clock deadline
     * minus `cancel-grace-seconds`, so the engine reports `pipeline.transform.timeout` first
     * and the executor's cancel is the backstop; the clock pinned to the execution's
     * `current_timestamp` (the platform tier), or a body calling `$now()`/`$millis()` refuses.
     */
    private fun limitsOf(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        support: TransformSupport,
        config: ExecutorConfig,
    ): EvaluationLimits {
        val deadlineSeconds = config.nodeTimeoutSecondsFor(node.timeoutSeconds)
        val wallClock = (deadlineSeconds - config.cancelGraceSeconds).coerceAtLeast(1)
        return EvaluationLimits(
            wallClock = Duration.ofSeconds(wallClock),
            maxDepth = support.maxDepth,
            now = ctx.values[ContextKeys.CURRENT_TIMESTAMP] as? Instant,
        )
    }

    /**
     * The 078 A5 rule applied to a value-mode TRANSFORM (record §3.1): the key was supplied at
     * execute time, so the node does NOT evaluate — but the supplied value is checked against
     * the contract's declared output first (an object output accepts any JSON object under the
     * byte cap), because an unchecked override would put a value into the Context that the
     * contract says cannot occur.
     */
    private fun callerSuppliedSkip(
        node: ExecutableNode,
        ctx: NodeExecutionContext,
        startedAt: Instant,
        support: TransformSupport,
        contract: TransformContract,
        contextKey: String,
    ): NodeResult {
        val supplied = ctx.values[contextKey]
        when (val output = contract.output) {
            is TransformOutput.Obj -> {
                when (
                    val verdict =
                        TypeGate.over(emptyList(), support.maxStringBytes).gateObject(supplied, support.maxValueBytes)
                ) {
                    is TypeGate.GateResult.Refuse -> throw TransformFailures.inputContract(node, describe(verdict.refusal), "supplied_value")
                    is TypeGate.GateResult.Pass -> Unit
                }
            }

            is TransformOutput.Value -> {
                val column = ColumnSchema(contextKey, output.type, output.precision, output.scale, nullable = false)
                val wire = TransformValues.wireValueOf(supplied, output.type)
                when (val verdict = TypeGate.over(listOf(column), support.maxStringBytes).gateValue(wire, column)) {
                    is TypeGate.GateResult.Refuse -> throw TransformFailures.inputContract(node, describe(verdict.refusal), "supplied_value")
                    is TypeGate.GateResult.Pass -> Unit
                }
            }

            is TransformOutput.Table -> error("unreachable: a caller-supplied key exists only on value mode")
        }
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = 0,
            startedAt = startedAt,
            contextKey = contextKey,
            contextValue = TransformValues.renderContextValue(ctx.values[contextKey]),
            providedBy = NodeResult.PROVIDED_BY_CALLER,
        )
    }

    /**
     * The phase wrapper, [NodeRunner]'s own shape: records the phase entry and maps any
     * unmapped failure through [ErrorCodeMapper] — a carried §13.18 code always wins.
     */
    internal suspend fun <T> phase(
        ctx: NodeExecutionContext,
        phase: NodePhase,
        nodeId: String,
        body: suspend () -> T,
    ): T =
        try {
            ctx.phases.enter(nodeId, phase)
            body()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw when (e) {
                is CancellationException -> e
                is NodeFailedSignal -> e
                else -> NodeFailedSignal(ErrorCodeMapper.map(e, phase, nodeId), e)
            }
        }

    internal fun describe(refusal: co.datapipelines.scripting.GateRefusal): String =
        when (refusal) {
            is co.datapipelines.scripting.GateRefusal.RowShapeMismatch -> {
                "row ${refusal.row}: shape mismatch (missing ${refusal.missing}, extra ${refusal.extra})"
            }

            is co.datapipelines.scripting.GateRefusal.ValueTypeMismatch -> {
                "${refusal.column}: expected ${refusal.expected}, got ${refusal.actual}"
            }

            is co.datapipelines.scripting.GateRefusal.PrecisionLost -> {
                "${refusal.column}: ${refusal.detail}"
            }

            is co.datapipelines.scripting.GateRefusal.ValueTooLarge -> {
                "${refusal.column}: ${refusal.bytes} bytes above the cap"
            }
        }

    private const val COMPILED_CACHE_CAP = 256
}

/**
 * One TRANSFORM run's state — the per-mode legs of [TransformNodeRuns], split out so the
 * dispatch object stays a dispatch object. Everything here is fixed for the run.
 */
internal class TransformRun(
    private val node: ExecutableNode,
    private val ctx: NodeExecutionContext,
    private val startedAt: Instant,
    private val support: TransformSupport,
    private val resolved: ResolvedTransform,
    private val engine: ScriptEngine,
    private val script: CompiledScript,
    private val limits: EvaluationLimits,
    private val meta: Map<String, Any?>,
    private val resultStore: ResultStore,
) {
    private val contract: TransformContract get() = resolved.contract

    /** Tables whose `stageRows` COMPLETED — dropped on any later refusal (§5.5). */
    private val written = mutableListOf<String>()

    /** The rejects table's name, when this run stages one — strict's read target (§5.4). */
    private var rejectsTable: String? = null

    /**
     * Row-mode caller outputs with invariants declared: the accepted/rejected halves buffered
     * during the single pass, because no tempdb table exists to read back (§5.4). Bounded —
     * the buffer refuses past `max-input-rows` with `invariants_too_large`.
     */
    private val bufferedRows = mutableListOf<Map<String, Any?>>()
    private val bufferedRejects = mutableListOf<RejectedRow>()
    private var buffered = false

    // ---------------------------------------------------------------- row mode

    suspend fun rowMode(): NodeResult {
        val inputEntry =
            contract.inputs.entries.singleOrNull { it.value is TransformInput.Table }
                ?: throw TransformFailures.inputContract(node, "row mode requires exactly one table input", "row_mode_inputs")
        val inputColumns = (inputEntry.value as TransformInput.Table).columns
        val outputColumns = (contract.output as TransformOutput.Table).columns
        val quotedInput = quoteTable(stagedTableName(inputEntry.key))
        val stagedSchema = stagedSchema(quotedInput)
        TransformChecks.coverCheck(node, inputEntry.key, inputColumns, stagedSchema)
        val selectList = inputColumns.joinToString(", ") { SqlIdentifiers.quote(it.name) }
        val valueInputs = resolveValueInputs()
        val collect = resolved.invariants.isNotEmpty() && node.output == NodeOutput.Caller

        var rowsIn = 0L
        var rowsOut = 0L
        var rowsRejected = 0L
        when (val output = node.output) {
            is NodeOutput.Tempdb -> {
                val outTable = requireTable(output.table)
                beginOperation()
                val observer = StagingObserverBridge(node.id, ctx.operations.observerFor(node.id), ctx.nodeProgress)
                val staged =
                    TransformNodeRuns.phase(ctx, NodePhase.STAGE, node.id) {
                        ctx.staging.withQuery("SELECT $selectList FROM $quotedInput") { rs ->
                            ctx.staging.stageRows(
                                outTable,
                                outputColumns.toColumnSchemas(),
                                batchRows(rs, inputColumns, stagedSchema, valueInputs, outputColumns, Split.ACCEPTED) {
                                    rowsIn += it
                                },
                                observer,
                            )
                        }
                    }
                written += outTable
                rowsOut = staged.rowsStaged
                reCheckStagingBudget()
                if (contract.rejects) {
                    val rejectsName = requireTable(output.rejects.orEmpty())
                    val stagedRejects =
                        TransformNodeRuns.phase(ctx, NodePhase.STAGE, node.id) {
                            ctx.staging.withQuery("SELECT $selectList FROM $quotedInput") { rs ->
                                ctx.staging.stageRows(
                                    rejectsName,
                                    inputColumns.toColumnSchemas() + TransformValues.REASON_COLUMN,
                                    batchRows(rs, inputColumns, stagedSchema, valueInputs, outputColumns, Split.REJECTED) {},
                                    observer,
                                )
                            }
                        }
                    written += rejectsName
                    rejectsTable = rejectsName
                    rowsRejected = stagedRejects.rowsStaged
                    reCheckStagingBudget()
                }
            }

            NodeOutput.Caller -> {
                beginOperation()
                rowsOut =
                    TransformNodeRuns.phase(ctx, NodePhase.MATERIALIZE, node.id) {
                        ctx.staging.withQuery("SELECT $selectList FROM $quotedInput") { rs ->
                            deliverCaller(
                                outputColumns.toColumnSchemas(),
                                batchRows(
                                    rs, inputColumns, stagedSchema, valueInputs, outputColumns, Split.ACCEPTED,
                                    collectRejects = collect,
                                ) { rowsIn += it },
                            ).rowsOut
                        }
                    }
            }

            else -> error("unreachable: §12.13 refuses ${node.output} on a row-mode TRANSFORM")
        }
        val invariantsChecked = checkInvariants(valueInputs, InvariantSource.Row(quotedInput, inputEntry.key, inputColumns, outputColumns))
        checkStrict(rowsRejected)
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = rowsOut,
            startedAt = startedAt,
            rowsIn = rowsIn,
            rowsRejected = rowsRejected,
            invariantsChecked = invariantsChecked,
        )
    }

    // ---------------------------------------------------------------- table / value modes

    /**
     * The single-evaluation modes (§5.2): every table input loaded whole — capped BEFORE
     * loading (`input_too_large`) — one evaluation on the pool, the output gated, then the
     * write. A `value` run writes its Context key only after invariants and strict pass (§5.5).
     */
    suspend fun singleShot(tableMode: Boolean): NodeResult {
        val valueInputs = resolveValueInputs()
        val loadedTables = loadTableInputs()
        val inputObject = mapOf("inputs" to (valueInputs + loadedTables), "meta" to meta)
        val evaluated =
            TransformNodeRuns.phase(ctx, NodePhase.EXECUTE, node.id) {
                evaluate(inputObject)
            }

        var rowsOut = 0L
        var rowsRejected = 0L
        var gatedValue: Any? = null
        var gatedRows: List<Map<String, Any?>> = emptyList()
        when (val output = contract.output) {
            is TransformOutput.Table -> {
                val outputColumns = output.columns
                val gated = shapeAndGateTable(outputColumns, evaluated, batch = null)
                gatedRows = gated.rows
                when (val nodeOutput = node.output) {
                    is NodeOutput.Tempdb -> {
                        val outTable = requireTable(nodeOutput.table)
                        beginOperation()
                        val observer = StagingObserverBridge(node.id, ctx.operations.observerFor(node.id), ctx.nodeProgress)
                        val staged =
                            TransformNodeRuns.phase(ctx, NodePhase.STAGE, node.id) {
                                ctx.staging.stageRows(
                                    outTable,
                                    outputColumns.toColumnSchemas(),
                                    gated.rows.asSequence().map { TransformValues.storageRow(it, outputColumns) },
                                    observer,
                                )
                            }
                        written += outTable
                        rowsOut = staged.rowsStaged
                        reCheckStagingBudget()
                        if (contract.rejects) {
                            val rejectsName = requireTable(nodeOutput.rejects.orEmpty())
                            val stagedRejects =
                                TransformNodeRuns.phase(ctx, NodePhase.STAGE, node.id) {
                                    ctx.staging.stageRows(
                                        rejectsName,
                                        outputColumns.toColumnSchemas() + TransformValues.REASON_COLUMN,
                                        gated.rejects.asSequence().map { TransformValues.rejectRow(it, outputColumns) },
                                        observer,
                                    )
                                }
                            written += rejectsName
                            rejectsTable = rejectsName
                            rowsRejected = stagedRejects.rowsStaged
                            reCheckStagingBudget()
                        }
                    }

                    NodeOutput.Caller -> {
                        beginOperation()
                        rowsOut =
                            TransformNodeRuns.phase(ctx, NodePhase.MATERIALIZE, node.id) {
                                deliverCaller(
                                    outputColumns.toColumnSchemas(),
                                    gated.rows.asSequence().map { TransformValues.storageRow(it, outputColumns) },
                                ).rowsOut
                            }
                    }

                    else -> error("unreachable: §12.13 refuses ${node.output} on a table-mode TRANSFORM")
                }
            }

            is TransformOutput.Value -> {
                val column = ColumnSchema("value", output.type, output.precision, output.scale, nullable = false)
                gatedValue =
                    when (
                        val verdict =
                            TypeGate.over(listOf(column), support.maxStringBytes).gateValue(evaluated, column)
                    ) {
                        is TypeGate.GateResult.Refuse -> throw TransformFailures.gateFailure(node, verdict.refusal, batch = null)
                        is TypeGate.GateResult.Pass -> verdict.value
                    }
                valueSizeCheck(gatedValue)
            }

            is TransformOutput.Obj -> {
                gatedValue =
                    when (
                        val verdict =
                            TypeGate.over(emptyList(), support.maxStringBytes).gateObject(evaluated, support.maxValueBytes)
                    ) {
                        is TypeGate.GateResult.Refuse -> throw TransformFailures.gateFailure(node, verdict.refusal, batch = null)
                        is TypeGate.GateResult.Pass -> verdict.value
                    }
            }
        }
        val invariantsChecked =
            checkInvariants(
                valueInputs + loadedTables,
                InvariantSource.Single(if (tableMode) gatedRows else gatedValue),
            )
        checkStrict(rowsRejected)
        // §5.5: the Context key is written only after invariants and strict pass.
        if (!tableMode) {
            val contextKey =
                requireNotNull(node.contextKey) { "value-mode TRANSFORM '${node.id}' reached the executor with no context_key (§12.13)" }
            ctx.values.put(contextKey, TransformValues.contextForm(gatedValue, contract.output))
        }
        return NodeResult.of(
            nodeId = node.id,
            rowsOut = rowsOut,
            startedAt = startedAt,
            contextKey = if (tableMode) null else node.contextKey,
            contextValue = if (tableMode) null else TransformValues.renderContextValue(gatedValue),
            rowsIn = loadedTables.values.sumOf { it.size.toLong() },
            rowsRejected = rowsRejected,
            invariantsChecked = invariantsChecked,
        )
    }

    // ---------------------------------------------------------------- the batch loop

    /** Which half of the function's partition a drain keeps (§5.2) — see the class KDoc for the two passes. */
    private enum class Split { ACCEPTED, REJECTED }

    /**
     * The row-mode batch loop as a lazy sequence of storage rows (§5.2): read
     * `result-batch-size` rows of the staged input, scan §5.1's nullable rule, evaluate
     * `{ rows, inputs, meta }` on the pool, gate every returned row (§5.3 — batch and row
     * numbers ride the refusal), yield the half [keep] names. Nothing but the current batch
     * lives in the JVM. [collectRejects] buffers both halves for a caller output's invariants
     * (bounded — see [buffer]).
     */
    @Suppress("LongParameterList") // the loop's fixed context — one per drain
    private fun batchRows(
        rs: ResultSet,
        inputColumns: List<ContractColumn>,
        stagedSchema: List<ColumnSchema>,
        valueInputs: Map<String, Any?>,
        outputColumns: List<ContractColumn>,
        keep: Split,
        collectRejects: Boolean = false,
        countRead: (Long) -> Unit,
    ): Sequence<List<Any?>> =
        sequence {
            var batchNumber = 0
            while (true) {
                val batch = readBatch(rs, inputColumns, support.readBatchSize)
                if (batch.isEmpty()) break
                batchNumber++
                countRead(batch.size.toLong())
                TransformChecks.nullableScan(node, batch, inputColumns, stagedSchema, batchNumber)
                val evaluated = evaluate(mapOf("rows" to batch, "inputs" to valueInputs, "meta" to meta))
                val gated = shapeAndGateTable(outputColumns, evaluated, batchNumber)
                if (collectRejects) buffer(gated)
                when (keep) {
                    Split.ACCEPTED -> gated.rows.forEach { yield(TransformValues.storageRow(it, outputColumns)) }
                    Split.REJECTED -> gated.rejects.forEach { yield(TransformValues.rejectRow(it, inputColumns)) }
                }
            }
        }

    /** One batch of the staged input, decoded and converted to the wire forms the engine sees (§6). */
    private fun readBatch(
        rs: ResultSet,
        columns: List<ContractColumn>,
        batchSize: Int,
    ): List<Map<String, Any?>> {
        val schemas = columns.toColumnSchemas()
        val batch = ArrayList<Map<String, Any?>>(batchSize.coerceAtMost(1_024))
        while (batch.size < batchSize && rs.next()) {
            val row = LinkedHashMap<String, Any?>(columns.size)
            columns.forEachIndexed { index, column ->
                val decoded = ResultRowReader.readValue(rs, index + 1, schemas[index])
                row[column.name] = TransformValues.wireValueOf(decoded, column.type)
            }
            batch += row
        }
        return batch
    }

    /**
     * The caller-output's invariants buffer (§5.4): bounded by `max-input-rows` — an output
     * the invariants could never read back anyway fails HERE, before the heap pays for it.
     */
    private fun buffer(gated: GatedTable) {
        buffered = true
        bufferedRows += gated.rows
        bufferedRejects += gated.rejects
        val total = bufferedRows.size + bufferedRejects.size
        if (total > support.maxInputRows) {
            throw TransformFailures.invariantsTooLarge(node, total.toLong(), support.maxInputRows)
        }
    }

    // ---------------------------------------------------------------- inputs (§5.1)

    /** The staged table's columns, read from a `LIMIT 0` cursor's metadata — never a row. */
    private suspend fun stagedSchema(quotedTable: String): List<ColumnSchema> =
        ctx.staging.withQuery("SELECT * FROM $quotedTable LIMIT 0") { rs ->
            ResultRowReader.schemaOf(rs.metaData, ctx.tempdbDialect).columns
        }

    /**
     * The value inputs, resolved from the live Context and converted to wire form (§6). A Map
     * value is an object-valued key (R4): it binds only to a TRANSFORM input, and crosses as
     * the object itself under the byte cap — the one input shape no `LogicalType` names.
     */
    private fun resolveValueInputs(): Map<String, Any?> =
        contract.inputs
            .filterValues { it is TransformInput.Value }
            .mapValues { (name, input) ->
                input as TransformInput.Value
                val key =
                    node.inputs.orEmpty()[name]
                        ?.takeIf { it.isTextual }
                        ?.asText()
                        ?.removePrefix("$")
                val value = ctx.values[key]
                if (value is Map<*, *>) {
                    when (
                        val verdict =
                            TypeGate.over(emptyList(), support.maxStringBytes).gateObject(value, support.maxValueBytes)
                    ) {
                        is TypeGate.GateResult.Refuse ->
                            throw TransformFailures.inputContract(node, "input '$name': ${TransformNodeRuns.describe(verdict.refusal)}", "object_too_large")

                        is TypeGate.GateResult.Pass -> verdict.value
                    }
                } else {
                    val wire = TransformValues.wireValueOf(value, input.type)
                    val column = ColumnSchema(name, input.type, input.precision, input.scale, nullable = false)
                    when (val verdict = TypeGate.over(listOf(column), support.maxStringBytes).gateValue(wire, column)) {
                        is TypeGate.GateResult.Refuse ->
                            throw TransformFailures.inputContract(node, "input '$name': ${TransformNodeRuns.describe(verdict.refusal)}", "value_type")

                        is TypeGate.GateResult.Pass -> verdict.value
                    }
                }
            }

    /** The table inputs of `table`/`value` mode, each counted BEFORE it is loaded (§4.3). */
    private suspend fun loadTableInputs(): Map<String, List<Map<String, Any?>>> =
        contract.inputs
            .filterValues { it is TransformInput.Table }
            .mapValues { (name, input) ->
                input as TransformInput.Table
                val quoted = quoteTable(stagedTableName(name))
                val count = countRows(quoted)
                if (count > support.maxInputRows) {
                    throw TransformFailures.tooLarge(node, "input '$name' has $count rows (cap ${support.maxInputRows}, max-input-rows)")
                }
                val stagedSchema = stagedSchema(quoted)
                TransformChecks.coverCheck(node, name, input.columns, stagedSchema)
                val selectList = input.columns.joinToString(", ") { SqlIdentifiers.quote(it.name) }
                val loaded =
                    ctx.staging.withQuery("SELECT $selectList FROM $quoted") { rs ->
                        buildList {
                            while (true) {
                                val batch = readBatch(rs, input.columns, support.readBatchSize)
                                if (batch.isEmpty()) break
                                addAll(batch)
                            }
                        }
                    }
                TransformChecks.nullableScan(node, loaded, input.columns, stagedSchema, batchNumber = 1)
                loaded
            }

    /** The table name a node's `inputs` entry names (a staged table, never a `$key` — §12.13). */
    private fun stagedTableName(input: String): String =
        node.inputs.orEmpty()[input]
            ?.takeIf { it.isTextual }
            ?.asText()
            ?: throw TransformFailures.inputContract(node, "input '$input' names no staged table", "input_missing")

    // ---------------------------------------------------------------- evaluate + gate

    /** One pooled evaluation — the engine never runs on a `dag-executor` thread (record §4.3). */
    private fun evaluate(inputObject: Map<String, Any?>): Any? =
        try {
            support.pool.run(limits, node.template.key) { engine.evaluate(script, inputObject, limits) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw TransformFailures.decorate(e, node)
        }

    /** The engine's result shaped per the contract and gated row by row (§5.3, R1). */
    @Suppress("UNCHECKED_CAST") // gateRow's Pass value is a LinkedHashMap by the gate's contract
    private fun shapeAndGateTable(
        outputColumns: List<ContractColumn>,
        evaluated: Any?,
        batch: Int?,
    ): GatedTable {
        val (rawRows, rawRejects) =
            shapeRows(evaluated)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Transform.ROW_SHAPE_MISMATCH,
                    message = "TRANSFORM node '${node.id}': the function's return does not match the declared output shape.",
                    details = mapOf("node" to node.id, "batch" to batch),
                )
        val gate = TypeGate.over(outputColumns.toColumnSchemas(), support.maxStringBytes)
        val rows = mutableListOf<Map<String, Any?>>()
        rawRows.forEachIndexed { index, entry ->
            val map =
                entry as? Map<String, Any?>
                    ?: throw TransformFailures.rowShape(node, batch, index + 1, "row is not an object")
            when (val verdict = gate.gateRow(map, index + 1, batch)) {
                is TypeGate.GateResult.Refuse -> throw TransformFailures.gateFailure(node, verdict.refusal, batch)
                is TypeGate.GateResult.Pass -> rows += verdict.value as Map<String, Any?>
            }
        }
        val rejects = gateRejects(outputColumns, rawRejects, batch)
        return GatedTable(rows, rejects)
    }

    /** The `{ rows, rejects }` or bare array the function returned, as raw lists (§5.3's mix-shape rule). */
    private fun shapeRows(evaluated: Any?): Pair<List<*>, List<*>>? {
        if (!contract.rejects) {
            val rows = evaluated as? List<*> ?: return null
            return rows to emptyList<Any?>()
        }
        val asMap = evaluated as? Map<*, *> ?: return null
        val rows = asMap["rows"] as? List<*> ?: return null
        val rejects = asMap["rejects"] as? List<*> ?: return null
        return rows to rejects
    }

    /** The rejects half, gated: `row` fits the mode's reject columns, `reason` is a non-empty string (§5.3). */
    @Suppress("UNCHECKED_CAST") // gateRow's Pass value is a LinkedHashMap by the gate's contract
    private fun gateRejects(
        outputColumns: List<ContractColumn>,
        rawRejects: List<*>,
        batch: Int?,
    ): List<RejectedRow> {
        if (!contract.rejects) return emptyList()
        val rejectColumns = rejectColumnsOf(outputColumns)
        val gate = TypeGate.over(rejectColumns.toColumnSchemas(), support.maxStringBytes)
        val rejects = mutableListOf<RejectedRow>()
        rawRejects.forEachIndexed { index, entry ->
            val map =
                entry as? Map<String, Any?>
                    ?: throw TransformFailures.rowShape(node, batch, index + 1, "reject is not an object")
            val reason =
                (map["reason"] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw DatapipelinesException(
                        code = PipelineErrorCodes.Transform.VALUE_TYPE_MISMATCH,
                        message = "TRANSFORM node '${node.id}': reject ${index + 1}: 'reason' must be a non-empty string.",
                        details = mapOf("node" to node.id, "reject" to index + 1),
                    )
            val row =
                map["row"] as? Map<String, Any?>
                    ?: throw TransformFailures.rowShape(node, batch, index + 1, "reject 'row' is not an object")
            when (val verdict = gate.gateRow(row, index + 1, batch)) {
                is TypeGate.GateResult.Refuse -> throw TransformFailures.gateFailure(node, verdict.refusal, batch)
                is TypeGate.GateResult.Pass -> rejects += RejectedRow(verdict.value as Map<String, Any?>, reason)
            }
        }
        return rejects
    }

    /** The rejects' row columns (record §2.2): the single table input's in row mode, the output's otherwise. */
    private fun rejectColumnsOf(outputColumns: List<ContractColumn>): List<ContractColumn> =
        when (contract.mode) {
            TransformMode.ROW -> contract.inputs.values.filterIsInstance<TransformInput.Table>().singleOrNull()?.columns
            else -> outputColumns
        }.orEmpty()

    // ---------------------------------------------------------------- invariants + strict (§5.4)

    /**
     * What the invariants read back (§5.4): the WRITTEN tempdb tables — never the JVM's
     * memory of them — with the single table input read back under its contract name in row
     * mode; the single-evaluation modes' value/caller output, which has no table, reads as
     * the gated value itself.
     */
    private sealed interface InvariantSource {
        /** Row mode: [quotedInput] is the staged input table; [inputName] its contract name. */
        data class Row(
            val quotedInput: String,
            val inputName: String,
            val inputColumns: List<ContractColumn>,
            val outputColumns: List<ContractColumn>,
        ) : InvariantSource

        /** Table/value mode: [gated] is the caller output's rows or the gated value. */
        data class Single(
            val gated: Any?,
        ) : InvariantSource
    }

    /**
     * §5.4: every declared invariant over `{ rows, rejects, inputs }`, read back from the
     * written tables under the `max-input-rows` bound — and NO bound when the template
     * declares no invariant: then the node streams without any read-back at all. Returns how
     * many invariants were evaluated.
     */
    private suspend fun checkInvariants(
        inputs: Map<String, Any?>,
        source: InvariantSource,
    ): Int {
        val invariants = resolved.invariants
        if (invariants.isEmpty()) return 0
        val total = readBackSize(inputs, source)
        if (total > support.maxInputRows) {
            throw TransformFailures.invariantsTooLarge(node, total, support.maxInputRows)
        }
        val invariantObject = invariantObjectOf(inputs, source)
        val jsonata =
            support.engines[ScriptLanguage.JSONATA]
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Transform.EVALUATION_FAILED,
                    message = "No JSONata engine wired for the invariants of node '${node.id}'.",
                    details = mapOf("node" to node.id),
                )
        invariants.forEach { invariant ->
            val verdict =
                try {
                    support.pool.run(limits, "${node.template.key} invariant '${invariant.name}'") {
                        jsonata.evaluate(jsonata.compile(invariant.expr), invariantObject, limits)
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    throw TransformFailures.decorate(e, node)
                }
            if (verdict != true) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Transform.INVARIANT_FAILED,
                    message = "TRANSFORM node '${node.id}': invariant '${invariant.name}' is not true: ${invariant.message}",
                    details = mapOf("node" to node.id, "invariant" to invariant.name, "message" to invariant.message),
                )
            }
        }
        return invariants.size
    }

    /** The §5.4 bound's count: rows + rejects + table inputs, before anything is read back. */
    private suspend fun readBackSize(
        inputs: Map<String, Any?>,
        source: InvariantSource,
    ): Long {
        var total = 0L
        written.forEach { total += countRows(quoteTable(it)) }
        when (source) {
            is InvariantSource.Row -> {
                if (buffered) {
                    total += bufferedRows.size + bufferedRejects.size
                }
                total += countRows(source.quotedInput)
            }

            is InvariantSource.Single -> {
                inputs.values.filterIsInstance<List<*>>().forEach { total += it.size.toLong() }
            }
        }
        return total
    }

    /** Builds the invariants' `{ rows, rejects, inputs }` (§5.4). */
    private suspend fun invariantObjectOf(
        inputs: Map<String, Any?>,
        source: InvariantSource,
    ): Map<String, Any?> =
        when (source) {
            is InvariantSource.Row -> {
                val rows: Any
                val rejects: Any
                if (buffered) {
                    rows = bufferedRows
                    rejects = bufferedRejects.map { mapOf("row" to it.row, "reason" to it.reason) }
                } else {
                    rows = written.firstOrNull()?.let { loadBack(quoteTable(it), source.outputColumns) }.orEmpty()
                    rejects =
                        rejectsTable
                            ?.let { loadBackRejects(quoteTable(it), rejectColumnsOf(source.outputColumns)) }
                            .orEmpty()
                }
                val inputRows = loadBack(source.quotedInput, source.inputColumns)
                mapOf("rows" to rows, "rejects" to rejects, "inputs" to (inputs + mapOf(source.inputName to inputRows)))
            }

            is InvariantSource.Single -> {
                val rows =
                    when {
                        written.isNotEmpty() -> loadBack(quoteTable(written.first()), (contract.output as TransformOutput.Table).columns)
                        else -> source.gated ?: emptyList<Any?>()
                    }
                val rejects =
                    rejectsTable
                        ?.let { loadBackRejects(quoteTable(it), rejectColumnsOf((contract.output as TransformOutput.Table).columns)) }
                        .orEmpty()
                mapOf("rows" to rows, "rejects" to rejects, "inputs" to inputs)
            }
        }

    /** A written table read back whole as wire-shaped row maps (called only under the §5.4 bound). */
    private suspend fun loadBack(
        quotedTable: String,
        columns: List<ContractColumn>,
    ): List<Map<String, Any?>> {
        if (columns.isEmpty()) return emptyList()
        val selectList = columns.joinToString(", ") { SqlIdentifiers.quote(it.name) }
        return ctx.staging.withQuery("SELECT $selectList FROM $quotedTable") { rs ->
            buildList {
                while (true) {
                    val batch = readBatch(rs, columns, support.readBatchSize)
                    if (batch.isEmpty()) break
                    addAll(batch)
                }
            }
        }
    }

    /** The rejects table read back as `{ row, reason }` pairs — the shape the invariants see (§5.4). */
    private suspend fun loadBackRejects(
        quotedTable: String,
        columns: List<ContractColumn>,
    ): List<Map<String, Any?>> =
        loadBack(quotedTable, columns + TransformValues.REASON_CONTRACT_COLUMN).map { staged ->
            mapOf(
                "row" to columns.associate { it.name to staged[it.name] },
                "reason" to staged[TransformValues.REASON_COLUMN.name],
            )
        }

    /** §5.4's second half: a non-empty rejects table with `strict: true` fails the node. */
    private suspend fun checkStrict(rowsRejected: Long) {
        val table = rejectsTable ?: return
        if (node.strict != true || !contract.rejects || rowsRejected == 0L) return
        val reasons =
            ctx.staging.withQuery(
                "SELECT ${SqlIdentifiers.quote(TransformValues.REASON_COLUMN.name)} FROM ${quoteTable(table)} LIMIT $STRICT_REASON_SAMPLE",
            ) { rs ->
                buildList {
                    while (rs.next()) add(rs.getString(1))
                }
            }
        throw DatapipelinesException(
            code = PipelineErrorCodes.Transform.REJECTS_STRICT,
            message =
                "TRANSFORM node '${node.id}' is strict and the function rejected $rowsRejected row(s): " +
                    reasons.joinToString("; "),
            details =
                mapOf(
                    "node" to node.id,
                    "rejected" to rowsRejected,
                    "first_reasons" to reasons,
                ),
        )
    }

    // ---------------------------------------------------------------- caller delivery

    /**
     * The caller-output fork of a `row`/`table` TRANSFORM (§9.6): with a [NodeExecutionContext.directSink]
     * the rows stream to the invoking parent; without one they materialize into the result
     * store, and the endpoint serves them through the existing contract.
     */
    private suspend fun deliverCaller(
        columns: List<ColumnSchema>,
        rows: Sequence<List<Any?>>,
    ): NodeResult {
        val sink = ctx.directSink
        return if (sink != null) {
            var rowsOut = 0L
            sink.accept(columns, rows.onEach { rowsOut++ })
            ctx.operations.observerFor(node.id).let {
                it.written(rowsOut)
                it.committed()
            }
            NodeResult.of(nodeId = node.id, rowsOut = rowsOut, startedAt = startedAt, callerResultRef = null)
        } else {
            val stored =
                resultStore.materializeRows(
                    ctx.executionId,
                    columns,
                    rows,
                    ctx.resultTtlSeconds,
                    ctx.operations.observerFor(node.id),
                )
            NodeResult.of(
                nodeId = node.id,
                rowsOut = stored.totalRows,
                startedAt = startedAt,
                callerResultRef = stored.key,
                bytesOutEstimate = stored.bytes,
            )
        }
    }

    // ---------------------------------------------------------------- small pieces

    private fun beginOperation() {
        val (kind, destination) = NodeOperations.operationFor(node)
        ctx.operations.begin(node.id, kind, destination).enter(OperationPhase.EXECUTING)
    }

    private fun quoteTable(table: String): String = SqlIdentifiers.quote(requireTable(table))

    private fun requireTable(name: String): String =
        SqlIdentifiers.requireValidTable(name, PipelineErrorCodes.Node.STAGING_FAILED)

    private suspend fun countRows(quotedTable: String): Long =
        ctx.staging.withQuery("SELECT COUNT(*) FROM $quotedTable") { rs ->
            if (rs.next()) rs.getLong(1) else 0L
        }

    /**
     * The per-pipeline clamped budget re-check the SQL paths apply after every staged write
     * (the staging instance enforces only the operator global it was constructed with).
     */
    private suspend fun reCheckStagingBudget() {
        val usedBytes = ctx.staging.stats().memoryUsedBytes
        if (usedBytes / BYTES_PER_KB > ctx.stagingMaxMemoryMb * KB_PER_MB) {
            val overflow = StagingMemoryLimitException(memoryUsedBytes = usedBytes, maxMemoryMb = ctx.stagingMaxMemoryMb)
            throw NodeFailedSignal(ErrorCodeMapper.map(overflow, NodePhase.STAGE, node.id), overflow)
        }
    }

    /** §5.5's cleanup half: drop the COMPLETED tables on any refusal after a write began. */
    suspend fun dropWritten() {
        if (written.isEmpty()) return
        withContext(NonCancellable) {
            written.forEach { table ->
                runCatching { ctx.staging.execute("DROP TABLE IF EXISTS ${quoteTable(table)}") }
            }
        }
    }

    /** The §4.3 value cap on a gated value output (an object is capped by the gate itself). */
    private fun valueSizeCheck(gated: Any?) {
        val bytes = CanonicalJson.write(gated).toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > support.maxValueBytes) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Transform.VALUE_TOO_LARGE,
                message = "TRANSFORM node '${node.id}': the value output is $bytes bytes (cap ${support.maxValueBytes}).",
                details = mapOf("node" to node.id, "bytes" to bytes, "max_value_bytes" to support.maxValueBytes),
            )
        }
    }

    private fun List<ContractColumn>.toColumnSchemas(): List<ColumnSchema> =
        map { ColumnSchema(it.name, it.type, it.precision, it.scale, nullable = it.nullable) }

    private companion object {
        const val BYTES_PER_KB = 1024L
        const val KB_PER_MB = 1024L
        const val STRICT_REASON_SAMPLE = 10
    }
}
