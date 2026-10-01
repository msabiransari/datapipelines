package co.datapipelines.visualization

import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * The two capability URLs a session hands out (#353; the implementation spec's §6.1, §6.3, §11.2). Built from the
 * deployment's configured external origin (`datapipelines.auth.base-url`) — never from a request's `Host` or
 * `Origin` (auth §5.2's rule) — and root-relative when none is configured, so a caller resolves them against the
 * origin it reached the app on. The preview's query parameter is spelled `session=` by the spec's §6.3 route; its
 * VALUE is the preview capability, not the session id.
 */
class TestSessionLinks(
    baseUrl: String?,
) {
    private val base: String = baseUrl?.trim()?.trimEnd('/').orEmpty()

    /** The preview page's URL — the capability rides the query; the page never echoes it. */
    fun preview(
        visualizationId: UUID,
        previewToken: String,
    ): String = "$base/visualizations/$visualizationId/preview?$PREVIEW_PARAMETER=$previewToken"

    /** The screenshot route of one session — the upload capability rides the [UPLOAD_TOKEN_HEADER] header. */
    fun upload(
        visualizationId: UUID,
        sessionId: UUID,
    ): String = "$base/api/v1/visualizations/$visualizationId/tests/sessions/$sessionId/screenshot"

    companion object {
        /** The header the screenshot route reads its single-use capability from (rest-api §22.2). */
        const val UPLOAD_TOKEN_HEADER = "DP-Upload-Token"

        /** The preview route's query parameter (frozen by the spec's §6.3 as `?session=`). */
        const val PREVIEW_PARAMETER = "session"
    }
}

/** A parsed results submission: the verdicts the agent gave and its environment claims. */
data class TestSubmission(
    val verdicts: List<SubmittedCase>,
    val environment: TestEnvironment?,
)

/**
 * The results body's SHAPE (rest-api §22.2, mcp-server §6.2.62) — `{cases: [{name, verdict, notes?}], environment?}`
 * — read into the service's DTOs with every refusal a `visualization.validation.body_invalid` naming the path and the
 * reason, the service's own spelling. One reader for both transports, so REST and MCP refuse the same bodies the same
 * way. What the shape cannot judge (an unknown or duplicate case, the notes bound) stays the service's.
 */
object TestSubmissionReader {
    private val TOP_LEVEL = setOf("cases", "environment")
    private val CASE_KEYS = setOf("name", "verdict", "notes")

    @Suppress("ThrowsCount") // each wrong shape is its own named refusal
    fun read(node: JsonNode): TestSubmission {
        if (!node.isObject) throw bodyInvalid("not_an_object", "")
        node.fieldNames().forEach { if (it !in TOP_LEVEL) throw bodyInvalid("unknown_key", it) }
        val cases = node.get("cases")
        if (cases == null || cases.isNull) throw bodyInvalid("missing", "cases")
        if (!cases.isArray) throw bodyInvalid("wrong_type", "cases")
        val verdicts = cases.mapIndexed { index, case -> verdict(case, "cases[$index]") }
        return TestSubmission(verdicts, environment(node.get("environment")))
    }

    @Suppress("ThrowsCount") // each wrong shape is its own named refusal
    private fun verdict(
        case: JsonNode,
        path: String,
    ): SubmittedCase {
        if (!case.isObject) throw bodyInvalid("wrong_type", path)
        case.fieldNames().forEach { if (it !in CASE_KEYS) throw bodyInvalid("unknown_key", "$path.$it") }
        val name = case.get("name")?.takeIf { it.isTextual }?.asText() ?: throw bodyInvalid("missing", "$path.name")
        val verdictText = case.get("verdict")?.takeIf { it.isTextual }?.asText() ?: throw bodyInvalid("missing", "$path.verdict")
        val verdict = CaseVerdict.entries.firstOrNull { it.wire == verdictText } ?: throw bodyInvalid("verdict_invalid", "$path.verdict")
        val notes = case.get("notes")
        if (notes != null && !notes.isNull && !notes.isTextual) throw bodyInvalid("wrong_type", "$path.notes")
        return SubmittedCase(name, verdict, notes?.takeIf { it.isTextual }?.asText())
    }

    /** The CLOSED environment set: an unknown field or a non-string value is refused by its path. */
    @Suppress("ThrowsCount") // each wrong shape is its own named refusal
    private fun environment(node: JsonNode?): TestEnvironment? {
        if (node == null || node.isNull) return null
        if (!node.isObject) throw bodyInvalid("wrong_type", "environment")
        val fields = mutableMapOf<String, String?>()
        node.fieldNames().forEach { field ->
            if (field !in TestEnvironment.FIELDS) throw bodyInvalid("environment_unknown", "environment.$field")
            val value = node.get(field)
            if (!value.isNull && (!value.isTextual || value.asText().length > TestEnvironment.MAX_FIELD)) {
                throw bodyInvalid("environment_field_invalid", "environment.$field")
            }
            fields[field] = value.takeIf { it.isTextual }?.asText()
        }
        return TestEnvironment(
            theme = fields["theme"],
            viewport = fields["viewport"],
            browser = fields["browser"],
            locale = fields["locale"],
            rendererVersion = fields["renderer_version"],
        )
    }

    private fun bodyInvalid(
        reason: String,
        path: String,
    ) = DatapipelinesException(
        code = VisualizationErrorCodes.BODY_INVALID,
        message = "The submission is not storable: $reason at ${path.ifEmpty { "the body" }}.",
        details = mapOf("reason" to reason, "path" to path),
    )
}

/**
 * The session answers on the wire — ONE projection for REST and MCP (rest-api §22.2, mcp-server §6.2.61–62), so the
 * two transports cannot answer a start or a submit in two shapes. Each capability's material appears here and
 * nowhere else: the preview capability inside `preview_url`, the upload capability as `upload.token` on a GREEN run.
 */
object TestSessionWire {
    fun started(
        started: TestSessionStarted,
        links: TestSessionLinks,
    ): Map<String, Any?> =
        mapOf(
            "session_id" to started.sessionId.toString(),
            "run_id" to started.runId.toString(),
            "visualization_id" to started.visualizationId.toString(),
            "version" to started.version,
            "body_hash" to started.bodyHash,
            "preview_url" to links.preview(started.visualizationId, started.previewToken),
            "expires_at" to started.expiresAt.toString(),
            "cases" to started.cases,
        )

    fun submitted(
        submitted: TestSessionSubmitted,
        visualizationId: UUID,
        links: TestSessionLinks,
    ): Map<String, Any?> =
        mapOf(
            "session_id" to submitted.sessionId.toString(),
            "run_id" to submitted.runId.toString(),
            "status" to submitted.status.name,
            "completed_at" to submitted.completedAt.toString(),
            "mechanical" to submitted.mechanical,
            "upload" to
                submitted.uploadToken?.let { token ->
                    mapOf(
                        "url" to links.upload(visualizationId, submitted.sessionId),
                        "header" to TestSessionLinks.UPLOAD_TOKEN_HEADER,
                        "token" to token,
                        "expires_at" to submitted.uploadExpiresAt?.toString(),
                    )
                },
        )

    fun screenshot(view: ScreenshotView): Map<String, Any?> =
        mapOf(
            "run_id" to view.runId.toString(),
            "media_type" to view.mediaType,
            "sha256" to view.sha256,
            "width" to view.width,
            "height" to view.height,
            "depicted_case" to view.depictedCase,
            "uploaded_at" to view.uploadedAt.toString(),
        )
}
