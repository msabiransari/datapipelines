package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointAuthorizer
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointMatcher
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointRequestValidator
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceLiveness
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.web.config.EndpointsProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * 180 (D15, roles design §3.5) — the RESOURCE half of deactivation on the serve path: an
 * endpoint whose OWN workspace is deactivated is unknown, byte-for-byte the same refusal an
 * unmatched path gets (the 404 rule, auth.md §11A.1), whoever is calling. Before 180 this
 * held only because a key PINNED to that workspace was refused at validation; a `user` key
 * of another workspace reached the authorizer and was told `key_not_bound` (403), which
 * confirms the endpoint exists. The check runs before the pipeline is even resolved, so the
 * registry serves nothing about it.
 */
class PublishedEndpointDeactivatedWorkspaceTest {
    private val workspaceId = UUID.randomUUID()
    private val otherWorkspaceId = UUID.randomUUID()
    private val endpoint =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/nyc/v1/revenue",
            pipelineId = UUID.randomUUID(),
            timeoutSeconds = 30,
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
    private val bindings = mockk<EndpointKeyBindingRepository>()

    private fun service(workspaceActive: Boolean) =
        PublishedEndpointServeService(
            registry = registry,
            bindings = bindings,
            authorizer = EndpointAuthorizer(),
            readOnlyRule = mockk<ReadOnlyPipelineRule>(),
            pipelines = pipelines,
            runner = mockk(),
            resultStore = mockk(),
            resultUrls = { "https://dp.test/api/v1/executions/$it/result" },
            resultConfig = ResultConfig(),
            endpointsProperties = EndpointsProperties(),
            audit = mockk(relaxed = true),
            authoring = AuthoringGuard(enabled = true),
            scope = CoroutineScope(Dispatchers.Default),
            workspaceLiveness = WorkspaceLiveness { it == workspaceId && workspaceActive },
        )

    /** A `user` key of ANOTHER (active) workspace — the caller the pre-180 gap answered 403 to. */
    private val foreignUserKey =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "agent@other.com",
            displayName = "Agent",
            scopes = setOf(Scope.EXECUTE),
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_FOREIGNKEY01",
            workspaceName = "other",
            workspace = WorkspaceContext(otherWorkspaceId, "other", WorkspaceRole.AUTHOR),
        )

    private val request =
        EndpointRequestValidator.Request(
            pathVariables = emptyMap(),
            queryParameters = emptyMap(),
            accept = "application/json",
            resultTtlSecondsHeader = null,
            resultPageRowsHeader = null,
        )

    @Test
    fun `an endpoint of a deactivated workspace is refused exactly like an unknown path, before its pipeline is read`() {
        val service = service(workspaceActive = false)

        val deactivated = service.serve("/nyc/v1/revenue", foreignUserKey, request)
        val unknown = service.serve("/nyc/v1/nothing-here", foreignUserKey, request)

        deactivated.shouldBeInstanceOf<PublishedEndpointServeService.Outcome.Refused>()
        deactivated.status shouldBe 404
        deactivated.code shouldBe PipelineErrorCodes.Endpoint.NOT_FOUND
        deactivated shouldBe unknown
        verify(exactly = 0) { pipelines.findRecord(any(), any(), any()) }
        verify(exactly = 0) { bindings.findByPrefixes(any()) }
    }

    @Test
    fun `the same endpoint in an active workspace is resolved past the liveness gate`() {
        val service = service(workspaceActive = true)
        every { pipelines.findRecord(workspaceId, any(), endpoint.pipelineId) } returns null

        val outcome = service.serve("/nyc/v1/revenue", foreignUserKey, request)

        // Past the gate: the next stage (the pointer) is what answered, not the 404 rule.
        outcome.shouldBeInstanceOf<PublishedEndpointServeService.Outcome.Refused>()
        outcome.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED
    }
}
