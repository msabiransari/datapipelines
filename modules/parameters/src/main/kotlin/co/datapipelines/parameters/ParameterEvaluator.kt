package co.datapipelines.parameters

import co.datapipelines.dag.Dag
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import co.datapipelines.typesystem.ParameterCoercion
import co.datapipelines.typesystem.ParameterValueOutcome
import co.datapipelines.typesystem.ParameterValueRule
import co.datapipelines.typesystem.ParameterValueValidator
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The parameter engine's runtime (record §5) — one evaluate of one stored set version: the client's
 * selections in, the whole set re-rendered out ([EvaluateResponse], §5.3). Stateless between calls:
 * the server remembers nothing, and the client sends every parameter every time (P27).
 *
 * ## §5.2, in order
 *
 * 1. **Every supplied selection** passes the SHARED validator (P28) against its declaration — an
 *    `INPUT` with its constraints, a `SELECT` its type, precision and scale. A `MULTI` over
 *    `max-multi-bind-values` is `too_many_values` before the validator reads a member; a wire-form
 *    failure is `invalid_value_type`, a rule's `constraint_violation` (`details.reason`). A refused
 *    value is recorded on its parameter and treated as ABSENT for the cascade, so the rest of the
 *    form still answers. Absent, `null` and a `MULTI`'s `[]` are one signal — nothing chosen (P25).
 *    A key that names no parameter refuses the whole request (`unknown_parameter`, 400).
 * 2. **The graph** is the stored, validated definition's `Dag` ([ParameterSetGraph]) — never
 *    re-validated here.
 * 3. **One coroutine per parameter** (P16, `PipelineExecutor`'s pattern): every parameter is
 *    scheduled up front and each awaits its parents' completion, so a parent that is also a child is
 *    evaluated once, after its parents and before its children, and independent selectors run
 *    concurrently. Their statements run on the [SelectorPool] bulkhead (P31), whose admission is the
 *    one refusal it makes (`selectors_saturated`, inline). The whole cascade runs under
 *    `withTimeout(evaluate-timeout-seconds)` on the coroutines that AWAIT the workers — never on a
 *    worker — so the deadline answers `parameter.evaluate.timeout` (504) whatever a driver does.
 * 4. **Per parameter**, on its parents' EFFECTIVE values: `hidden` / `disabled` from the stored
 *    expressions (interaction flags only — they never change or drop a value, P5); the source
 *    (`constants` verbatim; a template rendered against `{parents} ∪ {<name>_count per MULTI parent}
 *    ∪ org ∪ platform` and run, its rows judged by [SelectorRows]; an `INPUT`'s one sourced row);
 *    then THE VALUE by the selection priority (P26) — see [single], [multi], [input].
 * 5. A datasource or statement failure is recorded on its parameter with the failing subsystem's own
 *    code, its options empty, its children evaluated against `NULL`: the response is always whole.
 *    A response over `max-evaluate-response-bytes` is `parameter.evaluate.response_too_large` (413) —
 *    options are never truncated.
 *
 * The consumer payload ([EvaluateResponse.values]) is every parameter's value as the priority chose
 * it, hidden and disabled included — exactly what the form shows.
 *
 * **One configuration, two owners.** This class applies the evaluate-level limits of [config] (the
 * deadline, `max-multi-bind-values`, the option caps, the response budget); the [SelectorRunner] behind
 * [selectors] applies the STATEMENT-level ones from its own (`max-binds-per-statement`, the clamped
 * statement timeout). The wiring builds both — and the one [SelectorPool] — from the same
 * `ParametersConfig`.
 *
 * **Every evaluation is recorded** (#376, the workspace spec R2): the [recorder] is a constructor collaborator, so the
 * durable history sees the REST route, the dashboard runtime, the MCP tool and the Parameter Sets page alike — each
 * names itself in the REQUIRED [EvaluationAttempt]. The record opens when the attempt is admitted (after the
 * whole-request key check), gets one row per statement attempt, and is finished exactly once in [evaluate]'s `finally`
 * — non-cancellably, BEFORE the observer's `Ended`, so a page that reads the history on its terminal frame finds the
 * record finished.
 */
class ParameterEvaluator(
    private val selectors: SelectorTasks,
    private val pool: SelectorPool,
    private val config: ParametersConfig = ParametersConfig(),
    /** The deployment's org tier — bindable by every selector without a dependency (P30), echoed as `org`. */
    private val org: OrgContext = OrgContext.DEFAULTS,
    /**
     * `current_date` / `current_timestamp` — read once per evaluate, the same instant for every selector — and the
     * history's stamps (admission, each statement's queued/started/ended, the terminal write).
     */
    private val clock: Clock = Clock.systemUTC(),
    /** The durable evaluation history (#376) — every caller records; [ParameterEvaluationRecorder.NONE] records nothing. */
    private val recorder: ParameterEvaluationRecorder = ParameterEvaluationRecorder.NONE,
) {
    /** The shared judge (P28) with the engine's limits — the `max_length` default and the regex budget (P34). */
    private val validator = ParameterValueValidator(config.valueLimits)

    private val parser = ExpressionParser(config)

    private val rows = SelectorRows(config, validator)

    /** [evaluate] for a blocking caller (a request thread). */
    fun evaluateBlocking(
        workspaceId: UUID,
        set: ParameterSetVersion,
        selections: Map<String, JsonNode?>,
        attempt: EvaluationAttempt,
    ): EvaluateResponse = runBlocking { evaluate(workspaceId, set, selections, attempt) }

    /**
     * Evaluates [set] (a stored version the caller resolved and may read — lane D's route and tool)
     * in [workspaceId] against [selections] (the request's `selections` object, keyed by parameter).
     *
     * [attempt] names the caller and its principal for the durable record (#376) — required, so no call site records
     * under a discriminator it did not choose; it sits before the optional [observation] so S2's default stays last.
     *
     * [observation] is the observed evaluation's port (the parameter-set workspace spec §4.3, #375): the
     * Parameter Sets page's stream passes one and receives [ParameterEvaluationEvent]s — `Started` first,
     * `Ended` exactly once and last, written from a `finally` under `NonCancellable` whatever ended the
     * evaluation (decision D2). Every ordinary caller passes nothing: no event is built, no task wrapped,
     * no pool callback handed over — the response is byte-for-byte what it was.
     *
     * @throws DatapipelinesException `parameter.evaluate.unknown_parameter` (400),
     *   `parameter.evaluate.timeout` (504), `parameter.evaluate.response_too_large` (413) — the three
     *   whole-request refusals; everything else is per parameter, in `state.errors`.
     */
    @Suppress("ThrowsCount") // every catch arm only records how the evaluation ended, then rethrows it unchanged
    suspend fun evaluate(
        workspaceId: UUID,
        set: ParameterSetVersion,
        selections: Map<String, JsonNode?>,
        attempt: EvaluationAttempt,
        observation: ParameterEvaluationObserver = ParameterEvaluationObserver.NONE,
    ): EvaluateResponse {
        require(set.record.workspaceId == workspaceId) { "the set is not this workspace's — the caller resolved it wrongly" }
        val events = if (observation === ParameterEvaluationObserver.NONE) null else ObservedEvaluation(observation)
        val record = RecordedEvaluation(recorder, attempt, set, clock)
        events?.emit(
            ParameterEvaluationEvent.Started(
                set.body.parameters.map { it.name },
                clock.instant().plusSeconds(config.evaluateTimeoutSeconds),
            ),
        )
        // The terminal bookkeeping (D2): what ended the evaluation, decided where it ended, written once below.
        var outcome = EvaluationOutcome.FAILED
        var code: String? = null
        var completed: EvaluateResponse? = null
        try {
            return evaluated(workspaceId, set, selections, events, record).also {
                outcome = EvaluationOutcome.COMPLETED
                completed = it
            }
        } catch (e: CancellationException) {
            // Our own deadline became `parameter.evaluate.timeout` inside [underDeadline]; a cancellation
            // reaching here with our caller's scope stopped is the CALLER's — the observed route's grace.
            if (!currentCoroutineContext().isActive) outcome = EvaluationOutcome.ABORTED
            throw e
        } catch (e: DatapipelinesException) {
            outcome = if (e.code == ParameterErrorCodes.EVALUATE_TIMEOUT) EvaluationOutcome.TIMEOUT else EvaluationOutcome.FAILED
            code = e.code
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // A defect, never an author's problem: the record says FAILED with no code; the class is the one fact kept.
            log.warn("event=parameter.evaluation_defect evaluation_id={} error={}", attempt.evaluationId, e.javaClass.simpleName)
            throw e
        } finally {
            // The record first (#376), then the observer's last frame: both written once, here, whatever ended it.
            withContext(NonCancellable) {
                record.end(outcome, code, completed)
                events?.emit(ParameterEvaluationEvent.Ended(outcome, code, completed))
            }
        }
    }

    private suspend fun evaluated(
        workspaceId: UUID,
        set: ParameterSetVersion,
        selections: Map<String, JsonNode?>,
        events: ObservedEvaluation?,
        record: RecordedEvaluation,
    ): EvaluateResponse {
        val body = set.body
        SelectionKeys.refuseUnknown(body, selections)
        // Admitted: a request refused above (an unknown key) records nothing — "refused at admission writes no row".
        record.open()
        val submissions = body.parameters.associate { it.name to judge(it, selections[it.name]) }
        val graph = ParameterSetGraph.of(body)
        val evaluation = Evaluation(workspaceId, set.record.name, body, graph, submissions, events, record)
        val states = underDeadline { evaluation.cascade() }
        val order = body.parameters.withIndex().associate { it.value.name to it.index }
        val response =
            EvaluateResponse(
                id = set.record.id,
                name = set.record.name,
                version = set.detail.version,
                org = OrgEcho(org.values[OrgContext.CURRENCY_SYMBOL] as String?, org.values[OrgContext.CURRENCY_NAME] as String?),
                parameters = body.parameters.map { EvaluatedParameter(it, dependents(graph, it.name, order), states.getValue(it.name)) },
            )
        refuseOversized(response)
        return response
    }

    // ---- step 1 --------------------------------------------------------------------------------------

    /** What step 1 made of one parameter's selection. */
    private sealed interface Submission {
        /** Nothing chosen — absent, `null`, or `[]` for a `MULTI` (P25). */
        data object Absent : Submission

        /** A valid value, canonical (a list for a `MULTI`). */
        data class Supplied(
            val value: Any,
        ) : Submission

        /** Refused — recorded on the parameter, and absent for the cascade. */
        data class Refused(
            val error: ParameterError,
        ) : Submission
    }

    private fun judge(
        parameter: ParameterDefinition,
        node: JsonNode?,
    ): Submission {
        if (node == null || node.isNull || node.isMissingNode) return Submission.Absent
        if (parameter.cardinality == ParameterCardinality.MULTI && node.isArray && node.size() > config.maxMultiBindValues) {
            return Submission.Refused(
                ParameterError(
                    ParameterErrorCodes.EVALUATE_TOO_MANY_VALUES,
                    "At most ${config.maxMultiBindValues} values may be selected (datapipelines.parameters.max-multi-bind-values).",
                    mapOf("parameter" to parameter.name.safeEcho(), "max" to config.maxMultiBindValues, "count" to node.size()),
                ),
            )
        }
        val declaration =
            parameter.declaration.copy(
                required = false,
                default = null,
                constraints = if (parameter.kind == ParameterKind.INPUT) parameter.constraints else null,
            )
        return when (val outcome = validator.validate(declaration, node)) {
            ParameterValueOutcome.Unsupplied -> {
                Submission.Absent
            }

            is ParameterValueOutcome.Accepted -> {
                Submission.Supplied(outcome.value)
            }

            is ParameterValueOutcome.Refused -> {
                val constraint = outcome.refusal.rule == ParameterValueRule.CONSTRAINT_VIOLATION
                Submission.Refused(
                    ParameterError(
                        if (constraint) {
                            ParameterErrorCodes.EVALUATE_CONSTRAINT_VIOLATION
                        } else {
                            ParameterErrorCodes.EVALUATE_INVALID_VALUE_TYPE
                        },
                        outcome.refusal.message,
                        buildMap {
                            put("parameter", parameter.name.safeEcho())
                            outcome.refusal.reason?.let { put("reason", it) }
                        },
                    ),
                )
            }
        }
    }

    // ---- steps 3–5 -------------------------------------------------------------------------------------

    /** One evaluate's cascade — the per-request state every parameter's coroutine reads. */
    private inner class Evaluation(
        private val workspaceId: UUID,
        private val setName: String,
        body: ParameterSetBody,
        private val graph: Dag<ParameterDefinition>,
        private val submissions: Map<String, Submission>,
        /** The observed evaluation's delivery, or null for every ordinary caller (no event is ever built then). */
        private val events: ObservedEvaluation?,
        /** The durable record (#376) — a no-op for an unrecorded evaluation. */
        private val record: RecordedEvaluation,
    ) {
        private val types: Map<String, LogicalType> = body.parameters.associate { it.name to it.type }

        /** The two stored expressions, parsed once per evaluate (they passed §7 at save). */
        private val expressions: Map<String, Pair<Expr?, Expr?>> =
            body.parameters.associate { it.name to (parse(it.hiddenExpression) to parse(it.disabledExpression)) }

        /** The org and platform tiers (record §7.2 at evaluate: the real date and instant; `execution_id` absent). */
        private val tiers: Map<String, Any?> = tiers()

        /** Each datasource read once, live and workspace-visible, for the whole evaluate (P31). */
        private val resolver = selectors.resolver(workspaceId)

        suspend fun cascade(): Map<String, ParameterState> =
            coroutineScope {
                val scheduled = LinkedHashMap<String, Deferred<ParameterState>>()
                // Topological order guarantees a parameter's parents are already scheduled.
                graph.topologicalOrder().forEach { name ->
                    val parameter = graph.node(name)
                    val parents = parameter.dependsOn.distinct().associateWith { scheduled.getValue(it) }
                    scheduled[name] =
                        async {
                            if (parents.isNotEmpty()) events?.emit(ParameterEvaluationEvent.ParameterWaiting(name, parents.keys.toList()))
                            // The parents FIRST: a child never starts before every parent completed.
                            val effective = parents.mapValues { (_, state) -> state.await().value }
                            evaluate(parameter, effective).also { state ->
                                record.parameterEnded(parameter, state)
                                events?.let { report(it, parameter, state) }
                            }
                        }
                }
                scheduled.mapValues { (_, state) -> state.await() }
            }

        private suspend fun evaluate(
            parameter: ParameterDefinition,
            parents: Map<String, Any?>,
        ): ParameterState {
            val (hiddenExpr, disabledExpr) = expressions.getValue(parameter.name)
            val flags =
                Flags(ExpressionEvaluator.evaluate(hiddenExpr, parents, types), ExpressionEvaluator.evaluate(disabledExpr, parents, types))
            val submission = submissions.getValue(parameter.name)
            val errors = mutableListOf<ParameterError>()
            if (submission is Submission.Refused) errors += submission.error
            return when (parameter.kind) {
                ParameterKind.SELECT -> {
                    val options = options(parameter, parents, errors)
                    val resolved =
                        if (parameter.cardinality == ParameterCardinality.MULTI) {
                            multi(parameter, options, submission)
                        } else {
                            single(parameter, options, submission)
                        }
                    if (resolved.value == null) requiredMissing(parameter, submission, NO_OPTIONS)?.let { errors += it }
                    resolved.state(flags, options, errors)
                }

                ParameterKind.INPUT -> {
                    val resolved = input(parameter, sourced(parameter, parents, errors), submission)
                    if (resolved.value == null) {
                        val reason = if (parameter.source?.kind == SelectorSourceKind.TEMPLATE) NO_ROW else NO_DEFAULT
                        requiredMissing(parameter, submission, reason)?.let { errors += it }
                    }
                    resolved.state(flags, null, errors)
                }
            }
        }

        /** A `SELECT`'s options: its constants, or its selector's rows judged — empty (and an error) when it failed. */
        private suspend fun options(
            parameter: ParameterDefinition,
            parents: Map<String, Any?>,
            errors: MutableList<ParameterError>,
        ): List<EvaluatedOption> {
            val source = checkNotNull(parameter.source) { "a stored SELECT always has a source" }
            source.constants?.let { constants ->
                return constants.map { EvaluatedOption(coerce(parameter.type, it.value), it.displayValue, it.isDefault) }
            }
            return when (val run = run(parameter, parents, config.maxOptionsPerSelector + 1)) {
                is SelectorRun.Rows -> {
                    when (val checked = rows.options(parameter, run)) {
                        is SelectorRows.Options.Accepted -> checked.options
                        is SelectorRows.Options.Refused -> emptyList<EvaluatedOption>().also { errors += checked.error }
                    }
                }

                else -> {
                    emptyList<EvaluatedOption>().also { errors += failure(parameter, run) }
                }
            }
        }

        /** An `INPUT`'s sourced value (§6.2a) — null without a source, on a failure, or on no row. */
        private suspend fun sourced(
            parameter: ParameterDefinition,
            parents: Map<String, Any?>,
            errors: MutableList<ParameterError>,
        ): Any? {
            if (parameter.source?.kind != SelectorSourceKind.TEMPLATE) return null
            return when (val run = run(parameter, parents, SelectorProbe.SAVE_TIME_MAX_ROWS)) {
                is SelectorRun.Rows -> {
                    when (val row = rows.sourced(parameter, run)) {
                        is SelectorRows.Sourced.Value -> row.value
                        SelectorRows.Sourced.None -> null
                        is SelectorRows.Sourced.Refused -> null.also { errors += row.error }
                    }
                }

                else -> {
                    null.also { errors += failure(parameter, run) }
                }
            }
        }

        /** One template-backed source through the bulkhead: its render context and binds, then the pool. */
        private suspend fun run(
            parameter: ParameterDefinition,
            parents: Map<String, Any?>,
            maxRows: Int,
        ): SelectorRun? {
            val source = checkNotNull(parameter.source)
            val template = checkNotNull(source.template)
            val datasource = checkNotNull(source.datasource)
            val context = LinkedHashMap(tiers)
            val binds = LinkedHashMap(tiers)
            // Mirrors the save-time dry run's context (SelectorDryRun.contextFor) with the real values: a
            // MULTI parent's list never reaches the template, its size does (P29); a parent shadows a tier key.
            parameter.dependsOn.distinct().forEach { dependency ->
                val value = parents[dependency]
                if (graph.node(dependency).cardinality == ParameterCardinality.MULTI) {
                    val list = (value as? List<*>)?.toList().orEmpty()
                    context["$dependency$COUNT_SUFFIX"] = list.size
                    binds["$dependency$COUNT_SUFFIX"] = list.size
                    binds[dependency] = list
                } else {
                    context[dependency] = value
                    binds[dependency] = value
                }
            }
            val label = SelectorLabel(setName, parameter.name, datasource)
            // The statement attempt's row (#376) — null for an unrecorded evaluation, which then hands the pool the bare task.
            val query = record.queued(parameter.name, datasource, template)
            val produced = selectors.task(SelectorRequest(workspaceId, template, datasource, context, binds, maxRows), resolver)
            val task = query?.stamping(produced) ?: produced
            val admission =
                try {
                    if (events == null) {
                        pool.run(label, task)
                    } else {
                        pool.run(label, observed(task, parameter.name, datasource, template, events)) {
                            events.emit(ParameterEvaluationEvent.ParameterAdmitted(parameter.name))
                        }
                    }
                } catch (e: CancellationException) {
                    // Abandoned (the deadline, an abort): the terminal write closes this attempt with the evaluation's ending.
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    // The task itself threw (a defect): its attempt is FAILED, and the evaluation fails with it.
                    query?.let { record.defect(it) }
                    throw e
                }
            query?.let { record.ended(it, admission) }
            return when (admission) {
                is SelectorAdmission.Completed -> admission.run
                SelectorAdmission.Saturated -> null
            }
        }

        /**
         * The observed task: `parameter_running` at the START of [SelectorTask.run] on the worker thread — render
         * and run began there (the statement reaches the driver inside it). The frames before it are a few hundred
         * bytes per parameter, so a reader that stopped reading cannot fill the socket and stall the worker here.
         */
        private fun observed(
            task: SelectorTask,
            parameter: String,
            datasource: String,
            template: TemplateRef,
            events: ObservedEvaluation,
        ): SelectorTask =
            object : SelectorTask {
                override fun run(): SelectorRun {
                    events.emit(ParameterEvaluationEvent.ParameterRunning(parameter, datasource, template))
                    return task.run()
                }

                override fun abandon() = task.abandon()
            }

        /**
         * The parameter's ONE terminal event (spec §4.2's coverage rule — hidden, disabled, constants and inputs
         * included): `ParameterFailed` with the first error's code when it carries one, else `ParameterResolved`.
         * `rows` is the option count only where a query produced the options (a template `SELECT`).
         */
        private fun report(
            events: ObservedEvaluation,
            parameter: ParameterDefinition,
            state: ParameterState,
        ) {
            val error = state.errors.firstOrNull()
            val event =
                if (error != null) {
                    ParameterEvaluationEvent.ParameterFailed(
                        parameter.name,
                        error.code,
                        (error.details["reason"] as? String)?.safeEcho(MAX_DETAIL_CHARS),
                    )
                } else {
                    val queried = parameter.kind == ParameterKind.SELECT && parameter.source?.kind == SelectorSourceKind.TEMPLATE
                    ParameterEvaluationEvent.ParameterResolved(
                        parameter.name,
                        state.origin.wire,
                        state.reset,
                        state.options?.size?.takeIf { queried },
                    )
                }
            events.emit(event)
        }

        /** A run that did not produce rows, as the parameter's error — the owning subsystem's code; null is saturation. */
        private fun failure(
            parameter: ParameterDefinition,
            run: SelectorRun?,
        ): ParameterError {
            val datasource =
                parameter.source
                    ?.datasource
                    .orEmpty()
                    .safeEcho()
            val base = mapOf("parameter" to parameter.name.safeEcho(), "datasource" to datasource)
            return when (run) {
                null -> {
                    ParameterError(
                        ParameterErrorCodes.EVALUATE_SELECTORS_SATURATED,
                        "The selector queue is full (datapipelines.parameters.max-waiting-selector-queries); submit again shortly.",
                        base,
                    )
                }

                is SelectorRun.Unreachable -> {
                    ParameterError(
                        PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
                        "Datasource '$datasource' could not be reached: ${run.detail.safeEcho(MAX_DETAIL_CHARS)}",
                        base,
                    )
                }

                is SelectorRun.Failed -> {
                    ParameterError(run.code, run.detail.safeEcho(MAX_DETAIL_CHARS), base + run.details)
                }

                is SelectorRun.Rows -> {
                    error("rows are not a failure")
                }
            }
        }

        private fun parse(node: JsonNode?): Expr? {
            if (node == null || node.isNull) return null
            return when (val parsed = parser.parse(node)) {
                is ExpressionParse.Parsed -> parsed.expr
                is ExpressionParse.Refused -> error("a stored expression no longer parses — the body did not pass §7 at save")
            }
        }
    }

    // ---- the selection priority (P26) -------------------------------------------------------------

    /** The two interaction flags (P5): they govern a control, never its value. */
    private data class Flags(
        val hidden: Boolean,
        val disabled: Boolean,
    )

    /** What the priority chose for one parameter. */
    private data class Resolved(
        val value: Any?,
        val origin: ValueOrigin,
        val computedDefault: Any?,
        val reset: Boolean,
    ) {
        fun state(
            flags: Flags,
            options: List<EvaluatedOption>?,
            errors: List<ParameterError>,
        ) = ParameterState(value, origin, computedDefault, reset, flags.hidden, flags.disabled, options, errors.toList())
    }

    /**
     * `SELECT` / `SINGLE` (§5.2(c)): **1** the submitted value if it is among the options (`client`);
     * else **2** the configured default if among them (`default_value`, else the `is_default` option;
     * `default`); else **3** the first option (`first`). A submitted value that fits none walks to 2/3
     * with `reset: true` — never an error (P13). No options at all: `null`, `none`.
     */
    private fun single(
        parameter: ParameterDefinition,
        options: List<EvaluatedOption>,
        submission: Submission,
    ): Resolved {
        if (options.isEmpty()) return Resolved(null, ValueOrigin.NONE, null, reset = submission is Submission.Supplied)
        val byKey = options.associateBy { SelectorRows.canonicalKey(it.value) }
        val configured = declaredDefault(parameter)?.let { coerce(parameter.type, it) } ?: options.firstOrNull { it.isDefault }?.value
        val preferred = configured?.let { byKey[SelectorRows.canonicalKey(it)]?.value }
        val computed = preferred ?: options.first().value
        val computedOrigin = if (preferred != null) ValueOrigin.DEFAULT else ValueOrigin.FIRST
        if (submission is Submission.Supplied) {
            val chosen = byKey[SelectorRows.canonicalKey(submission.value)]?.value
            return if (chosen != null) {
                Resolved(chosen, ValueOrigin.CLIENT, computed, reset = false)
            } else {
                Resolved(computed, computedOrigin, computed, reset = true)
            }
        }
        return Resolved(computed, computedOrigin, computed, reset = false)
    }

    /**
     * `SELECT` / `MULTI` (§5.2(c)): **1** the submitted members that are among the options, in the
     * client's order (all of them ⇒ `client`; some dropped ⇒ the survivors, `reset: true`); none
     * surviving, or absent ⇒ **2** the configured default members among the options (`default_value`'s,
     * else the `is_default` option); else **3** the first option as the only member.
     */
    private fun multi(
        parameter: ParameterDefinition,
        options: List<EvaluatedOption>,
        submission: Submission,
    ): Resolved {
        if (options.isEmpty()) return Resolved(null, ValueOrigin.NONE, null, reset = submission is Submission.Supplied)
        val byKey = options.associateBy { SelectorRows.canonicalKey(it.value) }
        val configured =
            declaredDefault(parameter)?.let { default -> default.map { coerce(parameter.type, it) } }
                ?: listOfNotNull(options.firstOrNull { it.isDefault }?.value)
        val preferred = configured.mapNotNull { byKey[SelectorRows.canonicalKey(it)]?.value }.distinctBy(SelectorRows::canonicalKey)
        val computed = preferred.ifEmpty { listOf(options.first().value) }
        val computedOrigin = if (preferred.isNotEmpty()) ValueOrigin.DEFAULT else ValueOrigin.FIRST
        if (submission is Submission.Supplied) {
            val members = submission.value as List<*>
            val survivors = members.mapNotNull { member -> member?.let { byKey[SelectorRows.canonicalKey(it)]?.value } }
            return if (survivors.isNotEmpty()) {
                Resolved(survivors, ValueOrigin.CLIENT, computed, reset = survivors.size < members.size)
            } else {
                Resolved(computed, computedOrigin, computed, reset = true)
            }
        }
        return Resolved(computed, computedOrigin, computed, reset = false)
    }

    /**
     * `INPUT` (§5.2(c)): **1** the submitted value (it passed the input's rules in step 1 — a refused
     * one is absent here); absent ⇒ **2** the sourced row when the input has a source (`source`), else
     * `default_value` (`default`); else `null` (`none`). `computed_default` is the sourced row, else
     * `default_value` — what a renderer offers as "reset to computed" when a typed value keeps winning
     * after its parents changed. The source RUNS on every evaluate, a typed value or not, so that value
     * follows the parents.
     */
    private fun input(
        parameter: ParameterDefinition,
        sourced: Any?,
        submission: Submission,
    ): Resolved {
        val default = declaredDefault(parameter)?.let { coerce(parameter.type, it) }
        val computed = sourced ?: default
        val computedOrigin =
            when {
                sourced != null -> ValueOrigin.SOURCE
                default != null -> ValueOrigin.DEFAULT
                else -> ValueOrigin.NONE
            }
        return if (submission is Submission.Supplied) {
            Resolved(submission.value, ValueOrigin.CLIENT, computed, reset = false)
        } else {
            Resolved(computed, computedOrigin, computed, reset = false)
        }
    }

    /**
     * `required_missing` (P25): required, and nothing resolved — no client value, no default, and no
     * options / no sourced row. A client value the step-1 judge refused already carries its own error;
     * the parameter is not ALSO "missing" — the user supplied something, it was wrong.
     */
    private fun requiredMissing(
        parameter: ParameterDefinition,
        submission: Submission,
        reason: String,
    ): ParameterError? =
        if (!parameter.required || submission is Submission.Refused) {
            null
        } else {
            ParameterError(
                ParameterErrorCodes.EVALUATE_REQUIRED_MISSING,
                "A value is required and none resolved (${reason.replace('_', ' ')}).",
                mapOf("parameter" to parameter.name.safeEcho(), "reason" to reason),
            )
        }

    // ---- the whole request -----------------------------------------------------------------------

    /**
     * The evaluate's deadline — a `withTimeout` on the coroutines that await the workers. Ours versus
     * an ancestor's is told by scope liveness, never by exception type: kotlinx hands a parent's
     * `TimeoutCancellationException` to children UNWRAPPED (MISTAKES), and only when OUR scope is still
     * active did our own deadline fire.
     */
    private suspend fun <T> underDeadline(block: suspend () -> T): T =
        try {
            withTimeout(config.evaluateTimeoutSeconds.seconds) { block() }
        } catch (e: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            throw DatapipelinesException(
                code = ParameterErrorCodes.EVALUATE_TIMEOUT,
                message =
                    "The evaluate did not finish within ${config.evaluateTimeoutSeconds} s " +
                        "(datapipelines.parameters.evaluate-timeout-seconds).",
                details = mapOf("timeout_seconds" to config.evaluateTimeoutSeconds),
                cause = e,
            )
        }

    private fun refuseOversized(response: EvaluateResponse) {
        val size = EvaluateResponseJson.bytes(response).size.toLong()
        if (size <= config.maxEvaluateResponseBytes) return
        throw DatapipelinesException(
            code = ParameterErrorCodes.EVALUATE_RESPONSE_TOO_LARGE,
            message =
                "The evaluate response would be $size bytes; the limit is ${config.maxEvaluateResponseBytes} " +
                    "(datapipelines.parameters.max-evaluate-response-bytes) — lower the selectors' rows or the option caps.",
            details = mapOf("bytes" to size, "max_bytes" to config.maxEvaluateResponseBytes),
        )
    }

    private fun tiers(): Map<String, Any?> {
        val now = clock.instant()
        val zone = runCatching { ZoneId.of(org.values[OrgContext.TIMEZONE] as String) }.getOrDefault(ZoneOffset.UTC)
        return LinkedHashMap(org.values).apply {
            put(ContextKeys.CURRENT_DATE, LocalDate.ofInstant(now, zone))
            put(ContextKeys.CURRENT_TIMESTAMP, now)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ParameterEvaluator::class.java)

        const val NO_OPTIONS = "no_options"
        const val NO_DEFAULT = "no_default"
        const val NO_ROW = "no_row"
        const val MAX_DETAIL_CHARS = 200

        /** A stored wire value (a constant option, a `default_value`) as its canonical value — it passed the validator at save. */
        fun coerce(
            type: LogicalType,
            node: JsonNode,
        ): Any =
            when (val outcome = ParameterCoercion.coerce(type, node)) {
                is ParameterCoercion.Outcome.Coerced -> {
                    outcome.value
                }

                is ParameterCoercion.Outcome.Rejected -> {
                    error(
                        "a stored value no longer coerces to ${type.wire} — the body did not pass save",
                    )
                }
            }

        /** The declared `default_value`, a JSON null read as none (the model's own rule). */
        fun declaredDefault(parameter: ParameterDefinition): JsonNode? =
            parameter.defaultValue?.takeUnless { it.isNull || it.isMissingNode }

        /** Every parameter that depends on [name], transitively, in display order (record §5.3's `dependents`). */
        fun dependents(
            graph: Dag<ParameterDefinition>,
            name: String,
            order: Map<String, Int>,
        ): List<String> {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque(graph.dependentsOf(name))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (seen.add(next)) queue.addAll(graph.dependentsOf(next))
            }
            return seen.sortedBy { order[it] ?: Int.MAX_VALUE }
        }
    }
}
