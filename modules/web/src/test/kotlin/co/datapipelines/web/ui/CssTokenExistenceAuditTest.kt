package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 474 (#474) — the token EXISTENCE sweep, the complement to `AppCssTokenAuditTest` (which holds
 * app.css to no-literals but asks nothing about the other sheets' `var()` reads).
 *
 * The defect this lane paid for: `dashboards.css` read `var(--text-md)` and `var(--accent)` —
 * two names NO vendored or first-party sheet defines. A browser resolves an undefined custom
 * property to the guaranteed-invalid value: the crumb's `font-size` fell back to the inherited
 * size and the selected tab's underline to `currentColor`, both silently. Nothing in the repo
 * could see it, because every existing CSS audit checks how a value is WRITTEN, never whether
 * the name it names EXISTS.
 *
 * ## Scope, stated
 *
 *   - USE: every `var(--x)` in the first-party sheets (the `static/css` directory).
 *   - DEFINITION, four places the browser actually has: the first-party sheets themselves,
 *     the vendored design system tree (`tokens.css` and the themes), and the runtime writers
 *     in the `static/js` scripts, in BOTH shapes the writers take — the literal
 *     `setProperty('--x', …)` calls, and splitter.js's `prop: "--x"` declarations, where the
 *     name travels inside a variable and the literal regex cannot see it (the same fact
 *     `InlineWidthAuditTest` records for the width policy: the splitter's written token IS
 *     the sheet's one length, and its `var()` fallback is the shipped default by design —
 *     "reset to default" means "remove the property").
 *
 * ## The two defect classes, and the two known lists
 *
 * A use of an undefined name comes in two shapes, and the sweep holds them to different bars:
 *
 *   - BARE — `var(--x)` with no fallback — resolves to the guaranteed-invalid value and
 *     silently breaks the declaration. This is the class #474 reports.
 *   - WITH FALLBACK — `var(--x, #4338ca)` — degrades to the literal: functional, but a
 *     hard-coded colour that stops following the theme.
 *
 * Both ledgers have been EMPTY since #502 defined or migrated the fifteen names that
 * predate it. A NEW entry here is deliberate debt and must carry, in its comment: the
 * reason the name cannot be fixed in place, and the issue that tracks its fix. Each entry
 * dies automatically the moment its last use disappears, so both lists can only shrink.
 *
 * Non-vacuity is asserted in both directions: the sweep must see the real corpus (sheet count,
 * distinct used and defined names at their base floors), and the #474 fix itself is pinned —
 * the two rules must name the defined tokens, and the undefined names must be gone.
 */
class CssTokenExistenceAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private fun readAll(pattern: String): Map<String, String> =
        resolver
            .getResources(pattern)
            .filter { it.filename != null }
            .associate { it.url.toString().substringAfter("/static/") to it.inputStream.readBytes().decodeToString() }

    private fun String.withoutBlockComments(): String = replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")

    private val firstParty: Map<String, String> = readAll("classpath:static/css/*.css")

    private val bareUses: Map<String, Set<String>> by lazy { uses(BARE_USE) }

    private val fallbackUses: Map<String, Set<String>> by lazy { uses(FALLBACK_USE) }

    private fun uses(pattern: Regex): Map<String, Set<String>> {
        val found = mutableMapOf<String, MutableSet<String>>()
        firstParty.forEach { (name, css) ->
            pattern.findAll(css.withoutBlockComments()).forEach { found.getOrPut(it.groupValues[1]) { mutableSetOf() }.add(name) }
        }
        return found
    }

    private val definedBy: Map<String, Set<String>> by lazy {
        val defs = mutableMapOf<String, MutableSet<String>>()
        (firstParty + vendored).forEach { (name, css) ->
            DEFINITION.findAll(css.withoutBlockComments()).forEach { defs.getOrPut(it.groupValues[1]) { mutableSetOf() }.add(name) }
        }
        scripts.forEach { (name, js) ->
            SET_PROPERTY.findAll(js).forEach { defs.getOrPut(it.groupValues[1]) { mutableSetOf() }.add(name) }
            PROP_DECLARED.findAll(js).forEach { defs.getOrPut(it.groupValues[1]) { mutableSetOf() }.add(name) }
        }
        defs
    }

    private val vendored: Map<String, String> = readAll("classpath:static/vendor/design-system/**/*.css")

    private val scripts: Map<String, String> = readAll("classpath:static/js/**/*.js")

    @Test
    fun `no first party sheet reads an undefined token without a fallback`() {
        val unaccounted = bareUses.keys - definedBy.keys - KNOWN_BARE_UNDEFINED
        withClue(
            "these declarations resolve to nothing (the guaranteed-invalid value): " +
                unaccounted.joinToString("; ") { token -> "$token <- used by ${bareUses.getValue(token).sorted()}" },
        ) {
            unaccounted.shouldBeEmpty()
        }
    }

    @Test
    fun `the fallback pinned undefined names are exactly the known debt`() {
        val pinned = fallbackUses.keys - definedBy.keys
        val clue =
            "new fallback-pinned undefined names — repair them or list them here deliberately: " +
                (pinned - KNOWN_FALLBACK_UNDEFINED)
                    .joinToString("; ") { token -> "$token <- used by ${fallbackUses.getValue(token).sorted()}" }
        withClue(clue) {
            (pinned - KNOWN_FALLBACK_UNDEFINED).shouldBeEmpty()
        }
        withClue("remove these entries from KNOWN_FALLBACK_UNDEFINED: their last use is gone") {
            (KNOWN_FALLBACK_UNDEFINED - pinned).shouldBeEmpty()
        }
    }

    @Test
    fun `the known bare list holds no zombie - every entry is still an undefined name in use`() {
        val stillUndefined = bareUses.keys - definedBy.keys
        withClue("remove these entries from KNOWN_BARE_UNDEFINED: their last use is gone") {
            (KNOWN_BARE_UNDEFINED - stillUndefined).shouldBeEmpty()
        }
    }

    @Test
    fun `the sweep is not vacuous and the 474 fix is pinned`() {
        firstParty.size shouldBeGreaterThanOrEqual FIRST_PARTY_SHEET_FLOOR
        (bareUses.keys + fallbackUses.keys).size shouldBeGreaterThanOrEqual USED_NAME_FLOOR
        definedBy.keys.size shouldBeGreaterThanOrEqual DEFINED_NAME_FLOOR
        definedBy.keys shouldContain "--text-base"
        definedBy.keys shouldContain "--accent-primary"

        // #502 emptied both ledgers; the emptiness itself is pinned here so a regression in
        // the sweep's inputs cannot resurrect silent debt behind an empty-looking list.
        (bareUses.keys - definedBy.keys).shouldBeEmpty()
        (fallbackUses.keys - definedBy.keys).shouldBeEmpty()
        definedBy.getValue("--accent-danger-bg") shouldContain "css/app.css"
        definedBy.getValue("--focus-ring-width") shouldContain "css/app.css"
        definedBy.getValue("--app-content-max") shouldContain "css/app.css"
        definedBy.getValue("--measure") shouldContain "css/app.css"
        definedBy.getValue("--measure-wide") shouldContain "css/app.css"
        definedBy.getValue("--te-side-w") shouldContain "js/splitter.js"
        definedBy.getValue("--tplx-tree-w") shouldContain "js/splitter.js"

        val dashboards = firstParty.getValue("css/dashboards.css").withoutBlockComments()
        dashboards shouldContain "font-size: var(--text-base);"
        dashboards shouldContain "border-bottom-color: var(--accent-primary);"
        dashboards shouldNotContain "var(--text-md)"
        dashboards shouldNotContain "var(--accent)"
    }

    private companion object {
        val BARE_USE = Regex("""var\(\s*(--[A-Za-z0-9-]+)\s*\)""")

        val FALLBACK_USE = Regex("""var\(\s*(--[A-Za-z0-9-]+)\s*,""")

        /** A declaration — never matches inside a `var()` read, which has no colon after the name. */
        val DEFINITION = Regex("""(--[A-Za-z0-9-]+)\s*:""")

        val SET_PROPERTY = Regex("""setProperty\(\s*['"](--[A-Za-z0-9-]+)""")

        /**
         * The splitter's second write shape: the token name travels in a VARIABLE
         * (`applyProp(prop, …)` → `style.setProperty(prop, …)`), fed by `prop: "--te-side-w"`
         * style declarations, so [SET_PROPERTY] cannot see it. A name declared here is a
         * DEFINITION: the sheet's `var(--x, default)` fallback is the shipped default by
         * design ("reset" is "remove the property" — `InlineWidthAuditTest` records the same
         * fact for the width policy), not debt.
         */
        val PROP_DECLARED = Regex("""prop:\s*["'](--[A-Za-z0-9-]+)""")

        /**
         * Empty since #502 (both bare names defined or migrated, all thirteen fallback names
         * with them). A new entry is deliberate debt: its comment must carry the reason the
         * name cannot be fixed in place and the issue that tracks the fix. An entry whose
         * last use disappears fails the zombie test below until it is removed.
         */
        val KNOWN_BARE_UNDEFINED = emptySet<String>()

        /**
         * Empty since #502 (the thirteen fallback-pinned names defined or migrated; the two
         * splitter lengths count as DEFINED via [PROP_DECLARED], their fallback being the
         * design). Same ledger rules as the bare list.
         */
        val KNOWN_FALLBACK_UNDEFINED = emptySet<String>()

        const val FIRST_PARTY_SHEET_FLOOR = 11
        const val USED_NAME_FLOOR = 150
        const val DEFINED_NAME_FLOOR = 200
    }
}
