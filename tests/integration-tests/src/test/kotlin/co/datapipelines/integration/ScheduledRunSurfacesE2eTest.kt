package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #275 — R3 "on every surface" (rest-api §10.1, v2.40) held on the four execution lists that
 * still read own-only until #275: the search palette's executions group, the pipeline
 * explorer's Runs tab, the template explorer's Runs tab, and MCP's executions RESOURCE listing.
 *
 * The fixture is one released pipeline pinning one template, and two runs of it: Alice's own
 * interactive run and a run a SCHEDULE fired (`triggered_via = SCHEDULE`, executed by the
 * scheduler's system identity — nobody's own). Every surface is held to the same answer
 * `findVisible` gives the REST list:
 *
 * - **a viewer** (Vera) sees the scheduled run and NOT another member's own run;
 * - **a promoter** (Pam) reaches the three UI panes through `pipeline.read`/`template.read` but
 *   holds no `execution.read`, so R3's scheduled arm is not hers — she sees neither run
 *   (the same answer `visibleTo` gives a single read);
 * - over MCP, an **author-role key** (its own identity, keys v2 A13) lists the scheduled run and
 *   not Alice's session run, and a **workspace-admin-role key** lists both (`execution.read_all`);
 * - #275 item 5: `executions_get_result` answers the scheduled run for the author-role key (not
 *   `execution_not_found`) and refuses another member's interactive run as not found.
 *
 * The promoter's lens reads a STUB higher environment that holds nothing (the
 * `SchedulePromoterPagingE2eTest` shape), so the released pipeline and template are newer there and
 * the lens ADMITS them — without it the lens fails closed, the panes are empty for any read, and
 * the promoter case could not go red (measured: it stayed green with R3 opened to her). The
 * promoter test therefore first proves the lens admits the fixture.
 *
 * Beside `ExecutionsVisibilityTest`, whose session-JWT, key and seeding shape this follows. A
 * separate suite, not three more rows in that one: adding a scheduled run to its fixture would
 * change every listing it pins.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ScheduledRunSurfacesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the search palette - a viewer finds the scheduled run and not another member's own run`() {
        ensureSeeded()
        val html = partial(VERA, "/partials/search?q=scheduled_surfaces")
        withClue("the search palette as a viewer") {
            html shouldContain RUN_SCHEDULED
            html shouldNotContain RUN_ALICE
        }
    }

    @Test
    fun `the pipeline explorer's Runs tab - a viewer sees the scheduled run and not another member's own run`() {
        ensureSeeded()
        val html = partial(VERA, "/partials/pipelines/$PIPE_ID/runs")
        withClue("the pipeline Runs tab as a viewer") {
            html shouldContain RUN_SCHEDULED
            html shouldNotContain RUN_ALICE
        }
    }

    @Test
    fun `the template explorer's Runs tab - a viewer sees the scheduled run and not another member's own run`() {
        ensureSeeded()
        val html = partial(VERA, "/partials/templates/runs?name=$TPL_NAME")
        withClue("the template Runs tab as a viewer") {
            html shouldContain RUN_SCHEDULED
            html shouldNotContain RUN_ALICE
        }
    }

    @Test
    fun `a promoter reaches the three panes but R3's scheduled arm is execution_read's - she sees neither run`() {
        ensureSeeded()
        // Non-vacuity: the lens ADMITS the pipeline and the template, so an empty pane below is
        // the visibility read's answer, not the lens hiding everything.
        val palette = partial(PAM, "/partials/search?q=scheduled_surfaces")
        withClue("the promoter's lens admits the fixture") {
            palette shouldContain PIPE_NAME
            palette shouldContain TPL_NAME
        }
        listOf(
            "/partials/search?q=scheduled_surfaces",
            "/partials/pipelines/$PIPE_ID/runs",
            "/partials/templates/runs?name=$TPL_NAME",
        ).forEach { path ->
            val html = partial(PAM, path)
            withClue("$path as a promoter") {
                html shouldNotContain RUN_SCHEDULED
                html shouldNotContain RUN_ALICE
            }
        }
    }

    @Test
    fun `the MCP executions resource - an author-role key lists the scheduled run, not another member's own run`() {
        ensureSeeded()
        val authorList = resourcesList(ALICE_KEY.plaintext)
        withClue("resources/list for the author-role key") {
            authorList shouldContain "executions/$RUN_SCHEDULED"
            authorList shouldNotContain "executions/$RUN_ALICE"
        }
        // execution.read_all: the workspace-admin-role key lists the workspace's runs, as
        // executions_list does — the list read is visibleTo's rule decided in SQL.
        val adminList = resourcesList(WANDA_KEY.plaintext)
        withClue("resources/list for the workspace-admin-role key") {
            adminList shouldContain "executions/$RUN_SCHEDULED"
            adminList shouldContain "executions/$RUN_ALICE"
        }
    }

    @Test
    fun `executions_get_result - the scheduled run is visible to an author-role key, another member's interactive run is not found`() {
        ensureSeeded()
        tool("executions_get_result", ALICE_KEY.plaintext, """{"execution_id":"$RUN_SCHEDULED"}""") shouldNotContain
            "execution_not_found"
        // Alice's own SESSION run is not the key's — the key acts as its own identity (A13).
        tool("executions_get_result", ALICE_KEY.plaintext, """{"execution_id":"$RUN_ALICE"}""") shouldContain
            "execution_not_found"
    }

    // ------------------------------------------------------------------ drivers

    private fun partial(
        userId: String,
        path: String,
    ): String =
        session(userId)
            .accept("text/html")
            .header("HX-Request", "true")
            .get(path)
            .then()
            .statusCode(200)
            .extract()
            .asString()

    private fun resourcesList(key: String): String = rpc(key, """{"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}""")

    private fun tool(
        name: String,
        key: String,
        arguments: String,
    ): String = rpc(key, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""")

    private fun rpc(
        key: String,
        body: String,
    ): String =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body(body)
            .`when`()
            .post("/mcp")
            .then()
            .statusCode(200)
            .extract()
            .asString()

    private fun session(userId: String): RequestSpecification =
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(userId, "$userId@acme.test"))
            .cookie(CSRF_COOKIE, CSRF)
            .header(CSRF_HEADER, CSRF)

    companion object {
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val CSRF = "scheduled-run-surfaces-csrf"
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val SECRET_BYTES = 32
        private const val TOKEN_TTL_SECONDS = 3600L

        private const val WS_ACME = "aca00000-0000-0000-0000-000000000275"
        private const val ALICE = "aaa00000-0000-0000-0000-000000000275"
        private const val VERA = "eee00000-0000-0000-0000-000000000275"
        private const val WANDA = "ada00000-0000-0000-0000-000000000275"
        private const val PAM = "bbb00000-0000-0000-0000-000000000275"

        /** The scheduler's system identity stand-in: a scheduled run is executed by no member (R2). */
        private const val SCHEDULER = "5c500000-0000-0000-0000-000000000275"
        private const val PIPE_ID = "a1b00000-0000-0000-0000-000000000275"
        private const val PIPE_NAME = "acme/scheduled_surfaces"
        private const val TPL_ID = "7a100000-0000-0000-0000-000000000275"
        private const val TPL_NAME = "acme/scheduled_surfaces.sql"
        private const val RUN_ALICE = "e1000000-0000-0000-0000-000000000275"
        private const val RUN_SCHEDULED = "e5000000-0000-0000-0000-000000000275"

        /** The mcp keys' own `service` identities (keys v2 A13). */
        private const val ALICE_KEY_IDENTITY = "5e500000-0000-0000-0000-000000000275"
        private const val WANDA_KEY_IDENTITY = "5e500000-0000-0000-0000-000000000276"

        private val ALICE_KEY = E2eAuth.generateKey("alice-275-key", ownerId = ALICE_KEY_IDENTITY)
        private val WANDA_KEY = E2eAuth.generateKey("wanda-275-key", ownerId = WANDA_KEY_IDENTITY)

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private fun sessionJwt(
            userId: String,
            email: String,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val exp = now.plusSeconds(TOKEN_TTL_SECONDS).epochSecond
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Test User",""" +
                        """"iss":"datapipelines","iat":${now.epochSecond},"exp":$exp,"active_workspace":"acme"}""",
                )
            val signature =
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(Base64.getDecoder().decode(jwtSecret), "HmacSHA256"))
                    b64(doFinal("$header.$payload".toByteArray(Charsets.UTF_8)))
                }
            return "$header.$payload.$signature"
        }

        private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

        private var seeded = false

        fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement -> seedRows().forEach { statement.execute(it) } }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?, 'mcp', ?)",
                    ).use { ps ->
                        val keys = listOf(Triple(ALICE_KEY, ALICE, "author"), Triple(WANDA_KEY, WANDA, "workspace_admin"))
                        keys.forEach { (key, creator, role) ->
                            ps.setString(1, key.id)
                            ps.setObject(2, UUID.fromString(key.ownerId))
                            ps.setObject(3, UUID.fromString(creator))
                            ps.setString(4, key.name)
                            ps.setString(5, key.hash)
                            ps.setObject(6, UUID.fromString(WS_ACME))
                            ps.setString(7, role)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
            }
        }

        /** The fixture, in FK order: workspace, users, memberships, a template, a pipeline pinning it, two runs. */
        private fun seedRows(): List<String> =
            listOf(
                "INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ACME', 'acme', 'Acme')",
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                    ('$ALICE', '$ALICE@acme.test', 'Alice', 'test', 'alice-275-sub', TRUE, FALSE),
                    ('$VERA', '$VERA@acme.test', 'Vera', 'test', 'vera-275-sub', TRUE, FALSE),
                    ('$WANDA', '$WANDA@acme.test', 'Wanda', 'test', 'wanda-275-sub', TRUE, FALSE),
                    ('$PAM', '$PAM@acme.test', 'Pam', 'test', 'pam-275-sub', TRUE, FALSE)
                """.trimIndent(),
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES
                    ('$SCHEDULER', 'scheduler-275@system.invalid', 'Scheduler', 'system', 'scheduler-275', TRUE, FALSE, 'system'),
                    ('$ALICE_KEY_IDENTITY', '${ALICE_KEY.id.lowercase()}@keys.invalid', 'alice-275-key', 'key', '${ALICE_KEY.id}', TRUE, FALSE, 'service'),
                    ('$WANDA_KEY_IDENTITY', '${WANDA_KEY.id.lowercase()}@keys.invalid', 'wanda-275-key', 'key', '${WANDA_KEY.id}', TRUE, FALSE, 'service')
                """.trimIndent(),
                """
                INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                    ('$WS_ACME', '$ALICE', 'author'),
                    ('$WS_ACME', '$VERA', 'viewer'),
                    ('$WS_ACME', '$WANDA', 'workspace_admin'),
                    ('$WS_ACME', '$PAM', 'promoter')
                """.trimIndent(),
                """
                INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by) VALUES
                    ('$TPL_ID', '$TPL_NAME', 'Scheduled surfaces', '', 1, '$WS_ACME', '$ALICE')
                """.trimIndent(),
                """
                INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, body_hash,
                                               status, created_by, released_by, released_at)
                VALUES ('$TPL_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', 'tpl-275-hash',
                        'RELEASED', '$ALICE', '$ALICE', NOW())
                """.trimIndent(),
                """
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                    ('$PIPE_ID', '$PIPE_NAME', 'Scheduled surfaces', '', '$ALICE', '$WS_ACME', 1)
                """.trimIndent(),
                """
                INSERT INTO pipeline_versions
                    (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                VALUES ('$PIPE_ID', 1,
                        '{"schema_version":1,"name":"scheduled_surfaces","nodes":[{"id":"q","template":{"id":"$TPL_NAME","version":1}}]}'::jsonb,
                        'pipe-275-hash', 'RELEASED', '$ALICE', '$ALICE', NOW())
                """.trimIndent(),
                // Alice's own interactive run, and a run a schedule fired as the system identity.
                """
                INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, parameters_json,
                                                 executed_by, executed_by_key_kind, triggered_via, root_execution_id,
                                                 started_at, completed_at, duration_ms) VALUES
                    ('$RUN_ALICE', '$PIPE_ID', 1, 'SUCCESS', '{}'::jsonb, '$ALICE', NULL, 'UI', '$RUN_ALICE', NOW(), NOW(), 12),
                    ('$RUN_SCHEDULED', '$PIPE_ID', 1, 'SUCCESS', '{}'::jsonb, '$SCHEDULER', NULL, 'SCHEDULE',
                     '$RUN_SCHEDULED', NOW(), NOW(), 12)
                """.trimIndent(),
            )

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
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
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }

            // API mode: this suite only reads — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }

            // The promoter lens reads the stub higher environment.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "srs-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
        }

        private val oidc = OidcDiscoveryStub()

        /** The stub higher environment: it holds nothing, so every released entity here is newer. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"acme",""" +
                                """"pipelines":[],"templates":[],"datasources":[]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
