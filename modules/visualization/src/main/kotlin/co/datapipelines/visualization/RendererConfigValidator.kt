package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Judges a visualization's renderer-native `config` against its renderer's schema (the spec's §3.1, §11.3
 * step 1). A port so the tests lane (L4) can put the reduced Plotly plot-schema behind the SAME call; this
 * module's [RendererConfigValidators.default] owns the `table` and `kpi` schemas (ours — Dashboards §2.1.2)
 * and, for `plotly`, the shape the spec names: `data` an array of objects each with a `type` from the vendored
 * bundles' list (§10.4), `layout` and Plotly's own `config` objects. The configuration is never interpreted
 * beyond this (D4): what passes is stored verbatim.
 */
fun interface RendererConfigValidator {
    /** Every problem of [config] for [kind]; a path is relative to `config` (`data[0].type`). */
    fun validate(
        kind: RendererKind,
        config: ObjectNode,
    ): List<ConfigProblem>
}

/** One schema problem inside `config`: where, why (`details.reason`), and a message that echoes no value. */
data class ConfigProblem(
    val path: String,
    val reason: String,
    val message: String,
)

/** The module's renderer schemas — the production [RendererConfigValidator] until L4 deepens Plotly's. */
object RendererConfigValidators {
    /** The 2D bundle's traces (the spec's §10.4, D63 — confirmed 2026-09-28). */
    val PLOTLY_2D_TRACES: List<String> = listOf("scatter", "bar", "pie", "histogram", "box", "heatmap")

    /** The 3D bundle's additions — a dashboard with one loads the 3D bundle (§10.4). */
    val PLOTLY_3D_TRACES: List<String> = listOf("scatter3d", "surface", "mesh3d")

    /** Every trace type a Plotly `config` may name. */
    val PLOTLY_TRACES: List<String> = PLOTLY_2D_TRACES + PLOTLY_3D_TRACES

    val PLOTLY_KEYS: Set<String> = setOf("data", "layout", "config")
    val TABLE_KEYS: Set<String> = setOf("columns", "page_size")
    val TABLE_COLUMN_KEYS: Set<String> = setOf("label", "values", "format", "align")
    val TABLE_FORMATS: List<String> = listOf("text", "number", "integer", "percent", "date", "datetime")
    val TABLE_ALIGNMENTS: List<String> = listOf("left", "center", "right")
    val KPI_KEYS: Set<String> = setOf("label", "value", "format", "unit", "comparison")
    val KPI_COMPARISON_KEYS: Set<String> = setOf("label", "value")
    val KPI_FORMATS: List<String> = listOf("number", "integer", "percent", "currency")

    /** A table shows at most this many columns and pages at most this many rows (the schema's own bounds). */
    const val MAX_TABLE_COLUMNS = 64
    const val MAX_PAGE_SIZE = 1_000
    const val MAX_TRACES = 64
    const val MAX_LABEL_CHARS = 120
    const val MAX_UNIT_CHARS = 16

    /** The production validator: the three renderable kinds; a reserved kind has no schema (the validator refuses it first). */
    fun default(): RendererConfigValidator =
        RendererConfigValidator { kind, config ->
            val problems = mutableListOf<ConfigProblem>()
            when (kind) {
                RendererKind.PLOTLY -> plotly(config, problems)
                RendererKind.TABLE -> table(config, problems)
                RendererKind.KPI -> kpi(config, problems)
                RendererKind.HTML, RendererKind.SVG -> Unit
            }
            problems
        }

    /**
     * The L4a production validator (the spec's §11.3 step 1): Plotly goes through [PlotlySchemaValidator] —
     * the reduced 4.1.1 plot-schema, unknown attributes and wrong types refused with the path, the house
     * data-array checks kept in force — while `table` and `kpi` stay the house schemas. Save, submit and
     * release all read through THIS composition, so they refuse the same configurations.
     */
    fun deep(schema: PlotlySchemaValidator = PlotlySchemaValidator()): RendererConfigValidator =
        RendererConfigValidator { kind, config ->
            when (kind) {
                RendererKind.PLOTLY -> schema.validate(kind, config)
                else -> default().validate(kind, config)
            }
        }

    private fun plotly(
        config: ObjectNode,
        problems: MutableList<ConfigProblem>,
    ) {
        unknownKeys(config, PLOTLY_KEYS, "", problems)
        val data = config.get("data")
        when {
            data == null || !data.isArray || data.isEmpty -> {
                problems += ConfigProblem("data", "traces_missing", "A Plotly config needs a non-empty data array of traces.")
            }

            data.size() > MAX_TRACES -> {
                problems += ConfigProblem("data", "too_many_traces", "At most $MAX_TRACES traces.")
            }

            else -> {
                data.forEachIndexed { index, trace -> trace(trace, "data[$index]", problems) }
            }
        }
        listOf("layout", "config").forEach { key ->
            config.get(key)?.takeUnless { it.isObject }?.let { problems += ConfigProblem(key, "wrong_type", "'$key' must be an object.") }
        }
    }

