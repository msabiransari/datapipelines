package co.datapipelines.config

import co.datapipelines.config.ChartLibraryRemnantAllowlist.Entry
import co.datapipelines.config.ChartLibraryRemnantAllowlist.Kind
import co.datapipelines.config.ChartLibraryRemnantAllowlist.WholeFile
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No chart-library code or text is left in the tracked tree (#988, the js-rendered charts record's A11 and §5.4's
 * end state). The product draws its charts with its own engine; the vendored library, its converter idea and the
 * other library names of the old adapter's list are gone, and this guard keeps them gone.
 *
 * Every tracked text file (binary files skipped; the build's output is not tracked) is read line by line for one
 * pattern set: (a) the word `plotly` in any case, anywhere in a line; (b) the identifiers of the library idea, exact
 * case ([IDENTIFIERS]); (c) the other library names, any case, as WHOLE words only ([LIBRARY_NAMES]) — so
 * `lifecycleCharts(` is not a hit. The bare word "converter" is never searched: the product converts other things.
 *
 * Every hit must match one [ChartLibraryRemnantAllowlist] entry, and every entry must match at least one hit. A
 * Change Log line is one under a product doc's `## Appendix …: Change Log` heading and above the next heading of
 * level one or two, so a mention above that heading in the same doc is red. The sweep is held to a floor of files
 * and lines scanned, so an extraction that silently read nothing fails loudly.
 */
class ChartLibraryRemnantGuardTest {
    private val files by lazy { trackedTextFiles() }

    @Test
    fun `no chart library remnant is left outside the allowlist`() {
        val report = scan(files, ChartLibraryRemnantAllowlist.ENTRIES)
        println(report.render())
        withClue("non-vacuity: tracked text files scanned") { report.files shouldBeGreaterThanOrEqual FILE_FLOOR }
        withClue("non-vacuity: lines scanned") { report.lines shouldBeGreaterThanOrEqual LINE_FLOOR }
        withClue("allowlist entries that match no hit (remove them):\n${report.stale.joinToString("\n")}") {
            report.stale.shouldBeEmpty()
        }
        withClue("chart-library remnants off the allowlist (remove each where it stands):\n${report.offenders.joinToString("\n")}") {
            report.offenders.shouldBeEmpty()
        }
    }

    @Test
    fun `control - the pattern set`() {
        patternsIn("ui.registerConverter(\"bar\", convert)") shouldContainExactly listOf("registerConverter")
        patternsIn("<script data-dp-plotly-bundle=\"2d\">") shouldContainExactly listOf("plotly", "data-dp-plotly-bundle")
        patternsIn("A PLOTLY figure") shouldContainExactly listOf("plotly")
        patternsIn("Drawn with ECharts or Chart.js") shouldContainExactly listOf("echarts")
        patternsIn("val vegaliteSpec = 1; recharts") shouldContainExactly listOf("recharts")
        patternsIn("lifecycleCharts(\"a\" to 1)").shouldBeEmpty()
        patternsIn("the CSV converter writes a row").shouldBeEmpty()
        patternsIn("Renderer.Bundle is not the identifier").shouldBeEmpty()
    }

    @Test
    fun `control - a Change Log line is found by its heading, not by its file`() {
        val doc =
            listOf(
                "# A doc",
                "Plotly above the heading",
                "## Appendix B: Change Log",
                "| row | Plotly in a row |",
                "### a sub-heading inside the appendix",
                "| row | echarts in a row |",
                "## Appendix C: Glossary",
                "Plotly after the appendix",
            ).joinToString("\n")
        val report = scan(listOf(TrackedFile("docs/example.md", doc)), listOf(CHANGE_LOG_ONLY))
        report.offenders shouldContainExactly
            listOf("docs/example.md:2 [plotly] Plotly above the heading", "docs/example.md:8 [plotly] Plotly after the appendix")
        val notADoc = scan(listOf(TrackedFile("notes/example.md", doc)), listOf(CHANGE_LOG_ONLY))
        withClue("only a product doc under docs/ has a Change Log appendix") { notADoc.offenders.size shouldBe 4 }
    }

    @Test
    fun `control - a planted call is an offender and an entry that matches nothing is stale`() {
        val runtime = TrackedFile("modules/web/src/main/resources/static/js/example.js", "const a = 1;\nregisterConverter(\"bar\", f);\n")
        val unused = WholeFile("docs/never.md", Kind.HISTORICAL_SPEC, "control")
        val report = scan(listOf(runtime), listOf(unused))
        report.offenders shouldContainExactly
            listOf("modules/web/src/main/resources/static/js/example.js:2 [registerConverter] registerConverter(\"bar\", f);")
        report.stale shouldContainExactly listOf("kind 4 $unused")
    }

    internal data class TrackedFile(
        val path: String,
        val text: String,
    )

    internal data class Report(
        val files: Int,
        val lines: Int,
        val offenders: List<String>,
        val stale: List<String>,
        val hitsByKind: Map<Kind, Int>,
    ) {
        fun render(): String =
            "ChartLibraryRemnantGuardTest: files=$files lines=$lines offenders=${offenders.size} stale=${stale.size} " +
                Kind.entries.joinToString(" ") { "kind${it.number}=${hitsByKind[it] ?: 0}" }
    }

    /** The index's paths, NUL-separated; files holding a NUL byte are binary and skipped; a git failure fails the suite. */
    private fun trackedTextFiles(): List<TrackedFile> {
        var root = File(".").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) {
            root = root.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val listing = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ls-files exited $exit in $root: $listing" }
        return listing
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map { File(root, it) to it }
            .filter { (file, _) -> file.isFile }
            .mapNotNull { (file, path) -> file.readText().takeUnless { '\u0000' in it }?.let { TrackedFile(path, it) } }
    }

    internal companion object {
        /** 4,493 tracked text files measured at the lane's base (2026-11-15); the floor leaves room for deletions. */
        const val FILE_FLOOR = 4_000

        /** 1,010,063 lines measured at the lane's base (2026-11-15). */
        const val LINE_FLOOR = 900_000

        /** (b) The identifiers of the library idea, matched with their exact case anywhere in a line. */
        val IDENTIFIERS: List<String> =
            listOf(
                "registerConverter",
                "converter_missing",
                "library_undeclared",
                "charts.library",
                "renderer.bundle",
                "data-dp-plotly-bundle",
                "two_bundles",
                "bundle_insufficient",
            )

        /** (c) The other library names of the old adapter's list, matched in any case as whole words only. */
        val LIBRARY_NAMES: List<String> = listOf("echarts", "chartjs", "apexcharts", "vegalite", "highcharts", "amcharts5", "recharts")

        private const val PLOTLY = "plotly"
        private val WORD_RULES = LIBRARY_NAMES.associateWith { Regex("(?<![A-Za-z0-9_])$it(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE) }
        private val CHANGE_LOG_HEADING = Regex("""^## Appendix [^:]*: Change Log\s*$""")
        private val SECTION_HEADING = Regex("^#{1,2} ")
        private val CHANGE_LOG_ONLY = ChartLibraryRemnantAllowlist.ChangeLogAppendix(Kind.CHANGE_LOG, "control")

        /** The patterns [line] holds, in the order (a), (b), (c). */
        fun patternsIn(line: String): List<String> =
            buildList {
                if (line.contains(PLOTLY, ignoreCase = true)) add(PLOTLY)
                IDENTIFIERS.filterTo(this) { it in line }
                WORD_RULES.forEach { (name, rule) -> if (rule.containsMatchIn(line)) add(name) }
            }

        /** One flag per line of a product doc: true inside its Change Log appendix. */
        fun changeLogLines(
            path: String,
            lines: List<String>,
        ): BooleanArray {
            val flags = BooleanArray(lines.size)
            if (!path.startsWith("docs/") || !path.endsWith(".md")) return flags
            var inside = false
            lines.forEachIndexed { index, line ->
                inside =
                    when {
                        CHANGE_LOG_HEADING.matches(line) -> true
                        SECTION_HEADING.containsMatchIn(line) -> false
                        else -> inside.also { flags[index] = it }
                    }
            }
            return flags
        }

        fun scan(
            files: List<TrackedFile>,
            entries: List<Entry>,
        ): Report {
            var lineCount = 0
            val offenders = mutableListOf<String>()
            val matched = mutableSetOf<Entry>()
            val hitsByKind = mutableMapOf<Kind, Int>()
            files.forEach { file ->
                val lines = file.text.lines()
                lineCount += lines.size
                val changeLog = changeLogLines(file.path, lines)
                lines.forEachIndexed { index, line ->
                    val found = patternsIn(line)
                    if (found.isEmpty()) return@forEachIndexed
                    val entry = entries.firstOrNull { it.admits(file.path, line, changeLog[index]) }
                    if (entry == null) {
                        offenders += "${file.path}:${index + 1} [${found.joinToString(",")}] ${line.trim().take(MAX_QUOTE)}"
                    } else {
                        matched += entry
                        hitsByKind.merge(entry.kind, 1, Int::plus)
                    }
                }
            }
            val stale = entries.filter { it !in matched }.map { "kind ${it.kind.number} $it" }
            return Report(files.size, lineCount, offenders, stale, hitsByKind)
        }

        private const val MAX_QUOTE = 160
    }
}
