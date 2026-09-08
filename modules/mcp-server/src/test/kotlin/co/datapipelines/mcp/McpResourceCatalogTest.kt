package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** §7.3: fixed page size, opaque cursor, stable order, 24h execution window, scope filtering. */
class McpResourceCatalogTest {
    private val pipelines = mockk<PipelineRepository>()
    private val templates = mockk<TemplateRepository>()
    private val datasources = mockk<DatasourceRegistry>()
    private val executions = mockk<ExecutionRepository>()
    private val now: Instant = Instant.parse("2026-08-09T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val ctx = McpFixtures.ctx(Scope.READ)

    private val catalog = McpResourceCatalog(pipelines, templates, datasources, executions, clock)

    /**
     * The constant rows every listing now opens with (095): the skill and one row per
     * reference file. Derived from the packaged skill, never a literal — the reference set
     * grows, and a hand-typed copy here would be the exact drift the skill's own tools.md
     * exists to prevent.
     */
    private val skillUris: List<String> =
        listOf(McpResourceUri.skill()) + SkillDocs.references.keys.map { McpResourceUri.skillReference(it) }

    private fun emptyWorld() {
        every { pipelines.findAll(any(), null) } returns emptyList()
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `the page size is fixed at 100 and paging continues with an opaque cursor`() {
        val many =
            (1..250).map {
                McpFixtures.pipelineRecord(id = UUID.fromString("11111111-0000-0000-0000-%012d".format(it)), name = "p$it")
            }
        every { pipelines.findAll(any(), null) } returns many
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val first = catalog.list(ctx, null)
        val second = catalog.list(ctx, first.nextCursor)
        val third = catalog.list(ctx, second.nextCursor)

        // 250 pipelines + the always-present `datapipelines://datasources` collection URI (§7.1)
        // + the constant docs rows (095).
        val total = 251 + skillUris.size

        assertAll(
            { first.resources.size shouldBe McpResourceCatalog.PAGE_SIZE },
            { first.nextCursor.shouldNotBeNull() },
            { second.resources.size shouldBe McpResourceCatalog.PAGE_SIZE },
            { third.resources.size shouldBe total - 2 * McpResourceCatalog.PAGE_SIZE },
            // The last page omits nextCursor — its presence is the only "there is more" signal.
            { third.nextCursor.shouldBeNull() },
            // No entity is served twice across the run.
            {
                (first.resources + second.resources + third.resources).map { it.uri() }.toSet().size shouldBe total
            },
        )
    }

    @Test
    fun `enumeration order is docs, then pipelines, then templates, then datasources, then executions`() {
        every { pipelines.findAll(any(), null) } returns listOf(McpFixtures.pipelineRecord())
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns listOf(McpFixtures.template())
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns listOf(McpFixtures.datasource())
        every { executions.findByUser(any(), McpFixtures.USER, any(), any(), any(), any(), any(), any()) } returns
            listOf(McpFixtures.executionRecord(startedAt = now.minusSeconds(60)))

        val page = catalog.list(ctx, null)

        page.resources.map { it.uri() } shouldContainExactly
            skillUris +
            listOf(
                "datapipelines://pipelines/${McpFixtures.PIPELINE_ID}",
                "datapipelines://templates/test/revenue.sql",
                "datapipelines://datasources",
                "datapipelines://datasources/pg-prod",
                "datapipelines://executions/${McpFixtures.EXECUTION_ID}",
            )
    }

    @Test
    fun `only executions from the last 24 hours are enumerated`() {
        every { pipelines.findAll(any(), null) } returns emptyList()
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), McpFixtures.USER, any(), any(), any(), any(), any(), any()) } returns
            listOf(
                McpFixtures.executionRecord(startedAt = now.minusSeconds(3_600)),
                McpFixtures.executionRecord(executionId = UUID.randomUUID(), startedAt = now.minusSeconds(90_000)),
            )

        val page = catalog.list(ctx, null)

