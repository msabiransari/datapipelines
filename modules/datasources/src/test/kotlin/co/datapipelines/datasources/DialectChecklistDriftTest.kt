package co.datapipelines.datasources

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * datasources.md §15 — the "adding a dialect" checklist — kept true mechanically.
 *
 * Arm 1 (the inventory cannot shrink behind the doc): every main Kotlin source file under
 * `modules` that names at least [MIN_DISTINCT] of the six customer engines is a per-dialect
 * switch point, and §15.1 must list it. A new switch point added anywhere in the tree turns
 * this red until the checklist knows about it.
 *
 * Arm 2 (the doc cannot rot): every repo path §15 names in backticks still exists.
 *
 * The metric counts DISTINCT engine names, not mentions: `H2` and `LAKE` are everywhere (tempdb
 * and the lake are not "engines a customer adds"), and a file that names one engine in a
 * comment is not a switch point.
 */
class DialectChecklistDriftTest {
    @Test
    fun `every source file that switches on three or more customer dialects is listed in section 15_1`() {
        val listed = checklistPaths()
        val switchPoints =
            repoFile("modules")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/kotlin/") }
                .filter { file ->
                    val text = file.readText()
                    ENGINES.count { Regex("\\b$it\\b").containsMatchIn(text) } >= MIN_DISTINCT
                }.map { it.relativeTo(repoRoot()).path }
                .sorted()
                .toList()
        switchPoints.shouldNotBeEmpty()
        withClue("per-dialect switch points missing from datasources.md §15.1 — add a row for each") {
            switchPoints.filterNot { it in listed }.shouldBeEmpty()
        }
    }

    @Test
    fun `every repo path section 15 names still exists`() {
        val paths = checklistPaths()
        paths.shouldNotBeEmpty()
        withClue("datasources.md §15 names paths that no longer exist") {
            paths.filterNot { File(repoRoot(), it).exists() }.shouldBeEmpty()
        }
    }

    private fun checklistPaths(): List<String> {
        val text = repoFile(SPEC_PATH).readText()
        val start = text.indexOf("## 15. Adding a dialect")
        val end = text.indexOf("## Appendix A", start)
        check(start >= 0 && end > start) { "datasources.md §15 not found" }
        return Regex("`((?:modules|deploy|docs|\\.agents)/[^`]+)`")
            .findAll(text.substring(start, end))
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return dir
    }

    private fun repoFile(relative: String): File = File(repoRoot(), relative).also { check(it.exists()) { "$relative missing" } }

    companion object {
        const val SPEC_PATH = "docs/datasources.md"
        const val MIN_DISTINCT = 3
        val ENGINES = listOf("POSTGRES", "MYSQL", "ORACLE", "MSSQL", "SQLITE", "DUCKDB")
    }
}
