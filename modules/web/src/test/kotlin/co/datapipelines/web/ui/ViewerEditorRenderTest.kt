package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
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
 * 143 (T315) — the TEMPLATE editor as a READER sees it, rendered by the real engine and read
 * as DOM, not as a substring next to a role word.
 *
 * The owner's ruling: a viewer's "Open in editor" opens the template exactly as the pipeline
 * editor opens for a viewer — genuinely read-only. `readOnly` is what the controller stamps
 * (author capability combined with the version rule); what this pins is that the MARKUP obeys
 * it: the working version is a `<pre>`, never a textarea; nothing on the page posts a write;
 * the author-only Preview and its context rail are absent; the version select and the Imports
 * panel — the reads — stay. The inventory of interactive elements is asserted as an EXACT SET,
 * so a control added without a guard shows up here by name rather than passing a grep.
 *
 * The positive half is kept on purpose: an author's page still carries the textarea, Preview
 * and the context rail, and a promoter's still carries Release — "hide everything" cannot pass.
 */
class ViewerEditorRenderTest {
    @Test
    fun `a viewer's editor page is a read surface - no textarea, no preview, no context rail, no verb`() {
        val html =
            render("templates/editor") {
                page(readOnly = true)
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
        // The reads stay: the version select, the Imports table, the read-only badge line.
        html shouldContain "id=\"versionSelect\""
        html shouldContain "lib/common.sql"
        html shouldContain ">read-only<"
    }

    /** The exact interactive surface of a viewer's editor page — by element, not by grep. */
    @Test
    fun `a viewer's editor page carries exactly the version select and the shell's own controls`() {
        val html =
            render("templates/editor") {
                page(readOnly = true)
                viewer()
            }

        interactive(mainOf(html)) shouldContainExactly listOf("select#versionSelect")
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
        working shouldNotContain "previewBtn"
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
    fun `an author's editor keeps the textarea, Preview and the context rail`() {
        val html =
            render("templates/editor") {
                page(readOnly = false)
                author()
            }

        html shouldContain "id=\"templateBody\""
        html shouldContain "id=\"previewBtn\""
        html shouldContain "id=\"context-rows\""
        html shouldContain "id=\"contextJsonTextarea\""
        html shouldNotContain "data-role-note=\"read-only\""
        interactive(mainOf(html)).contains("textarea#templateBody") shouldBe true
        interactive(mainOf(html)).contains("button#previewBtn") shouldBe true
    }

    @Test
    fun `an author selecting an older version gets Edit, a reader never does`() {
        val author =
            render("partials/template-source") {
                source(readOnly = true, selected = 1)
                author()
            }
        author shouldContain "tpl-edit-version"
        // Preview stays an author's on the read-only pane too — it renders the STORED version.
        author shouldContain "previewBtn"

        val promoter =
            render("partials/template-source") {
                source(readOnly = true, selected = 1)
                promoter()
            }
        promoter shouldNotContain "tpl-edit-version"
        promoter shouldNotContain "previewBtn"
    }

    /** D5/D8 (2026-09-20): the promoter releases nothing — a read-only source, the read-only note, no verb. */
    @Test
    fun `a promoter's editor is read-only with no Release, even when a draft is pending`() {
        val html =
            render("templates/editor") {
                page(readOnly = true, hasDraft = true)
                promoter()
            }

        html shouldContain "id=\"versionBody\""
        html shouldNotContain "<textarea"
        html shouldNotContain "data-verb=\"template-release\""
        html shouldNotContain "data-verb=\"template-purge\""
        html shouldContain "data-role-note=\"read-only\""
        html shouldNotContain "previewBtn"
    }

    /** Every version row's Open names ITS version — a reader can trust which version opened. */
    @Test
    fun `every version row's Open carries that row's exact version`() {
        val html =
            render("partials/template-versions") {
                versions()
                viewer()
            }

        val opens = Regex("href=\"(/templates/editor\\?[^\"]*)\"[^>]*>Open<").findAll(html).map { it.groupValues[1] }.toList()
        opens shouldContainExactly
            listOf(
                "/templates/editor?name=acme/revenue.sql&amp;version=2",
                "/templates/editor?name=acme/revenue.sql&amp;version=1",
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
    }

    private fun WebContext.page(
        readOnly: Boolean,
        hasDraft: Boolean = false,
    ) {
        source(readOnly, selected = 2)
        setVariable("_csrf", mapOf("token" to "t", "parameterName" to "_csrf"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("navCounts", NavCounts.Counts(1, 1))
        setVariable("versions", emptyList<Any>())
        setVariable("hasDraft", hasDraft)
        setVariable("draftVersion", if (hasDraft) 2 else null)
        setVariable("draftHash", if (hasDraft) "h" else null)
    }

    private fun WebContext.versions() {
        setVariable("templateId", NAME)
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
