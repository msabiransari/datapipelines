package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName

/**
 * The MSSQL half of [DialectConnectivityIntegrationTest], separated **only** because its
 * container cannot start on every developer host.
 *
 * ## Why the architecture gate
 *
 * Microsoft publishes `mcr.microsoft.com/mssql/server` for **linux/amd64 only** — there is no
 * arm64 image. On Apple Silicon Docker runs it under Rosetta, where `sqlservr` starts, burns CPU,
 * and never reaches "ready for client connections"; every JDBC attempt gets a connection reset
 * and Testcontainers eventually throws `ContainerLaunchException`. Verified on this machine
 * (2026-08-08, darwin/arm64): the container reports `State.Running = true` while `ps` inside it
 * shows `/run/rosetta/rosetta /opt/mssql/bin/sqlservr` spinning, and the container log stops after
 * the three-line startup banner. Microsoft's documented arm64 alternative, Azure SQL Edge, has
 * been retired and is a different T-SQL surface, so substituting it would mean this test no longer
 * tests the product the `MSSQL` dialect targets.
 *
 * The gate is therefore on the **host architecture**, not on Docker availability: on amd64 (CI,
 * and any Intel/Linux dev box) the test runs for real and a genuine MSSQL regression fails the
 * build. On arm64 it reports **skipped, with the reason in the JUnit XML** — deliberately not a
 * silent pass and deliberately not a red build for an environment limitation. Escalated to the
 * orchestrator as a spec-coverage caveat: datasources.md §13.2 asks for an MSSQL container test,
 * and this is that test, with its one unavoidable host precondition stated out loud.
 */
class MssqlConnectivityIntegrationTest {
    @Test
    fun `mssql connects, queries, and maps an integer column`() {
        assumeTrue(isAmd64Host()) {
            "SKIPPED on ${System.getProperty("os.arch")}: mcr.microsoft.com/mssql/server ships linux/amd64 only " +
                "and does not reach 'ready for client connections' under Rosetta emulation. " +
                "This test runs for real on amd64 (CI)."
        }
        // Started manually rather than via @Container: a @Container field starts in beforeAll,
        // which runs BEFORE the assumption and would fail the whole class on arm64 instead of
        // skipping it — exactly the failure this class exists to avoid.
        MSSQLServerContainer(DockerImageName.parse(IMAGE)).acceptLicense().use { mssql ->
            mssql.start()
            DialectProbe.verifyIntegerColumn(
                datasource =
                    Datasource(
                        name = "mssql_it",
                        displayName = "MSSQL",
                        dialect = Dialect.MSSQL,
                        jdbcUrl = mssql.jdbcUrl,
                        username = mssql.username,
                        password = mssql.password,
                        properties = DatasourceProperties(jdbc = mapOf("trustServerCertificate" to "true")),
                    ),
                integerQuery = "SELECT CAST(1 AS INT) AS n",
            )
        }
    }

    private companion object {
        const val IMAGE = "mcr.microsoft.com/mssql/server:2022-latest"

        /** amd64 under either of the JVM's two spellings; anything else cannot run the image. */
        fun isAmd64Host(): Boolean = System.getProperty("os.arch") in setOf("amd64", "x86_64")
    }
}
