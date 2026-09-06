package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 076 §A width-policy guard, swept not recalled: every app screen's `<main>` is full-bleed
 * now, and width lives in exactly two places — the `.app-*` classes in app.css
 * (`.app-reading` for prose/cards, `.app-modal-*`/`.app-card-auth`/`.app-search-input`/
 * `.app-input-inline*` for sized boxes). An inline `max-width` or `grid-template-columns`
 * in a template is how the four-widths drift returns: one screen picks its own pixel count,
 * the next copies it with a different one, and the single policy is gone.
 *
 * The sweep covers every app template — layouts/, the marketing site/ tree and the two
 * public docs views are EXCLUDED: the shell and the site own their own width rules and are
 * audited by their own guards (SiteAssetAuditTest and friends).
 *
 * (Kotlin nests block comments, so a glob written into a KDoc opens one. Spell directories
 * out here rather than learning that again.)
 */
class InlineWidthAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .filterNot { resource ->
                val path = resource.uri.path
                path.contains("/templates/layouts/") ||
                    path.contains("/templates/site/") ||
                    resource.filename == "index-public.html" ||
                    resource.filename == "doc-public.html"
            }
            .associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `the sweep covers the app templates`() {
        // Non-vacuity: the app ships dozens of templates. A resolver that stopped matching
        // would make the audit below pass by auditing nothing.
        templates.size shouldBeGreaterThanOrEqual 30
    }

    @Test
    fun `no style attribute carries an inline width policy`() {
        templates.entries.shouldNotBeEmpty()

        val violations =
            templates.flatMap { (name, source) ->
                STYLE_ATTR
                    .findAll(source)
                    .map { it.groupValues[1] }
                    .filter { it.contains("max-width") || it.contains("grid-template-columns") }
                    .map { "$name carries an inline width: style=\"$it\"" }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `spot check - the classes replaced the inline widths`() {
        // The 076 §A conversions, pinned so a revert of the template half fails loudly:
        // the settings stack and a list-page search box carry the class, not the pixels.
        val settings = templates.getValue("settings/index.html")
        settings shouldNotContain "max-width"
        (settings.contains("app-reading")) shouldBe true

        val pipelineList = templates.getValue("pipelines/list.html")
        pipelineList shouldNotContain "max-width"
        (pipelineList.contains("app-search-input")) shouldBe true
    }

    private companion object {
        val STYLE_ATTR = Regex("""\bstyle="([^"]*)"""")
    }
}
