package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #517 — the workspace metadata writer guard (release-dependencies record §2, §4.3; brief A.3) over the REAL wired
 * application and the shared test Postgres: a release holds `SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE`
 * from its first statement to its commit, and every pin-affecting writer takes the same row lock first, so no writer
 * can interleave with a release's closure read and its flips.
 *
 * The stand-in for "a release in flight" is a raw connection holding exactly that lock in an open transaction — the
 * property under test is the lock, not the release's other work. Each race is deterministic: the writer is fired on its
 * own thread, the test waits until `pg_stat_activity` (read on ITS OWN connection — a snapshot inside a transaction
 * would be frozen) shows the writer's guard statement waiting on a lock the holder owns, asserts the writer has not
 * answered, commits the holder and then reads the writer's answer. With the guard call removed from the writer under
 * test, the writer answers without ever waiting and the race case is RED (falsification 3).
 *
 * The five races of record `:156`: a draft edit, a draft discard, an inbound pin save, a second release and a visualization
 * evidence completion, each against a release holding the guard. Then: exhausting `lock_timeout` (the production
 * default, 5 s) answers `pipeline.release.busy` with nothing written; another workspace's held row never blocks.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkspaceMutationGuardIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newFixedThreadPool(2)
    private var rpcId = 0

    // ---- the lock holder and the waiter probe -------------------------------------------------------------------

    /** An open transaction holding [workspaceId]'s guard row, as a release in flight does. */
    private class Holder(
        val connection: Connection,
        val pid: Int,
    ) : AutoCloseable {
        override fun close() {
            if (!connection.isClosed) {
                connection.rollback()
                connection.close()
            }
        }
    }

    private fun hold(workspaceId: String): Holder {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        val pid =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE").use { ps ->
            ps.setString(1, workspaceId)
            ps.executeQuery().use { rows -> withClue("the workspace row to hold exists") { rows.next() shouldBe true } }
        }
        return Holder(connection, pid)
    }

    /** Backends waiting for a lock [holderPid] owns while running the guard's statement — on a fresh connection. */
    private fun guardWaiters(holderPid: Int): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))" +
                        " AND query LIKE 'SELECT id FROM workspaces WHERE id = % FOR NO KEY UPDATE'",
                ).use { ps ->
                    ps.setInt(1, holderPid)
                    ps.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                }
        }

    /** Polls until the writer waits on the holder's guard row, or the writer answers first (the guard is not there). */
    private fun awaitGuardWait(
        holder: Holder,
        writer: CompletableFuture<*>,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while (!writer.isDone && System.nanoTime() < deadline) {
            if (guardWaiters(holder.pid) > 0) return true
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        return false
    }

    /**
     * The race: [writer] fired while a release holds the guard must WAIT on it — observed, not timed — and answer only
     * after the holder commits. Returns the writer's answer.
     */
    private fun <T> waitsForTheGuard(writer: () -> T): T =
        hold(WORKSPACE_ID).use { holder ->
            val answer = CompletableFuture.supplyAsync({ writer() }, pool)
            withClue("the writer must block on the workspace guard a release holds (answered first: ${answer.isDone})") {
                awaitGuardWait(holder, answer) shouldBe true
            }
            withClue("the writer cannot answer while the guard is held") { answer.isDone shouldBe false }
            holder.connection.commit()
            answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    // ---- wire helpers -----------------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val id = synchronized(this) { ++rpcId }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to id,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { body.has("error") shouldBe false }
        val result = body["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun pipelineArguments(
        name: String,
        templateId: String = TEMPLATE,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "Writer guard (517)",
            "description" to "One node pinning the shared template.",
            "nodes" to
                listOf(
                    mapOf(
                        "id" to "n0",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "description" to "One row",
                        "template" to mapOf("id" to templateId, "version" to 1),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
        )

    /** Creates a DRAFT pipeline over MCP; its id and draft hash. */
    private fun createPipeline(name: String): Pair<String, String> {
        val (created, isError) = callTool("pipelines_create", pipelineArguments(name))
        withClue("pipelines_create must succeed: $created") { isError shouldBe false }
        return created["id"].asText() to created["draft"]["body_hash"].asText()
    }

    /** A draft edit over MCP at [hash] — the description changes, so the body hash does. */
    private fun editPipeline(
        name: String,
        id: String,
        hash: String,
    ): Pair<JsonNode, Boolean> =
        callTool(
            "pipelines_update",
            pipelineArguments(name) + mapOf("id" to id, "expected_hash" to hash, "description" to "Edited."),
        )

    private fun discard(
        id: String,
        hash: String,
    ): Response = rest().header("If-Match", hash).post("/api/v1/pipelines/$id/draft/discard")

    private fun release(
        id: String,
        hash: String,
        alias: Boolean,
    ): Response =
        rest()
            .header("If-Match", hash)
            .queryParam("release_pinned_templates", alias.toString())
            .post("/api/v1/pipelines/$id/release")

    private fun rest(): RequestSpecification = given().port(port).asSession(SESSION)

    private fun draftHash(pipelineId: String): String? =
        rest()
            .get("/api/v1/pipelines/$pipelineId")
            .then()
            .extract()
            .jsonPath()
            .getString("data.draft.body_hash")

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - an owned H2 datasource and the DRAFT template every pipeline pins`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        rest()
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Writer guard (517)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
        val (template, isError) =
            callTool(
                "templates_create",
                mapOf(
                    "id" to TEMPLATE,
                    "dialect" to "H2",
                    "display_name" to "Writer guard (517)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 1 AS n",
                ),
            )
        withClue("templates_create must succeed: $template") { isError shouldBe false }
        // Non-vacuity of the probe: nobody waits on the guard row before any race starts.
        hold(WORKSPACE_ID).use { holder -> guardWaiters(holder.pid) shouldBe 0 }
    }

    @Test
    @Order(2)
    fun `race 1 - a draft edit waits for a release holding the guard, then lands`() {
        val (id, hash) = createPipeline("test/guard_517_edit")

        val (updated, isError) =
            waitsForTheGuard { editPipeline("test/guard_517_edit", id, hash) }

        withClue("the edit lands once the release committed: $updated") { isError shouldBe false }
        updated["draft"]["body_hash"].asText() shouldNotBe hash
    }

    @Test
    @Order(3)
    fun `race 2 - a draft discard waits for a release holding the guard, then purges`() {
        val (id, hash) = createPipeline("test/guard_517_discard")

        val response: Response = waitsForTheGuard { discard(id, hash) }

        withClue(response.asString()) { response.statusCode shouldBe 204 }
        rest().get("/api/v1/pipelines/$id").statusCode shouldBe 404
    }

    @Test
    @Order(4)
    fun `race 3 - an inbound pin save of the template waits for a purge or release holding the guard, then lands`() {
        val (created, isError) = waitsForTheGuard { callTool("pipelines_create", pipelineArguments("test/guard_517_pin")) }

        withClue("the pin save lands once the guard is free: $created") { isError shouldBe false }
        created["draft"]["body_hash"].isTextual shouldBe true
    }

    @Test
    @Order(5)
    fun `race 4 - a second release waits for the first to commit, then releases`() {
        // Unheld: release the shared template through the alias, so the release under test flips nothing but its
        // root — the template service's own guard (taken when a cascade flips a template) cannot stand in for the
        // coordinator's, and removing the coordinator's call is what turns this case red.
        val (warmUp, warmUpHash) = createPipeline("test/guard_517_release_first")
        release(warmUp, warmUpHash, alias = true).statusCode shouldBe 200
        val (id, hash) = createPipeline("test/guard_517_release")

        val response: Response = waitsForTheGuard { release(id, hash, alias = false) }

        withClue(response.asString()) { response.statusCode shouldBe 200 }
        response.jsonPath().getString("data.status") shouldBe "RELEASED"
    }

    @Test
    @Order(6)
    fun `race 5 - a visualization's evidence completion waits for a release holding the guard, then records GREEN`() {
        val created = rest().contentType(ContentType.JSON).body(VISUALIZATION).post("/api/v1/visualizations")
        withClue(created.asString()) { created.statusCode shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        val started = rest().post("/api/v1/visualizations/$id/tests/sessions")
        withClue(started.asString()) { started.statusCode shouldBe 201 }
        val sessionId = started.jsonPath().getString("data.session_id")

        val submitted: Response =
            waitsForTheGuard {
                rest()
                    .contentType(ContentType.JSON)
                    .body("""{"cases":[{"name":"one month","verdict":"green"}],"environment":{"browser":"chromium","theme":"light"}}""")
                    .post("/api/v1/visualizations/$id/tests/sessions/$sessionId/results")
            }

        withClue(submitted.asString()) { submitted.statusCode shouldBe 200 }
        submitted.jsonPath().getString("data.status") shouldBe "GREEN"
    }

    @Test
    @Order(7)
    fun `a guard held past lock_timeout answers busy and writes nothing`() {
        val (id, hash) = createPipeline("test/guard_517_busy")

        val response =
            hold(WORKSPACE_ID).use { holder ->
                val answer = CompletableFuture.supplyAsync({ discard(id, hash) }, pool)
                awaitGuardWait(holder, answer) shouldBe true
                // Held until the writer gives up: the production default bound, then the refusal.
                answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

        withClue(response.asString()) { response.statusCode shouldBe 409 }
        response.jsonPath().getString("error.code") shouldBe "pipeline.release.busy"
        response.jsonPath().getInt("error.details.lock_wait_seconds") shouldBe DEFAULT_LOCK_WAIT_SECONDS
        withClue("nothing was written: the draft is still there at its hash") { draftHash(id) shouldBe hash }
    }

    @Test
    @Order(8)
    fun `another workspace's held guard never blocks this workspace's writer`() {
        val (id, hash) = createPipeline("test/guard_517_other")

        hold(OTHER_WORKSPACE_ID).use { holder ->
            val answer = CompletableFuture.supplyAsync({ editPipeline("test/guard_517_other", id, hash) }, pool)
            // Answered while the OTHER workspace's row is still held.
            val (updated, isError) = answer.get(UNBLOCKED_SECONDS, TimeUnit.SECONDS)
            withClue(updated) { isError shouldBe false }
            guardWaiters(holder.pid) shouldBe 0
        }
    }

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val TEMPLATE = "test/guard_517.sql"
        private const val DATASOURCE = "h2-guard-517"
        private const val H2_JDBC_URL = "jdbc:h2:mem:guard517;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "guard-517"

        /** The production default of `datapipelines.release.dependencies.lock-wait-seconds` (configuration §3.35). */
        private const val DEFAULT_LOCK_WAIT_SECONDS = 5

        /** The probe gives up before the writer's own lock_timeout would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L
        private const val UNBLOCKED_SECONDS = 4L

        private val VISUALIZATION =
            """
            {"name": "test/charts/guard_517", "display_name": "Guard evidence", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "presentation": {"title": "Guard evidence"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val OTHER_WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-517-guard-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-517-guard@datapipelines.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // API mode: this suite only authors — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Writer guard 517')," +
                            " ('$OTHER_WORKSPACE_ID', 'guard-517-other', 'Writer guard 517 other')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin (an author there).
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-517-guard@datapipelines.test', 'E2E 517', 'test', 'e2e-517-guard-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', " +
                            "'${KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_ID)
                        ps.setString(4, KEY.name)
                        ps.setString(5, KEY.hash)
                        ps.setString(6, WORKSPACE_ID)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
