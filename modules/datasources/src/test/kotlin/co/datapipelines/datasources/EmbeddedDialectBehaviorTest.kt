package co.datapipelines.datasources

import co.datapipelines.datasources.pooling.ConnectionPoolManager
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.system.measureTimeMillis

/**
 * The three dialects that need **no container** — H2, DuckDB and SQLite are embedded, so the
 * adapter → HikariCP → driver → typesystem path they share with Postgres/MySQL/MSSQL can be
 * proven in-process (datasources.md §13.2).
 *
 * DuckDB and SQLite previously had *no* connectivity coverage at all: their adapters were exercised
 * only through URL-string assertions, which cannot catch a wrong driver class, a wrong scheme, or a
 * type mapper that does not survive real `ResultSetMetaData`.
 *
 * Pool-limit behavior lives here too, because it needs a real Hikari pool against a real database
 * and H2 supplies one for free.
 */
class EmbeddedDialectBehaviorTest {
    @Test
    fun `duckdb connects, queries, and maps an integer column`() {
        DialectProbe.verifyIntegerColumn(
            datasource = embedded(Dialect.DUCKDB, "duckdb_probe", "jdbc:duckdb::memory:"),
            integerQuery = "SELECT CAST(1 AS INTEGER) AS n",
        )
    }

    @Test
    fun `sqlite connects, queries, and maps an integer column`() {
        DialectProbe.verifyIntegerColumn(
            datasource = embedded(Dialect.SQLITE, "sqlite_probe", "jdbc:sqlite::memory:"),
            integerQuery = "SELECT CAST(1 AS INTEGER) AS n",
        )
    }

    @Test
    fun `duckdb and sqlite URLs validate for their own scheme and reject the other's`() {
        val duckdb = DialectAdapters.forDialect(Dialect.DUCKDB)
        val sqlite = DialectAdapters.forDialect(Dialect.SQLITE)

        duckdb.validateJdbcUrl("jdbc:duckdb::memory:").valid shouldBe true
        duckdb.validateJdbcUrl("jdbc:duckdb:/var/lib/warehouse.db").valid shouldBe true
        sqlite.validateJdbcUrl("jdbc:sqlite::memory:").valid shouldBe true
        sqlite.validateJdbcUrl("jdbc:sqlite:/var/lib/app.db").valid shouldBe true

        duckdb
            .validateJdbcUrl("jdbc:sqlite::memory:")
            .errors
            .single()
            .code shouldBe
            DatasourceErrorCodes.JDBC_URL_SCHEME_INVALID
        sqlite
            .validateJdbcUrl("jdbc:duckdb::memory:")
            .errors
            .single()
            .code shouldBe
            DatasourceErrorCodes.JDBC_URL_SCHEME_INVALID
        // A file path containing '@' is a path, not a userinfo authority — these dialects are
        // file-backed and must not trip the credential-authority check.
        duckdb.validateJdbcUrl("jdbc:duckdb:/var/lib/a@b.db").valid shouldBe true
    }

    @Test
    fun `R5 F1 driver pins - the closed-connection shapes the classifier's per-driver knowledge rests on`() {
        // Reproducing cases for SchemaIntrospector's connection-loss classification, against
        // the REAL pinned drivers — if a driver bump changes any shape below, the classifier's
        // per-driver knowledge (H2 codes, DuckDB/SQLite closed-connection messages) goes stale
        // and THIS test fails, forcing re-derivation instead of silent misclassification.
        //
        // h2 2.3.232: a closed connection OBJECT reports "The object is already closed" as
        // org.h2.jdbc.JdbcSQLNonTransientException with SQLState "90007" (vendor code 90007) —
        // NOT an SQLNonTransientConnectionException subclass, NOT an 08 state, so neither the
        // type branch nor the state branch can see it. (A database SHUTDOWN under a live
        // connection arrives as JdbcSQLNonTransientConnectionException state 90121, which the
        // existing type branch already classifies — verified 2026-08-16.)
        val h2 = java.sql.DriverManager.getConnection("jdbc:h2:mem:f1pin", "sa", "")
        h2.close()
        val h2Thrown = shouldThrow<SQLException> { h2.schema }
        (h2Thrown is org.h2.jdbc.JdbcSQLNonTransientException) shouldBe true
        h2Thrown.sqlState shouldBe "90007"
        h2Thrown.errorCode shouldBe 90007

        // duckdb_jdbc 1.5.5.1: a closed connection reports getSchema() failure as a PLAIN
        // java.sql.SQLException — NULL SQLState, vendor code 0, message exactly
        // "Connection was closed" (the JDBC layer's own lifecycle text). Native errors are
        // ALSO plain null-state SQLExceptions with DIFFERENT messages, so the message is the
        // only discriminator — pinned by the query-error arm below.
        val duck = java.sql.DriverManager.getConnection("jdbc:duckdb::memory:")
        duck.close()
        val duckClosed = shouldThrow<SQLException> { duck.schema }
        duckClosed.javaClass shouldBe SQLException::class.java
        duckClosed.sqlState shouldBe null
        duckClosed.errorCode shouldBe 0
        duckClosed.message shouldBe "Connection was closed"
        val duckQueryError =
            java.sql.DriverManager.getConnection("jdbc:duckdb::memory:").use { duckLive ->
                shouldThrow<SQLException> {
                    duckLive.createStatement().use { it.executeQuery("SELECT * FROM no_such_table_f1pin") }
                }
            }
        (duckQueryError.message == "Connection was closed") shouldBe false

        // sqlite-jdbc 3.49.1.0: getSchema() is hardcoded null — even on a CLOSED connection
        // it returns null rather than throwing, which is what makes the schemaless exemption
        // in columns() safe today (R5 F3 makes it structural).
        val lite = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        lite.close()
        lite.schema shouldBe null
    }

