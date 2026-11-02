package co.datapipelines.lake

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource

/**
 * ONE Postgres container for this module's test JVM, migrated ONCE with the SHIPPED migrations through plain JDBC
 * (module-structure §3.1 rule 2: no domain module takes Flyway) — the `SchedulerTestDb` pattern, so these suites
 * run V53's real catalog tables, fence and revision triggers. The container user is a superuser (Testcontainers'
 * POSTGRES_USER): row triggers fire for it like for any role, and the A0 suite needs it for its event trigger.
 *
 * [reset] empties every table the lake writes and puts `lake_control` back to `normal`. A0's empty databases are
 * created beside the migrated one ([freshDatabase]).
 */
internal object LakeTestDb {
    private const val IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one container. */
    const val DATABASE = "datapipelines_lake"

    /** A throwaway password drawn per JVM (#785 security: no fixed credential, even for a test container). */
    val password: String = HexFormat.of().formatHex(ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes))

    private const val PASSWORD_BYTES = 16

    private val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername("dp")
            .withPassword(password)
            .withReuse(true)
            .also(::boot)
    }

    /** DP's own pooled DataSource — the Hikari pool the lake's catalog connections must never come from. */
    val dataSource: DataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = POOL_SIZE
            },
        )
    }

    val jdbc: JdbcTemplate by lazy { JdbcTemplate(dataSource) }

    val leases: LakeLeases by lazy { LakeLeases(jdbc, holderInstance = "lake-test") }

    /** The migrated database as the lake's catalog sees it (DriverManager, never the Hikari pool). */
    fun catalogDatabase(url: String = urlFor(DATABASE)): CatalogDatabase = CatalogDatabase(url, postgres.username, postgres.password)

    /** A plain JDBC URL to [database] on the container, without SSL/GSS negotiation (the proxy parses the protocol). */
    fun urlFor(
        database: String,
        port: Int = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
        host: String = postgres.host,
    ): String = "jdbc:postgresql://$host:$port/$database?sslmode=disable&gssEncMode=disable"

    /** The container's mapped port, for [PgResponseDropProxy]'s upstream. */
    val port: Int get() = postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)

    val host: String get() = postgres.host

    /** An autocommit connection of its own on [database] — for fence probes and for polling `pg_stat_activity`. */
    fun connect(database: String = DATABASE): Connection =
        DriverManager.getConnection(urlFor(database), postgres.username, postgres.password)

    /** Empties every table the lake writes and puts the catalog back in `normal` mode. TRUNCATE fires no row trigger. */
    fun reset() {
        jdbc.execute("TRUNCATE iceberg_tables, iceberg_namespace_properties, lake_leases, lake_table_revisions RESTART IDENTITY")
        jdbc.execute("UPDATE lake_control SET mode = 'normal'")
    }

    /** Drops and recreates [name] empty — no migrations — for the A0 catalog-shape cases. */
    fun freshDatabase(name: String): String {
        require(Regex("^[a-z0-9_]{1,40}$").matches(name)) { "test database names are [a-z0-9_]: $name" }
        connect("postgres").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""DROP DATABASE IF EXISTS "$name" WITH (FORCE)""")
                statement.execute("""CREATE DATABASE "$name" OWNER "${postgres.username}"""")
            }
        }
        return name
    }

    /** A lease that was acquired: the free-name path of §8a's acquire, deadline [ttl] past the catalog clock. */
    fun lease(
        kind: LeaseKind,
        name: String,
        ttl: Duration = Duration.ofMinutes(5),
        operationId: UUID = UUID.randomUUID(),
    ): LeaseHandle {
        val outcome = leases.acquire(kind, name, operationId, ttl, leases.catalogNowPlus(ttl.multipliedBy(2)), "test")
        return (outcome as? LeaseOutcome.Acquired)?.lease ?: error("lease $kind $name not acquired: $outcome")
    }

    private fun boot(container: PostgreSQLContainer<*>) {
        container.start()
        DriverManager.getConnection(urlFor(container, "postgres"), container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                val names =
                    statement
                        .executeQuery("SELECT datname FROM pg_database WHERE datistemplate = false AND datname <> 'postgres'")
                        .use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                names.forEach { statement.execute("""DROP DATABASE IF EXISTS "$it" WITH (FORCE)""") }
                statement.execute("""CREATE DATABASE "$DATABASE" OWNER "${container.username}"""")
            }
        }
        val jdbc = JdbcTemplate(DriverManagerDataSource(container.jdbcUrl, container.username, container.password))
        migrations().forEach { jdbc.execute(it.readText()) }
    }

    /** The shipped migrations, version order — derived, never pinned (the modules' `ShippedMigrations`). */
    fun migrations(): List<File> {
        val dir = repoFile("modules/app/src/main/resources/db/migration")
        val grammar = Regex("""^V(\d+)__.*\.sql$""")
        return dir
            .listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            .orEmpty()
            .map { file ->
                val version = requireNotNull(grammar.find(file.name)) { "'${file.name}' is not V<n>__<desc>.sql" }.groupValues[1].toInt()
                version to file
            }.sortedBy { it.first }
            .map { it.second }
    }

    /** V53 itself — the catalog-shape suite reads the shipped DDL out of it rather than restating it. */
    fun v53(): String = migrations().single { it.name.startsWith("V53__") }.readText()

    /** A file relative to the repository root, wherever the test task runs from. */
    fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, relative).also { require(it.exists()) { "missing: $relative" } }
    }

    private fun urlFor(
        container: PostgreSQLContainer<*>,
        database: String,
    ): String = container.jdbcUrl.substringBeforeLast('/').substringBefore('?') + "/" + database

    private const val POOL_SIZE = 8
}
