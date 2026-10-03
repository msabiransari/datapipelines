package co.datapipelines.application.dashboards

import co.datapipelines.executor.ResultBytes
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.JsonEncoder
import co.datapipelines.visualization.DashboardErrorCodes
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.InputContract
import co.datapipelines.visualization.RefreshStatus
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The server runtime of ONE dashboard refresh (the implementation spec's §9, §18): fan out the plan's distinct
 * executions, hold each result in a bounded collector, run each target's transform once all its inputs have
 * arrived, stream the frames, and END — always — by writing the refresh's terminal row.
 *
 * ## The shape
 * - **Fan-out (§9 step 4).** One execution per [Invocation], started concurrently through the [SourceStarter] seam with
 *   one of the job's reserved slots. `onRecorded` links the execution to the refresh BEFORE the first event and only
 *   then announces `source_started`, so "linked before its first event" holds by construction.
 * - **Dependency scheduling (§9 step 5).** A target waits for ALL its inputs' invocations. A failed source fails every
 *   target reading it (D37) — a multi-input transform never runs on an empty stand-in.
 * - **Transform (§9 step 6).** Once, on the blocking dispatcher.
 * - **The deadlines.** The refresh's own, then each target's, then each invocation's (the latest of its consumers');
 *   the earliest that applies wins, and a source's own executor limits still hold.
 * - **Abort.** Polled at every stage boundary; a refresh that sees its flag cancels its work, every not-yet-started
 *   source is skipped, and the last frame is `refresh_completed`.
 *
 * ## The end is not on the normal path
 * The terminal bookkeeping — the row's status and summary, the audit row, the slots, the last frame — runs in
 * `withContext(NonCancellable)` at the single point that OWNS the refresh. `withContext` on a cancelled job skips its
 * block silently, so a row written on the normal path stays `RUNNING` forever when the client goes, the deadline
 * fires or the process stops (the coroutine-cancellation lesson, MISTAKES.md). "My deadline" is told from "my
 * ancestor's" by [currentCoroutineContext]'s liveness, never by the exception's type: a parent's timeout reaches a
 * nested child unwrapped, as the same class.
 *
 * ## What it never does
 * It reads no repository and knows no transport: the caller resolved every pin, and the frames go to a sink that may
 * be listening or not. A failure is a frame with a CODE; a driver's or datasource's message never reaches the stream.
 */