    @Test
    fun `a saved duckdb or sqlite datasource passes the full validator including the test pool build`() {
        val validator = DatasourceValidator()

        validator.validate(embedded(Dialect.DUCKDB, "duckdb_save", "jdbc:duckdb::memory:"), isCreate = true).errors shouldBe emptyList()
        validator.validate(embedded(Dialect.SQLITE, "sqlite_save", "jdbc:sqlite::memory:"), isCreate = true).errors shouldBe emptyList()
    }

    @Test
    fun `DS-SEC-19 - a properties_jdbc key reaches the DuckDB engine, so the settings catalog is a refusal surface`() {
        // The Gate C reviewer could not confirm from the jar whether DuckDB forwards an
        // unrecognized properties.jdbc key into engine config. This settles it against the real
        // engine, and stays as the regression guard: if a future driver stops forwarding, the
        // CONTROL/SET pair below stops differing and this test says so.
        //
        // The comparison is control-vs-set, never a bare "reads true" — `current_setting` returning
        // `true` proves nothing on its own if `true` is also the default.
        val probed = "allow_unsigned_extensions"

        readSetting(probed, jdbc = emptyMap()) shouldBe "false"
        readSetting(probed, jdbc = mapOf(probed to "true")) shouldBe "true"
    }

    @Test
    fun `DS-SEC-19 - every DuckDB engine setting the refusal set names is refused in both carriers`() {
        // Each key below was proven reachable by the same control-vs-set comparison as the test
        // above; see DialectRefusalSets.DUCKDB for the per-key rationale. Refusing them is what
        // stops a saved datasource from turning `allow_unsigned_extensions=true` plus an attacker
        // `custom_extension_repository` into native-code execution.
        listOf(
            "allow_unsigned_extensions",
            "allow_extensions_metadata_mismatch",
            "allow_parser_override_extension",
            "allow_unredacted_secrets",
            "custom_extension_repository",
            "autoinstall_extension_repository",
            "extension_directory",
            "extension_directories",
            "secret_directory",
            "home_directory",
            "file_search_path",
            "temp_directory",
            "allowed_paths",
        ).forEach { key ->
            withClue("properties.jdbc.$key must be refused for DUCKDB") {
                DatasourceValidator(driverAvailable = { true })
                    .validate(
                        Fixtures.forDialect(Dialect.DUCKDB, properties = DatasourceProperties(jdbc = mapOf(key to "x"))),
                        isCreate = true,
                    ).errors
                    .map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
            }
            withClue("$key smuggled into the DuckDB jdbc_url must be refused too") {
                DialectAdapters
                    .forDialect(Dialect.DUCKDB)
                    .validateJdbcUrl("jdbc:duckdb:/tmp/a.db;$key=x")
                    .errors
                    .single()
                    .code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
            }
        }
    }

    @Test
    fun `DS-SEC-20 - the DuckDB adapter disables extension loading and external access at connect`() {
        // §5.6 v1.8: DuckDB runs IN THE SERVER JVM, so a loaded native extension is arbitrary code
        // in this process. The refusal set cannot reach this vector — DuckDB autoloads known and
        // community extensions with no property involvement — so containment is at connect.
        HARDENED_SETTINGS.forEach { key ->
            withClue("DuckdbDialectAdapter.defaultProperties must disable $key at connect") {
                readSetting(key, jdbc = emptyMap()) shouldBe "false"
            }
        }
        // The exact set, pinned: an addition or removal is a security change and must be reviewed.
        DialectAdapters.forDialect(Dialect.DUCKDB).defaultProperties shouldBe
            HARDENED_SETTINGS.associateWith { "false" }
    }

