package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode

/**
 * The bar family's library-neutral model (design record 2026-11-06 §4.1 and §5.1): the server's ONE re-expression of the
 * runtime core's `model` (`datapipelines-dashboard-chart.js`, ported from the 854 reference `bar-core.mjs` with the
 * record's two corrections) — the same categories, series, cells and exact aggregates the converter draws, so the
 * mechanical check judges `mark_count`, `no_data` and `text_visible` over what the browser will draw. The parity test
 * (`ChartModelParityTest`, this module, over modules/web's `chart-parity` corpus) runs both over one corpus every build.
 *
 * Rows are read the way the runtime reads its columnar delivery: a value's identity is its JavaScript type and text (a
 * category `"1"` and a category `1` are two bands; INTEGER/DECIMAL values are float64, exactly as the browser parses
 * them), and a BIGINTEGER/BIGDECIMAL value is its exact decimal string (its wire form).
 */
class ChartModel private constructor(
    /** True when `y` carries the value. */
    val vertical: Boolean,
    /** The value channel's `stack` when `color` is set (`zero`, `normalize`, or null = grouped); null without `color`. */
    val stack: String?,
    val categories: List<Item>,
    val series: List<Item>,
    /** `cells[s][c]`: series `s` at category `c`, in the two lists' drawing order. */
    val cells: List<List<Cell>>,
    /** Rows not drawn because their category was null (`rule.null-category`). */
    val nullCategories: Int,
    /** Rows not drawn because their series was null (`rule.null-category`). */
    val nullSeries: Int,
    private val colored: Boolean,
) {
    /** One band or series: its identity, its raw value, its first-appearance index and its full text. */
    data class Item(
        val key: String,
        val raw: Any?,
        val first: Int,
        val full: String,
    ) {
        /** The axis label: [full] cut to [LABEL_CODE_POINTS] code points, the last an ellipsis. */
        val label: String get() = cut(full)
    }

    /**
     * One cell: [num] the drawn value (null = a gap, never a zero; a normalized stack's share), [exact] the exact decimal
     * string of an exact aggregate (null otherwise, and on a normalized stack), [aggregate] the aggregate's exact text
     * before normalization (the `text_visible` proof's source; null for a gap).
     */
    data class Cell(
        val num: Double?,
        val exact: String?,
        val aggregate: String?,
    )

    /** `mark_count`: the cells with a non-null aggregate — distinct band × series pairs (record §5.1). */
    val markCount: Int get() = cells.sumOf { row -> row.count { it.num != null } }

    /** `no_data`: nothing to draw — no rows, every row's category null, or every aggregate null. */
    val noData: Boolean get() = markCount == 0

    /**
     * True when [text] is mechanically PROVEN (record §5.1): a substring of a category's text, of a series value's text
     * (only when `color` sets a series), or of an aggregate's exact decimal string. Configuration strings are the
     * caller's to add; anything else is UNJUDGED — only the browser can judge a formatted label.
     */
    fun proves(text: String): Boolean =
        categories.any { text in it.full } ||
            (colored && series.any { text in it.full }) ||
            cells.any { row -> row.any { it.aggregate?.contains(text) == true } }

    /**
     * A measure value outside its wire form — an exact column's value that is not a decimal string, or a number column's
     * value that is not a number: the browser's core cannot draw it either. Names the FIELD, never the value.
     */
    class NotAMeasure(
        val field: String,
    ) : IllegalArgumentException("A value of '$field' is not a number in its column's wire form.")

    companion object {
        const val LABEL_CODE_POINTS = 32

        /**
         * The model of [config] (a bar config the schema accepted) over [rows] (the case's post-transform rows), with
         * [types] the contract's `{field: LogicalType}` — the same types the served occurrence's `columns` carries.
         */
        fun of(
            config: JsonNode,
            types: Map<String, LogicalType>,
            rows: List<Map<String, Any?>>,
        ): ChartModel = Builder(config, types, rows).build()

        /** At most [n] code points, the last an ellipsis (the reference `cut`). */
        fun cut(
            s: String,
            n: Int = LABEL_CODE_POINTS,
        ): String {
            val count = s.codePointCount(0, s.length)
            if (count <= n) return s
            return s.substring(0, s.offsetByCodePoints(0, n - 1)) + "…"
        }
    }

    private class Builder(
        config: JsonNode,
        private val types: Map<String, LogicalType>,
        private val rows: List<Map<String, Any?>>,
    ) {
        private val ch = BarChannels.of(config)
        private val bandField = ch.band.field.orEmpty()
        private val valueField = ch.value.field
        private val colorField = ch.color?.field
        private val temporal =
            ch.band.encoding
                .path("type")
                .asText() == "temporal"
        private val format =
            ch.band.encoding
                .get("format")
                ?.takeIf { it.isTextual }
                ?.asText()

        /** What one pass over the rows collects: bands and series in first appearance, the grouped values, the skips. */
        private class Collected {
            val cats = LinkedHashMap<String, Item>()
            val sers = LinkedHashMap<String, Item>()

            /**
             * A band's series' values — NESTED, so a pair's identity is the two keys' PAIR (860b R2): a flat
             * NUL-joined key collides when a category carries the NUL character itself (the join of `"a"` + NUL +
             * `"b"` reads like `"a"` + NUL + NUL + `"b"`'s pieces), and the collision draws one cell where the
             * browser draws two.
             */
            val groups = LinkedHashMap<String, LinkedHashMap<String, MutableList<Any?>>>()
            var nullCategories = 0
            var nullSeries = 0
        }

        fun build(): ChartModel {
            val seen = collect()
            val aggregate =
                ch.value.encoding
                    .path("aggregate")
                    .asText()
            val raw =
                seen.groups.mapValues { (_, bySeries) ->
                    bySeries.mapValues { (_, values) -> ChartAggregates.of(values, aggregate, valueField.orEmpty()) }
                }
            val cell = { c: Item, s: Item -> raw[c.key]?.get(s.key) ?: ChartAggregates.GAP }
            val categoryTotal = { c: Item ->
                seen.sers.values
                    .mapNotNull { cell(c, it).num }
                    .takeIf { it.isNotEmpty() }
                    ?.sum()
            }
            val categories =
                ChartOrder.of(seen.cats.values.toList(), ch.band.encoding.get("sort"), types[bandField], temporal, categoryTotal)
            val series =
                ChartOrder.of(seen.sers.values.toList(), ch.color?.encoding?.get("sort"), colorField?.let { types[it] }, false) { null }
            val stack =
                colorField?.let {
                    ch.value.encoding
                        .get("stack")
                        ?.takeUnless { it.isNull }
                        ?.asText()
                }
            val normalizers =
                if (stack == NORMALIZE) {
                    categories.map { c -> ChartNormalization(series.mapNotNull { cell(c, it).num?.takeIf(Double::isFinite) }) }
                } else {
                    emptyList()
                }
            val cells =
                series.map { s ->
                    categories.mapIndexed { index, c ->
                        drawn(cell(c, s), stack) { normalizers[index].share(it) }
                    }
                }
            return ChartModel(ch.vertical, stack, categories, series, cells, seen.nullCategories, seen.nullSeries, colorField != null)
        }

        private fun collect(): Collected {
            val seen = Collected()
            rows.forEach { row ->
                val b = wire(bandField, row[bandField])
                val s = colorField?.let { wire(it, row[it]) }
                when {
                    b == null -> {
                        seen.nullCategories++
                    }

                    colorField != null && s == null -> {
                        seen.nullSeries++
                    }

                    else -> {
                        val bk = ChartValues.key(b)
                        val sk = if (colorField != null) ChartValues.key(s) else ""
                        seen.cats.getOrPut(bk) {
                            Item(bk, b, seen.cats.size, if (temporal) ChartTemporal.label(b, format) else ChartValues.text(b))
                        }
                        seen.sers.getOrPut(sk) {
                            Item(sk, s, seen.sers.size, if (colorField != null) ChartValues.text(s) else valueField ?: "count")
                        }
                        seen.groups
                            .getOrPut(bk) { LinkedHashMap() }
                            .getOrPut(sk) { mutableListOf() } += if (valueField != null) wire(valueField, row[valueField]) else COUNT_UNIT
                    }
                }
            }
            return seen
        }

        /**
         * A cell as drawn: a gap stays a gap; a value float64 cannot carry (860b R1 — an aggregate past ±1.7e308, exact
         * or float) draws nothing, the same gap the browser's core draws; a normalized stack draws its share of the
         * category's absolute total, computed through [normalizedShare] without overflowing that total.
         */
        private fun drawn(
            r: ChartAggregates.Result,
            stack: String?,
            normalizedShare: (Double) -> Double,
        ): Cell =
            when {
                r.num == null || !r.num.isFinite() -> {
                    Cell(null, null, null)
                }

                stack == NORMALIZE -> {
                    Cell(normalizedShare(r.num), null, r.text)
                }

                else -> {
                    Cell(r.num, r.exact, r.text)
                }
            }

        /** A value as the runtime receives it: JSON numbers are float64; an exact column's numbers are their decimal string. */
        private fun wire(
            field: String,
            value: Any?,
        ): Any? =
            when {
                value == null -> null
                value is Number && types[field] in ChartTypes.EXACT -> java.math.BigDecimal(value.toString()).toPlainString()
                value is Number -> value.toDouble()
                else -> value
            }

        companion object {
            const val NORMALIZE = "normalize"

            /** What a `count` bar aggregates per row (no field). */
            const val COUNT_UNIT = 1.0
        }
    }
}
