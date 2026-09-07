package co.datapipelines.web.ui

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 080 — the v2 editor: the mock's top bar, the canvas chrome (hint, legend, minimap,
 * controls), and the four-tab dock (Details | Results | Errors | Events) that the 065
 * inspector overlay moved into. The Details tab's SQL section keeps the swap target the
 * htmx.ajax loader in init.js fills and the highlighter it depends on.
 */
class PipelineEditorRenderTest {
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

    /**
     * The floor's render assertion: the editor markup carries the four tabs and NO
     * overlay root. The inspector's scrim/panel/script are gone for good — a template
     * edit that reintroduces them trips here.
     */
    @Test
    fun `the dock renders four tabs - Details, Results, Errors, Events - and there is no overlay root`() {
        val html = render()

        html shouldContain "class=\"pe-dock\""
        html shouldContain "role=\"tablist\""
        html shouldContain "dock.selectTab('details')"
        html shouldContain "dock.selectTab('results')"
        html shouldContain "dock.selectTab('errors')"
        html shouldContain "dock.selectTab('events')"
        html shouldContain "id=\"pe-pane-details\""
        html shouldContain "id=\"pe-pane-results\""
        html shouldContain "id=\"pe-pane-errors\""
        html shouldContain "id=\"pe-pane-events\""

        // The 065 inspector overlay is deleted, not hidden: no scrim, no panel, no script.
        html shouldNotContain "pe-inspector-scrim"
        html shouldNotContain "pe-details-panel"
        html shouldNotContain "inspector.open"
        html shouldNotContain "/js/pipeline-editor/inspector.js"
        // The state machines the page binds to are the ones that load.
        html shouldContain "/js/pipeline-editor/dock.js"
        html shouldContain "/js/pipeline-editor/events.js"
    }

    /**
     * 065's rule, kept: a chevron collapse/restore pair and NO close. The reported
     * defect was that the result panel's × lost the pane with no way back short of
     * re-running; a dock whose only contraction is the collapse cannot reproduce it.
     */
    @Test
    fun `the dock keeps the collapse chevron and has no close`() {
        val html = render()

        html shouldContain "aria-label=\"Collapse dock\""
        html shouldContain "aria-label=\"Restore dock\""
        html shouldContain "dock.toggleCollapse()"
        html shouldNotContain "aria-label=\"Close results\""
        html shouldNotContain "resultPanel.visible = false"
    }

    /**
     * Details (080 §B — the 065 inspector as a tab): the empty state, the per-type
     * meta rows, the SQL swap target and its client-side fallback for the node types
     * that have no SQL, and the Open-template link.
     */
    @Test
    fun `the Details tab carries the meta grid, the SQL swap target and the non-SQL definition pane`() {
        val html = render()

        html shouldContain "Select a node to see its source, template, output and last run."
        html shouldContain "detailsMeta(selectedNode)"
        html shouldContain "class=\"pe-kv\""
        html shouldContain "id=\"pe-node-sql\""
        html shouldContain "id=\"pe-node-def\""
        html shouldContain "isSqlNode(selectedNode)"
        html shouldContain "definitionHtml(selectedNode)"
        // §8.2/§9.6: the template link goes to the route that exists, /templates/editor?name=.
        html shouldContain "/templates/editor?name='"
    }

    /**
     * The badges the tabs carry: Results shows the row count on success, Errors turns
     * danger above zero, Events counts the stream live.
     */
    @Test
    fun `the tab badges are wired - results rows, errors danger count, events live count`() {
        val html = render()

        html shouldContain "dock.resultsRows"
        html shouldContain "dock.errors.length > 0 ? 'pe-dock-count-err'"
        html shouldContain "eventsLog.count()"
        html shouldContain "dock.resultsStale"
    }

    /**
     * The 057 failure record lives in the Errors tab — one entry per failed node,
     * newest last, through the same failureView view-model.
     */
    @Test
    fun `the failure record renders in the Errors tab, not inside the results body`() {
        val html = render()

        html shouldNotContain "resultPanel.failure"
        html shouldContain "failureView(entry.record)"
        html shouldContain "entry in dock.errors"
        html shouldContain "No failures in this run."
    }

