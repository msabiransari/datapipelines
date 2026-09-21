package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 188 (#188) — the templates carry NO inline script, NO inline handler and NO inline style:
 * the enforced CSP (`SecurityHeaders`: `script-src 'self'; style-src 'self'`, no
 * `'unsafe-inline'`, no nonce) would refuse each of them at the browser, silently for
 * the user and loudly only in a console nobody reads in production. This sweep refuses
 * them at the build instead.
 *
 * Four rules over every packaged template:
 *  1. every `<script>` without `src` is a DATA block (`type="application/json"` /
 *     `application/ld+json`) — a script that executes is a file under `/js`;
 *  2. no `on*=` handler — neither a literal attribute nor one Thymeleaf writes
 *     (`th:attr="onclick=…"`, `th:onclick`) — the plain-grep inventory that briefed 188
 *     missed the two `th:attr` rows, which is why the pattern reads both forms;
 *  3. no `style=` in any form (`style=`, `th:style`, `th:attr="style=…"`, Alpine's
 *     `x-bind:style` / `:style` — the last two write a style ATTRIBUTE, which CSP refuses;
 *     CSSOM `el.style.x =` from a file is fine and is what the page scripts use);
 *  4. no `javascript:` URL.
 *
 * Non-vacuity, twice (MISTAKES.md, "Coverage is not existence"): the sweep must have
 * seen a floor of templates AND a floor of the data blocks it deliberately permits — a
 * resolver that matched nothing would otherwise report a clean tree. Falsified at birth
 * by adding one `onclick=` to a template (188's evidence).
 *
 * Shape follows [ScriptBlockUtextAuditTest] (classpath sweep + non-vacuity).
 */
class InlineScriptAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .associate { it.uri.path.substringAfter("/templates/") to stripComments(it.inputStream.readBytes().decodeToString()) }

    /**
     * HTML comments (and Thymeleaf's parser-level `<!--/* … */-->` ones) are prose about the
     * markup, not markup — several explain exactly these rules by naming `style=` — so they
     * are blanked before the scan. Blanked, not removed, so line numbers still point home.
     */
    private fun stripComments(source: String): String =
        COMMENT.replace(source) { m -> m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }

    /** Every `<script …>` open tag, per template, with its attribute text. */
    private fun scriptTags(source: String): List<String> = SCRIPT_OPEN.findAll(source).map { it.groupValues[1] }.toList()

    @Test
    fun `the sweep covers the templates and sees the data blocks it permits`() {
        templates.size shouldBeGreaterThanOrEqual 60
        val dataBlocks = templates.values.flatMap { scriptTags(it) }.count { isDataBlock(it) }
        dataBlocks shouldBeGreaterThanOrEqual 12
        // And the controls the handlers became: a tree with no `data-action` at all would
        // mean the delegated listeners have nothing to serve, or that this rule is being
        // read against the wrong tree.
        templates.values.sumOf { DATA_ACTION.findAll(it).count() } shouldBeGreaterThanOrEqual 15
    }

    @Test
    fun `every script without src is a JSON or JSON-LD data block - a script that executes is a file`() {
        val executing =
            templates.flatMap { (name, source) ->
                scriptTags(source).filter { attrs -> !hasSrc(attrs) && !isDataBlock(attrs) }.map { "$name: <script$it>" }
            }
        executing.shouldBeEmpty()
    }

    @Test
    fun `no template carries an inline event handler - literal or written by Thymeleaf`() {
        val handlers = findAll(INLINE_HANDLER)
        handlers.shouldBeEmpty()
    }

    @Test
    fun `no template carries an inline style - literal, Thymeleaf-written or Alpine-bound`() {
        val styles = findAll(INLINE_STYLE)
        styles.shouldBeEmpty()
    }

    @Test
    fun `no template carries a javascript URL`() {
        findAll(JAVASCRIPT_URL).shouldBeEmpty()
    }

    private fun findAll(regex: Regex): List<String> =
        templates.flatMap { (name, source) ->
            regex.findAll(source).map { m -> "$name:${lineOf(source, m.range.first)}: ${m.value.trim()}" }.toList()
        }

    private fun lineOf(
        source: String,
        offset: Int,
    ): Int = source.substring(0, offset).count { it == '\n' } + 1

    private fun hasSrc(attrs: String) = SRC_ATTR.containsMatchIn(attrs)

    private fun isDataBlock(attrs: String) = DATA_TYPE.containsMatchIn(attrs)

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val SCRIPT_OPEN = Regex("""<script\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val SRC_ATTR = Regex("""\s(th:)?src\s*=""")
        val DATA_TYPE = Regex("""\stype\s*=\s*["']application/(ld\+)?json["']""")
        val DATA_ACTION = Regex("""\sdata-action\s*=""")

        /**
         * A literal handler (` onclick="`), or one Thymeleaf writes for us (`th:onclick=`,
         * `th:attr="onclick=`). Matched on the attribute-name shape, so a prose comment that
         * happens to contain the word "onclick" does not count — an `=` must follow.
         */
        val INLINE_HANDLER = Regex("""(\s|"|')(th:)?on[a-z]+\s*=""", RegexOption.IGNORE_CASE)

        /** ` style=`, `th:style=`, `th:attr="…style=`, `x-bind:style=`, `:style=`. */
        val INLINE_STYLE = Regex("""(\s|"|')(th:|x-bind:|:)?style\s*=""", RegexOption.IGNORE_CASE)

        val JAVASCRIPT_URL = Regex("""=\s*["']\s*javascript:""", RegexOption.IGNORE_CASE)
    }
}
