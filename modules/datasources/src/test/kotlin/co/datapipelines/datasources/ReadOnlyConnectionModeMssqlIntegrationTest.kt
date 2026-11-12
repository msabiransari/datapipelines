package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection

/**
 * #463 — MSSQL under [MssqlConnectivityIntegrationTest]'s host gate (linux/amd64 only; read that
 * class for why). Two SEPARATE things:
 *
 *  - **(a) the shipped behavior.** MSSQL is `DECLARATIVE_ONLY` on every host architecture:
 *    [ReadOnlyConnectionMode.within] just runs the block, so a write inside it SUCCEEDS.
 *  - **(b) a raw experiment, follow-up evidence only.** The driver is called directly — never
 *    through `within` — with `setReadOnly(true)`, then with a session statement, and the outcome
 *    of a write after each is PRINTED (`READONLY-MSSQL-EXPERIMENT …`) for the lane's handback.
 *    Nothing here asserts it and it is never copied into the object's table or the doc table.
 *
 * The container is started the simple way: this class adds two cases to a container the
 * connectivity suite already boots on the same pinned build, and the digest below is checked
 * against that suite's own source so the two cannot drift apart.
 */
class ReadOnlyConnectionModeMssqlIntegrationTest {
    @Test
    fun `MSSQL ships DECLARATIVE_ONLY - within runs the block and a write inside it succeeds, plus the raw experiment`() {
        assumeTrue(System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            "SKIPPED on ${System.getProperty("os.arch")}: the MSSQL image ships linux/amd64 only (see MssqlConnectivityIntegrationTest)"
        }
        ReadOnlyConnectionMode.enforcementFor(Dialect.MSSQL) shouldBe ReadOnlyConnectionMode.Enforcement.DECLARATIVE_ONLY
        MSSQLServerContainer(DockerImageName.parse(IMAGE)).acceptLicense().use { mssql ->
            mssql.start()
            val datasource =
                pooledOne(Dialect.MSSQL, "ro_mssql", mssql.jdbcUrl, mssql.username, mssql.password)
                    .let { it.copy(properties = it.properties.copy(jdbc = mapOf("trustServerCertificate" to "true"))) }
            ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
                pool.leaseConnection().use { c ->
                    c.execute("CREATE TABLE ro_t (n INT)")
                    ReadOnlyConnectionMode.within(c, Dialect.MSSQL, { error("nothing to dispose") }) {
                        c.execute("INSERT INTO ro_t VALUES (1)")
                    }
                    c.count() shouldBe 1L
                    experiment(c)
                }
            }
        }
    }

    @Test
    fun `the pinned image is the connectivity suite's own digest`() {
        TestFiles
            .repoFile("modules/datasources/src/test/kotlin/co/datapipelines/datasources/MssqlConnectivityIntegrationTest.kt")
            .readText() shouldContain IMAGE
    }

    /** (b) — raw driver calls, results printed, nothing asserted. */
    private fun experiment(c: Connection) {
        val flag =
            runCatching {
                c.isReadOnly = true
                c.execute("INSERT INTO ro_t VALUES (2)")
                "write SUCCEEDED (flag=${c.isReadOnly})"
            }.getOrElse { "write REFUSED ${it.javaClass.simpleName}: ${it.message}" }
        runCatching { c.isReadOnly = false }
        println("READONLY-MSSQL-EXPERIMENT mechanism=setReadOnly(true) result=$flag")
        val session =
            runCatching {
                // The standard SQL spelling; T-SQL is expected to reject it, which is the finding.
                c.execute("SET TRANSACTION READ ONLY")
                c.execute("INSERT INTO ro_t VALUES (3)")
                "statement accepted, write SUCCEEDED"
            }.getOrElse { "REFUSED ${it.javaClass.simpleName}: ${it.message}" }
        println("READONLY-MSSQL-EXPERIMENT mechanism=session-statement result=$session")
    }

    private fun Connection.count(): Long =
        createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM ro_t").use {
                it.next()
                it.getLong(1)
            }
        }

    private companion object {
        /** [MssqlConnectivityIntegrationTest]'s pinned build; the second case proves the copy matches. */
        const val IMAGE =
            "mcr.microsoft.com/mssql/server@sha256:97b448857967be55e005424a660056fe6d51814435804dc07e8f79f028bab5fb"
    }
}
