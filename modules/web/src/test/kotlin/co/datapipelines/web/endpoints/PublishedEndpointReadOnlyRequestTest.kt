package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBinding
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointMatcher
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointVersion
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
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
import co.datapipelines.pipeline.PipelineRepository
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #463 — a published GET endpoint's serve sends the executor a READ-ONLY request: the fact that
 * makes the executor run every DQL datasource statement of the pinned version (and of the children
 * it composes) in the per-lease read-only mode, where the database refuses a write on the dialects
 * that enforce it (dag-executor.md §8.2's table).
 *
 * The fixture is `PublishedEndpointServeAuditOutcomeTest`'s, the version-pinned one (#786), with the
 * audit sink relaxed: this class asserts the captured request, not the audit rows.
 */
class PublishedEndpointReadOnlyRequestTest {
    private val workspaceId = UUID.randomUUID()

    private val runner = mockk<RecordingExecutionRunner>()
    private val resultStore = mockk<ResultStore>()
    private val readOnlyRule = mockk<ReadOnlyPipelineRule>()

    private val endpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/x/v1/p",
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
    private val pipelineRepository = mockk<PipelineRepository>()
    private val versions = mockk<PublishedEndpointVersionRepository>()
    private val pipelineId = UUID.randomUUID()
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
        // #786: the API's current version pins x/p@3 exactly, and carries the serve timeout (1 s).
        every { versions.findCurrent(endpoint.id) } returns
            PublishedEndpointVersion(
                endpointId = endpoint.id,
                version = 1,
                status = PipelineVersionStatus.RELEASED,
                body = EndpointVersionBody("x/p", 3, 1, ""),
                bodyHash = "api-hash",
                createdAt = Instant.EPOCH,
                createdBy = principal.userId,
                releasedAt = Instant.EPOCH,
                releasedBy = principal.userId,
            )
        every { pipelineRepository.findByName(workspaceId, "x/p") } returns record()
        every { pipelineRepository.findVersionDetail(workspaceId, pipelineId, 3) } returns versionDetail()
        every { pipelines.findExecutable(workspaceId, any(), any(), 3) } returns executable()
        val bindings = mockk<EndpointKeyBindingRepository>()
        // #215 B3: an unbound path serves no one, so the key is bound at the endpoint's root node.
        every { bindings.findByPrefixes(any(), any()) } returns
            listOf(EndpointKeyBinding("/x", KEY_ID, workspaceId, UUID.randomUUID(), Instant.now()))
        every { readOnlyRule.check(any(), any()) } returns ValidationResult(emptyList())
        return PublishedEndpointServeService(
            registry = registry,
            versions = versions,
            pipelineRepository = pipelineRepository,
            bindings = bindings,
            authorizer = EndpointAuthorizer(),
            readOnlyRule = readOnlyRule,
            pipelines = pipelines,
            runner = runner,
            resultStore = resultStore,
            resultUrls = { "https://dp.test/api/v1/executions/$it/result" },
            resultConfig = ResultConfig(),
            endpointsProperties = EndpointsProperties(),
            audit = mockk<AuditEventSink>(relaxed = true),
            authoring = AuthoringGuard(enabled = false),
            scope = CoroutineScope(Dispatchers.Default),
            workspaceLiveness = WorkspaceLiveness { true },
        )
    }

    @Test
    fun `the serve request is read-only`() {
        val seen = mutableListOf<ExecuteRequest>()
        coEvery { runner.run(any(), workspaceId, ExecutionTrigger.ENDPOINT) } coAnswers {
            val sent = firstArg<ExecuteRequest>()
            seen += sent
            succeeded(sent.executionId ?: error("the serve request carries no execution id"))
        }
        every { resultStore.describe(any()) } returns view()
        every { resultStore.page(any(), 0, any()) } returns page(UUID.randomUUID())

        val outcome = service().serve("/x/v1/p", principal, request)

        check(outcome is PublishedEndpointServeService.Outcome.Served) { "expected Served, got $outcome" }
        seen.single().readOnly shouldBe true
        seen.single().triggeredVia shouldBe ExecutionTrigger.ENDPOINT
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
            id = pipelineId,
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
            pipelineId = pipelineId,
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
            email = "dpk_servero463@keys.invalid",
            displayName = "serve-read-only",
            authMethod = AuthMethod.API_KEY,
            keyId = KEY_ID,
            workspaceName = "w",
            workspace = WorkspaceContext(workspaceId, "w", WorkspaceRole.VIEWER),
            keyKind = ApiKeyKind.ENDPOINT,
            keyRole = KeyRole.API_CALLER,
        )

    private companion object {
        const val KEY_ID = "dpk_SERVERO463"
    }
}
