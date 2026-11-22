package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.CaptureSession
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.StoredCaptureRun
import co.datapipelines.application.endpoints.StoredCaptureValue
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.RequestLimits
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrors
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * #1043 (rest-api §26) — the capture controller's own duties behind the session-only permission: a key principal
 * (even an administrator-role one, were the matrix ever to let it through) is refused `auth.session.required` before
 * the service is touched; the start body accepts only an optional `correlation_id` string; every answer is
 * `no-store`; and the detail's wire carries each value as the JSON text it was stored as.
 */
class ExecutionCaptureControllerTest {
    private val workspaceId = UUID.randomUUID()
    private val service = mockk<ExecutionCaptureService>()
    private val controller = ExecutionCaptureController(service)
    private val workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.WORKSPACE_ADMIN)
    private val session =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "admin@company.com",
            displayName = "Admin",
            authMethod = AuthMethod.OIDC,
            workspace = workspace,
        )
    private val adminKey =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "dpk_admin0001@keys.invalid",
            displayName = "key",
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_ADMIN0001",
            workspace = workspace,
            keyKind = ApiKeyKind.MCP,
            keyRole = KeyRole.WORKSPACE_ADMIN,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a key principal is refused session_required on every handler and the service is never called`() {
        signIn(adminKey)
        val id = UUID.randomUUID()
        val calls: List<() -> Unit> =
            listOf(
                { controller.list(id) },
                { controller.start(id, null) },
                { controller.stop(id, UUID.randomUUID()) },
                { controller.detail(id) },
            )

        calls.forEach { call ->
            val refused = shouldThrow<DatapipelinesException> { call() }
            refused.code shouldBe PipelineErrorCodes.Auth.SESSION_REQUIRED
            refused.details["auth_method"] shouldBe AuthMethod.API_KEY.name
        }
        verify(exactly = 0) { service.overview(any(), any()) }
        verify(exactly = 0) { service.start(any(), any(), any(), any()) }
        verify(exactly = 0) { service.stop(any(), any(), any(), any()) }
        verify(exactly = 0) { service.inspect(any(), any(), any()) }
    }

    @Test
    fun `a start passes the optional correlation filter and answers 201 no-store`() {
        signIn(session)
        val endpointId = UUID.randomUUID()
        val filter = UUID.randomUUID().toString()
        val opened = window(endpointId, UUID.fromString(filter))
        every { service.start(session, workspaceId, endpointId, filter) } returns opened
        every { service.start(session, workspaceId, endpointId, null) } returns opened

        val answered = controller.start(endpointId, """{"correlation_id": "$filter"}""")
        // No body, a blank one, an empty object and an explicit null all open an unfiltered window.
        controller.start(endpointId, null)
        controller.start(endpointId, "  ")
        controller.start(endpointId, "{}")
        controller.start(endpointId, """{"correlation_id": null}""")

        answered.statusCode shouldBe HttpStatus.CREATED
        answered.headers.cacheControl shouldBe "no-store"
        answered.body?.data?.get("capture_id") shouldBe opened.id.toString()
        answered.body?.data?.get("correlation_id") shouldBe filter
        answered.body?.data?.get("active") shouldBe true
        verify(exactly = 1) { service.start(session, workspaceId, endpointId, filter) }
        verify(exactly = 4) { service.start(session, workspaceId, endpointId, null) }
    }

    @Test
    fun `a start body with any other field, a non-object, a non-string filter or broken JSON is refused before the service`() {
        signIn(session)
        val endpointId = UUID.randomUUID()
        listOf(
            """{"correlation_id": "x", "window_seconds": 99999}""",
            """{"enabled": true}""",
            """["correlation_id"]""",
            """{"correlation_id": 42}""",
            "\"text\"",
            "[".repeat(RequestLimits.MAX_NESTING_DEPTH) + "]".repeat(RequestLimits.MAX_NESTING_DEPTH),
        ).forEach { body ->
            val refused = shouldThrow<DatapipelinesException> { controller.start(endpointId, body) }
            refused.code shouldBe PipelineErrorCodes.ExecutionCapture.INVALID
            refused.details["reason"] shouldBe ExecutionCaptureService.REASON_CORRELATION
        }
        verify(exactly = 0) { service.start(any(), any(), any(), any()) }
    }

    @Test
    fun `unreadable start bytes are malformed_json - the depth bound named, a parse failure quoting nothing of the body`() {
        signIn(session)
        val endpointId = UUID.randomUUID()
        val deep = "[".repeat(RequestLimits.MAX_NESTING_DEPTH + 1) + "]".repeat(RequestLimits.MAX_NESTING_DEPTH + 1)
        val overBound = shouldThrow<DatapipelinesException> { controller.start(endpointId, deep) }
        overBound.code shouldBe PipelineErrorCodes.ExecutionCapture.INVALID
        overBound.details["reason"] shouldBe ApiErrors.MALFORMED_JSON
        overBound.message shouldContain "nesting depth"
        listOf("{not json", """{"correlation_id": secret-filter-text}""").forEach { body ->
            val broken = shouldThrow<DatapipelinesException> { controller.start(endpointId, body) }
            broken.code shouldBe PipelineErrorCodes.ExecutionCapture.INVALID
            broken.details["reason"] shouldBe ApiErrors.MALFORMED_JSON
            broken.message shouldBe ExecutionCaptureController.MALFORMED_MESSAGE
        }
        verify(exactly = 0) { service.start(any(), any(), any(), any()) }
    }

    @Test
    fun `the detail wire carries the stored JSON text, the omission marker and the status, no-store`() {
        signIn(session)
        val executionId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val expires = Instant.parse("2026-11-23T10:00:00Z")
        val detail =
            CaptureDetail(
                executionId = executionId,
                sessionId = sessionId,
                endpointId = UUID.randomUUID(),
                method = EndpointMethod.PUT,
                admittedAt = Instant.parse("2026-11-22T10:00:00Z"),
                expiresAt = expires,
                executionStatus = ExecutionStatus.SUCCESS,
                status = CaptureStatus.COMPLETE,
                summary = StoredCaptureRun(sessionId, 1, 1, 0, 14, expires.minusSeconds(60), expires),
                values =
                    listOf(
                        StoredCaptureValue("shape", "total", """{"a":"<b>"}""", 11L, null, expires.minusSeconds(90), expires),
                        StoredCaptureValue("shape", "blob", null, null, "byte_budget_exhausted", expires.minusSeconds(90), expires),
                    ),
            )
        every { service.inspect(session, workspaceId, executionId) } returns detail

        val answered = controller.detail(executionId)

        answered.headers.cacheControl shouldBe "no-store"
        val data = requireNotNull(answered.body).data
        data["capture_status"] shouldBe "complete"
        data["execution_status"] shouldBe "SUCCESS"
        data["expires_at"] shouldBe expires.toString()
        val values = data["values"] as List<*>
        (values[0] as Map<*, *>)["value_json"] shouldBe """{"a":"<b>"}"""
        (values[0] as Map<*, *>)["state"] shouldBe "captured"
        (values[1] as Map<*, *>)["state"] shouldBe "omitted"
        (values[1] as Map<*, *>)["omitted_reason"] shouldBe "byte_budget_exhausted"
        data.toString() shouldContain sessionId.toString()
    }

    private fun signIn(principal: AuthenticatedPrincipal) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun window(
        endpointId: UUID,
        filter: UUID?,
    ) = CaptureSession(
        id = UUID.randomUUID(),
        workspaceId = workspaceId,
        endpointId = endpointId,
        method = EndpointMethod.POST,
        pathPattern = "/x/v1/orders",
        correlationId = filter,
        startedBy = session.userId,
        startedAt = Instant.now(),
        deadlineAt = Instant.now().plusSeconds(900),
    )
}
