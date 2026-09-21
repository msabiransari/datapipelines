package co.datapipelines.application.checks

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.SqlProbeExecutionException
import co.datapipelines.datasources.SqlProbeParameter
import co.datapipelines.datasources.SqlProbeParameterException
import co.datapipelines.datasources.SqlProbeRefusalException
import co.datapipelines.datasources.SqlProbeTimeoutException
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.ParameterBindingResult
import co.datapipelines.pipeline.ParameterWireEncoder
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineCheck
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The ONE entry point every surface's check run rides (140, pipeline-contract §3.3): MCP, REST,
 * the UI, and — through the `ReleaseCheckGate` port `web` wires over this class — the release
 * gate. It lives in `modules/application` because the run is cross-aggregate: it needs the
 * pipeline aggregate (bodies and versions), the datasource registry, and the bounded probe, and
 * no single one of those modules may see the other two (module-structure §5.10).
 *
 * ## What a run is
 *
 * [run] binds the parameters ONCE, then runs [Pipeline.checks] SEQUENTIALLY against their
 * datasources through [SqlProbe] — the same bounded, read-only, row-capped probe the
 * `sql_probe` tool exposes — and PERSISTS one `pipeline_check_runs` row per check BEFORE
 * returning (metadata-db §4.20). The persisted rows and the returned outcomes describe the same
 * runs: a release refusal's details and the UI's latest-run list read one truth.
 *
 * ## Parameters: declared only, bound the execute way — no calculator context
 *
 * Binding is `ParameterBinder(pipeline.parameters)` — the DECLARED parameters, exactly as
 * [co.datapipelines.pipeline.ChecksRules] enforced at save time (every `:name` a check binds is
 * a declared parameter). The CALCULATOR `context_key` tier is deliberately NOT available: a
 * check runs with no execution, so no calculator ever computed its key, and supplying one would
 * bind a value the pipeline itself would never see. Supplied keys the pipeline does not declare
 * are ignored (the binder's own §7.2 rule), defaults fill the execute way.
 *
 * A bind REJECTION is not thrown: every check records an `error` row whose message carries the
 * failures, because a run that cannot bind is a fact the gate and the UI must see, not an
 * exception that hides it. Such rows store `duration_ms` NULL (nothing ran) and the default
 * `{}` parameters (there is no bound context to record).
 *
 * ## Errors are verdicts, never thrown
 *
 * Per check, every documented failure of the probe's contract becomes an `error` outcome with a
 * bounded message — an unresolvable or invisible datasource, a refusal, a missing/mistyped
 * parameter, a timeout, a driver refusal (the same sanitized texts `SqlProbeTool` puts on the
 * wire: static exception messages plus the driver's own bounded message, never raw SQL). Only
 * the five types [SqlProbe.probe]'s KDoc declares are caught; anything else is a defect and
 * propagates rather than masquerading as a verdict.
 *
 * ## The rows-count truncation decision
 *
 * A `rows` check is probed with `limit = (expected.rows + 1).coerceAtMost(SqlProbe.MAX_LIMIT)`:
 * one row past the expectation is all an honest count needs, and the cap clamps the rest. The
 * verdict then reads the returned list plus the truncation flag (see
 * [CheckExpectationComparator]'s KDoc): not truncated → the count is exact; truncated with at
 * least `expected.rows` rows back → a clean `fail` (the true count is provably unequal);
 * truncated below that → `error`, because the count exceeded the probe cap and is unknowable —
 * never a silent undercount.
 *
 * `observed` on the outcome is the single cell's wire string (or the row count as a string);
 * the row's `observed_json` is `{"value": "74.62"}` or `{"rows": 6}` (metadata-db §4.20) — null
 * on either when the run errored before a value could be read.
 */
class PipelineCheckRunner(
    private val pipelines: PipelineService,
    private val datasources: DatasourceRegistry,
    private val probe: SqlProbe,
    private val runs: PipelineCheckRunRepository,
) {
    /**
     * The release gate's entry: run [pipeline]'s checks — the body the caller already holds —
     * and persist one row per check.
     *
     * [persist] is false only for the promotion receiver's pre-import gate: the pipeline does
     * not exist on the receiver yet, so no `pipeline_check_runs` row can key to it — the
     * outcomes are evaluated, nothing is recorded, and the receiver's first PERSISTED run is
     * the first one commissioned after the import lands. Every other caller keeps the
     * default: the row lands before the outcome returns.
     *
     * @return one outcome per declared check, in declaration order; empty when the body has none.
     */
    fun run(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int,
        pipeline: Pipeline,
        parameters: Map<String, JsonNode>,
        via: CheckRunVia,
        actor: UUID,
        correlationId: String? = null,
        persist: Boolean = true,
    ): List<CheckRunOutcome> {
        if (pipeline.checks.isEmpty()) return emptyList()

        val bound = ParameterBinder(pipeline.parameters).bind(parameters)
        if (bound is ParameterBindingResult.Rejected) {
            val message =
                "the parameters did not bind: " + bound.failures.joinToString("; ") { it.message }
            // Nothing ran: duration is NULL, and there is no bound context to record ("{}").
            val scope = RunScope(pipelineId, version, via, actor, correlationId, parametersJson = "{}")
            return pipeline.checks.map { check ->
                val outcome = CheckRunOutcome(check.id, check.name, check.expected, observed = null, CheckRunVerdict.ERROR, message)
                if (!persist) {
                    outcome
                } else {
                    outcome.copy(ranAt = persist(scope, durationMs = null, check, outcome, observedKind = null).ranAt)
                }
            }
        }

        val context = (bound as ParameterBindingResult.Bound).context
        val probeParameters =
            pipeline.parameters.mapValues { (name, parameter) ->
                SqlProbeParameter(parameter.type, wireString(parameter.type, context[name]))
            }
        // The row records what the statements actually ran with: the BOUND context (defaults
        // applied, undeclared supplied keys absent), every value wire-encoded per its type.
        val parametersJson =
            MAPPER.writeValueAsString(
                pipeline.parameters.mapValues { (name, parameter) -> ParameterWireEncoder.encode(parameter.type, context[name]) },
            )
        val scope = RunScope(pipelineId, version, via, actor, correlationId, parametersJson)
        val resolved = HashMap<String, Result<Datasource?>>()
        return pipeline.checks.map { check -> runOne(workspaceId, scope, probeParameters, resolved, check, persist) }
    }

    /**
     * The surfaces' entry: resolve the body itself, then [run]. A null [version] takes the
     * **working version** — the DRAFT when one exists, else the current RELEASED — the same
     * default every execute surface uses (`PipelineService.workingVersion`, versioning §7).
     *
     * @return null when the pipeline or the resolved version has no stored body — the surface
     *   owns its 404, exactly as it does for execute.
     */
    fun run(
        workspaceId: UUID,
        pipelineId: UUID,
        version: Int?,
        parameters: Map<String, JsonNode>,
        via: CheckRunVia,
        actor: UUID,
        correlationId: String? = null,
    ): List<CheckRunOutcome>? {
        val record = pipelines.findRecord(workspaceId, ReadLens.Everything, pipelineId) ?: return null
        val resolved = version ?: pipelines.workingVersion(workspaceId, record) ?: return null
        val executable = pipelines.findExecutable(workspaceId, record, resolved) ?: return null
        return run(workspaceId, pipelineId, resolved, executable.pipeline, parameters, via, actor, correlationId)
    }

    /** One check: attempt, persist the ONE row (unless the run is unpersisted), return — the row lands before the outcome does. */
    private fun runOne(
        workspaceId: UUID,
        scope: RunScope,
        probeParameters: Map<String, SqlProbeParameter>,
        resolved: MutableMap<String, Result<Datasource?>>,
        check: PipelineCheck,
        persist: Boolean,
    ): CheckRunOutcome {
        val startedAt = System.nanoTime()
        val attempt = attempt(workspaceId, probeParameters, resolved, check)
        if (!persist) return attempt.outcome
        val row = persist(scope, wallMs(startedAt), check, attempt.outcome, attempt.observedKind)
        return attempt.outcome.copy(ranAt = row.ranAt)
    }

    /** The check's verdict path — resolve, probe, compare — with the row not yet written. */
    private fun attempt(
        workspaceId: UUID,
        probeParameters: Map<String, SqlProbeParameter>,
        resolved: MutableMap<String, Result<Datasource?>>,
        check: PipelineCheck,
    ): Attempt {
        // One resolution per datasource per RUN, memoized across the checks that share it: a
        // registry read that threw for check A answers check B identically (every check on a
        // datasource whose read failed is `error` — the brief's grouping rule).
        val resolution = resolved.getOrPut(check.datasource) { runCatching { datasources.getVisible(check.datasource, workspaceId) } }
        val datasource = resolution.getOrNull()
        return when {
            resolution.isFailure -> {
                Attempt(errorOutcomeOf(check, "Datasource '${check.datasource}' could not be resolved for the check run."), null)
            }

            datasource == null -> {
                Attempt(errorOutcomeOf(check, "Datasource '${check.datasource}' is not registered or not visible to this workspace."), null)
            }

            else -> {
                probeAttempt(datasource, check, probeParameters)
            }
        }
    }

    /** Probe and compare, or turn one of the probe's five documented failure types into the `error` outcome. */
    private fun probeAttempt(
        datasource: Datasource,
        check: PipelineCheck,
        probeParameters: Map<String, SqlProbeParameter>,
    ): Attempt =
        try {
            val comparison =
                CheckExpectationComparator.compare(
                    check.expected,
                    probe.probe(datasource, check.sql, probeParameters, limitFor(check.expected)).rows,
                )
            Attempt(
                CheckRunOutcome(check.id, check.name, check.expected, comparison.observed, comparison.verdict, comparison.message),
                comparison.observedKind,
            )
        } catch (e: SqlProbeRefusalException) {
            Attempt(probeFailure(e, check), null)
        } catch (e: SqlProbeParameterException) {
            Attempt(probeFailure(e, check), null)
        } catch (e: SqlProbeTimeoutException) {
            Attempt(probeFailure(e, check), null)
        } catch (e: SqlProbeExecutionException) {
            Attempt(probeFailure(e, check), null)
        } catch (e: DatasourceUnreachableException) {
            Attempt(probeFailure(e, check), null)
        }

    /** One documented probe failure → its `error` outcome; anything undocumented never reaches here. */
    private fun probeFailure(
        e: RuntimeException,
        check: PipelineCheck,
    ): CheckRunOutcome =
        errorOutcomeOf(
            check,
            requireNotNull(probeErrorMessage(e, check)) { "probeFailure reached with an undocumented type: ${e.javaClass.name}" },
        )

    /**
     * The bounded error text for one of [SqlProbe.probe]'s five documented failure types — the
     * same sanitization `SqlProbeTool` puts on the wire: static exception messages, plus the
     * driver's own bounded text for an execution refusal (which CAN echo a statement fragment,
     * and is why no other part of the exception is ever quoted). Null for anything else: an
     * undocumented exception is a defect and propagates rather than masquerading as a verdict.
     */
    private fun probeErrorMessage(
        e: RuntimeException,
        check: PipelineCheck,
    ): String? =
        when (e) {
            is SqlProbeRefusalException -> e.message ?: "The check statement is not a single read-only SELECT."
            is SqlProbeParameterException -> "${e.message} Parameter: '${e.parameter}'."
            is SqlProbeTimeoutException -> e.message ?: "The check statement exceeded its timeout."
            is SqlProbeExecutionException -> "The database refused the statement: ${e.driverMessage}"
            is DatasourceUnreachableException -> "Datasource '${check.datasource}' could not be reached."
            else -> null
        }

    /** The `rows`-check limit: one past the expectation, capped — see the class KDoc. */
    private fun limitFor(expected: CheckExpectation): Int {
        val expectedRows = expected.rows
        return if (expected.kind == CheckExpectation.KIND_ROWS && expectedRows != null) {
            (expectedRows + 1).coerceIn(1, SqlProbe.MAX_LIMIT.toLong()).toInt()
        } else {
            SqlProbe.DEFAULT_LIMIT
        }
    }

    /** The one-row-per-check write — the run's own record, written before the outcome returns. */
    private fun persist(
        scope: RunScope,
        durationMs: Long?,
        check: PipelineCheck,
        outcome: CheckRunOutcome,
        observedKind: CheckComparison.ObservedKind?,
    ): PipelineCheckRun =
        runs.insert(
            NewPipelineCheckRun(
                pipelineId = scope.pipelineId,
                version = scope.version,
                checkId = check.id,
                ranBy = scope.actor,
                via = scope.via,
                parametersJson = scope.parametersJson,
                observedJson = observedJson(outcome.observed, observedKind),
                verdict = outcome.verdict,
                message = outcome.message,
                correlationId = scope.correlationId,
                durationMs = durationMs,
            ),
        )

    /** `observed_json` — `{"value": …}` for a single cell, `{"rows": …}` for a count (metadata-db §4.20). */
    private fun observedJson(
        observed: String?,
        kind: CheckComparison.ObservedKind?,
    ): String? =
        when {
            observed == null || kind == null -> null
            kind == CheckComparison.ObservedKind.ROWS -> MAPPER.writeValueAsString(mapOf("rows" to observed.toLong()))
            else -> MAPPER.writeValueAsString(mapOf("value" to observed))
        }

    /** An `error` outcome — no verdict could be formed; [message] says why. */
    private fun errorOutcomeOf(
        check: PipelineCheck,
        message: String,
    ) = CheckRunOutcome(check.id, check.name, check.expected, observed = null, CheckRunVerdict.ERROR, message)

    /** A bound parameter back to its wire string for the probe — null binds SQL NULL. */
    private fun wireString(
        type: LogicalType,
        value: Any?,
    ): String? = ParameterWireEncoder.encode(type, value).takeUnless { it.isNull }?.asText()

    private fun wallMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    /** What every row of one run shares — pipeline, version, surface, actor, correlation, parameters. */
    private data class RunScope(
        val pipelineId: UUID,
        val version: Int,
        val via: CheckRunVia,
        val actor: UUID,
        val correlationId: String?,
        val parametersJson: String,
    )

    /** One check's outcome with the `observed_json` shape its observed value belongs in, before the row write. */
    private data class Attempt(
        val outcome: CheckRunOutcome,
        val observedKind: CheckComparison.ObservedKind?,
    )

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
