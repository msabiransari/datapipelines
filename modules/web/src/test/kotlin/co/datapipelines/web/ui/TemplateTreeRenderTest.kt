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
 * Render-level guard for the templates catalog and its fragments (template-hierarchy-design §9,
 * round 047, re-aimed by #398 onto the catalog/workspace fragments; since #466 the sidebar's
 * tree is the REST one, rendered client-side, so no server-rendered tree level is left here).
 *
 * Controller tests pin the model; this class pins what the browser actually receives — and,
 * more importantly, what it must NEVER receive. Four of §9.1's six constraints are
 * *absences*, and an absence has no natural test: nothing fails when a well-meaning future
 * round adds a "New folder" button, a `type` select on an edit surface, or a rename field.
 * These assertions are the only thing standing between those constraints and that round.
 *
 *  - **Folders are virtual** (§3.1) — no create / rename / move / delete control anywhere in
 *    the catalog, and no empty-folder state.
 *  - **`type` is create-time and immutable** (§5.3) — a selector on the create form, a
 *    read-only VALUE on the workspace, and never a disabled control (which devtools re-enables
 *    in one click).
 *  - **No rename, anywhere** (§4.5) — a template's name is its identity.
 *  - **Client validation is a convenience, not an authority** (§9.5) — the create form's
 *    `pattern` must be the SERVER's regex source, not a copy of it.
 *  - **#398: a leaf NAVIGATES** — a full-document link into `/templates/{name}`, never a
 *    selection into a pane (the pane is gone); the rows carry `data-leaf-id`.
 *
 * Engine infra mirrors [DatasourcesTemplateRenderTest] (same WebContext shape).
 */
class TemplateTreeRenderTest {
    // ---------------------------------------------------------------- the catalog list

    @Test
    fun `a catalog row is a full-document link into the workspace, carrying the leaf id`() {
        val html = render("partials/template-search") { fillSearch() }

        html shouldContain "id=\"template-list-wrapper\""
        html shouldContain "data-template-list=\"page\""
        // #398: a leaf is a LINK to the workspace, a full document — never a detail-pane fetch.
        html shouldContain "href=\"/templates/$DEEP_PATH\""
        html shouldContain "hx-boost=\"true\""
        html shouldContain "data-leaf-id=\"$DEEP_PATH\""
        html shouldNotContain "/partials/templates/versions"
        html shouldNotContain "hx-target=\"#template-detail\""
        // The full path is on `title`.
        html shouldContain "title=\"$DEEP_PATH\""
        // The pager keeps every active filter, including 046's `type`.
        html shouldContain "q=revenue"
        html shouldContain "type=sql"
        // #466: no sidebar presentation is left — no listbox, no level request, no scope.
        html shouldNotContain "role=\"listbox\""
        html shouldNotContain "prefix="
        html shouldNotContain "scope="
    }

    @Test
    fun `the catalog offers NO folder create, rename, move or delete control`() {
        val html = render("partials/template-search") { fillSearch() }

        // §3.1: a folder is a name prefix with no identity, so there is nothing to act on.
        listOf("New folder", "new-folder", "Rename", "rename", "Move", "Delete folder", "hx-delete", "hx-put", "hx-patch")
            .forEach { html shouldNotContain it }
        html shouldNotContain "hx-post"
    }

    @Test
    fun `an empty catalog renders the screen empty state, never an empty FOLDER`() {
        val html =
            render("partials/template-search") {
                fillSearch()
                setVariable("templates", emptyList<Template>())
                setVariable("q", "")
                setVariable("filtered", false)
            }

        // The one empty state is "no templates yet" — the screen's, not a folder's.
        html shouldContain "No templates yet"
        html shouldNotContain "empty folder"
        html shouldNotContain "This folder is empty"
        html shouldNotContain "<ul class=\"tpl-results\""
    }

    @Test
    fun `085 - search results stay a FLAT list with no tree chrome`() {
        val html = render("partials/template-search") { fillSearch() }

        html shouldNotContain "tpl-chevron"
        html shouldNotContain "lucide-sprite"
        html shouldNotContain "tpl-icon"
    }

