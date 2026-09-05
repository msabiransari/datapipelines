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
 * The details panel's SQL section (pipeline-editor.md §8): the swap target the
 * htmx.ajax loader in init.js fills, its indicator, and the highlighter script the
 * section depends on — plus, since 065, the panel's own open/close contract and the
 * bottom dock that replaced the losable result panel.
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
     * 065 §C DELIBERATELY REMOVES the "Select a node" empty state this test used to
     * demand. It existed because the drawer was always in the DOM and had to say
     * something before the first selection; the panel is now gated on
     * `inspector.open`, so it does not exist until a node has been chosen and has
     * nothing to be empty about. What replaces the assertion is the gate itself plus
     * the close control — the two halves of "opened from the card, closable" that a
     * template edit could silently drop.
     */
    @Test
    fun `the inspector carries the SQL swap target, its spinner, the open gate and a close control`() {
        val html = render()

        html shouldContain "id=\"pe-node-sql\""
        html shouldContain "id=\"pe-node-sql-spinner\""
        html shouldContain "ds-spinner"
        // The panel exists only while the inspector is open — no always-present drawer,
        // hence no empty state to render before the first selection.
        html shouldContain "x-show=\"inspector.open\""
        html shouldNotContain "Select a node"
        // Close: the × the owner asked for, top-right of the panel header, and the id
        // init.js focuses on open.
        html shouldContain "id=\"pe-details-close\""
        html shouldContain "aria-label=\"Close details\""
        html shouldContain "closeNodeDetails()"
        // The SQL section is the one that grows (§8.3's 40% floor keys on this class).
        html shouldContain "pe-details-section-sql"
    }

    /**
     * 065 §B — the dock. Two tabs, a minimise/restore pair, and NO close: the reported
     * defect was that the result panel's × set `resultPanel.visible = false` and left no
     * way back short of re-running the pipeline. The absence of that binding is the
     * assertion worth keeping; a template edit that reintroduced a close button would be
     * the regression, and it would look perfectly reasonable in a diff.
     */
    @Test
    fun `the dock renders two tabs and a minimise-restore pair, and no close`() {
        val html = render()

        html shouldContain "class=\"pe-dock\""
        html shouldContain "role=\"tablist\""
        html shouldContain "dock.selectTab('results')"
        html shouldContain "dock.selectTab('errors')"
        html shouldContain "aria-label=\"Minimise results\""
        html shouldContain "aria-label=\"Restore results\""
        html shouldContain "dock.minimise()"
        html shouldContain "dock.restore()"
        // There is no close, and nothing may hide the pane by fiat.
        html shouldNotContain "aria-label=\"Close results\""
        html shouldNotContain "resultPanel.visible = false"
        html shouldNotContain "dock.state = 'hidden'"
        // The state machines the page binds to are actually loaded.
        html shouldContain "/js/pipeline-editor/dock.js"
        html shouldContain "/js/pipeline-editor/inspector.js"
    }

    /**
     * 065 §B — the 057 failure record moved OUT of the results body and into the Errors
     * tab. Both halves are pinned: the Results tab no longer references
     * `resultPanel.failure` at all, and the Errors tab renders the record through the
     * same `failureView` view-model the inspector uses.
     */
    @Test
    fun `the failure record renders in the Errors tab, not inside the results body`() {
        val html = render()

        html shouldNotContain "resultPanel.failure"
        html shouldContain "failureView(entry.record)"
        html shouldContain "entry in dock.errors"
        html shouldContain "No failures in this run."
        // The per-NODE view stays where it was — same record, two homes.
        html shouldContain "failureView(nodeErrors[selectedNode.id])"
    }

    @Test
    fun `the page loads the SQL highlighter`() {
        render() shouldContain "/js/pipeline-editor/sql-highlight.js"
    }

    /**
     * 059 §reference/§B: the card overlay's vendored script, the icon sprite the cards
     * and controls draw from, and the keyboard-reachable view controls. If any of these
     * disappears from the template the graph degrades to the empty-box look the owner
     * rejected — this pins the wiring, not the visual.
     *
     * 059b: icons.css joins the wiring pins. The card/toolbar svgs always carried the
     * .ds-icon size classes, but the page never loaded the stylesheet that gives them
     * meaning — every svg rendered at the 300×150 replaced-element default ("the icons
     * are the size of the canvas"). The link is load-bearing, not decorative.
     */
    @Test
    fun `the card overlay script, the icon sprite and the view controls are wired`() {
        val html = render()

        html shouldContain "/vendor/cytoscape/cytoscape-node-html-label.js"
        html shouldContain "lucide-sprite.svg#maximize"
        html shouldContain "lucide-sprite.svg#zoom-in"
        html shouldContain "aria-label=\"Graph view controls\""
        html shouldContain "graph.fitToView()"
        html shouldContain "graph.resetView()"
        // 059b: the icon system's stylesheet, and the toolbar row at md (20px).
        html shouldContain "/vendor/design-system/icons.css"
        html shouldContain "ds-icon ds-icon-md"
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

    @Test
    fun `the panel is grouped into headed sections and the template is a link`() {
        val html = render()

        html shouldContain "Identity"
        html shouldContain "Configuration"
        html shouldContain "Runtime"
        html shouldContain "Failure"
        // §8.2/§9.6: the template field links to the route that exists, /templates/editor?name=.
        html shouldContain "/templates/editor?name='"
        html shouldNotContain "JSON.stringify(selectedNode.output)"
    }

    @Test
    fun `a CALCULATOR node gets its own read-only section, and the SQL and Template fields step aside`() {
        val html = render()

        // 072 §0.6: read-only means read-only. The section shows the four things an author
        // cannot get anywhere else on this screen — the kind, the context key, each input
        // beside what the last run resolved it to, and the computed value — and there is no
        // picker, no form control and no save path anywhere in it (R10 stands).
        html shouldContain "selectedNode.type === 'CALCULATOR'"
        html shouldContain "Context Key"
        html shouldContain "Computed Value"
        html shouldContain "calculatorInputs(selectedNode)"
        html shouldContain "calculatorValue(selectedNode)"
        html shouldContain "pe-calc-input-resolved"

        // The fields that would be empty or false for a calculator are gated OFF, not left
        // to render "—": a Template row on a node that pins no template invites the reader
        // to go looking for one, and the SQL section would sit there loading forever.
        html shouldContain "x-show=\"selectedNode.type !== 'CALCULATOR'\""
        html shouldContain "selectedNode.type !== 'PIPELINE' && selectedNode.type !== 'CALCULATOR'"
    }

    @Test
    fun `the calculator section carries no editing affordance`() {
        // The negative half of R10, asserted rather than assumed. A future edit that adds an
        // input or a select to this section trips here first.
        val section =
            render()
                .substringAfter("<template x-if=\"selectedNode.type === 'CALCULATOR'\">")
                .substringBefore("</template>")

        listOf("<input", "<select", "<textarea", "x-model", "@click").forEach {
            section shouldNotContain it
        }
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
