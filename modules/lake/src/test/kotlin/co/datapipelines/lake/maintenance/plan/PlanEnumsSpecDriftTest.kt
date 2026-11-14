package co.datapipelines.lake.maintenance.plan

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The planner's vocabularies (#959) agree with enums.md: §55's wire pairs with [FiguresState], §56's error codes with
 * [PlanErrors] and its not-due reasons with [NotDueReason].
 */
class PlanEnumsSpecDriftTest {
    private val doc = LakeTestDb.repoFile("docs/enums.md").readText()

    private fun section(heading: String): String {
        val start = doc.indexOf("## $heading")
        check(start >= 0) { "Missing enum heading: $heading" }
        return doc.substring(start).substringBefore("\n---")
    }

    @Test
    fun `figures states match the doc`() {
        Regex("^\\| `([A-Z_]+)` \\| `([a-z_]+)` \\|", RegexOption.MULTILINE)
            .findAll(section("55. `FiguresState`"))
            .associate { it.groupValues[1] to it.groupValues[2] } shouldBe FiguresState.entries.associate { it.name to it.wire }
    }

    @Test
    fun `the error codes and the not-due reasons match the doc`() {
        val text = section("56. The lake maintenance planner")
        val codes = text.substringBefore("**Not-due reasons**")
        listOf(PlanErrors.TIMEOUT, PlanErrors.METADATA_TOO_LARGE).forEach { code ->
            codes.contains("`$code`") shouldBe true
        }
        val reasons =
            Regex("^\\| `([^`]+)` \\|", RegexOption.MULTILINE)
                .findAll(text)
                .map { it.groupValues[1] }
                .toSet()
        reasons shouldBe
            setOf(
                NotDueReason.NOT_MEASURED,
                NotDueReason.PROFILE_CHANGED,
                NotDueReason.NOT_REGISTERED,
                NotDueReason.CAPACITY,
                NotDueReason.CAPACITY_WAIT,
                NotDueReason.RULE_SILENT,
            )
    }
}
