package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedMutationReservation
import co.datapipelines.application.endpoints.PublishedMutationReservationRepository
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.BoundedValueCapture
import co.datapipelines.executor.CaptureSeal
import co.datapipelines.executor.CaptureValueStore
import co.datapipelines.executor.CapturedValue
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * #1043 — the launcher attaches the value recorder ONLY from the capture decision the reservation stored at admission,
 * and writes the run's closing summary once, however the run ended and whether or not the caller still waits. An
 * uncaptured admission's request reaches the runner untouched — the same instance, no recorder, no summary.
 */
class PublishedMutationCaptureLaunchTest {
    private val workspaceId = UUID.randomUUID()
    private val runner = mockk<RecordingExecutionRunner>()
    private val reservations = mockk<PublishedMutationReservationRepository>()
    private val executions = mockk<ExecutionRepository>()
    private val store = RecordingStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val endpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/x/v1/orders",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            method = EndpointMethod.POST,
        )
    private val principal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "dpk_capture01@keys.invalid",
            displayName = "capture",
            authMethod = AuthMethod.API_KEY,
            keyId = KEY_ID,
            workspaceName = "w",
            workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.VIEWER),
            keyKind = ApiKeyKind.ENDPOINT,
            keyRole = KeyRole.API_CALLER,
        )
    private val session = UUID.randomUUID()
    private val expiry = Instant.now().plusSeconds(3_600)
    private val uncaptured =
        PublishedMutationReservation(
            executionId = UUID.randomUUID(),
            workspaceId = workspaceId,
            apiKeyId = KEY_ID,
            endpointId = endpoint.id,
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
            createdAt = Instant.EPOCH,
            expiresAt = Instant.EPOCH.plusSeconds(86_400),
        )
    private val captured = uncaptured.copy(captureSessionId = session, captureExpiresAt = expiry)

    private val launcher =
        PublishedMutationLauncher(
            runner = runner,
            reservations = reservations,
            executions = executions,
            answers = PublishedMutationAnswers(mockk<ResultStore>(relaxed = true), PublishedMutationUrls(null)),
            endpointsProperties = EndpointsProperties(),
            audit = NoAudit,
            scope = scope,
            captures = CaptureRecorders(store, BUDGET),
        )

    @AfterEach
    fun stopScope() {
        scope.cancel()
    }

    @Test
    fun `an uncaptured admission runs its request untouched and writes no summary`() {
        val ran = slot<ExecuteRequest>()
        coEvery { runner.run(capture(ran), workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } returns success(uncaptured)
        val request = request(uncaptured)

        launcher.start(endpoint, principal, uncaptured, request, TIMEOUT_S, PAGE_ROWS).shouldBeInstanceOf<Outcome>()

        ran.captured shouldBeSameInstanceAs request
        ran.captured.valueCapture.shouldBeNull()
        store.seals.shouldBeEmpty()
        store.values.shouldBeEmpty()
    }

    @Test
    fun `a captured admission runs with the stored decision's recorder and seals once after the run`() {
        val ran = slot<ExecuteRequest>()
        coEvery { runner.run(capture(ran), workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } coAnswers {
            // The node path: one declared output recorded through the recorder the request carries.
            requireNotNull(ran.captured.valueCapture) { "the captured run carries its recorder" }.record("shape", "total", 42)
            success(captured)
        }

        launcher.start(endpoint, principal, captured, request(captured), TIMEOUT_S, PAGE_ROWS)

        ran.captured.valueCapture.shouldBeInstanceOf<BoundedValueCapture>()
        store.values.single().let {
            it.executionId shouldBe captured.executionId
            it.nodeId shouldBe "shape"
            it.valueJson shouldBe "42"
            it.expiresAt shouldBe expiry
        }
        awaitSeals(1)
        store.seals.single().let {
            it.sessionId shouldBe session
            it.valuesCaptured shouldBe 1
            it.expiresAt shouldBe expiry
        }
    }

    @Test
    fun `a captured run that outlives the caller's wait is still sealed when it ends`() {
        coEvery { runner.run(any(), workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } coAnswers {
            delay(SLOW_RUN_MS)
            success(captured)
        }

        // Timeout 1 s (the clamp's floor): the caller is answered 202 while the run goes on.
        launcher.start(endpoint, principal, captured, request(captured), 1, PAGE_ROWS).shouldBeInstanceOf<Outcome.Accepted>()

        store.seals.shouldBeEmpty()
        awaitSeals(1)
        store.seals shouldHaveSize 1
    }

    @Test
    fun `a captured run that throws is sealed too - the summary says how much was kept`() {
        coEvery { runner.run(any(), workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws IllegalStateException("late failure")
        every { reservations.releaseUnstarted(captured.executionId, captured.ownerToken) } returns false
        every { executions.findById(workspaceId, captured.executionId) } returns null

        launcher.start(endpoint, principal, captured, request(captured), TIMEOUT_S, PAGE_ROWS)

        awaitSeals(1)
        store.seals.single().valuesCaptured shouldBe 0
    }

    private fun awaitSeals(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (store.seals.size < count && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
    }

    private fun request(reservation: PublishedMutationReservation) =
        ExecuteRequest(
            pipelineId = reservation.pipelineId,
            pipelineVersion = 1,
            pipeline = mockk<Pipeline>(),
            userId = principal.userId,
            workspaceId = workspaceId,
            triggeredVia = ExecutionTrigger.ENDPOINT,
            executionId = reservation.executionId,
        )

    private fun success(reservation: PublishedMutationReservation) =
        ExecutionResult(
            executionId = reservation.executionId,
            status = ExecutionStatus.SUCCESS,
            nodeStats = emptyList(),
            resultRef = null,
            startedAt = Instant.EPOCH,
            completedAt = Instant.EPOCH,
            durationMs = 1,
        )

    /** In-memory [CaptureValueStore]: what was written is the assertion. */
    private class RecordingStore : CaptureValueStore {
        val values = CopyOnWriteArrayList<CapturedValue>()
        val seals = CopyOnWriteArrayList<CaptureSeal>()

        override fun write(value: CapturedValue): Boolean = values.add(value)

        override fun seal(seal: CaptureSeal): Boolean = seals.add(seal)
    }

    private object NoAudit : AuditEventSink {
        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) = Unit
    }

    private companion object {
        const val TIMEOUT_S = 5
        const val PAGE_ROWS = 10
        const val KEY_ID = "dpk_CAPTURE01"
        const val BUDGET = 1_048_576L
        const val SLOW_RUN_MS = 1_500L
        const val AWAIT_S = 10L
        const val POLL_MS = 20L
    }
}
