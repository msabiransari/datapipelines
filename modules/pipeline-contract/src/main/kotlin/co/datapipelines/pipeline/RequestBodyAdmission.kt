package co.datapipelines.pipeline

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyRefusal

/**
 * The run-time admission of an execution's structured request body (pipeline-contract §3.4,
 * published-mutation-apis §3 item 4; #1038) — the ONE judgement every execution surface applies
 * before any node runs: framework execute, `pipelines_execute`, schedules, and the executor itself
 * as the last line.
 *
 * Refusals carry [PipelineErrorCodes.Execution.REQUEST_BODY_INVALID] with a `reason` and never
 * the body, a member name or a value — the caller's own data stays out of the envelope, logs and
 * stored failure events.
 */
object RequestBodyAdmission {
    /** `details.reason` when the pipeline requires a body and none was supplied. */
    const val REQUIRED = "required"

    /** `details.reason` when a body was supplied to a pipeline that declares none. */
    const val NOT_ACCEPTED = "not_accepted"

    /** `details.reason` when `parameters` names a body-reading SCRIPT node's output. */
    const val OVERRIDE_REFUSED = "override_refused"

    /**
     * Refuses [body] for [pipeline], or returns normally. [suppliedParameters] are the keys of the
     * request's `parameters` object: one naming a body reader's `context_key` would skip the
     * transformer the body exists to feed, so it is refused rather than silently ignored.
     *
     * @throws DatapipelinesException `pipeline.execution.request_body_invalid`
     */
    fun check(
        pipeline: Pipeline,
        body: StructuredBody,
        suppliedParameters: Set<String> = emptySet(),
    ) {
        refusal(pipeline, body, suppliedParameters)?.let { throw it }
    }

    /** The first rule [body] breaks for [pipeline] — declaration, then presence, then overrides — or null. */
    private fun refusal(
        pipeline: Pipeline,
        body: StructuredBody,
        suppliedParameters: Set<String>,
    ): DatapipelinesException? {
        val declaration = pipeline.requestBody
        if (declaration == null && body.present) {
            return refused(NOT_ACCEPTED, "This pipeline declares no request_body, so an execution cannot supply one.")
        }
        if (declaration?.required == true && !body.present) {
            return refused(REQUIRED, "This pipeline requires a request_body (a JSON object or array of objects); none was supplied.")
        }
        val overridden =
            pipeline
                .requestBodyReaders()
                .mapNotNull { node -> node.contextKey?.takeUnless(String::isBlank) }
                .filter { it in suppliedParameters }
                .sorted()
        return if (overridden.isEmpty()) {
            null
        } else {
            refused(
                OVERRIDE_REFUSED,
                "The parameters name the output of a SCRIPT node that reads the request body; that value comes only " +
                    "from the pinned transformer.",
                mapOf("keys" to overridden),
            )
        }
    }

    /** The refusal for a body the bounded decoder refused — reason and position only, never a value. */
    fun decodeRefused(refusal: StructuredBodyRefusal): DatapipelinesException =
        refused(
            refusal.reason.wire,
            "The request_body is not one bounded JSON object or array of objects (${refusal.reason.wire.replace('_', ' ')}).",
            buildMap {
                refusal.offset?.let { put("offset", it) }
                refusal.index?.let { put("index", it) }
            },
        )

    private fun refused(
        reason: String,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Execution.REQUEST_BODY_INVALID,
        message = message,
        details = mapOf("reason" to reason) + extra,
    )
}
