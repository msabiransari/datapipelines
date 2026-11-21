package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.body.StructuredBody
import co.datapipelines.typesystem.body.StructuredBodyDecoder
import co.datapipelines.typesystem.body.StructuredBodyDecoding
import co.datapipelines.typesystem.body.StructuredBodyRefusal
import co.datapipelines.typesystem.body.StructuredBodyRefusalReason
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets

/**
 * The bounded published-request body codec (#1038; published-mutation-apis §3.2, §4, §5): it turns one published
 * request's entity into the shared [StructuredBody] — the same value framework execute and `pipelines_execute` build
 * from their `request_body` fixture — or into the catalogued refusal, before anything is admitted or written.
 *
 * Serving, admission, the full request fingerprint and replay are #1039's; this codec is the transport half it calls.
 * It refuses in this order, each stage only when the previous passed:
 *
 * 1. **No entity** (zero bytes): absent. A route that requires a body refuses it (`400`, reason `required`).
 * 2. **Encoding and media type**: a `Content-Encoding` other than identity, or a `Content-Type` other than
 *    `application/json` with an absent or UTF-8 charset, is `415 endpoint.request.unsupported_media_type`. Vendor
 *    `+json` types and other charsets are outside v1.
 * 3. **A bodyless route**: a body where the route takes none is `400` (reason `not_accepted`).
 * 4. **Decoding**: the stream is read through [StructuredBodyDecoder], which counts entity bytes as it reads — a
 *    chunked body crossing `body-max-bytes` stops at the cap, never buffered whole — and checks syntax, UTF-8,
 *    duplicate members, the root and array elements, depth and number tokens. A size refusal is `413
 *    endpoint.request.too_large`; every other one is `400`.
 *
 * Every `400` is [PipelineErrorCodes.EndpointRequest.INVALID] carrying ONE [PipelineErrorCodes.EndpointRequest.BODY_INVALID]
 * entry, so #1039 can merge it with the scalar validator's entries under the shared 20-entry cap. No refusal carries a
 * supplied value, a header value or a member name.
 */
