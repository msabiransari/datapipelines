package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode

/**
 * The D-A authority (design record §4.4 layer 3): how far the SERVER's chart library honours each field id of the bar
 * matrix. A MEANING field it `cannot` draw refuses the save (`chart.unsupported.<field>`); any other field it does not
 * draw `direct`ly is accepted with a warning (`chart.degraded.<field>`) — the reference `save-rules.mjs`'s table walk,
 * row for row. The production table is Plotly's column of the 854 matrix (`chart-support/plotly.json`, its source and
 * the source's sha256 inside): all 35 rows `direct` today, so the warnings exist for the day a row moves.
 */
class ChartLibrarySupport(
    val library: String,
    private val fields: Map<String, Support>,
) {
    /** One row: whether the field carries meaning or presentation, and how the library draws it. */
    data class Support(
        val kind: String,
        val level: String,
    )

    /** The table's verdict over one document's set fields. */
    data class Verdict(
        val refusals: List<ChartRefusal>,
        val warnings: List<ChartFieldWarning>,
    )

    /** One accepted-with-caveat field: the code and the JSON Pointer it is authored at. */
    data class ChartFieldWarning(
        val code: String,
        val pointer: String,
    )

    fun judge(
        setFields: List<String>,
        channels: BarChannels,
    ): Verdict {
        val set = setFields.toSet()
        val refusals = mutableListOf<ChartRefusal>()
        val warnings = mutableListOf<ChartFieldWarning>()
        fields.forEach { (field, support) ->
            if (field !in set || support.level == DIRECT) return@forEach
            val pointer = BarFieldSet.pointer(field, channels)
            if (support.level == CANNOT && support.kind == MEANING) {
                refusals += ChartRefusal("chart.unsupported.$field", pointer, "The server chart library cannot draw this meaning field.")
            } else {
                warnings += ChartFieldWarning("chart.degraded.$field", pointer)
            }
        }
        return Verdict(refusals, warnings)
    }

    companion object {
        const val DIRECT = "direct"
        const val DEGRADED = "degraded"
        const val CANNOT = "cannot"
        const val MEANING = "meaning"
        const val PRESENTATION = "presentation"
        private const val RESOURCE_DIR = "co/datapipelines/visualization/chart-support/"

        /** The server library's table from this module's classpath (`chart-support/<library>.json`). */
        fun load(library: String = "plotly"): ChartLibrarySupport {
            val stream =
                checkNotNull(ChartLibrarySupport::class.java.classLoader.getResourceAsStream("$RESOURCE_DIR$library.json")) {
                    "chart support table for $library is missing from the classpath"
                }
            val tree: JsonNode = stream.use { ArtifactJson.mapper.readTree(it) }
            check(tree.path("library").asText() == library) { "chart support table names another library" }
            val rows =
                tree.path("fields").properties().associate { (field, row) ->
                    val kind = row.path("kind").asText()
                    val level = row.path("class").asText()
                    check(
                        kind in setOf(MEANING, PRESENTATION) && level in setOf(DIRECT, DEGRADED, CANNOT),
                    ) { "chart support row $field is malformed" }
                    field to Support(kind, level)
                }
            return ChartLibrarySupport(library, rows)
        }
    }
}
