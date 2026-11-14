package co.datapipelines.config

import co.datapipelines.config.DriverVersionSpecDriftTest.Companion.DUCKDB
import co.datapipelines.config.DriverVersionSpecDriftTest.Companion.H2
import co.datapipelines.config.DriverVersionSpecDriftTest.Companion.SQLITE_JDBC
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * The guard core's own rules, on hand-built trees and the REAL rows of [DriverVersionSpecDriftTest] (#781, #826).
 *
 * Each case names the defect it exists to catch and was planted red before it was trusted (#826 correction round):
 * the stale-allow identity case against the reason-set subtraction, the collision case against the union
 * classifier, the duplicate case against a literal that loses its claim's alias, the KDoc-table case against a
 * table-row test that ignores comment markers.
 */
class PinDriftTest {
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
                TrackedFile("h.kt", "// h2 1.4.0 named, so the h2 row captures it too\n"),
                TrackedFile("i.kt", "// duckdb_jdbc 1.5.6.0 admitted as the pin\n"),
                TrackedFile("lock/gradle.lockfile", "duckdb_jdbc:1.4.0=runtimeClasspath\nduckdb_jdbc:1.3.0=runtimeClasspath\n"),
            )
        val stale = AllowRow(Regex("""^none/"""), "stale")
        val allow = listOf(AllowRow(Regex("""\.lockfile$"""), "build-managed"), stale)
        val catalog = mapOf(DUCKDB.alias to "1.5.6.0", H2.alias to "2.3.232")
        val report = PinDrift.scan(listOf(DUCKDB, H2), catalog, fixture, allow)
        // a.kt:1 and d.md:2 and e.md:6 name the engine (duckdb_jdbc / DuckDB) — resolved to the duckdb row,
        // red once. a.kt:3 and c.kt:3 name no engine and both rows captured 1.4.0 — ambiguous, red for both.
        report.red.map { "${it.path}:${it.line}" } shouldContainExactly
            listOf("a.kt:1", "a.kt:3", "a.kt:3", "c.kt:3", "c.kt:3", "d.md:2", "e.md:6", "h.kt:1").sorted()
        report.ambiguous shouldContainExactly
            listOf("a.kt:3", "c.kt:3").map {
                "$it: 1.4.0 — ambiguous: rows duckdb-jdbc, h2 all claim it and no context on the line names the engine — " +
                    "reword the line to name it"
            }
        report.provenance.map { "${it.path}:${it.line}" } shouldContainExactly listOf("b.kt:3", "b.kt:3", "d.md:1")
        report.current.map { "${it.path}:${it.line}:${it.version}" } shouldContainExactly
            listOf("f.kt:1:1.5.6.0", "f.kt:1:1.5.6", "i.kt:1:1.5.6.0")
        withClue("1.4.0.1 is never captured, and 1.3.0 only on an allowlisted line, so the bare pass never seeks it") {
            report.versions shouldBe setOf("1.4.0", "1.5.6.0", "1.5.6", "2.3.232")
        }
        withClue("each row classifies and counts its own literals") {
            report.literalCountByRow shouldBe mapOf(DUCKDB.alias to 12, H2.alias to 4)
        }
        report.allowed.mapValues { (_, owned) -> owned.map { "${it.path}:${it.line}:${it.version}" } } shouldBe
            mapOf("build-managed" to listOf("lock/gradle.lockfile:1:1.4.0", "lock/gradle.lockfile:2:1.3.0"))
        report.staleAllow shouldContainExactly listOf(stale)
        report.staleAllow.single() shouldBeSameInstanceAs stale
    }

    @Test
    fun `the DuckDB rehearsal's next patch raises the third component, zeroes the fourth and refuses other shapes`() {
        DriverVersionSpecDriftTest.nextPatch("2.7.4.1") shouldBe "2.7.5.0"
        DriverVersionSpecDriftTest.nextPatch("1.9.9.0") shouldBe "1.9.10.0"
        listOf("2.7.4", "2.7.4.1.0", "2.7.x.1", "2.7..1", "").forEach { malformed ->
            shouldThrow<IllegalStateException> { DriverVersionSpecDriftTest.nextPatch(malformed) }.message shouldBe
                "duckdb-jdbc $malformed is not four numeric components"
        }
    }

    @Test
    fun `an allow row that owns nothing is stale even while a row with the same reason owns a literal`() {
        val reason = "in flight #x: the owning lane dates these notes at its landing"
        val owning = AllowRow(Regex("""^owned\.kt$"""), reason, line = Regex("""2\.3\.232"""))
        val empty = AllowRow(Regex("""^dated\.kt$"""), reason, line = Regex("""2\.3\.232(?![^0-9]*\d{4}-\d\d-\d\d)"""))
        val files =
            listOf(
                TrackedFile("owned.kt", "// verified against h2 2.3.232, undated\n"),
                TrackedFile("dated.kt", "// verified against h2 2.3.232, 2026-01-02\n"),
            )
        val report = PinDrift.scan(listOf(H2), mapOf(H2.alias to "2.3.232"), files, listOf(owning, empty))
        withClue("the row whose only line was dated owns nothing; its sibling's shared reason must not hide it") {
            report.staleAllow shouldContainExactly listOf(empty)
            report.staleAllow.single() shouldBeSameInstanceAs empty
        }
        report.allowed.getValue(reason).map { "${it.path}:${it.line}" } shouldContainExactly listOf("owned.kt:1")
        report.render() shouldContain "STALE ^dated\\.kt$:2\\.3\\.232(?![^0-9]*\\d{4}-\\d\\d-\\d\\d) — $reason\n"
    }

    @Test
    fun `a stale DuckDB literal equal to H2's admitted pin is red for DuckDB, and H2's own literal stays current`() {
        val report = PinDrift.scan(listOf(DUCKDB, H2), COLLISION_CATALOG, COLLISION_FILES, emptyList())
        withClue("the DuckDB row's literal 2.3.232 is not ITS pin, whatever another row admits") {
            report.red.map { "${it.alias} ${it.path}:${it.line}:${it.version}" } shouldContainExactly
                listOf("duckdb-jdbc collision.kt:1:2.3.232")
        }
        withClue("the line naming H2 resolves to the H2 row and is current there") {
            report.current
                .filter { it.path == "h2.kt" }
                .map { "${it.alias} ${it.line}:${it.version}" } shouldContainExactly listOf("h2 1:2.3.232")
        }
        report.ambiguous.shouldBeEmpty()
    }

    @Test
    fun `one line naming two engines yields two literals, each classified by its own row`() {
        val report = PinDrift.scan(listOf(DUCKDB, H2), COLLISION_CATALOG, DUPLICATE_FILES, emptyList())
        withClue("both rows claim 2.3.232 on the line (DuckDB seeks it since collision.kt named it); H2's context wins") {
            report.literals
                .filter { it.path == "both.kt" }
                .map { "${it.alias} ${it.version}" }
                .sorted() shouldContainExactly listOf("duckdb-jdbc 1.5.5.1", "h2 2.3.232")
        }
        withClue("each literal is its own row's current pin, so none of the line is red") {
            report.red.filter { it.path == "both.kt" }.shouldBeEmpty()
            report.current
                .filter { it.path == "both.kt" }
                .map { it.alias }
                .sorted() shouldContainExactly listOf(DUCKDB.alias, H2.alias)
        }
    }

    @Test
    fun `a KDoc table row is judged by its own cells, never by the dated row beside it`() {
        val kdoc =
            TrackedFile(
                "RefusedKeys.kt",
                "/**\n" +
                    " * | Dialect | Jar inspected | Catalog class |\n" +
                    " * |---|---|---|\n" +
                    " * | DUCKDB | `duckdb_jdbc-1.5.5.1.jar` | `org.duckdb.DuckDBDriver` |\n" +
                    " * | SQLITE | `sqlite-jdbc-3.49.1.0.jar` (verified 2026-08-09) | `org.sqlite.SQLiteConfig.Pragma` |\n" +
                    " */\n",
            )
        val rows = listOf(DUCKDB, SQLITE_JDBC)
        val catalog = mapOf(DUCKDB.alias to "1.5.5.1", SQLITE_JDBC.alias to "3.49.1.0")
        val report = PinDrift.scan(rows, catalog, listOf(kdoc), emptyList())
        withClue("only the SQLite row carries a measurement word and a date in its own cells") {
            report.provenance.map { "${it.alias} ${it.line}" } shouldContainExactly listOf("sqlite-jdbc 5")
        }
        withClue("the undated DuckDB row is current today, not provenance borrowed from its neighbour") {
            report.current.map { "${it.alias} ${it.line}:${it.version}" } shouldContainExactly listOf("duckdb-jdbc 4:1.5.5.1")
        }
        val bumped = PinDrift.scan(rows, catalog + (DUCKDB.alias to "1.5.6.0"), listOf(kdoc), emptyList())
        withClue("so a DuckDB bump names the undated row") {
            bumped.red.map { "${it.alias} ${it.line}" } shouldContainExactly listOf("duckdb-jdbc 4")
        }
    }

    private companion object {
        /** The original brief's A.1 catalog: DuckDB's pin and H2's pin differ, and H2's is a string DuckDB could hold. */
        val COLLISION_CATALOG = mapOf("duckdb-jdbc" to "1.5.5.1", "h2" to "2.3.232")

        /** One engine per line: the collision is decided by each literal's own row, never by another row's pin. */
        val COLLISION_FILES =
            listOf(
                TrackedFile("collision.kt", "// duckdb_jdbc 2.3.232 is a stale DuckDB claim\n"),
                TrackedFile("h2.kt", "// h2 2.3.232 is the H2 driver's own pin\n"),
            )

        /** Plus one line naming both engines; collision.kt makes the DuckDB row seek 2.3.232 on that line too. */
        val DUPLICATE_FILES =
            COLLISION_FILES + TrackedFile("both.kt", "// duckdb_jdbc 1.5.5.1 ships beside h2 2.3.232 on one line\n")
    }
}
