package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Three independent authorities must agree: documented wire pairs, Kotlin and the shipped CHECK constraint. */
class DeliveryEnumsSpecDriftTest {
    private val doc = LakeTestDb.repoFile("docs/enums.md").readText()
    private val migration = LakeTestDb.repoFile("modules/app/src/main/resources/db/migration/V55__lake_deliveries.sql").readText()

    @Test
    fun `delivery states match both the doc and the database CHECK`() {
        val values = LakeDeliveryState.entries.associate { it.name to it.name }
        pairs("49. `LakeDeliveryState`") shouldBe values
        checkValues("chk_lake_deliveries_state") shouldBe values.values.toSet()
    }

    @Test
    fun `failure phases match both the doc and the database CHECK`() {
        val values = LakeDeliveryFailurePhase.entries.associate { it.name to it.wire }
        pairs("50. `LakeDeliveryFailurePhase`") shouldBe values
        checkValues("chk_lake_deliveries_failure_phase") shouldBe values.values.toSet()
    }

    @Test
    fun `attempt outcomes match both the doc and the database CHECK`() {
        val values = LakeDeliveryAttemptOutcome.entries.associate { it.name to it.name }
        pairs("51. `LakeDeliveryAttemptOutcome`") shouldBe values
        checkValues("chk_lake_delivery_attempts_outcome") shouldBe values.values.toSet()
    }

    private fun pairs(heading: String): Map<String, String> {
        val start = doc.indexOf("## $heading")
        check(start >= 0) { "Missing enum heading: $heading" }
        val rows = Regex("^\\| `([A-Z_]+)` \\| `([A-Za-z_]+)` \\|", RegexOption.MULTILINE)
        return rows
            .findAll(doc.substring(start).substringBefore("\n---"))
            .associate { it.groupValues[1] to it.groupValues[2] }
            .also { check(it.size >= MIN_ROWS) }
    }

    private fun checkValues(constraint: String): Set<String> {
        val line = migration.lineSequence().single { it.contains("CONSTRAINT $constraint ") }
        return Regex("'([A-Za-z_]+)'")
            .findAll(line)
            .map { it.groupValues[1] }
            .toSet()
            .also { check(it.size >= MIN_ROWS) }
    }

    private companion object {
        const val MIN_ROWS = 3
    }
}
