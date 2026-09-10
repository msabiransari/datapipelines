package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

/**
 * The LAKE dialect's seat in the per-dialect connectivity suite (datasources.md §13.2, 089 §F):
 * [DialectConnectivityIntegrationTest]'s probe, pointed at a **MinIO** Testcontainer — the lake
 * has no server of its own; S3-compatible object storage is what it connects TO.
 *
 * Two proofs through the adapter's real pool build:
 *
 * 1. `DialectProbe.verifyIntegerColumn` — connect, `SELECT 1`, map the column. For LAKE the
 *    pool build itself is the subject: `catalog.kind: s3` makes `connectionInit` INSTALL/LOAD
 *    `httpfs` + `aws` and create the S3 secret against MinIO's endpoint, so a green probe IS
 *    the httpfs-loaded proof (a failed extension load or a refused secret fails the lease).
 * 2. A registered Parquet table round trip — the same `LakeViewStatements` path phase B ships,
 *    one tiny file uploaded in `@BeforeAll`, read through the pool as a view over `s3://`.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LakeConnectivityIntegrationTest {
    @Test
    fun `lake connects over httpfs and maps an integer column`() {
        DialectProbe.verifyIntegerColumn(
            datasource = lakeDatasource(),
            integerQuery = "SELECT CAST(1 AS INTEGER) AS n",
        )
    }

    @Test
    fun `a registered parquet table reads through the pool as a view over s3`() {
        withViewPool(parquetViews()) { statement ->
            statement.executeQuery("SELECT count(*) FROM probe_trips").use { rs ->
                rs.next() shouldBe true
                rs.getInt(1) shouldBe PROBE_ROWS
            }
            // The hive_partitioning clause is harmless on a plain file (the
            // LakeViewStatements KDoc's pinned verification), and the value read
            // back is the value written.
            statement.executeQuery("SELECT label FROM probe_trips WHERE id = 1").use { rs ->
                rs.next() shouldBe true
                rs.getString(1) shouldBe "odd"
            }
        }
    }

    @Test
    fun `a registered iceberg table reads through the pool under catalog kind s3`() {
        // The 089 §F fix's module-level proof: catalog.kind s3 makes the ADAPTER load
        // httpfs+aws only; LakeViewStatements prepends the iceberg loads because the registry
        // row demands them. The fixture is the checked-in copy (this module's resources — see
        // its README); its metadata believes it lives at s3://dp-lake-it/iceberg/trips_iceberg,
        // which is exactly where @BeforeAll uploaded it.
        //
        // The location is the current METADATA FILE, not the table root: DuckDB 1.5.5.1
        // resolves a root through version-hint.text by CONSTRUCTING `v<n>.metadata.json` /
        // `<n>.metadata.json` names, which never match the spec's `%05d-<uuid>.metadata.json`
        // pyiceberg writes (measured 2026-09-08 against the pinned jar).
        withViewPool(icebergViews()) { statement ->
            statement
                .executeQuery(
                    "SELECT count(*), count(DISTINCT pickup_date) FROM probe_trips_iceberg",
                ).use { rs ->
                    rs.next() shouldBe true
                    assertAll(
                        { rs.getInt(1) shouldBe ICEBERG_ROWS },
                        { rs.getInt(2) shouldBe 2 },
                    )
                }
        }
    }

    // ------------------------------- the LISTING probe (109 §B)

    /**
     * The real registry's `testConnection` — the probe the Test button and `datasources_test`
     * run — over a Postgres-backed repository (the module's SharedPostgres), so the §8.1B
     * outcome write is exercised too. [tables] is what the registry's lake catalog answers.
     * Truncates `datasources` and seeds the `created_by` user the insert's FK needs, the
     * registry suite's own discipline. Returns the registry and the seeded owner.
     */
    private fun registryWithTables(vararg tables: LakeRegisteredTable): Pair<DefaultDatasourceRegistry, UUID> {
        val template = JdbcTemplate(SharedPostgres.pooledDataSource())
        template.execute("TRUNCATE datasources, users CASCADE")
        val owner =
            checkNotNull(
                template.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject) " +
                        "VALUES ('lake-it@example.com', 'Lake IT', 'google', 'sub-lake-it') RETURNING id",
                    UUID::class.java,
                ),
            )
        val registry =
            DefaultDatasourceRegistry(
                DatasourceRepository(NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())),
                testEncryptor(),
                lakeTables = LakeTableCatalog { tables.toList() },
            )
        return registry to owner
    }

    @Test
    fun `the lake probe LISTS the registered root - a listing-capable credential connects`() {
        val (registry, owner) =
            registryWithTables(
                LakeRegisteredTable(listOf("it"), "probe_trips", "parquet", "s3://$BUCKET/probe/trips.parquet"),
            )
        registry.save(lakeDatasource(), owner)

        val result = registry.testConnection("lake_it").shouldNotBeNull()

        // The glob LIST of s3://dp-lake-it/probe/ ran through the probe's own connection and
        // succeeded — the minioadmin credential may list.
        result.connected shouldBe true
        result.serverVersion.shouldNotBeNull()
    }

    @Test
    fun `a bucket that allows GET but refuses LIST fails the probe with the s3-ListBucket hint`() {
        // The T176 shape, reproduced exactly: the bucket policy grants anonymous object reads
        // (s3:GetObject) but not s3:ListBucket — the datasource is unsigned, a direct object
        // GET would succeed, and the OLD probe (a metadata version read) reported connected.
        // The 109 §B probe LISTS, so it must fail with the engine's 403 plus the one-line
        // grant-ListBucket hint.
        val client = checkNotNull(s3)
        client.createBucket { it.bucket(PUBLIC_BUCKET) }
        client.putObject(
            { it.bucket(PUBLIC_BUCKET).key("probe/trips.parquet") },
            RequestBody.fromString("placeholder bytes; the probe never GETs"),
        )
        client.putBucketPolicy(
            software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
                .builder()
                .bucket(PUBLIC_BUCKET)
                .policy(
                    """
                    {"Version":"2012-10-17","Statement":[
                       {"Effect":"Allow","Principal":"*","Action":"s3:GetObject",
                        "Resource":"arn:aws:s3:::$PUBLIC_BUCKET/*"}]}
                    """.trimIndent(),
                ).build(),
        )
        val (registry, owner) =
            registryWithTables(
                LakeRegisteredTable(listOf("it"), "probe_trips", "parquet", "s3://$PUBLIC_BUCKET/probe/trips.parquet"),
            )
        registry.save(
            lakeDatasource().copy(
                name = "lake_public",
                username = null,
                credentialKind = CredentialKind.NONE,
                secret = null,
                properties =
                    DatasourceProperties(
                        dialect =
                            mapOf(
                                "catalog.kind" to "s3",
                                "region" to "us-east-1",
                                "endpoint" to "localhost:${minio.getMappedPort(MINIO_PORT)}",
                                "url_style" to "path",
                                "unsigned" to "true",
                            ),
                    ),
            ),
            owner,
        )

        val result = registry.testConnection("lake_public").shouldNotBeNull()

        assertAll(
            { result.connected shouldBe false },
            { result.error.shouldNotBeNull().shouldContain("s3:ListBucket") },
        )
    }

    private fun parquetViews(): List<String> =
        viewStatements(
            LakeRegisteredTable(
                namespace = listOf("it"),
                name = "probe_trips",
                format = "parquet",
                location = "s3://$BUCKET/probe/trips.parquet",
            ),
        )

    private fun icebergViews(): List<String> =
        viewStatements(
            LakeRegisteredTable(
                namespace = listOf("it"),
                name = "probe_trips_iceberg",
                format = "iceberg",
                location = "s3://$BUCKET/iceberg/trips_iceberg/metadata/$icebergMetadataFile",
            ),
        )

    private fun viewStatements(table: LakeRegisteredTable): List<String> =
        LakeViewStatements.forTables(listOf(table), DialectAdapters.forDialect(Dialect.LAKE))

    /** A real pool carrying [views], one leased connection, one statement. */
    private fun withViewPool(
        views: List<String>,
        block: (java.sql.Statement) -> Unit,
    ) {
        co.datapipelines.datasources.pooling.ConnectionPoolManager
            .buildHikariPool(lakeDatasource(), views)
            .use { pool ->
                pool.leaseConnection().use { connection ->
                    connection.createStatement().use(block)
                }
            }
    }

    private fun lakeDatasource() =
        Datasource(
            name = "lake_it",
            displayName = "Lake IT",
            dialect = Dialect.LAKE,
            jdbcUrl = "jdbc:duckdb::memory:",
            username = MINIO_USER,
            credentialKind = CredentialKind.PASSWORD,
            secret = MINIO_PASSWORD,
            properties =
                DatasourceProperties(
                    dialect =
                        mapOf(
                            "catalog.kind" to "s3",
                            "region" to "us-east-1",
                            "endpoint" to "localhost:${minio.getMappedPort(MINIO_PORT)}",
                            "url_style" to "path",
                        ),
                ),
        )

    private companion object {
        private const val BUCKET = "dp-lake-it"
        private const val PUBLIC_BUCKET = "dp-lake-it-public"
        private const val MINIO_PORT = 9000
        private const val MINIO_USER = "minioadmin"
        private const val MINIO_PASSWORD = "minioadmin"
        private const val PROBE_ROWS = 4
        private const val ICEBERG_ROWS = 2000

        /**
         * The current stable RELEASE tag on Docker Hub (verified 2026-09-08), pinned exactly —
         * the same image tests/integration-tests' MinIO suite uses; each module pins its own
         * because test sources do not cross module bounds.
         */
        private const val MINIO_IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))

        private var s3: S3Client? = null

        /** The fixture's current metadata file name, discovered by [seedBucket]. */
        private lateinit var icebergMetadataFile: String

        /** One tiny parquet, written with the app's own pinned DuckDB JDBC and uploaded. */
        @BeforeAll
        @JvmStatic
        fun seedBucket() {
            val dir = Files.createTempDirectory("lake-connectivity-it")
            val parquet = dir.resolve("trips.parquet")
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "COPY (SELECT i AS id, CASE WHEN i % 2 = 0 THEN 'even' ELSE 'odd' END AS label " +
                            "FROM range(0, $PROBE_ROWS) tbl(i)) TO '${parquet.toAbsolutePath()}' (FORMAT PARQUET)",
                    )
                }
            }
            val client =
                S3Client
                    .builder()
                    .endpointOverride(URI.create("http://localhost:${minio.getMappedPort(MINIO_PORT)}"))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(MINIO_USER, MINIO_PASSWORD)),
                    ).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .httpClient(
                        software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
                            .builder()
                            .build(),
                    ).build()
            s3 = client
            client.createBucket { it.bucket(BUCKET) }
            client.putObject(
                { it.bucket(BUCKET).key("probe/trips.parquet") },
                RequestBody.fromFile(parquet),
            )
            // The checked-in Iceberg fixture, uploaded key-for-key (its metadata records
            // absolute s3://dp-lake-it/… URIs — the bucket name is load-bearing).
            val fixture =
                Path.of(
                    checkNotNull(LakeConnectivityIntegrationTest::class.java.getResource("/lake-fixture/iceberg")).toURI(),
                )
            Files.walk(fixture).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        client.putObject(
                            { it.bucket(BUCKET).key("iceberg/${fixture.relativize(file)}") },
                            RequestBody.fromFile(file),
                        )
                    }
            }
            // The current metadata file — the zero-padded `%05d` prefix makes the
            // lexicographically last metadata.json the newest.
            icebergMetadataFile =
                Files
                    .list(fixture.resolve("trips_iceberg/metadata"))
                    .use { paths ->
                        paths
                            .map { it.fileName.toString() }
                            .filter { it.endsWith(".metadata.json") }
                            .max(Comparator.naturalOrder())
                            .orElse(null)
                    } ?: error("the lake-fixture Iceberg table carries no metadata file")
            dir.toFile().deleteRecursively()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            s3?.close()
        }
    }
}
