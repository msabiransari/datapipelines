package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * 089 §F (R-L3): the dp-lake read path end to end against a real S3-compatible object store —
 * a **MinIO** Testcontainer, the first S3 container in this repo — driven entirely through the
 * REAL app's REST surface: register a LAKE datasource, register a day-partitioned Parquet table
 * AND an Iceberg table, introspect through the registry, execute DQL nodes over the views, and
 * prove partition pruning mechanically.
 *
 * ## The fixtures
 *
 * - **Parquet** (`parquet/trips/pickup_date=<day>/data_0.parquet`, three days x 1 500 rows):
 *   generated in `@BeforeAll` with the app's OWN pinned DuckDB JDBC
 *   (`COPY … TO … (FORMAT PARQUET, PARTITION_BY (pickup_date))`) into a temp dir and uploaded
 *   with the awssdk S3 client (test-scoped — see this module's build.gradle.kts). Generating at
 *   test time keeps the layout honest against the engine version under test; the content is a
 *   pure function of the row index (`fare = (id % 97) + 0.5`, `company` alternating
 *   `acme`/`globex`), so every assertion below is exact.
 * - **Iceberg** (`iceberg/trips_iceberg`, 2 000 rows over two days, ~56 KB): the CHECKED-IN
 *   fixture `src/test/resources/lake-fixture/` — checked in because an Iceberg table records
 *   ABSOLUTE locations in its metadata and must be built already believing it lives at
 *   `s3://dp-lake-it/iceberg/trips_iceberg` (the measured `allow_moved_paths` trap; see the
 *   fixture's README and `scripts/sample-data-lake/lib/iceberg_write.py`'s header). Building it
 *   needs pyiceberg, which a test JVM cannot assume. The BUCKET NAME `dp-lake-it` is therefore
 *   load-bearing: it is baked into the fixture's metadata URIs.
 *
 * ## The Iceberg half is also the §F extension fix's end-to-end proof
 *
 * `catalog.kind: s3` makes the adapter load `httpfs` + `aws` only — before the §F fix, a
 * registered `format=iceberg` table's `iceberg_scan` view failed the pool build at connect
 * (the phase-4 live finding). `LakeViewStatements` now prepends `INSTALL/LOAD iceberg` whenever
 * the registry holds an Iceberg table, so the `trips_iceberg` flow below goes red without it.
 *
 * ## The pruning proof
 *
 * DuckDB 1.5.5.1's `EXPLAIN ANALYZE` reports, on the `TABLE_SCAN` operator of a
 * `read_parquet … hive_partitioning = true` scan, the lines `Scanning Files: k/n` and
 * `Total Files Read: k` (verified 2026-09-08 against the pinned jar — a `pickup_date =` key
 * predicate on a three-partition hive layout prints `Scanning Files: 1/3` +
 * `Total Files Read: 1` and names the single file it opened; the same aggregate with no
 * predicate prints `Total Files Read: 3`). Both queries run as DQL nodes through the real
 * pipeline surface, and the suite asserts those exact counts from the returned plan text —
 * files, not a proxy.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LakeMinioE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `register and introspect - the namespace shows and the columns come through the views`() {
        seedAuthRows()
        createLakeDatasource()
        registerTable(
            name = "trips",
            format = "parquet",
            location = "s3://$BUCKET/parquet/trips/pickup_date=*/data_*.parquet",
            partitionColumn = "pickup_date",
        )
        registerTable(
            name = "trips_iceberg",
            format = "iceberg",
            // The current METADATA FILE, not the table root: DuckDB 1.5.5.1 resolves a root
            // location through version-hint.text by CONSTRUCTING `v<n>.metadata.json` /
            // `<n>.metadata.json` filenames, which never match the Iceberg spec's
            // `%05d-<uuid>.metadata.json` names pyiceberg writes (measured 2026-09-08 against
            // the pinned jar — root, root+version, and every version_name_format glob all
            // fail; only the explicit metadata file scans). The newest metadata file is
            // discovered at seed time so a regenerated fixture needs no test edit.
            location = "s3://$BUCKET/iceberg/trips_iceberg/metadata/$icebergMetadataFile",
            partitionColumn = null,
        )

        // Schemas = the registry's distinct namespaces (089 §C), both wire projections.
        val schemas =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/$DS/schemas")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        schemas.getList<String>("data.schemas") shouldContainExactly listOf("lake")
        schemas
            .getList<Map<String, Any?>>("data.entries")
            .single()["namespace"] shouldBe listOf("test", "lake")

        // Tables in the namespace: the two registered rows, reported as VIEWs with the
        // registry's format in remarks.
        val tables =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .queryParam("namespace", "test.lake")
                .`when`()
                .get("/api/v1/datasources/$DS/tables")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        val byName = tables.getList<Map<String, Any?>>("data.tables").associateBy { it["name"] as String }
        byName.keys shouldBe setOf("trips", "trips_iceberg")
        byName.getValue("trips")["type"] shouldBe "VIEW"
        byName.getValue("trips")["remarks"] shouldBe "parquet"
        byName.getValue("trips")["namespace"] shouldBe listOf("test", "lake")
        byName.getValue("trips_iceberg")["remarks"] shouldBe "iceberg"

        // Columns come through the VIEW (a zero-row scan over S3), for both formats. The
        // parquet view's hive_partitioning moves the partition column LAST (DuckDB appends
        // hive keys after the file's own columns — pinned, not incidental); the Iceberg scan
        // keeps the table's declared order.
        columnNames("trips") shouldContainExactly listOf("id", "fare", "company", "pickup_date")
        columnNames("trips_iceberg") shouldContainExactly listOf("id", "pickup_date", "fare", "company")
    }

    @Test
    @Order(2)
    fun `a DQL over the parquet view reads the bare name through the search path`() {
        val pipelineId =
            createTemplateAndPipeline(
                suffix = "parquet_aggregate",
                sql =
                    "SELECT count(*) AS n, count(DISTINCT pickup_date) AS days, " +
                        "min(fare) AS min_fare, max(fare) AS max_fare FROM trips",
            )
        val row = executeAndFetchRows(pipelineId).single()
        // count(*) is BIGINT — serialized as a string on the wire (the BIGINTEGER rule).
        row[0].toString() shouldBe "4500"
        row[1].toString() shouldBe "3"
        row[2].toString() shouldBe "0.5"
        row[3].toString() shouldBe "96.5"
    }

    @Test
    @Order(3)
    fun `a DQL over the iceberg view works under catalog kind s3 - the 089 F extension fix live`() {
        val pipelineId =
            createTemplateAndPipeline(
                suffix = "iceberg_aggregate",
                sql =
                    "SELECT count(*) AS n, count(DISTINCT pickup_date) AS days, " +
                        "sum(CASE WHEN company = 'acme' THEN 1 ELSE 0 END) AS acme FROM trips_iceberg",
            )
        val row = executeAndFetchRows(pipelineId).single()
        row[0].toString() shouldBe "2000"
        row[1].toString() shouldBe "2"
        row[2].toString() shouldBe "1000"
    }

    @Test
    @Order(4)
    fun `a partition predicate prunes to exactly one file - the bare scan reads all three`() {
        val pruned =
            createTemplateAndPipeline(
                suffix = "explain_pruned",
                sql = "EXPLAIN ANALYZE SELECT sum(fare) FROM trips WHERE pickup_date = DATE '2026-01-02'",
            )
        val prunedPlan = executeAndFetchRows(pruned).flatten().joinToString("\n") { it.toString() }
        // The file counts only: DuckDB's box-drawing WRAPS the Filename(s) line mid-string
        // ("…/pickup_date=2026-01\n-02/data_0.parquet"), so no filename substring is stable —
        // `Scanning Files: 1/3` and `Total Files Read: 1` are the mechanical proof, and the
        // 1,500-row scan cardinality (vs 4,500 below) corroborates.
        prunedPlan shouldContain "Scanning Files: 1/3"
        prunedPlan shouldContain "Total Files Read: 1"
        prunedPlan shouldContain "1,500 rows"

        val full =
            createTemplateAndPipeline(
                suffix = "explain_full",
                sql = "EXPLAIN ANALYZE SELECT sum(fare) FROM trips",
            )
        val fullPlan = executeAndFetchRows(full).flatten().joinToString("\n") { it.toString() }
        fullPlan shouldContain "Total Files Read: 3"
    }

    // ------------------------------------------------------------------ REST helpers

    private fun columnNames(table: String): List<String> =
        given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .queryParam("namespace", "test.lake")
            .`when`()
            .get("/api/v1/datasources/$DS/tables/$table/columns")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.name")

    private fun createLakeDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$DS", "display_name": "IT Lake (MinIO)", "dialect": "LAKE",
                 "jdbc_url": "jdbc:duckdb::memory:", "readonly": true,
                 "credential": {"kind": "password", "username": "$MINIO_USER", "secret": "$MINIO_PASSWORD"},
                 "properties": {"dialect": {"catalog.kind": "s3", "region": "us-east-1",
                   "endpoint": "localhost:${minio.getMappedPort(MINIO_PORT)}", "url_style": "path"}}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun registerTable(
        name: String,
        format: String,
        location: String,
        partitionColumn: String?,
    ) {
        val partition = partitionColumn?.let { """, "partition_column": "$it"""" } ?: ""
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"namespace": ["test", "lake"], "name": "$name", "format": "$format",
                 "location": "$location"$partition}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources/$DS/tables")
            .then()
            .statusCode(201)
    }

    /** One template + one single-node DQL pipeline reading it; returns the pipeline id. */
    private fun createTemplateAndPipeline(
        suffix: String,
        sql: String,
    ): String {
        val templateId = "test/lake_it_$suffix.sql"
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$templateId", "dialect": "LAKE", "display_name": "Lake IT $suffix",
                 "description": "089 F MinIO suite node", "imports": [],
                 "body": ${mapper.writeValueAsString(sql)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "test/lake_it_$suffix", "nodes": [{
                    "id": "read_lake", "description": "089 F MinIO suite node",
                    "type": "DQL", "source": "$DS",
                    "template": {"id": "$templateId", "version": 1},
                    "output": {"target": "caller"}, "depends_on": []}]}
                """.trimIndent().replace("\n", " "),
            ).`when`()
            .post("/api/v1/pipelines")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")
    }

    /** Executes the pipeline to end-of-stream and returns its result rows. */
    private fun executeAndFetchRows(pipelineId: String): List<List<Any?>> {
        val events =
            assertTimeoutPreemptively(EXECUTION_BUDGET) {
                val request =
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                        .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                        .header("DP-Correlation-Id", UUID.randomUUID().toString())
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                        .build()
                val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                response.statusCode() shouldBe 200
                E2eSse.parseEvents(response.body(), mapper)
            }
        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        val executionId = events.first().second["execution_id"].asText()
        return given()
            .port(port)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$executionId/result")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .get("data.rows")
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DS = "it_lake_minio"
        private const val BUCKET = "dp-lake-it"
        private const val MINIO_PORT = 9000
        private const val MINIO_USER = "minioadmin"
        private const val MINIO_PASSWORD = "minioadmin"

        /** The current stable RELEASE tag on Docker Hub (verified 2026-09-08), pinned exactly. */
        private const val MINIO_IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z"

        /** The first DQL builds the pool — extension INSTALLs on a cold extension cache. */
        private val EXECUTION_BUDGET: Duration = Duration.ofSeconds(180)

        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000089"
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-lake-it-key", arrayOf("read", "execute", "author"))
        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))

        @Container
        @JvmStatic
        val minio: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))

        private val oidc = OidcDiscoveryStub()

        private var staging: Path? = null
        private var s3: S3Client? = null

        /** The fixture's current metadata file name, discovered by [seedBucket]. */
        private lateinit var icebergMetadataFile: String

        /**
         * Builds the bucket contents: the generated day-partitioned Parquet set and the
         * checked-in Iceberg fixture, uploaded key-for-key (the bucket's key space mirrors the
         * fixture directories exactly — the Iceberg metadata's absolute `s3://dp-lake-it/…`
         * URIs resolve by construction).
         */
        @BeforeAll
        @JvmStatic
        fun seedBucket() {
            val dir = Files.createTempDirectory("lake-minio-it")
            staging = dir
            generatePartitionedParquet(dir.resolve("parquet/trips"))

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
            uploadTree(client, dir.resolve("parquet"), "parquet")
            val fixture =
                Path.of(
                    checkNotNull(LakeMinioE2eTest::class.java.getResource("/lake-fixture/iceberg")).toURI(),
                )
            uploadTree(client, fixture, "iceberg")
            // The current metadata file's name (see the registration site's comment for why the
            // location is the file, not the root): the zero-padded `%05d` prefix makes the
            // lexicographically last metadata.json the newest.
            icebergMetadataFile =
                Files
                    .list(fixture.resolve("trips_iceberg/metadata"))
                    .use { paths ->
                        paths
                            .map { it.name }
                            .filter { it.endsWith(".metadata.json") }
                            .max(Comparator.naturalOrder())
                            .orElse(null)
                    } ?: error("the lake-fixture Iceberg table carries no metadata file")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            s3?.close()
            staging?.toFile()?.deleteRecursively()
            oidc.close()
        }

        /** Three day partitions x 1 500 rows through the app's own pinned DuckDB JDBC. */
        private fun generatePartitionedParquet(target: Path) {
            Files.createDirectories(target)
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE trips AS
                        SELECT i AS id,
                               CAST('2026-01-0' || (1 + (i % 3)) AS DATE) AS pickup_date,
                               CAST((i % 97) + 0.5 AS DOUBLE) AS fare,
                               CASE WHEN i % 2 = 0 THEN 'acme' ELSE 'globex' END AS company
                        FROM range(0, 4500) tbl(i)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "COPY trips TO '${target.absolutePathString()}' " +
                            "(FORMAT PARQUET, PARTITION_BY (pickup_date))",
                    )
                }
            }
        }

        /** Every regular file under [localRoot], at `<keyPrefix>/<relative path>`. */
        private fun uploadTree(
            client: S3Client,
            localRoot: Path,
            keyPrefix: String,
        ) {
            Files.walk(localRoot).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.name != ".DS_Store" }
                    .forEach { file ->
                        client.putObject(
                            { it.bucket(BUCKET).key("$keyPrefix/${file.relativeTo(localRoot)}") },
                            RequestBody.fromFile(file),
                        )
                    }
            }
        }

        /** After context A's Flyway has run (a static @BeforeAll runs BEFORE that): idempotent. */
        private fun seedAuthRows() {
            val pg = SharedE2e.postgres
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-lake-it@datapipelines.test', 'E2E Lake IT', 'test',
                                'e2e-lake-it-sub', TRUE, TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    )
                }
                val insertSql =
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, 'defa0000-0000-0000-0000-000000000001') ON CONFLICT (id) DO NOTHING"
                connection.prepareStatement(insertSql).use { ps ->
                    ps.setString(1, ADMIN_KEY.id)
                    ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(3, ADMIN_KEY.name)
                    ps.setString(4, ADMIN_KEY.hash)
                    ps.setArray(5, connection.createArrayOf("text", ADMIN_KEY.scopes))
                    ps.executeUpdate()
                }
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }

            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            registry.add("datapipelines.jwt.secret") { SECRET }
            registry.add("datapipelines.db.encryption-key") { SECRET }

            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }
    }
}
