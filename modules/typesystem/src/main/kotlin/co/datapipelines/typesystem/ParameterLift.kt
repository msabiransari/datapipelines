package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.TextNode
import java.math.BigDecimal

/**
 * Lifts a declared-type STRING into the wire [JsonNode] [ParameterCoercion] judges (pipeline-contract
 * §6.3) — the transport half of the "decode, then judge" split for the two surfaces whose input is
 * text, not JSON: a published endpoint's query string (`EndpointRequestValidator`) and `sql_probe`'s
 * typed-string parameters (#265). The judge is always the shared coercion; this class only decides
 * which JSON shape a raw text must wear before it arrives there.
 *
 * ## Strict, by contract
 *
 * - **Nothing is trimmed** (P19/P28): a padded `" 12 "` stays padded and is refused downstream,
 *   exactly as the execute body refuses it — the same deliberate break rest-api's change log v2.36
 *   records for every other surface.
 * - **Booleans are strict**: `true`/`false` and nothing else — a `?dry_run=yes` must never read as
 *   false.
 * - **The #278 digit cap is applied to the RAW text** before any `BigDecimal` is constructed: the
 *   parse of an n-digit decimal is O(n²) on JDK 21, so the O(1) length check is what bounds the
 *   work, refusing as `null` before the construction the cap exists to prevent. The BIG textual
 *   types (`BIGINTEGER`/`BIGDECIMAL`) are string-on-wire and pass through as text — [ParameterCoercion]
 *   applies the same cap before its own parse.
 *
 * `null` is the refusal: the caller reports it in its own vocabulary (the endpoint's
 * `invalid_parameter_type` entry, the probe's parameter-naming exception) and the value text never
 * travels in either.
 *
 * A `NULL` type is not liftable — it is not a declarable parameter type (§6.2) — and answers `null`
 * like any other refusal rather than guessing a JSON null.
 */
object ParameterLift {
    /**
     * [raw] as the wire form [type] expects, or `null` when it cannot be one. [maxNumericDigits]
     * bounds the decimal text accepted here; the default is [ParameterCoercion.MAX_NUMERIC_DIGITS],
     * the same cap the coercion enforces on its side.
     */
    fun lift(
        type: LogicalType,
        raw: String,
        maxNumericDigits: Int = ParameterCoercion.MAX_NUMERIC_DIGITS,
    ): JsonNode? =
        when (type) {
            LogicalType.INTEGER -> raw.toLongOrNull()?.let { LongNode(it) }

            LogicalType.DECIMAL -> decimal(raw, maxNumericDigits)

            // Kotlin's toBooleanStrictOrNull, not toBoolean: the latter maps every other string to
            // false, and a truthy-looking value must never run as false.
            LogicalType.BOOLEAN -> raw.toBooleanStrictOrNull()?.let { BooleanNode.valueOf(it) }

            // Not a declarable parameter type (§6.2): a refusal, never a guessed JSON null.
            LogicalType.NULL -> null

            else -> TextNode(raw)
        }

    /**
     * The cap FIRST — `text.length` is O(1) and the decimal parse it guards is O(n²) (#278) — then
     * the construction. Below the cap the forms are exactly the pre-cap ones.
     */
    private fun decimal(
        raw: String,
        maxNumericDigits: Int,
    ): JsonNode? {
        if (raw.length > maxNumericDigits) return null
        return runCatching { DecimalNode(BigDecimal(raw)) }.getOrNull()
    }
}
