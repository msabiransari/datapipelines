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

    @Test
    fun `a pre-existing DP_H2_RESTRICTED carrying ADMIN is de-escalated, not just re-passworded`() {
        // 186b LOW (a): the restricted identity persists in a FILE database across pool builds.
        // If a database arrives with a user of that name carrying ADMIN (created outside the
        // product), a rotation that only re-passwords leaves the pool's identity an admin and
        // A3 is gone. The rotation therefore runs ALTER USER … ADMIN FALSE as well.
        val dbFile = scratch.resolve("squatter-${UUID.randomUUID()}.mv.db").absolutePathString().removeSuffix(".mv.db")
        val url = "jdbc:h2:file:$dbFile"
        // The hostile pre-existing file: DP_H2_RESTRICTED exists, WITH admin rights.
        DriverManager.getConnection(url, "sa", "").use { admin ->
            admin.createStatement().use {
                it.execute("CREATE USER ${H2InProcessPool.RESTRICTED_USER} PASSWORD 'squatter' ADMIN")
            }
        }
        val datasource = h2(url, username = "sa", secret = "")

        val token = "squatter-token-${UUID.randomUUID()}"
        val secret = scratch.resolve("squatter-secret.txt").also { it.writeText(token) }
        try {
            ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
                pool.leaseConnection().use { connection ->
                    val read =
                        shouldThrow<SQLException> { query(connection, "SELECT FILE_READ('${secret.absolutePathString()}', 'UTF-8')") }
                    read.sqlState shouldBe "90040"
                }
            }
            // And the flag itself reads FALSE in the database — the rotation de-escalated the
            // stored user, it did not merely fail this generation's privilege probe.
            DriverManager.getConnection(url, "sa", "").use { admin ->
                query(
                    admin,
                    "SELECT IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME = '${H2InProcessPool.RESTRICTED_USER}'",
                ) shouldBe "FALSE"
            }
        } finally {
            secret.deleteIfExists()
        }
    }

    @Test
    fun `a mixed-case H2 URL is never pooled - no file database appears under the working directory`() {
        // 186b: H2's parseName matches the four prefixes case-SENSITIVELY, so
        // `jdbc:h2:TCP://h/x` is a literal FILE the driver creates under the process's working
        // directory. Registration refuses the form; this is the runtime backstop — the pool
        // refuses it too, and nothing materializes on disk. Falsifiable: with the classifier's
        // case-sensitivity reverted, the URL classifies Server, the plain pool OPENS it (as the
        // registered credential, H2's admin on a fresh file) and the .mv.db appears.
        val before = mvDbUnderCwd()
        val datasource = h2("jdbc:h2:TCP://localhost:9/depool_case", username = "sa", secret = "sa")

        try {
            shouldThrow<IllegalArgumentException> { ConnectionPoolManager.buildHikariPool(datasource) }
        } finally {
            val created = mvDbUnderCwd() - before
            try {
                created shouldBe emptySet()
            } finally {
                // A regression's litter must not survive into the next run's `before` snapshot.
                created.forEach { java.io.File(it).deleteRecursively() }
                java.io.File("./TCP:").deleteRecursively()
            }
        }
    }

    @Test
    fun `admin-gated URL settings are applied once by the bootstrap and stripped from operational opens`() {
        // 186b LOW (b): H2 runs a URL's admin-gated settings as SET commands on EVERY session
        // open (Engine.openSession), and the restricted user cannot run them — before this
        // round every pooled open of such a datasource failed with 90040. The bootstrap (the
        // registered, admin credential) applies them once; they are database-scoped, so the
        // effect survives into the stripped operational URL. Falsifiable: drop CACHE_SIZE from
        // the strip set and this pool never comes up.
        val dbName = "depool_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;CACHE_SIZE=8192;WRITE_DELAY=2000"
        val datasource = h2(url, username = "sa", secret = "sa")

        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { connection ->
                assertAll(
                    { currentUser(connection) shouldBe H2InProcessPool.RESTRICTED_USER },
                    {
                        query(
                            connection,
                            "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'CACHE_SIZE'",
                        ) shouldBe "8192"
                    },
                )
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

    /** The H2 database files under the test JVM's working directory — the "the driver created a file" witness. */
    private fun mvDbUnderCwd(): Set<String> =
        java.io
            .File(".")
            .walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name.endsWith(".mv.db") }
            .map { it.path }
            .toSet()

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