class RefreshEngine(
    private val starter: SourceStarter,
    private val transformer: DashboardTransformer,
    private val config: DashboardRuntimeConfig,
    /** Where blocking work (the transform, the ledger, the audit) runs — the executor's dispatcher in production. */
    private val blocking: CoroutineDispatcher,
    /** How often an abort request is looked for. Cheap by contract: the signal caches any remote read itself. */
    private val abortPollMillis: Long = ABORT_POLL_MILLIS,
) {
    private val mapper = ObjectMapper()

    /**
     * Runs [job] to its end and returns how it ended. Cancellation of the caller (the process stopping) still ends the
     * row — the terminal work is non-cancellable — and is rethrown afterwards.
     */
    @Suppress("TooGenericExceptionCaught") // a bug in the work must still END the refresh: the row is closed FAILED, not left RUNNING
    suspend fun run(
        job: RefreshJob,
        ports: RefreshPorts,
    ): RefreshResult {
        val run = Run(job, ports)
        run.announce()
        var interrupted: CancellationException? = null
        // The abort check at job start (#356): a flag recorded while the refresh was still starting — before the row
        // existed, answered 202 by the runtime — ends the refresh here, before any source is launched, through the
        // one terminal path below. The same read the watcher would make a poll later, made before the fan-out.
        // The read sits INSIDE the try: a fault in it is a bug in the work, ended FAILED below, never a row left
        // RUNNING with no terminal frame (356 merge follow-up).
        val ending =
            try {
                if (ports.abort.requested(job.refreshId)) {
                    Ending.ABORTED
                } else {
                    withTimeout(Duration.ofSeconds(job.deadlineSeconds.toLong()).toMillis()) { run.work() }
                    // The ending is ABORTED only when the abort CANCELLED work (#370): the flag's
                    // timing alone is not the fact — the watcher may set it (and cancel a body that
                    // has already finished) in the window between the last data frame and the work's
                    // return. A refresh whose every target delivered Ok/NoData is DONE — the row and
                    // the last frame agree with the chips the client already rendered; a body the
                    // cancel caught leaves targets unrecorded, and a refresh whose targets failed was
                    // "not fully succeeded" — both stay ABORTED.
                    if (run.abortRequested.get() && !run.bodyCompletedEveryTarget()) Ending.ABORTED else Ending.DONE
                }
            } catch (e: TimeoutCancellationException) {
                // A deadline that fired BELOW this line is mine. One that fired above it reaches here as the same class.
                if (currentCoroutineContext().isActive) {
                    Ending.TIMED_OUT
                } else {
                    interrupted = e
                    Ending.ABORTED
                }
            } catch (e: CancellationException) {
                interrupted = e
                Ending.ABORTED
            } catch (e: RuntimeException) {
                // A bug in the work must still END the refresh: the row is closed FAILED, the class (never the message) is logged.
                LOG.error("event=dashboard.refresh_work_failed refresh_id={} error={}", job.refreshId, e.javaClass.simpleName)
                Ending.FAILED
            }
        val result = withContext(NonCancellable) { run.finish(ending) }
        interrupted?.let { throw it }
        return result
    }

    private enum class Ending { DONE, ABORTED, TIMED_OUT, FAILED }

    /** The single refresh's mutable state — one instance per [run], so the engine itself stays stateless. */
    private inner class Run(
        val job: RefreshJob,
        val ports: RefreshPorts,
    ) {
        val abortRequested = AtomicBoolean(false)
        private val budget = RefreshByteBudget(config.maxBytesPerRefresh)
        private val outcomes = ConcurrentHashMap<String, TargetOutcome>()
        private val sources = ConcurrentHashMap<String, Map<String, Any?>>()
        private val refreshCapReached = AtomicBoolean(false)
        private val startedNanos = System.nanoTime()

        fun announce() {
            val shared = job.plan.invocations.associate { inv -> inv.id to inv.shared }
            ports.events.emit(
                RefreshEvent.Started(
                    job.refreshId,
                    job.plan.targets,
                    job.plan.invocations.flatMap { inv -> inv.sources.map { SourceRef(it, shared.getValue(inv.id)) } },
                    job.startedAt.plusSeconds(job.deadlineSeconds.toLong()).toString(),
                ),
            )
            job.plan.targets.forEach { ports.events.emit(RefreshEvent.VisualizationStatus(job.refreshId, it, IN_PROGRESS)) }
        }

        /** The refresh's work: invocations and targets, with the abort watcher beside them. */
        suspend fun work() =
            coroutineScope {
                val body =
                    launch {
                        coroutineScope {
                            val results = job.plan.invocations.associate { inv -> inv.id to async { invocation(inv) } }
                            job.plan.targets.forEach { name -> launch { target(job.targets.getValue(name), results) } }
                        }
                    }
                val watcher =
                    launch {
                        while (true) {
                            delay(abortPollMillis)
                            if (ports.abort.requested(job.refreshId)) {
                                abortRequested.set(true)
                                body.cancel()
                                return@launch
                            }
                        }
                    }
                body.join()
                watcher.cancel()
                noticeLateAbort()
            }

        /**
         * The abort route cancels the running executions AND raises the flag, and an execution ended that way can finish
         * the work before the watcher's next poll — every target failed, nobody told the engine why, the row closed
         * FAILED for a refresh its viewer aborted. One last look at the flag records the request here, after the work
         * joined; WHAT the request means is the ending's rule alone (`run`'s check at the DONE/ABORTED fork): an abort
         * that arrives after the last visualization completed changes nothing (#370), while a refresh the abort
         * actually interrupted — targets unrecorded, or recorded failed — ends ABORTED.
         *
         * What the request means for a TARGET is `record`'s and `finish`'s rule (#435): the target the refresh's own
         * abort cancelled reports `abort`, on whichever side of this race the flag is read.
         */
        private fun noticeLateAbort() {
            if (abortRequested.get()) return
            if (ports.abort.requested(job.refreshId)) abortRequested.set(true)
        }

        /**
         * Whether every planned target completed on its own — [TargetOutcome.Ok] or NoData: the abort then cancelled
         * nothing, and a flag raised after that point changes nothing (#370). A target the cancel caught is ABSENT
         * here, and a failed one is an [TargetOutcome.Error] — either breaks the all-completed invariant.
         */
        fun bodyCompletedEveryTarget(): Boolean =
            job.plan.targets.all { outcomes[it]?.let { o -> o is TargetOutcome.Ok || o == TargetOutcome.NoData } == true }

        /** The outcome an execution that ended ABORTED leaves a target: an error at the abort stage. */
        private fun TargetOutcome.cancelledByAbort(): Boolean =
            this is TargetOutcome.Error && stage == ABORT_STAGE && code == PipelineErrorCodes.Execution.ABORTED

        // ---- one execution ------------------------------------------------------------------------------

        private suspend fun invocation(inv: Invocation): SourceResult {
            val spec = job.invocations.getValue(inv.id)
            val collector = BoundedCollector(config.maxBytesPerSource, budget)
            val slot = requireNotNull(job.reservation) { "an invocation needs a reserved slot" }.next()
            var recorded: UUID? = null
            val launch =
                SourceLaunch(
                    job.refreshId,
                    inv.sources.first(),
                    job.workspaceId,
                    job.userId,
                    job.executedByKeyKind,
                    spec.pipelineId,
                    spec.pipelineVersion,
                    spec.pipeline,
                    spec.parameters,
                    collector,
                    slot,
                    allowDraftDependencies = job.allowDraftDependencies,
                )
            val outcome =
                try {
                    within(invocationMillis(inv)) {
                        starter.run(launch) { id ->
                            recorded = id
                            announceSource(inv, id)
                        }
                    }
                } catch (e: CancellationException) {
                    // Cancelled from above (abort, the refresh's deadline, shutdown): stop the execution through the
                    // executor's own path too, so a statement running on the database is interrupted, not orphaned.
                    recorded?.let(ports.canceller::cancel)
                    throw e
                } finally {
                    slot.close() // the execution releases its own; a launch refused before it never did
                }
            val result = settle(inv, collector, outcome, recorded)
            if (outcome == null) recorded?.let(ports.canceller::cancel)
            return result
        }

        private fun announceSource(
            inv: Invocation,
            executionId: UUID,
        ) {
            inv.sources.forEach { source ->
                runCatching { ports.ledger.link(job.refreshId, source, executionId, inv.shared) }
                    .onFailure {
                        LOG.error("event=dashboard.refresh_link_failed refresh_id={} error={}", job.refreshId, it.javaClass.simpleName)
                    }
            }
            inv.sources.forEach { ports.events.emit(RefreshEvent.SourceStarted(job.refreshId, it, executionId)) }
        }

        private fun settle(
            inv: Invocation,
            collector: BoundedCollector,
            outcome: SourceOutcome?,
            recorded: UUID?,
        ): SourceResult {
            val result =
                when {
                    outcome == null -> {
                        SourceResult.Failed(TIMEOUT_STAGE, PipelineErrorCodes.Execution.TIMEOUT)
                    }

                    outcome is SourceOutcome.Succeeded -> {
                        SourceResult.Succeeded(collector.table ?: CollectedTable(emptyList(), emptyList(), 0))
                    }

                    collector.overflow != null -> {
                        overflowed(collector)
                    }

                    outcome is SourceOutcome.Aborted -> {
                        SourceResult.Failed(ABORT_STAGE, PipelineErrorCodes.Execution.ABORTED)
                    }

                    else -> {
                        SourceResult.Failed(SOURCE_STAGE, (outcome as SourceOutcome.Failed).code)
                    }
                }
            val executionId = outcome?.executionId ?: recorded
            inv.sources.forEach { source ->
                when (result) {
                    is SourceResult.Succeeded -> {
                        sources[source] = mapOf("outcome" to "ok", "rows" to result.table.rows.size, "bytes" to result.table.bytes)
                        executionId?.let {
                            val table = result.table
                            ports.events.emit(RefreshEvent.SourceCompleted(job.refreshId, source, it, table.rows.size, table.bytes))
                        }
                    }

                    is SourceResult.Failed -> {
                        sources[source] = mapOf("outcome" to "error", "stage" to result.stage, "code" to result.code)
                        ports.events.emit(RefreshEvent.SourceFailed(job.refreshId, source, executionId, result.code, SOURCE_FAILED_MESSAGE))
                    }
                }
            }
            return result
        }

        private fun overflowed(collector: BoundedCollector): SourceResult.Failed {
            if (collector.overflow == Overflow.REFRESH) refreshCapReached.set(true)
            return SourceResult.Failed(BUDGET_STAGE, DashboardErrorCodes.REFRESH_RESULT_TOO_LARGE)
        }

        // ---- one target ---------------------------------------------------------------------------------

        private suspend fun target(
            spec: TargetSpec,
            results: Map<String, Deferred<SourceResult>>,
        ) {
            val outcome =
                within(targetMillis(spec)) { produce(spec, results) }
                    ?: TargetOutcome.Error(TIMEOUT_STAGE, PipelineErrorCodes.Execution.TIMEOUT)
            record(spec.name, outcome)
        }

        @Suppress("ReturnCount") // each refusal is one stage of the target's pipeline: source, contract, transform
        private suspend fun produce(
            spec: TargetSpec,
            results: Map<String, Deferred<SourceResult>>,
        ): TargetOutcome {
            val tables = LinkedHashMap<String, CollectedTable>()
            for ((input, source) in spec.sourceOfInput) {
                when (val result = results.getValue(job.plan.invocationOfSource.getValue(source)).await()) {
                    is SourceResult.Failed -> return TargetOutcome.Error(result.stage, result.code)
                    is SourceResult.Succeeded -> tables[input] = result.table
                }
            }
            for ((input, table) in tables) {
                val missing = contractMismatch(table, spec.body.inputs.getValue(input))
                if (missing != null) return TargetOutcome.Error(SOURCE_STAGE, DashboardErrorCodes.INPUT_CONTRACT_MISMATCH, missing)
            }
            val rows =
                when (val produced = rowsOf(spec, tables)) {
                    is Produced.Refused -> return produced.outcome
                    is Produced.Rows -> produced.rows
                }
            if (rows.isEmpty()) return TargetOutcome.NoData
            val bindings = spec.body.bindings.mapValues { (_, column) -> rows.map { it[column] } }
            val bytes = ResultBytes.utf8Length(mapper.writeValueAsString(bindings))
            ports.events.emit(RefreshEvent.VisualizationData(job.refreshId, spec.name, bindings, rows.size, bytes))
            return TargetOutcome.Ok(rows.size, bytes)
        }

        private suspend fun rowsOf(
            spec: TargetSpec,
            tables: Map<String, CollectedTable>,
        ): Produced =
            try {
                val transform = spec.body.transform
                if (transform == null) {
                    // No transform: the one input's columns bind directly. Zero inputs cannot be bound (the validator
                    // requires every input mapped); a body that slipped through fails the target, it does not crash the run.
                    val only = tables.values.singleOrNull()
                    if (only == null) {
                        Produced.Refused(TargetOutcome.Error(SOURCE_STAGE, DashboardErrorCodes.INPUT_UNBOUND))
                    } else {
                        Produced.Rows(maps(only))
                    }
                } else {
                    val fed = transform.inputs.mapValues { (_, visualizationInput) -> maps(tables.getValue(visualizationInput)) }
                    val started = job.startedAt
                    val out =
                        withContext(blocking) {
                            if (job.allowDraftDependencies) {
                                transformer.transformDraft(job.workspaceId, transform.template, fed, started)
                            } else {
                                transformer.transform(job.workspaceId, transform.template, fed, started)
                            }
                        }
                    when (out) {
                        is TransformOutcome.Rows -> Produced.Rows(out.rows)
                        is TransformOutcome.Refused -> Produced.Refused(TargetOutcome.Error(TRANSFORM_STAGE, out.code))
                    }
                }
            } catch (e: DatapipelinesException) {
                Produced.Refused(TargetOutcome.Error(SOURCE_STAGE, e.code))
            }

        private fun maps(table: CollectedTable): List<Map<String, Any?>> =
            table.rows.map { row ->
                LinkedHashMap<String, Any?>(table.columns.size * 2).also { out ->
                    table.columns.forEachIndexed { index, column -> out[column.name] = JsonEncoder.encode(row[index], column) }
                }
            }

        /** The first contract column the table lacks or holds under another type, by name — or null. */
        private fun contractMismatch(
            table: CollectedTable,
            contract: InputContract,
        ): String? {
            val have = table.columns.associateBy { it.name }
            return contract.columns.firstOrNull { have[it.name]?.type != it.type }?.name
        }

        /**
         * Records [outcome] unless the target already has one — the first outcome wins, and the status frame goes out
         * at record time. One rule decides WHICH outcome is first (#435): an execution that ended aborted
         * ([cancelledByAbort]) while the refresh's OWN abort is requested was cancelled by that abort, so the target
         * reports `abort`, not the error the cancel's side effect would otherwise record — the outcome must not depend
         * on whether the execution's end or the watcher's flag read came first (#370's principle). The route raises the
         * flag before it cancels, so on the owning instance the flag is readable here by construction; an execution
         * aborted with no request (the executor's own abort) stays an error at the abort stage.
         */
        private fun record(
            name: String,
            reported: TargetOutcome,
        ) {
            val outcome = if (reported.cancelledByAbort() && ports.abort.requested(job.refreshId)) TargetOutcome.Aborted else reported
            if (outcomes.putIfAbsent(name, outcome) != null) return
            val status =
                when (outcome) {
                    is TargetOutcome.Ok -> {
                        null
                    }

                    // the data frame already carried it
                    TargetOutcome.NoData -> {
                        RefreshEvent.VisualizationStatus(job.refreshId, name, NO_DATA)
                    }

                    TargetOutcome.Aborted -> {
                        RefreshEvent.VisualizationStatus(job.refreshId, name, ABORT, ABORT_STAGE)
                    }

                    is TargetOutcome.Error -> {
                        RefreshEvent.VisualizationStatus(job.refreshId, name, ERROR, outcome.stage, outcome.code, messageOf(outcome))
                    }
                }
            status?.let(ports.events::emit)
        }

        private fun messageOf(error: TargetOutcome.Error): String =
            when (error.stage) {
                SOURCE_STAGE -> "A source this visualization reads failed" + (error.detail?.let { " (column '$it')" } ?: "") + "."
                TRANSFORM_STAGE -> "The visualization's transform refused its input."
                BUDGET_STAGE -> "The result was larger than this dashboard may hold."
                TIMEOUT_STAGE -> "The visualization did not finish in time."
                else -> "The visualization could not be produced."
            }

        // ---- the end ------------------------------------------------------------------------------------

        /** Terminal bookkeeping — runs under `NonCancellable`; every step is isolated so one failure cannot skip the rest. */
        suspend fun finish(ending: Ending): RefreshResult {
            // The cross-instance half of record's rule (#435): the flag was not yet readable when a cancelled execution's
            // target recorded, so it stands as the abort-stage error — and the refresh now ends ABORTED, whose terminal
            // frame is the truth. Only that ending rewrites, and only the cancelled-by-abort error: a delivered outcome
            // is never touched, and a FAILED/DONE/TIMED_OUT refresh reports the error it recorded.
            if (ending == Ending.ABORTED) outcomes.replaceAll { _, o -> if (o.cancelledByAbort()) TargetOutcome.Aborted else o }
            job.plan.targets.forEach { name ->
                record(
                    name,
                    when (ending) {
                        Ending.ABORTED -> TargetOutcome.Aborted

                        Ending.TIMED_OUT -> TargetOutcome.Error(TIMEOUT_STAGE, PipelineErrorCodes.Execution.TIMEOUT)

                        // DONE leaves none open; FAILED means the work itself broke — the outcome is unknown, as after a crash.
                        Ending.DONE, Ending.FAILED -> TargetOutcome.Error(SOURCE_STAGE, PipelineErrorCodes.Execution.INSTANCE_LOST)
                    },
                )
            }
            val status = statusOf(ending)
            val result = RefreshResult(status, job.plan.targets.associateWith { outcomes.getValue(it) }, summary(status, ending))
            withContext(blocking) {
                guarded("finish") { ports.ledger.finish(job.refreshId, status, result.summary) }
                guarded("audit") { ports.audit.record(job, result) }
            }
            job.reservation?.close()
            ports.events.emit(RefreshEvent.Completed(job.refreshId, status.name, result.targets.mapValues { (_, o) -> outcomeMap(o) }))
            LOG.info("event=dashboard.refresh_finished refresh_id={} status={} targets={}", job.refreshId, status, result.targets.size)
            return result
        }

        private fun statusOf(ending: Ending): RefreshStatus {
            if (ending == Ending.ABORTED) return RefreshStatus.ABORTED
            if (ending == Ending.TIMED_OUT) return RefreshStatus.TIMED_OUT
            if (ending == Ending.FAILED) return RefreshStatus.FAILED
            val good = outcomes.values.count { it is TargetOutcome.Ok || it == TargetOutcome.NoData }
            return when {
                good == outcomes.size -> RefreshStatus.COMPLETED
                good > 0 || refreshCapReached.get() -> RefreshStatus.PARTIAL
                else -> RefreshStatus.FAILED
            }
        }

        private fun summary(
            status: RefreshStatus,
            ending: Ending,
        ): ObjectNode =
            mapper.createObjectNode().also { out ->
                out.put("status", status.name)
                out.put("duration_ms", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis())
                if (refreshCapReached.get()) out.put("budget_exceeded", true)
                if (ending == Ending.TIMED_OUT) out.put("reason", "deadline_passed")
                if (ending == Ending.FAILED) out.put("reason", "internal_error")
                val targets = out.putObject("targets")
                job.plan.targets.forEach {
                    targets.set<com.fasterxml.jackson.databind.JsonNode>(
                        it,
                        mapper.valueToTree(outcomeMap(outcomes.getValue(it))),
                    )
                }
                val sourceNode = out.putObject("sources")
                sources.toSortedMap().forEach { (name, facts) ->
                    sourceNode.set<com.fasterxml.jackson.databind.JsonNode>(name, mapper.valueToTree(facts))
                }
            }

        private fun outcomeMap(outcome: TargetOutcome): Map<String, Any?> =
            when (outcome) {
                is TargetOutcome.Error -> {
                    mapOf(
                        "outcome" to outcome.wire,
                        "stage" to outcome.stage,
                        "reason" to mapOf("code" to outcome.code),
                    )
                }

                is TargetOutcome.Ok -> {
                    mapOf("outcome" to outcome.wire, "rows" to outcome.rows, "bytes" to outcome.bytes)
                }

                else -> {
                    mapOf("outcome" to outcome.wire)
                }
            }

        @Suppress("TooGenericExceptionCaught") // the end must reach its last step whatever an earlier one threw
        private inline fun guarded(
            step: String,
            block: () -> Unit,
        ) {
            try {
                block()
            } catch (e: RuntimeException) {
                LOG.error("event=dashboard.refresh_end_failed refresh_id={} step={} error={}", job.refreshId, step, e.javaClass.simpleName)
            }
        }

        // ---- the deadlines ------------------------------------------------------------------------------

        /**
         * A target's own deadline in ms — null when it is not STRICTLY earlier than the refresh's. The refresh's timer is
         * then the only authority: a second timer at the same instant adds nothing but a tie the timer queue's order
         * would have to break, and the ending should be a fact of the rule rather than of that order.
         */
        private fun targetMillis(spec: TargetSpec): Long? = earlier(secondsOf(spec))

        private fun secondsOf(spec: TargetSpec): Int = minOf(spec.timeoutSeconds ?: job.deadlineSeconds, job.deadlineSeconds)

        /** An execution may run as long as its LATEST consumer would wait — the earliest deadline that still matters. */
        private fun invocationMillis(inv: Invocation): Long? = earlier(inv.consumers.maxOf { secondsOf(job.targets.getValue(it)) })

        private fun earlier(seconds: Int): Long? =
            if (seconds <
                job.deadlineSeconds
            ) {
                Duration.ofSeconds(seconds.toLong()).toMillis()
            } else {
                null
            }

        /** [block] under [millis], or unbounded (the refresh's deadline above it applies) when null; null on expiry. */
        private suspend fun <T> within(
            millis: Long?,
            block: suspend () -> T,
        ): T? = if (millis == null) block() else withTimeoutOrNull(millis) { block() }
    }

    private sealed interface Produced {
        data class Rows(
            val rows: List<Map<String, Any?>>,
        ) : Produced

        data class Refused(
            val outcome: TargetOutcome,
        ) : Produced
    }

    private sealed interface SourceResult {
        data class Succeeded(
            val table: CollectedTable,
        ) : SourceResult

        data class Failed(
            val stage: String,
            val code: String,
        ) : SourceResult
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(RefreshEngine::class.java)
        const val ABORT_POLL_MILLIS = 100L
        const val IN_PROGRESS = "in-progress"
        const val ERROR = "error"
        const val ABORT = "abort"
        const val NO_DATA = "no-data"
        const val SOURCE_STAGE = "source"
        const val TRANSFORM_STAGE = "transform"
        const val BUDGET_STAGE = "budget"
        const val TIMEOUT_STAGE = "timeout"
        const val ABORT_STAGE = "abort"
        const val SOURCE_FAILED_MESSAGE = "The source did not produce a result."
    }
}
