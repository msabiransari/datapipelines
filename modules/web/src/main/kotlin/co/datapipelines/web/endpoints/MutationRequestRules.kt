package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedIdempotencyKey
import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType

/**
 * The per-request rules of a published mutation (#1039; published-mutation-apis §3–§5): the request's envelope — its
 * `Idempotency-Key` and its body — read BEFORE any lookup, and the pin's own rules — body required or accepted,
 * parameters, `Accept` — read against the version a request actually runs.
 *
 * ## No value leaves a refusal
 *
 * A `400` lists every problem under one shared cap of [MAX_ERRORS] entries, with `truncated` saying whether more
 * existed. An entry names a DECLARED parameter, its catalogued code and a fixed sentence: never the supplied value,
 * the key, a header value or an undeclared name the caller chose (the framework GET validator's messages quote up to
 * 64 characters of a value, which a mutation's audit-adjacent answer must not).
 */
class MutationRequestRules(
    private val resultConfig: ResultConfig,
) {
    private val log = LoggerFactory.getLogger(MutationRequestRules::class.java)

    /** The envelope's verdict: the decoded body, or the refusal to answer. */
    sealed interface Envelope {
        class Read(
            val body: StructuredBody,
        ) : Envelope

        class Refused(
            val outcome: Outcome,
        ) : Envelope
    }

    /** The pin's verdict over a request. */
    sealed interface Validated {
        class Valid(
            val valid: EndpointRequestValidator.Outcome.Valid,
        ) : Validated

        class Refused(
            val outcome: Outcome,
        ) : Validated
    }

    /**
     * The key and the body, read once. The body is decoded permissively (any route may carry one here); whether THIS
     * route's pin takes or needs one is [validate]'s question, because a replay answers from the ORIGINAL pin.
     * `413` and `415` answer at once; a malformed body and a missing or malformed key are merged `400` entries.
     */
    @Suppress("ReturnCount") // the stages ARE the refusal order
    fun envelope(
        call: MutationCall,
        codec: PublishedRequestBodyCodec,
    ): Envelope {
        val input = call.input
        // A form or multipart body may already have been consumed by the container's form filter before it reaches
        // here, so it would read as "no body": refuse the media type itself, whatever the stream holds now — unless the
        // request DECLARED a zero length, which proves nothing was sent (a client library labelling a bodyless call).
        if (formLike(input.contentType) && input.declaredLength != 0L) return Envelope.Refused(unsupportedMediaType())
        val entries = mutableListOf<Map<String, Any?>>()
        PublishedIdempotencyKey.refusal(input.idempotencyKey)?.let { reason -> entries += keyEntry(reason) }
        val body =
            when (val read = codec.read(input.contentType, input.contentEncoding, input.entity, PERMISSIVE)) {
                is PublishedRequestBodyCodec.Outcome.Admitted -> {
                    read.body
                }

                is PublishedRequestBodyCodec.Outcome.Refused -> {
                    if (read.status.value() != HTTP_BAD_REQUEST) return Envelope.Refused(refusedOf(read))
                    entries += errorsOf(read.details)
                    null
                }
            }
        if (entries.isNotEmpty() || body == null) return Envelope.Refused(invalid(entries))
        return Envelope.Read(body)
    }

    /** [pin]'s rules over the request: the body's presence, then parameters and `Accept`, all `400` entries merged. */
    fun validate(
        call: MutationCall,
        pin: MutationPin,
        body: StructuredBody,
    ): Validated {
        val pipeline = pin.executable.pipeline
        val route = PublishedRequestBodyCodec.Route.of(call.method, pipeline)
        val entries = mutableListOf<Map<String, Any?>>()
        when {
            body.present && !route.acceptsBody -> {
                entries += bodyEntry(PublishedRequestBodyCodec.REASON_NOT_ACCEPTED, "This route takes no request body.")
            }

            !body.present && route.required -> {
                entries += bodyEntry(PublishedRequestBodyCodec.REASON_REQUIRED, "This route requires a JSON request body; none was sent.")
            }
        }
        val validator = EndpointRequestValidator(declared = pipeline.parameters, limits = limits())
        return when (val validated = validator.validate(call.request.copy(pathVariables = call.pathVariables))) {
            is EndpointRequestValidator.Outcome.Unacceptable -> {
                Validated.Refused(notAcceptable())
            }

            is EndpointRequestValidator.Outcome.Invalid -> {
                val declared = pipeline.parameters.keys
                Validated.Refused(invalid(entries + validated.defects.map { sanitized(it, declared) }))
            }

            is EndpointRequestValidator.Outcome.Valid -> {
                if (entries.isEmpty()) Validated.Valid(validated) else Validated.Refused(invalid(entries))
            }
        }
    }

    /** `X-Result-Page-Rows` clamped the way the GET validator clamps it — for a replay's first page. */
    fun pageRows(call: MutationCall): Int =
        call.request.resultPageRowsHeader
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(1, resultConfig.pageMaxRows)
            ?: resultConfig.pageSizeRows

    /** `503 endpoint.mutations_disabled` — the operator withheld FRESH mutations; replay and polling remain. */
    fun disabled(call: MutationCall): Outcome =
        Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.MUTATIONS_DISABLED,
            "Mutation routes are disabled on this deployment (datapipelines.endpoints.mutations-enabled=false). " +
                "Nothing was written. A retry of an already admitted request is still answered.",
            mapOf("method" to call.method.name),
        )

    /** `503 endpoint.pipeline_not_released` — the route has no servable pin; nothing was reserved or written. */
    fun notReleased(endpoint: PublishedEndpoint): Outcome {
        log.warn(
            "event=endpoint.pipeline_not_released path={} endpoint_id={} " +
                "message=\"endpoint is live but its current API version or the pipeline version it pins is not servable\"",
            endpoint.pathPattern,
            endpoint.id,
        )
        return Outcome.Refused(
            HTTP_SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED,
            "This endpoint's pipeline has no servable version.",
        )
    }

    private fun limits() =
        EndpointRequestValidator.Limits(
            ttlMinSeconds = resultConfig.ttlMinSeconds,
            ttlMaxSeconds = resultConfig.ttlMaxSeconds,
            ttlDefaultSeconds = resultConfig.ttlDefaultSeconds,
            pageMaxRows = resultConfig.pageMaxRows,
            pageDefaultRows = resultConfig.pageSizeRows,
        )

    private fun formLike(contentType: String?): Boolean {
        val type =
            contentType?.takeUnless(String::isBlank)?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
                ?: return false
        return type.isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED) || type.type.equals("multipart", ignoreCase = true)
    }

    private fun unsupportedMediaType(): Outcome =
        Outcome.Refused(
            HTTP_UNSUPPORTED_MEDIA_TYPE,
            PipelineErrorCodes.EndpointRequest.UNSUPPORTED_MEDIA_TYPE,
            "A request body must be application/json (UTF-8) with no content encoding.",
            mapOf("reason" to PublishedRequestBodyCodec.REASON_MEDIA_TYPE, "accepts" to listOf(MediaType.APPLICATION_JSON_VALUE)),
        )

    private fun notAcceptable(): Outcome =
        Outcome.Refused(
            HTTP_NOT_ACCEPTABLE,
            PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE,
            "This endpoint serves application/json.",
            mapOf("produces" to listOf(MediaType.APPLICATION_JSON_VALUE)),
        )

    private fun refusedOf(read: PublishedRequestBodyCodec.Outcome.Refused): Outcome =
        Outcome.Refused(read.status.value(), read.code, read.message, read.details)

    private fun invalid(entries: List<Map<String, Any?>>): Outcome {
        val shown = entries.take(MAX_ERRORS)
        return Outcome.Refused(
            HTTP_BAD_REQUEST,
            PipelineErrorCodes.EndpointRequest.INVALID,
            "The request has ${entries.size} problem(s); details.errors lists them (at most $MAX_ERRORS).",
            mapOf("errors" to shown, "truncated" to (entries.size > shown.size)),
        )
    }

    private companion object {
        /** The shared cap on listed `400` entries (published-mutation-apis §5). */
        const val MAX_ERRORS = 20

        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_ACCEPTABLE = 406
        const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415
        const val HTTP_SERVICE_UNAVAILABLE = 503

        /** The envelope reads any body; the pin decides later whether this route takes one. */
        val PERMISSIVE = PublishedRequestBodyCodec.Route(acceptsBody = true, required = false)

        fun keyEntry(reason: String): Map<String, Any?> =
            mapOf(
                "parameter" to null,
                "code" to PipelineErrorCodes.EndpointRequest.IDEMPOTENCY_KEY_INVALID,
                "message" to
                    if (reason == PublishedIdempotencyKey.REASON_MISSING) {
                        "This request needs an Idempotency-Key header; none was sent."
                    } else {
                        "The Idempotency-Key header must be 1 to ${PublishedIdempotencyKey.MAX_LENGTH} characters of " +
                            "letters, digits, '.', '_', ':' or '-'."
                    },
                "reason" to reason,
            )

        fun bodyEntry(
            reason: String,
            message: String,
        ): Map<String, Any?> =
            EndpointRequestValidator
                .Defect(parameter = null, code = PipelineErrorCodes.EndpointRequest.BODY_INVALID, message = message)
                .toMap() + mapOf("reason" to reason)

        @Suppress("UNCHECKED_CAST") // the codec's own shape: details.errors is a list of entry maps
        fun errorsOf(details: Map<String, Any?>): List<Map<String, Any?>> = (details["errors"] as? List<Map<String, Any?>>).orEmpty()

        /** A validator defect with its value-quoting message replaced, and an undeclared (caller-chosen) name dropped. */
        fun sanitized(
            defect: EndpointRequestValidator.Defect,
            declared: Set<String>,
        ): Map<String, Any?> {
            val name = defect.parameter?.takeIf { it in declared }
            val subject = name?.let { "Parameter '$it'" } ?: "A parameter"
            return mapOf("parameter" to name, "code" to defect.code, "message" to "$subject is invalid (${defect.code}).")
        }
    }
}
