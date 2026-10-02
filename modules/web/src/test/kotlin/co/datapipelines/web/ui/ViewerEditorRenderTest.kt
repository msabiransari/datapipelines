package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateVersionDetail
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContainExactly
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
 * Role visibility on the template workspace (#398 — this class was the editor page's viewer
 * guard; the page it guards is the workspace now, and the rule is the same one sentence).
 *
 * A viewer's workspace page is a READ surface: the body renders read-only, the editable
 * textarea, the Render tab (the render-context panel and the preview — its POST is MUTATE)
 * and every lifecycle verb are absent; the version selector, the Imports table and the tab
 * strip are the reads that stay. The inventory of interactive elements is asserted as an
 * EXACT SET, so a control added without a guard shows up here by name rather than passing a
 * grep.
 *
 * The positive half is kept on purpose: an author on the working draft still gets the
 * textarea, the Render tab and (Q1(b)) the edit affordance, and a promoter never does —
 * "hide everything" cannot pass.
 */
class ViewerEditorRenderTest {
    @Test
    fun `a viewer's workspace is a read surface - no textarea, no render tab, no context rail, no verb`() {
        val html =
            render("templates/workspace") {
                page(viewedEditable = false)
                viewer()
            }

        html shouldContain "id=\"versionBody\""
        html shouldContain BODY
        html shouldNotContain "<textarea"
        html shouldNotContain "templateBody"
        html shouldNotContain "previewBtn"
        html shouldNotContain "previewPane"
        html shouldNotContain "context-rows"
        html shouldNotContain "contextJsonTextarea"
        html shouldNotContain "tpl-edit-version"
        html shouldNotContain "data-verb="
        html shouldContain "data-role-note=\"read-only\""
        // The reads stay: the version selector, the Imports table, the read-only badge line.
        html shouldContain "tw-version-link"
        html shouldContain "lib/common.sql"
        html shouldContain ">read-only<"
    }

    /** The exact interactive surface of a viewer's workspace page — by element, not by grep. */
    @Test
    fun `a viewer's workspace carries exactly its tab strip and nothing else`() {
        val html =
            render("templates/workspace") {
                page(viewedEditable = false)
                viewer()
            }

        interactive(mainOf(html)) shouldContainExactly
            listOf(
                "button#tw-tab-source",
                "button#tw-tab-overview",
                "button#tw-tab-runs",
                "button#tw-tab-used",
                "button#tw-tab-versions",
            )
    }

    @Test
    fun `the source partial for a reader is the read-only pane whatever version it shows`() {
        val working =
            render("partials/template-source") {
                source(readOnly = true, selected = 2)
                viewer()
            }
        working shouldContain "id=\"versionBody\""
        working shouldNotContain "<textarea"
        working shouldNotContain "tpl-edit-version"

        val older =
            render("partials/template-source") {
                source(readOnly = true, selected = 1)
                viewer()
            }
        older shouldContain "id=\"versionBody\""
        older shouldNotContain "<textarea"
    }

    @Test
    fun `an author on the working draft keeps the textarea, the Render tab and the preview`() {
        val html =
            render("templates/workspace") {
                page(viewedEditable = true, draftView = true)
                author()
            }

        html shouldContain "id=\"templateBody\""
        html shouldContain "id=\"previewBtn\""
        html shouldContain "id=\"context-rows\""
        html shouldContain "id=\"contextJsonTextarea\""
        html shouldNotContain "data-role-note=\"read-only\""
        interactive(mainOf(html)).contains("textarea#templateBody") shouldBe true
        interactive(mainOf(html)).contains("button#previewBtn") shouldBe true
        // The Render tab exists for the author of a non-transform template.
        html shouldContain "id=\"tw-tab-render\""
    }

    @Test
    fun `an author selecting an older version gets Edit, a reader never does`() {
        val author =
            render("partials/template-source") {
                source(readOnly = true, selected = 1)
                author()
            }
        author shouldContain "tpl-edit-version"

        val promoter =
            render("partials/template-source") {
                source(readOnly = true, selected = 1)
                promoter()
            }
        promoter shouldNotContain "tpl-edit-version"
    }

