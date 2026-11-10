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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
 * #841 (G0) R3 of the landing review — the NATIVE template writers that exist on the base, proven at the wire over the
 * real application and the shared test Postgres: the template import (`POST /api/v1/templates/import`, preserved-version
 * and version-less), the released-version discard and restore, the current switch, and the draft update (MCP
 * `templates_update`), which is guarded separately from the create the round-1 suite covers.
 *
 * Two shapes, as in `G0WriterContentionE2eTest`:
 *
 * - **busy** — a raw connection holds the workspace row (`FOR NO KEY UPDATE`, as a release in flight holds it); the
 *   writer is observed WAITING on it (transitive `pg_blocking_pids` on a fresh connection), exhausts the production
 *   bound (5 s) and answers `template.release.busy` with details exactly `{lock_wait_seconds}`; nothing was written, and
 *   the same writer then succeeds once the row is free (the successful control).
 * - **semantic interleaving** — two real writers queue FIFO behind the held row (the second is fired only once the first
 *   is observed waiting, and is itself observed waiting); the holder commits; the first lands and the second decides on
 *   the committed state. Without the guard on the second writer it never waits — the probe goes RED — and its outcome
 *   flips (asserted, persisted state and audit read back).
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class G0NativeTemplateWritersE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newFixedThreadPool(3)
    private var rpcId = 0

    /** The preserved-version import entry of [TEMPLATE] v1, exported in the fixture (`GET /versions`). */
    private lateinit var exportedV1: JsonNode

    // ---- the holder and the waiter probe ----------------------------------------------------------------------

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

    private fun holdWorkspace(): Holder {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE").use { ps ->
            ps.setString(1, WORKSPACE_ID)
            ps.execute()
        }
        val pid =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use { it.next().let { _ -> it.getInt(1) } }
            }
        return Holder(connection, pid)
    }

    private fun jdbc() = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

    /** Polls until [count] backends wait on [holder], directly or behind another waiter; RED when [writer] answered first. */
    private fun awaitWaiters(
        holder: Holder,
        writer: CompletableFuture<*>,
        count: Int = 1,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while ((jdbc().queryForObject(WAITERS_SQL, Int::class.java, holder.pid) ?: 0) < count) {
            withClue("the writer answered without waiting on the held workspace row: ${if (writer.isDone) writer.join() else ""}") {
                writer.isDone shouldBe false
            }
            withClue("no $count waiter(s) on the holder within the probe window") { (System.nanoTime() < deadline) shouldBe true }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
    }

    /** [writer] against a held workspace row, observed waiting, left to exhaust the production bound. */
    private fun <T> exhausts(writer: () -> T): T =
        holdWorkspace().use { holder ->
            val answer = CompletableFuture.supplyAsync({ writer() }, pool)
            awaitWaiters(holder, answer)
            answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    /** [first] then [second] queued FIFO behind one held row; the holder commits; both answers. */
    private fun <A, B> queued(
        first: () -> A,
        second: () -> B,
    ): Pair<A, B> =
        holdWorkspace().use { holder ->
            val a = CompletableFuture.supplyAsync({ first() }, pool)
            awaitWaiters(holder, a)
            val b = CompletableFuture.supplyAsync({ second() }, pool)
            awaitWaiters(holder, b, count = 2)
            holder.connection.commit()
            a.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS) to b.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    private fun assertRestBusy(response: Response) {
        withClue(response.asString()) { response.statusCode shouldBe 409 }
        val error = mapper.readTree(response.asString())["error"]
        error["code"].asText() shouldBe BUSY_CODE
        error["message"].asText() shouldBe BUSY_MESSAGE
        error["details"] shouldBe mapper.readTree("""{"lock_wait_seconds": $DEFAULT_LOCK_WAIT_SECONDS}""")
    }

    // ---- wire helpers -----------------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val id = synchronized(this) { ++rpcId }
        val body =
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "method" to "tools/call",
                "params" to mapOf("name" to name, "arguments" to arguments),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val tree = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { tree.has("error") shouldBe false }
        val result = tree["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun rest(): RequestSpecification = given().port(port).asSession(SESSION)

    private fun json(body: Any): RequestSpecification = rest().contentType(ContentType.JSON).body(mapper.writeValueAsString(body))

    private fun createTemplate(id: String) {
        val (created, isError) =
            callTool(
                "templates_create",
                mapOf(
                    "id" to id,
                    "dialect" to "H2",
                    "display_name" to "G0 native",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 1 AS n",
                ),
            )
        withClue("templates_create must succeed: $created") { isError shouldBe false }
    }

    /** The working version's `body_hash` (`GET ?name=`: the draft when one exists, else the latest released). */
    private fun workingHash(id: String): String =
        rest()
            .queryParam("name", id)
            .get("/api/v1/templates")
            .jsonPath()
            .getString("data.body_hash")

    private fun release(id: String) {
        val released = json(mapOf("name" to id)).header("If-Match", workingHash(id)).post("/api/v1/templates/release")
        withClue(released.asString()) { released.statusCode shouldBe 200 }
    }

    private fun discardVersion(id: String): Response = json(mapOf("name" to id, "version" to 1)).post("/api/v1/templates/version/discard")

    private fun restoreVersion(id: String): Response = json(mapOf("name" to id, "version" to 1)).post("/api/v1/templates/version/restore")

    private fun switchCurrent(
        id: String,
        version: Int = 1,
    ): Response = json(mapOf("name" to id, "version" to version)).post("/api/v1/templates/current")

    private fun importTemplates(vararg entries: Any): Response =
        json(mapOf("templates" to entries.toList())).post("/api/v1/templates/import")

    private fun versionLessEntry(id: String): Map<String, Any?> =
        mapOf(
            "id" to id,
            "dialect" to "H2",
            "display_name" to "G0 imported",
            "description" to "Expects: nothing (plain SQL).",
            "body" to "SELECT 3 AS n",
        )

    private fun update(
        id: String,
        expectedHash: String,
        body: String,
    ): Pair<JsonNode, Boolean> =
        callTool(
            "templates_update",
            mapOf(
                "id" to id,
                "expected_hash" to expectedHash,
                "display_name" to "G0 native",
                "description" to "Expects: nothing (plain SQL).",
                "body" to body,
            ),
        )

    /** Every stored version of [id] in this workspace, `version:STATUS` ascending — the persisted state. */
    private fun versions(id: String): String =
        jdbc().queryForObject(
            "SELECT coalesce(string_agg(v.version || ':' || v.status, ',' ORDER BY v.version), '') FROM template_versions v" +
                " JOIN templates t ON t.id = v.template_id WHERE t.workspace_id = ?::uuid AND t.name = ?",
            String::class.java,
            WORKSPACE_ID,
            id,
        ) ?: ""

    /**
     * Audit rows of [event] for template [id], read after a FENCE: a successful current switch audited by the same
     * session. The audit writer is a batching sink, FIFO per key (the session's user), so once the fence's row is
     * visible every row the earlier calls wrote is too — an absence read after it is a real absence.
     */
    private fun auditedAfterFence(
        event: String,
        id: String,
    ): Int {
        val fenceBefore = auditRows(SWITCHED_EVENT, FENCE_TEMPLATE)
        withClue("the audit fence (a current switch) must succeed") { switchCurrent(FENCE_TEMPLATE).statusCode shouldBe 200 }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AUDIT_WAIT_SECONDS)
        while (auditRows(SWITCHED_EVENT, FENCE_TEMPLATE) <= fenceBefore) {
            withClue("the fence's audit row never appeared") { (System.nanoTime() < deadline) shouldBe true }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        return auditRows(event, id)
    }

    private fun auditRows(
        event: String,
        id: String,
    ): Int =
        jdbc().queryForObject(
            "SELECT count(*) FROM audit_log WHERE event = ? AND user_id = ?::uuid AND details_json ->> 'template_id' = ?",
            Int::class.java,
            event,
            ADMIN_ID,
            id,
        ) ?: 0

    // ---- the fixture ------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - released templates, an exported v1, and an unheld preserved re-import is the idempotent no-op`() {
        rest()
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "G0 native writers", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
        listOf(TEMPLATE, FENCE_TEMPLATE, DRAFTED).forEach {
            createTemplate(it)
            release(it)
        }
        exportedV1 =
            mapper.readTree(
                rest()
                    .queryParam("name", TEMPLATE)
                    .queryParam("version", 1)
                    .get("/api/v1/templates/versions")
                    .asString(),
            )["data"]
        exportedV1["version"].asInt() shouldBe 1
        exportedV1["body_hash"].asText() shouldNotBe ""

        val control = importTemplates(exportedV1)
        withClue(control.asString()) { control.statusCode shouldBe 200 }
        control.jsonPath().getInt("data.templates[0].version") shouldBe 1
        versions(TEMPLATE) shouldBe "1:RELEASED"
    }

    // ---- import / discard / restore interleavings -------------------------------------------------------------

    @Test
    @Order(2)
    fun `a preserved import queued behind a version discard is refused - the discarded number is never reused`() {
        val (discarded, imported) = queued({ discardVersion(TEMPLATE) }, { importTemplates(exportedV1) })

        withClue(discarded.asString()) { discarded.statusCode shouldBe 200 }
        discarded.jsonPath().getString("data.status") shouldBe "DISCARDED"
        // Unguarded, the import would have read v1 RELEASED before the discard committed: an idempotent 200.
        withClue(imported.asString()) { imported.statusCode shouldBe 409 }
        imported.jsonPath().getString("error.code") shouldBe "template.version.conflict"
        imported.jsonPath().getString("error.details.target_status") shouldBe "DISCARDED"
        versions(TEMPLATE) shouldBe "1:DISCARDED"
        auditedAfterFence(DISCARDED_EVENT, TEMPLATE) shouldBe 1
    }

    @Test
    @Order(3)
    fun `a preserved import queued behind a restore lands as the idempotent no-op on the restored version`() {
        val (restored, imported) = queued({ restoreVersion(TEMPLATE) }, { importTemplates(exportedV1) })

        withClue(restored.asString()) { restored.statusCode shouldBe 200 }
        restored.jsonPath().getString("data.status") shouldBe "RELEASED"
        // Unguarded, the import would have read v1 DISCARDED: `template.version.conflict`.
        withClue(imported.asString()) { imported.statusCode shouldBe 200 }
        imported.jsonPath().getInt("data.templates[0].version") shouldBe 1
        versions(TEMPLATE) shouldBe "1:RELEASED"
        auditedAfterFence(RESTORED_EVENT, TEMPLATE) shouldBe 1
    }

    @Test
    @Order(4)
    fun `a version-less import queued behind a restore appends version 2 onto the restored template`() {
        withClue("v1 discarded again, unheld") { discardVersion(TEMPLATE).statusCode shouldBe 200 }
        val (restored, imported) = queued({ restoreVersion(TEMPLATE) }, { importTemplates(versionLessEntry(TEMPLATE)) })

        withClue(restored.asString()) { restored.statusCode shouldBe 200 }
        // Unguarded, the import would have found no working version (every version DISCARDED): `template.not_found`.
        withClue(imported.asString()) { imported.statusCode shouldBe 200 }
        imported.jsonPath().getInt("data.templates[0].version") shouldBe 2
        versions(TEMPLATE) shouldBe "1:RELEASED,2:RELEASED"
        auditedAfterFence(RESTORED_EVENT, TEMPLATE) shouldBe 2
    }

    // ---- busy at the import, discard, restore and switch boundaries -------------------------------------------

    @Test
    @Order(5)
    fun `template import over REST - template release busy, nothing imported - then the import lands`() {
        assertRestBusy(exhausts { importTemplates(versionLessEntry(IMPORTED)) })
        versions(IMPORTED) shouldBe ""

        withClue("the successful control") { importTemplates(versionLessEntry(IMPORTED)).statusCode shouldBe 200 }
        versions(IMPORTED) shouldBe "1:RELEASED"
    }

    @Test
    @Order(6)
    fun `template version discard over REST - template release busy, the version stays released and unaudited`() {
        val before = auditedAfterFence(DISCARDED_EVENT, IMPORTED)
        assertRestBusy(exhausts { discardVersion(IMPORTED) })
        versions(IMPORTED) shouldBe "1:RELEASED"
        auditedAfterFence(DISCARDED_EVENT, IMPORTED) shouldBe before

        withClue("the successful control") { discardVersion(IMPORTED).statusCode shouldBe 200 }
        versions(IMPORTED) shouldBe "1:DISCARDED"
    }

    @Test
    @Order(7)
    fun `template version restore over REST - template release busy, the version stays discarded - then it restores`() {
        assertRestBusy(exhausts { restoreVersion(IMPORTED) })
        versions(IMPORTED) shouldBe "1:DISCARDED"
        auditedAfterFence(RESTORED_EVENT, IMPORTED) shouldBe 0

        withClue("the successful control") { restoreVersion(IMPORTED).statusCode shouldBe 200 }
        versions(IMPORTED) shouldBe "1:RELEASED"
    }

    @Test
    @Order(8)
    fun `template current switch over REST - template release busy, the pointer stays - then it switches`() {
        val before = currentVersion(TEMPLATE)
        val target = if (before == 1) 2 else 1
        assertRestBusy(exhausts { switchCurrent(TEMPLATE, target) })
        currentVersion(TEMPLATE) shouldBe before

        withClue("the successful control") { switchCurrent(TEMPLATE, target).statusCode shouldBe 200 }
        currentVersion(TEMPLATE) shouldBe target
    }

    // ---- the pin witness on the discard boundary --------------------------------------------------------------

    @Test
    @Order(9)
    fun `a version discard queued behind a pin save is refused template in_use once the pin committed`() {
        val (pinned, refused) = queued({ callTool("pipelines_create", pipelineArguments(PINNER, TEMPLATE)) }, { discardVersion(TEMPLATE) })

        withClue("the pin save lands first: ${pinned.first}") { pinned.second shouldBe false }
        // Unguarded, the discard would have run before the pin committed and flipped the pinned v1 to DISCARDED.
        withClue(refused.asString()) { refused.statusCode shouldBe 409 }
        refused.jsonPath().getString("error.code") shouldBe "template.in_use"
        versions(TEMPLATE) shouldBe "1:RELEASED,2:RELEASED"
        auditedAfterFence(DISCARDED_EVENT, TEMPLATE) shouldBe 2
    }

    // ---- the separately guarded draft update (MCP templates_update) -------------------------------------------

    @Test
    @Order(10)
    fun `template draft update over MCP - template release busy, no draft opened - then the update opens one`() {
        val released = workingHash(DRAFTED)
        val (payload, isError) = exhausts { update(DRAFTED, released, "SELECT 2 AS n") }
        withClue(payload) { isError shouldBe true }
        payload["error"]["code"].asText() shouldBe BUSY_CODE
        payload["error"]["message"].asText() shouldBe BUSY_MESSAGE
        payload["error"]["details"] shouldBe mapper.readTree("""{"lock_wait_seconds": $DEFAULT_LOCK_WAIT_SECONDS}""")
        versions(DRAFTED) shouldBe "1:RELEASED"
        workingHash(DRAFTED) shouldBe released

        val (opened, failed) = update(DRAFTED, released, "SELECT 2 AS n")
        withClue("the successful control: $opened") { failed shouldBe false }
        versions(DRAFTED) shouldBe "1:RELEASED,2:DRAFT"
    }

    @Test
    @Order(11)
    fun `a draft update queued behind a draft purge is refused stale - the purge committed first, nothing reopened`() {
        val draftHash = workingHash(DRAFTED)
        val (purged, updated) =
            queued(
                { json(mapOf("name" to DRAFTED)).header("If-Match", draftHash).post("/api/v1/templates/draft/discard") },
                { update(DRAFTED, draftHash, "SELECT 4 AS n") },
            )

        withClue(purged.asString()) { purged.statusCode shouldBe 204 }
        // Unguarded, the update would have overwritten the draft first (its hash still matched) and the purge, holding a
        // stale If-Match, would have been the one refused.
        withClue(updated.first) { updated.second shouldBe true }
        updated.first["error"]["code"].asText() shouldBe "template.version.conflict"
        versions(DRAFTED) shouldBe "1:RELEASED"
        auditedAfterFence(PURGED_EVENT, DRAFTED) shouldBe 1
    }

    private fun currentVersion(id: String): Int =
        jdbc().queryForObject(
            "SELECT current_version FROM templates WHERE workspace_id = ?::uuid AND name = ?",
            Int::class.java,
            WORKSPACE_ID,
            id,
        )
            ?: 0

    private fun pipelineArguments(
        name: String,
        templateId: String,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "G0 native pinner",
            "description" to "One node pinning a template.",
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

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val TEMPLATE = "test/g0n/shared.sql"
        private const val FENCE_TEMPLATE = "test/g0n/fence.sql"
        private const val DRAFTED = "test/g0n/drafted.sql"
        private const val IMPORTED = "test/g0n/imported.sql"
        private const val PINNER = "test/g0n/pinner"
        private const val DATASOURCE = "h2-g0n-841"
        private const val H2_JDBC_URL = "jdbc:h2:mem:g0n841;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "g0n-841"

        private const val BUSY_CODE = "template.release.busy"
        private const val BUSY_MESSAGE = "Another change in this workspace is in progress. Nothing was written; try again in a moment."
        private const val DISCARDED_EVENT = "template.version.discarded"
        private const val RESTORED_EVENT = "template.version.restored"
        private const val PURGED_EVENT = "template.version.purged"
        private const val SWITCHED_EVENT = "template.current_switched"

        /** The production default of `datapipelines.release.dependencies.lock-wait-seconds`. */
        private const val DEFAULT_LOCK_WAIT_SECONDS = 5

        /** The probe gives up before the writer's own lock wait would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private const val AUDIT_WAIT_SECONDS = 10L
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L
        private val WAITERS_SQL =
            """
            WITH RECURSIVE waiting(pid) AS (
                SELECT a.pid FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid))
                UNION
                SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
            )
            SELECT count(*) FROM waiting
            """.trimIndent()

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-841-g0n-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-841-g0n@datapipelines.test", WORKSPACE)

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
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'G0 native 841')",
                    )
                    // A super admin (the instance datasource) who is also this workspace's admin.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-841-g0n@datapipelines.test', 'E2E 841 native', 'test', 'e2e-841-g0n-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES" +
                            " ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', '${KEY.id}'," +
                            " TRUE, FALSE, 'service')",
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
