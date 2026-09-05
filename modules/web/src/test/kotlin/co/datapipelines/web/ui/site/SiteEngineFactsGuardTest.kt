package co.datapipelines.web.ui.site

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The engine pages state driver facts; this reads the SPECS THOSE FACTS COME FROM and asserts
 * the six [SitePages.ENGINES] rows against them (073 §B).
 *
 * The pattern is 033/C5's four-way tool-count guard applied to a second set of transcribed
 * facts: a marketing page carrying a hand-copied driver coordinate is a claim that goes stale
 * silently, and "the driver is bundled" going stale is the kind of stale that wastes an
 * operator's afternoon. Editing either table without editing the registry now fails the build.
 *
 * Sources of truth: the dialect catalog table in `docs/datasources.md` §4.1 (driver + license)
 * and the driver matrix in `docs/deployment.md` §3.5 (what is in the published image).
 */
class SiteEngineFactsGuardTest {
    private val dialectCatalog: Map<String, List<String>> = markdownTable("datasources.md", "### 4.1 Dialect catalog")
    private val driverMatrix: Map<String, List<String>> = markdownTable("deployment.md", "### 3.5 JDBC driver matrix")

    @Test
    fun `both spec tables parsed, non-vacuously`() {
        // Seven dialects in each table. A parse that silently found nothing would make every
        // assertion below trivially true.
        dialectCatalog.keys.size shouldBe DIALECTS
        driverMatrix.keys.size shouldBe DIALECTS
        dialectCatalog.keys.containsAll(SitePages.ENGINES.map { it.dialect }) shouldBe true
    }

    @Test
    fun `every engine page's driver and license match the dialect catalog`() {
        val wrong =
            SitePages.ENGINES.mapNotNull { facts ->
                val row = dialectCatalog[facts.dialect] ?: return@mapNotNull "${facts.slug}: no ${facts.dialect} row"
                val driver = row[0]
                val license = row[1]
                when {
                    driver != facts.driver -> {
                        "${facts.slug}: driver is $driver in the spec, ${facts.driver} on the page"
                    }

                    !normalize(license).contains(normalize(facts.license)) -> {
                        "${facts.slug}: license is \"$license\" in the spec, \"${facts.license}\" on the page"
                    }

                    else -> {
                        null
                    }
                }
            }
        wrong shouldBe emptyList()
    }

    @Test
    fun `every engine page's bundled flag matches the driver matrix`() {
        val wrong =
            SitePages.ENGINES.mapNotNull { facts ->
                val row = driverMatrix[facts.dialect] ?: return@mapNotNull "${facts.slug}: no ${facts.dialect} row"
                val bundled = row[0].startsWith("Yes")
                if (bundled == facts.bundled) {
                    null
                } else {
                    "${facts.slug}: the matrix says \"${row[0]}\", the page says bundled=${facts.bundled}"
                }
            }
        wrong shouldBe emptyList()
    }

    @Test
    fun `a user-supplied driver page repeats the matrix's own instruction`() {
        val wrong =
            SitePages.ENGINES.filterNot { it.bundled }.mapNotNull { facts ->
                val instruction = driverMatrix.getValue(facts.dialect)[1]
                // The matrix writes `./gradlew -Pmysql bootJar` in backticks; the page says the
                // same thing in prose. What must agree is the PROFILE and the drop-in jar name.
                val profile = Regex("""-P([a-z]+)""").find(instruction)?.groupValues?.get(1)
                val jar = Regex("""([a-z0-9-]+\.jar)""").find(instruction)?.groupValues?.get(1)
                when {
                    profile == null || jar == null -> "${facts.slug}: could not parse the matrix instruction"
                    !facts.otherwise.contains("-P$profile") -> "${facts.slug}: page does not name the -P$profile profile"
                    !facts.otherwise.contains(jar) -> "${facts.slug}: page does not name $jar"
                    else -> null
                }
            }
        wrong shouldBe emptyList()
    }

    /**
     * The rows of the first markdown table after [heading] in [doc], keyed by the first cell
     * with its backticks stripped. Deliberately a parse of the SPEC rather than a fixture:
     * a fixture would have to be updated beside the registry, which is the drift this prevents.
     */
    private fun markdownTable(
        doc: String,
        heading: String,
    ): Map<String, List<String>> {
        val lines = File(repoRoot(), "docs/$doc").readLines()
        val start = lines.indexOfFirst { it.startsWith(heading) }
        check(start >= 0) { "$doc has no section starting \"$heading\" — the guard's anchor moved" }
        return lines
            .asSequence()
            .drop(start)
            .dropWhile { !it.startsWith("| ") }
            .takeWhile { it.startsWith("|") }
            .map { row -> row.trim('|').split('|').map { it.trim().trim('`', '*', ' ') } }
            .filterNot { it.first().startsWith("---") || it.first().equals("Dialect", ignoreCase = true) }
            .associate { it.first() to it.drop(1) }
    }

    private fun normalize(license: String): String = license.replace('-', ' ').lowercase()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs").isDirectory) dir = dir.parentFile
        return checkNotNull(dir) { "no ancestor of ${File("").absolutePath} holds a docs/ directory" }
    }

    private companion object {
        /** POSTGRES, ORACLE, MSSQL, MYSQL, H2, DUCKDB, SQLITE — H2 has no page, but must parse. */
        const val DIALECTS = 7
    }
}
