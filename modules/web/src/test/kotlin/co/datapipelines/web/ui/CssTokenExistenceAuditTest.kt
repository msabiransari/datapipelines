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
 *   - DEFINITION, three places the browser actually has: the first-party sheets themselves,
 *     the vendored design system tree (`tokens.css` and the themes), and the runtime writers
 *     (`setProperty('--x', …)` calls in the `static/js` scripts — the layout tokens the tree
 *     and table scripts measure at run time).
 *
 * ## The two defect classes, and the two known lists
 *
 * A use of an undefined name comes in two shapes, and the sweep holds them to different bars:
 *
 *   - BARE — `var(--x)` with no fallback — resolves to the guaranteed-invalid value and
 *     silently breaks the declaration. This is the class #474 reports, and after the fix it
 *     must stay EMPTY except for two names that predate this lane (`--accent-danger-bg`,
 *     `--surface-secondary`, in app.css and template-editor.css): repairing them means
 *     choosing replacement colours for other rounds' work, which the lane's fence forbids.
 *     They are NAMED here, tracked in #502, and each entry dies automatically
 *     the moment its last use disappears, so both lists can only shrink.
 *   - WITH FALLBACK — `var(--x, #4338ca)` — degrades to the literal: functional, but a
 *     hard-coded colour that stops following the theme. Thirteen such names predate the lane
 *     across six sheets; same ledger, same zombie rule.
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
        withClue("new fallback-pinned undefined names — repair them or list them here deliberately: " +
            (pinned - KNOWN_FALLBACK_UNDEFINED).joinToString("; ") { token -> "$token <- used by ${fallbackUses.getValue(token).sorted()}" }) {
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
         * Bare undefined uses that predate this lane — silent breakage, verified definition-free
         * across kt, js, html, css and json at the lane's base. Tracked for a fix of their own
         * (each needs a replacement token chosen); an entry leaves this list only by that fix or
         * by its last use disappearing.
         */
        val KNOWN_BARE_UNDEFINED = setOf("--accent-danger-bg", "--surface-secondary")

        /**
         * Fallback-pinned undefined names that predate this lane — functional, but each pins a
         * literal where a theme token should read. Same ledger rules as the bare list.
         */
        val KNOWN_FALLBACK_UNDEFINED =
            setOf(
                "--accent",
                "--accent-danger-bg",
                "--accent-soft",
                "--app-content-max",
                "--border",
                "--focus-ring-width",
                "--focus-width",
                "--gap-2xs",
                "--measure",
                "--measure-wide",
                "--te-side-w",
                "--text",
                "--tplx-tree-w",
            )

        const val FIRST_PARTY_SHEET_FLOOR = 11
        const val USED_NAME_FLOOR = 150
        const val DEFINED_NAME_FLOOR = 200
    }
}