    /** D5/D8 (2026-09-20): the promoter releases nothing — a read-only source, the read-only note, no verb. */
    @Test
    fun `a promoter's workspace is read-only with no Release, even when a draft is pending`() {
        val html =
            render("templates/workspace") {
                page(viewedEditable = false, hasDraft = true)
                promoter()
            }

        html shouldContain "id=\"versionBody\""
        html shouldNotContain "<textarea"
        html shouldNotContain "data-verb=\"template-release\""
        html shouldNotContain "data-verb=\"template-purge\""
        html shouldNotContain "data-verb=\"template-discard\""
        html shouldNotContain "data-verb=\"template-purge-entity\""
        html shouldContain "data-role-note=\"read-only\""
        html shouldNotContain "previewBtn"
        html shouldNotContain "id=\"tw-tab-render\""
    }

    /** Every version row's Open names ITS version on the CANONICAL workspace — a reader can trust which version opened. */
    @Test
    fun `every version row's Open carries that row's exact version`() {
        val html =
            render("partials/template-versions") {
                versions()
                viewer()
            }

        val opens = Regex("href=\"(/templates/[^\"]*)\"[^>]*>Open<").findAll(html).map { it.groupValues[1] }.toList()
        opens shouldContainExactly
            listOf(
                "/templates/acme/revenue.sql?version=2&amp;tab=source",
                "/templates/acme/revenue.sql?version=1&amp;tab=source",
            )
    }

    @Test
    fun `a missing template never gives a reader an editable source`() {
        val html =
            render("partials/template-source") {
                source(readOnly = false, selected = 1)
                setVariable("template", null)
                viewer()
            }

        interactive(html) shouldContainExactly emptyList()
    }

    // ----------------------------------------------------------------- fixtures

    private fun WebContext.viewer() {
        withRoles(
            canRead = true,
            canExecute = true,
            canAuthor = false,
            canPromote = false,
            canAdminWorkspace = false,
            isSuperAdmin = false,
            roleLabel = "viewer",
        )
    }

    private fun WebContext.promoter() {
        withRoles(
            canRead = true,
            canExecute = true,
            canAuthor = false,
            canPromote = true,
            canAdminWorkspace = false,
            isSuperAdmin = false,
            roleLabel = "promoter",
        )
    }

    private fun WebContext.author() {
        withRoles(
            canRead = true,
            canExecute = true,
            canAuthor = true,
            canPromote = false,
            canAdminWorkspace = false,
            isSuperAdmin = false,
            roleLabel = "author",
        )
    }

    private fun WebContext.source(
        readOnly: Boolean,
        selected: Int,
    ) {
        setVariable("template", template())
        setVariable("templateName", NAME)
        setVariable("workingVersion", 2)
        setVariable("readOnly", readOnly)
        setVariable("selectedVersion", selected)
        setVariable("selectedStatus", "RELEASED")
        setVariable("releasedAt", null)
        setVariable("releasedBy", null)
        setVariable("isTransform", false)
    }

    private fun WebContext.page(
        viewedEditable: Boolean,
        hasDraft: Boolean = false,
        draftView: Boolean = false,
    ) {
        source(readOnly = !viewedEditable, selected = if (draftView) 2 else 2)
        if (draftView) {
            setVariable("selectedStatus", "DRAFT")
            setVariable("readOnly", false)
        }
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("navCounts", NavCounts.Counts(1, 1))
        setVariable("templateName", NAME)
        setVariable("navCurrentPath", NAME)
        setVariable("hasSelectedBody", true)
        setVariable("viewedVersion", 2)
        setVariable("viewedLabel", if (draftView) "v2 · draft" else "v2 · released · current")
        setVariable("viewedIsDraft", draftView)
        setVariable("viewedIsCurrent", !draftView)
        setVariable("viewedStatusLabel", if (draftView) "draft" else "released")
        setVariable("currentVersion", if (draftView) null else 2)
        setVariable("viewedEditable", viewedEditable)
        setVariable("hasDraft", hasDraft)
        setVariable("draftVersion", if (hasDraft) 2 else null)
        setVariable("draftHash", if (hasDraft) "h" else null)
        setVariable("canDelete", false)
        setVariable("canDiscardCurrent", !draftView)
        setVariable("canPurgeDraftInHeader", false)
        setVariable("releasableVersion", if (hasDraft) 2 else null)
        setVariable("currentReleaseVersion", 2)
        setVariable("canAuthor", true)
        setVariable("activeTab", "source")
        setVariable("interpolations", emptyList<String>())
        setVariable("isTransform", false)
        fillUsedByFacts()
        // The selector reads the admitted history; one row of each side of the fork.
        setVariable("versions", selectorRows())
    }

