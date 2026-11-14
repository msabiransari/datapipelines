package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** #962: the documented cleanup vocabularies (enums.md §58–§61) are exactly the Kotlin wire values. */
class CleanupEnumsSpecDriftTest {
    private val doc = LakeTestDb.repoFile("docs/enums.md").readText()

    @Test
    fun `the incomplete reasons match the doc`() {
        pairs("## 58. `IncompleteReason`") shouldBe IncompleteReason.entries.associate { it.name to it.wire }
        pairs("## 58. `IncompleteReason`").size shouldBe 2
    }

    @Test
    fun `the observer outcomes match the doc`() {
        pairs("## 59. `ObserverOutcome`") shouldBe ObserverOutcome.entries.associate { it.name to it.wire }
        pairs("## 59. `ObserverOutcome`").size shouldBe 3
    }

    @Test
    fun `the observed ref types match the doc`() {
        pairs("## 60. `ObservedRefType`") shouldBe ObservedRefType.entries.associate { it.name to it.wire }
        pairs("## 60. `ObservedRefType`").size shouldBe 2
    }

    @Test
    fun `the floor's not-applicable spelling matches the doc`() {
        section("## 61. The cleanup's expiry floor spelling") shouldContain "`${ExpiryFloor.NOT_APPLICABLE}`"
    }

    private fun section(heading: String): String {
        val start = doc.indexOf(heading)
        check(start >= 0) { "Missing enum heading: $heading" }
        return doc.substring(start).substringBefore("\n---")
    }

    private fun pairs(heading: String): Map<String, String> =
        Regex("^\\| `([A-Z_]+)` \\| `([a-z_]+)` \\|", RegexOption.MULTILINE)
            .findAll(section(heading))
            .associate { it.groupValues[1] to it.groupValues[2] }
}
