package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 076 §D type-scale guard, swept not recalled: every app screen's page title is the design
 * system's `.ds-headline` at ONE size — the era of three hand-picked h1 sizes (text-xl on
 * settings/admin, text-2xl elsewhere, text-3xl on error pages) ended with the class
 * migration. An inline `font-size` on an `<h1>` is how the drift returns: one screen picks
 * its own size, the next copies it, and the single scale is gone.
 *
 * The sweep scope mirrors InlineWidthAuditTest — every app template, with layouts/, the
 * marketing site/ tree and the two public docs views EXCLUDED (they own their own type
 * rules and their own guards). pipelines/editor.html is IN scope: its `.pe-pipeline-title`
 * h1 (065) carries no inline font-size, which is exactly what this audit allows — the ban
 * is on hand-picked PIXEL/TOKEN sizes inline, not on headings styled by a class.
 */
class TypeScaleAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        run {
            val appTemplates =
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
            appTemplates.associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }
        }

    @Test
    fun `the sweep covers the app templates and finds the migrated headings`() {
        // Non-vacuity, both halves: the resolver must match the app's templates, and the
        // migrated ds-headline headings must be there to be found — an audit whose pattern
        // matched nothing would pass while auditing nothing.
        templates.size shouldBeGreaterThanOrEqual 30
        templates.values.count { it.contains("ds-headline") } shouldBeGreaterThanOrEqual 10
    }

    @Test
    fun `no h1 carries an inline font-size`() {
        templates.entries.shouldNotBeEmpty()

        val violations =
            templates.flatMap { (name, source) ->
                H1_TAG
                    .findAll(source)
                    .map { it.value }
                    .filter { it.contains("font-size") }
                    .map { "$name sizes its h1 inline: $it" }
            }
        violations shouldBe emptyList()
    }

    private companion object {
        val H1_TAG = Regex("""<h1\b[^>]*>""")
    }
}
