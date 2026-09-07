package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager

/**
 * The LAKE dialect and its connect-time setup (datasources.md §4.1/§4.2A, round 087).
 *
 * ## What is proven live, and what is only generated
 *
 * The LOCAL path is proven end to end against a real pool: a LAKE datasource whose
 * `properties.dialect.attach` names two DuckDB files opens, runs the adapter's `connectionInit`
 * through HikariCP's `connectionInitSql`, and reports both catalogs. That single test carries
 * three claims at once — the lock difference is real (the same datasource on `DUCKDB` cannot
 * `ATTACH`), `connectionInitSql` is genuinely wired (it was unreferenced before 087), and the
 * two-level namespace shape is what the engine actually reports.
 *
 * The S3 path is asserted at the level of the SQL the adapter GENERATES, not against a bucket.
 * That is deliberate and stated in the round's report: a real S3 needs cloud credentials a lane
 * must not have, and `INSTALL httpfs` needs egress to DuckDB's extension repository — whether
 * that works from the app's container, and whether the three extensions should be bundled into
 * the image instead, is the spike the warehouse-and-lake design note §7.3 names. Generating the
 * statements is this round's job; running them against a bucket is the lake connector's.
 */
class LakeDialectAdapterTest {
    @TempDir
    lateinit var tempDir: File

    private val lake = DialectAdapters.forDialect(Dialect.LAKE)
    private val embedded = DialectAdapters.forDialect(Dialect.DUCKDB)

    @Test
    fun `a local ATTACH lake opens through the real pool and reports both catalogs`() {
        seedDuckDbFile("one", "one_col")
        seedDuckDbFile("two", "two_col")

        // The REAL pool: HikariCP built by the adapter, connectionInitSql included.
        HikariDataSource(lake.buildHikariConfig(attachLake())).use { pool ->
            pool.connection.use { connection ->
                withClue("connectionInitSql did not run — the ATTACHes are missing") {
                    catalogsOf(connection).containsAll(listOf("a1", "a2")) shouldBe true
                }
                // And the data is genuinely readable through the attached catalogs.
                countOf(connection, "SELECT count(*) FROM a1.sales.orders") shouldBe 0
            }
        }
    }

