package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
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
 * #416 — what the four Release dialogs actually SUBMIT. Each family's form must carry exactly ONE
 * hidden hash, named the way that family's POST binds it (`bodyHash` for pipelines, templates and
 * dashboards; `body_hash` for visualizations, beside `release_pinned_templates`), valued with the hash
 * of the draft the dialog read, and placed INSIDE the form the submit button belongs to — a hash in
 * the wrong element is a form that posts nothing and a 400. The no-draft branch renders no form and
 * so no hash. The value goes through `th:value`, so a hostile hash is attribute-escaped, never
 * markup.
 *
 * Thymeleaf alone, no Spring context — the [PipelineLifecycleDialogRenderTest] harness.
 */
class ReleaseDialogHashRenderTest {
    private val families =
        listOf(
            Family(
                name = "pipeline",
                view = "partials/pipeline-lifecycle-release",
                field = "bodyHash",
                open = pipelineDialog(HASH),
                refused = pipelineDialog(HASH, refused = true),
            ),
            Family(
                name = "template",
                view = "partials/template-lifecycle-release",
                field = "bodyHash",
                open = templateDialog(HASH),
                refused = templateDialog(HASH, refused = true),
            ),
            Family(
                name = "dashboard",
                view = "partials/dashboard-lifecycle-release",
                field = "bodyHash",
                open = dashboardDialog(HASH),
                refused = dashboardDialog(HASH, refused = true),
            ),
            Family(
                name = "visualization",
                view = "partials/visualization-lifecycle-release",
                field = "body_hash",
                open = vizDialog(HASH),
                refused = vizDialog(HASH, refused = true),
            ),
        )

    @Test
    fun `every family's release form carries exactly one hidden hash, named as its POST binds it, valued with the dialog's draft`() {
        for (family in families) {
            val html = render(family.view, family.open)

            val hashInputs = HIDDEN.findAll(html).filter { it.groupValues[1] == family.field }.toList()
            withClue(family) { hashInputs.size shouldBe 1 }
            withClue(family) { hashInputs.single().groupValues[2] shouldBe HASH }
            // The forms of this partial are the ONLY submit surface: the hash lies inside one.
            val hashAt = html.indexOf(hashInputs.single().value)
            val formAt = html.lastIndexOf("<form", hashAt)
            val formEnd = html.indexOf("</form>", hashAt)
            withClue(family) { formAt shouldBeGreaterThan -1 }
            withClue(family) { formEnd shouldBeGreaterThan hashAt }
            withClue(family) { html.indexOf("type=\"submit\"", formAt) shouldBeLessThan formEnd }
        }
    }

    @Test
    fun `the pipeline hash precedes the footer placeholder, so the check run's spliced-in submit posts it too`() {
        val html = render("partials/pipeline-lifecycle-release", pipelineDialog(HASH, hasChecks = true))

        val hashAt = html.indexOf("name=\"bodyHash\"")
        val footerAt = html.indexOf("id=\"plc-release-footer\"")
        val formEnd = html.indexOf("</form>")
        hashAt shouldBeGreaterThan html.lastIndexOf("<form", hashAt)
        footerAt shouldBeGreaterThan hashAt
        formEnd shouldBeGreaterThan footerAt
    }

    @Test
    fun `a refused no-draft dialog renders no form and no hash`() {
        for (family in families) {
            val html = render(family.view, family.refused)

            withClue(family) { html shouldNotContain "<form" }
            withClue(family) { html shouldNotContain "name=\"${family.field}\"" }
        }
    }

    @Test
    fun `a hostile hash is attribute-escaped, never markup`() {
        val hostile = "\"><script>alert(1)</script>"
        val dialogs =
            mapOf(
                "partials/pipeline-lifecycle-release" to pipelineDialog(hostile),
                "partials/template-lifecycle-release" to templateDialog(hostile),
                "partials/dashboard-lifecycle-release" to dashboardDialog(hostile),
                "partials/visualization-lifecycle-release" to vizDialog(hostile),
            )
        for ((view, dialog) in dialogs) {
            val html = render(view, dialog)

            withClue(view) { html shouldNotContain "<script>alert(1)" }
            withClue(view) { html shouldNotContain hostile }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private class Family(
        val name: String,
        val view: String,
        val field: String,
        val open: Any,
        val refused: Any,
    ) {
        override fun toString() = name
    }

    private fun pipelineDialog(
        hash: String,
        hasChecks: Boolean = false,
        refused: Boolean = false,
    ) = PipelineLifecycleDialogModel.ReleaseDialog(
        id = ID,
        name = "nyc/mobility/probe",
        version = 3,
        updatedBy = if (refused) "" else "Muhammad",
        updatedAgo = if (refused) "" else "2 hours ago",
        updatedAt = if (refused) null else Instant.parse("2026-09-09T10:00:00Z"),
        pins = emptyList(),
        bodyHash = if (refused) "" else hash,
        hasChecks = hasChecks,
        refusal = if (refused) PipelineLifecycleDialogModel.Refusal("pipeline.version.not_draft", "No draft.") else null,
    )

    private fun templateDialog(
        hash: String,
        refused: Boolean = false,
    ) = TemplateLifecycleDialogModel.ReleaseDialog(
        id = "nyc/mobility/probe.sql",
        version = 2,
        updatedBy = if (refused) "" else "Muhammad",
        updatedAgo = if (refused) "" else "2 hours ago",
        updatedAt = if (refused) null else Instant.parse("2026-09-09T10:00:00Z"),
        bodyHash = if (refused) "" else hash,
        refusal = if (refused) TemplateLifecycleDialogModel.Refusal("template.version.not_draft", "No draft.") else null,
    )

    private fun dashboardDialog(
        hash: String,
        refused: Boolean = false,
    ) = DashboardLifecycleDialogModel.ReleaseDialog(
        id = ID,
        name = "finance/dashboards/revenue_overview",
        version = 2,
        updatedBy = if (refused) "" else "Muhammad",
        updatedAgo = if (refused) "" else "2 hours ago",
        updatedAt = if (refused) null else Instant.parse("2026-09-09T10:00:00Z"),
        pins = emptyList(),
        bodyHash = if (refused) "" else hash,
        refusal = if (refused) DashboardLifecycleDialogModel.Refusal("dashboard.version.not_draft", "No draft.") else null,
    )

    private fun vizDialog(
        hash: String,
        refused: Boolean = false,
    ) = VisualizationLifecycleDialogModel.ReleaseDialog(
        id = ID,
        name = "finance/visualizations/cells",
        version = 2,
        bodyHash = if (refused) "" else hash,
        updatedBy = if (refused) "" else "Muhammad",
        updatedAgo = if (refused) "" else "2 hours ago",
        caseCount = if (refused) 0 else 1,
        pin = null,
        refusals =
            if (refused) {
                listOf(VisualizationLifecycleDialogModel.Refusal("visualization.version.not_draft", "No draft."))
            } else {
                emptyList()
            },
    )

    // ------------------------------------------------------------------ harness

    private fun render(
        view: String,
        dialog: Any,
    ): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            ).withRoles()
        context.setVariable("dlg", dialog)
        context.setVariable("from", "editor")
        return COMMENT.replace(engine().process(view, context), "")
    }

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
        val ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        const val HASH = "5a1f0c7e9d3b2a48"
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        /** An `<input type="hidden" name="N" value="V">` — group 1 the name, group 2 the value. */
        val HIDDEN = Regex("""<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*>""")
    }
}
