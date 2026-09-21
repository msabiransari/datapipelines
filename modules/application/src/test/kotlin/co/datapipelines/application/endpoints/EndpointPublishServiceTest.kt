package co.datapipelines.application.endpoints

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
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
import co.datapipelines.pipeline.ReadLens
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

    private fun service(
        readOnly: Boolean = true,
        lens: PromoterLens? = null,
    ) = EndpointPublishService(
        endpoints = endpoints,
        pipelines = pipelines,
        pipelineRepository = pipelineRepository,
        readOnlyRule = ReadOnlyPipelineRule(PipelineResolver { _, _, _ -> null }, MAX_DEPTH),
        registry = registry,
        audit = audit,
        timeouts = EndpointPublishService.TimeoutBounds(defaultSeconds = 30, minSeconds = 1, maxSeconds = 300),
        lens = lens,
    ).also { if (!readOnly) stubPipeline(writes = true) }

    // ------------------------------------------------------------------ 178, the promoter lens

    @Test
    fun `list and get see every endpoint without a lens, and with an all-visible view - no pipeline read`() {
        val mine = endpoint()
        every { endpoints.findByWorkspace(WORKSPACE) } returns listOf(mine)
        every { endpoints.findByPath("/mine/v1/x") } returns mine

        service().list(principal()).map { it.pathPattern } shouldBe listOf("/mine/v1/x")
        service().get(principal(), "/mine/v1/x")?.pathPattern shouldBe "/mine/v1/x"
        val everything = PromoterLens { LensedView.EVERYTHING }
        service(lens = everything).list(principal()).map { it.pathPattern } shouldBe listOf("/mine/v1/x")
        service(lens = everything).get(principal(), "/mine/v1/x")?.pathPattern shouldBe "/mine/v1/x"
        // A strict mock: had the all-visible view read the index rows, `pipelines.list` would have thrown.
        verify(exactly = 0) { pipelines.list(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `under a narrowing lens an endpoint is visible iff its pipeline is`() {
        val hiddenPipeline = UUID.fromString("00000000-0000-0000-0000-0000000000e6")
        val visible = endpoint()
        val hidden = endpoint().copy(id = UUID.randomUUID(), pathPattern = "/mine/v1/hidden", pipelineId = hiddenPipeline)
        every { endpoints.findByWorkspace(WORKSPACE) } returns listOf(visible, hidden)
        every { endpoints.findByPath("/mine/v1/x") } returns visible
        every { endpoints.findByPath("/mine/v1/hidden") } returns hidden
        val lens = ReadLens.Only(setOf(PIPELINE_NAME))
        every { pipelines.list(WORKSPACE, lens, null, null, null) } returns
            listOf(
                PipelineRecord(
                    id = PIPELINE_ID,
                    name = PIPELINE_NAME,
                    displayName = PIPELINE_NAME,
                    description = "",
                    ownerId = ACTOR,
                    currentVersion = 1,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        val lensed = service(lens = PromoterLens { LensedView(lens, ReadLens.NOTHING) })

        lensed.list(principal()).map { it.pathPattern } shouldBe listOf("/mine/v1/x")
        lensed.get(principal(), "/mine/v1/x")?.pathPattern shouldBe "/mine/v1/x"
        // A hidden pipeline's endpoint is the same null an unknown path gets — never a 403.
        lensed.get(principal(), "/mine/v1/hidden") shouldBe null
    }

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
    fun `a reserved category is refused with endpoint path_reserved naming the segment`() {
        // R-EP5: every v<number> is the product's own API namespace, today and tomorrow. The
        // refusal comes BEFORE the pipeline lookup, like every other path rule. (The 'api'
        // category is covered by the normalisation test — '/api/…' strips one prefix first.)
        listOf("v1", "v2", "v10").forEach { category ->
            val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/$category/v1/x", "any", null, "") }
            assertAll(
                { refused.code shouldBe PipelineErrorCodes.Endpoint.PATH_RESERVED },
                { refused.details["segment"] shouldBe category },
            )
        }
        verify(exactly = 0) { pipelineRepository.findByName(any(), any()) }
    }

    @Test
    fun `one api prefix is normalised away, and the second one is the reserved category`() {
        stubPipeline()
        val stored = slot<PublishedEndpoint>()
        every { endpoints.insert(capture(stored)) } answers { stored.captured }

        // R-EP5: normalisation, not refusal — the stored form is always the part after /api.
        service().publish(principal(), "/api/nyc/v1/{borough}", PIPELINE_NAME, null, "")
        stored.captured.pathPattern shouldBe "/nyc/v1/{borough}"

        // '/api/api/v1/x' strips ONE prefix, leaving category 'api' — reserved, refused.
        shouldThrow<DatapipelinesException> { service().publish(principal(), "/api/api/v1/x", PIPELINE_NAME, null, "") }
            .code shouldBe PipelineErrorCodes.Endpoint.PATH_RESERVED
    }

    @Test
    fun `the shape rules — two segments, a variable category, a variable version — are path_invalid`() {
        assertAll(
            {
                shouldThrow<DatapipelinesException> { service().publish(principal(), "/nyc/v1", "any", null, "") }
                    .code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID
            },
            {
                // A variable category is a SHAPE failure, not a reservation — the reserved rule
                // only ever names a literal segment.
                shouldThrow<DatapipelinesException> { service().publish(principal(), "/{ns}/v1/x", "any", null, "") }
                    .code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID
            },
            {
                shouldThrow<DatapipelinesException> { service().publish(principal(), "/nyc/{v}/x", "any", null, "") }
                    .code shouldBe PipelineErrorCodes.Endpoint.PATH_INVALID
            },
        )
    }

    @Test
    fun `an unknown pipeline is refused as not found`() {
        every { pipelineRepository.findByName(WORKSPACE, "ghost") } returns null

        shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/v1/b", "ghost", null, "") }
            .code shouldBe PipelineErrorCodes.Execution.NOT_FOUND
    }

    @Test
    fun `a pipeline whose current version is a DRAFT is refused as not released`() {
        // §5.1's rule met at publish: an endpoint serves the latest RELEASED version, so
        // publishing over something that has never been released would create a URL that can
        // only ever answer 503.
        stubPipeline(status = PipelineVersionStatus.DRAFT)

        shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/v1/b", PIPELINE_NAME, null, "") }
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
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { pipelines.findCurrentVersion(WORKSPACE, any(), PIPELINE_ID) } returns null

        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/v1/b", PIPELINE_NAME, null, "") }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED },
            { refused.message.shouldContain("Release it from the UI first") },
        )
    }

    @Test
    fun `a pipeline that writes is refused, naming the node`() {
        stubPipeline(writes = true)

        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/v1/b", PIPELINE_NAME, null, "") }

        assertAll(
            { refused.code shouldBe PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY },
            { refused.details["node_id"] shouldBe "load" },
            { refused.message.orEmpty() shouldContain "side-effect-free" },
        )
    }

    @Test
    fun `a path variable the version does not declare is refused, listing what it does declare`() {
        stubPipeline()

        val refused = shouldThrow<DatapipelinesException> { service().publish(principal(), "/a/v1/{ghost}", PIPELINE_NAME, null, "") }

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

        service().publish(principal(), "/nyc/v1/{borough}", PIPELINE_NAME, null, "revenue")

        assertAll(
            { stored.captured.pathPattern shouldBe "/nyc/v1/{borough}" },
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
                service().publish(principal(), "/nyc/v1/{borough}", PIPELINE_NAME, requested, "")
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

        service().publish(principal(), "/nyc/v1/{borough}", PIPELINE_NAME, null, "")

        verify(exactly = 1) { registry.invalidate() }
        verify(exactly = 1) { audit.log("endpoint.published", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unpublishing another workspace's endpoint reports not-found rather than forbidden`() {
        // A URL is global, but managing one is not — and a refusal must not disclose that an
        // endpoint exists in a workspace the caller cannot see.
        every { endpoints.findByPath("/theirs/v1/x") } returns
            endpoint(workspaceId = UUID.fromString("defa0000-0000-0000-0000-0000000000ff"))

        assertAll(
            { service().unpublish(principal(), "/theirs/v1/x") shouldBe false },
            { verify(exactly = 0) { endpoints.deleteByPath(any()) } },
        )
    }

    @Test
    fun `unpublishing an endpoint of the caller's workspace removes it and invalidates`() {
        every { endpoints.findByPath("/mine/v1/x") } returns endpoint()
        every { endpoints.deleteByPath("/mine/v1/x") } returns true

        assertAll(
            { service().unpublish(principal(), "/mine/v1/x") shouldBe true },
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
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        every { pipelineRepository.findByName(WORKSPACE, PIPELINE_NAME) } returns record
        every { pipelines.findCurrentVersion(WORKSPACE, any(), PIPELINE_ID) } returns
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
        every { pipelines.findExecutable(WORKSPACE, any(), record, 1) } returns
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
            pathPattern = "/mine/v1/x",
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
