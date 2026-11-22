package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.CaptureSession
import co.datapipelines.application.endpoints.CaptureStart
import co.datapipelines.application.endpoints.CaptureStop
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.ExecutionCaptureSessionRepository
import co.datapipelines.application.endpoints.ExecutionCaptureStore
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.application.endpoints.StoredCaptureRun
import co.datapipelines.application.endpoints.StoredCaptureValue
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * #1043 — the administrator service's own rules (rest-api §26): what a start refuses before anything is written, that a
 * window's deadline is fixed from the configured window at start, that a stop names only its own window, and that a
 * read refuses an uncaptured or another workspace's execution as absent and an expired one as gone — at the exact
 * expiry instant, with a controllable clock, whether or not the purge has run. Every audit row is value-free.
 */
class ExecutionCaptureServiceTest {
    private val workspaceId = UUID.randomUUID()
    private val endpoints = mockk<PublishedEndpointRepository>()
    private val sessions = mockk<ExecutionCaptureSessionRepository>()
    private val reservations = mockk<PublishedMutationReservationRepository>()
    private val store = mockk<ExecutionCaptureStore>()
    private val executions = mockk<ExecutionRepository>()
    private val audit = RecordingAudit()
    private var now = Instant.parse("2026-11-22T10:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val service = ExecutionCaptureService(endpoints, sessions, reservations, store, executions, audit, WINDOW, clock)
    private val admin =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "admin@company.com",
            displayName = "Admin",
            authMethod = AuthMethod.OIDC,
            workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.WORKSPACE_ADMIN),
        )

    @Test
    fun `a start fixes the deadline from the configured window and audits identities only`() {
        val post = route(EndpointMethod.POST)
        every { endpoints.findById(workspaceId, post.id) } returns post
        val opened = slot<CaptureSession>()
        every { sessions.start(capture(opened)) } answers { CaptureStart.Opened(opened.captured) }
        val filter = UUID.randomUUID()

        val session = service.start(admin, workspaceId, post.id, " $filter ")

        session.startedAt shouldBe now
        session.deadlineAt shouldBe now.plus(WINDOW)
        session.correlationId shouldBe filter
        session.method shouldBe EndpointMethod.POST
        session.pathPattern shouldBe post.pathPattern
        audit.events() shouldBe listOf(ExecutionCaptureService.AUDIT_STARTED)
        audit.rows.single()["capture_id"] shouldBe session.id.toString()
        audit.rows.single()["correlation_filtered"] shouldBe true
        // The filter value itself is not an audit fact: only that one was set.
        audit.rows
            .single()
            .values
            .joinToString() shouldNotContain filter.toString()
    }

    @Test
    fun `a GET route, a malformed filter and an unknown route are refused before anything is written`() {
        val get = route(EndpointMethod.GET)
        every { endpoints.findById(workspaceId, get.id) } returns get
        val post = route(EndpointMethod.POST)
        every { endpoints.findById(workspaceId, post.id) } returns post
        val absent = UUID.randomUUID()
        every { endpoints.findById(workspaceId, absent) } returns null

        shouldThrow<ApiException> { service.start(admin, workspaceId, get.id, null) }.let {
            it.code shouldBe PipelineErrorCodes.ExecutionCapture.INVALID
            it.details["reason"] shouldBe ExecutionCaptureService.REASON_METHOD
        }
        shouldThrow<ApiException> { service.start(admin, workspaceId, post.id, "not-a-uuid") }.let {
            it.code shouldBe PipelineErrorCodes.ExecutionCapture.INVALID
            it.details["reason"] shouldBe ExecutionCaptureService.REASON_CORRELATION
        }
        shouldThrow<ApiException> { service.start(admin, workspaceId, absent, null) }.code shouldBe PipelineErrorCodes.Endpoint.NOT_FOUND
        verify(exactly = 0) { sessions.start(any()) }
        audit.rows.shouldBeEmpty()
    }

    @Test
    fun `a second start is the 409 naming the active window, and nothing is audited`() {
        val post = route(EndpointMethod.POST)
        every { endpoints.findById(workspaceId, post.id) } returns post
        val active = session(post)
        every { sessions.start(any()) } returns CaptureStart.AlreadyActive(active)

        val refused = shouldThrow<ApiException> { service.start(admin, workspaceId, post.id, null) }

        refused.code shouldBe PipelineErrorCodes.ExecutionCapture.ALREADY_ACTIVE
        refused.details["capture_id"] shouldBe active.id.toString()
        audit.rows.shouldBeEmpty()
    }

    @Test
    fun `a stop audits only the stop that ended a window - an already ended one answers its own state`() {
        val post = route(EndpointMethod.POST)
        val ended = session(post).copy(stoppedAt = now, stoppedBy = admin.userId, endReason = CaptureSession.EndReason.STOPPED)
        every { sessions.stop(workspaceId, post.id, ended.id, admin.userId, now) } returnsMany
            listOf(CaptureStop.Stopped(ended), CaptureStop.AlreadyEnded(ended))
        val unknown = UUID.randomUUID()
        every { sessions.stop(workspaceId, post.id, unknown, admin.userId, now) } returns CaptureStop.NotFound

        service.stop(admin, workspaceId, post.id, ended.id) shouldBe ended
        service.stop(admin, workspaceId, post.id, ended.id) shouldBe ended
        shouldThrow<ApiException> { service.stop(admin, workspaceId, post.id, unknown) }.code shouldBe
            PipelineErrorCodes.ExecutionCapture.NOT_FOUND

        audit.events() shouldBe listOf(ExecutionCaptureService.AUDIT_STOPPED)
    }

    @Test
    fun `an uncaptured or another workspace's execution is absent - the same 404, nothing audited`() {
        val uncaptured = reservation(captured = false)
        every { reservations.findOrigin(uncaptured.executionId, workspaceId) } returns uncaptured
        val foreign = UUID.randomUUID()
        every { reservations.findOrigin(foreign, workspaceId) } returns null

        listOf(uncaptured.executionId, foreign).forEach { id ->
            shouldThrow<ApiException> { service.inspect(admin, workspaceId, id) }.code shouldBe
                PipelineErrorCodes.ExecutionCapture.NOT_FOUND
        }
        audit.rows.shouldBeEmpty()
        verify(exactly = 0) { store.values(any(), any(), any()) }
    }

    @Test
    fun `the near-expiry boundary - readable one nanosecond before expiry, gone at expiry even before the purge`() {
        val captured = reservation(captured = true)
        val expiresAt = requireNotNull(captured.captureExpiresAt)
        every { reservations.findOrigin(captured.executionId, workspaceId) } returns captured
        every { store.run(captured.executionId, workspaceId, any()) } returns summary(captured, failures = 0)
        every { store.values(captured.executionId, workspaceId, any()) } returns listOf(value(captured))
        every { executions.findById(workspaceId, captured.executionId) } returns record(captured, ExecutionStatus.SUCCESS)

        now = expiresAt.minusNanos(1)
        val detail = service.inspect(admin, workspaceId, captured.executionId)
        detail.status shouldBe CaptureStatus.COMPLETE
        detail.values.single().valueJson shouldBe "\"MARKER\""
        audit.events() shouldBe listOf(ExecutionCaptureService.AUDIT_INSPECTED)
        audit.rows
            .single()
            .values
            .joinToString() shouldNotContain "MARKER"

        now = expiresAt
        shouldThrow<ApiException> { service.inspect(admin, workspaceId, captured.executionId) }.let {
            it.code shouldBe PipelineErrorCodes.ExecutionCapture.EXPIRED
            it.details["expired_at"] shouldBe expiresAt.toString()
        }
        // The expired read never reached the stored detail, and was not audited as an inspection.
        verify(exactly = 1) { store.values(any(), any(), any()) }
        audit.events() shouldBe listOf(ExecutionCaptureService.AUDIT_INSPECTED)
    }

    @Test
    fun `the capture status - running records, a clean summary completes, a failure or a missing summary is incomplete`() {
        val captured = reservation(captured = true)
        ExecutionCaptureService.statusOf(null, null) shouldBe CaptureStatus.RECORDING
        ExecutionCaptureService.statusOf(ExecutionStatus.RUNNING, null) shouldBe CaptureStatus.RECORDING
        ExecutionCaptureService.statusOf(ExecutionStatus.SUCCESS, summary(captured, failures = 0)) shouldBe CaptureStatus.COMPLETE
        ExecutionCaptureService.statusOf(ExecutionStatus.FAILED, summary(captured, failures = 0)) shouldBe CaptureStatus.COMPLETE
        ExecutionCaptureService.statusOf(ExecutionStatus.SUCCESS, summary(captured, failures = 1)) shouldBe CaptureStatus.INCOMPLETE
        ExecutionCaptureService.statusOf(ExecutionStatus.SUCCESS, null) shouldBe CaptureStatus.INCOMPLETE
        ExecutionCaptureService.statusOf(ExecutionStatus.ABORTED, null) shouldBe CaptureStatus.INCOMPLETE
    }

    @Test
    fun `the window must be positive`() {
        shouldThrow<IllegalArgumentException> {
            ExecutionCaptureService(endpoints, sessions, reservations, store, executions, audit, Duration.ZERO, clock)
        }
    }

    private fun route(method: EndpointMethod): PublishedEndpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/x/v1/orders",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            method = method,
        )

    private fun session(route: PublishedEndpoint) =
        CaptureSession(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            endpointId = route.id,
            method = route.method,
            pathPattern = route.pathPattern,
            correlationId = null,
            startedBy = admin.userId,
            startedAt = now.minusSeconds(60),
            deadlineAt = now.plus(WINDOW),
        )

    private fun reservation(captured: Boolean) =
        PublishedMutationReservation(
            executionId = UUID.randomUUID(),
            workspaceId = workspaceId,
            apiKeyId = "dpk_X",
            endpointId = UUID.randomUUID(),
            apiVersion = 1,
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            hasCaller = true,
            method = EndpointMethod.POST,
            businessPath = "/x/v1/orders",
            scopeDigest = "scope",
            requestFingerprint = "fingerprint",
            bodyBytes = null,
            ownerToken = UUID.randomUUID(),
            createdAt = now,
            expiresAt = now.plusSeconds(86_400),
            captureSessionId = if (captured) UUID.randomUUID() else null,
            captureExpiresAt = if (captured) now.plus(Duration.ofHours(24)) else null,
        )

    private fun summary(
        reservation: PublishedMutationReservation,
        failures: Int,
    ) = StoredCaptureRun(
        sessionId = requireNotNull(reservation.captureSessionId),
        valuesCaptured = 1,
        valuesOmitted = 0,
        writeFailures = failures,
        bytesUsed = 8,
        sealedAt = now,
        expiresAt = requireNotNull(reservation.captureExpiresAt),
    )

    private fun value(reservation: PublishedMutationReservation) =
        StoredCaptureValue("shape", "total", "\"MARKER\"", 8, null, now, requireNotNull(reservation.captureExpiresAt))

    private fun record(
        reservation: PublishedMutationReservation,
        status: ExecutionStatus,
    ) = ExecutionRecord(
        executionId = reservation.executionId,
        pipelineId = reservation.pipelineId,
        pipelineVersion = 1,
        status = status,
        parametersJson = "{}",
        executedBy = admin.userId,
        triggeredVia = ExecutionTrigger.ENDPOINT,
    )

    /** In-memory [AuditEventSink]: the recorded rows are the assertion. */
    private class RecordingAudit : AuditEventSink {
        val rows = mutableListOf<Map<String, Any?>>()
        private val names = mutableListOf<String>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            names += event
            rows += details
        }

        fun events(): List<String> = names.toList()
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(15)
    }
}
