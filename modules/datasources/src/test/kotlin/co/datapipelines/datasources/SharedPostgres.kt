package co.datapipelines.datasources

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * ONE Postgres container for this module's whole test JVM (round 060) — the singleton
 * pattern `dag`'s `RedisSupport` established. First touch starts the container, resets the
 * database to empty, and applies the shipped migrations ONCE; every integration suite in
 * this module runs against it instead of declaring its own `@Container`, so a module gate
 * pays one cold start rather than one per suite.
 *
 * ## Migrations
 *
 * The shipped migrations are applied at first touch, in version order through
 * [ShippedMigrations] — the same derived-list-over-plain-JDBC mechanism every suite used
 * to apply per class (module-structure §3.1 rule 2: no domain module takes the Flyway
 * dependency, even as a test dependency; `app`'s own suite proves Flyway applies them).
 * Centralising it here retires the per-suite application loops: a suite now receives a
 * migrated database and only ever cleans the tables it touches.
 *
 * ## The cleaning rule that makes sharing safe
 *
 * Each spec cleans the tables it touches (`TRUNCATE ... CASCADE` plus re-seed) instead of
 * relying on a fresh container — the discipline this module's suites already follow.
 * This module has no partial-migration suites; [scratchDatabase] exists for the same
 * discipline the other modules follow.
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
    private const val DATABASE = "datapipelines_datasources"

    private const val USER = "dp"
    private const val PASSWORD = "dp"

    /** Small on purpose — see [pooledDataSource]. */
    private const val POOL_SIZE = 4

    /** The shared container, started and migrated on first touch. */
    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withReuse(true)
            .also(::boot)
    }

    /**
     * An unpooled [DriverManagerDataSource] on the shared container — a new physical
     * connection per `getConnection()`, exactly the semantics the suites had when each
     * declared its own container.
     */
    fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    /**
     * A small **pooled** [DataSource] on the shared container: built on first touch (which
     * starts the container), reused by every suite that asks for it, closed at JVM exit —
     * i.e. it lives exactly as long as the container does.
     *
     * ## Pooled and unpooled are NOT interchangeable — do not "simplify" one of them away
     *
     * [dataSource] hands out a **new physical connection per `getConnection()`**. That is a
     * cost — a fixture-heavy suite pays a TCP connect plus a Postgres authentication
     * handshake for every single statement — but it is also a **semantic**: a suite whose
     * assertion is about two INDEPENDENT SESSIONS is only meaningful on separate physical
     * connections, because a pool can hand two callers the same connection and serialise
     * statements that were meant to race. The concurrency tests in
     * `PipelineRepositoryIntegrationTest` and the two-session suites in `modules/auth`
     * (`LocalAuthServiceTest`, `AuthRepositoriesIntegrationTest`, `LocalAdminSeederTest`,
     * `BootstrapActorProvisioningIntegrationTest`) therefore keep [dataSource].
     *
     * Everything else — ordinary fixture writes and reads — takes [pooledDataSource] and pays
     * the handshake once for the whole JVM instead of once per statement (round 062; round
     * 060 measured the same cure at 19.2 s → 1.16 s on a 602-insert fixture).
     *
     * [POOL_SIZE] is deliberately tiny: the suites on this pool run their own tests
     * sequentially, so the pool exists to RETAIN a connection, not to widen concurrency.
     */
    fun pooledDataSource(): DataSource = pool

    /** Backing pool for [pooledDataSource]; touching it starts the container. */
    private val pool: HikariDataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = postgres.driverClassName
                maximumPoolSize = POOL_SIZE
                poolName = "shared-postgres-datasources"
            },
        ).also { created -> Runtime.getRuntime().addShutdownHook(Thread(created::close)) }
    }

    /**
     * A fresh EMPTY database on the shared container, for a suite that builds a partial
     * schema itself (the pre-V7 name-gate and pre-V8 typed-template suites). [name]
     * namespaces it; the database starts empty — the migration sequence is the caller's
     * subject under test.
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
        ShippedMigrations.paths().forEach { path -> jdbc.execute(TestFiles.repoFile(path).readText()) }
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
