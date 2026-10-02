package co.datapipelines.web.parameters

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the parameter-set audit event names in **enums.md §15** versus [ParameterSetAuditEvents] — the
 * `DashboardAuditEventsSpecDriftTest` pattern. The names are wire values in `audit_log.event`; a typo would write
 * rows no query finds, and nothing else would fail. The parse reads every backticked dotted token in the section
 * (the family row is the template-twins' combined notation, whose later names a row-anchored parse cannot see),
 * and [ParameterSetAuditEvents.FIVE] is the non-vacuity floor (#332).
 */
class ParameterSetAuditEventsSpecDriftTest {
    private val documented: Set<String> = parseParameterSetEventsFromSpec()

    @Test
    fun `the parse found parameter_set events - guards against a silent empty parse`() {
        withClue("No parameter_set.* events parsed from enums.md §15 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `the parse found the five lifecycle events - the non-vacuity floor (#332)`() {
        withClue("The §15 lifecycle row for the parameter-set family is missing one of the five human verbs' events") {
            documented.containsAll(ParameterSetAuditEvents.FIVE) shouldBe true
        }
    }

    @Test
    fun `the declared event names are exactly the documented ones`() {
        ParameterSetAuditEvents.ALL shouldContainExactlyInAnyOrder documented
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 15."
        const val SECTION_END = "## 16."

        /** Every backticked dotted token in the section — the combined twins' rows name every event. */
        val TABLE_VALUE = Regex("`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`")

        fun parseParameterSetEventsFromSpec(): Set<String> {
            val text = TestRepoFiles.read(SPEC_PATH)
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .filter { it.startsWith("parameter_set.") }
                .toSet()
        }
    }
}