    private fun catalogsOf(connection: java.sql.Connection): List<String> =
        connection.metaData.catalogs.use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }

    private fun countOf(
        connection: java.sql.Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next() shouldBe true
                rs.getInt(1)
            }
        }

    @Test
    fun `the SAME datasource on the embedded DUCKDB adapter cannot ATTACH - the lock is the whole difference`() {
        seedDuckDbFile("one", "one_col")
        // The falsification anchor for the test above: `enable_external_access = false` is a
        // RUNTIME lock, so the embedded adapter physically cannot do what the lake adapter does.
        // If this ever stops throwing, the embedded hardening has regressed and the lake
        // adapter's reason to exist has gone with it.
        val failure =
            runCatching {
                DriverManager
                    .getConnection("jdbc:duckdb:", java.util.Properties().apply { setProperty("enable_external_access", "false") })
                    .use { connection ->
                        connection.createStatement().use { it.execute("ATTACH '${tempDir.resolve("one.db")}' AS a1 (READ_ONLY)") }
                    }
            }.exceptionOrNull()

        withClue("duckdb no longer refuses ATTACH under enable_external_access=false") {
            failure?.message.orEmpty() shouldContain "file system operations are disabled"
        }
        assertAll(
            // The lake adapter does NOT set it; the embedded one does. Nothing else differs in
            // the five, and neither adapter lets `properties.jdbc` set any of them (§5.6).
            { embedded.defaultProperties["enable_external_access"] shouldBe "false" },
            { lake.defaultProperties["enable_external_access"].shouldBeNull() },
            { lake.defaultProperties["allow_unsigned_extensions"] shouldBe "false" },
            { lake.defaultProperties["allow_community_extensions"] shouldBe "false" },
            // Refusal sets are IDENTICAL, `enable_external_access` included: the lake adapter
            // opens the setting by not defaulting it, never by letting row data set it.
            { RefusedPropertyKeys.forDialect(Dialect.LAKE) shouldBe RefusedPropertyKeys.forDialect(Dialect.DUCKDB) },
            { RefusedPropertyKeys.isRefused("enable_external_access", RefusedPropertyKeys.forDialect(Dialect.LAKE)) shouldBe true },
        )
    }

    @Test
    fun `a local lake generates NO extension load and NO secret - an air-gapped deployment stays connectable`() {
        // `INSTALL httpfs` would need egress to DuckDB's extension repository, and `TYPE s3`
        // needs httpfs loaded. Emitting either for a lake over paths the process can already
        // reach would turn a working air-gapped configuration into a connect failure.
        lake.connectionInit(attachLake()) shouldContainExactly
            listOf(
                "ATTACH '${tempDir.resolve("one.db")}' AS \"a1\" (READ_ONLY)",
                "ATTACH '${tempDir.resolve("two.db")}' AS \"a2\" (READ_ONLY)",
            )
    }

    @Test
    fun `an S3 lake with the IAM chain loads httpfs and aws and creates a credential-chain secret`() {
        val init =
            lake.connectionInit(
                lakeDatasource(
                    credentialKind = CredentialKind.NONE,
                    dialectProperties = mapOf("catalog.kind" to "s3", "region" to "us-east-1"),
                ),
            )

        init shouldContainExactly
            listOf(
                "INSTALL httpfs",
                "LOAD httpfs",
                "INSTALL aws",
                "LOAD aws",
                "CREATE OR REPLACE SECRET dp_lake (TYPE s3, PROVIDER credential_chain, REGION 'us-east-1')",
            )
    }

    @Test
    fun `an Iceberg catalog kind additionally loads iceberg`() {
        val init =
            lake.connectionInit(
                lakeDatasource(
                    credentialKind = CredentialKind.NONE,
                    dialectProperties = mapOf("catalog.kind" to "glue", "catalog.ref" to "123456789012"),
                ),
            )

        init.filter { it.startsWith("LOAD") } shouldContainExactly listOf("LOAD httpfs", "LOAD aws", "LOAD iceberg")
    }

    @Test
    fun `an explicit key pair travels in the credential, quote-escaped, and never in properties`() {
        val init =
            lake.connectionInit(
                lakeDatasource(
                    credentialKind = CredentialKind.PASSWORD,
                    username = "AKIAEXAMPLE",
                    secret = "s3cr3t'with''quotes",
                    dialectProperties = mapOf("catalog.kind" to "s3", "endpoint" to "minio:9000", "url_style" to "path"),
                ),
            )

        val secret = init.single { it.startsWith("CREATE OR REPLACE SECRET") }
        assertAll(
            { secret shouldContain "KEY_ID 'AKIAEXAMPLE'" },
            // Single quotes doubled: the ONE interpolation this adapter does, escaped at it.
            { secret shouldContain "SECRET 's3cr3t''with''''quotes'" },
            { secret shouldContain "ENDPOINT 'minio:9000'" },
            { secret shouldContain "URL_STYLE 'path'" },
            // A declared endpoint is a deliberate non-AWS target (MinIO, on-prem), which is
            // plain HTTP in every deployment of it that exists.
            { secret shouldContain "USE_SSL false" },
            { secret shouldNotContain "credential_chain" },
        )
    }

    @Test
    fun `the pool config carries the init statements joined into HikariCP's single slot`() {
        val config = lake.buildHikariConfig(attachLake())

        config.connectionInitSql shouldBe
            "ATTACH '${tempDir.resolve("one.db")}' AS \"a1\" (READ_ONLY); " +
            "ATTACH '${tempDir.resolve("two.db")}' AS \"a2\" (READ_ONLY)"
    }

    @Test
    fun `an unknown or ill-valued dialect key is refused, naming what is accepted`() {
        val validator = DatasourceValidator()

        val unknown = validator.validate(lakeDatasource(dialectProperties = mapOf("catalogue.kind" to "s3")), isCreate = true)
        val badEnum = validator.validate(lakeDatasource(dialectProperties = mapOf("catalog.kind" to "hdfs")), isCreate = true)

        assertAll(
            { unknown.errors.single { it.field == "properties.dialect.catalogue.kind" }.message shouldContain "accepted keys are" },
            { badEnum.errors.single { it.field == "properties.dialect.catalog.kind" }.message shouldContain "must be one of" },
            { validator.validate(lakeDatasource(dialectProperties = mapOf("catalog.kind" to "s3")), isCreate = true).valid shouldBe true },
        )
    }

    @Test
    fun `every OTHER dialect refuses the whole dialect namespace rather than ignoring it`() {
        // The default implementation, and the point of it: a dialect gains `dialect.*` keys by
        // DECLARING them. Silently accepting an unrecognized key would let a typo look like a
        // working setting — the exact failure this typed namespace exists to avoid.
        DialectAdapters.all().filter { it.dialect != Dialect.LAKE }.forEach { adapter ->
            withClue("${adapter.dialect.wire} silently accepted a dialect.* key") {
                adapter.validateDialectProperties(mapOf("warehouse" to "wh")).valid shouldBe false
            }
        }
        // …and every one of them generates no connect-time SQL, so the pool build is unchanged.
        DialectAdapters.all().filter { it.dialect != Dialect.LAKE }.forEach { adapter ->
            adapter.connectionInit(Fixtures.forDialect(adapter.dialect)) shouldBe emptyList()
        }
    }

    @Test
    fun `LAKE takes the IAM chain or a key pair, and refuses a token`() {
        assertAll(
            { lake.supportedCredentialKinds shouldBe setOf(CredentialKind.NONE, CredentialKind.PASSWORD) },
            { lake.namespaceShape shouldBe NamespaceShape.CATALOG_AND_SCHEMA },
            // Two BROWSABLE levels here, one on the embedded twin — the lock is why.
            { lake.namespaceShape.levels shouldBe 2 },
            { embedded.namespaceShape.levels shouldBe 1 },
        )
    }

    private fun attachLake(): Datasource =
        lakeDatasource(
            dialectProperties =
                mapOf("attach" to "a1=${tempDir.resolve("one.db")}, a2=${tempDir.resolve("two.db")}"),
        )

    private fun lakeDatasource(
        credentialKind: CredentialKind = CredentialKind.NONE,
        username: String? = null,
        secret: String? = null,
        dialectProperties: Map<String, Any?> = emptyMap(),
    ): Datasource =
        Datasource(
            name = "lake_ds",
            displayName = "Lake",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            username = username,
            credentialKind = credentialKind,
            secret = secret,
            properties = DatasourceProperties(dialect = dialectProperties),
        )

    private fun seedDuckDbFile(
        file: String,
        column: String,
    ) = DriverManager.getConnection("jdbc:duckdb:${tempDir.resolve("$file.db")}").use { seed ->
        seed.createStatement().use {
            it.execute("CREATE SCHEMA sales")
            it.execute("CREATE TABLE sales.orders(id INTEGER, $column VARCHAR)")
        }
    }

    @Test
    fun `a lake reads a local PARQUET file through the real pool, which the embedded adapter cannot`() {
        // The prompt's fixture: a file-backed Parquet read, not a real S3. DuckDB writes the file
        // through a plain connection (no lock), and the LAKE pool then reads it with
        // `read_parquet` — the shape a lake query takes once the registry rewrites a registered
        // table to it. The embedded adapter cannot even open it: `enable_external_access = false`
        // closes the filesystem, which is the whole reason the two adapters are two dialects.
        val parquet = tempDir.resolve("orders.parquet")
        DriverManager.getConnection("jdbc:duckdb:").use { writer ->
            writer.createStatement().use {
                it.execute("COPY (SELECT 1 AS id, 'a' AS label UNION ALL SELECT 2, 'b') TO '$parquet' (FORMAT PARQUET)")
            }
        }

        HikariDataSource(lake.buildHikariConfig(lakeDatasource())).use { pool ->
            pool.connection.use { connection ->
                countOf(connection, "SELECT count(*) FROM read_parquet('$parquet')") shouldBe 2
            }
        }

        // …and the same read under the embedded adapter's lock is refused.
        val refused =
            runCatching {
                HikariDataSource(embedded.buildHikariConfig(embeddedDuckDb())).use { pool ->
                    pool.connection.use { connection -> countOf(connection, "SELECT count(*) FROM read_parquet('$parquet')") }
                }
            }.exceptionOrNull()
        withClue("the embedded DuckDB adapter read a local Parquet file — its external-access lock has regressed") {
            refused shouldNotBe null
        }
    }

    private fun embeddedDuckDb(): Datasource =
        Datasource(
            name = "embedded_ds",
            displayName = "Embedded DuckDB",
            dialect = Dialect.DUCKDB,
            jdbcUrl = "jdbc:duckdb::memory:",
            credentialKind = CredentialKind.NONE,
        )
}
