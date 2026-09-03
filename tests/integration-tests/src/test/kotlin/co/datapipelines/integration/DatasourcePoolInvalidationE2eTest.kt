package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
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
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * §5.7 cross-instance pool invalidation, live (050/R1, ARCH-AUDIT M3): **two application
 * contexts** — instance A (this test's `@SpringBootTest` context, serving HTTP on
 * [port]) and instance B (a second full application booted beside it) — against ONE Postgres
 * and ONE Redis. Both instances are driven through their HTTP surfaces only: this suite may
 * depend on `:modules:app` alone (module-structure §5.11), and the black-box drive is the
 * honest M3 story anyway.
 *
 * The defect the channel closes: a pool built by B from the OLD row keeps serving the old
 * database until B restarts. The proof is behavioral, not log-based:
 *
 * 1. An execution on B against datasource `mi2_shared` returns marker value `'one'` — B's
 *    pool is now warm, built from the old row.
 * 2. A PUTs the datasource to point at the second H2 database. A's own eviction is
 *    synchronous; the Redis channel is B's only signal.
 * 3. An execution on B now returns `'two'` — within seconds, not at B's next restart.
 *    Without the channel (subscriber disabled) the final assertion fails: B keeps answering
 *    `'one'` forever, which is exactly M3.
 *
 * A single-instance test cannot go red on M3 — the synchronous local eviction produces the
 * same observable on one instance. That is why this suite boots a second context.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class DatasourcePoolInvalidationE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    fun `a datasource edited on A is served from the new row by B's next execution`() {
        // After context A's Flyway has run (a @BeforeAll runs BEFORE that): idempotent.
        seedAuthRows()
        registerDatasource(DS, "jdbc:h2:mem:$H2_ONE;DB_CLOSE_DELAY=-1")
        createTemplate(TEMPLATE_SQL, "SELECT v FROM marker")
        val pipelineId = createPipeline()

        // B's first execution warms B's pool from the OLD row and reads the OLD database.
        executeOnB(pipelineId) shouldBe "one"

        // The edit crosses A's HTTP surface: A evicts its own pool synchronously and publishes
        // the name; B's subscriber evicts, and B's next lease rebuilds from the new row.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"display_name": "MI2 repointed", "dialect": "H2",
                 "jdbc_url": "jdbc:h2:mem:$H2_TWO;DB_CLOSE_DELAY=-1", "username": "$H2_USER"}
                """.trimIndent(),
            ).`when`()
            .put("/api/v1/datasources/$DS")
            .then()
            .statusCode(200)

        // Pub/sub is asynchronous: keep executing on B until it reaches the new database, with
        // a deadline far under "B's next restart" — the pre-050 behaviour never gets there.
        val deadline = System.nanoTime() + PROPAGATION_BUDGET
        var marker: String? = null
        while (System.nanoTime() < deadline) {
            marker = executeOnB(pipelineId)
            if (marker == "two") break
            Thread.sleep(POLL_MILLIS)
        }
        marker shouldBe "two"
    }

    // ------------------------------------------------------------- HTTP on either instance

    /** Runs the pipeline on instance B and returns the marker value its result rows carry. */
    private fun executeOnB(pipelineId: String): String? {
        val events =
            assertTimeoutPreemptively(Duration.ofSeconds(60)) {
                consumeExecutionStream(portB, pipelineId)
            }
        events.map { it.first } shouldContainExactly
            listOf("execution_started", "node_started", "node_completed", "pipeline_completed", "data_ready")
        val executionId = events.first().second["execution_id"].asText()
        val result =
            given()
                .port(portB)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        val rows: List<List<Any?>> = result.get("data.rows")
        return rows.single().single().toString()
    }

    private fun consumeExecutionStream(
        targetPort: Int,
        pipelineId: String,
    ): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$targetPort/api/v1/pipelines/$pipelineId/execute"))
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .header("DP-Correlation-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return E2eSse.parseEvents(response.body(), mapper)
    }

    private fun registerDatasource(
        name: String,
        jdbcUrl: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"name": "$name", "display_name": "MI2 shared", "dialect": "H2",
                 "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplate(
        id: String,
        sql: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
            .body(
                """
                {"id": "$id", "dialect": "H2", "display_name": "MI2 E2E $id",
                 "description": "MI2 pool invalidation marker read", "imports": [],
                 "body": ${mapper.writeValueAsString(sql)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createPipeline(): String {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, ADMIN_KEY.plaintext)
                .body(
                    """
                    {"name": "mi2_marker_read", "nodes": [{
                        "id": "read_marker", "description": "Read the marker table",
                        "type": "DQL", "source": "$DS",
                        "template": {"id": "$TEMPLATE_SQL", "version": 1},
                        "output": {"target": "caller"}, "depends_on": []}]}
                    """.trimIndent().replace("\n", " "),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        return response.jsonPath().getString("data.id")
    }

    companion object {
        private const val REDIS_PORT = 6379
        private const val API_KEY_HEADER = "DP-API-Key"

        /** Instance B — the second application context this suite boots beside its own. */
        private var instanceB: ConfigurableApplicationContext? = null

        /** Instance B's random HTTP port, read from its own environment after boot. */
        private var portB: Int = 0

        /**
         * Booted in `@BeforeAll` (static): with the containers running, the second context
         * starts before the first test and stays up for the whole class — instance B's pool
         * outlives individual test methods, exactly like a real peer instance.
         */
        @BeforeAll
        @JvmStatic
        fun bootInstanceB() {
            seedMarker(H2_ONE, "one")
            seedMarker(H2_TWO, "two")
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
        }

        private fun seedMarker(
            h2Db: String,
            value: String,
        ) {
            DriverManager.getConnection("jdbc:h2:mem:$h2Db;DB_CLOSE_DELAY=-1", H2_USER, H2_PASSWORD).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE marker (v VARCHAR(10))")
                    statement.execute("INSERT INTO marker VALUES ('$value')")
                }
            }
        }

        private fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-mi2@datapipelines.test', 'E2E MI2', 'test', 'e2e-mi2-sub', TRUE, TRUE)
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

        /** The one datasource both instances touch — the M3 scenario's name. */
        private const val DS = "mi2_shared"
        private const val TEMPLATE_SQL = "mi2_read_marker.sql"

        // Two distinguishable in-memory "customer databases": the marker value each returns is
        // the identity of the pool that served the execution.
        private const val H2_ONE = "mi2_one"
        private const val H2_TWO = "mi2_two"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"

        private const val POLL_MILLIS = 250L
        private const val PROPAGATION_BUDGET = 30_000_000_000L

        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))
        private const val ADMIN_USER_ID = "a11e0000-0000-0000-0000-000000000002"
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-mi2-key", arrayOf("admin"))

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