    private fun trace(
        trace: JsonNode,
        path: String,
        problems: MutableList<ConfigProblem>,
    ) {
        val type = trace.get("type")
        when {
            !trace.isObject -> {
                problems += ConfigProblem(path, "wrong_type", "A trace is an object.")
            }

            type == null || !type.isTextual -> {
                problems += ConfigProblem("$path.type", "trace_type_missing", "A trace names its type.")
            }

            type.asText() !in PLOTLY_TRACES -> {
                problems += ConfigProblem("$path.type", "trace_type_unsupported", "Trace types are $PLOTLY_TRACES (the vendored bundles).")
            }
        }
    }

    private fun table(
        config: ObjectNode,
        problems: MutableList<ConfigProblem>,
    ) {
        unknownKeys(config, TABLE_KEYS, "", problems)
        val columns = config.get("columns")
        when {
            columns == null || !columns.isArray || columns.isEmpty -> {
                problems += ConfigProblem("columns", "columns_missing", "A table config needs a non-empty columns array.")
            }

            columns.size() > MAX_TABLE_COLUMNS -> {
                problems += ConfigProblem("columns", "too_many_columns", "At most $MAX_TABLE_COLUMNS columns.")
            }

            else -> {
                columns.forEachIndexed { index, column -> tableColumn(column, "columns[$index]", problems) }
            }
        }
        config.get("page_size")?.let { size ->
            if (!size.canConvertToInt() || !size.isIntegralNumber || size.asInt() !in 1..MAX_PAGE_SIZE) {
                problems += ConfigProblem("page_size", "out_of_range", "page_size is an integer 1..$MAX_PAGE_SIZE.")
            }
        }
    }

    private fun tableColumn(
        column: JsonNode,
        path: String,
        problems: MutableList<ConfigProblem>,
    ) {
        if (!column.isObject) {
            problems += ConfigProblem(path, "wrong_type", "A table column is an object.")
            return
        }
        unknownKeys(column, TABLE_COLUMN_KEYS, path, problems)
        label(column.get("label"), "$path.label", required = true, problems)
        if (!column.has("values")) problems += ConfigProblem("$path.values", "missing", "A column's values is the path a binding fills.")
        literal(column.get("format"), "$path.format", TABLE_FORMATS, problems)
        literal(column.get("align"), "$path.align", TABLE_ALIGNMENTS, problems)
    }

    private fun kpi(
        config: ObjectNode,
        problems: MutableList<ConfigProblem>,
    ) {
        unknownKeys(config, KPI_KEYS, "", problems)
        label(config.get("label"), "label", required = true, problems)
        if (!config.has("value")) problems += ConfigProblem("value", "missing", "A KPI's value is the path a binding fills.")
        literal(config.get("format"), "format", KPI_FORMATS, problems)
        config.get("unit")?.let { unit ->
            if (!unit.isTextual || unit.asText().length > MAX_UNIT_CHARS) {
                problems += ConfigProblem("unit", "wrong_type", "unit is a string of at most $MAX_UNIT_CHARS characters.")
            }
        }
        config.get("comparison")?.let { comparison ->
            if (!comparison.isObject) {
                problems += ConfigProblem("comparison", "wrong_type", "comparison is an object.")
            } else {
                unknownKeys(comparison, KPI_COMPARISON_KEYS, "comparison", problems)
                label(comparison.get("label"), "comparison.label", required = false, problems)
            }
        }
    }

    private fun label(
        node: JsonNode?,
        path: String,
        required: Boolean,
        problems: MutableList<ConfigProblem>,
    ) {
        when {
            node == null -> {
                if (required) problems += ConfigProblem(path, "missing", "'$path' is required.")
            }

            !node.isTextual || node.asText().isBlank() || node.asText().length > MAX_LABEL_CHARS -> {
                problems += ConfigProblem(path, "wrong_type", "'$path' is a non-blank string of at most $MAX_LABEL_CHARS characters.")
            }
        }
    }

    private fun literal(
        node: JsonNode?,
        path: String,
        allowed: List<String>,
        problems: MutableList<ConfigProblem>,
    ) {
        if (node != null && (!node.isTextual || node.asText() !in allowed)) {
            problems += ConfigProblem(path, "not_allowed", "'$path' is one of $allowed.")
        }
    }

    private fun unknownKeys(
        node: JsonNode,
        allowed: Set<String>,
        path: String,
        problems: MutableList<ConfigProblem>,
    ) {
        node.fieldNames().forEach { key ->
            if (key !in allowed) {
                problems +=
                    ConfigProblem(
                        JsonScan.join(path, key),
                        "unknown_key",
                        "'${key.safeEcho()}' is not a key here; allowed: ${allowed.sorted()}.",
                    )
            }
        }
    }
}
