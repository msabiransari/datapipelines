package co.datapipelines.mcp

import co.datapipelines.mcp.docs.DocSet
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Size budgets over the rendered set (the 242a record §5, fourth row): every document and
 * section within the [DocSet.BUDGET_CHARS] response budget — a document over it is flagged
 * `over_budget` and served by section only, so the flag and the sections must exist — and the
 * core inside its line budget and its readable-column rule (131 §A.3, moved from
 * `SkillDistributionTest` with the core itself).
 *
 * The tool-description cap is NOT re-asserted here: [McpClientCapTest] owns it (#241), and one
 * guard per contract.
 */
class DocSetBudgetTest {
    private val docSet = DocSetTestSupport.renderedDocSet()

    @Test
    fun `every document and section fits the response budget`() {
        for (doc in docSet.docs) {
            withClue("${doc.name} over-budget flag disagrees with its ${doc.chars} chars") {
                doc.overBudget shouldBe (doc.chars > DocSet.BUDGET_CHARS)
            }
            for (section in doc.sections) {
                withClue(
                    "${doc.name} §${section.id} is ${section.markdown.length} chars — a section is written " +
                        "to fit the budget; the split-to-budget machinery is a fail-safe, not a design target",
                ) {
                    section.markdown.length shouldBeLessThanOrEqual DocSet.BUDGET_CHARS
                }
            }
        }
    }

    @Test
    fun `every over-budget document carries sections to read instead`() {
        for (doc in docSet.docs.filter { it.overBudget }) {
            withClue("${doc.name} is over budget but has no sections to serve") {
                doc.sections.size shouldBeGreaterThan 0
            }
        }
    }

    @Test
    fun `the core stays inside its line budget and its readable column`() {
        val lines = docSet.core.markdown.lines()
        withClue("the core is ${lines.size} lines — the 400-line cap the format pays for at every trigger") {
            lines.size shouldBeLessThanOrEqual 400
        }
        // 131 §A.3 — the cap is met by CHOOSING, never by folding. Exempt: fenced code
        // blocks and table rows — none of those is prose a wrapper can reflow.
        val offenders = offenderLines(lines)
        withClue("core prose lines folding past the readable column: $offenders") {
            offenders.shouldBeEmpty()
        }
    }

    /**
     * 242b — the core is an ORIENTATION document: the record's working bound for it is
     * 8,000 characters (the acceptance itself is the fresh-session reading path, measured in
     * the lane's proof, not here); this pin is the cheap every-build form of the same
     * discipline. The 242a core was 31,672 chars and section-served; 242b shrunk it to the
     * rules and the area index. A capability that cannot fit without growing the core past
     * the bound belongs in an area guide or a new area (record P2).
     */
    @Test
    fun `the core stays within its 8000-character orientation bound`() {
        withClue("the core is ${docSet.core.chars} chars — past the 8,000-character orientation bound") {
            docSet.core.chars shouldBeLessThanOrEqual CORE_BOUND_CHARS
        }
    }

    private fun offenderLines(lines: List<String>): List<Int> {
        var inFence = false
        return lines
            .withIndex()
            .mapNotNull { (index, line) ->
                when {
                    line.startsWith("```") -> {
                        inFence = !inFence
                        null
                    }

                    !inFence && !line.startsWith("|") && line.length > MAX_LINE_CHARS -> {
                        index + 1
                    }

                    else -> {
                        null
                    }
                }
            }
    }

    private companion object {
        const val MAX_LINE_CHARS = 200

        /** 242b — the core's orientation bound (the record's working number for the split). */
        const val CORE_BOUND_CHARS = 8_000
    }
}
