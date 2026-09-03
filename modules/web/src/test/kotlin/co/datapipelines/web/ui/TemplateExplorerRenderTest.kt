package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.templates.TemplateVersionSummary
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
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
import java.time.Instant
import java.util.UUID

/**
 * Render-level guard for the templates screen's EXPLORER layout (058): tree LEFT, the
 * selected template RIGHT — "just like Windows file explorer", the owner's spec.
 *
 * The one property this class exists to pin is the layout's whole point:
 *
 *  - **A selection populates the detail pane WITHOUT re-rendering the tree.** Pinned at the
 *    fragment-contract level, where it is true by construction: the leaf's swap targets
 *    `#template-detail` with `innerHTML`, and the detail fragment itself contains NO tree
 *    markup, NO tree swap target and NO out-of-band swap — there is nothing in what a
 *    selection returns that could touch the tree's DOM, whatever else changes.
 *
 * Around it, the two-pane shape and the wiring the keyboard layer depends on: both panes
 * present, ARIA tree/listbox roles, `data-editor-url` (what Enter navigates to), and the
 * quiet states (nothing selected; a name that no longer exists).
 *
 * Engine infra mirrors [TemplateTreeRenderTest] (same WebContext shape, comments stripped
 * for the absence assertions — the markup documents its own absences at length).
 */
class TemplateExplorerRenderTest {
    // ------------------------------------------------------------- the two panes

    @Test
    fun `the screen renders a tree pane and a detail pane, and the tree pane wraps the swap root`() {
        val html = render("templates/list") { fillPage() }

        // Both panes, tree left and detail right (the layout classes carry the grid).
        html shouldContain "class=\"tplx-body\""
        html shouldContain "tplx-tree\" id=\"template-tree-pane\""
        html shouldContain "class=\"tplx-detail\""
        // The left pane is a STABLE container AROUND the long-standing swap root: filters
        // and levels replace #template-list-wrapper's contents, never the pane itself.
        html shouldContain "id=\"template-tree-pane\""
        html shouldContain "id=\"template-list-wrapper\""
        // Nothing selected: a quiet empty state, not a blank panel.
        html shouldContain "id=\"template-detail\""
        html shouldContain "Select a template"
        // The keyboard layer is on the page (up/down/left/right/Enter own selection and
        // focus; expansion alone stayed htmx + <details>).
        html shouldContain "src=\"/js/template-explorer.js\""
    }

    @Test
    fun `the root level is a tree and a nested level is a group under its folder`() {
        val root = render("partials/template-tree-level") { fillLevel() }
        val nested =
            render("partials/template-tree-level") {
                fillLevel()
                setVariable("prefix", "acme/finance")
                setVariable("levelId", TemplateBrowseModel.levelId("acme/finance"))
            }

        root shouldContain "role=\"tree\""
        root shouldContain "aria-label=\"Templates\""
        root shouldNotContain "role=\"group\""
        nested shouldContain "role=\"group\""
        nested shouldNotContain "aria-label=\"Templates\""
        // Every row is a treeitem; the list items themselves are presentation only.
        root shouldContain "role=\"treeitem\""
        root shouldContain "role=\"none\""
    }

    @Test
    fun `a leaf SELECTS - its versions swap into the detail pane and nothing in the tree moves`() {
        val html = render("partials/template-tree-level") { fillLevel() }

        // The selection swap: innerHTML into the detail pane. No outerHTML on a tree
        // element, no OOB, no second tree target — the pane's DOM cannot move.
        html shouldContain "hx-target=\"#template-detail\""
        html shouldContain "hx-swap=\"innerHTML\""
        html shouldNotContain "hx-swap-oob"
        // Selection state is client-owned (aria-selected), seeded false server-side.
        html shouldContain "aria-selected=\"false\""
        // Enter opens the editor; the URL it navigates to is rendered on the row.
        html shouldContain "data-editor-url=\"/templates/editor?name=legacy_flat.sql\""
        // A rapid keyboard sweep must not race stale detail loads into the pane: the LAST
        // selection replaces the in-flight one.
        html shouldContain "hx-sync=\"#template-detail:replace\""
    }

