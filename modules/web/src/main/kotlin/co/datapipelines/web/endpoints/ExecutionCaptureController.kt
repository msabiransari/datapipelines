package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.CaptureSession
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * rest-api §26 — runtime capture of published mutations (#1043). Workspace administrators only, interactive session
 * only: every handler carries a session-only `execution.capture.*` permission (no key kind or role holds it, an
 * administrator-role `mcp` key included) and checks the session again here. The writes are POSTs under the session's
 * CSRF protection. Every answer is `Cache-Control: no-store`.
 */
@RestController
class ExecutionCaptureController(
    private val captures: ExecutionCaptureService,
) {
    /** §26.1 — the route's active window (or null) and its recent windows with the executions each captured. */
    @GetMapping("/api/v1/endpoints/{id}/captures")
    @RequiredScope(Permission.EXECUTION_CAPTURE_MANAGE)
    fun list(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = requireCaptureSession()
        val overview = captures.overview(principal.requireWorkspace().id, id)
        return noStore(
            HttpStatus.OK,
            mapOf(
                "endpoint_id" to overview.endpoint.id.toString(),
                "method" to overview.endpoint.method.name,
                "active" to overview.active?.let { sessionWire(it, overview.now) },
                "captures" to
                    overview.recent.map {
                        sessionWire(it.session, overview.now) + ("execution_ids" to it.executions.map(UUID::toString))
                    },
            ),
        )
    }

    /** §26.2 — open a window; the optional body `{"correlation_id": "<uuid>"}` restricts it to one exact request. */
    @PostMapping("/api/v1/endpoints/{id}/captures")
    @RequiredScope(Permission.EXECUTION_CAPTURE_MANAGE)
    fun start(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: JsonNode?,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = requireCaptureSession()
        val opened = captures.start(principal, principal.requireWorkspace().id, id, correlationOf(body))
        return noStore(HttpStatus.CREATED, sessionWire(opened, opened.startedAt))
    }

    /** §26.3 — end window [captureId] (only that one); idempotent. */
    @PostMapping("/api/v1/endpoints/{id}/captures/{captureId}/stop")
    @RequiredScope(Permission.EXECUTION_CAPTURE_MANAGE)
    fun stop(
        @PathVariable id: UUID,
        @PathVariable captureId: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = requireCaptureSession()
        val stopped = captures.stop(principal, principal.requireWorkspace().id, id, captureId)
        return noStore(HttpStatus.OK, sessionWire(stopped, Instant.now()))
    }

    /** §26.4 — the private detail of a captured execution, until its expiry. */
    @GetMapping("/api/v1/executions/{id}/capture")
    @RequiredScope(Permission.EXECUTION_CAPTURE_READ)
    fun detail(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val principal = requireCaptureSession()
        return noStore(HttpStatus.OK, detailWire(captures.inspect(principal, principal.requireWorkspace().id, id)))
    }

    companion object {
        /**
         * The interactive-session gate of the capture surfaces — defense in depth behind the permission (which no key
         * holds): a key reaching a capture handler is refused with `auth.session_required`.
         */
        fun requireCaptureSession(): AuthenticatedPrincipal =
            currentPrincipal().also {
                if (it.authMethod != AuthMethod.OIDC) {
                    throw DatapipelinesException(
                        code = PipelineErrorCodes.Auth.SESSION_REQUIRED,
                        message = "Runtime capture is an administrator action and requires an interactive session.",
                        details = mapOf("auth_method" to it.authMethod.name),
                    )
                }
            }

        /** The window's wire shape (§26.1): identities, times and state — never a value. */
        fun sessionWire(
            session: CaptureSession,
            now: Instant,
        ): Map<String, Any?> =
            mapOf(
                "capture_id" to session.id.toString(),
                "endpoint_id" to session.endpointId.toString(),
                "method" to session.method.name,
                "path_pattern" to session.pathPattern,
                "correlation_id" to session.correlationId?.toString(),
                "started_at" to session.startedAt.toString(),
                "deadline_at" to session.deadlineAt.toString(),
                "ended_at" to session.stoppedAt?.toString(),
                "end_reason" to session.endReasonAt(now)?.name?.lowercase(),
                "active" to session.activeAt(now),
            )

        /** The detail's wire shape (§26.4); a value is the JSON text it was stored as, never re-rendered. */
        fun detailWire(detail: CaptureDetail): Map<String, Any?> =
            mapOf(
                "execution_id" to detail.executionId.toString(),
                "capture_id" to detail.sessionId.toString(),
                "endpoint_id" to detail.endpointId.toString(),
                "method" to detail.method.name,
                "admitted_at" to detail.admittedAt.toString(),
                "expires_at" to detail.expiresAt.toString(),
                "execution_status" to detail.executionStatus?.name,
                "capture_status" to detail.status.wire,
                "values_captured" to detail.summary?.valuesCaptured,
                "values_omitted" to detail.summary?.valuesOmitted,
                "write_failures" to detail.summary?.writeFailures,
                "bytes_used" to detail.summary?.bytesUsed,
                "values" to
                    detail.values.map {
                        mapOf(
                            "node_id" to it.nodeId,
                            "output_key" to it.outputKey,
                            "state" to if (it.omittedReason == null) "captured" else "omitted",
                            "value_json" to it.valueJson,
                            "value_bytes" to it.valueBytes,
                            "omitted_reason" to it.omittedReason,
                            "recorded_at" to it.recordedAt.toString(),
                        )
                    },
            )

        private fun correlationOf(body: JsonNode?): String? {
            if (body == null || body.isNull || body.isMissingNode) return null
            if (!body.isObject || body.fieldNames().asSequence().any { it != FIELD_CORRELATION }) {
                throw invalidBody()
            }
            val field = body.get(FIELD_CORRELATION) ?: return null
            if (field.isNull) return null
            if (!field.isTextual) throw invalidBody()
            return field.asText()
        }

        private fun invalidBody() =
            DatapipelinesException(
                code = PipelineErrorCodes.ExecutionCapture.INVALID,
                message = "The capture request accepts only an optional correlation_id UUID.",
                details = mapOf("reason" to ExecutionCaptureService.REASON_CORRELATION),
            )

        private fun <T> noStore(
            status: HttpStatus,
            body: T,
        ): ResponseEntity<ApiResponse<T>> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(body))

        private const val FIELD_CORRELATION = "correlation_id"
    }
}
