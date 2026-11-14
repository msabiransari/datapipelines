package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.sql.DriverManager

/**
 * A shared-data reset must leave the database as a fresh migration leaves it for what the next context needs: V53's
 * singleton for the managed-lake startup check, and V61's built-in lake profiles, without which no managed table can
 * be created (its INSERT takes the default profile by a sub-select).
 */
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2eCleanSeedRestorationTest : EntryAssuranceE2eBase() {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a seed reset restores the lake control singleton in normal mode`() {
        E2eClean.beforeSeeding()

        jdbc.queryForList("SELECT id::text || ':' || mode FROM lake_control", String::class.java) shouldBe listOf("1:normal")
    }

    @Test
    fun `a pre-context reset restores the lake control singleton before a later startup`() {
        E2eClean.beforeContext()

        jdbc.queryForList("SELECT id::text || ':' || mode FROM lake_control", String::class.java) shouldBe listOf("1:normal")
    }

    @Test
    fun `a seed reset restores the built-in lake profiles exactly as a fresh migration writes them`() {
        val fresh = SharedE2e.scratchDatabase("e2eclean_profiles")
        DriverManager.getConnection(fresh.jdbcUrl, fresh.username, fresh.password).use { connection ->
            connection.createStatement().use { statement -> migrationFiles().forEach { statement.execute(it.readText()) } }
        }
        val migrated =
            DriverManager.getConnection(fresh.jdbcUrl, fresh.username, fresh.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(PROFILE_ROWS).use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                }
            }
        migrated shouldHaveSize 2

        E2eClean.beforeSeeding()

        jdbc.queryForList(PROFILE_ROWS, String::class.java) shouldBe migrated
    }

    private companion object {
        /** Every column a fresh migration decides, in one comparable line per profile, byte-ordered. */
        const val PROFILE_ROWS =
            "SELECT name || '|' || is_default || '|' || seed_pending || '|' || (created_by IS NULL) || '|' || " +
                "(updated_by IS NULL) || '|' || values_json::text FROM lake_profiles ORDER BY name COLLATE \"C\""
        const val MIGRATION_DIR = "modules/app/src/main/resources/db/migration"
        val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

        /** Every migration in Flyway's version order, found by walking up to the repository root. */
        fun migrationFiles(): List<File> {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, MIGRATION_DIR).isDirectory) dir = dir.parentFile
            val migrations = File(requireNotNull(dir) { "$MIGRATION_DIR not found walking up" }, MIGRATION_DIR)
            return migrations
                .listFiles { f: File -> f.isFile }
                .orEmpty()
                .mapNotNull { f -> VERSION_PREFIX.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f } }
                .sortedBy { it.first }
                .map { it.second }
                .also { require(it.isNotEmpty()) { "no migrations under $migrations" } }
        }
    }
}
