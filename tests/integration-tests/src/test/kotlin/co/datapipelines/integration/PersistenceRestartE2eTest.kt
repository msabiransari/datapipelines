package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * #266 D.6/D.9 over the REAL application, on PRIVATE containers — the suite stops the application and
 * starts it again, which the shared containers' other suites must never see:
 *
 * - **the shutdown order** (D.6): closing the application runs the execution drain, then — after the
 *   web server's graceful drain — the batching writers' drain; the application's own log proves the
 *   order. (500 queued items written by the drain, and the drain giving up on a held store, are the
 *   writer-level proofs in `web`'s `PersistenceBatchingIntegrationTest`.)
 * - **the restart** (D.9): a new application on the same stores serves, through the durable route and
 *   the replay route, exactly the ids each execution's live stream carried — once each — and no
 *   `(execution_id, event_id)` pair is stored twice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PersistenceRestartE2eTest {
    private val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16-alpine").withDatabaseName("datapipelines_266r").withUsername("dp").withPassword("dp")
    private val redis: GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withCommand("redis-server", "--maxmemory-policy", "noeviction")
            .withExposedPorts(REDIS_PORT)
    private val http = HttpClient.newHttpClient()
    private val mapper =
        com.fasterxml.jackson.databind
            .ObjectMapper()

    @BeforeAll
    fun start() {
        postgres.start()
        redis.start()
        DriverManager.getConnection(H2_URL, "sa", "sa").use { h2 ->
            h2.createStatement().execute("CREATE TABLE IF NOT EXISTS probe (id INT)")
            h2.createStatement().execute("INSERT INTO probe VALUES (1), (2)")
        }
    }

    @AfterAll
    fun stop() {
        redis.stop()
        postgres.stop()
        oidc.close()
    }

    @Test
    fun `the application drains its writers after its executions, and a restart serves what was committed - every id once`(
        output: CapturedOutput,
    ) {
        val first = boot()
        val runs =
            try {
                seedAdmin()
                val port = portOf(first)
                registerDatasource(port)
                val pipeline = pipeline(port)
                (1..EXECUTIONS).map { executeToEnd(port, pipeline) }
            } finally {
                first.close()
            }

        val log = output.out
        val executionDrain = log.indexOf("event=shutdown.drain_complete")
        val writersDrain = log.indexOf("event=shutdown.persistence_drain_started")
        withClue("the application's own shutdown log, in order") {
            (executionDrain >= 0) shouldBe true
            (executionDrain < writersDrain) shouldBe true
            listOf("audit", "execution_events", "replay_log").forEach { store ->
                (log.indexOf("event=persistence.drained writer=$store") > writersDrain) shouldBe true
            }
        }

        val second = boot()
        try {
            val port = portOf(second)
            runs.forEach { (executionId, liveIds) ->
                withClue("execution $executionId after the restart; live ids $liveIds") {
                    liveIds shouldBe (1..liveIds.size).toList()
                    durableIds(port, executionId) shouldBe liveIds
                    replayIds(port, executionId) shouldBe liveIds
                }
            }
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection
                    .createStatement()
                    .executeQuery(
                        "SELECT COUNT(*) FROM (SELECT execution_id, event_id FROM execution_events GROUP BY 1, 2 HAVING COUNT(*) > 1) d",
                    ).use { rows ->
                        rows.next()
                        rows.getInt(1) shouldBe 0
                    }
            }
        } finally {
            second.close()
        }
    }

    // ------------------------------------------------------------------ the application

    private fun boot(): ConfigurableApplicationContext {
        val properties =
            mapOf(
                "server.port" to "0",
                "management.server.port" to "0",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "spring.data.redis.host" to redis.host,
                "spring.data.redis.port" to redis.getMappedPort(REDIS_PORT).toString(),
                "spring.data.redis.password" to "",
                "datapipelines.redis.host" to redis.host,
                "datapipelines.redis.port" to redis.getMappedPort(REDIS_PORT).toString(),
                "datapipelines.jwt.secret" to JWT_SECRET,
                "datapipelines.db.encryption-key" to ENCRYPTION_KEY,
                "datapipelines.auth.oidc.providers[0].name" to "google",
                "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
                "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
                "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
                "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
                "datapipelines.auth.base-url" to "http://localhost:8080",
            )
        // As command-line arguments, NOT builder `properties(...)`: those are DEFAULT properties, the
        // lowest precedence, and application.yml's `${SPRING_DATASOURCE_URL}` would shadow them.
        return SpringApplicationBuilder(DatapipelinesApplication::class.java).run(*properties.map { (k, v) -> "--$k=$v" }.toTypedArray())
    }

    private fun portOf(context: ConfigurableApplicationContext): Int = (context as WebServerApplicationContext).webServer.port

    private fun seedAdmin() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                        "('$ADMIN', 'restart-266@datapipelines.test', 'Restart 266', 'test', 'restart-266', TRUE, TRUE)",
                )
                statement.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                        "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN', 'workspace_admin')",
                )
            }
        }
    }

    private fun session(port: Int): RequestSpecification =
        given()
            .port(port)
            .header("Cookie", E2eSession.cookieHeader(SESSION))
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)

    private fun registerDatasource(port: Int) {
        session(port)
            .contentType(ContentType.JSON)
            .body(
                """{"name": "$DATASOURCE", "display_name": "266 restart", "dialect": "H2", """ +
                    """"jdbc_url": "$H2_URL", "username": "sa", "password": "sa"}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** A template and a pipeline, both RELEASED; the pipeline's id. */
    private fun pipeline(port: Int): String {
        session(port)
            .contentType(ContentType.JSON)
            .body(
                """{"id": "$TEMPLATE", "dialect": "H2", "display_name": "r", "description": "266 restart.", """ +
                    """"imports": [], "body": "SELECT id FROM probe"}""",
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        val templateHash =
            session(
                port,
            ).queryParam("name", TEMPLATE).`when`().get("/api/v1/templates").then().extract().jsonPath().getString("data.body_hash")
        session(port)
            .contentType(ContentType.JSON)
            .header("If-Match", templateHash)
            .body("""{"name": "$TEMPLATE"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
        val created =
            session(port)
                .contentType(ContentType.JSON)
                .body(
                    """
                    {"schema_version": 1, "name": "$PIPELINE", "display_name": "r", "description": "266 restart.", "parameters": {},
                     "nodes": [{"id": "rows", "description": "rows", "type": "DQL", "source": "$DATASOURCE",
                                "template": {"id": "$TEMPLATE", "version": 1}, "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        val id = created.jsonPath().getString("data.id")
        session(
            port,
        ).header(
            "If-Match",
            created.jsonPath().getString("data.body_hash"),
        ).`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    /** One REST execution read to the end of its live stream: its id and the `id:` sequence the stream carried. */
    private fun executeToEnd(
        port: Int,
        pipelineId: String,
    ): Pair<String, List<Int>> {
        val body =
            http
                .send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                        .header("Cookie", E2eSession.cookieHeader(SESSION))
                        .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
        val executionId =
            E2eSse
                .parseEvents(body, mapper)
                .first { it.first == "execution_started" }
                .second["execution_id"]
                .asText()
        return executionId to body.lines().filter { it.startsWith("id:") }.map { it.removePrefix("id:").trim().toInt() }
    }

    private fun durableIds(
        port: Int,
        executionId: String,
    ): List<Int> =
        session(port)
            .queryParam("format", "json")
            .`when`()
            .get("/api/v1/executions/$executionId/events")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.events.event_id", Int::class.javaObjectType)

    private fun replayIds(
        port: Int,
        executionId: String,
    ): List<Int> =
        http
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/executions/$executionId/events"))
                    .header("Cookie", E2eSession.cookieHeader(SESSION))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()
            .lines()
            .filter { it.startsWith("id:") }
            .map { it.removePrefix("id:").trim().toInt() }

    private companion object {
        const val REDIS_PORT = 6379
        const val EXECUTIONS = 5
        val RUN_ID: String = Integer.toHexString(SecureRandom().nextInt(0x10000))
        val DATASOURCE = "h2-266r-$RUN_ID"
        val H2_URL = "jdbc:h2:mem:p266r_$RUN_ID;DB_CLOSE_DELAY=-1"
        val TEMPLATE = "test/p266r_$RUN_ID.sql"
        val PIPELINE = "test/p266r_$RUN_ID"
        val DEFAULT_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val ADMIN: UUID = UUID.randomUUID()
        val JWT_SECRET: String = E2eSession.newSecret()
        val SESSION: String get() = E2eSession.jwt(JWT_SECRET, ADMIN.toString(), "restart-266@datapipelines.test")
        val ENCRYPTION_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val oidc = OidcDiscoveryStub()
    }
}
