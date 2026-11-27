package co.datapipelines.visualization

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.util.Currency

/** One save-rule refusal: a stable [ChartReason] (record §4.4), the JSON Pointer it names, a message that echoes no document text. */
data class ChartRefusal(
    val reason: ChartReason,
    val pointer: String,
    val message: String,
) {
    val code: String get() = reason.wire
}

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

    /** The rules over [body] by the descriptor and the mark policy of the [ChartType] its `config.mark.type` names. */
    fun check(
        body: JsonNode,
        columns: List<InputColumn>?,
    ): ChartSaveVerdict {
        val type =
            ChartType.fromWire(
                body
                    .path("config")
                    .path("mark")
                    .path("type")
                    .asText(),
            )
        return check(body, columns, type?.channels.orEmpty(), type?.policy)
    }

    /**
     * The rules over [body] by the descriptor [channels] and the type's mark [policy] (record §9 G2: the walk and the
     * rules take a descriptor as their input, so a test hands them a layered descriptor of its own). A scale rule takes
     * the policy of the shape's own layer mark where it has one, else [policy].
     */
    fun check(
        body: JsonNode,
        columns: List<InputColumn>?,
        channels: List<ChartChannel>,
        policy: ChartMarkPolicy?,
    ): ChartSaveVerdict {
        val refusals = mutableListOf<ChartRefusal>()
        val hasTransform = body.get("transform")?.isObject == true
        if (!hasTransform && body.path("inputs").size() != 1) {
            refusals += ChartRefusal(ChartReason.INPUTS_CARDINALITY, "/inputs", "Without a transform a chart binds exactly one input.")
        }
        if (columns == null) {
            refusals +=
                ChartRefusal(
                    ChartReason.TRANSFORM_OUTPUT_UNKNOWN,
                    "/transform",
                    "The transform output contract is needed to type the encoded fields.",
                )
            return ChartSaveVerdict(refusals, emptyMap())
        }
        val byName = columns.associateBy { it.name }
        val shapes = ChartPositions.shapes(body.path("config"), channels)
        // The positions' column rules first, then each shape's own rules (record §4.2): one walk, every type.
        shapes.flatMap { it.positions }.forEach { field(it, byName, refusals) }
        shapes.filter { it.shape == VALUE }.forEach { valueRules(it, refusals) }
        shapes.filter { it.shape == VALUE || it.shape == POSITION }.forEach { scaleRules(policyOf(it, policy), it, refusals) }
        shapes.filter { it.shape == SHADE }.forEach { shadeRules(it, refusals) }
        shapes.filter { it.shape == SIZE }.forEach { sizeRules(it, refusals) }
        markRules(body.path("config"), shapes, refusals)
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
                    ChartReason.BINDINGS_MISMATCH,
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
    private const val SHADE = "shade"
    private const val SIZE = "size"
    private const val LEVEL = "level"
    private const val LOG = "log"

    /** The policy a scale rule reads for [shape]: its own layer mark's where it sits in a layer, else the type's. */
    private fun policyOf(
        shape: ChartShapeNode,
        policy: ChartMarkPolicy?,
    ): ChartMarkPolicy? = shape.mark?.let { ChartMarkPolicy.of(it) } ?: policy

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
                    ChartRefusal(ChartReason.FIELD_UNKNOWN, path, "The encoded field is not a column of the bound input.")
                }

                column.type == LogicalType.BINARY -> {
                    ChartRefusal(ChartReason.FIELD_BINARY, path, "A BINARY column cannot be encoded.")
                }

                column.type == LogicalType.NULL -> {
                    ChartRefusal(ChartReason.FIELD_NULL_TYPE, path, "A column of type NULL cannot be encoded.")
                }

                shape == VALUE && column.type !in ChartTypes.QUANTITATIVE -> {
                    ChartRefusal(
                        ChartReason.MEASURE_NOT_NUMERIC,
                        path,
                        "A measure must be an INTEGER, BIGINTEGER, DECIMAL or BIGDECIMAL column.",
                    )
                }

                (shape == SHADE || shape == SIZE) && column.type !in ChartTypes.QUANTITATIVE -> {
                    ChartRefusal(
                        ChartReason.MEASURE_NOT_NUMERIC,
                        path,
                        "A colour or size by value must be an INTEGER, BIGINTEGER, DECIMAL or BIGDECIMAL column.",
                    )
                }

                shape == POSITION && holderType == "quantitative" && column.type !in ChartTypes.QUANTITATIVE -> {
                    ChartRefusal(
                        ChartReason.MEASURE_NOT_NUMERIC,
                        path,
                        "A quantitative axis must be an INTEGER, BIGINTEGER, DECIMAL or BIGDECIMAL column.",
                    )
                }

                (shape == BAND || shape == POSITION) && holderType == "temporal" && column.type !in ChartTypes.TEMPORAL -> {
                    ChartRefusal(
                        ChartReason.CATEGORY_TYPE_MISMATCH,
                        "${position.holder.pointer}/type",
                        "A temporal category must be a DATE or TIMESTAMP column.",
                    )
                }

                (shape == BAND || shape == LEVEL) && column.nullable -> {
                    ChartRefusal(ChartReason.CATEGORY_NULLABLE, path, "A category column must not be nullable.")
                }

                shape == SERIES && column.nullable -> {
                    ChartRefusal(ChartReason.COLOR_NULLABLE, path, "A series column must not be nullable.")
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
            refusals += ChartRefusal(ChartReason.SCALE_DOMAIN_NORMALIZE, "$vp/scale", "A normalized stack has the fixed range 0 to 100 %.")
        }
        val format = v.get("format")?.takeIf { it.isTextual }?.asText()
        if (normalize && format != null && format != "percent") {
            refusals += ChartRefusal(ChartReason.FORMAT_NORMALIZE_PERCENT, "$vp/format", "A normalized stack is labelled in percent.")
        }
        val currency = format?.let { CURRENCY.find(it)?.groupValues?.get(1) }
        if (currency != null && currency !in CURRENCIES) {
            refusals +=
                ChartRefusal(
                    ChartReason.FORMAT_CURRENCY_UNKNOWN,
                    "$vp/format",
                    "The currency code is not an ISO 4217 code this server knows.",
                )
        }
    }

    /**
     * A `shade`'s own rule (record §4.4): `mid`, where given, is a finite number (`chart.shade.mid_invalid`). A number
     * past a double's range reads as an infinity and is refused, as a scale's range is in [scaleRules].
     */
    private fun shadeRules(
        shade: ChartShapeNode,
        refusals: MutableList<ChartRefusal>,
    ) {
        val mid = shade.node.get("mid") ?: return
        if (!mid.isNumber || !mid.asDouble().isFinite()) {
            refusals +=
                ChartRefusal(ChartReason.SHADE_MID_INVALID, "${shade.pointer}/mid", "The middle of a colour scale must be a finite number.")
        }
    }

    /**
     * A `size`'s own rule (record §4.4): `range`, where given, is strictly increasing (`chart.size.range_invalid`). Its
     * length and bounds are the schema's; the order of two values of one instance is not (the 2020-12 dialect compares
     * none).
     */
    private fun sizeRules(
        size: ChartShapeNode,
        refusals: MutableList<ChartRefusal>,
    ) {
        val range = size.node.get("range")?.takeIf { it.isArray && it.size() == 2 } ?: return
        if (!(range[0].asDouble() < range[1].asDouble())) {
            refusals += ChartRefusal(ChartReason.SIZE_RANGE_INVALID, "${size.pointer}/range", "A size range must be increasing.")
        }
    }

    /**
     * Gauge members (owner ruling #1103): `chart.bands.outside_domain`, `chart.bands.not_ascending` and
     * `chart.target.outside_domain`. Bands need not cover the domain; only inside ends raise the greatest end.
     * An invalid domain belongs to [scaleRules] alone. Non-number members belong to the schema.
     */
    private fun markRules(
        config: JsonNode,
        shapes: List<ChartShapeNode>,
        refusals: MutableList<ChartRefusal>,
    ) {
        val mark = config.path("mark")
        val bands = mark.get("bands")?.takeIf { it.isArray }
        val target = mark.get("target")?.takeIf { it.isNumber }
        if (bands == null && target == null) return
        val domain =
            shapes
                .firstOrNull { it.shape == VALUE }
                ?.node
                ?.path("scale")
                ?.get("domain")
                ?.takeIf { it.isArray && it.size() == 2 } ?: return
        val lo = domain[0].asDouble()
        val hi = domain[1].asDouble()
        if (!linearRange(lo, hi, holdsZero = false)) return
        var greatest = Double.NEGATIVE_INFINITY
        bands?.forEachIndexed { i, band ->
            val to = band.get("to")?.takeIf { it.isNumber }?.asDouble() ?: return@forEachIndexed
            val inside = to > lo && to <= hi
            val pointer = "/config/mark/bands/$i/to"
            when {
                !inside -> {
                    refusals +=
                        ChartRefusal(
                            ChartReason.BANDS_OUTSIDE_DOMAIN,
                            pointer,
                            "A band's end must be above the scale's low end and at most its high end.",
                        )
                }

                to <= greatest -> {
                    refusals +=
                        ChartRefusal(ChartReason.BANDS_NOT_ASCENDING, pointer, "Each band's end must be greater than the ends before it.")
                }
            }
            if (inside) greatest = maxOf(greatest, to)
        }
        if (target != null && !(lo <= target.asDouble() && target.asDouble() <= hi)) {
            refusals +=
                ChartRefusal(
                    ChartReason.TARGET_OUTSIDE_DOMAIN,
                    "/config/mark/target",
                    "The target must lie inside the scale's domain.",
                )
        }
    }

    /**
     * G4 and G8 over a value's or a position's `scale` (record §4.4): a log scale only under a mark [policy] that admits
     * it, never stacked or normalized and never beside `zero` (`chart.scale.log_unsupported`); a log range greater than
     * 0 and increasing (`chart.scale.domain_log`); a linear range increasing, and holding zero where the axis always
     * does — a value under a policy whose axis always holds zero (bar, area), or `zero: true`
     * (`chart.scale.domain_invalid`, today's bar rule unchanged). Both ends
     * must be finite: a number past a double's range reads as an infinity and is refused like a reversed range. A
     * normalized range is [valueRules]' refusal alone.
     */
    private fun scaleRules(
        policy: ChartMarkPolicy?,
        holder: ChartShapeNode,
        refusals: MutableList<ChartRefusal>,
    ) {
        val scale = holder.node.get("scale")?.takeIf { it.isObject } ?: return
        val sp = "${holder.pointer}/scale"
        val log = scale.path("type").asText() == LOG
        if (log && !logAdmitted(policy, holder, scale)) {
            refusals +=
                ChartRefusal(
                    ChartReason.SCALE_LOG_UNSUPPORTED,
                    "$sp/type",
                    "A log scale is refused on this chart type, on a stacked or normalized value and beside zero.",
                )
            return
        }
        val domain = scale.get("domain")?.takeIf { it.isArray && it.size() == 2 } ?: return
        if (holder.node.path("stack").asText() == "normalize") return
        val lo = domain[0].asDouble()
        val hi = domain[1].asDouble()
        val holdsZero = scale.path("zero").asBoolean(false) || (holder.shape == VALUE && policy?.alwaysZero == true)
        val fine = if (log) logRange(lo, hi) else linearRange(lo, hi, holdsZero)
        if (!fine) {
            refusals +=
                if (log) {
                    ChartRefusal(ChartReason.SCALE_DOMAIN_LOG, "$sp/domain", "A log range must be greater than 0 and increasing.")
                } else {
                    ChartRefusal(
                        ChartReason.SCALE_DOMAIN_INVALID,
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

    /** G4: a log scale under a policy that admits it, on a value that is not stacked or normalized, without `zero`. */
    private fun logAdmitted(
        policy: ChartMarkPolicy?,
        holder: ChartShapeNode,
        scale: JsonNode,
    ): Boolean = policy?.logAdmitted == true && holder.node.get("stack")?.isTextual != true && !scale.has("zero")

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
