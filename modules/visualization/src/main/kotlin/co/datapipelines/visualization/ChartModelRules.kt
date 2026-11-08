package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode

/** A wire value's identity and text, as the browser's core reads them (`typeof v + ':' + String(v)`). */
internal object ChartValues {
    fun key(value: Any?): String =
        when (value) {
            null -> "object:null"
            is Double -> "number:${text(value)}"
            is String -> "string:$value"
            is Boolean -> "boolean:$value"
            else -> "object:$value"
        }

    /**
     * ECMAScript `String.prototype.trim` (ECMA-262 §22.1.3.32): strips WhiteSpace and LineTerminator — TAB, VT, FF, SPACE,
     * NBSP, ZWNBSP (U+FEFF), every `Zs` character, LF, CR, U+2028, U+2029. Kotlin's `trim()` differs on two counts (it
     * strips U+001C to U+001F and keeps U+FEFF), and an exact value's grammar is judged after this trim on both sides.
     */
    fun trim(s: String): String = s.trim(::isEcmaSpace)

    private fun isEcmaSpace(c: Char): Boolean = c in ECMA_SPACES || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    private const val ECMA_SPACES = "\t\u000B\u000C  ﻿\n\r  "

    /** `String(v)` of a wire value. */
    fun text(value: Any?): String =
        when (value) {
            null -> "null"
            is Double -> number(value)
            else -> value.toString()
        }

    /**
     * ECMAScript `Number::toString` of [d] (ECMA-262 §6.1.6.1.20): the shortest round-tripping digits — the JDK's
     * `Double.toString` digits (shortest since JDK 19) — laid out plainly from 1e-7 to below 1e21, exponentially
     * outside (`1e+21`, `1e-7`). A category of `1.0` is the text `1`, as the browser draws it.
     */
    @Suppress("MagicNumber") // the ECMA-262 layout bounds (21 and -6)
    fun number(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        val sign = if (d < 0) "-" else ""
        val exact = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        val digits = exact.unscaledValue().toString()
        val k = digits.length
        val n = k - exact.scale()
        val body =
            when {
                n in k..21 -> {
                    digits + "0".repeat(n - k)
                }

                n in 1..21 -> {
                    digits.substring(0, n) + "." + digits.substring(n)
                }

                n in -5..0 -> {
                    "0." + "0".repeat(-n) + digits
                }

                else -> {
                    val e = n - 1
                    val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                    mantissa + "e" + (if (e >= 0) "+" else "-") + Math.abs(e)
                }
            }
        return sign + body
    }
}

/** The aggregates: exact decimal arithmetic when any value is an exact (string) wire value, float64 otherwise. */
internal object ChartAggregates {
    /** One group's aggregate: the drawn [num], the [exact] decimal string when exact, and its [text] (the proof's source). */
    data class Result(
        val num: Double?,
        val exact: String?,
    ) {
        val text: String? get() = exact ?: num?.let(ChartValues::number)
    }

    val GAP = Result(null, null)

    /**
     * The reference's decimal grammar: an optional minus, digits, an optional fraction — the runtime core's `DECIMAL`, for
     * the measures here and for an exact category's order in [ChartOrder] (`+3`, `1e1`, `.5` are not decimals on either side).
     */
    internal val DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")

    /** The mean's extra fraction digits beyond the inputs' scale (record §4.1). */
    private const val MEAN_EXTRA_SCALE = 6

    fun of(
        values: List<Any?>,
        aggregate: String,
        field: String,
    ): Result {
        if (aggregate == "count") return Result(values.size.toDouble(), null)
        val xs = values.filterNotNull()
        if (xs.isEmpty()) return GAP
        if (xs.any { it is String }) {
            val exact = exact(xs.map { ChartValues.trim(ChartValues.text(it)) }, aggregate, field)
            return Result(exact.toDouble(), exact)
        }
        val ds = xs.map { it as? Double ?: throw ChartModel.NotAMeasure(field) }
        val num =
            when (aggregate) {
                "sum" -> ds.fold(0.0) { a, b -> a + b }
                "min" -> ds.reduce { a, b -> if (b < a) b else a }
                "max" -> ds.reduce { a, b -> if (b > a) b else a }
                else -> ds.fold(0.0) { a, b -> a + b } / ds.size
            }
        return Result(num, null)
    }

