package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineVersionStatus
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
    fun `release - a DRAFT template pin is the refused colour, says release the template first, and renders NO button`() {
        val html = renderRelease(pins = listOf(pin("demo/x.sql@2", PipelineVersionStatus.DRAFT)))

        html shouldContain "demo/x.sql@2"
        html shouldContain "DRAFT"
        html shouldContain "release the template first"
        html shouldNotContain "<button type=\"submit\""
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
    ): PipelineLifecycleDialogModel.PinView =
        PipelineLifecycleDialogModel.PinView(
            id = label.substringBefore("@"),
            version = label.substringAfter("@").toInt(),
            status = status,
        )

    private fun releaseDialog(pins: List<PipelineLifecycleDialogModel.PinView>) =
        PipelineLifecycleDialogModel.ReleaseDialog(
            id = LEAF_ID,
            name = "nyc/mobility/probe",
            version = 3,
            updatedBy = "Muhammad",
            updatedAgo = "2 hours ago",
            updatedAt = Instant.parse("2026-09-09T10:00:00Z"),
            pins = pins,
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
        val LEAF_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
