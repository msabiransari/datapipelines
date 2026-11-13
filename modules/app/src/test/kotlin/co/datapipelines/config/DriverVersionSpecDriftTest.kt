package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

/**
 * Every pinned-driver version literal in the tracked tree is current, provenance-dated or owned (#781, #826).
 *
 * Three engines are pinned. DuckDB JDBC is declared by the catalog's `duckdb-jdbc`; the BOM-managed H2 and
 * SQLite JDBC drivers have no catalog row, so the build-generated resolution ([co.datapipelines.datasources.DriverVersions])
 * is their authority — packaged beside the code, cross-checked here against the catalog for DuckDB. Each row
 * classifies its own literals: equal to its version (or, for DuckDB, its three-part core) is current; a provenance
 * note — a measurement word and a `YYYY-MM-DD` in the literal's window, or a dated Change Log row — stays true
 * forever as written; or a file an [AllowRow] owns, with its reason; anything else is that row's red. A literal
 * two rows claim with no engine named on its line is red for both (ambiguous — reword the line to name the
 * engine). At a bump, the row's red list is what must be edited, the printed provenance list is what must be
 * re-probed, and the per-row rehearsal proves the row would name exactly its current list (owner's rule: a guard
 * that can fail, an allowlist with intent).
 */
class DriverVersionSpecDriftTest {
    private val files by lazy { trackedFiles() }

    @Test
    fun `every pinned driver version literal outside its row's authority is current, provenance-dated or owned`() {
        val report = PinDrift.scan(ROWS, catalog(), files, ALLOW)
        println(report.render())
        withClue("non-vacuity: scanned files") { (report.scanned >= SCAN_FLOOR) shouldBe true }
        ROW_FLOOR.forEach { (alias, floor) ->
            withClue("non-vacuity: literals matched for row $alias") {
                (report.literalCountByRow.getValue(alias) >= floor) shouldBe true
            }
        }
        withClue("non-vacuity: provenance notes") { (report.provenance.size >= PROVENANCE_FLOOR) shouldBe true }
        withClue("allowlist rows that own nothing (remove them)") { report.staleAllow.shouldBeEmpty() }
        withClue("literals two rows claim with no engine named on the line (reword the line)") {
            report.ambiguous.shouldBeEmpty()
        }
        withClue("literals that are not their row's version, dated provenance or owned") { report.red.shouldBeEmpty() }
    }

    /** The hypothetical is derived from the pin (#936 D2), so no DuckDB bump edits this rehearsal. */
    @Test
    fun `the DuckDB bump rehearsal - a catalog at the next patch names the ARG, CORE and every unmarked statement`() {
        val today = PinDrift.scan(ROWS, catalog(), files, ALLOW)
        val next = nextPatch(catalog().getValue(DUCKDB.alias))
        val bumped = PinDrift.scan(ROWS, catalog() + (DUCKDB.alias to next), files, ALLOW)
        println("BUMP REHEARSAL (catalog duckdb-jdbc = $next): ${bumped.red.size} red\n" + bumped.red.joinToString("\n"))
        val red = bumped.red.filter { it.alias == DUCKDB.alias }.map { "${it.path}:${it.line}" }
        red shouldContain "Dockerfile:${lineOf("Dockerfile", "ARG DUCKDB_CORE_VERSION=")}"
        red shouldContain "$LICENCES:${lineOf(LICENCES, "private const val CORE = ")}"
        withClue("no provenance-marked line is red at a bump") {
            bumped.red
                .map { "${it.path}:${it.line}" }
                .intersect(today.provenance.map { "${it.path}:${it.line}" }.toSet())
                .shouldBeEmpty()
        }
        withClue("the other rows are unaffected by the DuckDB bump") {
            bumped.red
                .filter { it.alias != DUCKDB.alias }
                .map { "${it.alias} ${it.path}:${it.line}" }
                .sorted() shouldContainExactly
                today.red
                    .filter { it.alias != DUCKDB.alias }
                    .map { "${it.alias} ${it.path}:${it.line}" }
                    .sorted()
        }
        withClue("every unmarked literal today is red at the bump, and nothing else") {
            red.sorted() shouldContainExactly
                today.current
                    .filter { it.alias == DUCKDB.alias }
                    .map { "${it.path}:${it.line}" }
                    .sorted()
        }
    }

