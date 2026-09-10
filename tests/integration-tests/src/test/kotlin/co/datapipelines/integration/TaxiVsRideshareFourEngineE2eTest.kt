package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
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
import java.math.BigDecimal
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
 * 089 §F, the round's flagship proof: the SHIPPED `nyc/mobility/taxi_vs_rideshare` content
 * (`scripts/sample-data/content/examples-lake.json`, unmodified — the file the demo seeds)
 * executed end to end over FOUR engines, one of them the dp-lake object store:
 *
 * - **Postgres** (`sample-trips`): a `trips` table with the four columns
 *   `nyc/lake/taxi_zone_day.sql` reads, in its own container.
 * - **The lake** (`sample-lake`): a small day-partitioned `hvfhv_zone_day`-shaped Parquet set
 *   on a MinIO Testcontainer, generated in `@BeforeAll` with the app's own pinned DuckDB JDBC.
 *   The column names/types are probed against the PUBLISHED
 *   `lake/v1/hvfhv_zone_day/part-0.parquet` (DESCRIBE, 2026-09-08): `pickup_date DATE`,
 *   `pu_location_id SMALLINT`, `company VARCHAR`, `trip_count BIGINT`,
 *   `total_base_fare DECIMAL(16,2)`, `total_tips`, `total_driver_pay`, `total_miles`,
 *   `total_trip_time_s BIGINT`, `shared_request_count BIGINT` — the fixture carries all ten,
 *   partitioned by `pickup_date` like the real `hvfhv_trips` layout.
 * - **SQLite** (`sample-reference`): a tiny generated file with a `zones` table, read-only
 *   (`open_mode: 1`), exactly the demo's binding (bootstrap-datasources-nyc.yml).
 * - **MySQL** (`sample-weather`): an `observations` EAV table (station/day/element/value)
 *   with PRCP rows, in the compose-pinned `mysql:8.4` image.
 *
 * ## The seeder path, not a shortcut
 *
 * The content is imported by the REAL `ExampleContentSeeder`: the context boots with
 * `datapipelines.bootstrap.examples-file` pointing at the shipped file and
 * `provisioning-mode=auto-per-user`, the four datasources are registered GLOBAL under the
 * exact names the file's `requires_datasources` gate declares, and the gate is exercised
 * POSITIVELY — the first login's provisioning imports the five templates and the pipeline
 * into the fresh personal workspace (the `workspace.examples_seeded` line, no
 * `examples_gate_skipped`), which the test then executes through the real SSE surface.
 *
 * ## The fixture's arithmetic (asserted exactly in Order(3))
 *
 * Window `2024-12-01..2024-12-02` (span 1, so the per-day rates divide by 2),
 * `rain_threshold_mm=2.5`: 12-01 averages 1.2 mm PRCP across the two seeded stations
 * (dry), 12-02 averages 6.0 (rainy). Every engine also carries one noise row — an
 * out-of-window day (12-05) in Postgres/MySQL/the lake, a 200-mile trip and a
 * non-PRCP element — so a missing predicate changes an expected number. Expected caller
 * rows (109 §D's per-mode rain-lift shape — no share column, the taxi side being a
 * 1-in-16 sample): Brooklyn dry-only 1.0/1.5 per day with NULL lifts; Manhattan
 * taxi lift 1.0/1.5 = 0.67 and rideshare lift 3.0/3.0 = 1.00; Queens rainy-only
 * 0.5/2.0 per day with NULL lifts.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TaxiVsRideshareFourEngineE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var applicationContext: ApplicationContext

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `the four demo datasources register global under the content's exact names`() {
        seedAuthRows()
        seedTripsEngine()
        seedWeatherEngine()
        registerDatasources()
        registerLakeTable()

        // Registry-backed introspection: the table reports as a VIEW in its namespace.
        val tables =
            given()
                .port(port)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .queryParam("namespace", "nyc.mobility")
                .`when`()
                .get("/api/v1/datasources/sample-lake/tables")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList<Map<String, Any?>>("data.tables")
        tables.single()["name"] shouldBe "hvfhv_zone_day"
        tables.single()["type"] shouldBe "VIEW"
        tables.single()["remarks"] shouldBe "parquet"
        tables.single()["namespace"] shouldBe listOf("nyc", "mobility")
    }

    /**
     * The content file's four `requires_datasources` names, each global and read-only. The
     * registered URLs are the BARE container endpoints: Testcontainers appends driver-tuning
     * query params (`?loggerLevel=OFF` on Postgres, TC flags on MySQL) that §9 validation
     * refuses — the seed connections below keep the driver-tuned form.
     */
    private fun registerDatasources() {
        createDatasource(
            """
            {"name": "sample-trips", "display_name": "NYC Taxi Trips (sample)", "dialect": "POSTGRES",
             "jdbc_url": "${trips.jdbcUrl.substringBefore('?')}", "readonly": true, "global": true,
             "credential": {"kind": "password", "username": "${trips.username}", "secret": "${trips.password}"}}
            """.trimIndent(),
        )
        createDatasource(
            """
            {"name": "sample-weather", "display_name": "NYC Weather (sample)", "dialect": "MYSQL",
             "jdbc_url": "${weather.jdbcUrl.substringBefore('?')}", "readonly": true, "global": true,
             "credential": {"kind": "password", "username": "${weather.username}", "secret": "${weather.password}"}}
            """.trimIndent(),
        )
        createDatasource(
            """
            {"name": "sample-reference", "display_name": "NYC Reference Data (sample)", "dialect": "SQLITE",
             "jdbc_url": "jdbc:sqlite:${sqliteFile.absolutePathString()}", "readonly": true, "global": true,
             "credential": {"kind": "none"},
             "properties": {"jdbc": {"open_mode": "1"}}}
            """.trimIndent(),
        )
        createDatasource(
            """
            {"name": "sample-lake", "display_name": "NYC Rideshare Lake (sample)", "dialect": "LAKE",
             "jdbc_url": "jdbc:duckdb::memory:", "readonly": true, "global": true,
             "credential": {"kind": "password", "username": "$MINIO_USER", "secret": "$MINIO_PASSWORD"},
             "properties": {"dialect": {"catalog.kind": "s3", "region": "us-east-1",
               "endpoint": "localhost:${minio.getMappedPort(MINIO_PORT)}", "url_style": "path"}}}
            """.trimIndent(),
        )
    }

    /**
     * The lake's registry: ONE table, in the namespace the demo's lake datasource uses — the
     * single-namespace search path is what resolves the template's bare
     * `FROM hvfhv_zone_day r`.
     */
    private fun registerLakeTable() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"namespace": ["nyc", "mobility"], "name": "hvfhv_zone_day", "format": "parquet",
                 "location": "s3://$BUCKET/lake/hvfhv_zone_day/pickup_date=*/data_*.parquet",
                 "partition_column": "pickup_date"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources/sample-lake/tables")
            .then()
            .statusCode(201)
    }

    @Test
    @Order(2)
    fun `the first login seeds the shipped lake content - the requires_datasources gate passes`() {
        val email = "lake-e2e-${UUID.randomUUID().toString().take(8)}@example.com"

        lateinit var pair: Pair<UUID, UUID>
        val lines = capturingLogs { pair = firstLogin(email) }
        val (userId, workspaceId) = pair
        provisioned = pair

        // The gate PASSED: the seeded line, and no skip line naming the lake file.
        lines.any { it.contains("event=workspace.examples_seeded") } shouldBe true
        lines.none { it.contains("event=workspace.examples_gate_skipped") } shouldBe true

        // The personal workspace holds the shipped content, owned by the new user.
        metadataRows("SELECT name FROM templates WHERE workspace_id = '$workspaceId' ORDER BY name")
            .map { it["name"] as String } shouldContainExactly
            listOf(
                "nyc/lake/daily_rain.sql",
                "nyc/lake/rideshare_zone_day.sql",
                "nyc/lake/taxi_vs_rideshare.sql",
                "nyc/lake/taxi_zone_day.sql",
                "nyc/lake/zones.sql",
            )
        val pipeline =
            metadataRow("SELECT name, owner_id FROM pipelines WHERE workspace_id = '$workspaceId'")
        pipeline["name"] shouldBe "nyc/mobility/taxi_vs_rideshare"
        pipeline["owner_id"] shouldBe userId
    }

    @Test
    @Order(3)
    fun `taxi_vs_rideshare runs across postgres, the lake, sqlite and mysql into the h2 answer`() {
        val (userId, workspaceId) = provisioned
        seedExecutionKey(userId, workspaceId)
        val pipelineId =
            metadataScalar<Any>(
                "SELECT id FROM pipelines WHERE workspace_id = '$workspaceId' AND name = 'nyc/mobility/taxi_vs_rideshare'",
            ).toString()

        val events =
            execute(
                pipelineId,
                mapOf(
                    "start_date" to WINDOW_START,
                    "end_date" to WINDOW_END,
                    "rain_threshold_mm" to RAIN_THRESHOLD,
                ),
            )
        val names = events.map { it.first }
        names.first() shouldBe "execution_started"
        names.last() shouldBe "data_ready"
        names.none { it == "node_failed" } shouldBe true
        names.count { it == "node_started" } shouldBe 6
        names.count { it == "node_completed" } shouldBe 6
        val executionId = events.first().second["execution_id"].asText()

        // Every node, including the CALCULATOR, succeeded; the computed span reached the
        // context as 1 day (the per-day rates below divide by span + 1 = 2).
        val stats = nodeStats(executionId)
        stats.map { it["node_id"].asText() } shouldContainExactlyInAnyOrder
            listOf("stage_taxi", "stage_rideshare", "stage_zones", "stage_rain", "window_span", "taxi_vs_rideshare")
        stats.forEach { it["status"].asText() shouldBe "SUCCESS" }
        stats.single { it["node_id"].asText() == "window_span" }["context_value"].asText() shouldBe "1"

        // The answer: one row per borough (per-mode rain lift), exact arithmetic against the
        // seeded fixtures.
        val result =
            given()
                .port(port)
                .header(API_KEY_HEADER, RUN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
        result.jsonPath().getLong("data.total_rows") shouldBe 3L
        val rows: List<List<Any?>> = result.jsonPath().get("data.rows")
        assertRainLiftAnswer(rows)
    }

    /**
     * The 109 §D answer: one row per borough — (taxi/day dry, taxi/day rainy, rideshare/day
     * dry, rideshare/day rainy, taxi rain lift, rideshare rain lift). No share column: the
     * taxi side is a 1-in-16 sample, so a share would be a number about the sampling. A
     * borough with only one weather in the window carries NULLs for the other — Brooklyn saw
     * no rainy day, Queens no dry one — and both lifts are NULL there (a lift needs both
     * sides). Manhattan: taxi 1.0/1.5 = 0.67, rideshare 3.0/3.0 = 1.00.
     */
    private fun assertRainLiftAnswer(rows: List<List<Any?>>) {
        rows.map { it[0].toString() } shouldContainExactly
            listOf(
                "Brooklyn",
                "Manhattan",
                "Queens",
            )
        val expected =
            listOf(
                listOf("1.0", null, "1.5", null, null, null),
                listOf("1.5", "1.0", "3.0", "3.0", "0.67", "1.00"),
                listOf(null, "0.5", null, "2.0", null, null),
            )
        rows.forEachIndexed { index, row ->
            (1..6).forEach { column ->
                val want = expected[index][column - 1]
                if (want == null) {
                    row[column] shouldBe null
                } else {
                    BigDecimal(row[column].toString()).compareTo(BigDecimal(want)) shouldBe 0
                }
            }
        }
    }

    // ------------------------------------------------------------------ engine seeds

    /**
     * `trips` with exactly the columns `nyc/lake/taxi_zone_day.sql` reads. Three zones over
     * the two window days, plus a 200-mile row (the template's plausibility filter must drop
     * it) and a 12-05 row (outside the window).
     */
    private fun seedTripsEngine() {
        DriverManager.getConnection(trips.jdbcUrl, trips.username, trips.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE trips (
                        pickup_date DATE NOT NULL,
                        pu_location_id INT NOT NULL,
                        trip_distance_mi NUMERIC(6,2) NOT NULL,
                        total_amount NUMERIC(8,2) NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO trips (pickup_date, pu_location_id, trip_distance_mi, total_amount) VALUES
                        (DATE '2024-12-01', 1,  2.0,  10.00),
                        (DATE '2024-12-01', 1,  3.5,  12.50),
                        (DATE '2024-12-01', 1,  1.2,   9.50),
                        (DATE '2024-12-01', 2,  4.0,  15.00),
                        (DATE '2024-12-01', 2,  6.0,  18.00),
                        (DATE '2024-12-02', 1,  5.0,  25.00),
                        (DATE '2024-12-02', 1,  7.5,  30.00),
                        (DATE '2024-12-02', 3,  8.0,  40.00),
                        (DATE '2024-12-01', 1, 200.0, 500.00),
                        (DATE '2024-12-05', 1,  2.0,  12.00)
                    """.trimIndent(),
                )
            }
        }
    }

    /**
     * `observations` in the demo's EAV shape. 12-01 averages 1.2 mm (dry at the 2.5
     * threshold), 12-02 averages 6.0 (rainy); a TMAX row proves the element filter and a
     * 12-05 row the date predicate.
     */
    private fun seedWeatherEngine() {
        DriverManager.getConnection(weather.jdbcUrl, weather.username, weather.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE observations (
                        station_id VARCHAR(16) NOT NULL,
                        obs_date DATE NOT NULL,
                        element VARCHAR(8) NOT NULL,
                        value DECIMAL(8,2) NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO observations (station_id, obs_date, element, value) VALUES
                        ('USW00094728', '2024-12-01', 'PRCP', 1.0),
                        ('USW00014732', '2024-12-01', 'PRCP', 1.4),
                        ('USW00094728', '2024-12-02', 'PRCP', 5.0),
                        ('USW00014732', '2024-12-02', 'PRCP', 7.0),
                        ('USW00094728', '2024-12-01', 'TMAX', 8.0),
                        ('USW00094728', '2024-12-05', 'PRCP', 20.0)
                    """.trimIndent(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ REST + SSE

    private fun createDatasource(body: String) {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(body)
                .`when`()
                .post("/api/v1/datasources")
                .thenReturn()
        if (response.statusCode() != 201) {
            throw AssertionError("datasource create failed (status=${response.statusCode()}): ${response.body().asString()}")
        }
    }

    private fun execute(
        pipelineId: String,
        parameters: Map<String, Any>,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(EXECUTION_BUDGET) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header(API_KEY_HEADER, RUN_KEY.plaintext)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("parameters" to parameters))))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    /** `GET /executions/{id}` → `data.node_stats`, parsed — the REST surface, not the DB. */
    private fun nodeStats(executionId: String): List<JsonNode> {
        val body =
            given()
                .port(port)
                .header(API_KEY_HEADER, RUN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString()
        return mapper.readTree(body)["data"]["node_stats"].toList()
    }

    // ------------------------------------------------------------------ the seeder path

    /**
     * The `auto-per-user` first login, at the two calls `OidcSuccessHandler` makes — the
     * SampleDataBootstrapE2eTest seam (beans by name: `app` exposes `web` as
     * `implementation`). Provisioning runs the PersonalWorkspaceSeeders, which is where the
     * examples file's gate and import live.
     */
    private fun firstLogin(email: String): Pair<UUID, UUID> {
        val userService = applicationContext.getBean("userService")
        val user =
            userService.javaClass
                .getMethod(
                    "findOrCreateByEmail",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ).invoke(userService, email, "Lake E2E User", null, "google", "sub-${UUID.randomUUID()}")
        val userId = user.javaClass.getMethod("getId").invoke(user) as UUID

        val workspaceService = applicationContext.getBean("workspaceService")
        val context =
            workspaceService.javaClass.methods
                .first { it.name == "workspaceForLogin" }
                .invoke(workspaceService, user, email)
        val workspaceId = context.javaClass.getMethod("getId").invoke(context) as UUID
        return userId to workspaceId
    }

    private fun capturingLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
        return appender.list.map { it.formattedMessage + " " + it.argumentArray?.joinToString(" ") }
    }

    // ------------------------------------------------------------------ metadata SQL

    private fun metadataRows(sql: String): List<Map<String, Any?>> {
        val pg = SharedE2e.postgres
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    generateSequence { if (rs.next()) columns.associateWith { rs.getObject(it) } else null }.toList()
                }
            }
        }
    }

    private fun metadataRow(sql: String): Map<String, Any?> = metadataRows(sql).single()

    @Suppress("UNCHECKED_CAST")
    private fun <T> metadataScalar(sql: String): T = metadataRows(sql).single().values.first() as T

    private fun seedAuthRows() {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-lake-4eng@datapipelines.test', 'E2E Lake Four-Engine', 'test',
                            'e2e-lake-4eng-sub', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
            insertApiKey(connection, ADMIN_KEY, UUID.fromString(ADMIN_USER_ID), DEFAULT_WORKSPACE_ID)
        }
    }

    /** The executing key: the provisioned user, in the provisioned personal workspace. */
    private fun seedExecutionKey(
        userId: UUID,
        workspaceId: UUID,
    ) {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            insertApiKey(connection, RUN_KEY, userId, workspaceId)
        }
    }

    private fun insertApiKey(
        connection: java.sql.Connection,
        key: E2eAuth.SeededKey,
        userId: UUID,
        workspaceId: UUID,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                    " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
            ).use { ps ->
                ps.setString(1, key.id)
                ps.setObject(2, userId)
                ps.setString(3, key.name)
                ps.setString(4, key.hash)
                ps.setArray(5, connection.createArrayOf("text", key.scopes))
                ps.setObject(6, workspaceId)
                ps.executeUpdate()
            }
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val BUCKET = "dp-lake-e2e"
        private const val MINIO_PORT = 9000
        private const val MINIO_USER = "minioadmin"
        private const val MINIO_PASSWORD = "minioadmin"

        /** The same MinIO pin the 089 §F MinIO suite verified against Docker Hub. */
        private const val MINIO_IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z"

        /** The compose stack's exact MySQL pin (deploy/compose.yml). */
        private const val MYSQL_IMAGE = "mysql:8.4@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"

        private const val WINDOW_START = "2024-12-01"
        private const val WINDOW_END = "2024-12-02"
        private const val RAIN_THRESHOLD = "2.5"

        private val DEFAULT_WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

        /** The first lake read builds the DuckDB pool — extension INSTALLs on a cold cache. */
        private val EXECUTION_BUDGET: Duration = Duration.ofSeconds(180)

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-lake-4eng-key", arrayOf("read", "execute", "author"))
        private val RUN_KEY = E2eAuth.generateKey("e2e-lake-4eng-run", arrayOf("read", "execute", "author"))
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

        @Container
        @JvmStatic
        val trips: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("dp_sample_trips")
                .withUsername("dp")
                .withPassword("dp")

        @Container
        @JvmStatic
        val weather: MySQLContainer<*> =
            // The digest pin is the compose stack's exact image; the digest form needs the
            // explicit compatibility declaration the plain `mysql:8.4` tag carries implicitly.
            MySQLContainer(DockerImageName.parse(MYSQL_IMAGE).asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("dp_sample_weather")
                .withUsername("dp")
                .withPassword("dp")

        private val oidc = OidcDiscoveryStub()

        private lateinit var staging: Path
        private lateinit var sqliteFile: Path
        private var s3: S3Client? = null

        /** Set by Order(2), read by Order(3) — the composition suite's pattern. */
        private lateinit var provisioned: Pair<UUID, UUID>

        /**
         * Builds the local fixtures: the day-partitioned Parquet set (uploaded key-for-key —
         * the bucket mirrors the staging layout) and the SQLite zones file. Zones carry two
         * catch-all rows (`Unknown`, `N/A`) the shipped zones template must exclude.
         */
        @BeforeAll
        @JvmStatic
        fun seedFixtures() {
            staging = Files.createTempDirectory("lake-4eng-e2e")
            generateLakeParquet(staging.resolve("lake/hvfhv_zone_day"))
            sqliteFile = staging.resolve("nyc_reference.db")
            generateZonesSqlite(sqliteFile)

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
            uploadTree(client, staging.resolve("lake"), "lake")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            s3?.close()
            staging.toFile().deleteRecursively()
            oidc.close()
        }

        /**
         * The `hvfhv_zone_day` shape probed from the published lake/v1 object (see the class
         * KDoc). One (zone, day, company)-grain row per line; the 12-05 partition is the
         * out-of-window noise the template's date predicate must prune away from the result.
         */
        private fun generateLakeParquet(target: Path) {
            Files.createDirectories(target)
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE hvfhv_zone_day (
                            pickup_date DATE,
                            pu_location_id SMALLINT,
                            company VARCHAR,
                            trip_count BIGINT,
                            total_base_fare DECIMAL(16,2),
                            total_tips DECIMAL(16,2),
                            total_driver_pay DECIMAL(16,2),
                            total_miles DECIMAL(16,2),
                            total_trip_time_s BIGINT,
                            shared_request_count BIGINT
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO hvfhv_zone_day VALUES
                            (DATE '2024-12-01', 1, 'uber',   4,   80.00,  8.00,  60.00,  12.50, 3600, 1),
                            (DATE '2024-12-01', 1, 'lyft',   2,   40.00,  4.00,  30.00,   6.25, 1800, 0),
                            (DATE '2024-12-01', 2, 'uber',   3,   60.00,  6.00,  45.00,   9.00, 2700, 0),
                            (DATE '2024-12-02', 1, 'uber',   6,  120.00, 12.00,  90.00,  18.00, 5400, 2),
                            (DATE '2024-12-02', 3, 'via',    4,   88.00,  8.80,  66.00,  11.00, 4000, 0),
                            (DATE '2024-12-05', 1, 'uber', 100,  999.00,  0.00, 700.00, 100.00, 9000, 0)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "COPY hvfhv_zone_day TO '${target.absolutePathString()}' " +
                            "(FORMAT PARQUET, PARTITION_BY (pickup_date))",
                    )
                }
            }
        }

        private fun generateZonesSqlite(file: Path) {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePathString()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE zones (location_id INTEGER PRIMARY KEY, borough TEXT NOT NULL, zone TEXT NOT NULL)",
                    )
                    statement.execute(
                        """
                        INSERT INTO zones (location_id, borough, zone) VALUES
                            (1, 'Manhattan', 'Midtown'),
                            (2, 'Brooklyn', 'Williamsburg'),
                            (3, 'Queens', 'Astoria'),
                            (264, 'Unknown', 'Unknown'),
                            (265, 'N/A', 'N/A')
                        """.trimIndent(),
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

        /** The shipped content file, located by the JarSmokeE2eTest root-walk discipline. */
        private fun examplesFile(): Path {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            val root = requireNotNull(dir) { "Could not locate repository root (no settings.gradle.kts on any ancestor)" }
            val file = java.io.File(root, "scripts/sample-data/content/examples-lake.json")
            check(file.isFile) { "the shipped lake content file is not at ${file.absolutePath}" }
            return file.toPath()
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

            // The seeder path (089 §E/§F): the shipped lake examples file, imported into each
            // fresh personal workspace when its requires_datasources gate passes.
            registry.add("datapipelines.workspaces.provisioning-mode") { "auto-per-user" }
            registry.add("datapipelines.bootstrap.examples-file") { examplesFile().absolutePathString() }
        }
    }
}
