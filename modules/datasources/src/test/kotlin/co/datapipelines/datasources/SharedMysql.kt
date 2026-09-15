package co.datapipelines.datasources

import org.testcontainers.containers.MySQLContainer
import java.sql.DriverManager
import java.time.Duration

/**
 * ONE MySQL container for this module's whole test JVM (round 141) — the singleton pattern
 * [SharedPostgres] follows. First touch starts the container and resets it to a provably
 * empty server holding only the module's database; the three MySQL suites run against it
 * instead of each booting a `mysql:8.4` of their own. MySQL's first boot initialises its data
 * directory, the slowest database cold start in this repo (it blew Testcontainers' 60 s
 * default on a loaded box — 083), so a module gate now pays it once rather than three times.
 *
 * ## The database name is part of the subject
 *
 * `my_app` on purpose: the underscore-named database is what catches an escaped catalog
 * argument — a literal must match the stored name exactly — the defect
 * `DialectConnectivityIntegrationTest`'s introspection case exists for. Every suite here
 * shares that database; none of them asserts on the database's NAME being unique to it.
 *
 * ## The cleaning rule that makes sharing safe
 *
 * Each suite `DROP TABLE IF EXISTS` the tables it creates before creating them (and the
 * table-stats suite after each test as well), and no suite asserts an exact-set listing of
 * the schema — each finds its own tables by name and tolerates its neighbours'. The suites
 * run sequentially within a JVM (the JUnit Platform default; nothing in the build enables
 * parallel execution), so the only state to defend against is leftover, never concurrent.
 * `sql_mode` is deliberately untouched — the identifier-quoting suite's subject is what a
 * STOCK server does, and it asserts that before relying on it.
 *
 * ## First touch
 *
 * The container's superuser (`root`, whose password Testcontainers sets to the same value as
 * the user's) drops every non-system database the server holds — only `my_app` on a fresh
 * container; yesterday's on a reused one — and recreates `my_app`. The `test` user's grant on
 * `my_app.*` lives in `mysql.db`, independent of the database's existence, so it survives the
 * drop. A reused container therefore behaves exactly like a fresh one.
 *
 * ## Reuse (DEVELOPMENT.md §9.2)
 *
 * `withReuse(true)` is declared like the other shared containers: a no-op unless the local
 * opt-in is set. A container wedged by a killed run is removed with `docker rm`.
 */
internal object SharedMysql {
    /** The same stock image every MySQL suite pinned individually. */
    private const val IMAGE = "mysql:8.4"

    /** Module-unique, and underscore-named on purpose (see the class KDoc). */
    private const val DATABASE = "my_app"

    /** MySQL's own catalogs; everything else on the server is ours to drop at first touch. */
    private val systemDatabases = setOf("mysql", "information_schema", "performance_schema", "sys")

    /**
     * Testcontainers' DEFAULT startup wait is 60 s, and MySQL's first boot (initialising the
     * data directory) does not fit in it on a box running several lanes' containers — the
     * dialect suite failed a full gate with `Could not create new connection` while the server
     * was still starting (083, found by the round's own gate). A startup ceiling is not a
     * performance assertion; the boot's duration belongs to the machine.
     */
    private val startupCeiling: Duration = Duration.ofMinutes(5)

    /** The shared container, started and reset on first touch. */
    val mysql: MySQLContainer<*> by lazy {
        MySQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withStartupTimeout(startupCeiling)
            .withReuse(true)
            .also(::boot)
    }

    private fun boot(container: MySQLContainer<*>) {
        container.start()
        val rootUrl = container.jdbcUrl.substringBeforeLast('/') + "/mysql"
        DriverManager.getConnection(rootUrl, "root", container.password).use { connection ->
            connection.createStatement().use { statement ->
                val names =
                    statement.executeQuery("SELECT schema_name FROM information_schema.schemata").use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                names.filterNot { it.lowercase() in systemDatabases }.forEach { name ->
                    statement.execute("DROP DATABASE IF EXISTS `$name`")
                }
                statement.execute("CREATE DATABASE `$DATABASE`")
            }
        }
    }
}
