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

/**
 * The template twin of [PipelineLifecycleDialogRenderTest] (ui-screens §4.3d/§4.6, 102):
 * every dialog × every branch, the refused branch buttonless, the typed confirm present
 * and disabled on the irreversible ones — plus the twin's own two facts: the in-use
 * refusal is `template.in_use`'s pinners (there is no template.version.pinned), and there
 * is NO Switch dialog anywhere (templates are pinned by version).
 */
class TemplateLifecycleDialogRenderTest {
    @Test
    fun `release - a draft offers the button with the resolved-version wording`() {
        val html =
            render("partials/template-lifecycle-release") {
                setVariable("dlg", releaseDialog())
                setVariable("from", "explorer")
            }

        html shouldContain "Release v2?"
        html shouldContain "a pin without a number"
        html shouldContain ">Release v2</button>"
    }

    @Test
    fun `release - no draft opens the dialog and says so, with no button`() {
        val html =
            render("partials/template-lifecycle-release") {
                setVariable("dlg", releaseDialog().copy(refusal = refusal()))
                setVariable("from", "explorer")
            }

        html shouldContain "no draft"
        html shouldNotContain "<button type=\"submit\""
    }

    @Test
    fun `purge - a pinned draft lists the pipelines and renders no button`() {
        val html =
            render("partials/template-lifecycle-purge") {
                setVariable("dlg", purgeDialog(inUse = listOf("nyc/rollup", "nyc/nightly")))
                setVariable("from", "explorer")
            }

        html shouldContain "nyc/rollup"
        html shouldContain "nyc/nightly"
        html shouldContain "pinned by"
        html shouldNotContain "<button type=\"submit\""
        html shouldNotContain "data-confirm-input"
    }

    @Test
    fun `purge - an unpinned draft carries the typed confirm, and the sole-draft warning names the entity`() {
        val html =
            render("partials/template-lifecycle-purge") {
                setVariable("dlg", purgeDialog(inUse = emptyList(), soleVersion = true))
                setVariable("from", "explorer")
            }

        html shouldContain "TEMPLATE goes with it"
        html shouldContain "data-confirm-expect=\"v2\""
        html shouldContain "data-typed-confirm"
        html shouldContain "disabled"
        html shouldContain ">Purge v2</button>"
    }

    @Test
    fun `discard - the resolved version states the fallback and a pinned one lists pipelines with no button`() {
        val allowed =
            render("partials/template-lifecycle-discard") {
                setVariable("dlg", discardDialog())
                setVariable("from", "explorer")
            }
        allowed shouldContain "is the resolved version"
        allowed shouldContain "v2 becomes the version a pin without a number resolves to."
        allowed shouldContain ">Discard v1</button>"

        val pinned =
            render("partials/template-lifecycle-discard") {
                setVariable("dlg", discardDialog().copy(pinnerPipelines = listOf("nyc/rollup")))
                setVariable("from", "explorer")
            }
        pinned shouldContain "nyc/rollup"
        pinned shouldNotContain "<button type=\"submit\""
    }

    @Test
    fun `restore - states the pointer outcome both ways`() {
        val moves =
            render("partials/template-lifecycle-restore") {
                setVariable("dlg", restoreDialog(movesPointer = true))
                setVariable("from", "explorer")
            }
        moves shouldContain "v1 becomes the resolved version."

        val stays =
            render("partials/template-lifecycle-restore") {
                setVariable("dlg", restoreDialog(movesPointer = false))
                setVariable("from", "explorer")
            }
        stays shouldContain "The pointer stays at v2."
    }

    @Test
    fun `purge entity - the in-use branch names who blocks, with NO button`() {
        val html =
            render("partials/template-lifecycle-purge-entity") {
                setVariable(
                    "dlg",
                    purgeEntityDialog(
                        refusal =
                            TemplateLifecycleDialogModel.Refusal(
                                "template.in_use",
                                "Pinned by 1 pipeline(s) — a pinned template is never deleted.",
                            ),
                        inUse = listOf("nyc/rollup"),
                    ),
                )
                setVariable("from", "explorer")
            }

        html shouldContain "nyc/rollup"
        html shouldContain "pinned template is never deleted"
        html shouldNotContain "<button type=\"submit\""
    }

    @Test
    fun `purge entity - an unpinned sole draft carries the NAME typed confirm`() {
        val html =
            render("partials/template-lifecycle-purge-entity") {
                setVariable("dlg", purgeEntityDialog(refusal = null))
                setVariable("from", "explorer")
            }

        html shouldContain "data-confirm-expect=\"nyc/mobility/probe.sql\""
        html shouldContain ">Purge probe.sql</button>"
        html shouldContain "disabled"
    }

    // ------------------------------------------------------------------ fixtures

    private fun refusal() = TemplateLifecycleDialogModel.Refusal("refused", "This template has no draft — nothing to release.")

    private fun releaseDialog() =
        TemplateLifecycleDialogModel.ReleaseDialog(
            id = PATH,
            version = 2,
            updatedBy = "Muhammad",
            updatedAgo = "2 hours ago",
            updatedAt = Instant.parse("2026-09-09T10:00:00Z"),
            refusal = null,
        )

    private fun purgeDialog(
        inUse: List<String>,
        soleVersion: Boolean = false,
    ) = TemplateLifecycleDialogModel.PurgeDialog(
        id = PATH,
        version = 2,
        soleVersion = soleVersion,
        inUsePipelines = inUse,
        expected = "v2",
        refusal =
            inUse.takeIf { it.isNotEmpty() }?.let {
                TemplateLifecycleDialogModel.Refusal(
                    "template.in_use",
                    "Version 2 is pinned by 2 live pipeline version(s) — repoint or discard them first.",
                )
            },
    )

    private fun discardDialog() =
        TemplateLifecycleDialogModel.DiscardDialog(
            id = PATH,
            version = 1,
            isCurrent = true,
            fallback = "v2 becomes the version a pin without a number resolves to.",
            pinnerPipelines = emptyList(),
        )

    private fun restoreDialog(movesPointer: Boolean) =
        TemplateLifecycleDialogModel.RestoreDialog(
            id = PATH,
            version = 1,
            currentVersion = if (movesPointer) null else 2,
            movesPointer = movesPointer,
        )

    private fun purgeEntityDialog(
        refusal: TemplateLifecycleDialogModel.Refusal?,
        inUse: List<String> = emptyList(),
    ) = TemplateLifecycleDialogModel.PurgeEntityDialog(
        id = PATH,
        leafName = "probe.sql",
        inUsePipelines = inUse,
        expected = PATH,
        refusal = refusal,
    )

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
        const val PATH = "nyc/mobility/probe.sql"
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }
}
