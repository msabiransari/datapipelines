package co.datapipelines.executor

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Duration

/**
 * ONE SQL Server container for this module's test JVM (#1036) — the dag copy of the image pin
 * `datasources`' `MssqlConnectivityIntegrationTest` proves (DEVELOPMENT.md §9.2a: pinned by digest,
 * `16.0.4275.2`), following the per-module copy precedent [SharedPostgres] and [SharedMysql] set.
 *
 * The image needs an amd64 host and the accepted EULA ([MSSQLServerContainer.acceptLicense], the
 * repository's existing acceptance); on any other architecture the caller is SKIPPED by assumption
 * rather than failed, exactly as the datasources suite does. The container's own log streams to
 * stderr so a startup death (the #229 host AIO exhaustion) leaves its errorlog in the JUnit XML.
 *
 * First touch creates the module-unique database `q_dag`; each suite drops the tables it creates
 * before creating them, so a reused container behaves like a fresh one.
 */
internal object SharedMssql {
    /** The digest `datasources`' MSSQL suites pin (SQL Server 2022 16.0.4275.2). */
    private const val IMAGE =
        "mcr.microsoft.com/mssql/server@sha256:97b448857967be55e005424a660056fe6d51814435804dc07e8f79f028bab5fb"

    /** Module-unique (see the class KDoc). */
    const val DATABASE = "q_dag"

    private val startupCeiling: Duration = Duration.ofMinutes(5)

    /** Skips the caller on a host the image cannot run on. */
    fun assumeRunnable() {
        val arch = System.getProperty("os.arch").lowercase()
        assumeTrue(arch == "amd64" || arch == "x86_64") { "SQL Server's image is amd64-only; this host is $arch" }
    }

    /** The shared container, started and given its database on first touch. */
    val mssql: MSSQLServerContainer<*> by lazy {
        MSSQLServerContainer(DockerImageName.parse(IMAGE))
            .acceptLicense()
            .withStartupTimeout(startupCeiling)
            .withReuse(true)
            .withLogConsumer { frame -> System.err.print(frame.utf8String) }
            .also(::boot)
    }

    /** A JDBC URL for [DATABASE] on [host]:[port] — the container's own address, or a proxy in front of it. */
    fun jdbcUrl(
        host: String = mssql.host,
        port: Int = mssql.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT),
    ): String = "jdbc:sqlserver://$host:$port;databaseName=$DATABASE;encrypt=false;trustServerCertificate=true"

    private fun boot(container: MSSQLServerContainer<*>) {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("IF DB_ID('$DATABASE') IS NULL CREATE DATABASE $DATABASE")
            }
        }
    }
}
