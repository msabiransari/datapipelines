package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.LifecycleBucket
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.visualization.ArtifactPage
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardService
import co.datapipelines.web.EVERYTHING_LENS
import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.time.Instant
import java.util.UUID

/**
 * #512 — the dashboards catalog's `q` is the SQL search every other family's box uses, paged in
 * [DashboardService.search] (name, display name or description; case-insensitive; LIKE
 * metacharacters literal — proven for every artifact kind by `ArtifactLifecycleIntegrationTest`),
 * never a name filter over the first 200 rows. These cases pin the MODEL's half: with a needle it
 * asks the service for exactly one page of that needle and reports the service's total, so a
 * match past the 200th row is reachable and the pager is truthful; with no needle it pages the
 * lensed list in SQL under EVERY lens, never the old 200-row read.
 *
 * The doubles are STRICT on purpose and the question is the call's ARGUMENTS: `search` answers
 * only the exact needle, offset and page size the model must send, and `listAll`/`countAll` are
 * unstubbed in the needle cases (the old model's 200-row read finds no answer and throws — and
 * `verify(exactly = 0)` states it again, so the absence is asserted, not only implied).
 */
class DashboardBrowseModelSearchTest {
    private val workspaceId = UUID.randomUUID()
    private val dashboards = mockk<DashboardService>()
    private val model =
        DashboardBrowseModel(dashboards, mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    private val everything = LensedView.EVERYTHING
    private val ui = ExtendedModelMap()

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a needle pages the service search and reports its truthful total past the 200 row read`() {
        val needle = "revenue"
        every { dashboards.search(workspaceId, everything.dashboards, needle, 0, PAGE) } returns
            ArtifactPage(rows(count = PAGE, from = 0), total = TOTAL)

        model.fillList(ui, workspaceId, everything, needle, 0)

        ui["total"] shouldBe TOTAL
        ui["hasMore"] shouldBe true
        ui["offset"] shouldBe 0
        ui["q"] shouldBe needle
        leaves().map { it.name } shouldBe (0 until PAGE).map { "acme/boards/match_$it" }
        verify(exactly = 0) { dashboards.listAll(any(), any(), any(), any()) }
        verify(exactly = 0) { dashboards.countAll(any(), any()) }
    }

    @Test
    fun `the last page of a match set past the 200th row has no next page`() {
        val needle = "revenue"
        every { dashboards.search(workspaceId, everything.dashboards, needle, LAST_OFFSET, PAGE) } returns
            ArtifactPage(rows(count = 1, from = LAST_OFFSET), total = TOTAL)

        model.fillList(ui, workspaceId, everything, needle, LAST_OFFSET)

        ui["total"] shouldBe TOTAL
        ui["hasMore"] shouldBe false
        ui["offset"] shouldBe LAST_OFFSET
        leaves().single().name shouldBe "acme/boards/match_$LAST_OFFSET"
    }

    @Test
    fun `a padded needle is trimmed before the call and a negative offset is page one`() {
        every { dashboards.search(workspaceId, everything.dashboards, "revenue", 0, PAGE) } returns
            ArtifactPage(rows(count = 2, from = 0), total = 2)

        model.fillList(ui, workspaceId, everything, "  revenue ", -5)

        ui["q"] shouldBe "revenue"
        ui["total"] shouldBe 2
        ui["hasMore"] shouldBe false
        leaves() shouldHaveSize 2
    }

    @Test
    fun `a narrowing lens with no needle pages and counts the lensed list - never the 200 row read`() {
        val lens = ReadLens.Only(setOf("acme/boards/match_0", "acme/boards/match_1"))
        val narrowed = LensedView(ReadLens.Everything, ReadLens.Everything, dashboards = lens)
        every { dashboards.listAll(workspaceId, lens, PAGE, PAGE + 1) } returns rows(count = 2, from = 0)
        every { dashboards.countAll(workspaceId, lens) } returns 2

        model.fillList(ui, workspaceId, narrowed, null, PAGE)

        ui["total"] shouldBe 2
        ui["hasMore"] shouldBe false
        ui["offset"] shouldBe PAGE
        ui["q"] shouldBe ""
        leaves() shouldHaveSize 2
        verify(exactly = 0) { dashboards.listAll(any(), any(), 0, MAX_READ) }
    }

    @Test
    fun `the catalog partial renders the matching row and the truthful total through the real model`() {
        authenticate()
        val needle = "quarterly"
        every { dashboards.search(workspaceId, everything.dashboards, needle, 0, PAGE) } returns
            ArtifactPage(listOf(version("acme/boards/q3_numbers", "Quarterly revenue", 2)), total = 1)
        val controller =
            DashboardPartialController(
                mockk(),
                mockk(),
                mockk(),
                EVERYTHING_LENS,
                model,
                dashboards,
            )

        val view = controller.dashboardCatalog(ui, q = needle, offset = null, lifecycle = null)
        val html = render(view, ui)

        // The row is a dashboard whose NAME does not contain the term - only its display name does,
        // which the old name-only filter could never have shown.
        html shouldContain "acme/boards/q3_numbers"
        html shouldContain "data-leaf-id"
        html shouldContain "id=\"dash-list-wrapper\""
        html shouldNotContain "No dashboards match your search"
        ui["total"] shouldBe 1
    }

    /**
     * #580 — a lifecycle bucket takes the page and its total from the service's ONE filtered read
     * (`listIn`, the inventory's rule under every lens), the needle composed; the strict double
     * answers only that call, so the unfiltered `listAll`/`search` path is shown not to run.
     */
    @Test
    fun `a lifecycle bucket pages the filtered read with the needle and the pager carries the bucket`() {
        every { dashboards.listIn(workspaceId, everything.dashboards, LifecycleBucket.WITH_DRAFTS, "revenue", PAGE, PAGE) } returns
            ArtifactPage(rows(count = PAGE, from = PAGE), total = FILTERED_TOTAL)

        val html = render(model.fillWrapper(ui, workspaceId, everything, " revenue ", PAGE, LifecycleBucket.WITH_DRAFTS), ui)

        ui["total"] shouldBe FILTERED_TOTAL
        ui["hasMore"] shouldBe true
        ui["lifecycle"] shouldBe "with-drafts"
        ui["lifecycleLabel"] shouldBe "With drafts"
        ui["lifecycleOptions"] shouldBe CatalogLifecycle.options(CatalogLifecycle.DASHBOARDS)
        leaves() shouldHaveSize PAGE
        html shouldContain "q=revenue&amp;lifecycle=with-drafts&amp;offset=${PAGE * 2}"
        html shouldContain "q=revenue&amp;lifecycle=with-drafts&amp;offset=0"
        verify(exactly = 0) { dashboards.listAll(any(), any(), any(), any()) }
        verify(exactly = 0) { dashboards.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty lifecycle bucket is the filtered empty state with Clear filters, never the workspace's empty state`() {
        every { dashboards.listIn(workspaceId, everything.dashboards, LifecycleBucket.DRAFT_ONLY, null, 0, PAGE) } returns
            ArtifactPage(emptyList(), total = 0)

        val html = render(model.fillWrapper(ui, workspaceId, everything, null, 0, LifecycleBucket.DRAFT_ONLY), ui)

        html shouldContain "No dashboards match: Draft only"
        html shouldContain ">Clear filters</button>"
        html shouldNotContain "No dashboards yet"
        html shouldNotContain ">Clear search</button>"
    }

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme", WorkspaceRole.VIEWER),
                ),
                null,
                emptyList(),
            )
    }

    @Suppress("UNCHECKED_CAST") // the fill's own attribute, typed at its one writer
    private fun leaves() = ui["dashboards"] as List<DashboardBrowseModel.DashboardLeafView>

    private fun rows(
        count: Int,
        from: Int,
    ) = (from until from + count).map { version("acme/boards/match_$it", "Revenue $it", 1) }

    private fun version(
        name: String,
        displayName: String,
        number: Int,
    ): ArtifactVersion<DashboardBody> {
        val id = UUID.randomUUID()
        val at = Instant.parse("2026-10-01T09:00:00Z")
        return ArtifactVersion(
            ArtifactRecord(id, workspaceId, name, displayName, "", number, at, at, UUID.randomUUID()),
            ArtifactVersionDetail(id, number, PipelineVersionStatus.RELEASED, "hash-$number", at, UUID.randomUUID()),
            DashboardBody(displayName = displayName, visualizations = emptyList(), layout = DashboardLayout()),
        )
    }

    private fun render(
        view: String,
        attributes: ExtendedModelMap,
    ): String {
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        val request = MockHttpServletRequest().apply { requestURI = "/partials/dashboards" }
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(request, MockHttpServletResponse()),
            ).withRoles().apply {
                setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
                setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
                attributes.forEach { (key, value) -> setVariable(key, value) }
            }
        return engine.process(view, context)
    }

    private companion object {
        const val PAGE = DashboardBrowseModel.PAGE_SIZE
        const val TOTAL = 201
        const val LAST_OFFSET = 200
        const val FILTERED_TOTAL = 60

        /** The old model's whole-catalog read: [co.datapipelines.visualization.ArtifactRepository.MAX_PAGE_LIMIT]. */
        const val MAX_READ = 200
    }
}
