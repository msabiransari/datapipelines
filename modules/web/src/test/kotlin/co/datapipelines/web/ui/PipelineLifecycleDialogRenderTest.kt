package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
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
 * The lifecycle DIALOGS' render contract (ui-screens §4.3d, 102) — Thymeleaf, no Spring
 * context, the DatasourceDetailRenderTest harness shape.
 *
 * The one rule this file pins, per dialog × per branch: **a refused branch renders NO
 * button** (the 094 §B discipline — a refusal cannot be clicked past), the allowed branch
 * renders exactly one, and the two irreversible dialogs carry the typed confirm (§5.1:
 * the field, the expectation, and a button that starts DISABLED).
 */
class PipelineLifecycleDialogRenderTest {
    @Test
    fun `release - a clean draft offers the button and states the lock`() {
        val html = renderRelease(pins = listOf(pin("demo/x.sql@3", PipelineVersionStatus.RELEASED)))

        html shouldContain "Release v3?"
        html shouldContain "the current version and locks it."
        html shouldContain ">Release v3</button>"
        html shouldContain "data-release-pins"
    }

    @Test
    fun `release - 142 - a DRAFT template pin is a checked consent row, and the button IS rendered`() {
        val html =
            renderRelease(
                pins =
                    listOf(
                        pin("demo/x.sql@2", PipelineVersionStatus.DRAFT, otherPinners = 1),
                        pin("demo/y.sql@1", PipelineVersionStatus.RELEASED),
                    ),
            )

        // The consent box: checked by default, named after the flag the service reads.
        html shouldContain "name=\"releasePinnedTemplates\""
        html shouldContain "checked data-consent-input"
        html shouldContain "Also release this draft template with the pipeline"
        // The row names the template, the version, and the other draft pipelines pinning it.
        html shouldContain "data-cascade-pin=\"demo/x.sql@2\""
        html shouldContain ", also pinned by 1 other draft pipeline<"
        // Both wordings are in the markup — the CSS shows the one the box selects — so the
        // order rule stays visible when the box is unchecked.
        html shouldContain "released with this pipeline"
        html shouldContain "release the template first"
        // The RELEASED pin is in the plain list, the draft one is NOT (one row per pin).
        html shouldContain "demo/y.sql@1"
        html shouldContain "data-release-pins"
        // And the submit exists, consent-gated.
        html shouldContain "data-verb=\"pipeline-release-confirm\" data-consent-submit"
    }

    @Test
    fun `release - 142 - the consent group pluralises and omits the shared line at zero other pinners`() {
        val html =
            renderRelease(
                pins =
                    listOf(
                        pin("demo/x.sql@2", PipelineVersionStatus.DRAFT),
                        pin("demo/z.sql@4", PipelineVersionStatus.DRAFT, otherPinners = 3),
                    ),
            )

        html shouldContain "Also release these 2 draft templates with the pipeline"
        html shouldContain ", also pinned by 3 other draft pipelines<"
        // Exactly one shared line: the zero-pinner row carries none.
        Regex("data-cascade-shared").findAll(html).count() shouldBe 1
        html shouldNotContain "<ul class=\"u-text-sm u-mb-xs\" data-release-pins"
    }

    @Test
    fun `release - 142 - a DRAFT pin beside a MISSING one is the pre-142 refused shape - no consent, no button`() {
        val html =
            renderRelease(
                pins =
                    listOf(
                        pin("demo/x.sql@2", PipelineVersionStatus.DRAFT),
                        pin("gone/missing.sql@9", null),
                    ),
            )

        html shouldContain "demo/x.sql@2"
        html shouldContain "MISSING"
        html shouldContain "release the template first"
        html shouldNotContain "data-consent-input"
        html shouldNotContain "<button type=\"submit\""
    }

