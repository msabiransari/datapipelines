package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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

/**
 * 089 §F's two-instance pin: **a lake table registered on instance A is visible to instance
 * B's NEXT EXECUTION, without B restarting** — the registry twin of
 * [DatasourcePoolInvalidationE2eTest]'s datasource-row scenario, over the same two-context
 * harness (A = this test's `@SpringBootTest` context, B = a second full boot; ONE Postgres,
 * ONE Redis).
 *
 * The defect the channel closes: B builds its LAKE pool once, capturing the registry's view
 * statements into `connectionInitSql` AT POOL BUILD (`DefaultDatasourceRegistry`'s factory) —
 * a pool built BEFORE a registration carries no view for the new table, and without the
 * `LakeTableRegistryService.refreshConnections` publish B would keep answering
 * "table not found" until restart. The proof is behavioral:
 *
 * 1. An execution on B against `mi2_lake` FAILS — the table is not registered yet; B's pool
 *    is now warm, built from the EMPTY registry.
 * 2. A registers `marker_t` (REST). A's own eviction is synchronous; the Redis channel is
 *    B's only signal.
 * 3. An execution on B now succeeds and reads the parquet rows — within seconds, not at B's
 *    next restart.
 *
 * A `file://` fixture keeps the suite fast — MinIO is the other suite's subject; the invalidation
 * mechanism is location-agnostic (the pool's init SQL carries the same view either way). The
 * single-row parquet is written with the app's own DuckDB JDBC; both contexts run in this JVM,
 * so the file is equally visible to both — exactly what a shared mounted volume is in
 * production.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class LakeRegistryTwoInstanceE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `a lake table registered on A is read by B's next execution without a restart`() {
        // After context A's Flyway has run (a @BeforeAll runs BEFORE that): idempotent.
        seedAuthRows()
        registerLakeDatasource()
        createTemplate()
        val pipelineId = createPipeline()

        // B's first execution warms B's pool from the EMPTY registry — and fails, because no
        // view answers `marker_t` yet. The failure IS the "before" state: B's pool exists and
        // provably cannot see the table.
        eventsOnB(pipelineId).map { it.first } shouldContain "pipeline_failed"

        // The registration crosses A's HTTP surface: refreshConnections evicts A's pool
        // synchronously and publishes the datasource name; B's subscriber evicts, and B's
        // next pool build re-reads the registry — now one row.
        registerMarkerTable()

        // Pub/sub is asynchronous: keep executing on B until the view resolves, with a
        // deadline far under "B's next restart" — the pre-089 behavior never gets there.
        val deadline = System.nanoTime() + PROPAGATION_BUDGET_NANOS
        var marker: String? = null
        while (System.nanoTime() < deadline) {
            marker = markerOnB(pipelineId)
            if (marker == MARKER_VALUE) break
            Thread.sleep(POLL_MILLIS)
        }
        marker shouldBe MARKER_VALUE
    }

    // ------------------------------------------------------------- HTTP on either instance

    /** Executes the pipeline on B and returns its SSE events (success OR failure). */
    private fun eventsOnB(pipelineId: String): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofSeconds(90)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$portB/api/v1/pipelines/$pipelineId/execute"))
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

    /** The marker value of B's latest execution, or null while executions keep failing. */
    private fun markerOnB(pipelineId: String): String? {
        val events = eventsOnB(pipelineId)
        if ("pipeline_completed" !in events.map { it.first }) return null
        val executionId = events.first().second["execution_id"].asText()
        val rows: List<List<Any?>> =
            given()
                .port(portB)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .get("data.rows")
        return rows.single().single().toString()
    }

    private fun registerLakeDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$DS", "display_name": "MI2 lake", "dialect": "LAKE",
                 "jdbc_url": "jdbc:duckdb::memory:", "credential": {"kind": "none"}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$TEMPLATE_ID", "dialect": "LAKE", "display_name": "MI2 lake marker read",
                 "description": "089 F two-instance lake registry pin", "imports": [],
                 "body": "SELECT v FROM marker_t"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(): String =
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "test/mi2_lake_marker_read", "nodes": [{
                    "id": "read_marker", "description": "Read the lake marker view",
                    "type": "DQL", "source": "$DS",
                    "template": {"id": "$TEMPLATE_ID", "version": 1},
                    "output": {"target": "caller"}, "depends_on": []}]}
                """.trimIndent().replace("\n", " "),
            ).`when`()
            .post("/api/v1/pipelines")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")

    private fun registerMarkerTable() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"namespace": ["mi2"], "name": "marker_t", "format": "parquet",
                 "location": "file://${markerParquet.toAbsolutePath()}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources/$DS/tables")
            .then()
            .statusCode(201)
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DS = "mi2_lake_it"
        private const val TEMPLATE_ID = "test/mi2_lake_read_marker.sql"
        private const val MARKER_VALUE = "registered-on-a-read-on-b"

        private const val POLL_MILLIS = 250L
        private const val PROPAGATION_BUDGET_NANOS = 30_000_000_000L

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000090"
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-mi2-lake-key", arrayOf("read", "execute", "author"))

        /** Instance B — the second application context this suite boots beside its own. */
        private var instanceB: ConfigurableApplicationContext? = null

        /** Instance B's random HTTP port, read from its own environment after boot. */
        private var portB: Int = 0

        private lateinit var markerParquet: Path

        /** The module's shared Postgres — migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        // OWN Redis, deliberately not the shared one: this suite's subject is cross-instance
        // pub/sub, and Spring's context cache keeps EARLIER suites' contexts (and their
        // subscriptions) alive until JVM exit — a shared Redis would deliver this suite's
        // invalidations to stale listeners of suites that already finished.
        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

        private val oidc = OidcDiscoveryStub()

        @BeforeAll
        @JvmStatic
        fun bootInstanceB() {
            // The one-row marker parquet, written with the app's own pinned DuckDB JDBC —
            // visible to both contexts because both run in this JVM (a mounted volume, in
            // production).
            markerParquet = Files.createTempDirectory("lake-mi2-it").resolve("marker.parquet")
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "COPY (SELECT '$MARKER_VALUE' AS v) TO '${markerParquet.toAbsolutePath()}' (FORMAT PARQUET)",
                    )
                }
            }
            // Command-line args, not builder `.properties(...)`: those are DEFAULT properties
            // and application.yml's `${SPRING_DATASOURCE_URL}` would override them — args win.
            instanceB =
                SpringApplicationBuilder(DatapipelinesApplication::class.java)
                    .run(
                        "--server.port=0",
                        "--management.server.port=0",
                        "--spring.datasource.url=${postgres.jdbcUrl}",
                        "--spring.datasource.username=${postgres.username}",
                        "--spring.datasource.password=${postgres.password}",
                        "--spring.data.redis.host=${redis.host}",
                        "--spring.data.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                        "--spring.data.redis.password=",
                        "--datapipelines.redis.host=${redis.host}",
                        "--datapipelines.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                        "--datapipelines.jwt.secret=$SECRET",
                        "--datapipelines.db.encryption-key=$SECRET",
                        "--datapipelines.auth.oidc.providers[0].name=google",
                        "--datapipelines.auth.oidc.providers[0].client-id=test-google-client-id",
                        "--datapipelines.auth.oidc.providers[0].client-secret=test-google-client-secret",
                        "--datapipelines.auth.oidc.providers[0].issuer-uri=${oidc.issuer}",
                        "--datapipelines.auth.oidc.providers[0].display-name=Test google",
                        "--datapipelines.auth.base-url=http://localhost:8080",
                    )
            portB = Integer.parseInt(checkNotNull(instanceB?.environment?.getProperty("local.server.port")))
        }

        @AfterAll
        @JvmStatic
        fun closeInstanceB() {
            instanceB?.close()
            oidc.close()
            markerParquet.parent?.toFile()?.deleteRecursively()
        }

        private fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-mi2-lake@datapipelines.test', 'E2E MI2 Lake', 'test',
                                'e2e-mi2-lake-sub', TRUE, TRUE)
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

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

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
