package co.datapipelines.lake.maintenance

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Three authorities agree on the maintenance vocabularies (#957): the documented wire pairs (enums.md §52–§54), the Kotlin
 * enums and V60's CHECK constraints; and the run-finished audit event name matches enums.md §15.
 */
class MaintenanceEnumsSpecDriftTest {
    private val doc = LakeTestDb.repoFile("docs/enums.md").readText()
    private val migration = LakeTestDb.repoFile("modules/app/src/main/resources/db/migration/V60__lake_maintenance_runs.sql").readText()

    @Test
    fun `run kinds match both the doc and the database CHECK`() {
        val values = MaintenanceRunKind.entries.associate { it.name to it.wire }
        pairs("52. `MaintenanceRunKind`") shouldBe values
        checkValues("chk_lake_maintenance_runs_kind") shouldBe values.values.toSet()
    }

    @Test
    fun `run states match both the doc and the database CHECK`() {
        val values = MaintenanceRunState.entries.associate { it.name to it.wire }
        pairs("53. `MaintenanceRunState`") shouldBe values
        checkValues("chk_lake_maintenance_runs_state") shouldBe values.values.toSet()
    }

    @Test
    fun `part outcomes match the doc`() {
        pairs("54. `MaintenanceOutcome`") shouldBe MaintenanceOutcome.entries.associate { it.name to it.wire }
    }

    @Test
    fun `the maintenance audit events are exactly the documented ones, and there is one`() {
        val start = doc.indexOf("## 15.")
        val end = doc.indexOf("## 16.", start + 1)
        check(start >= 0 && end > start)
        val documented =
            Regex("^\\|\\s*`(lake\\.maintenance\\.[a-z_]+)`\\s*\\|", RegexOption.MULTILINE)
                .findAll(doc.substring(start, end))
                .map { it.groupValues[1] }
                .toSet()
        documented.size shouldBe 1
        LakeMaintenanceAuditEvents.ALL shouldContainExactlyInAnyOrder documented
    }

    private fun pairs(heading: String): Map<String, String> {
        val start = doc.indexOf("## $heading")
        check(start >= 0) { "Missing enum heading: $heading" }
        return Regex("^\\| `([A-Z_]+)` \\| `([a-z_]+)` \\|", RegexOption.MULTILINE)
            .findAll(doc.substring(start).substringBefore("\n---"))
            .associate { it.groupValues[1] to it.groupValues[2] }
            .also { check(it.size >= MIN_ROWS) { "too few rows under $heading: $it" } }
    }

    private fun checkValues(constraint: String): Set<String> {
        val line = migration.lineSequence().single { it.contains("CONSTRAINT $constraint ") }
        return Regex("'([a-z_]+)'")
            .findAll(line)
            .map { it.groupValues[1] }
            .toSet()
            .also { check(it.size >= MIN_ROWS) { "too few CHECK values in $constraint: $it" } }
    }

    private companion object {
        const val MIN_ROWS = 2
    }
}