    /**
     * 7e (transform-nodes design §8.2) — a pinned version that cites a RETIRED learned fact is a
     * WARNING row above the confirm, never a refusal: the row names the pin, the retired fact
     * and its successor, and the Release button is still rendered and enabled. The falsification
     * (handback F-3's twin here) moves the pin into the blocking branch and this case goes red at
     * the button.
     */
    @Test
    fun `release - 7e - a needs_review pin is a warning row above an ENABLED confirm`() {
        val retired = co.datapipelines.pipeline.RetiredFactCitation("fact-old", "superseded", "fact-new")
        val html =
            renderRelease(
                pins =
                    listOf(
                        pin("demo/rainy.jsonata@2", PipelineVersionStatus.RELEASED, retiredFacts = listOf(retired)),
                        pin("demo/x.sql@3", PipelineVersionStatus.RELEASED),
                    ),
            )

        html shouldContain "data-release-needs-review"
        html shouldContain "data-needs-review-pin=\"demo/rainy.jsonata@2\""
        html shouldContain "cites a retired fact: fact-old — superseded by fact-new"
        html shouldContain "Releasing is not blocked."
        // One row per needs-review pin: the clean pin carries none.
        html shouldNotContain "data-needs-review-pin=\"demo/x.sql@3\""
        // The confirm is untouched: rendered, and not disabled.
        html shouldContain "data-verb=\"pipeline-release-confirm\""
        html shouldContain ">Release v3</button>"
        val confirm = Regex("<button[^>]*data-verb=\"pipeline-release-confirm\"[^>]*>").find(html)!!.value
        // The standalone `disabled` attribute (hx-disabled-elt is htmx's in-flight guard, not a state).
        Regex("\\sdisabled[\\s>]").containsMatchIn(confirm) shouldBe false
    }

    @Test
    fun `release - 7e - a plain retirement names its reason, and a clean draft renders no warning block`() {
        val plain = co.datapipelines.pipeline.RetiredFactCitation("fact-old", "no longer our policy", null)
        renderRelease(pins = listOf(pin("demo/rainy.jsonata@2", PipelineVersionStatus.RELEASED, retiredFacts = listOf(plain)))) shouldContain
            "cites a retired fact: fact-old (retired: no longer our policy)"
        renderRelease(pins = listOf(pin("demo/x.sql@3", PipelineVersionStatus.RELEASED))) shouldNotContain "data-release-needs-review"
    }

    @Test
    fun `release - a MISSING template pin refuses the same way`() {
        val html = renderRelease(pins = listOf(pin("gone/missing.sql@9", null)))

        html shouldContain "MISSING"
        html shouldContain "release the template first"
        html shouldNotContain "<button type=\"submit\""
    }

    @Test
    fun `release - no draft opens the dialog and says so, with no button`() {
        val html =
            render("partials/pipeline-lifecycle-release") {
                setVariable("dlg", releaseDialog(pins = emptyList()).copy(refusal = refusal("pipeline.version.not_draft")))
                setVariable("from", "explorer")
            }

        html shouldContain "no draft"
        html shouldNotContain "<button type=\"submit\""
    }

    @Test
    fun `release - a draft WITHOUT checks keeps the pre-140 shape - no run, the submit enabled`() {
        val html = renderRelease(pins = emptyList())

        html shouldContain "No checks on this version."
        html shouldContain ">Release v3</button>"
        html shouldNotContain "checks/run"
        html shouldNotContain "hx-trigger=\"load\""
        // The enabled submit carries no disabled attribute (hx-disabled-elt is htmx's own).
        html shouldNotContain "type=\"submit\" disabled"
    }

    @Test
    fun `release - a draft WITH checks fires the run as the dialog opens and the submit starts disabled`() {
        val html =
            render("partials/pipeline-lifecycle-release") {
                setVariable("dlg", releaseDialog(pins = emptyList(), hasChecks = true))
                setVariable("from", "explorer")
            }

        // The run rides the dialog's own checks POST, asking for the release footer OOB.
        html shouldContain "hx-post=\"/partials/pipelines/$LEAF_ID/versions/3/checks/run?footer=release\""
        html shouldContain "hx-trigger=\"load\""
        html shouldContain "id=\"plc-dialog-checks\""
        // The footer placeholder exists for the OOB swap, and the submit in it is withheld
        // until the run's own fragment answers (§4.3d: never clickable past a pending run).
        html shouldContain "id=\"plc-release-footer\""
        html shouldContain "disabled"
        html shouldNotContain "data-verb=\"pipeline-release-confirm\""
    }

    @Test
    fun `purge - the button names the version and its runs, and starts disabled behind the typed confirm`() {
        val html =
            render("partials/pipeline-lifecycle-purge") {
                setVariable("dlg", purgeDialog(runCount = 3))
                setVariable("from", "explorer")
            }

        html shouldContain "Purge v4?"
        html shouldContain "Purge v4 and 3 runs"
        html shouldContain "cannot be undone"
        html shouldContain "data-confirm-input"
        html shouldContain "data-confirm-expect=\"v4\""
        html shouldContain "data-typed-confirm"
        html shouldContain "disabled"
    }

