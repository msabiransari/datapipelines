package co.datapipelines.datasources

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the datasource audit event names in **enums.md §15** versus
 * [DatasourceAuditEvents].
 *
 * The names are wire values written into `audit_log.event`; a typo here would produce rows no
 * query finds and no dashboard shows, and nothing else in the module would fail. So the test
 * reads the document, parses §15's datasource table, and drives its assertions **from the parsed
 * values** — the same cross-boundary pattern [DatasourceErrorCodesSpecDriftTest] applies to the
 * error codes, for the same reason (`datasources` cannot import an enum that lives elsewhere).
 */
class DatasourceAuditEventsSpecDriftTest {
    private val documented: Set<String> = parseDatasourceEventsFromSpec()

    @Test
    fun `the parse found datasource events - guards against a silent empty parse`() {
        withClue("No datasource.* events parsed from enums.md §15 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `the declared event names are exactly the documented ones`() {
        DatasourceAuditEvents.ALL shouldContainExactlyInAnyOrder documented
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 15."
        const val SECTION_END = "## 16."

        /** First cell of a markdown table row when it is a backticked dotted lowercase name. */
        val TABLE_VALUE = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseDatasourceEventsFromSpec(): Set<String> {
            val text = TestFiles.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .filter { it.startsWith("datasource.") }
                .toSet()
        }
    }
}
