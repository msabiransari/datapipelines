package co.datapipelines.web.ui

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.Template
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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
    fun `the workspace page paints the SAME column the fragments define - one id, one definition`() {
        val html = render("templates/workspace") { workspacePage() }

        // §5's idiom, kept through #398: the page th:replace's the SAME fragment the
        // /partials/templates/editor/source and /transform-face endpoints return — one
        // `#template-source` definition, so a paint and any swap cannot disagree. The ONE
        // in-page swap that targets it is the transform face's Save (pinned by
        // TransformFaceRenderTest against the fragment); an sql draft carries no save
        // affordance at all (the body is written through the API), and a version switch is a
        // full navigation — the server re-resolves everything, which is R5 by construction.
        Regex("id=\"template-source\"").findAll(html).count() shouldBe 1
        html shouldNotContain "updateVersion"
    }

    /**
     * 097 §D — the page renders NO script BODY. Its behaviour was a ~135-line
     * `<script th:inline="javascript">`: outside StaticJsCsrfAuditTest's `static/js` sweep,
     * outside `node --test`, and carrying its own CSRF reader, an `innerHTML` row builder and
     * two blocking browser dialogs. Every `<script>` on this page is now a `src` reference.
     */
    @Test
    fun `the workspace page carries no inline script - every script tag is a src reference`() {
        // The page's OWN content fragment, without the layout: the layout keeps one
        // deliberate inline snippet (the rail-collapse flash preventer, which must run before
        // first paint and therefore cannot be a file). This rule is about the screen.
        val html = COMMENT.replace(engine().process("templates/workspace", setOf("content"), context().apply { workspacePage() }), "")

        val inline =
            SCRIPT_TAG
                .findAll(html)
                .map { it.value }
                .filterNot { it.contains("src=") }
                .toList()
        inline shouldBe emptyList()
        // Non-vacuity: the page DOES load scripts, so an empty result above is a fact about
        // their shape rather than about a regex that stopped matching.
        SCRIPT_TAG.findAll(html).count() shouldBeGreaterThanOrEqual 4
        html shouldContain "/js/template-editor/lifecycle.js"
        html shouldContain "/js/csrf.js"
        // The one server value the script used to have inlined into it is a data attribute.
        html shouldContain "data-template-id="
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

    /** The workspace page's fill: an author on the working draft (the editable view). */
    private fun WebContext.workspacePage() {
        editable("SELECT working FROM t")
        setVariable("_csrf", mapOf("token" to "t"))
        setVariable("workspaceHeaderFragment", "")
        setVariable("workspaceOptions", emptyList<Any>())
        setVariable("activeWorkspace", "acme")
        setVariable("activeTheme", "saas")
        setVariable("authenticated", true)
        setVariable("currentPath", "/templates")
        setVariable("scopes", setOf("ADMIN"))
        setVariable("templateName", NAME)
        setVariable("navCurrentPath", NAME)
        setVariable("hasSelectedBody", true)
        setVariable("viewedVersion", 2)
        setVariable("viewedLabel", "v2 · draft")
        setVariable("viewedIsDraft", true)
        setVariable("viewedIsCurrent", false)
        setVariable("viewedStatusLabel", "draft")
        setVariable("currentVersion", null)
        setVariable("viewedEditable", true)
        setVariable("canAuthor", true)
        setVariable("hasDraft", true)
        setVariable("draftVersion", 2)
        setVariable("draftHash", "h")
        setVariable("canDelete", false)
        setVariable("canDiscardCurrent", false)
        setVariable("canPurgeDraftInHeader", false)
        setVariable("releasableVersion", 2)
        setVariable("currentReleaseVersion", null)
        setVariable("activeTab", "source")
        setVariable("interpolations", emptyList<String>())
        setVariable("isTransform", false)
        setVariable("templateWorkspace", workspaceResolved())
        setVariable("usedBy", emptyList<Any>())
        setVariable("usedByCount", 0)
        setVariable("usedBySets", emptyList<Any>())
        setVariable("usedByVisualizations", emptyList<Any>())
        setVariable("usedBySummary", "nothing")
        setVariable("versions", emptyList<Any>())
    }

    private fun workspaceResolved(): TemplateWorkspaceModel.Resolved =
        TemplateWorkspaceModel.Resolved(
            name = NAME,
            selected = TemplateWorkspaceModel.Selected(template("SELECT working FROM t").copy(version = 2), null),
            draft = null,
            currentVisible = null,
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
        /** An opening script tag, with its attributes — not its body. */
        val SCRIPT_TAG = Regex("<script[^>]*>")

        const val NAME = "acme/revenue.sql"
        const val RELEASED_BODY = "SELECT released_only FROM t"
        val RELEASED_AT: Instant = Instant.parse("2026-08-02T09:30:00Z")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
