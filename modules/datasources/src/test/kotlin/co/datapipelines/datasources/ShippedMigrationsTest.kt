package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [ShippedMigrations] — the ONE list of the shipped Flyway migrations both datasource
 * integration suites apply. Its two properties are pinned independently of its own locator:
 * the output equals the actual directory contents (derived through a KNOWN file, not the
 * helper's directory walk), and the order is NUMERIC (V10 after V2 — lexicographic order
 * would silently apply V10 between V1 and V2 the day it exists).
 */
class ShippedMigrationsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `the helper lists exactly the migration directory's contents in version order`() {
        // Independent derivation: locate the directory as the parent of a KNOWN migration
        // file, list its SQL files, sort by parsed V-number — the helper must agree exactly.
        val dir =
            TestFiles
                .repoFile("modules/app/src/main/resources/db/migration/V1__initial_schema.sql")
                .parentFile
        val expected =
            checkNotNull(dir.listFiles { f: File -> f.isFile && f.name.endsWith(".sql") })
                .map { it.name }
                .sortedBy { name -> Regex("""^V(\d+)__""").find(name)!!.groupValues[1].toInt() }

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
}
