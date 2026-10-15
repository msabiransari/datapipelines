package co.datapipelines.web.ui

import co.datapipelines.web.ui.site.REPORT_PROBLEM_URL
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import java.util.UUID

/**
 * #374 — the Parameter Sets pages pinned at the RENDER (DashboardUiRenderTest's mould): the catalog's contract
 * (a row navigates to the workspace, the model decides what renders, the pager keeps q; since #466 no sidebar
 * presentation is left in the fragment — the tree is the REST one), and
 * the workspace page's markup contract — the inert script catalog, no inline script bodies, the role-hidden form,
 * the draft label, the choose-a-version state. The model is where the lens is applied, so "a hidden set is absent
 * from the HTML" is exactly "it is not in the model".
 */
class ParameterSetsRenderTest {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private val setId = UUID.randomUUID()
    private val released = ParameterSetsBrowseModel.ParameterSetLeafView(setId, "acme/geo_filters", "geo_filters", "Geo filters", 3, true)
    private val draft = ParameterSetsBrowseModel.ParameterSetLeafView(UUID.randomUUID(), "acme/wip", "wip", "Wip", 1, false)

    private fun search(
        leaves: List<ParameterSetsBrowseModel.ParameterSetLeafView>,
        q: String,
    ): String =
        engine.process(
            "partials/parameter-set-search",
            webContext("/parameter-sets").apply {
                setVariable("searching", true)
                setVariable("rootId", ParameterSetsBrowseModel.CATALOG_ROOT_ID)
                setVariable("q", q)
                setVariable("parameterSets", leaves)
                setVariable("offset", 0)
                setVariable("hasMore", false)
                setVariable("total", leaves.size)
                setVariable("lensUnavailable", null)
            },
        )

    @Test
    fun `a catalog row is a link to the workspace carrying its id and full path, with drafts marked`() {
        val html = search(listOf(released, draft), "")
        html shouldContain "id=\"parameter-set-list-wrapper\""
        html shouldContain "href=\"/parameter-sets/$setId\""
        html shouldContain "data-leaf-id=\"$setId\""
        html shouldContain "hx-boost=\"true\""
        html shouldContain "title=\"acme/geo_filters\""
        html shouldContain ">v3<"
        html shouldContain "draft v1 pending release"
        // #466: no sidebar presentation is left in the fragment.
        html shouldNotContain "role=\"listbox\""
        html shouldNotContain "role=\"option\""
        html shouldNotContain "scope="
        html shouldNotContain "prefix="
    }

    @Test
    fun `a set the lens hides from the model is absent from the HTML`() {
        // Markup only: the partial's own explanatory comments are not the page's rows.
        val html = search(listOf(released), "").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        html shouldContain "acme/geo_filters"
        html shouldNotContain "wip"
    }

    @Test
    fun `#415 - the catalog's search results are plain links whose pager keeps q`() {
        val html = search(listOf(released), "geo")
        html shouldContain "id=\"parameter-set-list-wrapper\""
        html shouldNotContain "role=\"listbox\""
        html shouldContain "/partials/parameter-sets/tree?q=geo&amp;offset=25"
    }

    @Test
    fun `#415 - a failed search and an empty catalog say different things`() {
        // The catalog's no-hit search: the clear re-fetches the route bare (the box is the page's own).
        val pageMiss = search(emptyList(), "geo")
        pageMiss shouldContain "No parameter sets match your search"
        pageMiss shouldNotContain "data-nav-tree-clear"
        pageMiss shouldContain "hx-get=\"/partials/parameter-sets/tree\""
        // #432: and it names the box it empties (shell.js) and replaces a search still in flight.
        pageMiss shouldContain "data-dp-clear-filters=\"#parameter-set-filter-q\""
        pageMiss shouldContain "hx-sync=\"#parameter-set-filter-q:replace\""
        // The catalog with no query and no rows is the EMPTY state, not a failed search; it says
        // parameter sets are authored through the MCP server and offers no create control.
        val empty = search(emptyList(), "")
        empty shouldContain "No parameter sets yet"
        empty shouldNotContain "match your search"
        empty shouldNotContain "<button"
    }

    private fun workspace(
        canEvaluate: Boolean = true,
        hasBody: Boolean = true,
        draftView: Boolean = false,
        versions: List<ParameterSetsWorkspaceModel.VersionChoice> = emptyList(),
        label: String = "v1 · released · current",
        name: String = "Geo filters",
    ): String =
        engine.process(
            "parameter-sets/workspace",
            webContext("/parameter-sets/$setId").apply {
                setVariable("parameterSetId", setId)
                setVariable("parameterSetName", "acme/geo_filters")
                setVariable("parameterSetDisplayName", name)
                setVariable("navCurrentPath", "acme/geo_filters")
                setVariable("activeTab", "workspace")
                setVariable("hasSelectedBody", hasBody)
                setVariable("viewedIsDraft", draftView)
                setVariable("viewedLabel", label)
                setVariable("versions", versions)
                setVariable("canEvaluate", canEvaluate)
                setVariable("parameterSetJson", "{\"name\":\"acme/geo_filters\"}")
                setVariable("workspaceJson", "{\"viewedVersion\":1}")
            },
        )

