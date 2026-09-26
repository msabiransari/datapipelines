package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64

/**
 * #230 — an open SSE execution stream is cut at its next write after the subscriber's authority
 * is revoked (security-assurance ruling P4), and the revocation cancels NOTHING: the execution
 * itself still runs to completion (P4's first half — the stream is cut, the run is not).
 *
 * Real revocation over REST: an author opens the live execute stream of a slow (pg_sleep)
 * pipeline; mid-run a workspace admin removes their membership (`DELETE …/members/{userId}`,
 * which evicts the auth cache on this instance); the member's open stream must then end at the
 * next write — the §6.6 heartbeat cadence is forced to 1 s here — carrying a final `: revoked`
 * comment (§6.6's comment form, ignored by every SSE consumer) and never the terminal event.
 * The admin then reads the execution's metadata to COMPLETED: had the closed stream been
 * misread as a client DISCONNECT, the §6.8 grace timer would have cancelled the run and this
 * poll would see `EXECUTION_ABORTED` instead.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ExecutionStreamRevocationE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val adminSession get() = E2eSession.jwt(SECRET, ADMIN, "sse230-admin@datapipelines.test", WS_NAME)
    private val memberSession get() = E2eSession.jwt(SECRET, MEMBER, "sse230-member@datapipelines.test", WS_NAME)

    @Test
    fun `a removed member's open stream is cut at the next write and the execution still completes`() {
        ensureSeeded()
        val pipelineId = fixtures()

        // The member opens the live stream; the reader runs beside the revocation.
        val reader =
            Thread {
                streamBody(pipelineId, memberSession)
            }
        val failure = arrayOfNulls<Throwable>(1)
        reader.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> failure[0] = e }
        reader.start()

        // Mid-run (the node sits in pg_sleep(6)): a real revocation over REST, which evicts the
        // member's cached memberships on this instance immediately.
        Thread.sleep(REVOCATION_DELAY_MILLIS)
        given()
            .port(port)
            .asSession(adminSession)
            .`when`()
            .delete("/api/v1/workspaces/$WS_NAME/members/$MEMBER")
            .then()
            .statusCode(204)

        reader.join(STREAM_JOIN_TIMEOUT_MILLIS)
        if (reader.isAlive) throw AssertionError("the member's stream never ended within ${STREAM_JOIN_TIMEOUT_MILLIS} ms")
        failure[0]?.let { throw it }
        val body = streamBodies.removeFirst()

        // The cut: a final `revoked` SSE comment (the wire renders it `:revoked`) and NOTHING
        // after it — in particular never the terminal sequence, which the subscriber is no
        // longer authorized to read.
        body shouldContain ":revoked"
        body shouldNotContain "event: pipeline_completed"
        body shouldNotContain "event: data_ready"

        // P4's first half: the execution was NOT cancelled by the cut. The admin — whose
        // authority is intact — reads it to COMPLETED, not to the §6.8 disconnect abort.
        val executionId =
            E2eSse
                .parseEvents(body, mapper)
                .firstOrNull()
                ?.second
                ?.get("execution_id")
                ?.asText()
        check(executionId != null) { "the member's stream carried no execution_id: ${body.take(400)}" }
        awaitTerminalCompleted(executionId)
    }

    private fun awaitTerminalCompleted(executionId: String) {
        val deadline = System.currentTimeMillis() + TERMINAL_POLL_TIMEOUT_MILLIS
        var lastStatus: String? = null
        while (System.currentTimeMillis() < deadline) {
            val status =
                given()
                    .port(port)
                    .asSession(adminSession)
                    .`when`()
                    .get("/api/v1/executions/$executionId")
                    .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getString("data.status")
            when (status) {
                // The metadata's status is the ExecutionStatus wire (§10.2): a finished run is
                // SUCCESS — COMPLETED is the stream's close reason, not this field.
                "SUCCESS" -> {
                    return
                }

                "ABORTED", "FAILED" -> {
                    throw AssertionError(
                        "execution $executionId ended $status — the revocation cancelled a run it must not touch (P4)",
                    )
                }
            }
            lastStatus = status
            Thread.sleep(TERMINAL_POLL_MILLIS)
        }
        // Diagnostic for the timeout: what the durable event record holds when the row would
        // not move — the executor's own trail, not a guess.
        val events =
            given()
                .port(port)
                .asSession(adminSession)
                .queryParam("format", "json")
                .`when`()
                .get("/api/v1/executions/$executionId/events")
                .then()
                .extract()
                .body()
                .asString()
        throw AssertionError(
            "execution $executionId did not reach COMPLETED within ${TERMINAL_POLL_TIMEOUT_MILLIS} ms (last status=$lastStatus); " +
                "durable events: ${events?.take(600)}",
        )
    }

    /** Opens the live execute SSE stream and reads it to EOF, off-thread, collecting the body. */
    private fun streamBody(
        pipelineId: String,
        session: String,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header("Cookie", E2eSession.cookieHeader(session))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "execute answered ${response.statusCode()}: ${response.body().take(300)}" }
        streamBodies.add(response.body())
    }

    // ------------------------------------------------------------------ fixtures (namespaced, idempotent)

    private fun ensureSeeded() {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO workspaces (id, name, display_name) VALUES ('$WS', '$WS_NAME', 'SSE230')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                        ('$ADMIN', 'sse230-admin@datapipelines.test', 'SSE230 Admin', 'test', 'sub-sse230-admin', TRUE, TRUE),
                        ('$MEMBER', 'sse230-member@datapipelines.test', 'SSE230 Member', 'test', 'sub-sse230-member', TRUE, FALSE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                        ('$WS', '$ADMIN', 'workspace_admin'),
                        ('$WS', '$MEMBER', 'author')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }
        }
    }

    /** The datasource, the slow template and the one-node pipeline — all REST, all idempotent. */
    private fun fixtures(): String {
        ensureDatasource()
        ensureTemplate()
        return ensurePipeline()
    }

    private fun ensureDatasource() {
        val existing =
            given()
                .port(port)
                .asSession(adminSession)
                .`when`()
                .get("/api/v1/datasources/$DATASOURCE")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(adminSession)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "SSE230 source", "dialect": "POSTGRES",
                 "jdbc_url": "${SharedE2e.postgres.jdbcUrl.substringBefore("?")}",
                 "username": "${SharedE2e.postgres.username}", "password": "${SharedE2e.postgres.password}",
                 "workspace": "$WS_NAME"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun ensureTemplate() {
        val template =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(adminSession)
                .body(
                    """
                    {"id": "$TEMPLATE", "dialect": "POSTGRES", "display_name": "$TEMPLATE",
                     "description": "sse230 harness fixture", "imports": [],
                     "body": ${mapper.writeValueAsString("SELECT pg_sleep(6) AS waited, 1 AS n")}}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/templates")
                .thenReturn()
        // 409 is fine: a re-run finds its own fixture already there.
        if (template.statusCode() !in setOf(201, 409)) {
            throw AssertionError("Template $TEMPLATE failed (${template.statusCode()}): ${template.body().asString()}")
        }
    }

    /** Creates the pipeline (409 → already there, so fetch its id) and returns its UUID. */
    private fun ensurePipeline(): String {
        val nodes =
            """[
              { "id": "slow_read", "type": "DQL", "source": "$DATASOURCE", "template": { "id": "$TEMPLATE", "version": 1 },
                "output": { "target": "caller" }, "depends_on": [] }
            ]"""
        val first =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(adminSession)
                .body("""{"name": "$PIPELINE", "nodes": $nodes}""")
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (first.statusCode() == 201) return first.jsonPath().getString("data.id")
        if (first.statusCode() != 409) {
            throw AssertionError("Pipeline $PIPELINE failed (${first.statusCode()}): ${first.body().asString()}")
        }

        @Suppress("UNCHECKED_CAST")
        val items =
            given()
                .port(port)
                .asSession(adminSession)
                .queryParam("limit", 200)
                .`when`()
                .get("/api/v1/pipelines")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("data.items", Map::class.java)
        return items
            .firstOrNull { it["name"] == PIPELINE }
            ?.let { it["id"].toString() }
            ?: error("pipeline $PIPELINE neither created nor listed — fixture state lost")
    }

    companion object {
        private const val WS = "e2300000-0000-0000-0000-000000000001"
        private const val WS_NAME = "sse230-ws"
        private const val ADMIN = "e2300000-0000-0000-0000-0000000000a1"
        private const val MEMBER = "e2300000-0000-0000-0000-0000000000b2"
        private const val DATASOURCE = "sse230-pg"
        private const val TEMPLATE = "test/sse230_slow.sql"
        private const val PIPELINE = "test/sse230_slowpipe"

        /** The revocation lands while the node sits inside pg_sleep(6). */
        private const val REVOCATION_DELAY_MILLIS = 1_500L
        private const val STREAM_JOIN_TIMEOUT_MILLIS = 60_000L
        private const val TERMINAL_POLL_TIMEOUT_MILLIS = 60_000L
        private const val TERMINAL_POLL_MILLIS = 500L

        /** The reader hands its body to the asserting thread. */
        private val streamBodies = java.util.concurrent.ConcurrentLinkedDeque<String>()

        private val random = SecureRandom()
        private val SECRET: String = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { SECRET }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { OIDC.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // The cut must be observable at the next write, not a minute later: a 1 s heartbeat
            // bounds the revocation-to-cut latency; the 2 s grace makes any "closed stream read
            // as a disconnect" regression abort the run fast enough for the COMPLETED poll to
            // catch it deterministically.
            registry.add("datapipelines.sse.heartbeat-interval-seconds") { "1" }
            registry.add("datapipelines.sse.disconnect-grace-seconds") { "2" }
        }

        private val OIDC = OidcDiscoveryStub()

        @AfterAll
        @JvmStatic
        fun closeOidc() {
            OIDC.close()
        }
    }
}