        // The datasource collection URI (§7.1) and the docs rows are always present; only one
        // execution is in the window.
        page.resources.map { it.uri() } shouldContainExactly
            skillUris + listOf("datapipelines://datasources", "datapipelines://executions/${McpFixtures.EXECUTION_ID}")
    }

    @Test
    fun `the listing is scoped to the caller's own executions`() {
        every { pipelines.findAll(any(), null) } returns emptyList()
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), McpFixtures.OTHER_USER, any(), any(), any(), any(), any(), any()) } returns emptyList()

        val page = catalog.list(McpFixtures.ctx(Scope.READ, userId = McpFixtures.OTHER_USER), null)

        page.resources.none { it.uri().startsWith("datapipelines://executions/") } shouldBe true
    }

    @Test
    fun `descriptors carry a name, description and mime type`() {
        every { pipelines.findAll(any(), null) } returns listOf(McpFixtures.pipelineRecord())
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns listOf(McpFixtures.template())
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val page = catalog.list(ctx, null)
        val entities = page.resources.drop(skillUris.size)

        assertAll(
            { page.resources[0].name() shouldBe "skill" },
            { page.resources[0].mimeType() shouldBe McpResourceCatalog.MIME_MARKDOWN },
            // Every reference advertises its own H1, so a client picking one from the listing
            // is choosing on the file's actual subject rather than on its file name.
            { page.resources[1].description() shouldContain "reference of the datapipelines skill" },
            { entities[0].name() shouldBe "monthly_revenue" },
            { entities[0].mimeType() shouldBe McpResourceCatalog.MIME_JSON },
            { entities[0].description().shouldNotBeNull() },
            { entities[1].mimeType() shouldBe McpResourceCatalog.MIME_FREEMARKER_SQL },
        )
    }

    @Test
    fun `a cursor the server did not issue is invalid params`() {
        emptyWorld()

        assertAll(
            { shouldThrow<McpError> { catalog.list(ctx, "not-base64!!") }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS },
            {
                shouldThrow<McpError> {
                    val forged = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"k":"users","o":0}""".toByteArray())
                    catalog.list(ctx, forged)
                }.jsonRpcError.code() shouldBe McpArguments.INVALID_PARAMS
            },
        )
    }

    @Test
    fun `the cursor is opaque - clients see a token, not a position`() {
        val cursor = McpResourceCursor("pipelines", 100)

        assertAll(
            { cursor.encode() shouldStartWith "eyJ" },
            { McpResourceCursor.decode(cursor.encode(), McpResourceCatalog.KINDS) shouldBe cursor },
        )
    }

    /**
     * F3: the `read` floor is asserted, not inherited from `Scope.READ` happening to be ordinal 0.
     * A key with no scopes must not read every pipeline body through the resource surface.
     */
    @Test
    fun `a key holding no scope cannot list resources`() {
        emptyWorld()

        val error = shouldThrow<McpError> { catalog.list(McpToolContext(McpFixtures.principal(), McpFixtures.CORRELATION_ID), null) }

        assertAll(
            { error.jsonRpcError.code() shouldBe McpArguments.FORBIDDEN },
            { error.jsonRpcError.message() shouldContain "auth.scope.insufficient" },
        )
    }

    /**
     * F9: one page scans each unbounded source at most once, **probes included**.
     *
     * A full page is what exposes it: the walk slices the kind, then `nextPosition` probes the same
     * kind for a 101st row, so an un-memoized `findAll()` ran at least twice per page.
     */
    @Test
    fun `a single page scans the pipeline table only once`() {
        // Exactly enough to FILL one page beside the constant docs rows — a full page is what
        // makes the walk probe for a 101st row, which is the double-scan this asserts against.
        every { pipelines.findAll(any(), null) } returns
            (1..McpResourceCatalog.PAGE_SIZE - skillUris.size).map {
                McpFixtures.pipelineRecord(id = UUID.fromString("11111111-0000-0000-0000-%012d".format(it)), name = "p$it")
            }
        every { templates.list(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        catalog.list(ctx, null)

        verify(exactly = 1) { pipelines.findAll(any(), null) }
        verify(exactly = 1) { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) }
    }

    @Test
    fun `a cursor can land in the middle of the templates kind`() {
        every { pipelines.findAll(any(), null) } returns emptyList()
        every { templates.list(any(), any(), any(), any(), 5, any()) } returns listOf(McpFixtures.template(id = "t6"))
        every { templates.list(any(), any(), any(), any(), 0, any()) } returns listOf(McpFixtures.template(id = "t1"))
        every { datasources.listVisible(null, McpFixtures.WORKSPACE_ID) } returns emptyList()
        every { executions.findByUser(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        val page = catalog.list(ctx, McpResourceCursor(McpResourceUri.TEMPLATES, 5).encode())

        page.resources.first().uri() shouldBe "datapipelines://templates/t6"
    }

    @Test
    fun `an empty instance lists nothing and offers no cursor`() {
        emptyWorld()

        val page = catalog.list(ctx, null)

        assertAll(
            // Only the docs rows and the collection URI §7.1 always defines — no pipelines,
            // templates or executions.
            { page.resources.map { it.uri() } shouldContainExactly skillUris + listOf("datapipelines://datasources") },
            { page.nextCursor.shouldBeNull() },
        )
    }
}
