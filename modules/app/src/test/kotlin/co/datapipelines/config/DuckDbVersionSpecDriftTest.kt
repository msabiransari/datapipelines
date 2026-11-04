package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every pinned-engine version literal in the tracked tree is current, provenance-dated or owned (#781).
 *
 * The version catalog is the one hand-edited version. Everything else that names it is one of: equal to
 * the catalog version (or, for DuckDB, its three-part core); a provenance note — a measurement word
 * (`verified`, `probed`, `measured`) AND a `YYYY-MM-DD` date in the literal's window, or a dated Change Log
 * row — which stays true forever as written; or a file an [AllowRow] owns, with its reason. Anything else
 * is red. At a catalog bump the red list is what must be edited, and the printed provenance list is what
 * must be re-probed (owner's rule: a guard that can fail, an allowlist with intent).
 *
 * Detection does not depend on knowing the old version: a row's context patterns capture every version
 * the tree attaches to the engine (`duckdb_jdbc 1.5.5.1`, `DuckDB v1.5.5`, the ARG, the tag URLs), and a
 * second pass finds every bare occurrence of a captured version (`measured on 1.5.5.1`). Limit: a bare
 * literal of a version that no context line names anywhere outside the allowlist is invisible.
 *
 * The window is the literal's paragraph — the run of lines with text once comment markers are stripped —
 * bounded to [WINDOW] lines above and below; a Markdown table row is its own window.
 */
class DuckDbVersionSpecDriftTest {
    private val files by lazy { trackedFiles() }

    @Test
    fun `every DuckDB version literal outside the catalog is current, provenance-dated or owned`() {
        val report = PinDrift.scan(listOf(DUCKDB), catalog(), files, ALLOW)
        println(report.render())
        withClue("non-vacuity: scanned files") { (report.scanned >= SCAN_FLOOR) shouldBe true }
        withClue("non-vacuity: matched literals") { (report.literals.size >= LITERAL_FLOOR) shouldBe true }
        withClue("non-vacuity: provenance notes") { (report.provenance.size >= PROVENANCE_FLOOR) shouldBe true }
        withClue("allowlist rows that own nothing (remove them)") { report.staleAllow.shouldBeEmpty() }
        withClue("literals that are not the catalog version, dated provenance or owned") { report.red.shouldBeEmpty() }
    }

    @Test
    fun `the bump rehearsal - a catalog at 1_5_6_0 names the ARG, CORE and every unmarked statement`() {
        val today = PinDrift.scan(listOf(DUCKDB), catalog(), files, ALLOW)
        val bumped = PinDrift.scan(listOf(DUCKDB), mapOf(DUCKDB.alias to "1.5.6.0"), files, ALLOW)
        println("BUMP REHEARSAL (catalog duckdb-jdbc = 1.5.6.0): ${bumped.red.size} red\n" + bumped.red.joinToString("\n"))
        val red = bumped.red.map { "${it.path}:${it.line}" }
        red shouldContain "Dockerfile:${lineOf("Dockerfile", "ARG DUCKDB_CORE_VERSION=")}"
        red shouldContain "$LICENCES:${lineOf(LICENCES, "private const val CORE = ")}"
        withClue("no provenance-marked line is red at a bump") {
            red.intersect(today.provenance.map { "${it.path}:${it.line}" }.toSet()).shouldBeEmpty()
        }
        withClue("every unmarked literal today is red at the bump, and nothing else") {
            red.sorted() shouldContainExactly today.current.map { "${it.path}:${it.line}" }.sorted()
        }
    }

    @Test
    fun `the core admits, captures and refuses by the documented rules`() {
        val fixture =
            listOf(
                TrackedFile("a.kt", "// duckdb_jdbc 1.4.0 is old\n\n// measured on 1.4.0, nothing dated\n"),
                TrackedFile("b.kt", "/**\n * verified 2026-01-02 against\n * the driver: on 1.4.0 it works\n */\n"),
                TrackedFile("c.kt", "// probed 2026-01-02\n//\n// a new paragraph on 1.4.0\n"),
                TrackedFile("d.md", "| 2026-01-02 | v1 | DuckDB 1.4.0 row |\n| x | y | DuckDB 1.4.0 no date, verified |\n"),
                TrackedFile("e.md", "verified 2026-01-02\n1\n2\n3\n4\nDuckDB 1.4.0 five lines below\n"),
                TrackedFile("f.kt", "val v = \"1.5.6.0\" // DuckDB 1.5.6 core\nx = 1.4.0.1\ny = 1.3.0\n"),
                TrackedFile("lock/gradle.lockfile", "duckdb_jdbc:1.4.0=runtimeClasspath\nduckdb_jdbc:1.3.0=runtimeClasspath\n"),
            )
        val allow = listOf(AllowRow(Regex("""\.lockfile$"""), "build-managed"), AllowRow(Regex("""^none/"""), "stale"))
        val report = PinDrift.scan(listOf(DUCKDB), mapOf(DUCKDB.alias to "1.5.6.0"), fixture, allow)
        report.red.map { "${it.path}:${it.line}" } shouldContainExactly listOf("a.kt:1", "a.kt:3", "c.kt:3", "d.md:2", "e.md:6")
        report.provenance.map { "${it.path}:${it.line}" } shouldContainExactly listOf("b.kt:3", "d.md:1")
        report.current.map { "${it.path}:${it.line}:${it.version}" } shouldContainExactly listOf("f.kt:1:1.5.6.0", "f.kt:1:1.5.6")
        withClue("1.4.0.1 is never captured, and 1.3.0 only on an allowlisted line, so the bare pass never seeks it") {
            report.versions shouldBe setOf("1.4.0", "1.5.6.0", "1.5.6")
        }
        report.allowed.mapValues { (_, owned) -> owned.map { "${it.path}:${it.line}:${it.version}" } } shouldBe
            mapOf("build-managed" to listOf("lock/gradle.lockfile:1:1.4.0", "lock/gradle.lockfile:2:1.3.0"))
        report.staleAllow shouldContainExactly listOf("stale")
    }

    private fun catalog(): Map<String, String> {
        val toml = repoFile("gradle/libs.versions.toml").readText()
        val version = Regex("""(?m)^${Regex.escape(DUCKDB.alias)} = "([^"]+)"""").find(toml) ?: error("no ${DUCKDB.alias} in the catalog")
        return mapOf(DUCKDB.alias to version.groupValues[1])
    }

    private fun lineOf(
        path: String,
        prefix: String,
    ): Int = repoFile(path).readLines().indexOfFirst { it.trim().startsWith(prefix) }.also { check(it >= 0) { "$prefix in $path" } } + 1

    /** The index's paths, NUL-separated; binary and huge files are skipped, a git failure fails the suite. */
    private fun trackedFiles(): List<TrackedFile> {
        val root = repoFile("settings.gradle.kts").parentFile
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val listing = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ls-files exited $exit in $root: $listing" }
        return listing
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map { File(root, it) to it }
            .filter { (file, _) -> file.isFile && file.length() < MAX_BYTES }
            .mapNotNull { (file, path) -> file.readText().takeUnless { '\u0000' in it }?.let { TrackedFile(path, it) } }
    }

    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val LICENCES = "buildSrc/src/main/kotlin/DuckDbExtensionLicenses.kt"
        const val MAX_BYTES = 4_000_000L

        /** Measured 2026-11-04 on the #781 tree (base bc1b00b9d): 3,487 text files, 227 literals, 35 provenance; floors sit below. */
        const val SCAN_FLOOR = 3_000
        const val LITERAL_FLOOR = 150
        const val PROVENANCE_FLOOR = 25

        private const val VER = """(\d+\.\d+\.\d+(?:\.\d+)?)"""

        val DUCKDB =
            PinRow(
                alias = "duckdb-jdbc",
                contexts =
                    listOf(
                        Regex("""(?i)duckdb[-_]?jdbc[^0-9a-z\n]{0,4}v?$VER"""),
                        Regex("""(?i)\bduckdb(?: core)?\s+v?$VER"""),
                        Regex("""DUCKDB_CORE_VERSION=v?$VER"""),
                        Regex("""duckdb/duckdb/(?:tree|blob|releases/download)/v$VER"""),
                        Regex("""(?i)duckdb_version["'\s:=]+v?$VER"""),
                        Regex("""extensions/v$VER/"""),
                        Regex("""(?<![\w.])v$VER/(?:linux_|osx_|<platform>)"""),
                        Regex("""^duckdb(?:/[\w.]+)?\t$VER\t"""),
                    ),
                short = { it.split('.').take(3).joinToString(".") },
                renderedBy = Regex("""facts\.duckdbVersion|DuckDbVersion\.CURRENT"""),
            )

        val ALLOW =
            listOf(
                AllowRow(Regex("""\.lockfile$"""), "build-managed: dependency locks, regenerated from a cold home at a bump"),
                AllowRow(Regex("""^gradle/verification-metadata\.xml$"""), "build-managed: dependency verification metadata"),
                AllowRow(Regex("""^gradle/libs\.versions\.toml$"""), "the declaration itself"),
                AllowRow(
                    Regex("""^deploy/duckdb-extension"""),
                    "checksums and closures: ThirdPartyNoticesTask's read checks them against the catalog's core at build time",
                ),
                AllowRow(Regex("""^buildSrc/src/test/"""), "licence-read fixtures; ThirdPartyNoticesTest binds them to the real closures"),
                AllowRow(
                    Regex("""/(?:DuckDbVersionSpecDriftTest|DuckDbVersionTest)\.kt$"""),
                    "version-parsing fixtures of the guard and the token",
                ),
                AllowRow(
                    Regex("""^scripts/sample-data[^/]*/sources\.lock$|^modules/web/src/main/resources/site/demo/manifest-[a-z]+\.json$"""),
                    "the sample-data builds' own DuckDB CLI pin with its sums, and the manifests recording what built the " +
                        "published bucket: they move with a data rebuild, not with the driver",
                ),
                AllowRow(
                    Regex("""^modules/mcp-server/src/test/resources/docs-golden/"""),
                    "generated mirror of the skill (docsExport); the skill's own lines are scanned",
                ),
                AllowRow(Regex("""^docs/superpowers/"""), "dated research and design snapshots: frozen history"),
                AllowRow(
                    Regex("""^docs/module-structure\.md$"""),
                    "a stale catalog excerpt; its correction is a recorded #781 follow-up",
                    line = Regex("duckdb-jdbc = \"1\\.0\\.0\""),
                ),
            )
    }
}

/** One pinned engine: its catalog alias, the patterns whose group 1 captures a version it names, its short form, its token readers. */
internal data class PinRow(
    val alias: String,
    val contexts: List<Regex>,
    val short: (String) -> String,
    val renderedBy: Regex,
)

/** A file (and, optionally, only its lines matching [line]) whose literals another owner keeps true, with the reason. */
internal data class AllowRow(
    val path: Regex,
    val reason: String,
    val line: Regex? = null,
) {
    fun owns(
        file: String,
        text: String,
    ): Boolean = path.containsMatchIn(file) && (line?.containsMatchIn(text) ?: true)
}

internal data class TrackedFile(
    val path: String,
    val text: String,
)

internal data class Literal(
    val path: String,
    val line: Int,
    val version: String,
    val text: String,
) {
    override fun toString(): String = "$path:$line: $version — ${text.trim().take(TEXT_CHARS)}"

    private companion object {
        const val TEXT_CHARS = 120
    }
}

internal data class PinReport(
    val scanned: Int,
    val versions: Set<String>,
    val literals: List<Literal>,
    val red: List<Literal>,
    val provenance: List<Literal>,
    val current: List<Literal>,
    val allowed: Map<String, List<Literal>>,
    val staleAllow: List<String>,
    val rendered: List<String>,
) {
    /** The re-probe list a bump starts from: dated notes, then the pages that render the token. */
    fun render(): String =
        buildString {
            append("Pin drift: $scanned files, ${literals.size} literals of $versions; ")
            append("${current.size} current, ${provenance.size} provenance, ${allowed.values.sumOf { it.size }} owned, ${red.size} red\n")
            append("RE-PROBE at a bump — provenance notes (${provenance.size}):\n")
            provenance.forEach { append("  $it\n") }
            append("RE-PROBE at a bump — statements rendered from the token (${rendered.size}):\n")
            rendered.forEach { append("  $it\n") }
            allowed.forEach { (reason, owned) -> append("OWNED ${owned.size}: $reason\n") }
            red.forEach { append("RED $it\n") }
        }
}

/** The guard's pure core: no file system, no catalog file — the test hands it both. */
internal object PinDrift {
    private const val WINDOW = 4
    private val WORD = Regex("""(?i)\b(?:verified|probed|measured)\b""")
    private val DATE = Regex("""\b20\d\d-\d\d-\d\d\b""")
    private val DATED_ROW = Regex("""^\s*\|\s*20\d\d-\d\d-\d\d\s*\|""")
    private val COMMENT = Regex("""^(?://+|#+|/\*+|\*+/|\*+|<!--|-->|--)""")

    fun scan(
        rows: List<PinRow>,
        catalog: Map<String, String>,
        files: List<TrackedFile>,
        allow: List<AllowRow>,
    ): PinReport {
        val lines = files.associate { it.path to it.text.split('\n') }
        val versions = mutableSetOf<String>()
        val literals = mutableListOf<Pair<Literal, AllowRow?>>()
        val rendered = mutableListOf<String>()
        rows.forEach { row ->
            val pinned = checkNotNull(catalog[row.alias]) { "no catalog version for ${row.alias}" }
            val admitted = setOf(pinned, row.short(pinned))
            val rowVersions =
                admitted +
                    lines.flatMap { (path, text) ->
                        text.filter { line -> allow.none { it.owns(path, line) } }.flatMap { captures(row, it) }
                    }
            versions += rowVersions
            val alternatives = rowVersions.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            val bare = Regex("""(?<![0-9.])v?($alternatives)(?![0-9]|\.[0-9])""")
            lines.forEach { (path, text) ->
                text.forEachIndexed { index, line ->
                    val owner = allow.firstOrNull { it.owns(path, line) }
                    val found = bare.findAll(line).map { it.groupValues[1] } + if (owner != null) captures(row, line) else emptySequence()
                    found.distinct().forEach { literals += Literal(path, index + 1, it, line) to owner }
                    if (path.contains("/src/main/") && row.renderedBy.containsMatchIn(line)) rendered += "$path:${index + 1}"
                }
            }
        }
        val free = literals.filter { it.second == null }.map { it.first }
        val provenance = free.filter { provenanced(lines.getValue(it.path), it.line - 1) }
        val admittedVersions = rows.flatMap { catalog.getValue(it.alias).let { pin -> listOf(pin, it.short(pin)) } }.toSet()
        val rest = free - provenance.toSet()
        return PinReport(
            scanned = files.size,
            versions = versions,
            literals = literals.map { it.first },
            red = rest.filterNot { it.version in admittedVersions },
            provenance = provenance,
            current = rest.filter { it.version in admittedVersions },
            allowed = literals.mapNotNull { (literal, owner) -> owner?.let { it.reason to literal } }.groupBy({ it.first }, { it.second }),
            staleAllow = allow.map { it.reason } - literals.mapNotNull { it.second?.reason }.toSet(),
            rendered = rendered.distinct(),
        )
    }

    private fun captures(
        row: PinRow,
        line: String,
    ): Sequence<String> = row.contexts.asSequence().flatMap { pattern -> pattern.findAll(line).map { it.groupValues[1] } }

    /** A dated Change Log row, or a measurement word and a date in the literal's bounded paragraph. */
    private fun provenanced(
        lines: List<String>,
        index: Int,
    ): Boolean {
        val line = lines[index]
        if (line.trimStart().startsWith("|")) return DATED_ROW.containsMatchIn(line) || (WORD in line && DATE in line)
        var low = index
        while (low > 0 && index - low < WINDOW && hasText(lines[low - 1])) low--
        var high = index
        while (high < lines.lastIndex && high - index < WINDOW && hasText(lines[high + 1])) high++
        val window = lines.subList(low, high + 1).joinToString("\n")
        return WORD in window && DATE in window
    }

    private fun hasText(line: String): Boolean = line.trim().replace(COMMENT, "").isNotBlank()

    private operator fun Regex.contains(text: String): Boolean = containsMatchIn(text)
}
