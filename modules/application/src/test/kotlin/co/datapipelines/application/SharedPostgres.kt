package co.datapipelines.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager

/**
 * ONE Postgres container for this module's whole test JVM — the eighth sibling of the
 * `SharedPostgres` every other module with integration suites keeps in its own test sources
 * (DEVELOPMENT.md §9.1). First touch starts the container, resets the database to empty, and
 * applies the shipped migrations once.
 *
 * ## Why this module finally has one (083 §D)
 *
 * `:modules:application` sat at 84.9 % against an 84 floor, and the margin was thin for a
 * structural reason rather than a sloppy one: three JDBC classes here
 * ([co.datapipelines.application.endpoints.PublishedEndpointRepository],
 * [co.datapipelines.application.endpoints.EndpointKeyBindingRepository] and
 * [co.datapipelines.application.endpoints.EndpointServeAudit]) were exercised only through the
 * *web* module's integration suite, so this module earned no coverage for its own code and the
 * next addition here would have tripped someone else's gate. The 074 handback named the fix —
 * "it needs a Postgres fixture the module does not yet have" — and this is that fixture.
 *
 * They also deserve a real database on their own merits: an advisory lock, an `ON CONFLICT DO
 * NOTHING`, a `UNIQUE` violation translated to a catalog code, and a JSONB `->>` comparison are
 * all statements *about Postgres*. A mocked `NamedParameterJdbcTemplate` would assert that this
 * module passes strings to Spring, which is not the property anyone doubts.
 *
 * ## The cleaning rule that makes sharing safe
 *
 * Each spec cleans the tables it touches (`TRUNCATE … CASCADE` plus re-seed) rather than
 * relying on a fresh container — the discipline every sibling follows.
 *
 * ## Reuse
 *
 * `withReuse(true)` is a no-op unless a developer opts in per machine
 * (`~/.testcontainers.properties`, DEVELOPMENT.md §9.2). The database is dropped and recreated
 * at first touch either way, so a reused container behaves exactly like a fresh one.
 */
internal object SharedPostgres {
    private const val IMAGE = "postgres:16-alpine"

    /** Module-unique: two modules' test JVMs must never land on one container. */
    private const val DATABASE = "datapipelines_application"

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

    /**
     * An unpooled [DriverManagerDataSource] on the shared container — a new physical connection
     * per `getConnection()`.
     *
     * Unpooled deliberately (DEVELOPMENT.md §9.1): the publish path takes a *transaction-scoped*
     * advisory lock, and a suite that asserts two callers really do serialise needs two
     * genuinely independent sessions. A pool may hand both callers the same connection and
     * serialise statements that were meant to race, which turns that guard green without
     * testing anything.
     */
    fun dataSource(): DriverManagerDataSource =
        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName(postgres.driverClassName)
        }

    private fun boot(container: PostgreSQLContainer<*>) {
        container.start()
        // Drop every database this container may still hold (its own first-boot default on a
        // fresh container; yesterday's schema on a reused one) so the JVM below starts from a
        // provably empty server, then create the module's database and migrate it once.
        withAdminConnection(container) { statement ->
            val names =
                statement
                    .executeQuery(
                        "SELECT datname FROM pg_database WHERE datistemplate = false AND datname <> 'postgres'",
                    ).use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
            names.forEach { name -> statement.execute("""DROP DATABASE IF EXISTS "$name" WITH (FORCE)""") }
        }
        withAdminConnection(container) { statement ->
            statement.execute("""CREATE DATABASE "$DATABASE" OWNER "$USER"""")
        }
        val jdbc =
            JdbcTemplate(DriverManagerDataSource(container.jdbcUrl, container.username, container.password))
        ShippedMigrations.paths().forEach { path -> jdbc.execute(TestRepoFiles.read(path)) }
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

/**
 * Locates files relative to the repository root, whichever directory the test task runs from —
 * the same helper `auth`, `web`, `dag` and `pipeline-contract` keep in their own test sources so
 * a domain module can execute `app`'s real DDL without taking a Flyway dependency
 * (module-structure §3.1 rule 2).
 */
internal object TestRepoFiles {
    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
    }

    fun read(relativePath: String): String =
        File(root, relativePath)
            .also { require(it.exists()) { "Expected repo file not found: $relativePath (root=$root)" } }
            .readText()
}

/**
 * The shipped migration directory as a version-ordered path list — DERIVED, never hand-pinned.
 *
 * A literal list goes stale the moment a migration lands elsewhere, and the suite then runs on a
 * schema production does not have while staying green. The grammar is enforced rather than
 * filtered: a `.sql` file that does not match `V<version>__<desc>.sql` throws NAMING THE FILE
 * instead of being silently skipped, and lexicographic order would put V10 between V1 and V2.
 */
internal object ShippedMigrations {
    private const val DIR = "modules/app/src/main/resources/db/migration"
    private val VERSION_PREFIX = Regex("""^V(\d+)__.*\.sql$""")

    fun paths(): List<String> {
        val dir = File(TestRepoFiles.root, DIR)
        require(dir.isDirectory) { "No migration directory at $DIR" }
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".sql") }.orEmpty()
        return files
            .map { file ->
                val matched =
                    VERSION_PREFIX.matchEntire(file.name)
                        ?: error("Migration '${file.name}' does not match V<version>__<description>.sql")
                val version = matched.groupValues[1].toInt()
                version to file.name
            }.sortedBy { it.first }
            .map { "$DIR/${it.second}" }
            .also { require(it.isNotEmpty()) { "No migrations found under $DIR" } }
    }
}