    @Test
    fun `the detail fragment cannot touch the tree - no tree markup, no tree target, no OOB`() {
        val html = render("partials/template-detail") { fillDetail() }

        // The whole point of the layout, pinned at the fragment-contract level: what a
        // selection returns holds NOTHING that could alter the left pane's DOM.
        html shouldNotContain "tpl-tree"
        html shouldNotContain "template-list-wrapper"
        html shouldNotContain "template-tree-pane"
        html shouldNotContain "prefix="
        html shouldNotContain "hx-swap-oob"
        html shouldNotContain "tpl-folder"
        // ...and it IS the selected template: header, badges, Open in editor, versions.
        html shouldContain "class=\"tplx-detail-path\""
        html shouldContain DEEP_PATH
        html shouldContain "title=\"$DEEP_PATH\""
        html shouldContain "Open in editor"
        html shouldContain "/templates/editor?name=$DEEP_PATH"
        html shouldContain "<span class=\"ds-badge ds-badge-default\">sql</span>"
        html shouldContain "<span class=\"ds-badge ds-badge-default\">POSTGRES</span>"
        html shouldContain "RELEASED"
        html shouldContain "DRAFT"
    }

    @Test
    fun `a search result SELECTS exactly like a tree leaf`() {
        val html = render("partials/template-search") { fillSearch() }

        html shouldContain "role=\"listbox\""
        html shouldContain "aria-label=\"Search results\""
        html shouldContain "role=\"option\""
        html shouldContain "hx-target=\"#template-detail\""
        html shouldContain "hx-swap=\"innerHTML\""
        html shouldContain "data-editor-url=\"/templates/editor?name=$DEEP_PATH\""
    }

    @Test
    fun `a name that no longer names a live template renders a quiet not-found detail`() {
        val html =
            render("partials/template-detail") {
                fillDetail()
                setVariable("template", null)
            }

        html shouldContain "Template not found"
        html shouldContain "it may have been deleted"
        // No header of badges for a template that is not there — and still no tree markup.
        html shouldNotContain "tplx-detail-path"
        html shouldNotContain "Open in editor"
        html shouldNotContain "tpl-tree"
    }

    // ------------------------------------------------------------------ fixtures

    private fun WebContext.fillLevel() {
        setVariable("searching", false)
        setVariable("prefix", "")
        setVariable("levelId", TemplateBrowseModel.ROOT_LEVEL_ID)
        setVariable(
            "folders",
            listOf(
                TemplateFolderView("acme", "acme", 12, TemplateBrowseModel.levelId("acme")),
            ),
        )
        setVariable("foldersTruncated", false)
        setVariable("templates", listOf(template("legacy_flat.sql")))
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 1)
        setVariable("selectedDialect", "")
        setVariable("selectedType", "")
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillSearch() {
        setVariable("searching", true)
        setVariable("templates", listOf(template(DEEP_PATH)))
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("q", "revenue")
        setVariable("selectedDialect", "")
        setVariable("selectedType", "")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 4)
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillDetail() {
        setVariable("templateId", DEEP_PATH)
        setVariable("template", template(DEEP_PATH))
        setVariable(
            "versions",
            listOf(
                TemplateVersionSummary(DEEP_PATH, 2, Instant.parse("2026-09-02T10:00:00Z"), ACTOR),
                TemplateVersionSummary(DEEP_PATH, 1, Instant.parse("2026-09-01T10:00:00Z"), ACTOR),
            ),
        )
        setVariable("draftVersion", 2)
        setVariable("inUse", mapOf(2 to 1, 1 to 2))
    }

    private fun WebContext.fillPage() {
        fillChrome()
        fillLevel()
        setVariable("q", "")
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("types", TemplateType.WIRE_VALUES)
        setVariable("namePattern", TemplateNameGrammar.pattern)
        setVariable("nameMaxLength", TemplateNameGrammar.maxLength)
        setVariable("nameHint", TemplateNameGrammar.DESCRIPTION)
    }

    private fun WebContext.fillChrome() {
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun template(
        id: String,
        type: TemplateType = TemplateType.SQL,
    ) = Template(
        id = id,
        version = 1,
        type = type,
        dialect = if (type == TemplateType.SQL) Dialect.POSTGRES else null,
        displayName = id,
        description = "Fixture.",
        body = "SELECT 1",
        createdAt = Instant.parse("2026-09-01T10:00:00Z"),
        createdBy = ACTOR,
        status = PipelineVersionStatus.RELEASED,
    )

    /**
     * Renders a view with HTML COMMENTS STRIPPED — same reasoning as
     * [TemplateTreeRenderTest]: the absences this class guards are documented in the
     * markup's own comments, and a comment is not an affordance.
     */
    private fun render(
        view: String,
        fill: WebContext.() -> Unit,
    ): String = COMMENT.replace(engine().process(view, context().apply(fill)), "")

    private fun context(): WebContext =
        WebContext(
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
        )

    private fun engine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    private companion object {
        const val DEEP_PATH = "acme/finance/monthly_revenue"
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