class PublishedRequestBodyCodec(
    private val decoder: StructuredBodyDecoder,
) {
    /** What the route admits: whether a body may be sent, and whether one must be. */
    data class Route(
        val acceptsBody: Boolean,
        val required: Boolean,
    ) {
        init {
            require(acceptsBody || !required) { "a route that takes no body cannot require one" }
        }

        companion object {
            /**
             * The route a [method] entity pinning [pipeline] offers: a GET never takes a body (publish refuses a GET pin
             * of a body-requiring pipeline, `endpoint.request_body_incompatible`); a mutation takes one exactly when the
             * pipeline declares `request_body`, and must when it is `required`.
             */
            fun of(
                method: EndpointMethod,
                pipeline: Pipeline,
            ): Route {
                val declaration = pipeline.requestBody
                val accepts = method != EndpointMethod.GET && declaration != null
                return Route(acceptsBody = accepts, required = accepts && declaration.required)
            }
        }
    }

    /** The codec's verdict. */
    sealed interface Outcome {
        /** The decoded body — [StructuredBody.Absent] when none was sent and none is required. */
        data class Admitted(
            val body: StructuredBody,
        ) : Outcome

        /** The refusal: the HTTP [status], the envelope [code], a fixed [message] and bounded [details]. */
        data class Refused(
            val status: HttpStatus,
            val code: String,
            val message: String,
            val details: Map<String, Any?>,
        ) : Outcome
    }

    /**
     * Reads [entity] for [route]. [contentType] and [contentEncoding] are the raw header values (null when absent);
     * they are inspected and never echoed. The caller closes [entity].
     */
    @Suppress("ReturnCount") // the stages ARE the refusal order
    fun read(
        contentType: String?,
        contentEncoding: String?,
        entity: InputStream,
        route: Route,
    ): Outcome {
        val stream = PushbackInputStream(entity, 1)
        val first = stream.read()
        if (first < 0) {
            return if (route.required) {
                invalid(
                    REASON_REQUIRED,
                    "This route requires a JSON request body; none was sent.",
                )
            } else {
                Outcome.Admitted(StructuredBody.Absent)
            }
        }
        stream.unread(first)
        unsupportedEncoding(contentEncoding)?.let { return unsupported(it) }
        unsupportedMediaType(contentType)?.let { return unsupported(it) }
        if (!route.acceptsBody) return invalid(REASON_NOT_ACCEPTED, "This route takes no request body.")
        return when (val decoded = decoder.decode(stream)) {
            is StructuredBodyDecoding.Decoded -> Outcome.Admitted(decoded.body)
            is StructuredBodyDecoding.Refused -> refused(decoded.refusal)
        }
    }

    private fun refused(refusal: StructuredBodyRefusal): Outcome.Refused {
        val reason = refusal.reason
        if (!reason.tooLarge) {
            return invalid(
                reason.wire,
                "The request body is not one JSON object or array of objects (${reason.wire.replace('_', ' ')}).",
                buildMap {
                    refusal.offset?.let { put("offset", it) }
                    refusal.index?.let { put("index", it) }
                },
            )
        }
        val limit =
            when (reason) {
                StructuredBodyRefusalReason.TOO_MANY_BYTES -> decoder.limits.maxBytes
                StructuredBodyRefusalReason.TOO_DEEP -> decoder.limits.maxDepth.toLong()
                else -> null
            }
        return Outcome.Refused(
            HttpStatus.PAYLOAD_TOO_LARGE,
            PipelineErrorCodes.EndpointRequest.TOO_LARGE,
            "The request body exceeds this deployment's bound (${reason.wire.replace('_', ' ')}).",
            buildMap {
                put("reason", reason.wire)
                limit?.let { put("limit", it) }
            },
        )
    }

    private fun invalid(
        reason: String,
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Outcome.Refused {
        val entry =
            EndpointRequestValidator
                .Defect(parameter = null, code = PipelineErrorCodes.EndpointRequest.BODY_INVALID, message = message)
                .toMap() + mapOf("reason" to reason) + extra
        return Outcome.Refused(
            HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.EndpointRequest.INVALID,
            "The request has 1 problem(s); every one of them is listed in details.errors.",
            mapOf("errors" to listOf(entry), "truncated" to false),
        )
    }

    private fun unsupported(reason: String) =
        Outcome.Refused(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            PipelineErrorCodes.EndpointRequest.UNSUPPORTED_MEDIA_TYPE,
            "A request body must be application/json (UTF-8) with no content encoding.",
            mapOf("reason" to reason, "accepts" to listOf(MediaType.APPLICATION_JSON_VALUE)),
        )

    private fun unsupportedEncoding(header: String?): String? {
        val coding = header?.trim().orEmpty()
        return REASON_CONTENT_ENCODING.takeUnless { coding.isEmpty() || coding.equals(IDENTITY, ignoreCase = true) }
    }

    private fun unsupportedMediaType(header: String?): String? {
        val type = parsedJson(header) ?: return REASON_MEDIA_TYPE
        val charset = type.getParameter("charset")?.trim('"', ' ') ?: return null
        return REASON_CHARSET.takeUnless { charset.equals(StandardCharsets.UTF_8.name(), ignoreCase = true) }
    }

    /** The header parsed as exactly `application/json` (any parameters), or null for anything else or unparseable. */
    private fun parsedJson(header: String?): MediaType? {
        val type =
            header?.takeUnless(String::isBlank)?.let {
                try {
                    MediaType.parseMediaType(it)
                } catch (_: InvalidMediaTypeException) {
                    null
                }
            }
        return type?.takeIf { it.type.equals("application", ignoreCase = true) && it.subtype.equals("json", ignoreCase = true) }
    }

    companion object {
        /** `reason` of the body entry: the route requires a body and none was sent. */
        const val REASON_REQUIRED = "required"

        /** `reason` of the body entry: a body on a route that takes none. */
        const val REASON_NOT_ACCEPTED = "not_accepted"

        /** `reason` of the 415: the media type is not `application/json`. */
        const val REASON_MEDIA_TYPE = "media_type"

        /** `reason` of the 415: a charset other than UTF-8. */
        const val REASON_CHARSET = "charset"

        /** `reason` of the 415: a content encoding other than identity. */
        const val REASON_CONTENT_ENCODING = "content_encoding"

        private const val IDENTITY = "identity"
    }
}
