package co.datapipelines.pipeline

import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Turns one supplied JSON parameter value into the typed Kotlin object the Context holds
 * (pipeline-contract §6.3, §7.1 step 4).
 *
 * ## Strict, by contract
 *
 * §6.3: "Coercion is strict — wrong wire encoding is rejected, never silently converted."
 * The four named cases and why each is a rejection rather than a convenience:
 *
 *  - A JSON **number** where `BIGINTEGER`/`BIGDECIMAL` is declared. Those types are
 *    string-on-wire precisely because their value space exceeds the IEEE 754 safe integer
 *    range (type-system §2 principle 2); accepting the number form would silently lose
 *    precision for the values the type exists to carry.
 *  - A JSON **string** where `INTEGER`/`DECIMAL`/`BOOLEAN` is declared. Accepting `"1"` for
 *    a number means accepting `"1abc"` next, and the parse rules become the contract.
 *  - A zone-less `TIMESTAMP`. "The server never guesses the client's timezone" — and it
 *    cannot: the client's zone is not in the request.
 *  - A loose `DATE`/`TIME`. Exact ISO 8601 only, so `01/02/2026` never gets read as either
 *    the first of February or the second of January depending on locale.
 *
 * Every rejection is `pipeline.execution.invalid_parameter_type` (§13.3).
 */
internal object ParameterCoercion {
    /** Successfully coerced value, or the reason it was rejected. */
    sealed interface Outcome {
        data class Coerced(
            val value: Any,
        ) : Outcome

        data class Rejected(
            val reason: String,
        ) : Outcome
    }

    fun coerce(
        type: LogicalType,
        node: JsonNode,
    ): Outcome =
        when (type) {
            LogicalType.INTEGER -> integer(node)

            LogicalType.DECIMAL -> decimal(node)

            LogicalType.BOOLEAN -> if (node.isBoolean) ok(node.booleanValue()) else wrongForm(type, node, "a JSON boolean")

            LogicalType.BIGINTEGER -> bigInteger(node)

            LogicalType.BIGDECIMAL -> bigDecimal(node)

            LogicalType.STRING -> if (node.isTextual) ok(node.asText()) else wrongForm(type, node, "a JSON string")

            LogicalType.BINARY -> binary(node)

            LogicalType.DATE -> date(node)

            LogicalType.TIME -> time(node)

            LogicalType.TIMESTAMP -> timestamp(node)

            // Unreachable: NULL is excluded from parameter declarations (§6.2) and the
            // deserializer's pre-scan rejects it with `parameter_type_invalid`.
            LogicalType.NULL -> Outcome.Rejected("NULL is not a declarable parameter type")
        }

    private fun integer(node: JsonNode): Outcome =
        when {
            !node.isNumber -> wrongForm(LogicalType.INTEGER, node, "a JSON number")
            !node.isIntegralNumber -> Outcome.Rejected("INTEGER takes a whole number; got ${node.asText()}")
            !node.canConvertToInt() -> Outcome.Rejected("INTEGER is int32; ${node.asText()} is out of range")
            else -> ok(node.intValue())
        }

    private fun decimal(node: JsonNode): Outcome =
        if (node.isNumber) ok(node.decimalValue()) else wrongForm(LogicalType.DECIMAL, node, "a JSON number")

    private fun bigInteger(node: JsonNode): Outcome {
        if (!node.isTextual) return wrongForm(LogicalType.BIGINTEGER, node, "a JSON string")
        val parsed = runCatching { BigInteger(node.asText().trim()) }.getOrNull()
        return when {
            parsed == null -> Outcome.Rejected("BIGINTEGER value is not an integer: '${node.asText().truncateForError()}'")
            parsed.bitLength() >= Long.SIZE_BITS -> Outcome.Rejected("BIGINTEGER is int64; value is out of range")
            else -> ok(parsed)
        }
    }

    private fun bigDecimal(node: JsonNode): Outcome {
        if (!node.isTextual) return wrongForm(LogicalType.BIGDECIMAL, node, "a JSON string")
        val parsed = runCatching { BigDecimal(node.asText().trim()) }.getOrNull()
        return parsed?.let(::ok)
            ?: Outcome.Rejected("BIGDECIMAL value is not a number: '${node.asText().truncateForError()}'")
    }

