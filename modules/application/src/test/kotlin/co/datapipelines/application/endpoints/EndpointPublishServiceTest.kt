package co.datapipelines.application.endpoints

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * Publishing (§4.2) — the ONE validated path REST, MCP and promotion share.
 *
 * The ORDER of the preconditions is the thing most worth pinning. They run cheapest and
 * most-specific first, so an author who has broken two things at once is told about the one they
 * can act on: "that path is malformed" rather than "that pipeline has no released version". An
 * implementation that reordered them would still refuse every bad publish and would still pass a
 * suite that only asserted "it was refused" — so each case here asserts WHICH refusal came back.
 */
class EndpointPublishServiceTest {
    private val endpoints = mockk<PublishedEndpointRepository>()
    private val pipelines = mockk<PipelineService>()
    private val pipelineRepository = mockk<PipelineRepository>()
    private val registry = mockk<EndpointRegistry>(relaxed = true)
    private val audit = mockk<AuditEventSink>(relaxed = true)

    private fun service(readOnly: Boolean = true) =
        EndpointPublishService(
            endpoints = endpoints,
            pipelines = pipelines,
            pipelineRepository = pipelineRepository,
            readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
            registry = registry,
            audit = audit,
            timeouts = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300),
        ).also { if (!readOnly) stubPipeline(writes = true) }

    @Test
    fun `a malformed path is refused before the pipeline is even looked up`() {
        // Cheapest first: no repository call should be needed to reject '/Bad'.
        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/Bad Path", "any", null, "") }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID },
            { verify(exactly = 0) { pipelineRepository.findByName(any(), any()) } },
        )
    }

    @Test
    fun `an unknown pipeline is refused as not found`() {
        every { pipelineRepository.findByName(WORKSPACE, "ghost") } returns null

        shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/b", "ghost", null, "") }
            .code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `a pipeline whose current version is a DRAFT is refused as not released`() {
        // §5.1's rule met at publish: an endpoint serves the latest RELEASED version, so
        // publishing over something that has never been released would create a URL that can
        // only ever answer 503.
        stubPipeline(status = PipelineVersionStatus.DRAFT)

        shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/b", PIPELINE_NAME, null, "") }
            .code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED
    }

    @Test
    fun `a freshly created pipeline - never released, no pointer at all - is refused as not released`() {
        // The D55 shape, which is now the state EVERY new pipeline starts in: `current_version` is
        // null, so there is no current-version row to read at all. The publish path must refuse it
        // by the same catalogued code, and the message must name the way forward.
        every { pipelineRepository.findByName(WORKSPACE, PIPELINE_NAME) } returns
            PipelineRecord(
                id = PIPELINE_ID,
                name = PIPELINE_NAME,
                displayName = "Revenue",
                description = "",
                ownerId = ACTOR,
                currentVersion = null,
                isDeleted = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { pipelines.findCurrentVersion(WORKSPACE, PIPELINE_ID) } returns null

        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/b", PIPELINE_NAME, null, "") }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED },
            { refused.message.shouldContain("Release it from the UI first") },
        )
    }

    @Test
    fun `a pipeline that writes is refused, naming the node`() {
        stubPipeline(writes = true)

        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/b", PIPELINE_NAME, null, "") }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY },
            { refused.details["node_id"] shouldBe "load" },
            { refused.message.orEmpty() shouldContain "side-effect-free" },
        )
    }

    @Test
    fun `a path variable the version does not declare is refused, listing what it does declare`() {
        stubPipeline()

        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/{ghost}", PIPELINE_NAME, null, "") }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_VARIABLE_UNKNOWN },
            { refused.message.orEmpty() shouldContain "borough" },
        )
    }

    @Test
    fun `a path variable that IS declared publishes, and extra declared parameters are fine`() {
        // A SUBSET check, not equality: `start_date` is declared and unbound by the path, and it
        // comes from the query string instead.
        stubPipeline()
        val stored = slot<PublishedEndpoint>()
        every { endpoints.insert(capture(stored)) } answers { stored.captured }

        service().publish(principal(), "/nyc/{borough}", PIPELINE_NAME, null, "revenue")

        assertAll(
            { stored.captured.pathPattern shouldBe "/nyc/{borough}" },
            { stored.captured.pathVariables shouldBe listOf("borough") },
            { stored.captured.description shouldBe "revenue" },
        )
    }

    @Test
    fun `the timeout is clamped, and an omitted one takes the configured default`() {
        stubPipeline()
        val stored = slot<PublishedEndpoint>()
        every { endpoints.insert(capture(stored)) } answers { stored.captured }

        val timeouts =
            listOf(null to 30, 0 to 1, 5 to 5, 9_999 to 300).map { (requested, expected) ->
                service().publish(principal(), "/nyc/{borough}", PIPELINE_NAME, requested, "")
                stored.captured.timeoutSeconds to expected
            }

        assertAll(timeouts.map { (actual, expected) -> { actual shouldBe expected } })
    }

    @Test
    fun `a successful publish invalidates the registry AFTER the write, and is audited`() {
        // Order matters: invalidating before the row is written would let a peer reload the
        // pre-write state and cache it again.
        stubPipeline()
        every { endpoints.insert(any()) } answers { firstArg() }

        service().publish(principal(), "/nyc/{borough}", PIPELINE_NAME, null, "")

        verify(exactly = 1) { registry.invalidate() }
        verify(exactly = 1) { audit.log("endpoint.published", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unpublishing another workspace's endpoint reports not-found rather than forbidden`() {
        // A URL is global, but managing one is not — and a refusal must not disclose that an
        // endpoint exists in a workspace the caller cannot see.
        every { endpoints.findByPath("/theirs") } returns
            endpoint(workspaceId = UUID.fromString("defa0000-0000-0000-0000-0000000000ff"))

        assertAll(
            { service().unpublish(principal(), "/theirs") shouldBe false },
            { verify(exactly = 0) { endpoints.deleteByPath(any()) } },
        )
    }

    @Test
    fun `unpublishing an endpoint of the caller's workspace removes it and invalidates`() {
        every { endpoints.findByPath("/mine") } returns endpoint()
        every { endpoints.deleteByPath("/mine") } returns true

        assertAll(
            { service().unpublish(principal(), "/mine") shouldBe true },
            { verify(exactly = 1) { registry.invalidate() } },
            { verify(exactly = 1) { audit.log("endpoint.unpublished", any(), any(), any(), any(), any()) } },
        )
    }

    private fun stubPipeline(
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
        writes: Boolean = false,
    ) {
        val record =
            PipelineRecord(
                id = PIPELINE_ID,
                name = PIPELINE_NAME,
                displayName = "Revenue",
                description = "",
                ownerId = ACTOR,
                currentVersion = 1,
                isDeleted = false,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { pipelineRepository.findByName(WORKSPACE, PIPELINE_NAME) } returns record
        every { pipelines.findCurrentVersion(WORKSPACE, PIPELINE_ID) } returns
            PipelineVersionDetail(
                pipelineId = PIPELINE_ID,
                version = 1,
                status = status,
                bodyHash = "hash",
                createdAt = Instant.EPOCH,
                createdBy = ACTOR,
                releasedAt = null,
                releasedBy = null,
                updatedBy = null,
                updatedAt = Instant.EPOCH,
            )
        every { pipelines.findExecutable(WORKSPACE, record, 1) } returns
            PipelineService.ExecutablePipeline(record, 1, "{}", body(writes))
    }

    private fun body(writes: Boolean) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = PIPELINE_NAME,
            displayName = "Revenue",
            description = "",
            settings = PipelineSettings(),
            parameters =
                mapOf(
                    "borough" to Parameter(LogicalType.STRING, required = true),
                    "start_date" to Parameter(LogicalType.DATE),
                ),
            nodes =
                buildList {
                    add(node("revenue", NodeType.DQL, NodeOutput.Caller))
                    if (writes) add(node("load", NodeType.DML, null))
                },
        )

    private fun node(
        id: String,
        type: NodeType,
        output: NodeOutput?,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = "pg",
        template = TemplateRef("test/$id.sql", 1),
        output = output,
        dependsOn = emptyList(),
    )

    private fun endpoint(workspaceId: UUID = WORKSPACE) =
        PublishedEndpoint.of(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = "/mine",
            pipelineId = PIPELINE_ID,
            timeoutSeconds = 30,
            description = "",
            isEnabled = true,
            createdBy = ACTOR,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun principal() =
        AuthenticatedPrincipal(
            userId = ACTOR,
            email = "a@b.c",
            displayName = "A",
            scopes = setOf(Scope.AUTHOR),
            authMethod = AuthMethod.API_KEY,
            keyId = "dpk_ABCDEFGHIJKL",
            workspaceName = "default",
            workspace = WorkspaceContext(WORKSPACE, "default"),
        )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val PIPELINE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e5")
        const val PIPELINE_NAME = "nyc/mobility/revenue_by_borough"
        const val MAX_DEPTH = 5
    }
}