    @Test
    fun `the catalog's no-match state offers the Clear search control that keeps dialect and type (#432)`() {
        val emptyCatalog =
            render("partials/template-search") {
                fillSearch()
                setVariable("templates", emptyList<Template>())
                setVariable("q", "revenue")
                // #510: the fixture bypasses the browse model that stamps the flag; this
                // miss is q standing, so filtered stands and #432's Clear search renders.
                setVariable("filtered", true)
            }
        emptyCatalog shouldNotContain "data-nav-tree-clear"
        emptyCatalog shouldContain "Clear search"
        // #432: the clear empties the box (shell.js, by the ids named here), keeps dialect, type
        // and — since #579 — lifecycle, and its one request describes the three it keeps; it
        // replaces a search still in flight.
        emptyCatalog shouldContain "data-dp-clear-filters=\"#template-filter-q\""
        emptyCatalog shouldContain "hx-include=\"#template-filter-dialect, #template-filter-type, #template-filter-lifecycle\""
        emptyCatalog shouldContain "hx-sync=\"#template-filter-q:replace\""
        TemplateBrowseModel.CATALOG_ROOT_ID shouldBe "template-list-wrapper"
    }

    @Test
    fun `the list fragment never carries the OOB attribute unprompted`() {
        render("partials/template-search") { fillSearch() } shouldNotContain "hx-swap-oob=\"true\""
    }

    @Test
    fun `the dispatcher fragment is gone - the controller returns the concrete view`() {
        // partials/templates.html retired with the two-pane page (#398): a dispatcher here
        // would be a silent second contract beside the model's concrete SEARCH view.
        javaClass.getResource("/templates/partials/templates.html") shouldBe null
        javaClass.getResource("/templates/partials/template-detail.html") shouldBe null
    }

    @Test
    fun `the Versions tab is the house table, with per-row menus and the canonical Open`() {
        val html = render("partials/template-versions") { fillVersions() }

        // §4.4's house-table rule: the same component every version surface renders.
        html shouldContain "<table class=\"ds-table\""
        html shouldContain "data-version-row=\"2\""
        html shouldContain "DRAFT"
        html shouldContain "RELEASED"
        html shouldContain "ds-badge ds-badge-warning"
        html shouldContain "ds-badge ds-badge-success"
        // Open is the CANONICAL workspace with the row's own version explicit (R5).
        html shouldContain "/templates/$DEEP_PATH?version=1&amp;tab=source"
        // 102: each row's verbs live in the ⋯ overflow menu, opened with from=editor — the
        // workspace's surface — and a verb §3.5 refuses for the row's status is ABSENT.
        listOf("Rename", "Move", "hx-put").forEach { html shouldNotContain it }
        html shouldContain "details class=\"tplx-vmenu\""
        html shouldContain "hx-get=\"/partials/templates/lifecycle/release?name=$DEEP_PATH&amp;from=editor\""
        html shouldContain "hx-get=\"/partials/templates/lifecycle/purge?name=$DEEP_PATH&amp;version=2&amp;from=editor\""
        html shouldContain "hx-get=\"/partials/templates/lifecycle/discard?name=$DEEP_PATH&amp;version=1&amp;from=editor\""
        html shouldContain "hx-target=\"#tx-dialog\""
        // The template rows offer no Switch, and the fetch path is gone.
        html shouldNotContain "Switch to v"
        html shouldNotContain "data-verb-url="
        html shouldNotContain "data-confirm="
    }

    @Test
    fun `each version row states its in-use count, and an unused version stays quiet`() {
        // fillVersions: v2 (the draft) is pinned by one pipeline, v1 by two — both sides of
        // the singular/plural fork, worded by the model ([VersionRowView.usageLabel]).
        val html = render("partials/template-versions") { fillVersions() }

        html shouldContain "1 pipeline"
        html shouldContain "2 pipelines"
        html shouldNotContain "In use"
    }

    // ------------------------------------------------------------- create form

    @Test
    fun `the create form's name pattern is DERIVED from the server's grammar, never retyped`() {
        val html = render("templates/list") { fillPage() }

        // §9.5: the rendered attribute IS the validator's own regex source and its own cap.
        html shouldContain "pattern=\"${TemplateNameGrammar.pattern}\""
        html shouldContain "maxlength=\"${TemplateNameGrammar.maxLength}\""
        // ...and the grammar appears EXACTLY once in the page: the rendered attribute. A
        // second occurrence is a hand-copied regex, which is the drift §9.5 forbids.
        html.windowed(TemplateNameGrammar.pattern.length).count { it == TemplateNameGrammar.pattern } shouldBe 1
    }

    @Test
    fun `the create form offers type with a sql default and a conditional dialect`() {
        val html = render("templates/list") { fillPage() }

        html shouldContain "id=\"create-template-type\""
        html shouldContain "id=\"create-template-dialect-field\""
        // 188: the sync lives in the page's script file (no inline script under the CSP);
        // the page loads it, and the file carries the mechanism.
        html shouldContain "/js/template-create-modal.js"
        val script =
            checkNotNull(javaClass.getResource("/static/js/template-create-modal.js")) { "template-create-modal.js not on the classpath" }
                .readText()
        script shouldContain "syncTemplateDialect()"
        // The dialect control is DISABLED for html, not merely hidden — a hidden-but-enabled
        // select still posts its value.
        script shouldContain "select.disabled = !isSql"
    }