    /**
     * `sum | min | max` exact at the inputs' (largest) scale; `mean` = exact sum ÷ count rounded HALF-EVEN at that
     * scale + 6, trailing zeros dropped (`1, 1, 0` → `0.666667`) — the record's correction of the reference's truncation.
     */
    private fun exact(
        texts: List<String>,
        aggregate: String,
        field: String,
    ): String {
        val xs = texts.map { if (DECIMAL.matches(it)) BigDecimal(it) else throw ChartModel.NotAMeasure(field) }
        val scale = xs.maxOf { it.scale() }
        val sum = { xs.fold(BigDecimal.ZERO) { a, b -> a + b }.setScale(scale) }
        return when (aggregate) {
            "sum" -> sum().toPlainString()
            "min" -> xs.reduce { a, b -> if (b < a) b else a }.setScale(scale).toPlainString()
            "max" -> xs.reduce { a, b -> if (b > a) b else a }.setScale(scale).toPlainString()
            else -> sum().divide(BigDecimal(xs.size), scale + MEAN_EXTRA_SCALE, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
        }
    }
}

/** The UTC temporal rules (record §4.1, the reference `format.mjs`): labels and order from the wire string's own digits. */
internal object ChartTemporal {
    private const val YMD = "(?<y>\\d{4})-(?<m>\\d{2})-(?<d>\\d{2})"
    private val DATE = Regex("^$YMD$")
    private val STAMP = Regex("^${YMD}T(?<h>\\d{2}):(?<mi>\\d{2})(?::(?<s>\\d{2})(?:\\.(?<f>\\d{1,9}))?)?Z$")
    private val STAMP_LABEL = Regex("^${YMD}T(?<h>\\d{2}):(?<mi>\\d{2})(?::\\d{2}(?:\\.\\d+)?)?Z$")
    private const val NANO_DIGITS = 9

    /** A named group's text; an optional group that did not take part reads as empty. */
    private fun MatchResult.part(name: String): String = groups[name]?.value.orEmpty()

    /** The fixed-width sort key `YYYY-MM-DDTHH:MM:SS.fffffffff`, or null when the value is neither admitted shape. */
    fun key(raw: Any?): String? {
        val s = ChartValues.text(raw)
        DATE.matchEntire(s)?.let { return "${it.part("y")}-${it.part("m")}-${it.part("d")}T00:00:00.000000000" }
        val t = STAMP.matchEntire(s) ?: return null
        val time = "${t.part("h")}:${t.part("mi")}:${t.part("s").ifEmpty { "00" }}.${t.part("f").padEnd(NANO_DIGITS, '0')}"
        return "${t.part("y")}-${t.part("m")}-${t.part("d")}T$time"
    }