    /**
     * §6.3 — `BINARY` takes **padded** standard base64 (RFC 4648 §4, length ≡ 0 mod 4).
     *
     * The explicit length check is load-bearing: `Base64.getDecoder()` accepts unpadded input,
     * so without it `"AAE"` and `"AAE="` would both be read while §3.5 mandates the padded form
     * on egress. Accepting on ingress what we refuse to emit makes the contract asymmetric in
     * exactly the way §6.3 calls the "symmetric contract".
     */
    private fun binary(node: JsonNode): Outcome {
        if (!node.isTextual) return wrongForm(LogicalType.BINARY, node, "a base64 JSON string")
        val text = node.asText()
        if (text.length % BASE64_QUANTUM != 0) {
            return Outcome.Rejected("BINARY must be PADDED standard base64 (length a multiple of $BASE64_QUANTUM)")
        }
        val decoded = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
        return decoded?.let(::ok) ?: Outcome.Rejected("BINARY value is not valid RFC 4648 base64")
    }

    private fun date(node: JsonNode): Outcome {
        if (!node.isTextual) return wrongForm(LogicalType.DATE, node, "a JSON string")
        val text = node.asText()
        if (!DATE_SHAPE.matches(text)) return Outcome.Rejected("DATE must be exactly YYYY-MM-DD; got '${text.truncateForError()}'")
        return parsed(text) { LocalDate.parse(it) } ?: Outcome.Rejected("DATE '${text.truncateForError()}' is not a real date")
    }

    private fun time(node: JsonNode): Outcome {
        if (!node.isTextual) return wrongForm(LogicalType.TIME, node, "a JSON string")
        val text = node.asText()
        if (!TIME_SHAPE.matches(text)) {
            return Outcome.Rejected("TIME must be exactly HH:MM:SS[.ffffff]; got '${text.truncateForError()}'")
        }
        return parsed(text) { LocalTime.parse(it) } ?: Outcome.Rejected("TIME '${text.truncateForError()}' is not a real time")
    }

    private fun timestamp(node: JsonNode): Outcome {
        if (!node.isTextual) return wrongForm(LogicalType.TIMESTAMP, node, "a JSON string")
        val text = node.asText()
        return parsed(text) { OffsetDateTime.parse(it).toInstant() }
            ?: Outcome.Rejected(
                "TIMESTAMP must be ISO 8601 with an explicit offset or 'Z' " +
                    "(the server never guesses the client's timezone); got '${text.truncateForError()}'",
            )
    }

    private fun <T : Any> parsed(
        text: String,
        parse: (String) -> T,
    ): Outcome? =
        try {
            ok(parse(text))
        } catch (_: DateTimeParseException) {
            null
        }

    private fun ok(value: Any): Outcome = Outcome.Coerced(value)

    private fun wrongForm(
        type: LogicalType,
        node: JsonNode,
        expected: String,
    ): Outcome =
        Outcome.Rejected(
            "${type.wire} is ${type.wireForm.name.lowercase()}-on-wire and takes $expected; got ${node.nodeType.name.lowercase()}",
        )

    private const val BASE64_QUANTUM = 4

    /**
     * §6.3 — `DATE` is exactly `YYYY-MM-DD`.
     *
     * The anchored shape is not redundant with `LocalDate.parse`: the ISO parser accepts
     * `+12026-08-01` and `-0001-08-01` (expanded-year forms) and a bare `LocalDate.parse` would
     * take them. Four digits, no sign — nothing else.
     */
    private val DATE_SHAPE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /**
     * §6.3 — `TIME` is exactly `HH:MM:SS[.ffffff]`, with **1–6** fractional digits.
     *
     * Sub-microsecond input is rejected, not silently truncated: `LocalTime` holds nanoseconds
     * and would keep them, while §3.5's egress emits microseconds — so a 9-digit input would
     * come back out shortened, a silent transformation of the caller's value.
     */
    private val TIME_SHAPE = Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?$")
}
