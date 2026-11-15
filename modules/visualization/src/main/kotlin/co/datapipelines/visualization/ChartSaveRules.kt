package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.util.Currency

/** One save-rule refusal: a stable `chart.*` code (record §4.4), the JSON Pointer it names, a message that echoes no document text. */
data class ChartRefusal(
    val code: String,
    val pointer: String,
    val message: String,
)

/** The save rules' verdict: every refusal, and the bindings the encoding derives (sorted, each field to itself). */
data class ChartSaveVerdict(
    val refusals: List<ChartRefusal>,
    val derivedBindings: Map<String, String>,
)

/**
 * Layer 2 of a chart document's save (record §4.4): the rules BEYOND the JSON Schema, ported from the 854 reference
 * `D/scripts/save-rules.mjs` code for code. Runs only on a body the schema accepted. [columns] is the contract the
 * chart reads — the one input's, or the transform's OUTPUT (null when it cannot be known: `chart.transform.output_unknown`).
 * `bindings` is DERIVED: absent from [body] (the author omitted it) it is not judged; present, it must equal the
 * derived map exactly (`chart.bindings.mismatch`).
 */
object ChartSaveRules {
    private val CURRENCY = Regex("^currency\\(([A-Z]{3})\\)$")

    /** ISO 4217 codes the JVM knows — the server-side twin of the reference's `Intl.supportedValuesOf('currency')`. */
    val CURRENCIES: Set<String> = Currency.getAvailableCurrencies().map { it.currencyCode }.toSet()

    fun check(
        body: JsonNode,
        columns: List<InputColumn>?,
    ): ChartSaveVerdict {
        val refusals = mutableListOf<ChartRefusal>()
        val hasTransform = body.get("transform")?.isObject == true
        if (!hasTransform && body.path("inputs").size() != 1) {
            refusals += ChartRefusal("chart.inputs.cardinality", "/inputs", "Without a transform a chart binds exactly one input.")
        }
        if (columns == null) {
            refusals +=
                ChartRefusal(
                    "chart.transform.output_unknown",
                    "/transform",
                    "The transform output contract is needed to type the encoded fields.",
                )
            return ChartSaveVerdict(refusals, emptyMap())
        }
        val byName = columns.associateBy { it.name }
        val shapes = ChartPositions.shapes(body.path("config"))
        // The positions' column rules first, then each shape's own rules (record §4.2): one walk, every type.
        shapes.flatMap { it.positions }.forEach { field(it, byName, refusals) }
        val type =
            ChartType.fromWire(
                body
                    .path("config")
                    .path("mark")
                    .path("type")
                    .asText(),
            )
        shapes.filter { it.shape == VALUE }.forEach { valueRules(it, refusals) }
        shapes.filter { it.shape == VALUE || it.shape == POSITION }.forEach { scaleRules(type, it, refusals) }
        val derived =
            shapes
                .flatMap { it.positions }
                .map { it.field }
                .toSortedSet()
                .associateWith { it }
        val authored = body.get("bindings")
        if (authored != null && !sameBindings(authored, derived)) {
            refusals +=
                ChartRefusal(
                    "chart.bindings.mismatch",
                    "/bindings",
                    "bindings is derived from the encoding: each encoded field maps to itself.",
                )
        }
        return ChartSaveVerdict(refusals, derived)
    }

    /** The §4.4 shapes whose column rules this object knows; any other shape takes the rules every position takes. */
    private const val BAND = "band"
    private const val VALUE = "value"
    private const val SERIES = "series"
    private const val POSITION = "position"
    private const val LOG = "log"

    /** G8: the types whose value axis always holds zero (`scale.zero: false` is refused by their schema). */
    private val ALWAYS_ZERO = setOf(ChartType.BAR, ChartType.AREA)

    /** G4: the types that admit a log scale; any other is refused with `chart.scale.log_unsupported`. */
    private val LOG_TYPES = setOf(ChartType.BAR, ChartType.LINE)

    // One refusal per position, first rule wins: a branch per save rule in the reference's order (record §4.4), each at
    // the pointer of its position (§4.2).
    @Suppress("CyclomaticComplexMethod")
    private fun field(
        position: ChartPosition,
        columns: Map<String, InputColumn>,
        refusals: MutableList<ChartRefusal>,
    ) {
        val path = position.pointer
        val shape = position.holder.shape
        val column = columns[position.field]
        val holderType =
            position.holder.node
                .path("type")
                .asText()
        val refusal =
            when {
                column == null -> {
                    ChartRefusal("chart.field.unknown", path, "The encoded field is not a column of the bound input.")
                }

                column.type == LogicalType.BINARY -> {
                    ChartRefusal("chart.field.binary", path, "A BINARY column cannot be encoded.")
                }

                column.type == LogicalType.NULL -> {
                    ChartRefusal("chart.field.null_type", path, "A column of type NULL cannot be encoded.")
                }

                shape == VALUE && column.type !in ChartTypes.QUANTITATIVE -> {
                    ChartRefusal(
                        "chart.measure.not_numeric",
                        path,
                        "A measure must be an INTEGER, BIGINTEGER, DECIMAL or BIGDECIMAL column.",
                    )
                }

                shape == POSITION && holderType == "quantitative" && column.type !in ChartTypes.QUANTITATIVE -> {
                    ChartRefusal(
                        "chart.measure.not_numeric",
                        path,
                        "A quantitative axis must be an INTEGER, BIGINTEGER, DECIMAL or BIGDECIMAL column.",
                    )
                }

                (shape == BAND || shape == POSITION) && holderType == "temporal" && column.type !in ChartTypes.TEMPORAL -> {
                    ChartRefusal(
                        "chart.category.type_mismatch",
                        "${position.holder.pointer}/type",
                        "A temporal category must be a DATE or TIMESTAMP column.",
                    )
                }

                shape == BAND && column.nullable -> {
                    ChartRefusal("chart.category.nullable", path, "A category column must not be nullable.")
                }

                shape == SERIES && column.nullable -> {
                    ChartRefusal("chart.color.nullable", path, "A series column must not be nullable.")
                }

                else -> {
                    null
                }
            }
        refusal?.let { refusals += it }
    }

