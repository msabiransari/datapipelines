package co.datapipelines.typesystem

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Parameter-engine record §6.4, every row: same descriptor ⇒ accept; an explicitly listed
 * lossless widening ⇒ accept; anything lossy, ambiguous or value-inferred ⇒ refuse. A decimal
 * widening must keep the integer digits as well as the fractional ones (P31).
 */
class TypeWideningTest {
    @Test
    fun `an identical descriptor is accepted, whatever the type`() {
        listOf(
            TypeDescriptor(LogicalType.STRING),
            TypeDescriptor(LogicalType.DATE),
            TypeDescriptor(LogicalType.DECIMAL, 12, 2),
            TypeDescriptor(LogicalType.DECIMAL, 15),
            TypeDescriptor(LogicalType.BIGDECIMAL, null, 4),
        ).forEach { withClue(it) { widens(it, it) shouldBe true } }
    }

    @Test
    fun `INTEGER widens to BIGINTEGER, and to a decimal only with ten integer digits or unbounded`() {
        widens(INTEGER, TypeDescriptor(LogicalType.BIGINTEGER)) shouldBe true
        withClue("§6.4's counterexamples: (9,0) cannot hold Int.MAX_VALUE, (10,0) can") {
            widens(INTEGER, TypeDescriptor(LogicalType.DECIMAL, 9, 0)) shouldBe false
            widens(INTEGER, TypeDescriptor(LogicalType.DECIMAL, 10, 0)) shouldBe true
        }
        widens(INTEGER, TypeDescriptor(LogicalType.BIGDECIMAL, 12, 2)) shouldBe true
        widens(INTEGER, TypeDescriptor(LogicalType.BIGDECIMAL, 11, 2)) shouldBe false
        widens(INTEGER, TypeDescriptor(LogicalType.BIGDECIMAL, null, 2)) shouldBe true
        widens(INTEGER, TypeDescriptor(LogicalType.DECIMAL, 15)) shouldBe false
    }

    @Test
    fun `BIGINTEGER widens to BIGDECIMAL only when the precision is unbounded`() {
        widens(BIGINTEGER, TypeDescriptor(LogicalType.BIGDECIMAL, null, 0)) shouldBe true
        widens(BIGINTEGER, TypeDescriptor(LogicalType.BIGDECIMAL, 38, 0)) shouldBe false
        widens(BIGINTEGER, TypeDescriptor(LogicalType.DECIMAL, 15, 0)) shouldBe false
    }

    @Test
    fun `an exact decimal widens only when BOTH the scale and the integer digits are kept`() {
        val from = TypeDescriptor(LogicalType.DECIMAL, 6, 2)
        withClue("Astra's counterexample: P ≥ p and S ≥ s, yet 9999.99 does not fit (6,4)") {
            widens(from, TypeDescriptor(LogicalType.DECIMAL, 6, 4)) shouldBe false
            widens(from, TypeDescriptor(LogicalType.BIGDECIMAL, 6, 4)) shouldBe false
        }
        widens(from, TypeDescriptor(LogicalType.DECIMAL, 8, 4)) shouldBe true
        widens(from, TypeDescriptor(LogicalType.DECIMAL, 7, 1)) shouldBe false
        widens(from, TypeDescriptor(LogicalType.BIGDECIMAL, null, 2)) shouldBe true
        widens(from, TypeDescriptor(LogicalType.BIGDECIMAL, null, 1)) shouldBe false
        withClue("a BIGDECIMAL source reads the same way into a BIGDECIMAL target") {
            widens(TypeDescriptor(LogicalType.BIGDECIMAL, 10, 2), TypeDescriptor(LogicalType.BIGDECIMAL, 12, 2)) shouldBe true
            widens(TypeDescriptor(LogicalType.BIGDECIMAL, null, 2), TypeDescriptor(LogicalType.BIGDECIMAL, 38, 2)) shouldBe false
        }
    }

    @Test
    fun `lossy, approximate and value-inferred flows are refused`() {
        val refused =
            listOf(
                TypeDescriptor(LogicalType.DECIMAL, 15) to TypeDescriptor(LogicalType.DECIMAL, 15, 2),
                TypeDescriptor(LogicalType.DECIMAL, 15) to TypeDescriptor(LogicalType.BIGDECIMAL, null, 2),
                TypeDescriptor(LogicalType.STRING) to TypeDescriptor(LogicalType.DATE),
                TypeDescriptor(LogicalType.STRING) to TypeDescriptor(LogicalType.INTEGER),
                TypeDescriptor(LogicalType.TIMESTAMP) to TypeDescriptor(LogicalType.DATE),
                BIGINTEGER to INTEGER,
                TypeDescriptor(LogicalType.BIGDECIMAL, 10, 2) to TypeDescriptor(LogicalType.DECIMAL, 12, 2),
                TypeDescriptor(LogicalType.DECIMAL, 12, 2) to TypeDescriptor(LogicalType.DECIMAL, 12, 1),
                TypeDescriptor(LogicalType.NULL) to TypeDescriptor(LogicalType.STRING),
                TypeDescriptor(LogicalType.NULL) to TypeDescriptor(LogicalType.NULL),
                TypeDescriptor(LogicalType.BOOLEAN) to INTEGER,
            )
        refused.forEach { (from, to) -> withClue("$from → $to") { widens(from, to) shouldBe false } }
    }

    private fun widens(
        from: TypeDescriptor,
        to: TypeDescriptor,
    ): Boolean = TypeWidening.isLossless(from, to)

    private companion object {
        val INTEGER = TypeDescriptor(LogicalType.INTEGER)
        val BIGINTEGER = TypeDescriptor(LogicalType.BIGINTEGER)
    }
}