    @Test
    fun `the page loads the SQL highlighter`() {
        render() shouldContain "/js/pipeline-editor/sql-highlight.js"
    }

    /**
     * The canvas chrome (080 §A): the card overlay's vendored script, the icon system,
     * the keyboard hint, the legend, the minimap and the view controls — fit included,
     * with its F shortcut advertised.
     */
    @Test
    fun `the canvas chrome is wired - overlay script, icons, hint, legend, minimap, controls`() {
        val html = render()

        html shouldContain "/vendor/cytoscape/cytoscape-node-html-label.js"
        html shouldContain "lucide-sprite.svg#maximize"
        html shouldContain "lucide-sprite.svg#zoom-in"
        html shouldContain "aria-label=\"Graph view controls\""
        html shouldContain "graph.fitToView()"
        html shouldContain "graph.resetView()"
        html shouldContain "/vendor/design-system/icons.css"
        html shouldContain "ds-icon ds-icon-md"
        html shouldContain "class=\"pe-hint\""
        html shouldContain "class=\"pe-legend\""
        html shouldContain "id=\"pe-minimap\""
    }

    @Test
    fun `the result grid renders on the shared table with the frozen pager bindings`() {
        val html = render()

        html shouldContain "<table class=\"ds-table\">"
        html shouldNotContain "pe-result-table\""
        // 027b C is restyled, not rewired: the pager keeps its exact bindings.
        html shouldContain "resultPanel.prevPage()"
        html shouldContain "resultPanel.nextPage()"
        html shouldContain "resultPanel.hasPrev"
        html shouldContain "resultPanel.hasNext"
    }

    /**
     * 080 §D — the top bar: back to Pipelines, the crumb (folder muted + name bold),
     * the version chip, the run status and the primary Execute.
     */
    @Test
    fun `the top bar is the mock's - back link, crumb, version chip, status, execute`() {
        val html = render()

        html shouldContain "class=\"pe-topbar\""
        // 085 §B: the &larr; entity is the sprite's arrow-left now (xs — a text-link glyph).
        html shouldContain "lucide-sprite.svg#arrow-left"
        html shouldContain " Pipelines</a>"
        html shouldContain "crumbPath()"
        html shouldContain "crumbName()"
        html shouldContain "pe-vchip"
        html shouldContain "statusClass()"
        html shouldContain "runStatus.text"
        html shouldContain "executePipeline()"
    }

    /**
     * 080 §C — every link INTO a pipeline editor is a full document load. This page
     * itself renders none (its links all leave the editor), so the assertion is the
     * non-vacuous twin of PipelineExplorerRenderTest's: any such href that ever
     * appears here must carry hx-boost="false".
     */
    @Test
    fun `any link into a pipeline editor on this page is a full document load`() {
        val html = render()
        val editorLinks = Regex("""<a [^>]*href="/pipelines/[^"]+/editor"[^>]*>""").findAll(html).map { it.value }.toList()
        editorLinks.forEach { it shouldContain "hx-boost=\"false\"" }
        // The leaving-the-editor links that must never be boosted either: the downloads
        // (a file response cannot swap into #app-main) and the template editor.
        html shouldContain "hx-boost=\"false\""
    }

    private fun render(): String =
        engine.process(
            "pipelines/editor",
            webContext().apply {
                setVariable("_csrf", mapOf("token" to "t"))
                setVariable("workspaceHeaderFragment", "")
                setVariable("workspaceOptions", emptyList<Any>())
                setVariable("activeWorkspace", "acme")
                setVariable("activeTheme", "saas")
                setVariable("authenticated", true)
                setVariable("currentPath", "/pipelines")
                setVariable("hasDraft", false)
                setVariable("draftVersion", null)
                setVariable("draftHash", null)
                setVariable("releasedVersion", 1)
                setVariable(
                    "pipelineJson",
                    """{"id":"00000000-0000-0000-0000-000000000001","name":"p",""" +
                        """"display_name":"P","version":1,"parameters":{},"nodes":[]}""",
                )
            },
        )

    private fun webContext(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )
}
