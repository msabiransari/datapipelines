package co.datapipelines.browser

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * ONE Postgres and ONE Redis for this module's whole test JVM (round 060's convention —
 * `tests/integration-tests`'s `SharedE2e` is the exemplar). The browser suite's spec
 * classes reference this singleton through `@DynamicPropertySource` instead of declaring
 * their own `@Container` pairs, so the module gate pays one cold start per engine.
 *
 * Module-unique database name: two modules' test JVMs must never land on one container.
 *
 * Migrations: the first Spring context's Flyway, exactly as production applies them —
 * this module boots the real application and migrates nothing itself.
 *
 * `withReuse(true)` is a no-op without the per-machine opt-in (DEVELOPMENT.md §9.2);
 * the drop-and-recreate at first touch either way makes a reused pair semantically fresh.
 */
internal object SharedBrowserE2e {
    private const val PG_IMAGE = "postgres:16-alpine"

    /** Module-unique. */
    private const val DATABASE = "datapipelines_browser"

    private const val USER = "dp"
    private const val PASSWORD = "dp"

    private const val REDIS_IMAGE = "redis:7-alpine"
    private const val REDIS_PORT = 6379

    /** The shared Postgres, started and reset to an empty database on first touch. */
    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(PG_IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername(USER)
            .withPassword(PASSWORD)
            // Shared server, shared-sized connection budget (the SharedE2e finding).
            .withCommand("postgres", "-c", "max_connections=400")
            .withReuse(true)
            .also { container ->
                container.start()
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
    }

    /** The shared Redis (`noeviction` — the result store must never evict), FLUSHALLed at first touch. */
    val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(REDIS_IMAGE))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .withExposedPorts(REDIS_PORT)
            .withReuse(true)
            .also { container ->
                container.start()
                container
                    .execInContainer("redis-cli", "FLUSHALL")
                    .also { result -> check(result.exitCode == 0) { "redis-cli FLUSHALL failed: ${result.stderr}" } }
            }
    }

    val jdbcUrl: String get() = postgres.jdbcUrl

    val username: String get() = postgres.username

    val password: String get() = postgres.password

    val redisHost: String get() = redis.host

    val redisPort: Int get() = redis.getMappedPort(REDIS_PORT)

    /** The admin connection targets the `postgres` maintenance database — never the one being dropped. */
    private fun withAdminConnection(
        container: PostgreSQLContainer<*>,
        block: (java.sql.Statement) -> Unit,
    ) {
        val adminUrl = container.jdbcUrl.substringBeforeLast('/').substringBefore('?') + "/postgres"
        DriverManager.getConnection(adminUrl, container.username, container.password).use { connection ->
            connection.createStatement().use(block)
        }
    }
}
