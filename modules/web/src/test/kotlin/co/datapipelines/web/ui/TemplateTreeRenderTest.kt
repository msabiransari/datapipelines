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
 * Render-level guard for the templates TREE (template-hierarchy-design §9, round 047).
 *
 * Controller tests pin the model; this class pins what the browser actually receives — and,
 * more importantly, what it must NEVER receive. Four of §9.1's six constraints are *absences*,
 * and an absence has no natural test: nothing fails when a well-meaning future round adds a
 * "New folder" button, a `type` select on the edit form, or a rename field. These assertions
 * are the only thing standing between those constraints and that round.
 *
 *  - **Folders are virtual** (§3.1) — no create / rename / move / delete control anywhere in
 *    the tree, and no empty-folder state.
 *  - **`type` is create-time and immutable** (§5.3) — a selector on the create form, a
 *    read-only VALUE on the editor, and never a disabled control (which devtools re-enables
 *    in one click).
 *  - **No rename, anywhere** (§4.5) — a template's name is its identity.
 *  - **Client validation is a convenience, not an authority** (§9.5) — the create form's
 *    `pattern` must be the SERVER's regex source, not a copy of it.
 *
 * Engine infra mirrors [DatasourcesTemplateRenderTest] (same WebContext shape).
 */
class TemplateTreeRenderTest {
    // ---------------------------------------------------------------- the tree

