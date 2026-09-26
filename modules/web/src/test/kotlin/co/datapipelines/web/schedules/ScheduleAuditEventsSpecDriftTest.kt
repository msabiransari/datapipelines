package co.datapipelines.web.schedules

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the schedule audit event names in **enums.md §15** versus [ScheduleAuditEvents] —
 * the `MailAuditEventsSpecDriftTest` pattern. The names are wire values in `audit_log.event`; a
 * typo would write rows no query finds, and nothing else would fail.
 */
class ScheduleAuditEventsSpecDriftTest {
    private val documented: Set<String> = parseScheduleEventsFromSpec()

    @Test
    fun `the parse found schedule events - guards against a silent empty parse`() {
        withClue("No schedule.* events parsed from enums.md §15 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `the declared event names are exactly the documented ones`() {
        ScheduleAuditEvents.ALL shouldContainExactlyInAnyOrder documented
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 15."
        const val SECTION_END = "## 16."

        val TABLE_VALUE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseScheduleEventsFromSpec(): Set<String> {
            val text = TestRepoFiles.read(SPEC_PATH)
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .filter { it.startsWith("schedule.") }
                .toSet()
        }
    }
}
