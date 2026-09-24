package co.datapipelines.application.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TransformTestInput
import co.datapipelines.templates.TransformTestRunner
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
 * transform templates.
 *
 * A refusal is a [DatapipelinesException] carrying the refusal's own code (the engine's
 * §7 mapping, the input check, or the type gate) — the surface maps it by the error
 * catalog exactly like any other failure, and the audit row never carries the input
 * object or the output (record §9.5).
 */
class TemplateEvaluateService(
    private val templates: TemplateRepository,
    private val runner: TransformTestRunner,
) {
    /** The §9.1 response. */
    data class Evaluation(
        val output: Any?,
        val rejects: List<Map<String, Any?>>,
        val invariants: List<TransformTestRunner.InvariantVerdict>,
    )

    /**
     * Evaluates the working (or [version]'d) version of [name] over [input].
     *
     * @throws DatapipelinesException `template.not_found` (unknown id or version),
     *   `template.contract_invalid` with rule `type_not_transform` (an sql/html template —
     *   evaluation is the transform types' verb), or the refusal the run itself produced.
     */
    fun evaluate(
        workspaceId: UUID,
        name: String,
        version: Int?,
        input: TransformTestInput,
        now: Instant? = null,
    ): Evaluation {
        val resolved =
            when {
                version != null -> templates.findVersion(workspaceId, name, version) ?: throw notFound(name, version)
                else -> templates.findWorking(workspaceId, name) ?: throw notFound(name, null)
            }
        if (!resolved.type.isTransform) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.CONTRACT_INVALID,
                message =
                    "Template '$name' has type '${resolved.type.wire}'; evaluation is the transform types' verb " +
                        "('jsonata'/'javascript').",
                details = mapOf("rule" to "type_not_transform", "template_id" to name, "type" to resolved.type.wire),
            )
        }
        val contract = resolved.contract
        val invariants = resolved.invariants
        if (contract == null || invariants == null) {
            // chk_transform_blocks says this cannot exist; fail closed rather than NPE.
            throw notFound(name, version)
        }
        val engine =
            runner.engineFor(resolved.type)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Template.RENDER_NOT_APPLICABLE,
                    message = "No engine for type '${resolved.type.wire}' yet (javascript ships in round two).",
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
            is TransformTestRunner.RunOutcome.Refused ->
                throw DatapipelinesException(
                    code = outcome.code,
                    message = outcome.message,
                    details = mapOf("template_id" to name, "version" to resolved.version),
                )

            is TransformTestRunner.RunOutcome.Evaluated ->
                Evaluation(outcome.output, outcome.rejects, outcome.invariants)
        }
    }

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
