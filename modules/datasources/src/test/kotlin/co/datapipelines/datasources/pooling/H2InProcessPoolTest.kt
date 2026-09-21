package co.datapipelines.datasources.pooling

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * The #186 A3 guard: a registered in-process H2 datasource's pool runs **de-privileged** —
 * every connection authenticates as `DP_H2_RESTRICTED`, never as the registered (admin)
 * credential — so author SQL through a node or `sql_probe` cannot reach the host even though a
 * super admin registered the engine.
 *
 * **This test must be able to fail.** Reverting the `buildHikariPool` interception turns every
 * refusal below into a working `FILE_READ`/`CREATE ALIAS` — the control case proves the same
 * statement succeeds as the registered `sa`, so a reversion is red, not green.
 *
 * Everything runs against temp files with random tokens — never a real secret (186's brief).
 */
class H2InProcessPoolTest {
    private val scratch = Files.createTempDirectory("dp-h2-pool")

    @Test
    fun `a mem datasource pools the restricted user - workload works, the host surface is refused`() {
        val dbName = "depool_${UUID.randomUUID().toString().replace("-", "")}"
        // DB_CLOSE_DELAY=-1 everywhere the database is touched: with default semantics a mem:
        // database dies with its last connection, and the seeding connection below would take
        // the fixture with it. A customer's mem: datasource carries the same setting for the
        // same reason.
        val url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
        // The customer's shape: the database and its table exist before the product connects
        // (created here as sa, exactly as the registered credential would have made them).
        DriverManager.getConnection(url, "sa", "sa").use { admin ->
            admin.createStatement().use { it.execute("CREATE TABLE readings (id INT PRIMARY KEY, label VARCHAR(20))") }
            admin.createStatement().use { it.execute("INSERT INTO readings VALUES (1, 'a')") }
        }
        val datasource = h2(url, username = "sa", secret = "sa")

        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { connection ->
                assertAll(
                    { currentUser(connection) shouldBe H2InProcessPool.RESTRICTED_USER },
                    // The workload: read AND write the customer's own PUBLIC tables.
                    { query(connection, "SELECT label FROM readings") shouldBe "a" },
                    {
                        connection.createStatement().use { it.execute("INSERT INTO readings VALUES (2, 'b')") }
                        query(connection, "SELECT COUNT(*) FROM readings") shouldBe "2"
                    },
                    // And DDL — a DDL node is a legitimate workload against your own datasource.
                    { connection.createStatement().use { it.execute("CREATE TABLE made_by_pool (id INT)") } },
                )
            }
        }
    }

    @Test
    fun `FILE_READ and CREATE ALIAS through the pool are refused with 90040 - admin control reads the file`() {
        val token = "pool-token-${UUID.randomUUID()}"
        val secret = scratch.resolve("secret.txt").also { it.writeText(token) }
        val dbName = "depool_${UUID.randomUUID().toString().replace("-", "")}"
        val datasource = h2("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", username = "sa", secret = "sa")

        try {
            ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
                pool.leaseConnection().use { connection ->
                    val read =
                        shouldThrow<SQLException> { query(connection, "SELECT FILE_READ('${secret.absolutePathString()}', 'UTF-8')") }
                    val alias =
                        shouldThrow<SQLException> {
                            connection
                                .createStatement()
                                .use { it.execute("CREATE ALIAS GET_PROP FOR 'java.lang.System.getProperty(java.lang.String)'") }
                        }
                    assertAll(
                        { read.sqlState shouldBe "90040" },
                        { alias.sqlState shouldBe "90040" },
                        { read.message.orEmpty() shouldContain "Admin rights are required" },
                    )
                }
            }

            // The control: the REGISTERED credential reads the same file — that is the
            // capability the pool no longer hands to author SQL.
            DriverManager.getConnection("jdbc:h2:mem:$dbName", "sa", "sa").use { admin ->
                query(admin, "SELECT FILE_READ('${secret.absolutePathString()}', 'UTF-8')") shouldBe token
            }
        } finally {
            secret.deleteIfExists()
        }
    }

    @Test
    fun `a file datasource survives a pool rebuild - the restricted user's password rotates`() {
        val dbFile = scratch.resolve("rotate-${UUID.randomUUID()}.mv.db").absolutePathString().removeSuffix(".mv.db")
        val url = "jdbc:h2:file:$dbFile"
        val datasource = h2(url, username = null, secret = null)

        // First build creates the file database (bootstrap sa/"") and a table through the pool.
        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE t (id INT PRIMARY KEY)") }
                connection.createStatement().use { it.execute("INSERT INTO t VALUES (7)") }
            }
        }
        // Second build over the SAME file: the user persists there from the first generation —
        // rotateIfExists is what makes this a rotation instead of a duplicate-user failure.
        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { connection ->
                query(connection, "SELECT id FROM t") shouldBe "7"
            }
        }
    }

    private fun h2(
        url: String,
        username: String?,
        secret: String?,
    ) = Datasource(
        name = "h2-${UUID.randomUUID().toString().take(8)}",
        displayName = "A3 test",
        dialect = Dialect.H2,
        jdbcUrl = url,
        username = username,
        credentialKind =
            if (secret == null) {
                co.datapipelines.datasources.CredentialKind.NONE
            } else {
                co.datapipelines.datasources.CredentialKind.PASSWORD
            },
        secret = secret,
        properties = DatasourceProperties(),
    )

    private fun currentUser(connection: java.sql.Connection): String = query(connection, "SELECT CURRENT_USER")

    private fun query(
        connection: java.sql.Connection,
        sql: String,
    ): String =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rows ->
                rows.next()
                rows.getString(1)
            }
        }
}
