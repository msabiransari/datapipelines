package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointMatcher
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecuteRequest
import co.datapipelines.executor.ExecutionResult
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.executor.ResultConfig
import co.datapipelines.executor.ResultPage
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.StoredResultView
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ValidationResult
import co.datapipelines.web.config.EndpointsProperties
import co.datapipelines.web.pipelines.RecordingExecutionRunner
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #192 — the serve audit records the outcome it OBSERVED, not the one it guessed at.
 *
 * Before the fix the single row was written before the await with `outcome` decided by
 * `deferred.isCompleted` — a sample taken before the wait, so a serve that finished within the
 * timeout was recorded as `started` forever. The row's PURPOSE (the key's ticket to read its
 * result later) is preserved; what changes is that a second, terminal row names what actually
 * happened: `completed`, `failed`, or `accepted` (the 202/timeout branch — the execution
 * continues).
 *
 * The sink here is a REAL in-memory recorder, not a strict mock: the property under test is
 * "a terminal row exists with this outcome", and a strict mock would make a MISSING write
 * unobservable (it fails on extra calls, never on absent ones).
 */
class PublishedEndpointServeAuditOutcomeTest {
    private val workspaceId = UUID.randomUUID()

    /** In-memory [AuditEventSink] — the recorded effect is the assertion. */
    private class RecordingAudit : AuditEventSink {
        val rows = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            rows += event to details
        }

