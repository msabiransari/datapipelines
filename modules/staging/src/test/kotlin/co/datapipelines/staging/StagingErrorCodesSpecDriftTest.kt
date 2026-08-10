package co.datapipelines.staging

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Drift guard: the staging error codes in **pipeline-contract.md §13.5** versus
 * [StagingErrorCodes].
 *
 * pipeline-contract.md is the single authority for concrete error codes system-wide, but
 * `staging` cannot depend on `pipeline-contract` (module-structure.md §4.2), so the codes are
 * re-declared in [StagingErrorCodes]. Every other test asserts against a constant a developer
 * typed, so the constant and the document could drift apart with the suite still green. This
 * test reads §13.5 out of the document and drives its assertions from the parsed values: add a
 * `pipeline.staging.*` code to the spec and it fails until the constant exists; misspell a
 * constant and it fails until the document agrees.
 *
 * Zero-dependency, mirroring `PipelineErrorCodesSpecDriftTest` in `pipeline-contract`: the
 * table is located by heading and parsed with a regex over its first cell, and the constants
 * are read by plain reflection (`const val`s in an `object` compile to static final fields).
 */
class StagingErrorCodesSpecDriftTest {
    @Test
    fun `§13-5 and StagingErrorCodes list exactly the same codes`() {
        val documented = parseStagingCatalog()
        val declared = declaredConstants()

        // Guard the guard: a heading rename or table reformat that made the parse return nothing
        // would turn the set-difference assertions below into vacuous passes.
        withClue("Parsed nothing from §13.5 — the heading or table shape changed") {
            documented.size shouldBeGreaterThan 0
        }
        withClue("pipeline.staging.* codes in §13.5 with no StagingErrorCodes constant") {
            (documented - declared).shouldBeEmpty()
        }
        withClue("StagingErrorCodes constants absent from pipeline-contract.md §13.5") {
            (declared - documented).shouldBeEmpty()
        }
    }

    private fun parseStagingCatalog(): Set<String> {
        val text = repoFile(SPEC_PATH).readText()
        val section =
            text
                .substringAfter(SECTION_HEADING, missingDelimiterValue = "")
                .substringBefore("\n### ")
        require(section.isNotBlank()) { "Could not locate '$SECTION_HEADING' in $SPEC_PATH" }
        return CODE_CELL.findAll(section).map { it.groupValues[1] }.toSet()
    }

    private fun declaredConstants(): Set<String> =
        StagingErrorCodes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map {
                it.isAccessible = true
                it.get(null) as String
            }.toSet()

    private fun repoFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relativePath not found walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val SPEC_PATH = "docs/pipeline-contract.md"
        const val SECTION_HEADING = "### 13.5 Staging"

        /** The first table cell of a §13.5 row: a backticked `pipeline.staging.*` code. */
        val CODE_CELL = Regex("\\|\\s*`(pipeline\\.staging\\.[a-z_]+)`")
    }
}