    @Test
    fun `the H2 and SQLite bump rehearsals name exactly their rows' current lists`() {
        val today = PinDrift.scan(ROWS, catalog(), files, ALLOW)
        val rehearsals = mapOf(H2.alias to "2.3.233", SQLITE_JDBC.alias to "3.49.2.0")
        rehearsals.forEach { (alias, hypothetical) ->
            val bumped = PinDrift.scan(ROWS, catalog() + (alias to hypothetical), files, ALLOW)
            println("BUMP REHEARSAL ($alias = $hypothetical): ${bumped.red.size} red\n" + bumped.red.joinToString("\n"))
            withClue("no provenance-marked line is red at the $alias bump") {
                bumped.red
                    .map { "${it.path}:${it.line}" }
                    .intersect(today.provenance.map { "${it.path}:${it.line}" }.toSet())
                    .shouldBeEmpty()
            }
            withClue("the other rows are unaffected by the $alias bump") {
                bumped.red
                    .filter { it.alias != alias }
                    .map { "${it.alias} ${it.path}:${it.line}" }
                    .sorted() shouldContainExactly
                    today.red
                        .filter { it.alias != alias }
                        .map { "${it.alias} ${it.path}:${it.line}" }
                        .sorted()
            }
            withClue("every unmarked $alias literal today is red at the bump, and nothing else") {
                bumped.red
                    .filter { it.alias == alias }
                    .map { "${it.path}:${it.line}" }
                    .sorted() shouldContainExactly
                    today.current
                        .filter { it.alias == alias }
                        .map { "${it.path}:${it.line}" }
                        .sorted()
            }
        }
    }

