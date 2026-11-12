package co.datapipelines.browser

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

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
 *
 * **The connection budget (#933).** Every class of the module shares this ONE server, so its
 * [MAX_CONNECTIONS] is a budget the whole run spends. Measured 2026-11-12 on 151 classes, one fork,
 * alphabetical order (`spec/dependency-graph` `bd9eded1a`): the server reached 400 of 400 at
 * `PipelineWorkspacePromoterAdmittedBrowserTest` and refused 94 cases. At the refusal the owners
 * were 8 cached application contexts' pools x 10 (Hikari's `minimumIdle` defaults to the pool
 * size) = 80, plus 156 pools of the `*-src-*` datasources the cases register against this server
 * x 2 (the product's `minimumIdle`) = 312: 392 of 400, the other 8 connections not yet
 * idle-retired; direct `DriverManager` connections were all closed. A registered datasource's pool
 * lives as long as its context and nothing in a run removes it, so the floor grew by 2 per
 * registering case (300 was crossed at `DashboardWorkspaceBrowserTest`).
 *
 * Bounded where it is produced, not here: [SOURCE_DATASOURCE_POOL_FIELDS] (every helper that
 * registers this server as a datasource keeps nothing warm) and [BrowserSuite]'s
 * `spring.datasource.hikari.*` (a cached context keeps at most 2 idle). Measured after, same run
 * shape and order: peak 29 (`DashboardWorkspaceBrowserTest`); at the end 10 contexts x at most 2
 * plus 160 registered pools x 0 idle = 22. [MAX_CONNECTIONS] therefore stays 400. [BrowserSuite]'s
 * class-level budget check fails the class that pushes the server past
 * [CONNECTION_BUDGET_THRESHOLD], by name.
 */
internal object SharedBrowserE2e {
    private const val PG_IMAGE = "postgres:16-alpine"

    /** Module-unique. */
    private const val DATABASE = "datapipelines_browser"

    private const val USER = "dp"
    private const val PASSWORD = "dp"

    /**
     * The shared server's `max_connections` — ONE constant: the container command and
     * [BrowserSuite]'s budget check both read it. Raise it only under #933's rule: when the
     * demand, bounded at its source, still peaks above 75% of it.
     */
    const val MAX_CONNECTIONS = 400

    /** The budget check's threshold: 85% of [MAX_CONNECTIONS]. */
    const val CONNECTION_BUDGET_THRESHOLD = MAX_CONNECTIONS * 85 / 100

    /**
     * The `pool.<hikariKey>` fields every helper that registers THIS server as a datasource posts
     * with the form (#933 (b)): keep no connection warm (`minimumIdle` 0) and close an unused one
     * after HikariCP's floor of 10 s. A case under load still gets the product's maximum pool size.
     */
    val SOURCE_DATASOURCE_POOL_FIELDS: Map<String, String> =
        mapOf("pool.minimumIdle" to "0", "pool.idleTimeout" to "10000")

    /** PostgreSQL's `too_many_connections` — what the server answers at [MAX_CONNECTIONS]. */
    private const val TOO_MANY_CONNECTIONS = "53300"

    private const val REDIS_IMAGE = "redis:7-alpine"
    private const val REDIS_PORT = 6379

    /** The shared Postgres, started and reset to an empty database on first touch. */
    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(PG_IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername(USER)
            .withPassword(PASSWORD)
            // Shared server, shared-sized connection budget (the SharedE2e finding; #933 above).
            .withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
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

    /**
     * #933's budget check, run once per class after its cases ([BrowserSuite]): ONE connection, ONE
     * query — the server-wide client backends, this check's own included. Returns the line the
     * class prints; throws an [AssertionError] naming the count, the ceiling, the threshold,
     * [className] and #933 when the count is above [CONNECTION_BUDGET_THRESHOLD], and the same
     * named error when the check itself is refused a connection at the ceiling (SQL state 53300).
     */
    fun checkConnectionBudget(className: String): String {
        val count =
            try {
                DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery("SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'")
                            .use { rows ->
                                rows.next()
                                rows.getInt(1)
                            }
                    }
                }
            } catch (refused: SQLException) {
                if (refused.sqlState != TOO_MANY_CONNECTIONS) throw refused
                throw AssertionError(
                    "#933 connection budget exceeded after $className: the budget check itself was refused a " +
                        "connection (SQL state $TOO_MANY_CONNECTIONS) — the shared browser Postgres is at its ceiling " +
                        "of $MAX_CONNECTIONS (threshold $CONNECTION_BUDGET_THRESHOLD). Bound the new demand where it " +
                        "is produced (SharedBrowserE2e KDoc).",
                    refused,
                )
            }
        if (count > CONNECTION_BUDGET_THRESHOLD) {
            throw AssertionError(
                "#933 connection budget exceeded after $className: the shared browser Postgres holds $count client " +
                    "connections, above the threshold $CONNECTION_BUDGET_THRESHOLD (85% of its ceiling " +
                    "$MAX_CONNECTIONS). Bound the new demand where it is produced (SharedBrowserE2e KDoc).",
            )
        }
        return "#933 connection budget after $className: $count client connections " +
            "(threshold $CONNECTION_BUDGET_THRESHOLD, ceiling $MAX_CONNECTIONS)"
    }

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
