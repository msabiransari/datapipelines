package co.datapipelines.lake.definitions

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the `lake.definition.*` audit event names in enums.md §15 versus [LakeAuditEvents] (the
 * `SemanticsAuditEventsSpecDriftTest` shape). The names are `audit_log.event` wire values; a typo would write rows
 * no query finds and nothing else would fail.
 */
class LakeAuditEventsSpecDriftTest {
    private val documented: Set<String> = documentedEvents()

    @Test
    fun `the parse found the three lake definition events - no silent empty parse`() {
        documented.size shouldBe EXPECTED_EVENTS
    }

    @Test
    fun `the declared event names are exactly the documented ones`() {
        LakeAuditEvents.ALL shouldContainExactlyInAnyOrder documented
    }

    private companion object {
        const val EXPECTED_EVENTS = 3
        const val SECTION_START = "## 15."
        const val SECTION_END = "## 16."
        val TABLE_VALUE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun documentedEvents(): Set<String> {
            val text = LakeTestDb.repoFile("docs/enums.md").readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in enums.md" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in enums.md" }
            return TABLE_VALUE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .filter { it.startsWith("lake.definition.") }
                .toSet()
        }
    }
}
