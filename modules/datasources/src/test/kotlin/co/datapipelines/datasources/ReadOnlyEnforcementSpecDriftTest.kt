package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #463 — the per-dialect read-only table of dag-executor.md (the `| Dialect | Enforcement |
 * Mechanism |` table after the readonly-backstop paragraph of §8.2) cannot drift from
 * [ReadOnlyConnectionMode]: exactly one row per [Dialect] value, its Enforcement cell the
 * object's [ReadOnlyConnectionMode.enforcementFor] and its Mechanism cell the object's
 * [ReadOnlyConnectionMode.mechanismLabel]. A dialect added to the enum without a row, a row for
 * a dialect that does not exist, or a flipped cell is red, naming the dialect.
 */
class ReadOnlyEnforcementSpecDriftTest {
    @Test
    fun `the dag-executor read-only table has one row per dialect, matching the object's table`() {
        val rows = table()
        withClue("dialects in the doc table vs Dialect.entries") {
            rows.map { it.first }.sorted() shouldBe Dialect.entries.map { it.name }.sorted()
        }
        assertSoftly {
            rows.forEach { (dialect, enforcement, mechanism) ->
                val value = Dialect.valueOf(dialect)
                withClue("$dialect's Enforcement cell") { enforcement shouldBe ReadOnlyConnectionMode.enforcementFor(value).name }
                withClue("$dialect's Mechanism cell") { mechanism shouldBe ReadOnlyConnectionMode.mechanismLabel(value) }
            }
        }
    }

    /** The rows of the one table whose header is exactly [HEADER], as (dialect, enforcement, mechanism). */
    private fun table(): List<Triple<String, String, String>> {
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
                        .map { it.trim() }
                check(cells.size == 3) { "malformed row: $line" }
                Triple(cells[0].removeSurrounding("`"), cells[1].removeSurrounding("`"), cells[2])
            }
    }

    private companion object {
        const val SPEC_PATH = "docs/dag-executor.md"
        const val HEADER = "| Dialect | Enforcement | Mechanism |"
    }
}