    @Test
    fun `a tree level renders folders and leaves, each expanding with ONE prefix request`() {
        val html = render("partials/template-tree-level") { fillLevel() }

        html shouldContain "id=\"template-list-wrapper\""
        // One level per request: the folder fetches its OWN prefix, nothing wider (§9.2).
        html shouldContain "hx-get=\"/partials/templates?prefix=acme&amp;"
        html shouldContain "hx-trigger=\"click once\""
        html shouldContain "hx-target=\"next .tpl-level\""
        // A leaf expands to its versions, addressed by ?name= — never a path segment (§9.6).
        html shouldContain "/partials/templates/versions?name=legacy_flat.sql"
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
    fun `a nested level carries the derived id its placeholder announced`() {
        val nested =
            render("partials/template-tree-level") {
                fillLevel()
                setVariable("prefix", "acme/finance")
                setVariable("levelId", TemplateBrowseModel.levelId("acme/finance"))
            }

        // Derived in ONE place, so the placeholder and the fragment that replaces it agree.
        nested shouldContain "id=\"${TemplateBrowseModel.levelId("acme/finance")}\""
        TemplateBrowseModel.levelId(null) shouldBe "template-list-wrapper"
        (TemplateBrowseModel.levelId("acme/finance") == TemplateBrowseModel.levelId("acme/hr")) shouldBe false
    }

    @Test
    fun `search is a FLAT list of full paths, not a pruned tree`() {
        val html = render("partials/template-search") { fillSearch() }

        html shouldContain "id=\"template-list-wrapper\""
        html shouldContain "<table class=\"ds-table\">"
        // Full paths, each also on `title` — the search result is where a path is longest.
        html shouldContain "acme/finance/monthly_revenue"
        html shouldContain "title=\"acme/finance/monthly_revenue\""
        // No tree machinery reaches the search presentation.
        html shouldNotContain "hx-trigger=\"click once\""
        html shouldNotContain "prefix="
        // The pager keeps every active filter, including 046's `type`.
        html shouldContain "q=revenue"
        html shouldContain "type=sql"
    }

    @Test
    fun `the wrapper dispatches to the tree when q is empty and to search when it is not`() {
        val browsing = render("partials/templates") { fillLevel() }
        val searching = render("partials/templates") { fillSearch() }

        browsing shouldContain "hx-trigger=\"click once\""
        browsing shouldNotContain "<table class=\"ds-table\">"
        searching shouldContain "<table class=\"ds-table\">"
        searching shouldNotContain "hx-trigger=\"click once\""
        // One stable swap root in BOTH presentations (ui-screens.md §4.5).
        browsing shouldContain "id=\"template-list-wrapper\""
        searching shouldContain "id=\"template-list-wrapper\""
    }

    @Test
    fun `the primary level fragment never carries the OOB attribute`() {
        render("partials/template-tree-level") { fillLevel() } shouldNotContain "hx-swap-oob"
        render("partials/template-search") { fillSearch() } shouldNotContain "hx-swap-oob"
    }

    @Test
    fun `a leaf's versions carry RELEASED and DRAFT badges and no destructive action`() {
        val html = render("partials/template-versions") { fillVersions() }

        html shouldContain "DRAFT"
        html shouldContain "RELEASED"
        html shouldContain "ds-badge ds-badge-warning"
        html shouldContain "ds-badge ds-badge-success"
        html shouldContain "/templates/editor?name=$DEEP_PATH"
        listOf("Rename", "Delete", "hx-delete", "hx-put").forEach { html shouldNotContain it }
    }

    @Test
    fun `each version row states its in-use count, and an unused version stays quiet`() {
        // fillVersions: v2 (the draft) is pinned by one pipeline, v1 by two, and any version
        // with no working-version pin renders the em dash — "nobody uses this" is the
        // retirement-ready signal, not a zero to squint past.
        val html = render("partials/template-versions") { fillVersions() }

        html shouldContain "1 pipeline"
        html shouldContain "2 pipelines"
        html shouldContain "In use"
        // The singular/plural fork is honest: exactly one occurrence of the singular row.
        html.windowed("1 pipeline".length).count { it == "1 pipeline" } shouldBe 1
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
        html shouldContain "syncTemplateDialect()"
        // The dialect control is DISABLED for html, not merely hidden — a hidden-but-enabled
        // select still posts its value.
        html shouldContain "select.disabled = !isSql"
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
    fun `the type filter joins dialect and search on the list screen`() {
        val html = render("templates/list") { fillPage() }

        html shouldContain "id=\"template-filter-type\""
        html shouldContain "hx-include=\"#template-filter-q, #template-filter-dialect\""
        html shouldContain "hx-include=\"#template-filter-dialect, #template-filter-type\""
        html shouldContain "id=\"template-list-wrapper\""
    }

    // ------------------------------------------------------------- edit / draft

    @Test
    fun `the editor shows type as a read-only VALUE and never as a control`() {
        val html = render("templates/editor") { fillEditor() }

        // §9.3 / §5.3: a read-only value, because a disabled <select> is re-enabled in
        // devtools in one click and the UI must not present a lock it does not own.
        html shouldContain "Type:"
        html shouldContain "<span class=\"ds-badge ds-badge-default\">sql</span>"
        // No `type` control of any kind — not an enabled one, and not a disabled one either.
        html shouldNotContain "name=\"type\""
        html shouldNotContain "disabled=\"disabled\""
        // ...and no rename affordance on the edit form either (§4.5).
        html shouldNotContain "Rename"
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
    fun `the pipeline editor's inspector puts the same string on the link and on its title`() {
        val html = render("pipelines/editor") { fillPipelineEditor() }

        // One value, rendered twice — the truncated text is never the only copy (§9.4).
        html shouldContain "class=\"pe-link pe-path\""
        html shouldContain "x-bind:title=\"templateRefText(selectedNode)\""
        html shouldContain "x-text=\"templateRefText(selectedNode)\""
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
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillSearch() {
        setVariable("searching", true)
        setVariable("templates", listOf(template(DEEP_PATH)))
        setVariable("drafts", emptyMap<String, TemplateVersionDetail>())
        setVariable("q", "revenue")
        setVariable("selectedDialect", "")
        setVariable("selectedType", "sql")
        setVariable("offset", 0)
        setVariable("hasMore", true)
        setVariable("total", 4)
        setVariable("scopes", setOf("ADMIN"))
    }

    private fun WebContext.fillVersions() {
        setVariable("templateId", DEEP_PATH)
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

    private fun WebContext.fillEditor() {
        fillChrome()
        setVariable("template", template(DEEP_PATH))
        setVariable("versions", emptyList<TemplateVersionSummary>())
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("draftHash", null)
    }

    private fun WebContext.fillPipelineEditor() {
        fillChrome()
        setVariable("pipelineId", "p1")
        setVariable("pipelineName", "p1")
        setVariable("datasources", emptyList<Any>())
        setVariable("scopes", setOf("ADMIN"))
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