    @Test
    fun `neither the create form nor the tree offers a rename affordance`() {
        val html = render("templates/list") { fillPage() }

        // §4.5: `name` is a create-time input; there is no rename anywhere in v1.
        html shouldNotContain "Rename"
        html shouldNotContain "rename"
        html shouldNotContain "New folder"
    }

    @Test
    fun `the type filter joins dialect and search on the catalog page`() {
        val html = render("templates/list") { fillPage() }

        html shouldContain "id=\"template-filter-type\""
        html shouldContain "hx-include=\"#template-filter-q, #template-filter-dialect, #template-filter-lifecycle\""
        html shouldContain "hx-include=\"#template-filter-dialect, #template-filter-type, #template-filter-lifecycle\""
        html shouldContain "id=\"template-list-wrapper\""
        // #398: the catalog's other door is the sidebar tree's reveal, not a page tree.
        html shouldContain "data-nav-tree-reveal=\"templates\""
    }

    // ------------------------------------------------------------- the workspace's type rule

    @Test
    fun `the workspace shows type as a read-only VALUE and never as a control`() {
        val html = render("templates/workspace") { fillWorkspace() }

        // §9.3 / §5.3: a read-only value, because a disabled <select> is re-enabled in
        // devtools in one click and the UI must not present a lock it does not own.
        html shouldContain "<span class=\"ds-badge ds-badge-default\""
        html shouldContain ">sql</span>"
        // No `type` control of any kind — not an enabled one, and not a disabled one either.
        html shouldNotContain "name=\"type\""
        html shouldNotContain "disabled=\"disabled\""
        // ...and no rename affordance on the workspace either (§4.5).
        html shouldNotContain "Rename"
        html shouldNotContain "rename"
    }

    // ------------------------------------------ §9.4 the pipeline editor's path

    @Test
    fun `a node's template reference truncates to one line with the FULL path on title`() {
        val html =
            render("partials/pipeline-node-sql") {
                setVariable("state", "rendered")
                setVariable("dialect", "POSTGRES")
                setVariable("templateId", DEEP_PATH)
                setVariable("templateVersion", 3)
                setVariable("sql", "SELECT 1")
                setVariable("sampledParameters", emptyList<String>())
            }

        html shouldContain "class=\"pe-link pe-path\""
        html shouldContain "title=\"$DEEP_PATH @ v3\""
        // The link is the PIPELINE partial's own: since #433 it points at the workspace route
        // directly (the old editor route is a 302 kept for bookmarks) — the destination is
        // PipelineNodeSqlRenderTest's, the §9.4 title rule is this one's.
        html shouldContain "href=\"/templates/$DEEP_PATH\""
    }

    @Test
    fun `the template-missing state names the same path, truncated, with the full path on title`() {
        val html =
            render("partials/pipeline-node-sql") {
                setVariable("state", "template-missing")
                setVariable("templateId", DEEP_PATH)
                setVariable("templateVersion", 3)
            }

        html shouldContain "class=\"pe-path\""
        html shouldContain "title=\"$DEEP_PATH @ v3\""
        html shouldContain "is not in this workspace"
    }

    @Test
    fun `the pipeline editor's Details tab puts the same string on every value and on its title`() {
        val html = render("pipelines/editor") { fillPipelineEditor() }

        // One value, rendered twice — the truncated text is never the only copy (§9.4).
        html shouldContain "x-bind:title=\"row.v\""
        html shouldContain "x-text=\"row.v\""
        // …and the SQL partial's own template link keeps its pe-path form.
        val partial =
            render("partials/pipeline-node-sql") {
                setVariable("state", "rendered")
                setVariable("dialect", "POSTGRES")
                setVariable("templateId", DEEP_PATH)
                setVariable("templateVersion", 3)
                setVariable("sql", "SELECT 1")
                setVariable("sampledParameters", emptyList<String>())
            }
        partial shouldContain "class=\"pe-link pe-path\""
    }

    // ------------------------------------------------------------------ fixtures

