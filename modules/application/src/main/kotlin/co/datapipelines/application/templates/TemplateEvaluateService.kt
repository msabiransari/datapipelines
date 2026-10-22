package co.datapipelines.application.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.scripting.ConsoleLine
import co.datapipelines.templates.ScriptTestInput
import co.datapipelines.templates.ScriptTestRunner
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * The one service behind `templates_evaluate` and `POST /api/v1/templates/evaluate`
 * (transform-nodes design §9.1/§9.2): `{ name, version?, input }` resolves the working
 * version (the draft when one exists, else the latest released — `templates_render`'s
 * resolution), applies the §5.1 input check, evaluates on the evaluation pool under
 * `evaluate-timeout-seconds`, gates the output and runs the invariants, and answers
 * `{ output, rejects, invariants }` — no staging, no Context. `sql_probe`'s twin for
 * script templates.
 *
 * A refusal is a [DatapipelinesException] carrying the refusal's own code (the engine's
 * §7 mapping, the input check, or the type gate) — the surface maps it by the error
 * catalog exactly like any other failure, and the audit row never carries the input
 * object or the output (record §9.5).
 */
@Suppress("ThrowsCount") // each refusal is a distinct catalogued outcome the surfaces map
class TemplateEvaluateService(
    private val templates: TemplateRepository,
    private val runner: ScriptTestRunner,
) {
    /** The §9.1 response. */
    data class Evaluation(
        val output: Any?,
        val rejects: List<Map<String, Any?>>,
        val invariants: List<ScriptTestRunner.InvariantVerdict>,
        val console: List<ConsoleLine> = emptyList(),
    ) {
        /** Wire form for adapters that do not depend on the scripting module. */
        fun consolePayload(): List<Map<String, String>> = console.map { mapOf("level" to it.level.name.lowercase(), "text" to it.text) }
    }

    /**
     * Evaluates the working (or [version]'d) version of [name] over [input].
     *
     * @throws DatapipelinesException `template.not_found` (unknown id or version),
     *   `template.contract_invalid` with rule `type_not_script` (an sql/html template —
     *   evaluation is the script types' verb), or the refusal the run itself produced.
     */
    fun evaluate(
        workspaceId: UUID,
        name: String,
        version: Int?,
        input: ScriptTestInput,
        now: Instant? = null,
    ): Evaluation {
        val resolved =
            when {
                version != null -> templates.findVersion(workspaceId, name, version) ?: throw notFound(name, version)
                else -> templates.findWorking(workspaceId, name) ?: throw notFound(name, null)
            }
        if (!resolved.type.isScript) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.CONTRACT_INVALID,
                message =
                    "Template '$name' has type '${resolved.type.wire}'; evaluation is the script types' verb " +
                        "('jsonata'/'javascript').",
                details = mapOf("rule" to "type_not_script", "template_id" to name, "type" to resolved.type.wire),
            )
        }
        val contract = resolved.contract
        val invariants = resolved.invariants
        if (contract == null || invariants == null) {
            // chk_script_blocks says this cannot exist; fail closed rather than NPE.
            throw notFound(name, version)
        }
        val engine =
            runner.engineFor(resolved.type)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Script.JS_UNAVAILABLE,
                    message = co.datapipelines.pipeline.JAVASCRIPT_UNAVAILABLE_MESSAGE,
                    details = mapOf("type" to resolved.type.wire),
                )
        val script = engine.compile(resolved.body)
        val jsonata =
            requireNotNull(runner.engineFor(TemplateType.JSONATA)) { "the JSONATA engine is always registered" }
        val compiledInvariants = invariants.map { it to jsonata.compile(it.expr) }
        return when (
            val outcome =
                runner.runCase(
                    label = "$name@${resolved.version}",
                    engine = engine,
                    script = script,
                    contract = contract,
                    compiledInvariants = compiledInvariants,
                    input = input,
                    now = now,
                )
        ) {
            is ScriptTestRunner.RunOutcome.Refused -> {
                throw evaluationRefusal(name, resolved.version, outcome)
            }

            is ScriptTestRunner.RunOutcome.Evaluated -> {
                Evaluation(outcome.output, outcome.rejects, outcome.invariants, outcome.console)
            }
        }
    }

    private fun evaluationRefusal(
        name: String,
        version: Int,
        outcome: ScriptTestRunner.RunOutcome.Refused,
    ): DatapipelinesException =
        DatapipelinesException(
            code = outcome.code,
            message = outcome.message,
            details =
                outcome.details +
                    mapOf(
                        "template_id" to name,
                        "version" to version,
                        "console" to outcome.console.map { mapOf("level" to it.level.name.lowercase(), "text" to it.text) },
                    ),
        )

    private fun notFound(
        name: String,
        version: Int?,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message =
                if (version == null) {
                    "Template '$name' does not exist."
                } else {
                    "Template '$name' has no version $version."
                },
            details =
                buildMap {
                    put("template_id", name)
                    if (version != null) put("version", version)
                },
        )
}