        /** The `outcome` of every `endpoint.served` row for [executionId], in write order. */
        fun outcomes(executionId: UUID): List<String> =
            rows
                .filter { it.first == "endpoint.served" && it.second["execution_id"] == executionId.toString() }
                .map { it.second["outcome"] as String }
    }

    private val audit = RecordingAudit()
    private val runner = mockk<RecordingExecutionRunner>()
    private val resultStore = mockk<ResultStore>()
    private val readOnlyRule = mockk<ReadOnlyPipelineRule>()

    private val endpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/x/v1/p",
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 1,
            description = "",
            isEnabled = true,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private val registry =
        mockk<EndpointRegistry> {
            every { matcher() } returns EndpointMatcher(listOf(endpoint))
        }

    private val pipelines = mockk<PipelineService>()
    private val principal = endpointUserKey()
    private val request =
        EndpointRequestValidator.Request(
            pathVariables = emptyMap(),
            queryParameters = emptyMap(),
            accept = "application/json",
            resultTtlSecondsHeader = null,
            resultPageRowsHeader = null,
        )

    private fun service(): PublishedEndpointServeService {
        every { pipelines.findRecord(workspaceId, any(), endpoint.pipelineId) } returns record()
        every { pipelines.findCurrentVersion(workspaceId, any(), endpoint.pipelineId) } returns versionDetail()
        every { pipelines.findExecutable(workspaceId, any(), any(), 3) } returns executable()
        val bindings = mockk<EndpointKeyBindingRepository>()
        // #215 B3: an unbound path serves no one, so the key is bound at the endpoint's root node.
        every { bindings.findByPrefixes(any(), any()) } returns
            listOf(EndpointKeyBinding("/x", KEY_ID, workspaceId, UUID.randomUUID(), Instant.now()))
        every { readOnlyRule.check(any(), any()) } returns ValidationResult(emptyList())
        return PublishedEndpointServeService(
            registry = registry,
            bindings = bindings,
            authorizer = EndpointAuthorizer(),
            readOnlyRule = readOnlyRule,
            pipelines = pipelines,
            runner = runner,
            resultStore = resultStore,
            resultUrls = { "https://dp.test/api/v1/executions/$it/result" },
            resultConfig = ResultConfig(),
            endpointsProperties = EndpointsProperties(),
            audit = audit,
            authoring = AuthoringGuard(enabled = false),
            scope = CoroutineScope(Dispatchers.Default),
            workspaceLiveness = WorkspaceLiveness { true },
        )
    }

    @Test
    fun `a fast serve answers 200 and the audit ends at completed`() {
        // The runner honours the request's execution id — the id the audit rows carry. The
        // result is built INSIDE coAnswers, where the captured request is in hand.
        coEvery { runner.run(any(), workspaceId, ExecutionTrigger.ENDPOINT) } coAnswers {
            succeeded(firstArg<ExecuteRequest>().executionId ?: error("the serve request carries no execution id"))
        }
        every { resultStore.describe(any()) } returns view()
        every { resultStore.page(any(), 0, any()) } returns page(UUID.randomUUID())

        val outcome = service().serve("/x/v1/p", principal, request)

        val served = outcome as? PublishedEndpointServeService.Outcome.Served
        check(served != null) { "expected Served, got $outcome" }
        // The pre-answer row exists for the key's later cursor read...
        audit.outcomes(served.executionId).first() shouldBe "started"
        // ...and the TERMINAL row names what was observed, not what was guessed.
        audit.outcomes(served.executionId).last() shouldBe "completed"
    }

    @Test
    fun `a serve that outlives its timeout answers 202 and the audit ends at accepted`() {
        coEvery { runner.run(any(), workspaceId, ExecutionTrigger.ENDPOINT) } coAnswers { awaitCancellation() }

        val outcome = service().serve("/x/v1/p", principal, request)

        val accepted = outcome as? PublishedEndpointServeService.Outcome.Accepted
        check(accepted != null) { "expected Accepted, got $outcome" }
        audit.outcomes(accepted.executionId) shouldBe listOf("started", "accepted")
    }

    @Test
    fun `a failing pipeline's audit ends at failed`() {
        coEvery { runner.run(any(), workspaceId, ExecutionTrigger.ENDPOINT) } coAnswers {
            failed(firstArg<ExecuteRequest>().executionId ?: error("the serve request carries no execution id"))
        }

        val outcome = service().serve("/x/v1/p", principal, request)

        // The refusal shape is the surface's existing answer for a failed run; what #192 adds
        // is the terminal audit row that says the run FAILED rather than leaving `started`.
        val refused = outcome as? PublishedEndpointServeService.Outcome.Refused
        check(refused != null) { "expected Refused, got $outcome" }
        val executionId = checkNotNull(refused.details["execution_id"]) { "the refusal names no execution" }
        audit.outcomes(UUID.fromString(executionId.toString())).last() shouldBe "failed"
    }

    // ------------------------------------------------------------------ fixture

    /** A SUCCESS result named by the execution id the run request carried. */
    private fun succeeded(executionId: UUID): ExecutionResult =
        ExecutionResult(
            executionId = executionId,
            status = ExecutionStatus.SUCCESS,
            nodeStats = emptyList(),
            resultRef = "exec:${UUID.randomUUID()}",
            startedAt = Instant.now(),
            completedAt = Instant.now(),
            durationMs = 5,
        )

    private fun failed(executionId: UUID): ExecutionResult =
        ExecutionResult(
            executionId = executionId,
            status = ExecutionStatus.FAILED,
            nodeStats = emptyList(),
            resultRef = null,
            startedAt = Instant.now(),
            completedAt = Instant.now(),
            durationMs = 5,
        )

    private fun view(): StoredResultView =
        StoredResultView(
            key = "exec:x",
            executionId = UUID.randomUUID(),
            schema = emptyList(),
            firstPage = emptyList(),
            totalRows = 1,
            bytes = 10,
            expiresAt = Instant.now().plusSeconds(60),
        )

    private fun page(executionId: UUID): ResultPage =
        ResultPage(
            executionId = executionId,
            schema = emptyList(),
            rows = listOf(listOf(1)),
            offset = 0,
            limit = 100,
            totalRows = 1,
            expiresAt = Instant.now().plusSeconds(60),
        )

    private fun record() =
        PipelineRecord(
            id = endpoint.pipelineId,
            name = "x/p",
            displayName = "p",
            description = "",
            ownerId = principal.userId,
            currentVersion = 3,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun versionDetail() =
        PipelineVersionDetail(
            pipelineId = endpoint.pipelineId,
            version = 3,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = "hash",
            createdAt = Instant.EPOCH,
            createdBy = principal.userId,
        )

    private fun executable(): PipelineService.ExecutablePipeline {
        val body =
            """{"name":"x/p","display_name":"p","description":"d","parameters":{},""" +
                """"nodes":[{"id":"n","type":"DQL","source":"s","template":{"id":"t.sql","version":1},"depends_on":[]}]}"""
        return PipelineService.ExecutablePipeline(record(), 3, body, PipelineDeserializer().readOrThrow(body))
    }

    /** An `api_caller` key acting as its own identity (#215), bound at `/x` — the principal that reaches the run. */
    private fun endpointUserKey() =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "dpk_serveaudit01@keys.invalid",
            displayName = "serve-audit",
            authMethod = AuthMethod.API_KEY,
            keyId = KEY_ID,
            workspaceName = "w",
            workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.VIEWER),
            keyKind = ApiKeyKind.ENDPOINT,
            keyRole = KeyRole.API_CALLER,
        )

    private companion object {
        const val KEY_ID = "dpk_SERVEAUDIT01"
    }
}
