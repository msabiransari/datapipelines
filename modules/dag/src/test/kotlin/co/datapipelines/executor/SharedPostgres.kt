package co.datapipelines.executor

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * ONE Postgres container for this module's whole test JVM (round 060) — the singleton
 * pattern this module's own [RedisSupport] established. First touch starts the container,
 * resets the database to empty, and applies the shipped migrations ONCE; every
 * `*IntegrationTest` in this module runs against it instead of declaring its own
 * `@Container`, so a module gate pays one cold start rather than one per suite.
 *
 * ## Migrations
 *
 * The shipped migrations are applied at first touch, in version order through
 * [RepoFiles.migrationPaths] — the same derived-list-over-plain-JDBC mechanism every
 * suite used to apply per class (module-structure §3.1 rule 2: no domain module takes the
 * Flyway dependency, even as a test dependency; `app`'s own suite proves Flyway applies
 * them). Centralising it here retires the per-suite application loops.
 *
 * ## The cleaning rule that makes sharing safe
 *
 * Each spec cleans the tables it touches (`TRUNCATE users CASCADE` plus re-seed, or
 * `RedisSupport.flush` for the keyspace) instead of relying on a fresh container — the
 * discipline this module's suites already follow. Suites that need an ad-hoc schema the
 * migrations do not create (the injection fixture's tables, the orders source) take a
 * [scratchDatabase] — a fresh empty database on the same container — so they never see
 * or disturb the shipped-schema one.
 *
 * ## Reuse (DEVELOPMENT.md, "Reusing test containers across runs")
 *
 * `withReuse(true)` is declared so a developer who opts in locally via
 * `~/.testcontainers.properties` keeps the container across Gradle runs; without the
 * opt-in it is a no-op and Ryuk reaps the container at JVM exit as always. The database
 * is dropped and recreated at first touch either way, so a reused container behaves
 * exactly like a fresh one: yesterday's rows cannot leak into today's run. A container
 * wedged by a killed run is removed with `docker rm`.
 */
internal object SharedPostgres {
    private const val IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one container. */
    private const val DATABASE = "datapipelines_dag"

    private const val USER = "dp"
    private const val PASSWORD = "dp"

    /** The shared container, started and migrated on first touch. */
    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withReuse(true)
            .also(::boot)
    }

    /** An unpooled [DriverManagerDataSource] on the shared container. */
    fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    /**
     * A fresh EMPTY database on the shared container, for a suite whose schema the shipped
     * migrations do not create (ad-hoc fixture tables, a pipeline's source database).
     * [name] namespaces it; the caller builds whatever it needs inside.
     */
    fun scratchDatabase(name: String): DatabaseRef {
        val database = "scratch_$name"
        withAdminConnection(postgres) { statement ->
            statement.execute("""DROP DATABASE IF EXISTS "$database" WITH (FORCE)""")
            statement.execute("""CREATE DATABASE "$database" OWNER "$USER"""")
        }
        return DatabaseRef(
            jdbcUrl = urlFor(postgres, database),
            username = USER,
            password = PASSWORD,
        )
    }

    /** The endpoint a suite connects to. */
    data class DatabaseRef(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

    private fun boot(container: PostgreSQLContainer<*>) {
        container.start()
        // Drop every database this container may still hold (its own first-boot default
        // on a fresh container; yesterday's schema and scratch databases on a reused one)
        // so the JVM below starts from a provably empty server, then create the module's
        // database and apply the shipped migrations once.
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
        }
        withAdminConnection(container) { statement ->
            statement.execute("""CREATE DATABASE "$DATABASE" OWNER "$USER"""")
        }
        val jdbc =
            JdbcTemplate(
                DriverManagerDataSource(container.jdbcUrl, container.username, container.password),
            )
        RepoFiles.migrationPaths().forEach { path -> jdbc.execute(RepoFiles.read(path)) }
    }

    /** Opens the maintenance database (`postgres`) on [container] as its superuser. */
    private inline fun withAdminConnection(
        container: PostgreSQLContainer<*>,
        block: (java.sql.Statement) -> Unit,
    ) {
        val adminUrl = urlFor(container, "postgres")
        DriverManager.getConnection(adminUrl, container.username, container.password).use { connection ->
            connection.createStatement().use(block)
        }
    }

    private fun urlFor(
        container: PostgreSQLContainer<*>,
        database: String,
    ): String = container.jdbcUrl.substringBeforeLast('/').substringBefore('?') + "/" + database
}
