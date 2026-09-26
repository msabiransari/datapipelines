package co.datapipelines.scheduler

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/**
 * ONE Postgres container for this module's whole test JVM, migrated ONCE with the SHIPPED
 * migrations through plain JDBC (module-structure §3.1 rule 2: no domain module takes Flyway) —
 * the pattern every module's `SharedPostgres` follows, V38 included, so these suites run the real
 * `schedules` / `schedule_runs` / `schedule_run_events` / `scheduled_tasks` DDL.
 *
 * Each suite cleans the tables it touches ([reset]) and reseeds the two rows every schedule needs:
 * the workspace and the users (a creator and the system identity).
 */
internal object SchedulerTestDb {
    private const val IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one container. */
    private const val DATABASE = "datapipelines_scheduler"

    val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    val OTHER_WORKSPACE: UUID = UUID.fromString("0a4e0000-0000-0000-0000-000000000009")
    val CREATOR: UUID = UUID.fromString("c4ea0000-0000-0000-0000-000000000009")
    val SYSTEM_ACTOR: UUID = UUID.fromString("5a570000-0000-0000-0000-000000000009")

    private val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername("dp")
            .withPassword("dp")
            .withReuse(true)
            .also(::boot)
    }

    /** A pooled DataSource on the shared container (a fixture-heavy suite pays no per-statement connect). */
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

    val jdbc: NamedParameterJdbcTemplate by lazy { NamedParameterJdbcTemplate(dataSource) }

    /** Empties every table the scheduler writes and reseeds the workspace and the users. */
    fun reset() {
        jdbc.jdbcTemplate.execute("TRUNCATE schedule_run_events, schedule_runs, schedules, scheduled_tasks CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name, is_personal, created_by) VALUES " +
                "('$WORKSPACE', 'default', 'Default', FALSE, NULL), ('$OTHER_WORKSPACE', 'other', 'Other', FALSE, NULL) " +
                "ON CONFLICT (id) DO NOTHING",
        )
        jdbc.jdbcTemplate.execute(
            "UPDATE workspaces SET deactivated_at = NULL, is_deleted = FALSE WHERE id IN ('$WORKSPACE', '$OTHER_WORKSPACE')",
        )
        jdbc.jdbcTemplate.execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, kind) VALUES " +
                "('$CREATOR', 'author@scheduler.test', 'Author', 'local', 'author@scheduler.test', 'human'), " +
                "('$SYSTEM_ACTOR', 'system@scheduler.invalid', 'System', 'system', 'system-scheduler-test', 'system') " +
                "ON CONFLICT (id) DO NOTHING",
        )
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
