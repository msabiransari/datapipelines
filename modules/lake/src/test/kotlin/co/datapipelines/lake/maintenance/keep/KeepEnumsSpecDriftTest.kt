package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** #960: the documented reasons a period gets no keep (enums.md §55) are exactly [KeepNotKeptReason]'s wire pairs. */
class KeepEnumsSpecDriftTest {
    private val doc = LakeTestDb.repoFile("docs/enums.md").readText()

    @Test
    fun `the not-kept reasons match the doc`() {
        val heading = "## 55. `KeepNotKeptReason`"
        val start = doc.indexOf(heading)
        check(start >= 0) { "Missing enum heading: $heading" }
        val documented =
            Regex("^\\| `([A-Z_]+)` \\| `([a-z_]+)` \\|", RegexOption.MULTILINE)
                .findAll(doc.substring(start).substringBefore("\n---"))
                .associate { it.groupValues[1] to it.groupValues[2] }
        documented shouldBe KeepNotKeptReason.entries.associate { it.name to it.wire }
        documented.size shouldBe 2
    }
}
