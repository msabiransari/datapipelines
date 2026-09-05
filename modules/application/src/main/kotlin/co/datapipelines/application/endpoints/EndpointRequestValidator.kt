package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.ParameterBindingResult
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.TextNode
import java.math.BigDecimal

/**
 * The published-endpoint request validator (design §5.3) — everything between "this URL resolves
 * to an endpoint" and "run the pipeline".
 *
 * ## One 400 carrying every defect
 *
 * The rule that shapes this whole class: **all failures are reported together**. A caller of a
 * published endpoint is a program whose developer is looking at a URL; telling them about the
 * typo, then about the missing parameter, then about the bad date, across three round trips, is
 * three deploys of their code. So nothing here fails fast — every check runs, every failure is
 * collected, and the result is one [Outcome.Invalid] whose entries name the parameter, the code
 * and the message.
 *
 * ## Strictness is deliberate
 *
 * An unknown query parameter is a `400`, not a shrug. `ParameterBinder` IGNORES undeclared
 * inputs — correct for the execute body, where a client may legitimately send more than one
 * version declares — but wrong here: `?start_dt=2024-01-01` on an endpoint declaring `start_date`
 * would silently run the DEFAULT and return plausible, wrong rows. Wrong rows that look right are
 * worse than an error, so this surface refuses what the execute body tolerates.
 *
 * ## Lifting text into the declared type's wire form
 *
 * A URL carries only text; [co.datapipelines.pipeline.ParameterCoercion] judges JSON, and for
 * `INTEGER`/`DECIMAL`/`BOOLEAN` it requires a JSON number or boolean, not a string. So this class
 * lifts each raw string into the wire shape its declared type expects and then hands the result
 * to [ParameterBinder], which stays the single authority on whether a value is acceptable.
 *
 * The split is deliberate and narrow: **lifting is a transport concern** (only this surface has
 * text-only input), **judging is not** (every surface shares it). When a text cannot be lifted at
 * all — `?limit=abc` for an `INTEGER` — this class raises the canonical
 * `pipeline.execution.invalid_parameter_type` itself rather than passing a `TextNode` down to be
 * refused with "INTEGER takes a JSON number", which is a true sentence that means nothing to
 * someone holding a URL.
 *
 * ## Headers are clamps, not failures
 *
 * `DP-Result-TTL-Seconds` and `DP-Result-Page-Rows` are clamped to their configured bounds, and
 * an unparseable value is treated as absent — the server clamps anyway, so the worst outcome of
 * ignoring garbage is the documented default (the rule `requestedResultTtlSeconds` already
 * follows). `Accept` is the exception: it is a `406` rather than a member of the 400 body,
 * because it is a negotiation failure about the response, not a defect in the request's data.
 */
