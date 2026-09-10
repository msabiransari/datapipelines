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
        val nested = render("partials/template-tree-level") { fillNestedLevel() }

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
        // A NESTED level: since 077 a leaf can only sit under a folder (§4.1).
        val html = render("partials/template-tree-level") { fillNestedLevel() }

        // The selection swap: innerHTML into the detail pane. No outerHTML on a tree
        // element, no OOB, no second tree target — the pane's DOM cannot move.
        html shouldContain "hx-target=\"#template-detail\""
        html shouldContain "hx-swap=\"innerHTML\""
        html shouldNotContain "hx-swap-oob"
        // Selection state is client-owned (aria-selected), seeded false server-side.
        html shouldContain "aria-selected=\"false\""
        // Enter opens the editor; the URL it navigates to is rendered on the row.
        html shouldContain "data-editor-url=\"/templates/editor?name=acme/finance/monthly_revenue.sql\""
        // A rapid keyboard sweep must not race stale detail loads into the pane: the LAST
        // selection replaces the in-flight one.
        html shouldContain "hx-sync=\"#template-detail:replace\""
    }

    @Test
    fun `077 - the ROOT level renders folders only, never a leaf`() {
        // §4.1 requires a folder, so nothing sits directly at the root and the fragment has no
        // "leaf at the root" branch left to exercise. Asserted on the RENDERED level rather
        // than on the model, because the branch that is gone lived in the markup: the label
        // used to be `prefix.isEmpty() ? t.id : t.id.substring(...)`. `V12__folder_required.sql`
        // is what makes this true of stored rows and not merely of this fixture.
        val root = render("partials/template-tree-level") { fillLevel() }

        root shouldNotContain "tpl-leaf"
        root shouldNotContain "data-editor-url"
        root shouldContain "tpl-folder"
        // …and a NESTED level still renders its leaves, labelled by their last segment.
        val nested = render("partials/template-tree-level") { fillNestedLevel() }
        nested shouldContain "tpl-leaf"
        nested shouldContain ">monthly_revenue.sql</span>"
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
        html shouldContain ">sql</span>"
        html shouldContain ">POSTGRES</span>"
        html shouldContain "RELEASED"
        html shouldContain "DRAFT"
    }

    @Test
    fun `106 - the templates detail is the pipelines detail's twin - same three regions`() {
        val html = render("partials/template-detail") { fillDetail() }

        html shouldContain "class=\"tplx-detail-header\""
        html shouldContain "class=\"tplx-read\""
        html shouldContain "class=\"tplx-act\""
        html shouldContain ">Overview<"
        html shouldContain ">Used by<"
        html shouldContain "data-tab-panel=\"template-tab-versions\""
        html shouldContain "data-tab-panel=\"template-tab-source\""
        html shouldContain "data-tab-panel=\"template-tab-runs\""
        // Versions AND Source are in the first paint (both are already read); only Runs is lazy.
        html shouldContain "/partials/templates/runs?name="
        html shouldContain "hx-trigger=\"click once\""
        // The path is the eyebrow, the leaf is the title.
        html shouldContain "acme/finance/reports/</p>"
        html shouldContain "monthly_revenue.sql</h2>"
        // The excerpt, and the jump to the full body — never a second copy of the source.
        html shouldContain "class=\"tplx-excerpt\""
        html shouldContain "data-tab-jump=\"template-tab-source\""
        // A template declares no parameter schema; the card says what the body REFERENCES.
        html shouldContain ">References<"
        html shouldContain "start_date"
        html shouldNotContain "style=\""
    }

    @Test
    fun `102 - the templates header opens 101's verbs as dialogs, one destructive at most`() {
        // SUPERSEDES 106's "pointing at the REST routes": the verbs hx-get the §4.3d template
        // twins into #tx-dialog (§9.6: the name travels in the query, never a path segment),
        // and at most ONE destructive renders in the header.
        val html = render("partials/template-detail") { fillDetail() }

        html shouldContain "Release v2…"
        html shouldContain "hx-get=\"/partials/templates/lifecycle/release?name=$DEEP_PATH\""
        html shouldContain "hx-get=\"/partials/templates/lifecycle/discard?name=$DEEP_PATH&amp;version=1\""
        html shouldContain "hx-target=\"#tx-dialog\""
        // The {R,D} shape: Discard of the resolved release, and NO entity purge in the
        // header (the draft's Purge lives on the draft's ROW, which this same render carries).
        html shouldContain "Discard v1…"
        html shouldNotContain "Purge template"
        // No Switch — templates are pinned by version; there is no served pointer to switch.
        html shouldNotContain ">Switch"
        // The fetch path is gone.
        html shouldNotContain "data-verb-url="
        html shouldNotContain "data-confirm="

        val draftOnly =
            render("partials/template-detail") {
                fillDetail()
                setVariable("canDelete", true)
                setVariable("canDiscardCurrent", false)
                setVariable("canPurgeDraftInHeader", false)
            }
        draftOnly shouldContain "Purge template…"
        // (No header-Discard assertion here: this render keeps the version rows, whose own
        // menu legitimately offers Discard — the header's flag is what canDiscardCurrent drove.)
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

    /**
     * The ROOT level: folders and nothing else (077, §4.1).
     *
     * It used to carry `template("legacy_flat.sql")` — a leaf sitting at the root, which the
     * grammar now forbids and `TemplateBrowseModel` no longer even queries for. Every
     * assertion about a LEAF therefore moved onto [fillNestedLevel].
     */
    /**
     * 114 §B — the template twin of the pipeline ladder. Same rule, same reason: Release is
     * the promoter's (D-R2), the three destructive verbs are the author's (D-R4), and a
     * viewer's detail carries neither. Templates have no Switch — they are pinned by version,
     * so there is no served pointer to move.
     */
    @Test
    fun `114 - the template header renders Release only for a promoter and Purge only for an author`() {
        val admin = render("partials/template-detail") { fillDetail() }
        admin shouldContain "data-verb=\"template-release\""

        val author =
            render("partials/template-detail") {
                fillDetail()
                withRoles(canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "author")
            }
        author shouldNotContain "data-verb=\"template-release\""

        val promoter =
            render("partials/template-detail") {
                fillDetail()
                withRoles(canAuthor = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "promoter")
            }
        promoter shouldContain "data-verb=\"template-release\""
        promoter shouldNotContain "data-verb=\"template-purge\""
        promoter shouldNotContain "data-verb=\"template-discard\""

        val viewer =
            render("partials/template-detail") {
                fillDetail()
                withRoles(RoleModel.NONE.copy(canRead = true, canExecute = true))
            }
        viewer shouldNotContain "data-verb="
        viewer shouldContain "Open in editor"
    }

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
        setVariable("templates", emptyList<Template>())
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("offset", 0)
        setVariable("hasMore", false)
        setVariable("total", 0)
        setVariable("selectedDialect", "")
        setVariable("selectedType", "")
        setVariable("scopes", setOf("ADMIN"))
    }

    /** A NESTED level — the only kind that has leaves now. */
    private fun WebContext.fillNestedLevel() {
        fillLevel()
        setVariable("prefix", "acme/finance")
        setVariable("levelId", TemplateBrowseModel.levelId("acme/finance"))
        setVariable("folders", emptyList<TemplateFolderView>())
        setVariable("templates", listOf(template("acme/finance/monthly_revenue.sql")))
        setVariable("total", 1)
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

    /** The 106 template detail: header + reading column + acting column, in one fill. */
    private fun WebContext.fillDetail() {
        setVariable("templateId", DEEP_PATH)
        setVariable("template", template(DEEP_PATH))
        setVariable("folderPath", "acme/finance/reports/")
        setVariable("leafName", "monthly_revenue.sql")
        setVariable("draftVersion", 2)
        setVariable("draftHash", "h2")
        setVariable("releasableVersion", 2)
        setVariable("canDelete", false)
        // 102 §B.1's header flags (the {R,D} shape: Discard of the resolved release).
        setVariable("canDiscardCurrent", true)
        setVariable("canPurgeDraftInHeader", false)
        setVariable("currentReleaseVersion", 1)
        setVariable("createdVia", "session")
        setVariable("inUse", mapOf(2 to 1, 1 to 2))
        setVariable("versionCount", 2)
        setVariable("runCount", 0)
        setVariable("usedBy", emptyList<Any>())
        setVariable("usedByCount", 0)
        setVariable("excerpt", "SELECT 1")
        setVariable("excerptTruncated", true)
        setVariable("interpolations", listOf("start_date"))
        setVariable(
            "versions",
            listOf(
                VersionRowView.of(
                    version = 2,
                    status = PipelineVersionStatus.DRAFT,
                    createdAt = Instant.parse("2026-09-02T10:00:00Z"),
                    actor = "Muhammad",
                    now = Instant.parse("2026-09-03T10:00:00Z"),
                    usage = 1,
                    usageUnit = "pipeline",
                    isCurrent = false,
                ),
                VersionRowView.of(
                    version = 1,
                    status = PipelineVersionStatus.RELEASED,
                    createdAt = Instant.parse("2026-09-01T10:00:00Z"),
                    actor = "Muhammad",
                    now = Instant.parse("2026-09-03T10:00:00Z"),
                    usage = 2,
                    usageUnit = "pipeline",
                    isCurrent = true,
                ),
            ),
        )
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
        ).withRoles()

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
