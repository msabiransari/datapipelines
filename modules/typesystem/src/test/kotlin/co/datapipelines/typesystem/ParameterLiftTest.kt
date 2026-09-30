package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * `ParameterLift` (#265) — a declared-type STRING into the wire [JsonNode] the strict coercion
 * judges. One case per type for the lifted node form, then the three strictness rules: no trim,
 * strict booleans, and the #278 cap refusing BEFORE any `BigDecimal` construction (bounded time).
 *
 * The lift is only transport: every refused-because-malformed case below is the COERCION's answer
 * one hop later, so the accepted node forms here are what matter, not the full refusal matrix —
 * that is `ParameterCoercionTest`'s.
 */
class ParameterLiftTest {
    @Test
    fun `every type lifts to the wire node form its coercion judges`() {
        // (type, raw text) -> the node class the coercion's judge for that type accepts.
        val lifted =
            listOf(
                LogicalType.INTEGER to "42",
                LogicalType.DECIMAL to "1.5",
                LogicalType.BOOLEAN to "true",
                LogicalType.BIGINTEGER to "9007199254740993",
                LogicalType.BIGDECIMAL to "12345678901234567890.55",
                LogicalType.STRING to "hello",
                LogicalType.BINARY to "AAE=",
                LogicalType.DATE to "2026-08-01",
                LogicalType.TIME to "23:59:59",
                LogicalType.TIMESTAMP to "2026-08-01T10:00:00Z",
            )
        lifted.forEach { (type, raw) ->
            withClue("$type lifting '$raw'") {
                val node = ParameterLift.lift(type, raw)
                node.shouldBeInstanceOf<com.fasterxml.jackson.databind.JsonNode>()
                // The round trip through the shared judge accepts every one of these.
                ParameterCoercion.coerce(type, node).shouldBeInstanceOf<ParameterCoercion.Outcome.Coerced>()
            }
        }
    }

    @Test
    fun `the numeric and boolean nodes carry the exact wire types`() {
        (ParameterLift.lift(LogicalType.INTEGER, "42") as LongNode).longValue() shouldBe 42L
        (ParameterLift.lift(LogicalType.DECIMAL, "1.5") as DecimalNode).decimalValue() shouldBe BigDecimal("1.5")
        (ParameterLift.lift(LogicalType.BOOLEAN, "true") as BooleanNode).booleanValue() shouldBe true
        (ParameterLift.lift(LogicalType.STRING, "acme") as TextNode).asText() shouldBe "acme"
        // The BIG textual types are string-on-wire: the lift passes the text through unchanged.
        (ParameterLift.lift(LogicalType.BIGINTEGER, "9007199254740993") as TextNode).asText() shouldBe "9007199254740993"
        (ParameterLift.lift(LogicalType.BIGDECIMAL, "12.50") as TextNode).asText() shouldBe "12.50"
        (ParameterLift.lift(LogicalType.TIMESTAMP, "2026-08-01T10:00:00Z") as TextNode).asText() shouldBe
            "2026-08-01T10:00:00Z"
    }

    @Test
    fun `the lifted nodes survive the full judge with the values intact`() {
        val judged =
            listOf(
                Triple(LogicalType.INTEGER, "42", 42 as Any),
                Triple(LogicalType.DECIMAL, "1.5", BigDecimal("1.5")),
                Triple(LogicalType.BOOLEAN, "false", false),
                Triple(LogicalType.STRING, "acme", "acme"),
                Triple(LogicalType.DATE, "2026-08-01", LocalDate.of(2026, 8, 1)),
                Triple(LogicalType.TIME, "23:59:59", LocalTime.of(23, 59, 59)),
                Triple(LogicalType.TIMESTAMP, "2026-08-01T10:00:00Z", Instant.parse("2026-08-01T10:00:00Z")),
            )
        judged.forEach { (type, raw, expected) ->
            withClue("$type from '$raw'") {
                val node = ParameterLift.lift(type, raw)
                val outcome = ParameterCoercion.coerce(type, checkNotNull(node))
                (outcome as ParameterCoercion.Outcome.Coerced).value shouldBe expected
            }
        }
    }

    @Test
    fun `nothing is trimmed - a padded text refuses, never binds`() {
        // P19/P28: the server never normalises what the caller sent. The padded forms below are
        // the ones the probe's old copy accepted and rest-api's v2.36 break retired everywhere else.
        ParameterLift.lift(LogicalType.INTEGER, " 12 ").shouldBeNull()
        ParameterLift.lift(LogicalType.DECIMAL, " 12.50 ").shouldBeNull()
        ParameterLift.lift(LogicalType.BOOLEAN, " true").shouldBeNull()
        // The BIG textual types are string-on-wire and lift as text; the COERCION refuses the padding.
        val padded = ParameterLift.lift(LogicalType.BIGINTEGER, " 5")
        padded.shouldBeInstanceOf<TextNode>()
        ParameterCoercion.coerce(LogicalType.BIGINTEGER, padded).shouldBeInstanceOf<ParameterCoercion.Outcome.Rejected>()
    }

    @Test
    fun `booleans are strict - only true and false lift`() {
        ParameterLift.lift(LogicalType.BOOLEAN, "yes").shouldBeNull()
        ParameterLift.lift(LogicalType.BOOLEAN, "TRUE").shouldBeNull()
        ParameterLift.lift(LogicalType.BOOLEAN, "1").shouldBeNull()
        ParameterLift.lift(LogicalType.BOOLEAN, "").shouldBeNull()
    }

    @Test
    fun `an integer text out of long range refuses at the lift`() {
        ParameterLift.lift(LogicalType.INTEGER, "99999999999999999999").shouldBeNull()
    }

    @Test
    fun `a NULL type is never liftable`() {
        ParameterLift.lift(LogicalType.NULL, "anything").shouldBeNull()
    }

    @Test
    fun `a decimal text over the digit cap refuses in bounded time - before any construction`() {
        // The #278 shape (ParameterCoercionTest's megabyte case): the parse of an n-digit decimal
        // is O(n²) on JDK 21, so the bound only bites at the magnitude the parse is quadratic in.
        // The refusal must be O(1) in the text's length; if the cap were applied after
        // BigDecimal(raw), the quadratic parse would blow this bound.
        val huge = "9".repeat(1_000_000)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            ParameterLift.lift(LogicalType.DECIMAL, huge).shouldBeNull()
        }
        // The brief's 100,000-digit value: refused too, same O(1) answer.
        ParameterLift.lift(LogicalType.DECIMAL, "9".repeat(100_000)).shouldBeNull()
    }

    @Test
    fun `the cap is the coercion's default and honours a smaller caller bound`() {
        ParameterLift.lift(LogicalType.DECIMAL, "9".repeat(1_025)).shouldBeNull()
        ParameterLift.lift(LogicalType.DECIMAL, "9".repeat(1_024)).shouldBeInstanceOf<DecimalNode>()
        // A caller's own bound tightens the same way (the probe and the endpoint take the default).
        ParameterLift.lift(LogicalType.DECIMAL, "9".repeat(11), maxNumericDigits = 10).shouldBeNull()
    }

    @Test
    fun `a decimal text under the cap that is not a number refuses`() {
        ParameterLift.lift(LogicalType.DECIMAL, "not-a-number").shouldBeNull()
    }
}
