package co.datapipelines.browser

/**
 * #987 — the look contract's §3.7 example table as rows a page can run (LA4), and LA11's two stylesheet rows. A
 * resolver row is `{label, decls, expect, refused}`: the declarations set on an ancestor of the chart, the value the
 * engine then draws with per token (a number, or `[r, g, b]` / `[r, g, b, a]` as the engine takes a colour), and the
 * tokens the resolver must refuse. The defaults are the engine's Light column (contract §3.7: on a page the default is
 * the Light column): text 12, dim 0.25, title weight 600, grid #e6e9ee, axis #a7acb4, series 1 #3b5bdb, step 3 #aab4d9.
 */
internal object TokenRows {
    private const val TEXT = "--chart-text"
    private const val GRID = "--chart-grid"
    private val gridDefault = listOf(230, 233, 238)

    /** The default grid colour as the browser computes the drawn stroke. */
    const val DEFAULT_GRID = "rgb(230, 233, 238)"

    private fun row(
        label: String,
        decls: Map<String, String>,
        expect: Map<String, Any>,
        refused: List<String> = emptyList(),
    ): Map<String, Any> = mapOf("label" to label, "decls" to decls, "expect" to expect, "refused" to refused)

    private fun text(
        value: String,
        expected: Int,
        refused: Boolean,
        extra: Map<String, String> = emptyMap(),
    ) = row(
        "$TEXT: $value${if (extra.isEmpty()) "" else " $extra"}",
        extra + (TEXT to value),
        mapOf(TEXT to expected),
        if (refused) listOf(TEXT) else emptyList(),
    )

    private fun grid(
        label: String,
        decls: Map<String, String>,
        expected: List<Number>,
        refused: Boolean = false,
    ) = row(label, decls, mapOf(GRID to expected), if (refused) listOf(GRID) else emptyList())

    val RESOLVER: List<Map<String, Any>> =
        listOf("12", "12px", "0.75rem", "calc(10px + 2px)").map { text(it, 12, refused = false) } +
            text("1em", 14, refused = false, extra = mapOf("font-size" to "14px")) +
            listOf("50%", "large", "twelve", "-4", "4", "120").map { text(it, 12, refused = true) } +
            listOf(
                row("--chart-gap: 0", mapOf("--chart-gap" to "0"), mapOf("--chart-gap" to 0)),
                row("--chart-dim: 1.4", mapOf("--chart-dim" to "1.4"), mapOf("--chart-dim" to 0.25), listOf("--chart-dim")),
                row("--chart-dim: 25%", mapOf("--chart-dim" to "25%"), mapOf("--chart-dim" to 0.25), listOf("--chart-dim")),
            ) +
            listOf("0.12s", "120ms", "120").map { row("--chart-ease: $it", mapOf("--chart-ease" to it), mapOf("--chart-ease" to 120)) } +
            listOf(
                row("--chart-title: 18", mapOf("--chart-title" to "18"), mapOf("--chart-title" to 18, "--chart-title-weight" to 600)),
                row("--chart-title-weight: 400", mapOf("--chart-title-weight" to "400"), mapOf("--chart-title-weight" to 400)),
                row(
                    "--chart-title-weight: bold",
                    mapOf("--chart-title-weight" to "bold"),
                    mapOf("--chart-title-weight" to 600),
                    listOf("--chart-title-weight"),
                ),
                grid("color-mix", mapOf(GRID to "color-mix(in srgb, #000 20%, white)"), listOf(204, 204, 204)),
                grid(
                    "var(--border) where --border is a colour",
                    mapOf("--border" to "#123456", GRID to "var(--border)"),
                    listOf(18, 52, 86),
                ),
                grid("$GRID: 12px", mapOf(GRID to "12px"), gridDefault, refused = true),
                grid("$GRID: url(x.png)", mapOf(GRID to "url(x.png)"), gridDefault, refused = true),
                grid("$GRID declared empty", mapOf(GRID to " "), gridDefault),
                grid("$GRID: initial", mapOf(GRID to "initial"), gridDefault),
                grid("$GRID: var(--nothing)", mapOf(GRID to "var(--nothing)"), gridDefault),
                row(
                    "$GRID and --chart-axis in a var() cycle",
                    mapOf(GRID to "var(--chart-axis)", "--chart-axis" to "var($GRID)"),
                    mapOf(GRID to gridDefault, "--chart-axis" to listOf(167, 172, 180)),
                ),
                grid("$GRID: var(--nothing, #ccd)", mapOf(GRID to "var(--nothing, #ccd)"), listOf(204, 204, 221)),
                row(
                    "--chart-canvas translucent",
                    mapOf("--chart-canvas" to "rgb(255 255 255 / 0.5)"),
                    mapOf("--chart-canvas" to listOf(255, 255, 255, 0.5)),
                ),
                row("--chart-plot: transparent", mapOf("--chart-plot" to "transparent"), mapOf("--chart-plot" to listOf(0, 0, 0, 0))),
                row(
                    "--chart-series-1 with alpha",
                    mapOf("--chart-series-1" to "rgb(59 91 219 / 0.8)"),
                    mapOf("--chart-series-1" to listOf(59, 91, 219)),
                    listOf("--chart-series-1"),
                ),
                row(
                    "--chart-series-1: transparent",
                    mapOf("--chart-series-1" to "transparent"),
                    mapOf("--chart-series-1" to listOf(59, 91, 219)),
                    listOf("--chart-series-1"),
                ),
                row(
                    "--chart-seq-3 with alpha",
                    mapOf("--chart-seq-3" to "rgb(0 0 0 / 0.5)"),
                    mapOf("--chart-seq-3" to listOf(170, 180, 217)),
                    listOf("--chart-seq-3"),
                ),
                row(
                    "--chart-on-fill-light transparent",
                    mapOf("--chart-on-fill-light" to "rgb(255 255 255 / 0)"),
                    mapOf("--chart-on-fill-light" to listOf(255, 255, 255)),
                    listOf("--chart-on-fill-light"),
                ),
                row(
                    "--chart-series-2: color(srgb 0.5 0.5 0.5)",
                    mapOf("--chart-series-2" to "color(srgb 0.5 0.5 0.5)"),
                    mapOf("--chart-series-2" to listOf(128, 128, 128)),
                ),
            )