    @Test
    fun `purge - zero runs drops the plural, not the warning`() {
        val html =
            render("partials/pipeline-lifecycle-purge") {
                setVariable("dlg", purgeDialog(runCount = 0))
                setVariable("from", "explorer")
            }

        html shouldContain "Purge v4</button>"
        html shouldContain "cannot be undone"
        html shouldNotContain "0 runs"
    }

    @Test
    fun `discard - the current version states the fallback, and the button is reversible prose`() {
        val html = renderDiscard(fallback = "v1 becomes current.")

        html shouldContain "Discard v2?"
        html shouldContain "is current"
        html shouldContain "v1 becomes current."
        html shouldContain "Restore brings it back"
        html shouldContain ">Discard v2</button>"
    }

    @Test
    fun `discard - the null fallback names the 503 the endpoints will answer`() {
        val html =
            renderDiscard(fallback = "nothing eligible remains — the pipeline will have no current version, and its endpoints answer 503.")

        html shouldContain "endpoints answer 503"
        html shouldContain ">Discard v2</button>"
    }

    @Test
    fun `discard - a pinned version lists the pinners and renders NO button`() {
        val html =
            render("partials/pipeline-lifecycle-discard") {
                setVariable(
                    "dlg",
                    discardDialog().copy(
                        pinnerPipelines =
                            listOf(
                                PipelineLifecycleDialogModel.PinnerView("nyc/rollup", 3, "extract"),
                            ),
                    ),
                )
                setVariable("from", "explorer")
            }

        html shouldContain "nyc/rollup"
        html shouldContain "v3 · node extract"
        html shouldContain "cannot be discarded"
        html shouldNotContain "<button type=\"submit\""
    }

    @Test
    fun `discard - a non-current version says the pointer does not move`() {
        val html = renderDiscard(isCurrent = false, fallback = null)

        html shouldContain "not the current version"
        html shouldContain ">Discard v2</button>"
    }

    @Test
    fun `restore - states the pointer outcome both ways`() {
        val moves =
            render("partials/pipeline-lifecycle-restore") {
                setVariable("dlg", restoreDialog(movesPointer = true))
                setVariable("from", "explorer")
            }
        moves shouldContain "v2 becomes current."

        val stays =
            render("partials/pipeline-lifecycle-restore") {
                setVariable("dlg", restoreDialog(movesPointer = false))
                setVariable("from", "explorer")
            }
        stays shouldContain "The pointer stays at v3."
    }

    @Test
    fun `purge entity - the last_release branch opens with the shape's answer and NO button`() {
        val html =
            renderPurgeEntity(
                refusal =
                    PipelineLifecycleDialogModel.Refusal(
                        "pipeline.version.last_release",
                        "An entity purge requires the only version to be a DRAFT — this pipeline holds 2 version(s).",
                    ),
            )

        html shouldContain "only version to be a DRAFT"
        html shouldNotContain "<button type=\"submit\""
        html shouldNotContain "data-confirm-input"
    }

    @Test
    fun `purge entity - the checkbox lists the exclusive templates by name, and the typed confirm is the pipeline's name`() {
        val html = renderPurgeEntity(refusal = null, exclusive = listOf("test/only_here.sql", "test/also_mine.sql"))

        html shouldContain "template(s)"
        html shouldContain "pinned by nothing else"
        html shouldContain "test/only_here.sql"
        html shouldContain "test/also_mine.sql"
        html shouldContain "name=\"include_exclusive\""
        // The offer is a control of the purge FORM: an htmx hx-post submits the form's own
        // controls only, and a checkbox outside it never reaches the server (prod, 2026-09-18:
        // the owner ticked it, the pipeline was purged, the three drafts stayed).
        val form = html.substring(html.indexOf("<form"), html.indexOf("</form>"))
        form shouldContain "name=\"include_exclusive\""
        html shouldContain "data-confirm-expect=\"nyc/mobility/probe\""
        html shouldContain ">Purge probe</button>"
        html shouldContain "disabled"
    }

