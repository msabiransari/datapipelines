package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode

/**
 * The chart engine as the server sees it (js-rendered charts record §5.1, §5.3; brief 986 D2): render a chart document
 * over its columns at a frame and read the output, or write the standalone SVG of one chart for a frame and a theme
 * (the picture service). The implementation lives in `modules/application`, over the scripting module's entry for
 * trusted code and the engine's files; `modules/web` wires it. No route, no MCP tool and no permission reaches it
 * (O-11): its callers are the mechanical check's judge (§4.12), tests and the evidence a test run stores.
 *
 * A save NEVER calls it (O-12): the save rules read [ChartType]'s channel descriptor, so a chart saves where the
 * JavaScript runtime does not run.
 */
interface ChartEngine {
    /**
     * `JsRendered.render` over [input] at [frame], with the server's width table and nothing hidden.
     *
     * @throws ChartEngineFailure when the evaluation throws or hits a limit: there is no result then (record §4.3).
     */
    fun render(
        input: ChartInput,
        frame: ChartFrame,
    ): ChartOutput

    /**
     * The standalone SVG of [input] at [frame] in [theme]: written by the engine's one escaping string writer.
     *
     * @throws ChartEngineFailure as [render].
     */
    fun picture(
        input: ChartInput,
        frame: ChartFrame,
        theme: ChartTheme,
    ): ChartPicture
}

/**
 * One chart to draw: its type, the document as stored, the delivered columns `{field: [values]}` (the runtime's own
 * shape, values in their wire form) and each encoded field's logical type.
 */
data class ChartInput(
    val type: ChartType,
    val document: JsonNode,
    val columns: Map<String, List<Any?>>,
    val types: Map<String, LogicalType>,
) {
    companion object {
        /**
         * The input for a test case's [rows] (the judge's one conversion, record §4.12): one column per field of
         * [types], in that order, each the rows' values in row order — a field a row lacks is null there, as the
         * runtime delivers a null cell. Values keep their wire form: nothing is formatted or normalized.
         */
        fun ofRows(
            type: ChartType,
            document: JsonNode,
            rows: List<Map<String, Any?>>,
            types: Map<String, LogicalType>,
        ): ChartInput = ChartInput(type, document, types.keys.associateWith { field -> rows.map { it[field] } }, types)
    }
}

/** A frame in CSS pixels, each side clamped to [MIN] to [MAX] (record §7, "Input bounds"). */
class ChartFrame private constructor(
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean = other is ChartFrame && other.width == width && other.height == height

    override fun hashCode(): Int = width * MAX + height

    override fun toString(): String = "${width}x$height"

    companion object {
        const val MIN = 16
        const val MAX = 4096

        /** The judge's fixed frame (record §4.12): 640 × 400. */
        val CANONICAL: ChartFrame = of(640, 400)

        /** [width] × [height], each clamped into [MIN]..[MAX]. */
        fun of(
            width: Int,
            height: Int,
        ): ChartFrame = ChartFrame(width.coerceIn(MIN, MAX), height.coerceIn(MIN, MAX))
    }
}

/** The theme NAMES the engine carries defaults for (record §4.10): a closed list, never a path or a token value. */
enum class ChartTheme(
    val wire: String,
) {
    LIGHT("light"),
    DARK("dark"),
}

/** The engine's output (record §4.3), read by field. [json] is the whole result as the engine wrote it. */
class ChartOutput(
    val json: JsonNode,
) {
    /** `state`: `too-small`, `refused`, `no-data` or `rendered`. */
    val state: String get() = json.path("state").asText()

    /** `summary.marks`: the type's mark count of the visible series. */
    val marks: Int get() = json.path("summary").path("marks").asInt()

    /** `summary.refusal.code`, or null. */
    val refusal: String? get() = json.path("summary").path("refusal").path("code").takeIf { it.isTextual }?.asText()

    /** The engine's version (record §4.11). */
    val version: String get() = json.path("version").asText()

    /** Every `text` primitive's SHOWN text, in order: its cut text when it was cut, else its full text (§4.3). */
    val shownTexts: List<String>
        get() =
            json.path("primitives").filter { it.path("k").asText() == "text" }.map { p ->
                p.get("shown")?.takeIf { it.isTextual }?.asText() ?: p.path("text").asText()
            }

    companion object {
        const val RENDERED = "rendered"
        const val NO_DATA = "no-data"
        const val REFUSED = "refused"
        const val TOO_SMALL = "too-small"
    }
}

/** The picture service's answer: the engine's state and, for `rendered` and `no-data`, the SVG text. */
data class ChartPicture(
    val state: String,
    val svg: String?,
)

/**
 * An engine evaluation that gave no result (record §5.3, "Named outcomes"; §6): a limit — [limit] names it (`timeout`,
 * `statements`, `heap`, `depth`, `output`, `input_bytes`, `result_bytes`) — or a failure of the engine or its host
 * ([limit] null). The next call starts with a full budget.
 */
class ChartEngineFailure(
    val limit: String?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
