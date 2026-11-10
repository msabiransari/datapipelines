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
        val ch = BarChannels.of(body.path("config"))
        field(Role.BAND, ch.band, byName, refusals)
        field(Role.VALUE, ch.value, byName, refusals)
        ch.color?.let { field(Role.COLOR, it, byName, refusals) }
        valueRules(ch.value, refusals)
        val derived = listOfNotNull(ch.band.field, ch.value.field, ch.color?.field).toSortedSet().associateWith { it }
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

    private enum class Role { BAND, VALUE, COLOR }

    // One refusal per field, first rule wins: a branch per save rule in the reference's order (record §4.4).
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun field(
        role: Role,
        channel: BarChannels.Channel,
        columns: Map<String, InputColumn>,
        refusals: MutableList<ChartRefusal>,
    ) {
        val name = channel.field ?: return
        val path = "${channel.pointer}/field"
        val column = columns[name]
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

                role == Role.VALUE && column.type !in ChartTypes.QUANTITATIVE -> {
                    ChartRefusal(
                        "chart.measure.not_numeric",
                        path,
                        "A measure must be an INTEGER, BIGINTEGER, DECIMAL or BIGDECIMAL column.",
                    )
                }

                role == Role.BAND && channel.encoding.path("type").asText() == "temporal" && column.type !in ChartTypes.TEMPORAL -> {
                    ChartRefusal(
                        "chart.category.type_mismatch",
                        "${channel.pointer}/type",
                        "A temporal category must be a DATE or TIMESTAMP column.",
                    )
                }

                role == Role.BAND && column.nullable -> {
                    ChartRefusal("chart.category.nullable", path, "A category column must not be nullable.")
                }

                role == Role.COLOR && column.nullable -> {
                    ChartRefusal("chart.color.nullable", path, "A series column must not be nullable.")
                }

                else -> {
                    null
                }
            }
        refusal?.let { refusals += it }
    }

    private fun valueRules(
        value: BarChannels.Channel,
        refusals: MutableList<ChartRefusal>,
    ) {
        val v = value.encoding
        val vp = value.pointer
        val normalize = v.path("stack").asText() == "normalize"
        val domain = v.path("scale").get("domain")?.takeIf { it.isArray && it.size() == 2 }
        if (domain != null && normalize) {
            refusals += ChartRefusal("chart.scale.domain_normalize", "$vp/scale", "A normalized stack has the fixed range 0 to 100 %.")
        } else if (domain != null) {
            val lo = domain[0].asDouble()
            val hi = domain[1].asDouble()
            if (!(lo < hi && lo <= 0.0 && hi >= 0.0)) {
                refusals +=
                    ChartRefusal(
                        "chart.scale.domain_invalid",
                        "$vp/scale/domain",
                        "The value range must be increasing and contain zero (bars grow from zero).",
                    )
            }
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

    /** The authored map equals [derived] entry for entry (order-free — a JSON object's member order carries no meaning). */
    private fun sameBindings(
        authored: JsonNode,
        derived: Map<String, String>,
    ): Boolean {
        if (!authored.isObject || authored.size() != derived.size) return false
        return derived.all { (field, column) -> authored.get(field)?.let { it.isTextual && it.asText() == column } == true }
    }
}
