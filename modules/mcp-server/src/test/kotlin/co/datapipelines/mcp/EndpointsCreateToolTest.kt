package co.datapipelines.mcp

import co.datapipelines.application.endpoints.EndpointPinValidator
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.spec.McpError
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * #786 Skill — `endpoints_create` carries the authored exact pin (`pipeline_version`, REQUIRED; the input schema itself
 * is pinned against mcp-server.md §6.2.23 by `McpToolSurfaceSpecDriftTest`). The tool hands the pin to the ONE native
 * creation path unchanged, refuses a call without it (or below 1) before anything is written, and on a deployment with
 * authoring disabled the native path answers `endpoint.authoring_disabled` — there is nothing to release there.
 */
class EndpointsCreateToolTest {
    private val ctx = McpFixtures.ctx()

    @Test
    fun `the exact pipeline_version reaches the native creation path unchanged`() {
        val publishing = mockk<EndpointPublishService>(relaxed = true)
        runCatching {
            EndpointsTools.CreateTool(publishing).call(
                McpArguments(mapOf("path" to "/finance/v1/revenue", "pipeline" to "finance/revenue", "pipeline_version" to 4, "timeout_seconds" to 60)),
                ctx,
            )
        }
        verify(exactly = 1) { publishing.create(ctx.principal, "/finance/v1/revenue", "finance/revenue", 4, 60, "") }
    }

    @Test
    fun `a call without pipeline_version, or below 1, is invalid params and nothing is created`() {
        val publishing = mockk<EndpointPublishService>()
        val tool = EndpointsTools.CreateTool(publishing)
        listOf(
            mapOf("path" to "/finance/v1/revenue", "pipeline" to "finance/revenue") to "pipeline_version",
            mapOf("path" to "/finance/v1/revenue", "pipeline" to "finance/revenue", "pipeline_version" to 0) to "1 or greater",
        ).forEach { (args, expected) ->
            shouldThrow<McpError> { tool.call(McpArguments(args), ctx) }.message shouldContain expected
        }
        confirmVerified(publishing)
    }

    @Test
    fun `an authoring-disabled deployment refuses creation with endpoint authoring_disabled`() {
        val authoring = AuthoringGuard(false)
        val endpoints = mockk<PublishedEndpointRepository>()
        val versions = mockk<PublishedEndpointVersionRepository>()
        val pipelineRepository = mockk<PipelineRepository>()
        val publishing =
            EndpointPublishService(
                endpoints = endpoints,
                versions = versions,
                pins =
                    EndpointPinValidator(
                        pipelineRepository = pipelineRepository,
                        pipelines = mockk<PipelineService>(),
                        readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
                        authoring = authoring,
                    ),
                registry = mockk<EndpointRegistry>(),
                audit = mockk<AuditEventSink>(),
                timeouts = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300),
                authoring = authoring,
            )
        val refused =
            shouldThrow<DatapipelinesException> {
                EndpointsTools.CreateTool(publishing).call(
                    McpArguments(mapOf("path" to "/finance/v1/revenue", "pipeline" to "finance/revenue", "pipeline_version" to 2)),
                    ctx,
                )
            }
        refused.code shouldBe PipelineErrorCodes.Endpoint.AUTHORING_DISABLED
        // Strict mocks: the refusal came before any read or write of the entity, the pin or the audit.
        confirmVerified(endpoints, versions, pipelineRepository)
    }

    private companion object {
        const val MAX_DEPTH = 5
    }
}
