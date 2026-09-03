package co.datapipelines

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * ONE Postgres container for this module's whole test JVM (round 060) — the singleton
 * pattern `dag`'s `RedisSupport` established. First touch starts the container and resets
 * the database to EMPTY; every context-booting suite in this module runs against it
 * instead of declaring its own `@Container`, so a module gate pays one cold start rather
 * than one per suite.
 *
 * ## Migrations: the first context's Flyway, exactly as production applies them
 *
 * Unlike the domain modules' shared containers (which apply the migrations over plain
 * JDBC because §3.1 rule 2 confines the Flyway dependency to `app`), this module HAS
 * Flyway — so this object deliberately migrates NOTHING. The first application context
 * to boot migrates the empty database through Spring Boot's Flyway autoconfiguration,
 * which is the exact path production takes; every later context finds the schema history
 * complete and no-ops. Nothing here re-implements or pre-empts that.
 *
 * ## The cleaning rule that makes sharing safe
 *
 * The suites in this module assert on behaviour at boot (health, log lines, refused
 * startup), never on row counts, so cross-suite data cannot satisfy or break an
 * assertion; the reset-on-first-touch keeps a reused container from accumulating history.
 *
 * ## Reuse (DEVELOPMENT.md, "Reusing test containers across runs")
 *
 * `withReuse(true)` is declared so a developer who opts in locally via
 * `~/.testcontainers.properties` keeps the container across Gradle runs; without the
 * opt-in it is a no-op and Ryuk reaps the container at JVM exit as always. The database
 * is dropped and recreated at first touch either way, so a reused container behaves
 * exactly like a fresh one. A container wedged by a killed run is removed with `docker rm`.
 */
internal object SharedPostgres {
    private const val IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one container. */
    private const val DATABASE = "datapipelines_app"

    private const val USER = "dp"
    private const val PASSWORD = "dp"

    /** The shared container, started and reset to an empty database on first touch. */
    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withReuse(true)
            .also(::boot)
    }

    private fun boot(container: PostgreSQLContainer<*>) {
        container.start()
        // Drop every database this container may still hold (its own first-boot default
        // on a fresh container; yesterday's schema on a reused one) so the first context
        // below migrates a provably empty server via Flyway.
        withAdminConnection(container) { statement ->
            val names =
                statement
                    .executeQuery(
                        "SELECT datname FROM pg_database WHERE datistemplate = false AND datname <> 'postgres'",
                    ).use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
            names.forEach { name ->
                statement.execute("""DROP DATABASE IF EXISTS "$name" WITH (FORCE)""")
            }
            statement.execute("""CREATE DATABASE "$DATABASE" OWNER "$USER"""")
        }
    }

    /** Opens the maintenance database (`postgres`) on [container] as its superuser. */
    private inline fun withAdminConnection(
        container: PostgreSQLContainer<*>,
        block: (java.sql.Statement) -> Unit,
    ) {
        val adminUrl = container.jdbcUrl.substringBeforeLast('/').substringBefore('?') + "/postgres"
        DriverManager.getConnection(adminUrl, container.username, container.password).use { connection ->
            connection.createStatement().use(block)
        }
    }
}
