package co.datapipelines.web.ui

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldHaveSize
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The NEGATIVE space of R5 (054) — what selecting a version must make impossible.
 *
 * The owner's standing invariant is *"we will never modify RELEASED, period"*. The server
 * has enforced it since 035/039; R5 makes it visible on the screen, and an invariant that
 * lives in a UI has no natural test: nothing fails when a future round, wanting the version
 * view to "feel editable", drops the selected body into the textarea. These assertions are
 * the only thing standing between that round and the invariant.
 *
 *  - The editable textarea is **never** populated from a RELEASED version's selection.
 *  - No save/write control is rendered while a RELEASED version is displayed.
 *  - Edit issues exactly ONE request, to the one path that applies the lifecycle rule —
 *    the UI never carries a body, a hash, or a second draft-create of its own.
 *
 * The interaction half of the third rule (a draft present ⇒ Edit writes nothing) is a
 * controller assertion, in [TemplateEditorControllerTest].
 *
 * Engine infra mirrors [TemplateTreeRenderTest] (same WebContext shape).
 */
class TemplateEditorVersionRenderTest {
    @Test
    fun `a selected RELEASED version renders read-only - no textarea is populated from it`() {
        val html = render { readOnly(RELEASED_BODY) }

        // The body is on screen — in the read-only pane 041 built, as a <pre>.
        html shouldContain RELEASED_BODY
        html shouldContain "id=\"versionBody\""
        // ...and in NO editable surface. Not a textarea, not a disabled one, not an
        // `id="templateBody"` the save path would read. This is the assertion the
        // falsification gate flips: wire the selected body into the textarea and it goes red.
        html shouldNotContain "<textarea"
        html shouldNotContain "templateBody"
        html shouldNotContain "contenteditable"
    }

    @Test
    fun `a RELEASED version on screen offers no save or other write control`() {
        val html = render { readOnly(RELEASED_BODY) }

        // Edit is the ONE mutating affordance, and it posts to the ONE path that applies
        // the lifecycle rule. No save, no release, no in-place write of any kind.
        listOf("Save", "save", "hx-put", "hx-patch", "hx-delete", "<form")
            .forEach { html shouldNotContain it }
        val posts = Regex("hx-post=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
        posts shouldHaveSize 1
        posts.single() shouldContain "/partials/templates/editor/edit"
        posts.single() shouldContain "version=1"
    }

    @Test
    fun `Edit carries only the name and the version - never a body, never a hash`() {
        val html = render { readOnly(RELEASED_BODY) }

        html shouldContain "id=\"tpl-edit-version\""
        html shouldContain "hx-target=\"#tpl-edit-refusal\""
        // The client states WHICH version it is looking at and nothing else: the content to
        // copy and the hash to base it on are the server's to read (a client-supplied body
        // would be an edit of a RELEASED version by another name).
        html shouldNotContain "data-hash"
        html shouldNotContain "data-body"
        // The badge states what the row is, and the release provenance is on screen.
        html shouldContain ">RELEASED<"
        // The release stamp, rendered the way every other version surface in this app
        // renders one (`#temporals.format` — the SERVER's zone, no zone marker; matching
        // partials/template-versions rather than inventing a second convention here).
        html shouldContain
            DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(RELEASED_AT)
        html shouldContain ACTOR.toString()
    }

    @Test
    fun `the working version keeps the editable textarea and shows no read-only pane`() {
        val html = render { editable("SELECT working FROM t") }

        html shouldContain "id=\"templateBody\""
        html shouldContain "SELECT working FROM t"
        html shouldNotContain "id=\"versionBody\""
        html shouldNotContain "tpl-edit-version"
    }

    @Test
    fun `the editor page paints the SAME fragment the version swap targets`() {
        val html = render("templates/editor") { editorPage() }

        // §5's idiom: one definition, so the first paint and every later swap agree.
        html shouldContain "id=\"template-source\""
        html shouldContain "hx-target=\"#template-source\""
        html shouldContain "hx-swap=\"outerHTML\""
        Regex("hx-get=\"([^\"]*)\"").find(html)!!.groupValues[1] shouldContain "/partials/templates/editor/source"
        // The dead 041 handler is gone — the select no longer calls a function that
        // blanked the query string and reloaded the same version.
        html shouldNotContain "updateVersion"
    }

    // ----------------------------------------------------------------- fixtures

    private fun WebContext.readOnly(body: String) {
        base(body)
        setVariable("readOnly", true)
        setVariable("selectedVersion", 1)
        setVariable("selectedStatus", "RELEASED")
        setVariable("releasedAt", RELEASED_AT)
        setVariable("releasedBy", ACTOR.toString())
    }

    private fun WebContext.editable(body: String) {
        base(body)
        setVariable("readOnly", false)
        setVariable("selectedVersion", 2)
        setVariable("selectedStatus", "RELEASED")
        setVariable("releasedAt", null)
        setVariable("releasedBy", null)
    }

    private fun WebContext.base(body: String) {
        setVariable("template", template(body))
        setVariable("templateName", NAME)
        setVariable("workingVersion", 2)
    }

    private fun WebContext.editorPage() {
        editable("SELECT working FROM t")
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("scopes", setOf("ADMIN"))
        setVariable("versions", emptyList<Any>())
        setVariable("hasDraft", false)
        setVariable("draftVersion", null)
        setVariable("draftHash", null)
    }

    private fun template(body: String) =
        Template(
            id = NAME,
            version = 1,
            type = TemplateType.SQL,
            dialect = Dialect.POSTGRES,
            displayName = "Revenue",
            description = "d",
            body = body,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            createdBy = ACTOR,
        )

    private fun render(
        view: String = "partials/template-source",
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
        const val RELEASED_BODY = "SELECT released_only FROM t"
        val RELEASED_AT: Instant = Instant.parse("2026-08-02T09:30:00Z")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
