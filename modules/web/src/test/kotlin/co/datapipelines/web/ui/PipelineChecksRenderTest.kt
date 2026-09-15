package co.datapipelines.web.ui

import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunVerdict
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
 * The checks partial's render contract (140, ui-screens §4.3b/§4.3d) — Thymeleaf, no Spring
 * context, the [PipelineLifecycleDialogRenderTest] harness shape.
 *
 * Pinned per branch: the run-status chip idiom (a pass reads `app-chip-ok`, a fail
 * `app-chip-bad`, an error `app-chip-warn`, never-run a plain badge), the expectation as ONE
 * compact clause, the error's reason in place of an observed value — and the release dialog's
 * OOB footer: present only when asked for (`releaseFooter`), all pass renders the enabled
 * Release submit, anything short of PASS renders the override disclosure (the textarea's
 * minlength, the overridden ids, the audit copy) and NO enabled normal submit.
 */
class PipelineChecksRenderTest {
    @Test
    fun `the expectation renders as one compact clause per kind`() {
        CheckEntryView.expectedText(CheckExpectation(kind = "value", value = 74.62, tolerance = 0.01)) shouldBe "= 74.62 ± 0.01"
        CheckEntryView.expectedText(CheckExpectation(kind = "value", value = 1.0)) shouldBe "= 1"
        CheckEntryView.expectedText(CheckExpectation(kind = "range", min = 74.0, max = 75.0)) shouldBe "74 ≤ x ≤ 75"
        CheckEntryView.expectedText(CheckExpectation(kind = "rows", rows = 6)) shouldBe "6 rows"
    }

    @Test
    fun `a run list renders the verdict chips, the expected clause and the server's observed`() {
        val html =
            renderChecks(
                entries =
                    listOf(
                        entry("share_matches", CheckRunVerdict.PASS, observed = "74.62"),
                        entry("two_columns", CheckRunVerdict.ERROR, observed = null, message = "The statement returned two columns."),
                        entry("never_ran", null, observed = null),
                    ),
            )

        html shouldContain "app-chip-ok"
        html shouldContain ">Pass</span>"
        html shouldContain "app-chip-warn"
        html shouldContain "The statement returned two columns."
        html shouldContain "not run"
        html shouldContain "= 74.62 ± 0.01"
        html shouldContain ">74.62</span>"
        html shouldContain " · via ui"
        // No footer was asked for: no OOB element reaches surfaces that have no placeholder.
        html shouldNotContain "hx-swap-oob"
        html shouldNotContain "plc-release-footer"
    }

    @Test
    fun `an error row shows its reason where the observed value would be`() {
        val html =
            renderChecks(
                entries =
                    listOf(
                        entry("two_columns", CheckRunVerdict.ERROR, observed = null, message = "The database refused the statement."),
                    ),
            )

        html shouldContain "app-chip-warn"
        html shouldContain "The database refused the statement."
        html shouldNotContain ">74.62</span>"
    }

    @Test
    fun `a version with no checks says so`() {
        val html = renderChecks(entries = emptyList())

        html shouldContain "No checks on this version."
        html shouldNotContain "plc-check-row"
    }

    @Test
    fun `the release footer - all pass offers the enabled Release submit`() {
        val html =
            renderChecks(
                entries = listOf(entry("share_matches", CheckRunVerdict.PASS, observed = "74.62")),
                releaseFooter = true,
            )

        html shouldContain "id=\"plc-release-footer\""
        html shouldContain "hx-swap-oob=\"true\""
        html shouldContain "checks pass."
        html shouldContain "data-verb=\"pipeline-release-confirm\""
        html shouldContain ">Release v3</button>"
        html shouldNotContain "plc-override"
        html shouldNotContain "overrideChecksReason"
    }

    @Test
    fun `the release footer - a failing check withholds Release and renders the override disclosure`() {
        val html =
            renderChecks(
                entries =
                    listOf(
                        entry("share_matches", CheckRunVerdict.FAIL, observed = "74.62"),
                        entry("count_in_range", CheckRunVerdict.PASS, observed = "5"),
                    ),
                releaseFooter = true,
            )

        html shouldContain "checks did not pass — releasing is blocked."
        // No enabled normal submit: the override path is the only way forward.
        html shouldNotContain "data-verb=\"pipeline-release-confirm\""
        html shouldContain "name=\"overrideChecksReason\""
        html shouldContain "minlength=\"10\""
        html shouldContain "required"
        // The copy names the overridden ids and says where the reason lands.
        html shouldContain "share_matches"
        html shouldContain "audit event"
        // The arm rule rides lifecycle-dialog.js's min-chars pair (the dialogs' own client
        // layer — Alpine is the editor's, not the explorers') and starts disabled in markup.
        html shouldContain "data-min-chars-input"
        html shouldContain "data-min-chars=\"10\""
        html shouldContain "data-min-chars-submit"
        html shouldContain "disabled"
        html shouldNotContain "x-data"
        html shouldContain ">Release v3 anyway</button>"
    }

    // ------------------------------------------------------------------ harness

    private fun entry(
        checkId: String,
        verdict: CheckRunVerdict?,
        observed: String?,
        message: String? = null,
    ) = CheckEntryView(
        checkId = checkId,
        // Deliberately NOT derived from the id: a shouldContain on the id is then evidence
        // about the override copy, not about the row's name cell.
        name = "A named check",
        datasource = "sample-lake",
        expectedText = CheckEntryView.expectedText(CheckExpectation(kind = "value", value = 74.62, tolerance = 0.01)),
        observed = observed,
        verdict = verdict,
        message = message,
        ranAt = if (verdict != null) Instant.parse("2026-09-14T10:00:00Z") else null,
        ranAgo = if (verdict != null) "just now" else null,
        via = if (verdict != null) "ui" else null,
    )

    private fun renderChecks(
        entries: List<CheckEntryView>,
        releaseFooter: Boolean = false,
    ): String {
        val failing = entries.filter { it.verdict != null && it.verdict != CheckRunVerdict.PASS }
        return render("partials/pipeline-checks") {
            setVariable("pipelineId", LEAF_ID)
            setVariable("version", 3)
            setVariable("checks", entries)
            setVariable("hasChecks", entries.isNotEmpty())
            setVariable("allPass", entries.isNotEmpty() && failing.isEmpty() && entries.all { it.verdict != null })
            setVariable("anyFailing", failing.isNotEmpty())
            setVariable("failingIds", failing.map { it.checkId })
            setVariable("releaseFooter", releaseFooter)
        }
    }

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
        val LEAF_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
