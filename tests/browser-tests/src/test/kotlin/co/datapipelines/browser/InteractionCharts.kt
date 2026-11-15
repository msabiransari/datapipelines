package co.datapipelines.browser

import co.datapipelines.browser.ChartInteractionSuite.Chart

/**
 * #990 — the small charts the interaction proofs mount, built here so every value a test reads back is the test's own
 * number: grouped bars over three quarters, lines and areas over months (a null value is a row left out: a gap), and
 * the sparse line of brief 990 A.1 (x 0 to 3; A at 0 and 1 only, B at 2 and 3 only).
 */
object InteractionCharts {
    val QUARTERS = listOf("Q1", "Q2", "Q3")

    /** Grouped bars: series [values]' keys in order, one value per quarter. */
    fun bar(
        values: Map<String, List<Int>>,
        title: String = "Bars",
    ): Chart {
        val k = mutableListOf<Any?>()
        val s = mutableListOf<Any?>()
        val v = mutableListOf<Any?>()
        QUARTERS.forEachIndexed { c, quarter ->
            values.forEach { (id, row) ->
                k += quarter
                s += id
                v += row[c]
            }
        }
        val config =
            """{"mark":{"type":"bar"},"encoding":{"x":{"field":"k","type":"nominal","title":"Quarter"},""" +
                """"y":{"field":"v","type":"quantitative","aggregate":"sum","title":"Value"},""" +
                """"color":{"field":"s","type":"nominal","title":"Series"}},"title":{"text":"$title"}}"""
        return Chart(title, config, mapOf("k" to "STRING", "s" to "STRING", "v" to "DECIMAL"), mapOf("k" to k, "s" to s, "v" to v))
    }

    /** Lines (or areas, [type]) over months: series [values]' keys in the data's order, a null left out. */
    fun line(
        values: Map<String, List<Int?>>,
        type: String = "line",
        title: String = "Lines",
        tooltip: Map<String, List<String>> = emptyMap(),
    ): Chart {
        val x = mutableListOf<Any?>()
        val s = mutableListOf<Any?>()
        val v = mutableListOf<Any?>()
        val fields = tooltip.keys.associateWith { mutableListOf<Any?>() }
        val count = values.values.maxOf { it.size }
        for (i in 0 until count) {
            values.forEach { (id, row) ->
                val value = row.getOrNull(i) ?: return@forEach
                x += month(i)
                s += id
                v += value
                tooltip.forEach { (field, texts) -> fields.getValue(field) += texts[i % texts.size] }
            }
        }
        val tips = tooltip.keys.joinToString(",") { """{"field":"$it","title":"${it.uppercase()}"}""" }
        val config =
            """{"mark":{"type":"$type"},"encoding":{"x":{"field":"x","type":"temporal","title":"Month","format":"month"},""" +
                """"y":{"field":"v","type":"quantitative","aggregate":"sum","title":"Value"},""" +
                """"color":{"field":"s","type":"nominal","sort":null,"title":"Series"}""" +
                (if (tooltip.isEmpty()) "" else ""","tooltip":[$tips]""") + """},"title":{"text":"$title"}}"""
        val types = mapOf("x" to "DATE", "s" to "STRING", "v" to "DECIMAL") + tooltip.keys.associateWith { "STRING" }
        return Chart(title, config, types, mapOf("x" to x, "s" to s, "v" to v) + fields)
    }

    /** The sparse line (A.1, D5): x 0 to 3, A drawn at 0 and 1 only, B at 2 and 3 only. */
    fun sparse(): Chart {
        val config =
            """{"mark":{"type":"line","points":"markers"},"encoding":{"x":{"field":"x","type":"quantitative","title":"X"},""" +
                """"y":{"field":"v","type":"quantitative","aggregate":"sum","title":"Value"},""" +
                """"color":{"field":"s","type":"nominal","sort":null,"title":"Series"}},"title":{"text":"Sparse"}}"""
        return Chart(
            "Sparse",
            config,
            mapOf("x" to "DECIMAL", "s" to "STRING", "v" to "DECIMAL"),
            mapOf("x" to listOf(0, 1, 2, 3), "s" to listOf("A", "A", "B", "B"), "v" to listOf(SPARSE[0], SPARSE[1], SPARSE[2], SPARSE[3])),
        )
    }

    /** The sparse line's values at x 0 to 3. */
    val SPARSE = listOf(4, 7, 3, 6)

    /** The columns of [chart] keeping only the rows whose series is one of [ids] (a refresh that drops series). */
    fun only(
        chart: Chart,
        vararg ids: String,
    ): Map<String, List<Any?>> {
        val series = chart.columns.getValue("s")
        val keep = series.indices.filter { series[it] in ids }
        return chart.columns.mapValues { (_, column) -> keep.map { column[it] } }
    }

    /** The first day of month [i] of 2024, as the DATE column carries it. */
    fun month(i: Int): String = "2024-%02d-01".format(i + 1)
}