    /** LA11's two rows that start from a stylesheet. */
    val LA11: List<Map<String, Any>> =
        listOf(
            row(
                "--chart-on-fill-light: rgb(255 255 255 / 0)",
                mapOf("--chart-on-fill-light" to "rgb(255 255 255 / 0)"),
                mapOf("--chart-on-fill-light" to listOf(255, 255, 255)),
                listOf("--chart-on-fill-light"),
            ),
            row(
                "--chart-series-1: rgb(59 91 219 / 0.8)",
                mapOf("--chart-series-1" to "rgb(59 91 219 / 0.8)"),
                mapOf("--chart-series-1" to listOf(59, 91, 219)),
                listOf("--chart-series-1"),
            ),
        )

    /** What the drawn picture shows for a declaration: the grid line's computed stroke and a tick's font size. */
    data class Drawn(
        val grid: String,
        val tickSize: String,
    )

    /** The drawn rows: nothing set, the two refusals the gate names, and a valid value of each (the reading's control). */
    val DRAWN: List<Pair<Map<String, String>, Drawn>> =
        listOf(
            emptyMap<String, String>() to Drawn(DEFAULT_GRID, "12"),
            mapOf(GRID to "12px") to Drawn(DEFAULT_GRID, "12"),
            mapOf(TEXT to "-4") to Drawn(DEFAULT_GRID, "12"),
            mapOf(GRID to "#ff0000") to Drawn("rgb(255, 0, 0)", "12"),
            mapOf(TEXT to "14") to Drawn(DEFAULT_GRID, "14"),
        )

    /** `--chart-pad` as CSS reads it (contract §3.7's second table): the card's computed padding, top then left. */
    val PADDING: List<Pair<Map<String, String>, String>> =
        listOf(
            emptyMap<String, String>() to "16px 16px",
            mapOf("--chart-pad" to "var(--nothing)") to "16px 16px",
            mapOf("--chart-pad" to "initial") to "16px 16px",
            mapOf("--chart-pad" to "var(--pad-cycle)", "--pad-cycle" to "var(--chart-pad)") to "16px 16px",
            mapOf("--chart-pad" to "1rem") to "16px 16px",
            mapOf("--chart-pad" to "8px 16px") to "8px 16px",
            mapOf("--chart-pad" to "0") to "0px 0px",
            mapOf("--chart-pad" to "wide") to "0px 0px",
            mapOf("--chart-pad" to "16") to "0px 0px",
            mapOf("--chart-pad" to " ") to "0px 0px",
        )

    /** LA6's hostile stylesheet, the declarations of one rule. */
    const val HOSTILE_SHEET =
        "--chart-grid: url(https://example.invalid/x); --chart-text: expression(1); --font-sans: \"x'><script>alert(1)</script>\";"
}
