package co.datapipelines.datasources

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #1036 — the qualification matrix table of dag-executor.md §16.6 (the one table whose header is
 * [HEADER]) cannot drift from [LocalWriteQualification.ROWS]: the same rows, in the same order,
 * every cell equal — engine, pinned driver, server, storage, operation, key mode, status and
 * reason. A row added, removed or re-statused in the code without the doc (or the reverse) is red,
 * naming the first differing row.
 */
class LocalWriteQualificationSpecDriftTest {
    @Test
    fun `the dag-executor qualification table is the runtime matrix, row for row`() {
        val documented = table()
        val runtime =
            LocalWriteQualification.ROWS.map { row ->
                listOf(
                    row.dialect.name,
                    row.driver,
                    row.server,
                    row.storage,
                    row.operation.wire,
                    row.keyMode.wire,
                    row.status.wire,
                    row.reason,
                )
            }
        withClue("row count: $SPEC_PATH vs LocalWriteQualification.ROWS") { documented.size shouldBe runtime.size }
        documented.zip(runtime).forEachIndexed { index, (doc, code) ->
            withClue("table row ${index + 1}") { doc shouldBe code }
        }
    }

    /** The cells of every row of the one table whose header is exactly [HEADER], backticks removed. */
    private fun table(): List<List<String>> {
        val lines = TestFiles.repoFile(SPEC_PATH).readLines()
        val header = lines.indexOfFirst { it.trim() == HEADER }
        check(header >= 0) { "$SPEC_PATH has no '$HEADER' table" }
        check(lines.count { it.trim() == HEADER } == 1) { "$SPEC_PATH has more than one '$HEADER' table" }
        return lines
            .drop(header + 2)
            .takeWhile { it.trim().startsWith("|") }
            .map { line ->
                val cells =
                    line
                        .trim()
                        .removePrefix("|")
                        .removeSuffix("|")
                        .split("|")
                        .map { it.trim().removeSurrounding("`") }
                check(cells.size == COLUMNS) { "malformed row: $line" }
                cells
            }
    }

    private companion object {
        const val SPEC_PATH = "docs/dag-executor.md"
        const val HEADER = "| Engine | Driver | Server | Storage | Operation | Key mode | Status | Reason |"
        const val COLUMNS = 8
    }
}