    @Test
    fun `DS-SEC-20 - the load-bearing settings cannot be re-enabled by author SQL`() {
        // The half that actually matters. A connect-time default that session SQL could flip back
        // would be decorative — this asserts DuckDB refuses the flip while the database is running.
        onHardenedDuckdb { connection ->
            LOCKED_SETTINGS.forEach { key ->
                withClue("author SQL must not be able to re-enable $key") {
                    shouldThrow<SQLException> {
                        connection.createStatement().use { it.execute("SET $key = true") }
                    }
                    connection.readSingleString("SELECT current_setting('$key')") shouldBe "false"
                }
            }
        }
    }

    @Test
    fun `DS-SEC-20 - the two runtime-settable toggles are inert - no load path survives the external-access lock`() {
        // autoload_known_extensions and autoinstall_known_extensions ARE settable at runtime, which
        // is why this test exists: it proves they buy an attacker nothing. `enable_external_access`
        // is the runtime-locked control, and with the filesystem and network off there is no
        // INSTALL / LOAD / ATTACH path left regardless of what the toggles say.
        onHardenedDuckdb { connection ->
            connection.createStatement().use { it.execute("SET autoload_known_extensions = true") }
            connection.createStatement().use { it.execute("SET autoinstall_known_extensions = true") }
            // The flip really did take effect — otherwise this test would prove nothing.
            connection.readSingleString("SELECT current_setting('autoload_known_extensions')") shouldBe "true"
        }
        // Fresh connection per statement: a failed DuckDB query poisons the rest of its connection
        // ("unsuccessful or closed pending query result"), which would otherwise read as a later
        // probe also being blocked when it never actually ran.
        //
        // These three assert the CAUSE, not merely that something threw. Without it the test would
        // pass on a network-less CI box for entirely the wrong reason — `INSTALL httpfs` fails there
        // whether or not the hardening exists. DuckDB names the real reason: "file system operations
        // are disabled by configuration" / "Loading external extensions is disabled through
        // configuration".
        listOf(
            "INSTALL httpfs",
            "LOAD httpfs",
            "ATTACH '/tmp/dp-should-not-open.db' AS other",
        ).forEach { sql ->
            onHardenedDuckdb { connection ->
                connection.createStatement().use { it.execute("SET autoload_known_extensions = true") }
                connection.createStatement().use { it.execute("SET autoinstall_known_extensions = true") }
                withClue("must be blocked BY CONFIGURATION, not incidentally: $sql") {
                    val thrown = shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }
                    thrown.message.orEmpty().lowercase() shouldContain "disabled"
                }
            }
        }
        // These two are blocked as well, but DuckDB's JDBC layer masks the root cause behind
        // "Attempting to execute an unsuccessful or closed pending query result", so there is no
        // message worth asserting — only that author SQL cannot read or write the filesystem.
        listOf(
            "SELECT * FROM read_csv_auto('/etc/hosts')",
            "COPY (SELECT 1 AS a) TO '/tmp/dp-should-not-write.csv'",
        ).forEach { sql ->
            onHardenedDuckdb { connection ->
                withClue("author SQL must not reach the filesystem: $sql") {
                    shouldThrow<SQLException> { connection.createStatement().use { it.execute(sql) } }
                }
            }
        }
    }

    @Test
    fun `DS-SEC-20 - hardening does not break a legitimate DuckDB datasource`() {
        // Over-hardening would be its own defect: a file-backed datasource is the normal production
        // shape, and `enable_external_access=false` must not stop DuckDB opening its OWN database.
        val fileDb = File.createTempFile("dp-duckdb-hardened", ".db").also { it.delete() }
        val datasource = embedded(Dialect.DUCKDB, "duckdb_file_hardened", "jdbc:duckdb:$fileDb")
        try {
            DatasourceValidator().validate(datasource, isCreate = true).errors shouldBe emptyList()
            onDuckdb(datasource) { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE t (n INTEGER)") }
                connection.createStatement().use { it.execute("INSERT INTO t VALUES (7)") }
                connection.readSingleString("SELECT n FROM t") shouldBe "7"
                connection.readSingleString("SELECT current_setting('enable_external_access')") shouldBe "false"
            }
        } finally {
            fileDb.delete()
        }
    }

    @Test
    fun `DS-SEC-20 - an operator cannot override the hardening back to enabled via either carrier`() {
        // properties.jdbc is applied AFTER defaultProperties (§4.2), so before v1.8 a saved
        // datasource carrying `enable_external_access=true` silently reverted the whole defense.
        // Verified live at the time: the override reached the engine and read back `true`.
        HARDENED_SETTINGS.forEach { key ->
            withClue("properties.jdbc.$key must be refused for DUCKDB") {
                DatasourceValidator(driverAvailable = { true })
                    .validate(
                        Fixtures.forDialect(Dialect.DUCKDB, properties = DatasourceProperties(jdbc = mapOf(key to "true"))),
                        isCreate = true,
                    ).errors
                    .map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
            }
            withClue("$key smuggled into the DuckDB jdbc_url must be refused too") {
                DialectAdapters
                    .forDialect(Dialect.DUCKDB)
                    .validateJdbcUrl("jdbc:duckdb:/tmp/a.db;$key=true")
                    .errors
                    .single()
                    .code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
            }
        }
    }

    @Test
    fun `DS-SEC-21 - the SQLite adapter prevents ATTACH at connect`() {
        // SQLite runs IN THE SERVER JVM, so ATTACH DATABASE is a filesystem-access primitive that
        // author SQL must not have. `limit_attached=0` sets SQLITE_LIMIT_ATTACHED to 0, which
        // makes sqlite3_limit refuse every ATTACH.
        onHardenedSqlite { connection ->
            val thrown =
                shouldThrow<SQLException> {
                    connection.createStatement().use {
                        it.execute(
                            "ATTACH DATABASE '/tmp/dp-should-not-open.db' AS other",
                        )
                    }
                }
            thrown.message.orEmpty().lowercase() shouldContain "attached"
        }
    }

    @Test
    fun `DS-SEC-21 - SQLite hardening does not break legitimate usage`() {
        // Over-hardening would be its own defect. `limit_attached=0` must not break
        // create table / insert / select — the engine's own database operations.
        onHardenedSqlite { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE t (n INTEGER)") }
            connection.createStatement().use { it.execute("INSERT INTO t VALUES (7)") }
            connection.readSingleString("SELECT n FROM t") shouldBe "7"
        }
    }

    @Test
    fun `DS-SEC-21 - SQLite adapter defaultProperties prevent extension loading`() {
        // `enable_load_extension=false` prevents LOAD_EXTENSION. The PRAGMA itself is not
        // readable via executeQuery in the xerial JDBC driver, so prove it behaviorally:
        // attempting to load a non-existent extension fails with "not authorized" — NOT
        // with "not found", which would mean the setting was already on.
        onHardenedSqlite { connection ->
            val thrown =
                shouldThrow<SQLException> { connection.createStatement().use { it.execute("SELECT load_extension('nonexistent')") } }
            thrown.message.orEmpty().lowercase() shouldContain "not authorized"
        }
    }

    @Test
    fun `DS-SEC-21 - an operator cannot override SQLite hardening back via either carrier`() {
        // properties.jdbc is applied AFTER defaultProperties (§4.2). Without refusing these keys
        // in the dialect set, a saved datasource carrying `limit_attached=10` would silently
        // re-open the ATTACH surface.
        SQLITE_HARDENED_SETTINGS.forEach { key ->
            withClue("properties.jdbc.$key must be refused for SQLITE") {
                DatasourceValidator(driverAvailable = { true })
                    .validate(
                        Fixtures.forDialect(Dialect.SQLITE, properties = DatasourceProperties(jdbc = mapOf(key to "true"))),
                        isCreate = true,
                    ).errors
                    .map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
            }
            withClue("$key smuggled into the SQLite jdbc_url must be refused too") {
                DialectAdapters
                    .forDialect(Dialect.SQLITE)
                    .validateJdbcUrl("jdbc:sqlite:/tmp/a.db;$key=true")
                    .errors
                    .single()
                    .code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
            }
        }
    }

    @Test
    fun `DS-SEC-21 - SQLite adapter defaultProperties match the expected hardening keys`() {
        val defaults = DialectAdapters.forDialect(Dialect.SQLITE).defaultProperties
        defaults shouldBe
            mapOf(
                "enable_load_extension" to "false",
                "limit_attached" to "0",
            )
    }

    /** Runs [block] against an in-memory connection built through the real, hardened SQLite adapter. */
    private fun onHardenedSqlite(block: (Connection) -> Unit) =
        onSqlite(embedded(Dialect.SQLITE, "sqlite_hardened", "jdbc:sqlite::memory:"), block)

    /** Runs [block] against a fresh pooled connection for [datasource]. */
    private fun onSqlite(
        datasource: Datasource,
        block: (Connection) -> Unit,
    ) {
        ConnectionPoolManager.buildHikariPool(datasource).use { pool -> pool.leaseConnection().use(block) }
    }

    /** Runs [block] against an in-memory connection built through the real, hardened DuckDB adapter. */
    private fun onHardenedDuckdb(block: (Connection) -> Unit) =
        onDuckdb(embedded(Dialect.DUCKDB, "duckdb_hardened", "jdbc:duckdb::memory:"), block)

    /** Runs [block] against a fresh pooled connection for [datasource]. */
    private fun onDuckdb(
        datasource: Datasource,
        block: (Connection) -> Unit,
    ) {
        ConnectionPoolManager.buildHikariPool(datasource).use { pool -> pool.leaseConnection().use(block) }
    }

    /** `current_setting(name)` on a fresh DuckDB connection built with [jdbc] as `properties.jdbc`. */
    private fun readSetting(
        name: String,
        jdbc: Map<String, Any?>,
    ): String {
        val datasource =
            embedded(Dialect.DUCKDB, "duckdb_setting_probe", "jdbc:duckdb::memory:")
                .copy(properties = DatasourceProperties(jdbc = jdbc))
        return ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use { connection -> connection.readSingleString("SELECT current_setting('$name')") }
        }
    }

    /** The first column of the first row of [sql] — the whole shape a settings read needs. */
    private fun Connection.readSingleString(sql: String): String =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    @Test
    fun `maximumPoolSize 1 makes a second concurrent lease time out rather than block forever`() {
        // §5.3: acquisition beyond hikari.connectionTimeout fails; the executor maps that to
        // pipeline.node.datasource_connection_failed. Without this case nothing proved the
        // passthrough pool limits actually reach the running pool.
        val datasource =
            Fixtures.h2(
                name = "pool_limit",
                properties =
                    DatasourceProperties(
                        hikari = mapOf("maximumPoolSize" to 1, "connectionTimeout" to LEASE_TIMEOUT_MS),
                    ),
            )

        ConnectionPoolManager.buildHikariPool(datasource).use { pool ->
            pool.leaseConnection().use {
                val elapsed =
                    measureTimeMillis {
                        val thrown = shouldThrow<SQLTransientConnectionException> { pool.leaseConnection() }
                        thrown.message.orEmpty() shouldContain "pool_limit"
                    }
                // It waited for the configured timeout instead of failing instantly or hanging.
                (elapsed >= LEASE_TIMEOUT_MS) shouldBe true
            }
            // Once the first lease is returned the pool serves again.
            pool.leaseConnection().use { connection: Connection -> connection.isClosed shouldBe false }
        }
    }

    private fun embedded(
        dialect: Dialect,
        name: String,
        url: String,
    ): Datasource =
        Datasource(
            name = name,
            displayName = "Embedded ${dialect.wire}",
            dialect = dialect,
            jdbcUrl = url,
            username = "",
            // §9 requires a password on create even where the engine ignores it: an embedded
            // file/memory database has no authentication, so the value is inert here.
            password = "embedded-no-auth",
        )

    private companion object {
        /** HikariCP's floor for connectionTimeout is 250 ms; anything lower is silently raised. */
        const val LEASE_TIMEOUT_MS = 250L

        /** The §5.6 (v1.8) five that [DuckdbDialectAdapter.defaultProperties] must disable. */
        val HARDENED_SETTINGS =
            listOf(
                "allow_unsigned_extensions",
                "allow_community_extensions",
                "autoload_known_extensions",
                "autoinstall_known_extensions",
                "enable_external_access",
            )

        /**
         * The three of [HARDENED_SETTINGS] DuckDB refuses to change while the database is running.
         * `autoload_known_extensions` / `autoinstall_known_extensions` are deliberately absent —
         * they ARE settable at runtime, and the test above proves they are inert.
         */
        val LOCKED_SETTINGS =
            listOf(
                "allow_unsigned_extensions",
                "allow_community_extensions",
                "enable_external_access",
            )

        /** The §5.6 (v1.9) two that [SqliteDialectAdapter.defaultProperties] must disable. */
        val SQLITE_HARDENED_SETTINGS =
            listOf(
                "enable_load_extension",
                "limit_attached",
            )
    }
}