    /** The UTC label at [granularity] (`date | month | year | datetime`; default `date`, or `datetime` for a timestamp). */
    fun label(
        raw: Any?,
        granularity: String?,
    ): String {
        val s = ChartValues.text(raw)
        val d = DATE.matchEntire(s)
        val t = if (d == null) STAMP_LABEL.matchEntire(s) else null
        val ymd = d ?: t ?: return s
        val (y, m, day) = listOf("y", "m", "d").map { ymd.part(it) }
        val time = t?.let { "${it.part("h")}:${it.part("mi")}" }
        return when (granularity ?: if (time != null) "datetime" else "date") {
            "year" -> y
            "month" -> "$y-$m"
            "date" -> "$y-$m-$day"
            else -> "$y-$m-$day ${time ?: "00:00"} UTC"
        }
    }
}

/**
 * The band and series order (record §4.1): null = first appearance; an explicit list = its order, the rest after in
 * first appearance; `ascending | descending` by the COLUMN'S TYPE — INTEGER/DECIMAL by value, BIGINTEGER/BIGDECIMAL by
 * exact decimal comparison, `temporal` by the UTC digit key, everything else by code point; `y | -y | x | -x` (a band
 * only) by the category's total, nulls last, ties in first appearance. Every sort is stable; `descending` reverses
 * the ascending order (the reference).
 */
internal object ChartOrder {
    fun of(
        items: List<ChartModel.Item>,
        sort: JsonNode?,
        type: LogicalType?,
        temporal: Boolean,
        totalOf: (ChartModel.Item) -> Double?,
    ): List<ChartModel.Item> {
        val byFirst = items.sortedBy { it.first }
        return when {
            sort == null || sort.isNull -> {
                byFirst
            }

            sort.isArray -> {
                val pos = HashMap<String, Int>()
                sort.forEachIndexed { i, v -> pos[v.asText()] = i }
                val listed = byFirst.filter { ChartValues.text(it.raw) in pos }.sortedBy { pos.getValue(ChartValues.text(it.raw)) }
                listed + byFirst.filter { ChartValues.text(it.raw) !in pos }
            }

            sort.asText() == "ascending" -> {
                byFirst.sortedWith(byValue(type, temporal))
            }

            sort.asText() == "descending" -> {
                byFirst.sortedWith(byValue(type, temporal)).reversed()
            }

            else -> {
                byTotal(byFirst, sort.asText().startsWith("-"), totalOf)
            }
        }
    }

    private fun byTotal(
        byFirst: List<ChartModel.Item>,
        descending: Boolean,
        totalOf: (ChartModel.Item) -> Double?,
    ): List<ChartModel.Item> =
        byFirst.sortedWith { a, b ->
            val x = totalOf(a)
            val y = totalOf(b)
            when {
                x == null && y == null -> a.first - b.first
                x == null -> 1
                y == null -> -1
                else -> (if (descending) y.compareTo(x) else x.compareTo(y)).takeIf { it != 0 } ?: (a.first - b.first)
            }
        }

    private fun byValue(
        type: LogicalType?,
        temporal: Boolean,
    ): Comparator<ChartModel.Item> =
        when {
            temporal -> {
                Comparator { a, b ->
                    codePoints(
                        ChartTemporal.key(a.raw) ?: ChartValues.text(a.raw),
                        ChartTemporal.key(b.raw) ?: ChartValues.text(b.raw),
                    )
                }
            }

            type in ChartTypes.EXACT -> {
                Comparator { a, b ->
                    val x = decimal(a.raw)
                    val y = decimal(b.raw)
                    if (x != null && y != null) x.compareTo(y) else codePoints(ChartValues.text(a.raw), ChartValues.text(b.raw))
                }
            }

            else -> {
                // INTEGER/DECIMAL are float64 on the wire; text types compare by code point; an unknown type (no contract)
                // keeps the reference rule: two numbers by value, anything else by code point.
                Comparator { a, b ->
                    val x = a.raw
                    val y = b.raw
                    if (x is Double && y is Double) x.compareTo(y) else codePoints(ChartValues.text(x), ChartValues.text(y))
                }
            }
        }

    /** An exact category's decimal value, or null outside the decimal grammar (it then orders by code point). */
    private fun decimal(raw: Any?): BigDecimal? {
        val text = (raw as? String)?.let(ChartValues::trim) ?: return null
        return if (ChartAggregates.DECIMAL.matches(text)) BigDecimal(text) else null
    }

    /** Code point order (a String's compareTo orders UTF-16 units; the two differ above U+FFFF). */
    fun codePoints(
        a: String,
        b: String,
    ): Int {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        for (i in 0 until minOf(x.size, y.size)) {
            if (x[i] != y[i]) return x[i] - y[i]
        }
        return x.size - y.size
    }
}
