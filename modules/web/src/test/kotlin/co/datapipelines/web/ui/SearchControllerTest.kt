package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.executor.ExecutionRecord
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutionStatus
import co.datapipelines.executor.ExecutionTrigger
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

/**
 * [SearchController] / [SearchBrowseModel] — the palette's answers, not its markup
 * (`SearchPartialRenderTest` renders the fragment). What is pinned here is the contract the
 * shell promises: the query is answered INSIDE the session's workspace and role (the
 * executions fork is the history screen's, verbatim), each group caps at 8 with a truthful
 * `hasMore`, a blank query fetches nothing, an ambiguous status word is not a status filter,
 * and a pasted pipeline id prefix finds its pipeline the way the name search cannot.
 */
class SearchControllerTest {
    private val pipelines = mockk<PipelineService>()
    private val templates = mockk<TemplateRepository>()
    private val executions = mockk<ExecutionRepository>()
    private val pipelineNames = mockk<PipelineNames>().also { every { it.lookup(any(), any()) } returns emptyMap() }
    private val controller = SearchController(SearchBrowseModel(pipelines, templates, executions, pipelineNames))

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val model = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(workspaceAdmin: Boolean) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    emptySet(),
                    AuthMethod.OIDC,
                    workspace =
                        WorkspaceContext(
                            workspaceId,
                            "acme",
                            if (workspaceAdmin) WorkspaceRole.WORKSPACE_ADMIN else WorkspaceRole.VIEWER,
                        ),
                ),
                null,
                emptyList(),
            )
    }

    private fun pipeline(
        name: String,
        id: UUID = UUID.randomUUID(),
    ): PipelineRecord = PipelineRecord(id, name, name.substringAfterLast('/'), "", UUID.randomUUID(), 1, Instant.EPOCH, Instant.EPOCH)

    private fun template(id: String) =
        Template(
            id = id,
            version = 1,
            dialect = Dialect.POSTGRES,
            displayName = id,
            description = "",
            body = "SELECT 1",
            createdAt = Instant.EPOCH,
            createdBy = userId,
        )

    private fun execution(startedAt: Instant) =
        ExecutionRecord(
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
            pipelineVersion = 1,
            status = ExecutionStatus.SUCCESS,
            parametersJson = "{}",
            executedBy = userId,
            triggeredVia = ExecutionTrigger.UI,
            startedAt = startedAt,
        )

    @Suppress("SameParameterValue")
    private fun ask(q: String): ExtendedModelMap {
        controller.results(model, q)
        return model
    }

    @Test
    fun `a query answers inside the session's workspace and the admin sees the workspace's runs`() {
        authenticate(workspaceAdmin = true)
        val hit = pipeline("nyc/mobility/revenue")
        every { pipelines.list(workspaceId, any(), query = "revenue") } returns listOf(hit)
        every { templates.list(workspaceId, q = "revenue", limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        // No status contains "revenue", the candidate list is exactly one pipeline, and the
        // admin fork asks findAll — the history screen's own authorization decision.
        every { executions.findAll(workspaceId, hit.id, null, limit = 9) } returns emptyList()

        val m = ask("revenue")

        m["asked"] shouldBe true
        m["pipelineHits"] shouldBe listOf(hit)
        verify { executions.findAll(workspaceId, hit.id, null, limit = 9) }
    }

    @Test
    fun `a viewer's executions come from their own runs, never the workspace's`() {
        authenticate(workspaceAdmin = false)
        val hit = pipeline("nyc/mobility/revenue")
        every { pipelines.list(workspaceId, any(), query = "revenue") } returns listOf(hit)
        every { templates.list(workspaceId, q = "revenue", limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        every { executions.findByUser(workspaceId, userId, hit.id, null, limit = 9) } returns emptyList()

        ask("revenue")

        // The VIEWER fork: findByUser names the principal, never the workspace alone —
        // "a second surface over the same rows must not be a wider one".
        verify { executions.findByUser(workspaceId, userId, hit.id, null, limit = 9) }
    }

    @Test
    fun `each group caps at 8 and reports hasMore from the overflow row`() {
        authenticate(workspaceAdmin = true)
        val nine = (1..9).map { pipeline("nyc/mobility/p$it") }
        every { pipelines.list(workspaceId, any(), query = "nyc") } returns nine
        every { templates.list(workspaceId, q = "nyc", limit = 9) } returns (1..9).map { template("acme/t$it") }
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        every { executions.findAll(eq(workspaceId), any(), null, limit = 9) } returns emptyList()

        val m = ask("nyc")

        (m["pipelineHits"] as List<*>).size shouldBe 8
        (m["templateHits"] as List<*>).size shouldBe 8
        m["pipelinesMore"] shouldBe true
        m["templatesMore"] shouldBe true
    }

    @Test
    fun `no match in any group renders the one-sentence empty state`() {
        authenticate(workspaceAdmin = true)
        every { pipelines.list(workspaceId, any(), query = "zzz") } returns emptyList()
        every { templates.list(workspaceId, q = "zzz", limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        every { executions.findAll(workspaceId, null, null, limit = 9) } returns emptyList()

        val m = ask("zzz")

        m["asked"] shouldBe true
        m["empty"] shouldBe true
    }

    @Test
    fun `a blank query fetches nothing at all`() {
        controller.results(model, "   ")

        model["asked"] shouldBe false
        verify { pipelines wasNot Called }
        verify { templates wasNot Called }
        verify { executions wasNot Called }
    }

    @Test
    fun `one status named filters by it`() {
        authenticate(workspaceAdmin = true)
        every { pipelines.list(workspaceId, any(), query = "failed") } returns emptyList()
        every { templates.list(workspaceId, q = "failed", limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        val failed = List(2) { execution(Instant.EPOCH.plusSeconds(it.toLong())) }
        every { executions.findAll(workspaceId, null, ExecutionStatus.FAILED, limit = 9) } returns failed

        val m = ask("failed")

        m["statusMatch"] shouldBe ExecutionStatus.FAILED
        (m["executionHits"] as List<*>).size shouldBe 2
    }

    @Test
    fun `a word several statuses contain is not a status filter - it falls through to pipelines`() {
        authenticate(workspaceAdmin = true)
        // "r" is contained by RUNNING and ABORTED — naming NEITHER.
        every { pipelines.list(workspaceId, any(), query = "r") } returns emptyList()
        every { templates.list(workspaceId, q = "r", limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        every { executions.findAll(workspaceId, null, null, limit = 9) } returns emptyList()

        val m = ask("r")

        m["statusMatch"] shouldBe null
        verify(exactly = 0) { executions.findAll(workspaceId, null, any<ExecutionStatus>(), any(), any(), any(), any()) }
    }

    @Test
    fun `a pasted pipeline id prefix finds the pipeline the name search cannot`() {
        authenticate(workspaceAdmin = true)
        val byId = pipeline("nyc/mobility/revenue", id = UUID.randomUUID())
        val needle = byId.id.toString().take(8)
        every { pipelines.list(workspaceId, any(), query = needle) } returns emptyList()
        every { templates.list(workspaceId, q = needle, limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns listOf(pipeline("other/thing"), byId)
        every { executions.findAll(workspaceId, byId.id, null, limit = 9) } returns emptyList()

        val m = ask(needle)

        m["pipelineMatch"] shouldBe byId
        verify { executions.findAll(workspaceId, byId.id, null, limit = 9) }
    }

    @Test
    fun `executions merge across the matching pipelines, newest first, capped at 8`() {
        authenticate(workspaceAdmin = true)
        val a = pipeline("nyc/a")
        val b = pipeline("nyc/b")
        every { pipelines.list(workspaceId, any(), query = "nyc") } returns listOf(a, b)
        every { templates.list(workspaceId, q = "nyc", limit = 9) } returns emptyList()
        every { pipelines.list(workspaceId, any()) } returns emptyList()
        val older = execution(Instant.EPOCH)
        val newer = execution(Instant.EPOCH.plusSeconds(60))
        every { executions.findAll(workspaceId, a.id, null, limit = 9) } returns listOf(older)
        every { executions.findAll(workspaceId, b.id, null, limit = 9) } returns listOf(newer)

        val m = ask("nyc")

        (m["executionHits"] as List<*>) shouldBe listOf(newer, older)
    }
}
