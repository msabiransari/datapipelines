package co.datapipelines.staging

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The §4.5 identifier rule in isolation — shape validation, case-insensitive duplicate
 * rejection, and case-preserving pass-through. `H2StagingIdentifierSafetyTest` proves the same
 * rule blocks an injection label from reaching any DDL; this pins the rule's own boundaries.
 */
class StagingIdentifiersTest {
    @Test
    fun `valid labels pass through unchanged and case-preserved`() {
        val labels = listOf("id", "_x", "MixedCase", "a1_b2", "A".repeat(63))
        StagingIdentifiers.validateColumnNames(labels) shouldBe labels
    }

    @Test
    fun `each malformed shape is rejected with its ordinal`() {
        // label → the 1-based ordinal the exception must report.
        val cases =
            listOf<Pair<String?, Int>>(
                "" to 1,
                "1abc" to 1,
                "a b" to 1,
                "a-b" to 1,
                "a;b" to 1,
                "a\"b" to 1,
                "a--b" to 1,
                "DROP TABLE x" to 1,
                "A".repeat(64) to 1,
                null to 1,
            )
        cases.forEach { (label, ordinal) ->
            val thrown = shouldThrow<StagingInvalidColumnNameException> { StagingIdentifiers.validateColumnNames(listOf(label)) }
            thrown.ordinal shouldBe ordinal
            thrown.label shouldBe label
            thrown.code shouldBe StagingErrorCodes.INVALID_COLUMN_NAME
        }
    }

    @Test
    fun `a case-insensitive duplicate is rejected at the second occurrence`() {
        // H2 folds unquoted identifiers to upper case, so `total` and `TOTAL` collide (§4.5).
        val thrown =
            shouldThrow<StagingInvalidColumnNameException> {
                StagingIdentifiers.validateColumnNames(listOf("total", "amount", "TOTAL"))
            }
        thrown.ordinal shouldBe 3
        thrown.label shouldBe "TOTAL"
    }

    @Test
    fun `an offending label surfaces in the error details, not a sanitised rename`() {
        val thrown = shouldThrow<StagingInvalidColumnNameException> { StagingIdentifiers.validateColumnNames(listOf("bad name")) }
        thrown.details["label"] shouldBe "bad name"
        thrown.details["ordinal"] shouldBe 1
    }
}
