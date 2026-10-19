package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * #608 — the utility-class EXISTENCE sweep, the class-name sibling of
 * `CssTokenExistenceAuditTest` (which holds token names, not class names).
 *
 * The defect this lane paid for: thirteen template sites named six `u-*` utility classes
 * (`u-sr-only`, `u-visually-hidden`, `u-mt-0`, `u-mb-0`, `u-mr-md`, `u-ml-xs`) that NO loaded
 * stylesheet defined. An undefined class is inert, so the two screen-reader-only column
 * headers rendered as visible text and four spacing requests never applied — silently, with
 * compile, lint and every audit green, because every existing CSS audit checks how a value is
 * WRITTEN, never whether the class a template names EXISTS.
 *
 * ## Scope, stated
 *
 *   - DEFINED: every class selector that names a `u-` utility, in EVERY sheet the app serves
 *     under the static directory — the first-party sheets and the vendored design system
 *     alike — with block comments stripped so prose cannot mint definitions.
 *   - USED, two places an author names a class: the `class`, `th:class` and `th:classappend`
 *     attribute values of the app templates, and string literals in the first-party scripts
 *     under the static js directory (the template editor toggles `u-hidden` and friends by
 *     name at runtime). Double-quoted attribute values only — the corpus carries no
 *     single-quoted attributes. The marketing site tree, the two public views and any
 *     vendored script tree are excluded: separate surfaces with their own sheets and their
 *     own guards, the same boundary `InlineWidthAuditTest` draws.
 *
 * A site is one file and line; the violation message names it, so a red run points at the
 * author's line, not at a file.
 *
 * Non-vacuity is asserted in the shape that matters: the sweep must reach the real corpus
 * (template count, site count and the two utility names no template refactor should lose),
 * and the #608 fix itself is pinned — all six names that started this issue are defined.
 *
 * (Kotlin nests block comments, so a glob written into this KDoc opens or closes one. The
 * resolver patterns live in the code below; spell directories out here.)
 */
class UtilityClassExistenceAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private fun readAllStatic(pattern: String): Map<String, String> =
        resolver
            .getResources(pattern)
            .filter { it.filename != null }
            .associate { it.url.toString().substringAfter("/static/") to it.inputStream.readBytes().decodeToString() }

    /**
     * Every app template: the whole templates tree MINUS the marketing site and the two
     * public views (separate surfaces, own sheets, own guards). The shell layouts STAY in:
     * they render on app pages that load app.css, so a layout naming an undefined utility is
     * the same defect as a partial naming one.
     */
    private val templates: Map<String, String> =
        run {
            val appTemplates =
                resolver
                    .getResources("classpath*:templates/**/*.html")
                    .toList()
                    .filter { it.filename != null }
                    .filterNot { resource ->
                        val path = resource.uri.path
                        path.contains("/templates/site/") ||
                            resource.filename == "index-public.html" ||
                            resource.filename == "doc-public.html"
                    }
            appTemplates.associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }
        }

    private val sheets: Map<String, String> = readAllStatic("classpath:static/**/*.css")

    private val scripts: Map<String, String> =
        (readAllStatic("classpath:static/js/**/*.js") + readAllStatic("classpath:static/js/**/*.mjs"))
            .filterKeys { !it.startsWith("js/vendor/") }

    private fun String.withoutBlockComments(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")

    /** One class token: a word boundary, the `u-` prefix, the name. `menu-hidden` never matches. */
    private fun tokensIn(value: String): List<String> = UTILITY_TOKEN.findAll(value).map { it.value }.toList()

    /** Class token to the "file:line" sites that name it, over both halves of the corpus. */
    private val usedBy: Map<String, List<String>> by lazy {
        val found = mutableMapOf<String, MutableList<String>>()
        templates.forEach { (name, source) ->
            source.lines().forEachIndexed { index, line ->
                CLASS_ATTR.findAll(line).forEach { attribute ->
                    tokensIn(attribute.groupValues[2]).forEach { token ->
                        found.getOrPut(token) { mutableListOf() }.add("$name:${index + 1}")
                    }
                }
            }
        }
        scripts.forEach { (name, source) ->
            JS_LITERAL.findAll(source).forEach { literal ->
                val line = source.take(literal.range.first).count { it == '\n' } + 1
                tokensIn(literal.groupValues[1]).forEach { token ->
                    found.getOrPut(token) { mutableListOf() }.add("$name:$line")
                }
            }
        }
        found
    }

    /** Utility name to the sheets that define it. */
    private val definedBy: Map<String, List<String>> by lazy {
        val defs = mutableMapOf<String, MutableList<String>>()
        sheets.forEach { (name, css) ->
            UTILITY_SELECTOR.findAll(css.withoutBlockComments()).forEach { match ->
                defs.getOrPut(match.value.drop(1)) { mutableListOf() }.add(name)
            }
        }
        defs
    }

    @Test
    fun `no template or first party script names an undefined u-x utility class`() {
        val unaccounted = usedBy.keys - definedBy.keys - KNOWN_UNDEFINED
        withClue(
            "these class names are silently inert (no loaded stylesheet defines them): " +
                unaccounted.joinToString("; ") { token -> "$token <- named at ${usedBy.getValue(token).sorted()}" },
        ) {
            unaccounted.shouldBeEmpty()
        }
    }

    @Test
    fun `the sweep is not vacuous and the 608 fix is pinned`() {
        templates.size shouldBeGreaterThanOrEqual TEMPLATE_FLOOR
        val sites = linkedSetOf<String>()
        usedBy.values.forEach { sites.addAll(it) }
        val siteCount = sites.size
        println(
            "utility-class audit: ${usedBy.size} distinct u-* names at $siteCount sites; " +
                "${definedBy.size} defined by ${definedBy.values.flatten().distinct().size} sheet references",
        )
        siteCount shouldBeGreaterThanOrEqual USED_SITE_FLOOR
        usedBy.keys shouldContain "u-hidden"
        usedBy.keys shouldContain "u-mb-md"
        definedBy.keys.size shouldBeGreaterThanOrEqual DEFINED_NAME_FLOOR

        // The six names #608 started from, pinned so a revert of the app.css half fails loudly.
        // The u-visually-hidden spelling is retired BY DESIGN (its one site renamed to
        // u-sr-only; the alias rule keeps the older app-visually-hidden name defined), so the
        // canonical sr-only name is what gets pinned here.
        definedBy.keys shouldContain "u-sr-only"
        definedBy.keys shouldContain "u-mt-0"
        definedBy.keys shouldContain "u-mb-0"
        definedBy.keys shouldContain "u-mr-md"
        definedBy.keys shouldContain "u-ml-xs"
        definedBy.getValue("u-sr-only").first().endsWith("app.css") shouldBe true
    }

    @Test
    fun `the allowlist is empty`() {
        // The allowlist EXISTS so a future exception has to be written down, reviewed and dated
        // rather than smuggled in as a regex tweak. It is empty today and that is the state
        // this asserts; an entry carries its issue and dies with its last site, the same
        // ledger rules CssTokenExistenceAuditTest holds its lists to.
        KNOWN_UNDEFINED shouldBe emptySet<String>()
    }

    private companion object {
        /** A static `class="…"`, `th:class="…"` or `th:classappend="…"` attribute value. */
        val CLASS_ATTR = Regex("""(?:^|\s)((?:th:)?class(?:append)?)="([^"]*)"""")

        /** A single- or double-quoted script string literal — the shape the runtime togglers use. */
        val JS_LITERAL = Regex("""["']([^"'\n]{0,2000}?)["']""")

        val UTILITY_TOKEN = Regex("""\bu-[a-z0-9-]+\b""")

        /** A selector that defines a `u-` utility — the dot then the name. */
        val UTILITY_SELECTOR = Regex("""\.u-[a-z0-9-]+""")

        /**
         * Empty, and asserted empty above. An entry here would be a class name no stylesheet
         * defines that a template or script may keep naming; it must carry the reason and the
         * issue that tracks its fix, and it dies the moment its last site disappears.
         */
        val KNOWN_UNDEFINED = emptySet<String>()

        const val TEMPLATE_FLOOR = 30
        const val USED_SITE_FLOOR = 100
        const val DEFINED_NAME_FLOOR = 15
    }
}
