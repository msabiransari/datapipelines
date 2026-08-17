package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * The inverse of [ParameterCoercion]: one **bound** parameter value back to its §6.3 wire
 * encoding, so it can cross a process internal boundary that speaks pipeline JSON.
 *
 * The one consumer is composition (design 2026-08-13-pipeline-node-type §4.1): a PIPELINE node's
 * `${parent_param}` reference resolves against the parent's **bound** runtime parameters — typed
 * Kotlin values, defaults already applied — while the child execution's `ExecuteRequest` carries
 * parameters as `JsonNode`s for its own bind pass. Re-encoding through the declared type (the
 * child's, which §12.9 proved identical to the parent's) keeps the strict forms strict:
 * `BIGINTEGER`/`BIGDECIMAL` go back as JSON **strings**, `BINARY` as padded base64, `TIME` always
 * with seconds — a naive `valueToTree` would emit a JSON number for a `BigInteger` and the
 * child's binder would rightly reject it.
 *
 * The round trip is exact by construction for every value [ParameterCoercion] can produce —
 * `ParameterWireEncoderTest` proves it per type — and `null` encodes as JSON null, which the
 * child's binder reads as "not supplied" (defaults then apply, exactly as §7.1 says).
 */
object ParameterWireEncoder {
    /** Encodes [value] — bound per [type] — into the JSON form §6.3 mandates for that type. */
    fun encode(
        type: LogicalType,
        value: Any?,
    ): JsonNode {
        if (value == null) return NullNode.instance
        return when (type) {
            LogicalType.INTEGER -> IntNode((value as Int))

            LogicalType.DECIMAL -> DecimalNode((value as BigDecimal))

            LogicalType.BOOLEAN -> BooleanNode.valueOf((value as Boolean))

            LogicalType.BIGINTEGER -> TextNode((value as BigInteger).toString())

            LogicalType.BIGDECIMAL -> TextNode((value as BigDecimal).toPlainString())

            LogicalType.STRING -> TextNode((value as String))

            // Padded standard base64 — the same rule §6.3 enforces on ingress (RFC 4648 §4).
            LogicalType.BINARY -> TextNode(Base64.getEncoder().encodeToString((value as ByteArray)))

            // ISO_LOCAL_DATE is exactly §6.3's YYYY-MM-DD for every in-range year.
            LogicalType.DATE -> TextNode((value as LocalDate).format(DateTimeFormatter.ISO_LOCAL_DATE))

            // `LocalTime.toString()` drops zero seconds ("10:00"), which §6.3's shape forbids —
            // so the format is explicit: seconds always, fraction only when there is one. A
            // bound value carries at most microsecond digits (ingress rejects more), so the
            // six-digit form is exact, never truncated.
            LogicalType.TIME -> TextNode(if ((value as LocalTime).nano == 0) TIME_WHOLE.format(value) else TIME_FRACTION.format(value))

            // `Instant.toString()` is ISO 8601 with the `Z` designator at whatever precision the
            // value holds — accepted by §6.3's offset-bearing parse and exact on the round trip.
            LogicalType.TIMESTAMP -> TextNode((value as Instant).toString())

            // Unreachable: NULL is not a declarable parameter type (§6.2).
            LogicalType.NULL -> throw IllegalArgumentException("NULL is not a declarable parameter type")
        }
    }

    private val TIME_WHOLE = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val TIME_FRACTION = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS")
}
