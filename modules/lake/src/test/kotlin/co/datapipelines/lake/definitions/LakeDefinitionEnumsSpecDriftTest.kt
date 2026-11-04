package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTestDb
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * enums.md §46–§48 versus the three closed vocabularies of the definitions: each section's table is parsed (Enum
 * and Wire value columns) and compared, as pairs, with the Kotlin entries; a value on one side only is red. §46 and
 * §47 are also V54's CHECK lists (`FlywayMigrationIntegrationTest` pins those).
 */
class LakeDefinitionEnumsSpecDriftTest {
    private val enums = LakeTestDb.repoFile("docs/enums.md").readText()

    @Test
    fun `each section's enum and wire pairs are exactly the Kotlin enum's`() {
        withClue("§46 LakeTableStatus") {
            pairs("## 46. `LakeTableStatus`") shouldBe LakeTableStatus.entries.associate { it.name to it.wire }
        }
        withClue("§47 LakeApplyState") {
            pairs("## 47. `LakeApplyState`") shouldBe LakeApplyState.entries.associate { it.name to it.wire }
        }
        withClue("§48 LakeChangeClass") {
            pairs("## 48. `LakeChangeClass`") shouldBe LakeChangeClass.entries.associate { it.name to it.wire }
        }
    }

    /** The (Enum, Wire value) cells of the table under [heading], up to the section's closing rule. */
    private fun pairs(heading: String): Map<String, String> {
        val start = enums.indexOf(heading)
        check(start >= 0) { "'$heading' not found in enums.md" }
        val section = enums.substring(start).substringBefore("\n---")
        return ROW
            .findAll(section)
            .associate { it.groupValues[1] to it.groupValues[2] }
            .also { check(it.size >= MIN_ROWS) { "fewer than $MIN_ROWS rows parsed under '$heading': $it" } }
    }

    private companion object {
        const val MIN_ROWS = 3
        val ROW = Regex("^\\| `([A-Z_]+)` \\| `([a-z_]+)` \\|", RegexOption.MULTILINE)
    }
}
