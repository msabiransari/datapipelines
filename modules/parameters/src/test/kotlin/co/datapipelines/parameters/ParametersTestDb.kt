package co.datapipelines.parameters

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource

/**
 * ONE Postgres container for this module's whole test JVM, migrated ONCE with the SHIPPED migrations
 * through plain JDBC (module-structure §3.1 rule 2: no domain module takes Flyway) — the
 * `SchedulerTestDb` shape, V39 included, so these suites run the real `parameter_sets` /
 * `parameter_set_versions` DDL (and the real `templates` tables the pins resolve against).
 *
 * Each suite cleans the tables it touches ([reset]) and reseeds the two workspaces and the two users
 * every set needs.
 */
internal object ParametersTestDb {
    private const val IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one database. */
    private const val DATABASE = "datapipelines_parameters"

    val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000194")
    val OTHER_WORKSPACE: UUID = UUID.fromString("0a4e0000-0000-0000-0000-000000000194")
    val AUTHOR: UUID = UUID.fromString("a0740000-0000-0000-0000-000000000194")
    val OTHER_AUTHOR: UUID = UUID.fromString("a0750000-0000-0000-0000-000000000194")

    private val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName(DATABASE)
            .withUsername("dp")
            .withPassword("dp")
            .withReuse(true)
            .also(::boot)
    }

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

    /** A connection OUTSIDE the pool — the forced races hold a transaction open on it while the pool works. */
    fun rawConnection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    /** The container's JDBC coordinates — lane C's CUSTOMER datasource points at the same database (`CustomerDb`). */
    val jdbcUrl: String get() = postgres.jdbcUrl
    val username: String get() = postgres.username
    val password: String get() = postgres.password

    fun reset() {
        jdbc.jdbcTemplate.execute("TRUNCATE parameter_set_versions, parameter_sets, template_versions, templates CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name, is_personal, created_by) VALUES " +
                "('$WORKSPACE', 'params', 'Params', FALSE, NULL), ('$OTHER_WORKSPACE', 'params-other', 'Other', FALSE, NULL) " +
                "ON CONFLICT (id) DO NOTHING",
        )
        jdbc.jdbcTemplate.execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, kind) VALUES " +
                "('$AUTHOR', 'author@parameters.test', 'Author', 'local', 'author@parameters.test', 'human'), " +
                "('$OTHER_AUTHOR', 'other@parameters.test', 'Other', 'local', 'other@parameters.test', 'human') " +
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

    /** The shipped migrations in version order — derived, never pinned (the modules' `ShippedMigrations`). */
    fun migrations(): List<File> {
        val dir = ParametersTestFiles.repoFile("modules/app/src/main/resources/db/migration/V1__initial_schema.sql").parentFile
        val grammar = Regex("""^V(\d+)__.*\.sql$""")
        return dir
            .listFiles { f -> f.isFile && f.name.endsWith(".sql") }
            .orEmpty()
            .map { file ->
                requireNotNull(grammar.find(file.name)) { "'${file.name}' is not V<n>__<desc>.sql" }.groupValues[1].toInt() to
                    file
            }.sortedBy { it.first }
            .map { it.second }
    }

    private fun urlFor(
        container: PostgreSQLContainer<*>,
        database: String,
    ): String = container.jdbcUrl.substringBeforeLast('/').substringBefore('?') + "/" + database

    private const val POOL_SIZE = 8
}