    private fun choice(
        version: Int,
        status: co.datapipelines.pipeline.PipelineVersionStatus,
        current: Boolean,
        viewed: Boolean,
    ) = ParameterSetsWorkspaceModel.VersionChoice(
        ParameterSetsUiFixtures.detail(setId, version, status),
        current,
        viewed,
    )

    @Test
    fun `the page declares the sidebar's current leaf by id and full path`() {
        val html = workspace()
        html shouldContain "data-nav-current=\"parameter-sets\""
        html shouldContain "data-nav-current-id=\"$setId\""
        html shouldContain "data-nav-current-path=\"acme/geo_filters\""
    }

    @Test
    fun `every vendor and module script rides in the INERT catalog - the runtime is its last entry and the loader's mount`() {
        val html = workspace()
        // #494: the shell's page-script loader executes an "on-mount" catalog once the page is in place.
        val match =
            Regex(
                "<template id=\"ps-runtime-scripts\" data-page-assets=\"on-mount\">(.*?)</template>",
                RegexOption.DOT_MATCHES_ALL,
            ).find(html)
        val catalog = requireNotNull(match) { "no on-mount ps-runtime-scripts page-asset catalog" }.groupValues[1]
        listOf(
            "dagre.min.js",
            "cytoscape.min.js",
            "cytoscape-dagre.js",
            "datapipelines-dashboard.js",
            "model.js",
            "graph.js",
            "inspector.js",
            "workspace.js",
        ).forEach { catalog shouldContain it }
        catalog.trimEnd() shouldEndWith "<script data-page-mount src=\"/js/parameter-workspace/runtime.js\"></script>"
        val outside = html.replace(catalog, "")
        outside shouldNotContain "datapipelines-dashboard.js"
        outside shouldNotContain "cytoscape"
        outside shouldNotContain "/js/parameter-workspace/"
    }

    @Test
    fun `no script tag carries an inline body - every one is a src or a JSON data block`() {
        val html = workspace()
        val tags = Regex("<script\\b[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        tags.isNotEmpty() shouldBe true
        tags.forEach { t ->
            val open = t.value.substringBefore('>')
            val ok = "src=" in open || "type=\"application/json\"" in open
            ok shouldBe true
        }
        html shouldNotContain " style=\""
        html shouldNotContain " onclick="
    }

    @Test
    fun `an evaluating role gets the form host and a read-only role gets none`() {
        workspace(canEvaluate = true) shouldContain "id=\"ps-form-host\""
        val readOnly = workspace(canEvaluate = false)
        readOnly shouldNotContain "id=\"ps-form-host\""
        readOnly shouldContain "data-ps-read-only"
        // The refusal region exists either way: a malformed block is refused visibly, never silently.
        readOnly shouldContain "id=\"ps-form-error-code\""
    }

    @Test
    fun `a draft is labelled with the warning badge and a released view is not`() {
        workspace(draftView = true, label = "v2 · draft") shouldContain "ds-badge-warning"
        workspace(draftView = false) shouldNotContain "ds-badge-warning"
    }

    @Test
    fun `the version selector links name their exact version and mark the viewed one`() {
        val html =
            workspace(
                versions =
                    listOf(
                        choice(2, co.datapipelines.pipeline.PipelineVersionStatus.RELEASED, current = false, viewed = false),
                        choice(1, co.datapipelines.pipeline.PipelineVersionStatus.RELEASED, current = true, viewed = true),
                    ),
            )
        html shouldContain "/parameter-sets/$setId?version=2"
        html shouldContain "/parameter-sets/$setId?version=1"
        html shouldContain "aria-current=\"page\""
        html shouldContain "v1 — released · current"
    }

    @Test
    fun `no body renders the choose-a-version state with no data block, no form and no graph`() {
        val html =
            workspace(
                hasBody = false,
                canEvaluate = false,
                label = "no version selected",
                versions = listOf(choice(2, co.datapipelines.pipeline.PipelineVersionStatus.RELEASED, current = false, viewed = false)),
            )
        html shouldContain "Choose a version"
        html shouldNotContain "id=\"ps-data\""
        html shouldNotContain "id=\"ps-graph\""
        html shouldNotContain "id=\"ps-form-host\""
        // The workspace block still rides, so the client can refuse to mount rather than guess.
        html shouldContain "id=\"ps-workspace\""
    }

    @Test
    fun `a display name is escaped in the header - the one th-text on user content`() {
        val html = workspace(name = "<img src=x onerror=alert(1)>")
        html shouldNotContain "<img src=x"
        html shouldContain "&lt;img src=x"
    }

    @Test
    fun `the graph is keyboard-reachable and labelled, with a live region for the selection`() {
        val html = workspace()
        html shouldContain "id=\"ps-graph\""
        html shouldContain "tabindex=\"0\""
        html shouldContain "id=\"ps-graph-live\""
        html shouldContain "aria-live=\"polite\""
    }

    private fun webContext(path: String): WebContext {
        val request = MockHttpServletRequest()
        request.requestURI = path
        return WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(request, MockHttpServletResponse()),
        ).withRoles().apply {
            setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
            setVariable("workspaceHeaderFragment", "")
            setVariable("workspaceOptions", emptyList<Any>())
            setVariable("activeWorkspace", "acme")
            setVariable("activeTheme", "saas")
            setVariable("authenticated", true)
            setVariable("currentPath", path)
            setVariable("reportProblemUrl", REPORT_PROBLEM_URL)
        }
    }
}
