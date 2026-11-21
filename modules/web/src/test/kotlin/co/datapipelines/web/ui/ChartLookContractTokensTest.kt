package co.datapipelines.web.ui

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * The look contract's rows LA1 and LA12 (look contract draft 8, store `29384ed90`, §3, §5.1 and §10; lane 991 D4):
 * what the engine draws and what the product's stylesheet styles are one look.
 *
 * - **LA1, the engine:** every token `05-theme.js` holds a default of has its `light` and `dark` defaults equal to
 *   §3's Light and Dark columns, name by name. `--chart-pad` and `--chart-tip-shadow` are the stylesheet's alone.
 * - **LA12, the product's stylesheet** (`css/app.css`, `tokens.css` and the nine theme files, read as text): (a) the
 *   chart's own tokens set once, to §3's Light value under a light colour scheme and its Dark value under a dark one,
 *   and no theme file sets a chart token; (b) the seven theme-following tokens are a `var()` of §5.1's design-system
 *   token and resolve, through `light.css` and `dark.css`, to §3's two columns; (c) on each of the ten card surfaces
 *   every series slot has 3 : 1 against the card and the inks 4.5 : 1, with ONE list of exceptions.
 *
 * The expected values below are the contract's tables, copied from its text; never read from the engine or the
 * stylesheet. The contrast arithmetic is WCAG 2's relative luminance, the same the contract's `theme-check/` used.
 */
class ChartLookContractTokensTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private fun read(path: String): String =
        resolver
            .getResource("classpath:static/$path")
            .inputStream
            .readBytes()
            .decodeToString()

    private val appCss = declarations(read("css/app.css"))
    private val tokensCss = declarations(read("vendor/design-system/tokens.css"))
    private val themes = THEMES.associateWith { declarations(read("vendor/design-system/themes/$it.css")) }

    @Test
    fun `LA1 - the engine's light and dark defaults are the contract's tables, name by name`() {
        val source = JsRenderedEngineFiles.sources.getValue("05-theme.js")
        val block = source.substringAfter("var TOKENS = {").substringBefore("\n  };")
        val lines = block.lines().map(String::trim).filter { it.startsWith("\"--") }
        val engine =
            lines.associate { line ->
                val m = requireNotNull(ENGINE_LINE.matchEntire(line)) { "an engine token line this test cannot read: $line" }
                m.groupValues[1] to (engineValue(m.groupValues[2]) to engineValue(m.groupValues[3]))
            }
        val expected = CONTRACT - STYLESHEET_ONLY
        engine.size shouldBe lines.size
        engine.size shouldBe ENGINE_TOKEN_COUNT
        engine.keys shouldBe expected.keys
        val problems =
            expected.flatMap { (name, values) ->
                val (light, dark) = engine.getValue(name)
                listOfNotNull(
                    "$name light: engine $light, contract ${values.first}".takeIf { light != norm(values.first) },
                    "$name dark: engine $dark, contract ${values.second}".takeIf { dark != norm(values.second) },
                )
            }
        problems shouldBe emptyList()
    }

    @Test
    fun `LA12 a - each chart-own token is set once, to the Light value under a light scheme and the Dark under a dark one`() {
        val chart = appCss.filter { it.name.startsWith(CHART_PREFIX) }
        val darkContexts = surfaces().filter { it.scheme == DARK }.map { it.appCssContext }.toSet()
        darkContexts.size shouldBe 2
        val problems = mutableListOf<String>()
        chart
            .map { it.name }
            .toSet()
            .minus(CONTRACT.keys)
            .forEach { problems += "$it is no token of the contract's §3" }
        chart
            .groupBy { it.name to it.context }
            .filterValues { it.size > 1 }
            .keys
            .forEach { problems += "${it.first} twice in ${it.second}" }
        val own = CONTRACT.keys - FOLLOWING.keys - DESIGN_SYSTEM_FONT
        own.size shouldBe OWN_TOKEN_COUNT
        for (name in own) {
            val (light, dark) = CONTRACT.getValue(name)
            val inRoot = chart.filter { it.name == name && it.context == ROOT }
            val elsewhere = chart.filter { it.name == name && it.context != ROOT }
            if (inRoot.size != 1) {
                problems += "$name is declared ${inRoot.size} times in :root, not once"
                continue
            }
            val root = inRoot.single().value
            if (resolve(root, emptyMap(), LIGHT) != norm(light)) problems += "$name under light: $root, contract $light"
            if (elsewhere.isEmpty()) {
                if (resolve(root, emptyMap(), DARK) != norm(dark)) problems += "$name under dark: $root, contract $dark"
            } else {
                if (elsewhere.map { it.context }.toSet() != darkContexts) problems += "$name overridden in ${elsewhere.map { it.context }}"
                elsewhere
                    .filter { norm(it.value) != norm(dark) }
                    .forEach { problems += "$name in ${it.context}: ${it.value}, contract $dark" }
            }
        }
        val elsewhereSet = (themes.values.flatten() + tokensCss).filter { it.name.startsWith(CHART_PREFIX) }
        elsewhereSet.forEach { problems += "a design-system file sets ${it.name}" }
        problems shouldBe emptyList()
    }

    @Test
    fun `LA12 b - the seven theme-following tokens are a var of the design-system token and equal the tables in light and dark`() {
        val problems = mutableListOf<String>()
        FOLLOWING.forEach { (name, systemToken) ->
            val declared = appCss.filter { it.name == name }
            if (declared.map { it.context to norm(it.value) } != listOf(ROOT to "var($systemToken)")) {
                problems += "$name is declared ${declared.map { "${it.context}: ${it.value}" }}, not once as var($systemToken)"
            }
        }
        for ((theme, column) in listOf(LIGHT to 0, DARK to 1)) {
            val surface = surfaces().single { it.label == "$theme[0]" }
            FOLLOWING.keys.forEach { name ->
                val contract = CONTRACT.getValue(name).toList()[column]
                val resolved = resolve("var($name)", surface.vars, surface.scheme)
                if (resolved != norm(contract)) problems += "$name through $theme.css: $resolved, contract $contract"
            }
        }
        problems shouldBe emptyList()
    }

    @Test
    fun `LA12 c - on every card surface the series have 3 to 1 and the inks 4,5 to 1, forest's two slots listed with their values`() {
        val all = surfaces()
        all.map { it.label } shouldBe SURFACE_LABELS
        val measured = mutableListOf<Triple<String, String, Double>>()
        for (s in all) {
            fun colour(token: String) = resolve("var($token)", s.vars, s.scheme)
            val card = colour("--chart-canvas")
            val tip = colour("--chart-tip-surface")
            (1..SERIES).forEach { measured += Triple(s.theme, "series $it", contrast(colour("--chart-series-$it"), card)) }
            measured += Triple(s.theme, "ink/card", contrast(colour("--chart-ink"), card))
            measured += Triple(s.theme, "ink-2/card", contrast(colour("--chart-ink-2"), card))
            measured += Triple(s.theme, "muted/card", contrast(colour("--chart-muted"), card))
            measured += Triple(s.theme, "ink/tip", contrast(colour("--chart-tip-ink"), tip))
            measured += Triple(s.theme, "muted/tip", contrast(colour("--chart-muted"), tip))
        }
        measured.size shouldBe SURFACE_LABELS.size * (SERIES + TEXT_PAIRS)
        val problems = mutableListOf<String>()
        for ((theme, pair, ratio) in measured) {
            val floor = if (pair.startsWith("series")) SERIES_FLOOR else TEXT_FLOOR
            val listed = EXCEPTIONS[theme to pair]
            when {
                listed != null && round2(ratio) != listed -> problems += "$theme $pair is listed at $listed but measures ${round2(ratio)}"
                listed == null && ratio < floor -> problems += "$theme $pair measures ${round2(ratio)}, under $floor and not listed"
            }
        }
        EXCEPTIONS.keys
            .filter { key -> measured.none { (it.first to it.second) == key } }
            .forEach { problems += "listed $it is never measured" }
        problems shouldBe emptyList()
    }

    /** One card surface: a theme file's `:root`, or the dark half of `auto` under its `@media` block. */
    private data class Surface(
        val label: String,
        val theme: String,
        val scheme: String,
        val vars: Map<String, String>,
        val appCssContext: List<String>,
    )

    private fun surfaces(): List<Surface> {
        val base = rootVars(tokensCss) + rootVars(appCss)
        return THEMES.flatMap { theme ->
            val decls = themes.getValue(theme)
            val root = rootVars(decls)
            val first = Surface("$theme[0]", theme, schemeOf(root), base + root, listOf(":root[data-theme=\"$theme\"]"))
            val media = decls.filter { it.context.size == 2 && it.context[0].contains(DARK_QUERY) && it.context[1] == ":root" }
            if (media.isEmpty()) {
                listOf(first)
            } else {
                val merged = root + media.associate { it.name to it.value }
                val context = listOf(media[0].context[0], ":root[data-theme=\"$theme\"]")
                listOf(first, Surface("$theme[1]", theme, schemeOf(merged), base + merged, context))
            }
        }
    }

    private fun rootVars(decls: List<Decl>): Map<String, String> = decls.filter { it.context == ROOT }.associate { it.name to it.value }

    private fun schemeOf(vars: Map<String, String>): String =
        requireNotNull(vars["color-scheme"]) { "a theme without color-scheme" }.trim().split(Regex("\\s+")).first()

    private data class Decl(
        val context: List<String>,
        val name: String,
        val value: String,
    )

    companion object {
        private const val CHART_PREFIX = "--chart-"
        private const val LIGHT = "light"
        private const val DARK = "dark"
        private const val SERIES = 8
        private const val TEXT_PAIRS = 5
        private const val SERIES_FLOOR = 3.0
        private const val TEXT_FLOOR = 4.5
        private const val ENGINE_TOKEN_COUNT = 56
        private const val OWN_TOKEN_COUNT = 50
        private const val DESIGN_SYSTEM_FONT = "--font-sans"
        private const val DARK_QUERY = "prefers-color-scheme: dark"
        private val ROOT = listOf(":root")

        private fun t(
            light: String,
            dark: String = light,
        ) = light to dark

        /**
         * The look contract's §3, Light and Dark, written from its text: §3.1, §3.2, §3.3, §3.4, §3.5 (draft 8), and §3.6's
         * thirteen colours with a meaning and the two-sided ramp (draft 13, the owner's pick L9; brief 1053 D4).
         */
        private val CONTRACT: Map<String, Pair<String, String>> =
            linkedMapOf(
                "--chart-canvas" to t("#ffffff", "#171b21"),
                "--chart-plot" to t("transparent"),
                "--chart-grid" to t("#e6e9ee", "#262c35"),
                "--chart-axis" to t("#a7acb4", "#616772"),
                "--chart-ink" to t("#0f172a", "#e9ecf2"),
                "--chart-ink-2" to t("#334155", "#a6afbf"),
                "--chart-muted" to t("#64748b", "#8993a5"),
                "--chart-on-fill-light" to t("#ffffff"),
                "--chart-on-fill-dark" to t("#111418"),
                "--font-sans" to t("Noto Sans"),
                "--chart-series-1" to t("#3b5bdb", "#5c7cfa"),
                "--chart-series-2" to t("#e8590c", "#e8590c"),
                "--chart-series-3" to t("#0ca678", "#0fa678"),
                "--chart-series-4" to t("#c98500", "#b88a00"),
                "--chart-series-5" to t("#d6336c", "#e64980"),
                "--chart-series-6" to t("#1098ad", "#1299ac"),
                "--chart-series-7" to t("#7048e8", "#9775fa"),
                "--chart-series-8" to t("#5c940d", "#66a80f"),
                "--chart-seq-1" to t("#ebeffb", "#222b44"),
                "--chart-seq-2" to t("#cbd2ea", "#323e63"),
                "--chart-seq-3" to t("#aab4d9", "#425081"),
                "--chart-seq-4" to t("#8a97c8", "#5363a0"),
                "--chart-seq-5" to t("#6979b7", "#6376be"),
                "--chart-seq-6" to t("#495ca6", "#7388dd"),
                "--chart-seq-7" to t("#283e95", "#839bfb"),
                "--chart-positive" to t("#1971c2", "#4dabf7"),
                "--chart-negative" to t("#d9480f", "#ff922b"),
                "--chart-total" to t("#343a40", "#dee2e6"),
                "--chart-good" to t("#00755a", "#42ae8e"),
                "--chart-warn" to t("#ce7c04", "#fab72a"),
                "--chart-bad" to t("#cd171e", "#d42325"),
                "--chart-div-1" to t("#283e95", "#839bfb"),
                "--chart-div-2" to t("#6279b6", "#6375b5"),
                "--chart-div-3" to t("#a5b3d5", "#465174"),
                "--chart-div-4" to t("#eceef2", "#2a2f38"),
                "--chart-div-5" to t("#d7a699", "#674c3b"),
                "--chart-div-6" to t("#b95d42", "#a86734"),
                "--chart-div-7" to t("#7f2400", "#ee820b"),
                "--chart-text" to t("12"),
                "--chart-text-small" to t("11"),
                "--chart-title" to t("14"),
                "--chart-title-weight" to t("600"),
                "--chart-bar-radius" to t("4"),
                "--chart-gap" to t("2"),
                "--chart-line" to t("2"),
                "--chart-stroke" to t("1"),
                "--chart-point" to t("4"),
                "--chart-cell-radius" to t("2.5"),
                "--chart-pad" to t("16px"),
                "--chart-area-top" to t("0.38"),
                "--chart-area-bottom" to t("0.02"),
                "--chart-dim" to t("0.25"),
                "--chart-ease" to t("120"),
                "--chart-tip-surface" to t("#ffffff", "#232831"),
                "--chart-tip-ink" to t("#0f172a", "#e9ecf2"),
                "--chart-tip-border" to t("#e3e6ea", "#2b313a"),
                "--chart-tip-shadow" to
                    t(
                        "drop-shadow(0 6px 12px rgba(15,23,42,.14)) drop-shadow(0 1px 1.5px rgba(15,23,42,.10))",
                        "drop-shadow(0 8px 14px rgba(0,0,0,.5)) drop-shadow(0 1px 1.5px rgba(0,0,0,.4))",
                    ),
                "--chart-tip-radius" to t("10"),
            )

        /** §3.7: the two tokens the engine never receives. */
        private val STYLESHEET_ONLY = setOf("--chart-pad", "--chart-tip-shadow")

        /** §5.1's first row: the token that follows the theme → the design-system token it is a `var()` of. */
        private val FOLLOWING =
            linkedMapOf(
                "--chart-canvas" to "--surface-default",
                "--chart-ink" to "--text-primary",
                "--chart-ink-2" to "--text-secondary",
                "--chart-muted" to "--text-muted",
                "--chart-axis" to "--border-subtle",
                "--chart-tip-surface" to "--surface-raised",
                "--chart-tip-ink" to "--text-primary",
            )

        private val THEMES = listOf("auto", "dark", "forest", "healthcare", "light", "minimal", "ocean", "professional", "saas")

        private val SURFACE_LABELS = THEMES.flatMap { if (it == "auto") listOf("auto[0]", "auto[1]") else listOf("$it[0]") }

        /** LA12's ONE list of exceptions (§5.1, O-2): forest's card `#faf8f3`, accepted as measured, not fixed. */
        private val EXCEPTIONS = mapOf(("forest" to "series 3") to 2.94, ("forest" to "series 4") to 2.89)

        private const val VALUE = """hex\("#[0-9a-fA-F]{6}"\)|TRANSPARENT|"[^"]*"|-?\d+(?:\.\d+)?"""
        private val ENGINE_LINE = Regex("""^"(--[a-z0-9-]+)":\s*\["\w+",\s*($VALUE),\s*($VALUE)(?:,\s*-?[\d.]+,\s*-?[\d.]+)?],$""")
        private val VAR = Regex("""^var\((--[\w-]+)\)$""")
        private val LIGHT_DARK = Regex("""^light-dark\(([^,]+),([^,]+)\)$""")
        private val NUMBER = Regex("""^-?\d+(?:\.\d+)?$""")

        private fun engineValue(raw: String): String =
            when {
                raw.startsWith("hex(") -> norm(raw.substring(5, 12))
                raw == "TRANSPARENT" -> "transparent"
                raw.startsWith("\"") -> raw.trim('"')
                else -> norm(raw)
            }

        /** One spelling per value: lower-case colours, six-digit hex, numbers without trailing zeros, no spaces around `,()`. */
        private fun norm(value: String): String {
            val v = value.trim().replace(Regex("\\s+"), " ")
            return when {
                NUMBER.matches(v) -> {
                    BigDecimal(v).stripTrailingZeros().toPlainString()
                }

                v.matches(Regex("^#[0-9a-fA-F]{3}$")) -> {
                    "#" +
                        v
                            .drop(1)
                            .map { "$it$it" }
                            .joinToString("")
                            .lowercase()
                }

                v.startsWith("#") -> {
                    v.lowercase()
                }

                v.first().isUpperCase() -> {
                    v
                }

                else -> {
                    v.lowercase().replace(Regex("\\s*([,()])\\s*"), "$1")
                }
            }
        }

        /** A value as a page under [scheme] computes it: `var()` followed through [vars], `light-dark()` picked. */
        private fun resolve(
            value: String,
            vars: Map<String, String>,
            scheme: String,
            hops: Int = 0,
        ): String {
            require(hops < 8) { "a var() cycle at $value" }
            val v = norm(value)
            VAR.matchEntire(v)?.let {
                val name = it.groupValues[1]
                return resolve(requireNotNull(vars[name]) { "$name is not declared" }, vars, scheme, hops + 1)
            }
            LIGHT_DARK.matchEntire(v)?.let { return resolve(it.groupValues[if (scheme == DARK) 2 else 1], vars, scheme, hops + 1) }
            return v
        }

        private fun contrast(
            a: String,
            b: String,
        ): Double {
            val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }

        private fun luminance(hex: String): Double {
            require(Regex("^#[0-9a-f]{6}$").matches(hex)) { "not an opaque colour: $hex" }
            val (r, g, b) =
                (0..2)
                    .map { hex.substring(1 + 2 * it, 3 + 2 * it).toInt(16) / 255.0 }
                    .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        private fun round2(x: Double): Double = BigDecimal(x).setScale(2, RoundingMode.HALF_UP).toDouble()

        /** Every custom property and `color-scheme` declaration of a stylesheet, with the preludes that enclose it. */
        private fun declarations(css: String): List<Decl> = CssScanner().apply { css.replace(COMMENT, " ").forEach(::take) }.out

        private val COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val SPACES = Regex("\\s+")
    }

    /** Reads CSS text one character at a time, minding quotes, parentheses and the block preludes around each declaration. */
    private class CssScanner {
        val out = mutableListOf<Decl>()
        private val stack = ArrayDeque<String>()
        private val buf = StringBuilder()
        private var quote: Char? = null
        private var depth = 0

        fun take(c: Char) {
            if (quote != null || depth > 0 || c !in "{;}") return text(c)
            if (c == '{') stack.addLast(buf.toString().trim().replace(SPACES, " ")) else declaration()
            buf.clear()
            if (c == '}') stack.removeLast()
        }

        private fun text(c: Char) {
            buf.append(c)
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '(' -> depth++
                c == ')' -> depth--
            }
        }

        private fun declaration() {
            val d = buf.toString().trim()
            val name = d.substringBefore(':').trim()
            if (d.contains(':') && (name.startsWith("--") || name == "color-scheme")) {
                out += Decl(stack.toList(), name, d.substringAfter(':').trim())
            }
        }
    }
}
