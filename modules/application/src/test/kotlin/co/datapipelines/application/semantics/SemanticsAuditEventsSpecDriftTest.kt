package co.datapipelines.application.semantics

import co.datapipelines.application.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the learned-semantics audit event names in **enums.md §15** versus
 * [SemanticsAuditEvents] — the `DatasourceAuditEventsSpecDriftTest` pattern. The names are wire
 * values written into `audit_log.event`, and the owner's §9 acceptance is a COUNT over them: a
 * typo here would produce rows no query finds, and nothing else would fail.
 */
class SemanticsAuditEventsSpecDriftTest {
    private val documented: Set<String> = parseSemanticsEventsFromSpec()

    @Test
    fun `the parse found semantics events - guards against a silent empty parse`() {
        withClue("No semantics.* events parsed from enums.md §15 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `the declared event names are exactly the documented ones`() {
        SemanticsAuditEvents.ALL shouldContainExactlyInAnyOrder documented
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 15."
        const val SECTION_END = "## 16."

        val TABLE_VALUE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseSemanticsEventsFromSpec(): Set<String> {
            val text = TestRepoFiles.read(SPEC_PATH)
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .filter { it.startsWith("semantics.") }
                .toSet()
        }
    }
}
