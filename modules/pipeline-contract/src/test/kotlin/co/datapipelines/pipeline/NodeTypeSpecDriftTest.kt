package co.datapipelines.pipeline

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Drift guard: the `NodeType` wire values in **enums.md §2** versus [NodeType].
 *
 * enums.md is the catalog of record for enum wire values ("Case & serialization convention"),
 * and the `type` field is the one enum every pipeline document carries — a value added to the
 * code without the catalog (or vice versa) silently splits what authors may write from what the
 * deserializer accepts. Same cross-boundary pattern as [PipelineErrorCodesSpecDriftTest] and
 * `DatasourceAuditEventsSpecDriftTest` (datasources), which this file's shape copies.
 */
class NodeTypeSpecDriftTest {
    private val documented: List<String> = parseNodeTypesFromSpec()

    @Test
    fun `the parse found node types - guards against a silent empty parse`() {
        withClue("No NodeType values parsed from enums.md §2 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `every documented value is a declared wire value`() {
        documented shouldContainExactlyInAnyOrder NodeType.entries.map { it.wire }
    }

    @Test
    fun `every declared wire value is documented`() {
        NodeType.entries.map { it.wire } shouldContainExactlyInAnyOrder documented
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 2."
        const val SECTION_END = "## 3."

        /** First cell of a markdown table row when it is a backticked bare uppercase value. */
        val TABLE_VALUE = Regex("^\\|\\s*`([A-Z]+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseNodeTypesFromSpec(): List<String> {
            val text = Fixtures.repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE
                .findAll(text.substring(start, end))
                .map { it.groupValues[1] }
                .toList()
        }
    }
}