    @Test
    fun `switch - the rows carry the versions, the current mark, and restore-first on discarded`() {
        val html =
            render("partials/pipeline-lifecycle-switch") {
                setVariable(
                    "dlg",
                    PipelineLifecycleDialogModel.SwitchDialog(
                        id = LEAF_ID,
                        name = "nyc/mobility/probe",
                        currentVersion = 1,
                        options =
                            listOf(
                                PipelineLifecycleDialogModel.SwitchOption(3, PipelineVersionStatus.DRAFT, false, true),
                                PipelineLifecycleDialogModel.SwitchOption(2, PipelineVersionStatus.DISCARDED, false, false),
                                PipelineLifecycleDialogModel.SwitchOption(1, PipelineVersionStatus.RELEASED, true, true),
                            ),
                    ),
                )
                setVariable("preselect", null)
                setVariable("from", "explorer")
            }

        html shouldContain "role=\"radiogroup\""
        html shouldContain "value=\"3\""
        html shouldContain "value=\"2\""
        html shouldContain "value=\"1\""
        html shouldContain "current</span>"
        html shouldContain "restore first"
        // The discarded row's radio is disabled — the §3.5 refusal, shown where the row is.
        html shouldContain "disabled"
        html shouldContain "endpoints published on this pipeline"
    }

    // ------------------------------------------------------------------ fixtures

    private fun refusal(code: String) = PipelineLifecycleDialogModel.Refusal(code, "This pipeline has no draft — nothing to release.")

    private fun pin(
        label: String,
        status: PipelineVersionStatus?,
        otherPinners: Int = 0,
        retiredFacts: List<co.datapipelines.pipeline.RetiredFactCitation> = emptyList(),
    ): PipelineLifecycleDialogModel.PinView =
        PipelineLifecycleDialogModel.PinView(
            id = label.substringBefore("@"),
            version = label.substringAfter("@").toInt(),
            status = status,
            otherPinners = otherPinners,
            retiredFacts = retiredFacts,
        )

    private fun releaseDialog(
        pins: List<PipelineLifecycleDialogModel.PinView>,
        hasChecks: Boolean = false,
    ) = PipelineLifecycleDialogModel.ReleaseDialog(
        id = LEAF_ID,
        name = "nyc/mobility/probe",
        version = 3,
        updatedBy = "Muhammad",
        updatedAgo = "2 hours ago",
        updatedAt = Instant.parse("2026-09-09T10:00:00Z"),
        pins = pins,
        hasChecks = hasChecks,
        refusal = null,
    )

    private fun renderRelease(pins: List<PipelineLifecycleDialogModel.PinView>): String =
        render("partials/pipeline-lifecycle-release") {
            setVariable("dlg", releaseDialog(pins))
            setVariable("from", "explorer")
        }

    private fun purgeDialog(runCount: Int) =
        PipelineLifecycleDialogModel.PurgeDialog(
            id = LEAF_ID,
            name = "nyc/mobility/probe",
            version = 4,
            runCount = runCount,
            expected = "v4",
        )

    private fun discardDialog() =
        PipelineLifecycleDialogModel.DiscardDialog(
            id = LEAF_ID,
            name = "nyc/mobility/probe",
            version = 2,
            isCurrent = true,
            fallback = "v1 becomes current.",
            pinnerPipelines = emptyList(),
        )

    private fun renderDiscard(
        isCurrent: Boolean = true,
        fallback: String? = "v1 becomes current.",
    ): String =
        render("partials/pipeline-lifecycle-discard") {
            setVariable("dlg", discardDialog().copy(isCurrent = isCurrent, fallback = fallback))
            setVariable("from", "explorer")
        }

    private fun restoreDialog(movesPointer: Boolean) =
        PipelineLifecycleDialogModel.RestoreDialog(
            id = LEAF_ID,
            name = "nyc/mobility/probe",
            version = 2,
            currentVersion = if (movesPointer) 1 else 3,
            movesPointer = movesPointer,
        )

    private fun renderPurgeEntity(
        refusal: PipelineLifecycleDialogModel.Refusal?,
        exclusive: List<String> = emptyList(),
    ): String =
        render("partials/pipeline-lifecycle-purge-entity") {
            setVariable(
                "dlg",
                PipelineLifecycleDialogModel.PurgeEntityDialog(
                    id = LEAF_ID,
                    name = "nyc/mobility/probe",
                    leafName = "probe",
                    runCount = 1,
                    exclusiveTemplates = exclusive,
                    expected = "nyc/mobility/probe",
                    refusal = refusal,
                ),
            )
            setVariable("from", "explorer")
        }

    // ------------------------------------------------------------------ harness

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
