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
 * Render-level guard for the templates tree and its fragments (template-hierarchy-design §9,
 * round 047, re-aimed by #398 onto the sidebar tree and the catalog/workspace fragments).
 *
 * Controller tests pin the model; this class pins what the browser actually receives — and,
 * more importantly, what it must NEVER receive. Four of §9.1's six constraints are
 * *absences*, and an absence has no natural test: nothing fails when a well-meaning future
 * round adds a "New folder" button, a `type` select on an edit surface, or a rename field.
 * These assertions are the only thing standing between those constraints and that round.
 *
 *  - **Folders are virtual** (§3.1) — no create / rename / move / delete control anywhere in
 *    the tree, and no empty-folder state.
 *  - **`type` is create-time and immutable** (§5.3) — a selector on the create form, a
 *    read-only VALUE on the workspace, and never a disabled control (which devtools re-enables
 *    in one click).
 *  - **No rename, anywhere** (§4.5) — a template's name is its identity.
 *  - **Client validation is a convenience, not an authority** (§9.5) — the create form's
 *    `pattern` must be the SERVER's regex source, not a copy of it.
 *  - **#398: a leaf NAVIGATES** — a full-document link into `/templates/{name}`, never a
 *    selection into a pane (the pane is gone); the sidebar's rows carry `data-leaf-id` for
 *    nav-tree.js's current-leaf marking.
 *
 * Engine infra mirrors [DatasourcesTemplateRenderTest] (same WebContext shape).
 */
class TemplateTreeRenderTest {
    // ---------------------------------------------------------------- the tree

    @Test
    fun `a tree level renders folders and leaves, each expanding with ONE prefix request`() {
        val html = render("partials/template-tree-level") { fillLevel() }

        html shouldContain "id=\"template-nav-root\""
        // One level per request: the folder fetches its OWN prefix, nothing wider (§9.2).
        html shouldContain "hx-get=\"/partials/templates?prefix=acme&amp;"
        html shouldContain "hx-trigger=\"click once\""
        html shouldContain "hx-target=\"next .tpl-level\""
        // #398: a leaf is a LINK to the workspace, a full document — never a detail-pane fetch.
        html shouldContain "href=\"/templates/legacy_flat.sql\""
        html shouldContain "hx-boost=\"true\""
        html shouldContain "data-leaf-id=\"legacy_flat.sql\""
        html shouldNotContain "/partials/templates/versions"
        // The full path is on `title` at every leaf, and the count comes from the subtree.
        html shouldContain "title=\"acme\""
        html shouldContain "ds-badge ds-badge-default"
    }

    @Test
    fun `the tree offers NO folder create, rename, move or delete control`() {
        val html = render("partials/template-tree-level") { fillLevel() }

        // §3.1: a folder is a name prefix with no identity, so there is nothing to act on.
        listOf("New folder", "new-folder", "Rename", "rename", "Move", "Delete folder", "hx-delete", "hx-put", "hx-patch")
            .forEach { html shouldNotContain it }
        // A folder never carries a mutating request of any kind.
        html shouldNotContain "hx-post"
    }

    @Test
    fun `a level with nothing in it renders the screen empty state, never an empty FOLDER`() {
        val html =
            render("partials/template-tree-level") {
                fillLevel()
                setVariable("folders", emptyList<TemplateFolderView>())
                setVariable("templates", emptyList<Template>())
            }

        // The one empty state is "no templates yet" — the screen's, not a folder's.
        html shouldContain "No templates yet"
        html shouldNotContain "empty folder"
        html shouldNotContain "This folder is empty"
        // And no tree at all is rendered, so no folder chrome can imply one exists.
        html shouldNotContain "<ul class=\"tpl-tree\">"
    }

    @Test
    fun `085 - folder rows carry the sprite chevron and folder state icons, leaves the file glyph`() {
        val html = render("partials/template-tree-level") { fillLevel() }

        // The disclosure marker is the sprite's chevron-right, followed by the folder state
        // pair (CSS swaps folder → folder-open on [open]).
        html shouldContain "class=\"ds-icon ds-icon-xs tpl-chevron\""
        html shouldContain "lucide-sprite.svg#chevron-right"
        html shouldContain "tpl-icon-folder\""
        html shouldContain "lucide-sprite.svg#folder\""
        html shouldContain "tpl-icon-folder-open"
        html shouldContain "lucide-sprite.svg#folder-open"
        // A leaf gets file-code in the same slot, and every icon is sized and hidden.
        html shouldContain "lucide-sprite.svg#file-code"
        html shouldNotContain "<svg class=\"ds-icon\""
        // The pending placeholder the guides must not touch is unchanged.
        html shouldContain "tpl-level tpl-level-pending"
    }

    @Test
    fun `085 - search results stay a FLAT list with no tree chrome`() {
        val html = render("partials/template-search") { fillSearch() }

        html shouldNotContain "tpl-chevron"
        html shouldNotContain "lucide-sprite"
        html shouldNotContain "tpl-icon"
    }

    @Test
    fun `a nested level carries the derived id its placeholder announced`() {
        val nested =
            render("partials/template-tree-level") {
                fillLevel()
                setVariable("prefix", "acme/finance")
                setVariable("levelId", TemplateBrowseModel.levelId("acme/finance"))
            }

        // Derived in ONE place, so the placeholder and the fragment that replaces it agree.
        nested shouldContain "id=\"${TemplateBrowseModel.levelId("acme/finance")}\""
        TemplateBrowseModel.levelId(null) shouldBe "template-nav-root"
        TemplateBrowseModel.CATALOG_ROOT_ID shouldBe "template-list-wrapper"
        (TemplateBrowseModel.levelId("acme/finance") == TemplateBrowseModel.levelId("acme/hr")) shouldBe false
    }

    @Test
    fun `the catalog instance is plain links under the page root, the nav instance the listbox`() {
        val catalog = render("partials/template-search") { fillSearch(scope = TemplateListScope.CATALOG) }
        val nav = render("partials/template-search") { fillSearch(scope = TemplateListScope.NAV) }

        // #398's two instances: the scope names the markup AND the stable swap root.
        catalog shouldContain "id=\"template-list-wrapper\""
        catalog shouldContain "data-template-list=\"page\""
        catalog shouldNotContain "role=\"listbox\""
        nav shouldContain "id=\"template-nav-root\""
        nav shouldContain "data-template-list=\"nav\""
        nav shouldContain "role=\"listbox\""
        // Both are links into the workspace, full documents, carrying the leaf id.
        listOf(catalog, nav).forEach {
            it shouldContain "href=\"/templates/$DEEP_PATH\""
            it shouldContain "hx-boost=\"true\""
            it shouldContain "data-leaf-id=\"$DEEP_PATH\""
            it shouldNotContain "hx-target=\"#template-detail\""
            it shouldNotContain "prefix="
        }
        // The pager keeps every active filter, including 046's `type` — on the page instance.
        catalog shouldContain "q=revenue"
        catalog shouldContain "type=sql"
        // The nav's clear is nav-tree.js's (it empties the sidebar's own box too) and lives on
        // the EMPTY branch — the no-match case; the catalog's clear is a bare re-fetch.
        val emptyNav =
            render("partials/template-search") {
                fillSearch(TemplateListScope.NAV)
                setVariable("templates", emptyList<Template>())
            }
        emptyNav shouldContain "data-nav-tree-clear"
        val emptyCatalog =
            render("partials/template-search") {
                fillSearch(TemplateListScope.CATALOG)
                setVariable("templates", emptyList<Template>())
                setVariable("q", "revenue")
            }
        emptyCatalog shouldNotContain "data-nav-tree-clear"
        emptyCatalog shouldContain "Clear search"
    }

    @Test
    fun `the level and list fragments never carry the OOB attribute unprompted`() {
        render("partials/template-tree-level") { fillLevel() } shouldNotContain "hx-swap-oob=\"true\""
        render("partials/template-search") { fillSearch() } shouldNotContain "hx-swap-oob=\"true\""
    }

    @Test
    fun `the dispatcher fragment is gone - the controller returns the concrete view`() {
        // partials/templates.html retired with the two-pane page (#398): a dispatcher here
        // would be a silent second contract beside the model's concrete LEVEL/SEARCH views.
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
        html shouldContain "hx-include=\"#template-filter-q, #template-filter-dialect\""
        html shouldContain "hx-include=\"#template-filter-dialect, #template-filter-type\""
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
        // The link is the PIPELINE partial's own (not this lane's fence): it points at the
        // editor route, which #398 made the 302 into the workspace — the destination is
        // TemplateEditorControllerTest's, the §9.4 title rule is this one's.
        html shouldContain "/templates/editor?name=$DEEP_PATH"
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

    private fun WebContext.fillLevel() {
        setVariable("searching", false)
        setVariable("scope", TemplateListScope.NAV.wire)
        setVariable("prefix", "")
        setVariable("levelId", TemplateBrowseModel.ROOT_LEVEL_ID)
        setVariable(
            "folders",
            listOf(
                TemplateFolderView("acme", "acme", 12, TemplateBrowseModel.levelId("acme")),
                TemplateFolderView("lib", "lib", 3, TemplateBrowseModel.levelId("lib")),
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
    }

    private fun WebContext.fillSearch(scope: TemplateListScope = TemplateListScope.NAV) {
        setVariable("searching", true)
        setVariable("scope", scope.wire)
        setVariable("rootId", scope.rootId)
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
        fillSearch(TemplateListScope.CATALOG)
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