class EndpointRequestValidator(
    private val declared: Map<String, Parameter>,
    private val limits: Limits,
) {
    /** The configured bounds this validator clamps against (§5.5, §7.4). */
    data class Limits(
        val ttlMinSeconds: Long,
        val ttlMaxSeconds: Long,
        val ttlDefaultSeconds: Long,
        val pageMaxRows: Int,
        val pageDefaultRows: Int,
    )

    /** What a caller presented, already decoded by the servlet layer. */
    data class Request(
        val pathVariables: Map<String, String>,
        /** Query parameters as the container parsed them — a list per key, so repeats are visible. */
        val queryParameters: Map<String, List<String>>,
        val accept: String?,
        val resultTtlSecondsHeader: String?,
        val resultPageRowsHeader: String?,
    )

    /** One entry of the `400` body's `details.errors[]`. */
    data class Defect(
        val parameter: String?,
        val code: String,
        val message: String,
    ) {
        fun toMap(): Map<String, Any?> = mapOf("parameter" to parameter, "code" to code, "message" to message)
    }

    sealed interface Outcome {
        /** Everything checked out: the bound parameters and the resolved header values. */
        data class Valid(
            val parameters: Map<String, JsonNode>,
            val ttlSeconds: Long,
            val pageRows: Int,
        ) : Outcome

        /** One `400`, every defect named. */
        data class Invalid(
            val defects: List<Defect>,
        ) : Outcome

        /** A `406` — the one refusal that is not about the request's data. */
        data class Unacceptable(
            val accept: String,
        ) : Outcome
    }

    @Suppress("ReturnCount") // the 406 short-circuits by design; every other path collects
    fun validate(request: Request): Outcome {
        if (!acceptable(request.accept)) return Outcome.Unacceptable(request.accept.orEmpty())

        val defects = mutableListOf<Defect>()
        val values = mutableMapOf<String, JsonNode>()

        // Path variables first. A path variable that names no declared parameter is a PUBLISH-time
        // refusal (§4.2 endpoint.path_variable_unknown), so by the time a request arrives every
        // one of them is declared — but the endpoint's released version can change underneath a
        // published path, so it is checked rather than assumed.
        request.pathVariables.forEach { (name, raw) -> collect(name, raw, inPath = true, defects, values) }

        request.queryParameters.forEach { (name, raws) ->
            when {
                raws.size > 1 -> {
                    defects +=
                        Defect(
                            name,
                            PipelineErrorCodes.EndpointRequest.PARAMETER_REPEATED,
                            "Parameter '$name' appeared ${raws.size} times; this endpoint takes one value per parameter.",
                        )
                }

                // A query parameter that also came from the path is the path variable's, not a
                // second chance to set it — silently letting the query win would let a caller
                // address a different row than the URL says.
                request.pathVariables.containsKey(name) -> {
                    defects +=
                        Defect(
                            name,
                            PipelineErrorCodes.EndpointRequest.PARAMETER_REPEATED,
                            "Parameter '$name' is set by the URL path; it cannot also be given in the query string.",
                        )
                }

                else -> {
                    collect(name, raws.first(), inPath = false, defects, values)
                }
            }
        }

        // Required-missing and any remaining type judgment, from the ONE binder every surface
        // shares. Values this class already rejected are absent from `values`, so a bad type is
        // reported once — by whichever check saw it first — and never twice.
        when (val bound = ParameterBinder(declared).bind(values)) {
            is ParameterBindingResult.Rejected -> {
                bound.failures.forEach { failure ->
                    val parameter = failure.details["parameter"] as? String
                    if (defects.none { it.parameter == parameter }) {
                        defects += Defect(parameter, failure.code, failure.message)
                    }
                }
            }

            is ParameterBindingResult.Bound -> {
                // Nothing to add: every value that bound is already in `values`.
                @Suppress("UNUSED_EXPRESSION")
                Unit
            }
        }

        if (defects.isNotEmpty()) return Outcome.Invalid(defects)
        return Outcome.Valid(
            parameters = values,
            ttlSeconds = clampTtl(request.resultTtlSecondsHeader),
            pageRows = clampPageRows(request.resultPageRowsHeader),
        )
    }

    /** Adds one raw value to [values], or the reason it could not be added to [defects]. */
    private fun collect(
        name: String,
        raw: String,
        inPath: Boolean,
        defects: MutableList<Defect>,
        values: MutableMap<String, JsonNode>,
    ) {
        val parameter = declared[name]
        if (parameter == null) {
            defects +=
                Defect(
                    name,
                    if (inPath) {
                        PipelineErrorCodes.Endpoint.PATH_VARIABLE_UNKNOWN
                    } else {
                        PipelineErrorCodes.EndpointRequest.PARAMETER_UNKNOWN
                    },
                    if (inPath) {
                        "The URL path binds '$name', which the pipeline's released version does not declare."
                    } else {
                        "Unknown parameter '$name'. This endpoint accepts: ${declared.keys.sorted().joinToString(", ")}."
                    },
                )
            return
        }
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
            defects +=
                Defect(
                    name,
                    PipelineErrorCodes.EndpointRequest.VALUE_TOO_LARGE,
                    "Value for '$name' is larger than ${MAX_VALUE_BYTES / BYTES_PER_KB} KB.",
                )
            return
        }
        val lifted = lift(parameter.type, raw)
        if (lifted == null) {
            defects +=
                Defect(
                    name,
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "Parameter '$name' is declared ${parameter.type.wire}; '${raw.take(
                        MAX_ECHOED_CHARS,
                    )}' is not a ${parameter.type.wire}.",
                )
            return
        }
        values[name] = lifted
    }

    /**
     * The raw text as the wire form [type] expects, or null when it cannot be one.
     *
     * Only the three types whose wire form is NOT a JSON string need lifting; everything else is
     * textual on the wire already (§6.3), so it passes through and
     * [co.datapipelines.pipeline.ParameterCoercion] judges its shape — dates, timestamps, base64
     * and the big numerics all keep their existing, tested messages.
     */
    private fun lift(
        type: LogicalType,
        raw: String,
    ): JsonNode? =
        when (type) {
            LogicalType.INTEGER -> raw.trim().toLongOrNull()?.let { LongNode(it) }

            LogicalType.DECIMAL -> runCatching { DecimalNode(BigDecimal(raw.trim())) }.getOrNull()

            // Strictly "true"/"false": Kotlin's toBooleanStrictOrNull, not toBoolean, because the
            // latter maps every other string to false — a `?dry_run=yes` would run for real.
            LogicalType.BOOLEAN -> raw.trim().toBooleanStrictOrNull()?.let { BooleanNode.valueOf(it) }

            else -> TextNode(raw)
        }

    /**
     * v1 serves JSON. A wildcard `Accept`, an absent header and a blank one all mean
     * "whatever you have".
     */
    private fun acceptable(accept: String?): Boolean {
        val header = accept?.trim().orEmpty()
        if (header.isEmpty()) return true
        return header.split(',').any { candidate ->
            val type = candidate.substringBefore(';').trim()
            type == "*/*" || type == "application/*" || type == "application/json"
        }
    }

    private fun clampTtl(header: String?): Long =
        (header?.trim()?.toLongOrNull() ?: limits.ttlDefaultSeconds).coerceIn(limits.ttlMinSeconds, limits.ttlMaxSeconds)

    private fun clampPageRows(header: String?): Int =
        (header?.trim()?.toIntOrNull() ?: limits.pageDefaultRows).coerceIn(1, limits.pageMaxRows)

    private companion object {
        const val BYTES_PER_KB = 1024
        const val MAX_VALUE_BYTES = 4 * BYTES_PER_KB
        const val MAX_ECHOED_CHARS = 64
    }
}
