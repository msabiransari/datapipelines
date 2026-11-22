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
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.LimitScope
import co.datapipelines.executor.PipelineConcurrencyLimitException
import co.datapipelines.executor.ResultStore
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.endpoints.PublishedEndpointServeService.Outcome
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import co.datapipelines.web.sse.ExecutionRecordUnwritableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Instant
import java.util.UUID

/**
 * #1039 — a published mutation refused BEFORE its run started (published-mutation-apis §4, the reconciled 429/503 rows):
 * the execution-concurrency limit's `429 pipeline.execution.concurrency_limit` and the unwritable record's `503`. Such a
 * run executed no business SQL, so the launcher releases its reservation — only while it is still the caller's claim
 * and no execution row exists — and the same key may be retried. A release the store REFUSED is answered from the
 * persisted state; a release or a record read the store did not ANSWER confirms no state at all, so it is
 * `503 idempotency.reservation_unavailable` with the reservation kept — never an assumed running execution, and never
 * the refusal surfaced as if nothing had been admitted.
 *
 * The HTTP surface cannot steer a full slot table without starving its sibling cases, so these branches are pinned
 * here. The audit sink and meter registry are REAL recorders: their contract is "this was written".
 */
class PublishedMutationLauncherTest {
    private val workspaceId = UUID.randomUUID()
    private val runner = mockk<RecordingExecutionRunner>()
    private val reservations = mockk<PublishedMutationReservationRepository>()
    private val executions = mockk<ExecutionRepository>()
    private val audit = RecordingAudit()
    private val meters = SimpleMeterRegistry()
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
            email = "dpk_launcher01@keys.invalid",
            displayName = "launcher",
            authMethod = AuthMethod.API_KEY,
            keyId = KEY_ID,
            workspaceName = "w",
            workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.VIEWER),
            keyKind = ApiKeyKind.ENDPOINT,
            keyRole = KeyRole.API_CALLER,
        )
    private val reservation =
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
    private val request = mockk<ExecuteRequest> { every { executionId } returns reservation.executionId }

    private val launcher =
        PublishedMutationLauncher(
            runner = runner,
            reservations = reservations,
            executions = executions,
            answers = PublishedMutationAnswers(mockk<ResultStore>(), PublishedMutationUrls(null)),
            endpointsProperties = EndpointsProperties(),
            audit = audit,
            scope = scope,
            meters = meters,
        )

    @AfterEach
    fun stopScope() {
        scope.cancel()
    }

    @Test
    fun `a full slot table is the catalogued 429 refusal, the reservation is released and nothing else answers`() {
        val refusal = PipelineConcurrencyLimitException(LimitScope.PER_USER, 1)
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws refusal
        every { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) } returns true

        val thrown = shouldThrow<PipelineConcurrencyLimitException> { start() }

        thrown shouldBeSameInstanceAs refusal
        thrown.code shouldBe PipelineErrorCodes.Execution.CONCURRENCY_LIMIT
        verify(exactly = 1) { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) }
        verify(exactly = 0) { executions.findById(any(), any()) }
        audit.outcomes() shouldBe listOf(PublishedMutationLauncher.OUTCOME_STARTED, PublishedMutationLauncher.OUTCOME_NOT_STARTED)
        counted(PublishedMutationLauncher.OUTCOME_NOT_STARTED) shouldBe 1.0
    }

    @Test
    fun `a refusal whose release is not confirmed answers from the persisted state, never as a retryable 429`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws
            PipelineConcurrencyLimitException(LimitScope.GLOBAL, 1)
        // The claim is no longer ours or an execution row exists: the release refuses.
        every { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) } returns false
        every { executions.findById(workspaceId, reservation.executionId) } returns null

        val outcome = start()

        outcome.shouldBeInstanceOf<Outcome.Accepted>().executionId shouldBe reservation.executionId
        audit.outcomes() shouldBe listOf(PublishedMutationLauncher.OUTCOME_STARTED, PublishedMutationLauncher.OUTCOME_FAILED)
        counted(PublishedMutationLauncher.OUTCOME_NOT_STARTED) shouldBe 0.0
    }

    @Test
    fun `review - a reservation release the store cannot answer is 503, not an assumed running execution`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws
            PipelineConcurrencyLimitException(LimitScope.PER_USER, 1)
        every { reservations.releaseUnstarted(any(), any()) } throws DataAccessResourceFailureException("down")
        every { executions.findById(workspaceId, reservation.executionId) } returns null

        val refused = start().shouldBeInstanceOf<Outcome.Refused>()
        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        // The reservation is kept: one release attempt, nothing retired or re-claimed, nothing reported unadmitted.
        verify(exactly = 1) { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) }
        audit.outcomes() shouldBe
            listOf(PublishedMutationLauncher.OUTCOME_STARTED, PublishedMutationLauncher.OUTCOME_RESERVATION_UNAVAILABLE)
        counted(PublishedMutationLauncher.OUTCOME_NOT_STARTED) shouldBe 0.0
        counted(PublishedMutationLauncher.OUTCOME_RESERVATION_UNAVAILABLE) shouldBe 1.0
    }

    @Test
    fun `an unanswered release and an unreadable record are 503 reservation_unavailable`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws
            ExecutionRecordUnwritableException(reservation.executionId)
        every { reservations.releaseUnstarted(any(), any()) } throws DataAccessResourceFailureException("down")
        every { executions.findById(workspaceId, reservation.executionId) } throws DataAccessResourceFailureException("down")

        start().shouldBeInstanceOf<Outcome.Refused>().code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
    }

    @Test
    fun `a refused release whose record cannot be read is 503, not the 202 an absent row would give`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws
            PipelineConcurrencyLimitException(LimitScope.GLOBAL, 1)
        every { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) } returns false
        every { executions.findById(workspaceId, reservation.executionId) } throws DataAccessResourceFailureException("down")

        start().shouldBeInstanceOf<Outcome.Refused>().code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
    }

    @Test
    fun `an unanswered release with a CONFIRMED terminal row gives that row's normal answer`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws IllegalStateException("late failure")
        every { reservations.releaseUnstarted(any(), any()) } throws DataAccessResourceFailureException("down")
        every { executions.findById(workspaceId, reservation.executionId) } returns record(ExecutionStatus.ABORTED)

        val refused = start().shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 409
        refused.code shouldBe PipelineErrorCodes.Execution.ABORTED
        refused.details["execution_id"] shouldBe reservation.executionId.toString()
    }

    @Test
    fun `a finished run whose record cannot be read is 503, never a 202 for a run that ended`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } returns
            ExecutionResult(reservation.executionId, ExecutionStatus.FAILED, emptyList(), null, Instant.EPOCH, Instant.EPOCH, 0)
        every { executions.findById(workspaceId, reservation.executionId) } throws DataAccessResourceFailureException("down")

        start().shouldBeInstanceOf<Outcome.Refused>().code shouldBe PipelineErrorCodes.Limits.IDEMPOTENCY_RESERVATION_UNAVAILABLE
        verify(exactly = 0) { reservations.releaseUnstarted(any(), any()) }
    }

    @Test
    fun `an unwritable execution record is released and answered 503 dependency_unavailable`() {
        coEvery { runner.run(request, workspaceId, ExecutionTrigger.ENDPOINT, true, any()) } throws
            ExecutionRecordUnwritableException(reservation.executionId)
        every { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) } returns true

        val refused = start().shouldBeInstanceOf<Outcome.Refused>()

        refused.status shouldBe 503
        refused.code shouldBe PipelineErrorCodes.Endpoint.DEPENDENCY_UNAVAILABLE
        verify(exactly = 1) { reservations.releaseUnstarted(reservation.executionId, reservation.ownerToken) }
        audit.outcomes() shouldBe listOf(PublishedMutationLauncher.OUTCOME_STARTED, PublishedMutationLauncher.OUTCOME_NOT_STARTED)
    }

    private fun start(): Outcome = launcher.start(endpoint, principal, reservation, request, TIMEOUT_S, PAGE_ROWS)

    private fun record(status: ExecutionStatus): ExecutionRecord =
        ExecutionRecord(
            executionId = reservation.executionId,
            pipelineId = reservation.pipelineId,
            pipelineVersion = 1,
            status = status,
            parametersJson = "{}",
            executedBy = principal.userId,
            triggeredVia = ExecutionTrigger.ENDPOINT,
        )

    private fun counted(outcome: String): Double =
        meters
            .find(PublishedMutationLauncher.METER)
            .tags("method", "POST", "outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    /** In-memory [AuditEventSink] — the recorded effect is the assertion. */
    private class RecordingAudit : AuditEventSink {
        val rows = mutableListOf<Map<String, Any?>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += details
        }

        fun outcomes(): List<Any?> = rows.map { it["outcome"] }
    }

    private companion object {
        const val TIMEOUT_S = 5
        const val PAGE_ROWS = 10
        const val KEY_ID = "dpk_LAUNCHER01"
    }
}
