package co.datapipelines.visualization

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** ECMAScript Number::toString digits and layout, independent of the JDK's different decimal selection. */
internal object EcmaNumberText {
    fun of(value: Double): String =
        when {
            value.isNaN() -> "NaN"
            value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
            value == 0.0 -> "0"
            else -> (if (value < 0) "-" else "") + layout(shortest(Math.abs(value)))
        }

    /**
     * Test both adjacent k-digit decimals: around a power of two the rounding interval is asymmetric, so its
     * nearest decimal can fail to round-trip while the other succeeds. At the first successful precision choose
     * the closest round-tripping decimal, ties to an even significand (ECMA-262 §6.1.6.1.20).
     */
    @Suppress("MagicNumber") // every binary64 value round-trips by 17 significant decimal digits
    private fun shortest(value: Double): BigDecimal {
        val exact = BigDecimal(value)
        for (precision in 1..17) {
            val candidates =
                listOf(RoundingMode.FLOOR, RoundingMode.CEILING)
                    .map { exact.round(MathContext(precision, it)).stripTrailingZeros() }
                    .filter { it.toDouble() == value }
            val chosen =
                candidates.minWithOrNull(
                    compareBy<BigDecimal> { it.subtract(exact).abs() }
                        .thenBy { it.unscaledValue().testBit(0) },
                )
            if (chosen != null) return chosen
        }
        error("binary64 did not round-trip at 17 digits")
    }

    /** ECMA's plain notation from 1e-6 to below 1e21, scientific notation outside those bounds. */
    @Suppress("MagicNumber") // ECMA-262's notation bounds
    private fun layout(decimal: BigDecimal): String {
        val digits = decimal.unscaledValue().toString()
        val k = digits.length
        val n = k - decimal.scale()
        return when {
            n in k..21 -> {
                digits + "0".repeat(n - k)
            }

            n in 1..21 -> {
                digits.substring(0, n) + "." + digits.substring(n)
            }

            n in -5..0 -> {
                "0." + "0".repeat(-n) + digits
            }

            else -> {
                val exponent = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (exponent >= 0) "+" else "-") + Math.abs(exponent)
            }
        }
    }
}
