package co.datapipelines.integration

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * ONE Postgres and ONE Redis for this module's whole test JVM (round 060) — the singleton
 * pattern `dag`'s `RedisSupport` established. First touch starts each container and resets
 * it to a provably empty state; the suites here boot full application contexts against
 * them through `@DynamicPropertySource` instead of declaring their own `@Container` pairs,
 * so a module gate pays one cold start per engine rather than one per suite.
 *
 * ## Migrations: the first context's Flyway, exactly as production applies them
 *
 * This module boots the real application (`app` carries the Flyway dependency,
 * module-structure §3.1 rule 2), so this object deliberately migrates NOTHING: the first
 * context to boot migrates the empty database through Spring Boot's Flyway
 * autoconfiguration — the exact path production takes — and every later context finds
 * the schema history complete and no-ops.
 *
 * ## The isolation model that makes sharing safe
 *
 * Today's per-suite containers are replaced by per-suite DATA isolation, which the suites
 * already mostly have: seeds carry suite-unique emails, user ids, names and keys, and
 * assertions are scoped to them. A suite whose assertions are GLOBAL (an exact-set
 * listing, a whole-table count) calls [E2eClean.once] at the top of its first-test seed
 * hook, which truncates every table the migrations created (derived from `pg_tables`,
 * never a hand-copied list — `flyway_schema_history` excluded) and re-seeds the V4
 * `default` workspace, i.e. exactly the state a freshly migrated database is in. Suites
 * that need the schema in a partial migration state (the V4 rekey test) take a
 * [scratchDatabase]; the two-deployment promotion suite keeps its own containers because
 * per-deployment isolation is its subject.
 *
 * ## Reuse (DEVELOPMENT.md, "Reusing test containers across runs")
 *
 * `withReuse(true)` is declared on both containers so a developer who opts in locally via
 * `~/.testcontainers.properties` keeps them across Gradle runs; without the opt-in it is
 * a no-op and Ryuk reaps them at JVM exit as always. The database is dropped and
 * recreated and the keyspace FLUSHALLed at first touch either way, so a reused pair
 * behaves exactly like fresh ones. A container wedged by a killed run is removed with
 * `docker rm`.
 */
internal object SharedE2e {
    private const val PG_IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one container. */
    private const val DATABASE = "datapipelines_e2e"

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
            // The context cache keeps every suite's context — and its Hikari pool — open
            // until JVM exit; fifteen-odd cached pools exhaust Postgres's default 100
            // connections mid-gate (observed as Flyway's "too many clients already"). A
            // shared server needs a shared-sized connection budget.
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

    /**
     * The shared Redis (`noeviction`, as every suite declared): the engine's result store
     * must never evict. FLUSHALLed at first touch so a reused container is semantically
     * fresh.
     */
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

    val redisHost: String get() = redis.host

    val redisPort: Int get() = redis.getMappedPort(REDIS_PORT)

    /**
     * A fresh EMPTY database on the shared Postgres, for a suite that builds a partial
     * schema itself (the V4 rekey test). [name] namespaces it; the caller applies whatever
     * migration subset is its subject.
     */
    fun scratchDatabase(name: String): SharedPostgresRef {
        val database = "scratch_$name"
        withAdminConnection(postgres) { statement ->
            statement.execute("""DROP DATABASE IF EXISTS "$database" WITH (FORCE)""")
            statement.execute("""CREATE DATABASE "$database" OWNER "$USER"""")
        }
        return SharedPostgresRef(
            jdbcUrl = urlFor(postgres, database),
            username = USER,
            password = PASSWORD,
        )
    }

    /** The endpoint a suite connects to. */
    data class SharedPostgresRef(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

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

/**
 * Data reset to the freshly-migrated state, for suites whose seeds or assertions need the
 * empty world their former per-suite container guaranteed: suites that re-seed the shared
 * acme/globex fixture vocabulary, suites with exact-set or whole-table assertions, and
 * the fresh-deployment walkthrough. Derives the table list from `pg_tables` — a migration
 * that adds a table is included automatically, never a hand-copied list — excludes
 * `flyway_schema_history` (dropping it would make every later context re-migrate or
 * fail), truncates with CASCADE, and re-seeds the V4 `default` workspace.
 *
 * ALWAYS call from the suite's first-test seed hook BEHIND its `seeded` flag — the flag,
 * not this object, provides the once-per-suite guarantee (several suites in one JVM each
 * need their own reset). A static `@BeforeAll` is too early: it runs before the context —
 * and with it Flyway — so the tables would not exist yet.
 */
internal object E2eClean {
    /** The V4-seeded `default` workspace (metadata-db §4.11) — a pinned literal, not a guess. */
    private const val DEFAULT_WORKSPACE =
        "INSERT INTO workspaces (id, name, display_name) VALUES " +
            "('defa0000-0000-0000-0000-000000000001', 'default', 'Default') ON CONFLICT DO NOTHING"

    fun beforeSeeding() {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                val tables =
                    statement
                        .executeQuery(
                            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                        ).use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        }
                check(tables.isNotEmpty()) { "No public tables found — has a context (and Flyway) run yet?" }
                statement.execute("TRUNCATE ${tables.joinToString(", ") { "\"$it\"" }} CASCADE")
                statement.execute(DEFAULT_WORKSPACE)
            }
        }
    }
}
