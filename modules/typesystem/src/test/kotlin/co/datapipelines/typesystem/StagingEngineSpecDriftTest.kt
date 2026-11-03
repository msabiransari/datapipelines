package co.datapipelines.typesystem

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Drift guard: the `StagingEngine` wire values in **enums.md §7** versus [StagingEngine].
 *
 * enums.md is the catalog of record for enum wire values, and `settings.tempdb.engine` is what
 * an author writes to pick an engine — a value added to the code without the catalog (or the
 * reverse) silently splits what authors are told they may write from what the deserializer
 * accepts. Same shape as `NodeTypeSpecDriftTest` (pipeline-contract): the section is located
 * by heading and its first table cells are parsed, so the assertions are driven by the
 * document, not by a constant a developer typed.
 */
class StagingEngineSpecDriftTest {
    private val documented: List<String> = parseStagingEnginesFromSpec()

    @Test
    fun `the parse found engines - guards against a silent empty parse`() {
        withClue("No StagingEngine values parsed from enums.md §7 — the heading or table format changed") {
            documented.isEmpty() shouldBe false
        }
    }

    @Test
    fun `every documented engine is a declared wire value and the reverse`() {
        documented shouldContainExactlyInAnyOrder StagingEngine.entries.map { it.wire }
    }

    @Test
    fun `every engine derives the dialect of the same name`() {
        // §7's "Templates on source: tempdb are then written in the <ENGINE> dialect": the
        // derivation is by engine, and each engine's dialect is the Dialect of its own name.
        StagingEngine.entries.forEach { engine ->
            withClue(engine.wire) { engine.dialect.wire shouldBe engine.wire }
        }
    }

    private companion object {
        const val SPEC_PATH = "docs/enums.md"
        const val SECTION_START = "## 7. `StagingEngine`"
        const val SECTION_END = "## 8."

        /** First cell of a markdown table row when it is a backticked bare uppercase value. */
        val TABLE_VALUE = Regex("^\\|\\s*`([A-Z0-9]+)`\\s*\\|", RegexOption.MULTILINE)

        fun parseStagingEnginesFromSpec(): List<String> {
            val text = repoFile(SPEC_PATH).readText()
            val start = text.indexOf(SECTION_START)
            check(start >= 0) { "'$SECTION_START' not found in $SPEC_PATH" }
            val end = text.indexOf(SECTION_END, start + SECTION_START.length)
            check(end > start) { "'$SECTION_END' not found after '$SECTION_START' in $SPEC_PATH" }
            return TABLE_VALUE.findAll(text.substring(start, end)).map { it.groupValues[1] }.toList()
        }

        fun repoFile(relativePath: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, relativePath)
                if (candidate.isFile) return candidate
                dir = dir.parentFile
            }
            error("$relativePath not found walking up from ${File("").absolutePath}")
        }
    }
}