    private fun WebContext.fillUsedByFacts() {
        setVariable("templateWorkspace", resolved())
        setVariable("usedBy", emptyList<Any>())
        setVariable("usedByCount", 0)
        setVariable("usedBySets", emptyList<Any>())
        setVariable("usedByVisualizations", emptyList<Any>())
        setVariable("usedBySummary", "nothing")
    }

    private fun selectorRows(): List<VersionRowView> =
        listOf(
            VersionRowView.of(
                version = 2,
                status = PipelineVersionStatus.RELEASED,
                createdAt = Instant.EPOCH,
                actor = "Muhammad",
                now = Instant.EPOCH,
                usage = 0,
                usageUnit = "pipeline",
                isCurrent = true,
                isViewed = true,
            ),
            VersionRowView.of(
                version = 1,
                status = PipelineVersionStatus.RELEASED,
                createdAt = Instant.EPOCH,
                actor = "Muhammad",
                now = Instant.EPOCH,
                usage = 1,
                usageUnit = "pipeline",
                isCurrent = false,
            ),
        )

    private fun resolved(): TemplateWorkspaceModel.Resolved =
        TemplateWorkspaceModel.Resolved(
            name = NAME,
            selected = TemplateWorkspaceModel.Selected(template(), null),
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

    private fun WebContext.versions() {
        setVariable("templateName", NAME)
        setVariable(
            "versions",
            listOf(
                VersionRowView.of(2, PipelineVersionStatus.DRAFT, Instant.EPOCH, "Muhammad", Instant.EPOCH, 0, "pipeline", false),
                VersionRowView.of(1, PipelineVersionStatus.RELEASED, Instant.EPOCH, "Muhammad", Instant.EPOCH, 1, "pipeline", true),
            ),
        )
    }

    private fun template() =
        Template(
            id = NAME,
            version = 2,
            type = TemplateType.SQL,
            dialect = Dialect.POSTGRES,
            displayName = "Revenue",
            description = "d",
            body = BODY,
            imports = listOf(co.datapipelines.templates.TemplateImport("lib/common.sql", 1, "common")),
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = UUID.randomUUID(),
        )

    /** The page's own content: everything inside `<main`, so the shell's switcher and avatar menu are not counted. */
    private fun mainOf(html: String): String = html.substringAfter("<main").substringBefore("</main>")

    /**
     * The interactive elements of a rendered fragment as `tag#id` (or `tag[name]`, or the bare
     * tag), in document order. Read from the rendered markup, so a control hidden by a guard is
     * absent and a control added without one is present — by name.
     */
    private fun interactive(html: String): List<String> =
        Regex("<(form|input|button|select|textarea)\\b([^>]*)>")
            .findAll(html)
            .map { m ->
                val tag = m.groupValues[1]
                val attrs = m.groupValues[2]
                val id = Regex("\\bid=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
                val name = Regex("\\bname=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)
                when {
                    id != null -> "$tag#$id"
                    name != null -> "$tag[$name]"
                    else -> tag
                }
            }.toList()

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
        const val NAME = "acme/revenue.sql"
        const val BODY = "SELECT revenue FROM t"
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
