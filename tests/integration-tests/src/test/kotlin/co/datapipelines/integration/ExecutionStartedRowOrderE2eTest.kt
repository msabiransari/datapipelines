package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #306 — the execution row exists before `execution_started` reaches the client, FORCED the way
 * lane 297 forced it: a held `LOCK TABLE pipeline_executions IN SHARE MODE` blocks the RUNNING
 * insert while admitting the controller's `findById` SELECTs — exactly the window in which a
 * cancel on the first event answered `404 result.execution_not_found`.
 *
 * The fix's order (started hook → RUNNING insert → `onRecorded` → the live send) is asserted on
 * the WIRE, in two halves:
 *
 * 1. while the lock is held, **no event arrives** — on the pre-#306 order `execution_started`
 *    landed here immediately (that is the red, naming the event that arrived); the client cannot
 *    learn an id whose row does not resolve yet;
 * 2. the lock lifts, the insert commits, and only then `execution_started` arrives; a DELETE
 *    issued right after reading it (a client that cancels on the very first frame) is answered
 *    **204**, and the stream carries `execution_aborted`.
 *
 * The lock-hold window ([EARLY_WINDOW_MS]) must stay well under the shipped lifecycle bound
 * (`datapipelines.executor.lifecycle-write-timeout-seconds`, 10 s): past it the insert would be
 * given up as unconfirmed and the first frame would arrive with no row — a different (documented)
 * degraded mode, not this test's subject. The bound itself is proven against a paused database by
 * the web module's `LifecycleWriteBoundIntegrationTest`.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ExecutionStartedRowOrderE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Suppress("SwallowedException") // the timeout IS the assertion; the exception carries nothing
    fun `a cancel on execution_started is answered 204 - the row exists before the first frame`() {
        ensureAuthSeeded()
        registerDatasource()
        seedSourceUsers()
        createTemplate()
        val pipelineId = createCancelPipeline()

        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("LOCK TABLE pipeline_executions IN SHARE MODE") }
                val readerExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "e2e-306-reader").apply { isDaemon = true } }
                try {
                    // The window runs against the REQUEST itself: a Spring SSE response flushes
                    // its headers together with the first frame, so "the client received nothing"
                    // means "send() has not returned". While the lock holds, the response must NOT
                    // arrive — on the pre-#306 order the 200 + execution_started landed at once,
                    // which is exactly the red. After the lock lifts, it arrives, carrying the
                    // committed row's id.
                    val responseFuture =
                        readerExecutor.submit<HttpResponse<InputStream>> {
                            HttpClient
                                .newHttpClient()
                                .send(executeRequest(pipelineId), HttpResponse.BodyHandlers.ofInputStream())
                        }
                    var earlyResponse: HttpResponse<InputStream>? = null
                    try {
                        earlyResponse = responseFuture.get(EARLY_WINDOW_MS, TimeUnit.MILLISECONDS)
                    } catch (e: TimeoutException) {
                        // Expected: nothing was sent while the RUNNING row could not commit.
                    }
                    withClue(
                        "the response arrived while the RUNNING row could not be committed " +
                            "(the pre-#306 order flushes 200 + execution_started at once): " +
                            "status=${earlyResponse?.statusCode()}",
                    ) {
                        earlyResponse shouldBe null
                    }

                    // Half 2: the row commits, and only then the frame that carries its id.
                    lock.commit()
                    val response = responseFuture.get(30, TimeUnit.SECONDS)
                    response.statusCode() shouldBe 200
                    val reader = BufferedReader(InputStreamReader(response.body()))
                    val name = reader.readLine()
                    withClue("the first frame after the row could commit") {
                        name shouldBe "event:execution_started"
                    }
                    val payload = readDataLine(reader)
                    val executionId = payload["execution_id"].asText()

                    // Non-vacuity: the cancel is issued only after the event was RECEIVED — the race
                    // the issue names is client-cancels-on-the-first-frame, not a blind early DELETE.
                    val answer = cancelExecution(executionId)
                    withClue("DELETE /api/v1/executions/$executionId answered ${answer.status} ${answer.code}: ${answer.body}") {
                        answer.status shouldBe 204
                    }

                    drainUntilAborted(reader)
                } finally {
                    readerExecutor.shutdownNow()
                    // A red above must not leave the execution's insert (or the shared table) blocked.
                    runCatching { lock.rollback() }
                }
            }
        }
    }

    /** The `data:` line following an already-read `event:` line. */
    private fun readDataLine(reader: BufferedReader): JsonNode {
        for (line in reader.lines()) {
            if (line.startsWith("data:")) return mapper.readTree(line.removePrefix("data:").trim())
        }
        throw AssertionError("the stream ended before the event's data line")
    }

    // ------------------------------------------------------------ helpers

    private fun executeRequest(pipelineId: String): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
            .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
            .build()

    /** Reads to EOF; fails unless `execution_aborted` was carried. */
    private fun drainUntilAborted(reader: BufferedReader) {
        val seen = mutableListOf<String>()
        var aborted = false
        for (line in reader.lines()) {
            if (line.startsWith("event:")) {
                seen += line.removePrefix("event:").trim()
                if (seen.last() == "execution_aborted") aborted = true
            }
        }
        withClue("expected execution_aborted after the accepted cancel; events after execution_started: $seen") {
            aborted shouldBe true
        }
    }

    private fun cancelExecution(executionId: String): CancelAnswer {
        val response =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .delete("/api/v1/executions/$executionId")
        val body = response.body.asString()
        val code =
            runCatching {
                mapper
                    .readTree(body)
                    .path("error")
                    .path("code")
                    .asText("")
            }.getOrDefault("")
        return CancelAnswer(response.statusCode, body, code.ifEmpty { "(no error code)" })
    }

    private data class CancelAnswer(
        val status: Int,
        val body: String,
        val code: String,
    )

    private fun ensureAuthSeeded() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', 'e2e-306-admin@datapipelines.test', 'e2e-306-admin', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                        ('$DEFAULT_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }
        }
    }

    private fun registerDatasource() {
        val existing =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/datasources/pg-local")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "pg-local-306", "display_name": "Source Postgres", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
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
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"id": "test/order_306_slow.sql", "dialect": "POSTGRES", "display_name": "Order 306 Slow Query",
                 "description": "Auto-generated template for E2E test", "imports": [],
                 "body": "SELECT id, email, name, created_at FROM users, pg_sleep($CANCEL_SLEEP_SECONDS) WHERE is_active = true ORDER BY created_at DESC"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
    }

    private fun createCancelPipeline(): String {
        val bodyJson =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to "test/order_306",
                    "display_name" to "Order 306 Pipeline",
                    "description" to "E2E test pipeline — the execution row's order",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to
                        listOf(
                            mapOf(
                                "id" to "slow_node",
                                "description" to "Slow caller node",
                                "type" to "DQL",
                                "source" to "pg-local-306",
                                "template" to mapOf("id" to "test/order_306_slow.sql", "version" to 1),
                                "output" to mapOf("target" to "caller"),
                                "depends_on" to emptyList<String>(),
                            ),
                        ),
                ),
            )
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(bodyJson)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != 201) {
            throw AssertionError("Pipeline creation failed (status=${response.statusCode()}): body=$bodyJson")
        }
        return response.jsonPath().getString("data.id")
    }

    private fun seedSourceUsers() {
        DriverManager
            .getConnection(source.jdbcUrl, source.username, source.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS users (
                            id SERIAL PRIMARY KEY,
                            email TEXT NOT NULL,
                            name TEXT NOT NULL,
                            is_active BOOLEAN NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.execute("TRUNCATE users")
                    statement.execute(
                        """
                        INSERT INTO users (email, name, is_active, created_at) VALUES
                            ('order306@datapipelines.test', 'Active', TRUE, NOW())
                        """.trimIndent(),
                    )
                }
            }
    }

    companion object {
        private const val CANCEL_SLEEP_SECONDS = 15

        /**
         * How long the test holds the lock and demands silence. Must stay well under the shipped
         * lifecycle bound (10 s) — see the class KDoc — and well over any honest scheduling delay
         * for the first frame of a healthy execution (milliseconds).
         */
        private const val EARLY_WINDOW_MS = 4_000L

        private const val SSE_BUDGET_MINUTES = 2L

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        private val random = SecureRandom()

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret`, signing the session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, "e2e-306-admin@datapipelines.test")

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        /** The pipeline's SOURCE database: a scratch database on the shared container. */
        private val source get() = SharedE2e.scratchDatabase("order306_source")

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(32).also { random.nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
