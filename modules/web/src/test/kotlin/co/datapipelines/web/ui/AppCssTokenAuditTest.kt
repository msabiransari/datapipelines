package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 079 — the token audit the round's brief assumed existed.
 *
 * The brief cited `tokensAudit` twice as the guard on "no literals in CSS". **There is no such
 * Gradle task and no such test in this repository** — the rule was written down in three docs
 * (`ui-screens.md §2.3`, `pipeline-editor.md §3.4`, `module-structure.md`) and enforced by
 * nobody. This is that guard, and it is scoped honestly to what it can actually decide.
 *
 * ## What it checks
 *
 * 1. **`app.css` writes no literal colour**, except in two places that are literals ON PURPOSE
 *    and are named here so a third one cannot slip in unremarked:
 *
 *    - the `--node-*` / `--edge-*` block at the top, where the hex is a `var(…, fallback)`
 *      SECOND argument. The Cytoscape stylesheet reads these through `getComputedStyle`, and a
 *      token that failed to resolve there would paint a graph in `unset` rather than degrade;
 *    - the six theme swatches (079 §B), for the reason below.
 *
 * 2. **Each swatch shows its theme's REAL accent.** A swatch has to display a theme the page
 *    is not currently wearing, so its colour cannot come from a live `var()` — every swatch
 *    would then paint the same. The six values are copied out of the vendored stylesheets, and
 *    a copy drifts: a design-system sync changes `themes/ocean.css`'s `--_accent-primary`, the
 *    dot keeps the old colour, and nothing anywhere says so. This test reads BOTH sides and
 *    compares them, so the drift fails the build the moment the sync lands.
 *
 * 3. **Every swatch has a theme and every palette has a swatch.** `AppShellAdvice` derives the
 *    palette list from `VendoredThemes.names()` minus the three modes; a tenth theme would
 *    render as an unstyled grey dot without this.
 *
 * ## What it deliberately does NOT check
 *
 * The other stylesheets. `pipeline-editor.css` is round 080's file this cycle and
 * `template-tree.css`/`template-editor.css` were not part of this round's restyle; widening
 * the sweep to them without reading them would either fail the build on someone else's work
 * or, worse, pass with an exception list nobody revisits. The scope is stated rather than
 * assumed — see the `the sweep names its own scope` test.
 */
class AppCssTokenAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private fun read(path: String): String =
        resolver
            .getResource("classpath:static/$path")
            .inputStream
            .readBytes()
            .decodeToString()

    private val appCss: String = read("css/app.css")

    /** `.app-swatch[data-swatch="ocean"] { background: #0369a1; }` → `ocean` to `#0369a1`. */
    private val swatches: Map<String, String> =
        SWATCH_RULE.findAll(appCss).associate { it.groupValues[1] to it.groupValues[2].lowercase() }

    /** A theme stylesheet's own `--_accent-primary`, which is what its `--accent-primary` resolves to. */
    private fun themeAccent(theme: String): String? =
        ACCENT
            .find(read("vendor/design-system/themes/$theme.css"))
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    @Test
    fun `app css carries no literal colour outside the two declared exceptions`() {
        val violations =
            appCss
                .lines()
                .withIndex()
                .filter { (_, line) -> LITERAL_COLOUR.containsMatchIn(line) }
                .filterNot { (_, line) -> line.contains("--node-") || line.contains("--edge-") }
                .filterNot { (_, line) -> SWATCH_RULE.containsMatchIn(line) }
                .filterNot { (_, line) -> line.trimStart().startsWith("*") || line.trimStart().startsWith("/*") }
                .map { (index, line) -> "app.css:${index + 1} carries a literal colour: ${line.trim()}" }

        violations shouldBe emptyList()
    }

    @Test
    fun `the sweep names its own scope, and it is not vacuous`() {
        // Non-vacuity in both directions: the file must be the real one (it is thousands of
        // lines and defines the shell), and the two declared exceptions must actually be
        // present — a sweep whose exclusions matched everything would pass while checking
        // nothing.
        appCss.lines().size shouldBeGreaterThanOrEqual MIN_LINES
        val fallbackLines = appCss.lines().count { it.contains("--node-") && LITERAL_COLOUR.containsMatchIn(it) }
        fallbackLines shouldBeGreaterThanOrEqual MIN_FALLBACK_LINES
        swatches.size shouldBe EXPECTED_PALETTES
    }

    @Test
    fun `every swatch shows the accent its own vendored theme actually declares`() {
        swatches.keys.shouldNotBeEmpty()

        val drifted =
            swatches.mapNotNull { (theme, painted) ->
                val declared = themeAccent(theme)
                when {
                    declared == null -> {
                        "$theme has a swatch but no vendored themes/$theme.css"
                    }

                    declared != painted -> {
                        "swatch for $theme paints $painted but themes/$theme.css declares --_accent-primary: $declared"
                    }

                    else -> {
                        null
                    }
                }
            }
        drifted shouldBe emptyList()
    }

    @Test
    fun `the hidden attribute outranks the design system's display classes`() {
        // A user-agent `[hidden] { display: none }` loses to ANY author class that sets
        // display — including `.ds-icon { display: inline-block }`. The first screenshot pass
        // of this round showed the top bar's sun and moon icons rendering TOGETHER for
        // exactly that reason. Every attribute-hidden element in this app (the avatar menu,
        // the mode icons, the flash bins) depends on this one rule.
        appCss shouldContain "[hidden] {"
        appCss shouldContain "display: none !important;"
    }

    @Test
    fun `a table wider than its card scrolls inside it rather than being clipped`() {
        // `overflow: hidden` on the table card sliced the endpoints table's last column off
        // at the card's edge and made it look deliberate. Scrolling keeps the content
        // reachable and still keeps the DOCUMENT from widening, which is what
        // AppShellBrowserTest's overflow assertion actually asks for.
        // Scoped to the .app-card-table RULE, not to the file: `overflow: hidden` is right in
        // several other places here (the rail clips its labels when collapsed, the avatar
        // clips its image), and a file-wide ban would be a guard that fails on correct code.
        val rule = appCss.substringAfter(".app-card-table {").substringBefore("}")
        rule shouldContain "overflow-x: auto;"
        rule shouldNotContain "overflow: hidden;"
    }

    @Test
    fun `every palette the menu offers has a swatch`() {
        // The menu's list is derived (VendoredThemes.names() minus the three modes), so a theme
        // added by a design-system sync appears there by itself — and would render as an
        // unstyled grey dot unless a swatch rule lands with it.
        val palettes = UserSettingsController.listAvailableThemes().filterNot { it in AppShellAdvice.MODES }
        palettes.shouldNotBeEmpty()
        palettes.sorted() shouldBe swatches.keys.sorted()
    }

    private companion object {
        val SWATCH_RULE = Regex("""\.app-swatch\[data-swatch="([a-z-]+)"]\s*\{\s*background:\s*(#[0-9a-fA-F]{6})""")
        val ACCENT = Regex("""--_accent-primary:\s*(#[0-9a-fA-F]{6})""")

        /** Six digits only — `#abc` is indistinguishable from an id and this file has none. */
        val LITERAL_COLOUR = Regex("""(?:#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?\b|\b(?:rgba?|hsla?)\()""")

        const val MIN_LINES = 800
        const val MIN_FALLBACK_LINES = 10
        const val EXPECTED_PALETTES = 6
    }
}
