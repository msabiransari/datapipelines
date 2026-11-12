package co.datapipelines.executor

import org.testcontainers.containers.MySQLContainer
import java.sql.DriverManager
import java.time.Duration

/**
 * ONE MySQL container for this module's whole test JVM — the dag copy of `datasources`'
 * `SharedMysql` (#463), following the per-module copy precedent [SharedPostgres] set. First touch
 * starts the container and resets it to a provably empty server holding only this module's
 * database. MySQL's first boot initialises its data directory, the slowest database cold start in
 * this repo, so the startup ceiling below is the same five minutes.
 *
 * ## Module-unique database
 *
 * `my_dag`, not `datasources`' `my_app`: with the local reuse opt-in, two modules' test JVMs must
 * never land on one container (the same rule [SharedPostgres] states), and the database name is
 * part of the container's configuration. The underscore is kept from the original.
 *
 * ## The cleaning rule that makes sharing safe
 *
 * Each suite `DROP`s the tables and routines it creates before creating them, and no suite asserts
 * an exact-set listing of the schema. The suites run sequentially within a JVM (the JUnit Platform
 * default), so the only state to defend against is leftover, never concurrent.
 *
 * ## First touch
 *
 * The container's superuser (`root`, whose password Testcontainers sets to the same value as the
 * user's) drops every non-system database the server holds and recreates this module's one. The
 * `test` user's grant on it lives in `mysql.db`, independent of the database's existence, so it
 * survives the drop. A reused container therefore behaves exactly like a fresh one.
 *
 * ## Reuse (DEVELOPMENT.md §9.2)
 *
 * `withReuse(true)` is a no-op unless the local opt-in is set. A container wedged by a killed run
 * is removed with `docker rm`.
 */
internal object SharedMysql {
    /** The same stock image `datasources`' MySQL suites use. */
    private const val IMAGE = "mysql:8.4"

    /** Module-unique (see the class KDoc). */
    private const val DATABASE = "my_dag"

    /** MySQL's own catalogs; everything else on the server is ours to drop at first touch. */
    private val systemDatabases = setOf("mysql", "information_schema", "performance_schema", "sys")

    /**
     * Testcontainers' DEFAULT startup wait is 60 s, and MySQL's first boot (initialising the
     * data directory) does not fit in it on a box running several lanes' containers (083, in
     * `datasources`). A startup ceiling is not a performance assertion; the boot's duration
     * belongs to the machine.
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
