package co.datapipelines.typesystem

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * #265 F4 — the one path where a SERVER-PRODUCED wire string meets the stricter probe lift:
 * the pipeline checks runner feeds `sql_probe` the BOUND context's values re-encoded by
 * [ParameterWireEncoder] (`encode(type, value).asText()` — a DECIMAL arrives as DecimalNode
 * text). A type whose wire text the lift or the coercion then refuses would silently fail
 * EVERY check of that type, so the round trip is proven per type, not by sampling.
 *
 * encode → lift → coerce is exactly `PipelineCheckRunner.wireString` → `SqlProbeParameter`
 * → `ProbePayloads.toJdbcValue`; the asserted equality is on the coerced value the JDBC bind
 * would receive.
 */
class ParameterWireLiftRoundTripTest {
    @Test
    fun `every logical type's wire text survives the probe's lift and strict coercion`() {
        val bound =
            mapOf(
                LogicalType.INTEGER to 42,
                LogicalType.DECIMAL to BigDecimal("1234.56"),
                LogicalType.BOOLEAN to true,
                LogicalType.BIGINTEGER to BigInteger("9223372036854775806"),
                LogicalType.BIGDECIMAL to BigDecimal("12345678901234567890.55"),
                LogicalType.STRING to "acme",
                LogicalType.BINARY to byteArrayOf(0x00, 0x01),
                LogicalType.DATE to LocalDate.of(2026, 8, 1),
                LogicalType.TIME to LocalTime.of(23, 59, 59),
                LogicalType.TIMESTAMP to Instant.parse("2026-08-01T10:00:00Z"),
            )
        bound.forEach { (type, value) ->
            withClue("$type round-tripping ${value::class.simpleName}") {
                val wire = ParameterWireEncoder.encode(type, value).asText()
                val node = ParameterLift.lift(type, wire)
                val outcome = ParameterCoercion.coerce(type, checkNotNull(node))
                val coerced = outcome.shouldBeInstanceOf<ParameterCoercion.Outcome.Coerced>()
                sameValue(type, coerced.value, value)
            }
        }
    }

    /** Value equality as the bind sees it: numerically for decimals, by content for bytes. */
    private fun sameValue(
        type: LogicalType,
        coerced: Any,
        bound: Any,
    ) {
        when (type) {
            LogicalType.DECIMAL, LogicalType.BIGDECIMAL -> {
                (coerced as BigDecimal).compareTo(bound as BigDecimal) shouldBe 0
            }

            LogicalType.BINARY -> {
                (coerced as ByteArray).contentEquals(bound as ByteArray) shouldBe true
            }

            else -> {
                coerced shouldBe bound
            }
        }
    }
}