    private fun WebContext.fillSearch() {
        setVariable("searching", true)
        setVariable("rootId", TemplateBrowseModel.CATALOG_ROOT_ID)
        setVariable("templates", listOf(template(DEEP_PATH)))
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("q", "revenue")
        setVariable("selectedDialect", "")
        setVariable("selectedType", "sql")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 4)
    }

    /**
     * The Versions tab's rows: DRAFT v2 over RELEASED v1, with the per-version in-use counts
     * already WORDED by the model ([VersionRowView.usageLabel]) — v2 by one pipeline, v1 by
     * two, so both sides of the singular/plural fork are exercised.
     */
    private fun WebContext.fillVersions() {
        setVariable("templateName", DEEP_PATH)
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
                    isViewed = true,
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
        // The catalog's own fill: the flat list, always (the dispatcher is gone).
        fillSearch()
        setVariable("q", "")
        setVariable("dialects", listOf("POSTGRES", "MYSQL"))
        setVariable("types", TemplateType.WIRE_VALUES)
        setVariable("namePattern", TemplateNameGrammar.pattern)
        setVariable("nameMaxLength", TemplateNameGrammar.maxLength)
        setVariable("nameHint", TemplateNameGrammar.DESCRIPTION)
        setVariable("transformTypes", TemplateType.entries.filter { it.isTransform }.joinToString(",") { it.wire })
        setVariable("skeleton", TransformSkeleton)
        setVariable("canAuthor", true)
    }

    /** The workspace page's fill, release-first (an sql template at its v2 release). */
    private fun WebContext.fillWorkspace() {
        fillChrome()
        setVariable("templateName", DEEP_PATH)
        setVariable("navCurrentPath", DEEP_PATH)
        setVariable("hasSelectedBody", true)
        setVariable("viewedVersion", 2)
        setVariable("viewedLabel", "v2 · released · current")
        setVariable("viewedIsDraft", false)
        setVariable("viewedIsCurrent", true)
        setVariable("viewedStatusLabel", "released")
        setVariable("currentVersion", 2)
        setVariable("viewedEditable", false)
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("draftHash", null)
        setVariable("canDelete", false)
        setVariable("canDiscardCurrent", true)
        setVariable("canPurgeDraftInHeader", false)
        setVariable("releasableVersion", null)
        setVariable("currentReleaseVersion", 2)
        setVariable("canAuthor", true)
        setVariable("activeTab", "source")
        setVariable("templateWorkspace", workspaceResolved())
        setVariable("usedBy", emptyList<Any>())
        setVariable("usedByCount", 0)
        setVariable("usedBySets", emptyList<Any>())
        setVariable("usedByVisualizations", emptyList<Any>())
        setVariable("usedBySummary", "nothing")
        setVariable("template", template(DEEP_PATH))
        setVariable("selectedVersion", 2)
        setVariable("selectedStatus", "RELEASED")
        setVariable("releasedAt", null)
        setVariable("releasedBy", null)
        setVariable("readOnly", true)
        setVariable("interpolations", emptyList<String>())
        setVariable("isTransform", false)
        setVariable("versions", emptyList<VersionRowView>())
    }

    private fun workspaceResolved(): TemplateWorkspaceModel.Resolved =
        TemplateWorkspaceModel.Resolved(
            name = DEEP_PATH,
            selected = TemplateWorkspaceModel.Selected(template(DEEP_PATH), null),
            draft = null,
            currentVisible = 2,
            versions = emptyList(),
            usedBy =
                TemplateWorkspaceModel.UsedByFacts(
                    pipelines = emptyList(),
                    pipelineCount = 0,
                    sets = emptyList(),
                    visualizations = emptyList(),
                    summary = "nothing",
                ),
        )

    private fun WebContext.fillPipelineEditor() {
        fillChrome()
        setVariable("pipelineId", "p1")
        setVariable("pipelineName", "p1")
        setVariable("datasources", emptyList<Any>())
        setVariable("scopes", setOf("ADMIN"))
        // PipelineWorkspaceController always stamps the version state + the body JSON; the
        // Details pane's checks line reads them (140).
        setVariable("hasSelectedBody", true)
        setVariable("viewedVersion", 1)
        setVariable("viewedLabel", "v1 · released · current")
        setVariable("viewedIsDraft", false)
        setVariable("viewedIsCurrent", true)
        setVariable("viewedStatusLabel", "released")
        setVariable("currentVersion", 1)
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("versions", emptyList<Any>())
        setVariable("activeTab", "flow")
        setVariable("canReadExecutions", true)
        setVariable("workspaceJson", """{"viewedVersion":1,"hasBody":true}""")
        setVariable("pipelineJson", """{"id":"p1","name":"p1","display_name":"P1","version":1,"parameters":{},"nodes":[]}""")
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
     * Renders a view with HTML COMMENTS STRIPPED.
     *
     * Half of these assertions are absences — "no rename control", "no folder CRUD" — and the
     * markup's own comments explain, at length, why those things are absent. Asserting over
     * raw output would make the documentation trip the guard it documents, and the obvious
     * "fix" would be to delete the documentation. Comments are not affordances; the browser
     * cannot click one.
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