    private fun catalog(): Map<String, String> {
        val toml = repoFile("gradle/libs.versions.toml").readText()
        val duckdb =
            Regex("""(?m)^duckdb-jdbc = "([^"]+)"""").find(toml)?.groupValues[1]
                ?: error("no duckdb-jdbc in the catalog")
        // `app` declares only `web` (§4.2), so [DriverVersions] — the resource's reader class — is
        // not on this module's classpath. The generated file itself travels inside the datasources
        // jar (runtime, via web), so this test reads it by name; a rename there fails HERE, loudly,
        // with the path named. The Gradle test worker splits the classpath across loaders — the
        // class's own loader may not see the jar while the context loader does — so both are tried.
        val properties =
            Properties().apply {
                val stream =
                    javaClass.getResourceAsStream("/$DRIVER_VERSION_RESOURCE")
                        ?: Thread
                            .currentThread()
                            .contextClassLoader
                            ?.getResourceAsStream(DRIVER_VERSION_RESOURCE)
                checkNotNull(stream) {
                    "$DRIVER_VERSION_RESOURCE is missing from the test classpath — the datasources jar " +
                        "(runtime, via web) carries the generated driver versions; build it first"
                }
                stream.use(::load)
            }

        fun key(name: String): String = checkNotNull(properties.getProperty(name)) { "$DRIVER_VERSION_RESOURCE carries no '$name' key" }
        check(key("jdbc") == duckdb) {
            "the packaged DuckDB token (${key("jdbc")}) has drifted from the catalog's duckdb-jdbc ($duckdb)"
        }
        return mapOf(DUCKDB.alias to duckdb, H2.alias to key("h2"), SQLITE_JDBC.alias to key("sqlite_jdbc"))
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

    /** Internal so [PinDriftTest] runs the core's own cases on the REAL rows, never on copies of them. */
    internal companion object {
        const val LICENCES = "buildSrc/src/main/kotlin/DuckDbExtensionLicenses.kt"
        const val MAX_BYTES = 4_000_000L

        /**
         * The generated resolution record's classpath path — the same file [DuckDbVersion] and
         * datasources' [DriverVersions] read, written by `generateDuckDbVersion`. Its name is
         * owned by the datasources module; a rename must move this string, and this test is what
         * catches a forgotten move.
         */
        const val DRIVER_VERSION_RESOURCE = "co/datapipelines/datasources/duckdb-version.properties"

        /** Measured 2026-11-04 on the #826 tree: 3,488 text files; floors sit below, per row. */
        const val SCAN_FLOOR = 3_200
        const val PROVENANCE_FLOOR = 70

        /** A `duckdb-jdbc` version has four numeric components: the core's three and the driver's own patch. */
        private const val JDBC_COMPONENTS = 4

        /**
         * The DuckDB rehearsal's hypothetical (#936 D2): [version] with its third component raised by one and its
         * fourth set to 0 (2.7.4.1 → 2.7.5.0). Anything but four numeric components fails, naming the version.
         */
        fun nextPatch(version: String): String {
            val parts = version.split('.')
            check(parts.size == JDBC_COMPONENTS && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) {
                "duckdb-jdbc $version is not four numeric components"
            }
            return listOf(parts[0], parts[1], (parts[2].toInt() + 1).toString(), "0").joinToString(".")
        }

        private const val VER ="""(\d+\.\d+\.\d+(?:\.\d+)?)"""

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
                renderedBy = Regex("""facts\.duckdbVersion|DuckDbVersion\.CURRENT|\$\{duckdb_core\}|[$]duckdbCore\b"""),
            )

        val H2 =
            PinRow(
                alias = "h2",
                contexts =
                    listOf(
                        Regex("""(?i)\bh2\b[^\S\n]*(?:[\w-]+[^\S\n]+){0,2}\(?v?$VER"""),
                        Regex("""(?i)h2-$VER"""),
                        Regex("""com\.h2database:h2[^\S\n]*:v?$VER"""),
                    ),
                short = { it },
            )

        val SQLITE_JDBC =
            PinRow(
                alias = "sqlite-jdbc",
                contexts =
                    listOf(
                        Regex("""(?i)sqlite-jdbc[^\S\n]*v?$VER"""),
                        Regex("""(?i)sqlite-jdbc-$VER"""),
                        Regex("""org\.xerial:sqlite-jdbc:$VER"""),
                        Regex("""(?i)\bxerial\b[^\S\n]*(?:[\w-]+[^\S\n]+){0,2}\(?v?$VER"""),
                        Regex("""(?i)\bsqlite\b[^\S\n]*v?$VER"""),
                    ),
                short = { it },
            )

        val ROWS = listOf(DUCKDB, H2, SQLITE_JDBC)

        /** Measured literal counts per row on this tree, with a margin below (a row that matches nothing fails). */
        val ROW_FLOOR: Map<String, Int> =
            mapOf(
                DUCKDB.alias to 180,
                H2.alias to 80,
                SQLITE_JDBC.alias to 28,
            )

        /** An undated pinned-driver literal on a line an in-flight lane owns: dating the line un-owns it. */
        val UNDATED_PIN = Regex("""(?:2\.3\.232|3\.49\.1\.0)(?![^0-9]*\d{4}-\d\d-\d\d)""")

        private fun inFlight(
            lane: String,
            path: String,
        ): AllowRow =
            AllowRow(
                Regex("""^${Regex.escape(path)}$"""),
                "in flight $lane: the owning lane dates these measurement notes at its landing; then this row goes stale and is removed",
                line = UNDATED_PIN,
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
                    Regex("""/(?:DriverVersionSpecDriftTest|PinDrift|PinDriftTest|DuckDbVersionTest|DriverVersionsTest)\.kt$"""),
                    "version-parsing fixtures of the guard, its core and the token readers",
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
                    Regex("""^modules/typesystem/src/main/kotlin/co/datapipelines/typesystem/StagingEngine\.kt$"""),
                    "SPEC-REVIEW revision citation in the KDoc (the 2026-08 spec review's item number), not a driver version",
                    line = Regex("SPEC-REVIEW 2\\.1\\.8"),
                ),
                // In-flight lanes' files: #818b owns modules/datasources/** and tests/integration-tests/**,
                // #799 owns modules/dag/**; these measurement notes keep their undated literals until those
                // lanes land. Each row owns ONLY an undated literal, so dating the line (the landing duty)
                // makes the row stale and the drift test flags it for removal.
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/JdbcUrlGuard.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/SqlProbeExceptions.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/DialectAdapter.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/DialectAdapters.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/JdbcUrlForm.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/SqlProbe.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/TableStatsPlan.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/TableStatsPlans.kt"),
                inFlight("#818b", "modules/datasources/src/main/kotlin/co/datapipelines/datasources/pooling/H2InProcessPool.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/pooling/ConnectionPoolManagerTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/DatasourceFileRootsTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/DatasourceRegistryIntegrationTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/EmbeddedDialectBehaviorTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/JdbcUrlFormTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/SchemaIntrospectorRoutingTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/SchemaIntrospectorCapAndLeaseTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/SqlProbeH2Test.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/SqlProbePermissionDeniedTest.kt"),
                inFlight("#818b", "modules/datasources/src/test/kotlin/co/datapipelines/datasources/TableStatsEmbeddedTest.kt"),
                inFlight(
                    "#818b",
                    "tests/integration-tests/src/test/kotlin/co/datapipelines/integration/DatasourceInProcessRegistrationE2eTest.kt",
                ),
                inFlight("#818b", "docs/datasources.md"),
                inFlight("#799", "modules/dag/src/main/kotlin/co/datapipelines/executor/NodeRunner.kt"),
                inFlight("#799", "modules/dag/src/main/kotlin/co/datapipelines/executor/WritebackRunner.kt"),
                inFlight("#799", "modules/dag/src/test/kotlin/co/datapipelines/executor/Fixtures.kt"),
            )
    }
}