    private fun valueRules(
        value: ChartShapeNode,
        refusals: MutableList<ChartRefusal>,
    ) {
        val v = value.node
        val vp = value.pointer
        val normalize = v.path("stack").asText() == "normalize"
        val domain = v.path("scale").get("domain")?.takeIf { it.isArray && it.size() == 2 }
        if (domain != null && normalize) {
            refusals += ChartRefusal("chart.scale.domain_normalize", "$vp/scale", "A normalized stack has the fixed range 0 to 100 %.")
        }
        val format = v.get("format")?.takeIf { it.isTextual }?.asText()
        if (normalize && format != null && format != "percent") {
            refusals += ChartRefusal("chart.format.normalize_percent", "$vp/format", "A normalized stack is labelled in percent.")
        }
        val currency = format?.let { CURRENCY.find(it)?.groupValues?.get(1) }
        if (currency != null && currency !in CURRENCIES) {
            refusals +=
                ChartRefusal("chart.format.currency_unknown", "$vp/format", "The currency code is not an ISO 4217 code this server knows.")
        }
    }

    /**
     * G4 and G8 over a value's or a position's `scale` (record §4.4): a log scale only on a type that admits it, never
     * stacked or normalized and never beside `zero` (`chart.scale.log_unsupported`); a log range greater than 0 and
     * increasing (`chart.scale.domain_log`); a linear range increasing, and holding zero where the axis always does —
     * a bar's or an area's value, or `zero: true` (`chart.scale.domain_invalid`, today's bar rule unchanged). Both ends
     * must be finite: a number past a double's range reads as an infinity and is refused like a reversed range. A
     * normalized range is [valueRules]' refusal alone.
     */
    private fun scaleRules(
        type: ChartType?,
        holder: ChartShapeNode,
        refusals: MutableList<ChartRefusal>,
    ) {
        val scale = holder.node.get("scale")?.takeIf { it.isObject } ?: return
        val sp = "${holder.pointer}/scale"
        val log = scale.path("type").asText() == LOG
        if (log && !logAdmitted(type, holder, scale)) {
            refusals +=
                ChartRefusal(
                    "chart.scale.log_unsupported",
                    "$sp/type",
                    "A log scale is refused on this chart type, on a stacked or normalized value and beside zero.",
                )
            return
        }
        val domain = scale.get("domain")?.takeIf { it.isArray && it.size() == 2 } ?: return
        if (holder.node.path("stack").asText() == "normalize") return
        val lo = domain[0].asDouble()
        val hi = domain[1].asDouble()
        val holdsZero = scale.path("zero").asBoolean(false) || (holder.shape == VALUE && type in ALWAYS_ZERO)
        val fine = if (log) logRange(lo, hi) else linearRange(lo, hi, holdsZero)
        if (!fine) {
            refusals +=
                if (log) {
                    ChartRefusal("chart.scale.domain_log", "$sp/domain", "A log range must be greater than 0 and increasing.")
                } else {
                    ChartRefusal(
                        "chart.scale.domain_invalid",
                        "$sp/domain",
                        "The range must be increasing, and contain zero where the axis holds zero (a bar or an area grows from zero).",
                    )
                }
        }
    }

    /** A log range: greater than 0, increasing, its top finite. */
    private fun logRange(
        lo: Double,
        hi: Double,
    ): Boolean = lo > 0.0 && lo < hi && hi.isFinite()

    /** G4: a log scale on a type that admits it, on a value that is not stacked or normalized, without `zero`. */
    private fun logAdmitted(
        type: ChartType?,
        holder: ChartShapeNode,
        scale: JsonNode,
    ): Boolean = type in LOG_TYPES && holder.node.get("stack")?.isTextual != true && !scale.has("zero")

    /** A linear range: both ends finite, increasing, and holding zero when [holdsZero]. */
    private fun linearRange(
        lo: Double,
        hi: Double,
        holdsZero: Boolean,
    ): Boolean {
        val increasing = lo.isFinite() && hi.isFinite() && lo < hi
        return increasing && (!holdsZero || (lo <= 0.0 && hi >= 0.0))
    }

    /** The authored map equals [derived] entry for entry (order-free — a JSON object's member order carries no meaning). */
    private fun sameBindings(
        authored: JsonNode,
        derived: Map<String, String>,
    ): Boolean {
        if (!authored.isObject || authored.size() != derived.size) return false
        return derived.all { (field, column) -> authored.get(field)?.let { it.isTextual && it.asText() == column } == true }
    }
}
