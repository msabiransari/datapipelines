package co.datapipelines.datasources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [ShippedMigrations] — the ONE list of the shipped Flyway migrations both datasource
 * integration suites apply. Its two properties are pinned independently of its own locator:
 * the output equals the actual directory contents (derived through a KNOWN file, not the
 * helper's directory walk), and the order is NUMERIC (V10 after V2 — lexicographic order
 * would silently apply V10 between V1 and V2 the day it exists). Any `.sql` file outside the
 * accepted `V<int>__…\.sql` grammar FAILS LOUD, naming the file (R5 F4) — the helper exists
 * to kill stale-schema drift, and a silent exclusion would relocate that drift inside the
 * guard.
 */
class ShippedMigrationsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the helper lists exactly the migration directory's contents in version order`() {
        // Independent derivation: locate the directory as the parent of a KNOWN migration
        // file, list its SQL files, sort by parsed V-number — the helper must agree exactly.
        // A file outside the grammar fails HERE too, with the file named — never a bare `!!`
        // NPE crash instead of a report.
        val dir =
            TestFiles
                .repoFile("modules/app/src/main/resources/db/migration/V1__initial_schema.sql")
                .parentFile
        val expected =
            checkNotNull(dir.listFiles { f: File -> f.isFile && f.name.endsWith(".sql") })
                .map { it.name }
                .sortedBy { name ->
                    val match =
                        requireNotNull(Regex("""^V(\d+)__""").find(name)) {
                            "'$name' is not a versioned V<version>__ migration — the drift derivation cannot sort it; " +
                                "either rename it to the accepted grammar or widen ShippedMigrations deliberately"
                        }
                    requireNotNull(match.groupValues[1].toIntOrNull()) {
                        "'$name' carries a version number that does not fit an Int — widen ShippedMigrations deliberately"
                    }
                }

        ShippedMigrations.paths().map { it.substringAfterLast('/') } shouldBe expected
    }

    @Test
    fun `migration order is numeric - V10 sorts after V2, not between V1 and V2`() {
        tempDir.resolve("V2__second.sql").writeText("-- 2")
        tempDir.resolve("V10__tenth.sql").writeText("-- 10")
        tempDir.resolve("V1__first.sql").writeText("-- 1")
        tempDir.resolve("notes.txt").writeText("not a migration")

        ShippedMigrations.migrations(tempDir).map { it.second.name } shouldBe
            listOf("V1__first.sql", "V2__second.sql", "V10__tenth.sql")
    }

    @Test
    fun `a repeatable migration FAILS LOUD naming the file - it is never silently excluded`() {
        // R5 F4: `R__*.sql` is Flyway-legal but outside the accepted grammar. The helper's
        // old mapNotNull silently dropped it, so production Flyway would apply what the
        // integration suites silently omit — the exact stale-schema drift the helper exists
        // to kill, relocated inside the guard.
        tempDir.resolve("V1__first.sql").writeText("-- 1")
        val repeatable = tempDir.resolve("R__refresh_views.sql")
        repeatable.writeText("-- repeatable")

        val thrown = shouldThrow<IllegalArgumentException> { ShippedMigrations.migrations(tempDir) }

        thrown.message shouldContain "R__refresh_views.sql"
    }

    @Test
    fun `a sub-versioned migration FAILS LOUD naming the file`() {
        tempDir.resolve("V1__first.sql").writeText("-- 1")
        val subversioned = tempDir.resolve("V2_1__split.sql")
        subversioned.writeText("-- sub")

        val thrown = shouldThrow<IllegalArgumentException> { ShippedMigrations.migrations(tempDir) }

        thrown.message shouldContain "V2_1__split.sql"
    }

    @Test
    fun `an overflowing version FAILS LOUD naming the file`() {
        tempDir.resolve("V1__first.sql").writeText("-- 1")
        val overflowing = tempDir.resolve("V99999999999__overflow.sql")
        overflowing.writeText("-- too big for Int")

        val thrown = shouldThrow<IllegalArgumentException> { ShippedMigrations.migrations(tempDir) }

        thrown.message shouldContain "V99999999999__overflow.sql"
    }

    @Test
    fun `non-SQL files in the directory stay ignored`() {
        tempDir.resolve("V1__first.sql").writeText("-- 1")
        tempDir.resolve("notes.txt").writeText("not a migration")
        tempDir.resolve("README.md").writeText("readme")

        ShippedMigrations.migrations(tempDir).map { it.second.name } shouldBe listOf("V1__first.sql")
    }
}
