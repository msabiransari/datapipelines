package co.datapipelines.mcp

import co.datapipelines.application.endpoints.CurrentVersion
import co.datapipelines.application.endpoints.EndpointBodyContract
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointPinValidator
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * #786 Skill — `endpoints_create` carries the authored exact pin (`pipeline_version`, REQUIRED; the input schema itself
 * is pinned against mcp-server.md §6.2.23 by `McpToolSurfaceSpecDriftTest`). The tool hands the pin to the ONE native
 * creation path unchanged, refuses a call without it (or below 1) before anything is written, and on a deployment with
 * authoring disabled the native path answers `endpoint.authoring_disabled` — there is nothing to release there.
 *
 * #1040: an authored method round-trips — the `method` argument reaches the native path, the answer carries the route's
 * method, its exact pin (`pipeline_version`) and the pinned version's `request_body` contract — and a call without one
 * is the legacy GET route.
 */
class EndpointsCreateToolTest {
    private val ctx = McpFixtures.ctx()

    @Test
    fun `the exact pipeline_version reaches the native creation path unchanged`() {
        val publishing = mockk<EndpointPublishService>(relaxed = true)
        runCatching {
            EndpointsTools.CreateTool(publishing).call(
                McpArguments(
                    mapOf(
                        "path" to "/finance/v1/revenue",
                        "pipeline" to "finance/revenue",
                        "pipeline_version" to 4,
                        "timeout_seconds" to 60,
                    ),
                ),
                ctx,
            )
        }
        verify(exactly = 1) { publishing.create(ctx.principal, "/finance/v1/revenue", "finance/revenue", 4, 60, "") }
    }

    @Test
    fun `an authored POST route round-trips its method, exact pin and body contract`() {
        // #1040: the method reaches the ONE native creation path, and the answer shows what an author must see before
        // releasing — the method, the exact pin and the pinned version's body mode with the transformers that read it.
        val publishing = mockk<EndpointPublishService>()
        val created = endpoint("/lending/v1/applications", EndpointMethod.POST, "lending/apply", 3)
        every {
            publishing.create(ctx.principal, "/lending/v1/applications", "lending/apply", 3, null, "", EndpointMethod.POST)
        } returns created
        every { publishing.bodyContract(created) } returns
            EndpointBodyContract(EndpointBodyContract.Mode.REQUIRED, listOf(EndpointBodyContract.Reader("shape", "lending/shape.py@2")))

        val answer =
            EndpointsTools.CreateTool(publishing).call(
                McpArguments(
                    mapOf("path" to "/lending/v1/applications", "pipeline" to "lending/apply", "pipeline_version" to 3, "method" to "post"),
                ),
                ctx,
            )

        answer shouldBe
            mapOf(
                "path" to "/lending/v1/applications",
                "method" to "POST",
                "pipeline" to "lending/apply",
                "pipeline_version" to 3,
                "timeout_seconds" to 30,
                "description" to "",
                "request_body" to
                    mapOf("mode" to "required", "readers" to listOf(mapOf("node_id" to "shape", "template" to "lending/shape.py@2"))),
                "enabled" to true,
                "path_variables" to emptyList<String>(),
                "url" to "/api/lending/v1/applications",
            )
    }

    @Test
    fun `a call without method creates the legacy GET route, and a method the surface does not publish creates nothing`() {
        val publishing = mockk<EndpointPublishService>()
        val created = endpoint("/finance/v1/revenue", EndpointMethod.GET, "finance/revenue", 4)
        every { publishing.create(ctx.principal, "/finance/v1/revenue", "finance/revenue", 4, null, "", EndpointMethod.GET) } returns
            created
        every { publishing.bodyContract(created) } returns EndpointBodyContract(EndpointBodyContract.Mode.NONE, emptyList())
        val tool = EndpointsTools.CreateTool(publishing)

        val answer =
            tool.call(McpArguments(mapOf("path" to "/finance/v1/revenue", "pipeline" to "finance/revenue", "pipeline_version" to 4)), ctx)

        @Suppress("UNCHECKED_CAST")
        (answer as Map<String, Any?>).let {
            it["method"] shouldBe "GET"
            it["request_body"] shouldBe mapOf("mode" to "none", "readers" to emptyList<Any>())
        }
        val patch = mapOf("path" to "/finance/v1/revenue", "pipeline" to "finance/revenue", "pipeline_version" to 4, "method" to "PATCH")
        shouldThrow<McpError> { tool.call(McpArguments(patch), ctx) }.message shouldContain "PATCH"
        verify(exactly = 1) { publishing.create(any(), any(), any(), any(), any(), any(), any()) }
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

    private fun endpoint(
        path: String,
        method: EndpointMethod,
        pipeline: String,
        version: Int,
    ) = PublishedEndpoint.of(
        id = UUID.nameUUIDFromBytes("$method $path".toByteArray()),
        workspaceId = UUID.fromString("00000000-0000-0000-0000-000000001040"),
        pathPattern = path,
        isEnabled = true,
        createdBy = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        current = CurrentVersion(1, PipelineVersionStatus.DRAFT, EndpointVersionBody(pipeline, version, 30, "")),
        method = method,
    )

    private companion object {
        const val MAX_DEPTH = 5
    }
}
