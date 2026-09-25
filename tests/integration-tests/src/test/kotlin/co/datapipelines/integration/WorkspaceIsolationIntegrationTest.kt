package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Workspace isolation, proven at the row level over HTTP against the FULL application
 * (design 2026-08-16-workspaces §5): two workspaces, two users, same-named content in
 * both — and each principal sees exactly its own workspace's pipelines, templates and
 * executions.
 *
 * Resolution semantics proven on the wire:
 * - an API key operates in its **pinned** workspace; a cross-workspace pipeline UUID is a
 *   404, and `DP-Workspace` on a key request is **refused** (`400 workspace.header_forbidden`);
 * - a session principal switches with `DP-Workspace` — a member switch resolves, a
 *   non-member switch is `404 workspace.not_found` (D-R5), and an unknown name is the
 *   SAME 404, so nothing about a workspace is probeable;
 * - a zero-membership principal (`carol`) authenticates and is refused on every
 *   workspace-scoped operation, API-key issuance included. That one stays a 403
 *   `workspace.membership_required`: no workspace was ADDRESSED, so there is no name whose
 *   existence a 403 could leak.
 *
 * Rows are seeded directly via SQL (the 016 rule: isolation at the row level where
 * possible); session JWTs are minted locally over the suite's own signing secret — HS256
 * is HMAC over a shared secret, so the test signs exactly what `JwtService` would.
 * Argon2id key hashes use the same pinned library/parameters as the sibling E2E classes
 * (no literal hashes in fixtures).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class WorkspaceIsolationIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    // ---------------------------------------------------------------- pipelines & templates

    @Test
    fun `each principal sees only its own workspace's pipelines - same name, two worlds`() {
        ensureSeeded()
        pipelines(ALICE_SESSION).map { it["id"] } shouldContain PIPE_ACME
        pipelines(ALICE_SESSION).map { it["id"] } shouldNotContain PIPE_GLOBEX
        pipelines(BOB_SESSION).map { it["id"] } shouldContain PIPE_GLOBEX
        pipelines(BOB_SESSION).map { it["id"] } shouldNotContain PIPE_ACME
    }

    @Test
    fun `each principal sees only its own workspace's templates`() {
        ensureSeeded()
        templates(ALICE_SESSION).map { it["id"] } shouldContain "sales_tpl"
        templates(ALICE_SESSION).map { it["display_name"] } shouldContain "Acme Template"
        templates(ALICE_SESSION).map { it["display_name"] } shouldNotContain "Globex Template"
        templates(BOB_SESSION).map { it["display_name"] } shouldContain "Globex Template"
        templates(BOB_SESSION).map { it["display_name"] } shouldNotContain "Acme Template"
    }

    @Test
    fun `a cross-workspace pipeline UUID is a 404, not a leak`() {
        ensureSeeded()
        given()
            .port(port)
            .asSession(ALICE_SESSION)
            .`when`()
            .get("/api/v1/pipelines/$PIPE_GLOBEX")
            .then()
            .statusCode(404)

        given()
            .port(port)
            .asSession(BOB_SESSION)
            .`when`()
            .get("/api/v1/pipelines/$PIPE_GLOBEX")
            .then()
            .statusCode(200)
    }

    // ---------------------------------------------------------------- executions

    @Test
    fun `executions are visible only within their pipeline's workspace`() {
        ensureSeeded()
        executions(ALICE_SESSION).map { it["execution_id"] } shouldContain EXEC_ACME
        executions(ALICE_SESSION).map { it["execution_id"] } shouldNotContain EXEC_GLOBEX

        given()
            .port(port)
            .asSession(ALICE_SESSION)
            .`when`()
            .get("/api/v1/executions/$EXEC_GLOBEX")
            .then()
            .statusCode(404)

        given()
            .port(port)
            .asSession(BOB_SESSION)
            .`when`()
            .get("/api/v1/executions/$EXEC_GLOBEX")
            .then()
            .statusCode(200)
    }

    // ---------------------------------------------------------------- API-key pinning

    @Test
    fun `DP-Workspace on an API-key request is refused 400 header_forbidden`() {
        // A key's workspace is its pin; no header re-targets it. Since #215 B2 the MCP key
        // never reaches a REST route (it is refused by kind first), so the header rule is shown
        // with the key kind that DOES reach one: an api_caller reading its own execution.
        ensureSeeded()
        given()
            .port(port)
            .header(API_KEY_HEADER, ACME_CALLER_KEY.plaintext)
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/executions/$EXEC_ACME")
            .then()
            .statusCode(400)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.header_forbidden"))

        // …and the MCP key is refused as a kind before the header is ever read.
        given()
            .port(port)
            .header(API_KEY_HEADER, ALICE_KEY.plaintext)
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
            .body("error.details.reason", org.hamcrest.Matchers.equalTo("mcp_key_off_surface"))
    }

    // ---------------------------------------------------------------- session switching

    @Test
    fun `a session principal switches to a member workspace and sees its content`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "acme")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(200)

        // The stamped claim alone (no header) resolves the same workspace.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(200)
    }

    @Test
    fun `a session switch naming a non-membership is 404 not_found - same as an unknown name (D-R5)`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(404)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.not_found"))

        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "ghost-workspace")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(404)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.not_found"))
    }

    // ---------------------------------------------------------------- invitations (113)

    @Test
    fun `a pending invitation in one workspace is invisible from the other - in the listing and by revoke`() {
        ensureSeeded()
        // acme's own listing: members yes, and its invitations[] array is EMPTY — a ghost
        // invited into globex never appears in acme's arrays, mixed or separate.
        given()
            .port(port)
            .asSession(ALICE_SESSION)
            .`when`()
            .get("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            .body("data.invitations", org.hamcrest.Matchers.empty<Any>())

        // Revoking globex's REAL pending invitation from acme is the workspace's 404
        // (D-R5) — identical for the foreign email and for one that exists nowhere, so
        // the pair of answers cannot tell acme that dana is invited anywhere.
        val foreign =
            given()
                .port(port)
                .asSession(ALICE_SESSION)
                .`when`()
                .delete("/api/v1/workspaces/globex/invitations/$GLOBEX_INVITATION_EMAIL")
                .then()
                .extract()
        val absent =
            given()
                .port(port)
                .asSession(ALICE_SESSION)
                .`when`()
                .delete("/api/v1/workspaces/globex/invitations/nobody@nowhere.test")
                .then()
                .extract()
        foreign.statusCode() shouldBe 404
        absent.statusCode() shouldBe foreign.statusCode()
        foreign.body().asString() shouldNotContain "dana@globex.test"

        // The invitation row itself is untouched: the refusal happened before any delete.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("SELECT COUNT(*) FROM workspace_invitations WHERE email = ?")
                .use { ps ->
                    ps.setString(1, GLOBEX_INVITATION_EMAIL)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
        }
    }

    // ---------------------------------------------------------------- zero memberships

    @Test
    fun `a zero-membership principal is refused on workspace-scoped operations, issuance included`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(CAROL, "carol@nowhere.test", null))
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            // 404 workspace.not_found, from ScopeMatrix.allowed's null-context branch: for a
            // caller with nowhere to be, every workspace-scoped operation names a workspace
            // that does not exist FOR THEM (D-R5).
            .statusCode(404)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.not_found"))

        // Double-submit CSRF (auth §8.4): cookie and header must match — any value works.
        val csrf = "test-csrf-token"
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(CAROL, "carol@nowhere.test", null))
            .cookie(CSRF_COOKIE, csrf)
            .header(CSRF_HEADER, csrf)
            .contentType(ContentType.JSON)
            .body("""{"name": "carol-key", "kind": "endpoint"}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(404)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.not_found"))
    }

    // ---------------------------------------------------------------- deactivation (D-R10)

    @Test
    fun `a key pinned to a deactivated workspace is 404 auth_key_workspace_inactive on REST and MCP - and reactivation restores it`() {
        // 131 §B (T222, the owner's 2026-09-14 ruling): a deactivated workspace answers
        // not-found on EVERY surface, keys included — 112 had made keys the 403 exception
        // ("the pin proves existence"), and the ruling is that the design's 404 rule holds
        // everywhere. The CODE stays `auth.key_workspace_inactive`: the holder is a member
        // of that workspace by construction (keys are issued by members and pinned there),
        // so it reveals nothing, and it is what an operator greps the audit log for.
        //
        // A FRESH key, seeded here and never validated before, so no AuthCache key entry can
        // serve this test a pre-deactivation world — and the flip goes through the PRODUCT's
        // verb (a super admin's `POST …/deactivate`), because since 180 the workspace's
        // liveness is cached by id for the TTL and `WorkspaceService.deactivate` is what
        // evicts it. A raw SQL flip models a world the service never produces.
        ensureSeeded()
        seedKey(GLOBEX_INACTIVE_KEY, WS_GLOBEX, creator = BOB)
        setDeactivated(port, "globex", true).statusCode(200)
        try {
            // (i) REST
            given()
                .port(port)
                .header(API_KEY_HEADER, GLOBEX_INACTIVE_KEY.plaintext)
                .`when`()
                .get("/api/v1/pipelines")
                .then()
                .statusCode(404)
                .body("error.code", org.hamcrest.Matchers.equalTo("auth.key_workspace_inactive"))

            // (ii) MCP — the refusal is the HTTP status on the POST itself: the key fails
            // auth's ApiKeyFilter on the security chain, and McpAuthFilter writes the
            // stashed exception's envelope before any JSON-RPC handling happens.
            given()
                .port(port)
                .header(API_KEY_HEADER, GLOBEX_INACTIVE_KEY.plaintext)
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                .`when`()
                .post("/mcp")
                .then()
                .statusCode(404)
                .body("error.code", org.hamcrest.Matchers.equalTo("auth.key_workspace_inactive"))
        } finally {
            setDeactivated(port, "globex", false)
        }

        // (iii) D-R10's "deactivate, never delete" — the KDoc's promise is that
        // reactivation restores the very same key, on its surface (/mcp — #215 B2).
        given()
            .port(port)
            .header(API_KEY_HEADER, GLOBEX_INACTIVE_KEY.plaintext)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pipelines_list","arguments":{}}}""")
            .`when`()
            .post("/mcp")
            .then()
            .statusCode(200)
            .body(org.hamcrest.Matchers.containsString(PIPE_GLOBEX))
    }

    // ---------------------------------------------------------------- helpers

    private fun pipelines(session: String): List<Map<String, String>> =
        given()
            .port(port)
            .asSession(session)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.items")

    private fun templates(session: String): List<Map<String, String>> =
        given()
            .port(port)
            .asSession(session)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.items")

    private fun executions(session: String): List<Map<String, String>> =
        given()
            .port(port)
            .asSession(session)
            .`when`()
            .get("/api/v1/executions")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.items")

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val SECRET_BYTES = 32

        private const val ALICE = "aaa00000-0000-0000-0000-000000000001"

        /** Exposed for [WorkspaceIsolationSweepTest], which drives this suite's world. */
        const val BOB = "bbb00000-0000-0000-0000-000000000002"
        const val GLOBEX_INVITATION_EMAIL = "dana@globex.test"
        private const val CAROL = "ccc00000-0000-0000-0000-000000000003"

        // 179 (V31): one live `user` key per (user, workspace) — the deactivation test's
        // second globex key needs its OWN owner, a globex member.
        private const val DAVE = "ddd00000-0000-0000-0000-000000000004"

        /** The instance's super admin (no membership anywhere) — the one principal who may deactivate a workspace. */
        private const val EVE = "eee00000-0000-0000-0000-000000000005"
        private const val WS_ACME = "aca00000-0000-0000-0000-000000000001"
        private const val WS_GLOBEX = "b0b00000-0000-0000-0000-000000000002"
        private const val PIPE_ACME = "a1b00000-0000-0000-0000-000000000001"
        const val PIPE_GLOBEX = "b2b00000-0000-0000-0000-000000000002"

        /** 118: a WORKSPACE-scope learned fact of globex, on its own datasource — `semantics_retire`'s foreign id. */
        const val FACT_GLOBEX = "fac00000-0000-0000-0000-000000000002"
        private const val TPL_ACME_ID = "a3b00000-0000-0000-0000-000000000001"
        private const val TPL_GLOBEX_ID = "b4b00000-0000-0000-0000-000000000002"
        private const val EXEC_ACME = "a5b00000-0000-0000-0000-000000000001"
        const val EXEC_GLOBEX = "b6b00000-0000-0000-0000-000000000002"

        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"report","display_name":"Report","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"test/t","version":1}}]}"""

        private val random = SecureRandom()

        // Generated per run — no literal secret in any test fixture (HIGH-2). Kept as a
        // value: the session JWTs below are signed with the same secret the app validates.
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val ALICE_KEY = E2eAuth.generateKey("alice-key", ownerId = ALICE_KEY_IDENTITY)

        /** Alice's mcp key's own `service` identity (keys v2 A13). */
        private const val ALICE_KEY_IDENTITY = "5e000000-0000-0000-0000-00000000acbf"

        /** #215 B2: REST is a session's surface — the two members' sessions, pinned by their ACTIVE workspace. */
        private val ALICE_SESSION get() = sessionJwt(ALICE, "alice@acme.test", "acme")
        private val BOB_SESSION get() = sessionJwt(BOB, "bob@globex.test", "globex")

        /** acme's API key: an `api_caller` acting as its own identity (record §3.3), created by Alice. */
        private const val ACME_CALLER_IDENTITY = "5e000000-0000-0000-0000-00000000acbe"
        private val ACME_CALLER_KEY = E2eAuth.generateKey("acme-caller", ownerId = ACME_CALLER_IDENTITY)

        /**
         * Pinned to globex but seeded only by the deactivation test — a key no other test
         * validates. Its own identity carries it (keys v2 A13); DAVE creates it.
         */
        private const val GLOBEX_INACTIVE_IDENTITY = "5e000000-0000-0000-0000-00000000acc0"
        private val GLOBEX_INACTIVE_KEY = E2eAuth.generateKey("globex-inactive-key", ownerId = GLOBEX_INACTIVE_IDENTITY)

        /**
         * Mints the session JWT exactly as `JwtService.issue` does (HS256, `iss`, iat/exp,
         * `active_workspace` when given) — signing with the suite's own configured secret.
         */
        private fun sessionJwt(
            userId: String,
            email: String,
            activeWorkspace: String?,
        ): String {
            val now = Instant.now()
            val header = b64("""{"alg":"HS256","typ":"JWT"}""")
            val workspaceClaim = activeWorkspace?.let { ""","active_workspace":"$it"""" } ?: ""
            val payload =
                b64(
                    """{"sub":"$userId","email":"$email","name":"Test User",""" +
                        """"iss":"datapipelines","iat":${now.epochSecond},"exp":${now.plusSeconds(3600).epochSecond}$workspaceClaim}""",
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

        /**
         * An `acme` AUTHOR's session and key, for [WorkspaceIsolationSweepTest] — which walks
         * every route with them rather than re-seeding a second world. One fixture, one set of
         * identifiers: a sweep against a DIFFERENT seed would prove isolation between two
         * things this suite never showed were isolated.
         */
        fun acmeSession(): String = sessionJwt(ALICE, "alice@acme.test", "acme")

        fun acmeKey(): String = ALICE_KEY.plaintext

        private var seeded = false

        /**
         * Seeds once, on first use — inside a test method, i.e. AFTER the application's
         * Flyway migrations have run (a `@BeforeAll` would execute against the bare
         * container, before the context and its `workspaces` table exist).
         */
        fun ensureSeeded() {
            if (seeded) return
            seeded = true

            // This suite's world (acme/globex, alice/bob/carol) is the SAME fixture
            // vocabulary two other suites seed — plain INSERTs that must find an empty
            // database, exactly as their former per-suite container guaranteed.
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                seedRows(connection)
                seedContent(connection)
                seedGlobexOnlyDatasource(connection)
                seedKeys(connection)
            }
        }

        private fun seedRows(connection: java.sql.Connection) {
            connection.createStatement().use { statement ->
                // Two workspaces beside the V4-seeded `default`.
                statement.execute(
                    """
                    INSERT INTO workspaces (id, name, display_name) VALUES
                        ('$WS_ACME', 'acme', 'Acme'),
                        ('$WS_GLOBEX', 'globex', 'Globex')
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                        ('$ALICE', 'alice@acme.test', 'Alice', 'test', 'alice-sub', TRUE, FALSE),
                        ('$BOB', 'bob@globex.test', 'Bob', 'test', 'bob-sub', TRUE, FALSE),
                        ('$CAROL', 'carol@nowhere.test', 'Carol', 'test', 'carol-sub', TRUE, FALSE),
                        ('$DAVE', 'dave@globex.test', 'Dave', 'test', 'dave-sub', TRUE, FALSE),
                        ('$EVE', 'eve@instance.test', 'Eve', 'test', 'eve-sub', TRUE, TRUE)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                        ('$WS_ACME', '$ALICE', 'workspace_admin'),
                        ('$WS_GLOBEX', '$BOB', 'workspace_admin'),
                        ('$WS_GLOBEX', '$DAVE', 'viewer')
                    """.trimIndent(),
                )
                // 113: one PENDING invitation in globex — the row that must be invisible
                // from acme, in listings and by revoke (the sweep drives the verb).
                statement.execute(
                    """
                    INSERT INTO workspace_invitations (workspace_id, email, role, invited_by) VALUES
                        ('$WS_GLOBEX', '$GLOBEX_INVITATION_EMAIL', 'author', '$BOB')
                    """.trimIndent(),
                )
            }
        }

        private fun seedContent(connection: java.sql.Connection) {
            connection.createStatement().use { statement ->
                // Same pipeline name in BOTH workspaces — legal per-workspace (D2), and the
                // reason name-keyed caches had to be re-keyed.
                statement.execute(
                    """
                    INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                        ('$PIPE_ACME', 'report', 'Acme Report', '', '$ALICE', '$WS_ACME', 1),
                        ('$PIPE_GLOBEX', 'report', 'Globex Report', '', '$BOB', '$WS_GLOBEX', 1)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                        ('$PIPE_ACME', 1, '$PIPELINE_BODY'::jsonb, 'seed-hash', 'RELEASED', '$ALICE', '$ALICE', NOW()),
                        ('$PIPE_GLOBEX', 1, '$PIPELINE_BODY'::jsonb, 'seed-hash', 'RELEASED', '$BOB', '$BOB', NOW())
                    """.trimIndent(),
                )
                // Same template name in both workspaces.
                statement.execute(
                    """
                    INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by) VALUES
                        ('$TPL_ACME_ID', 'sales_tpl', 'Acme Template', '', 1, '$WS_ACME', '$ALICE'),
                        ('$TPL_GLOBEX_ID', 'sales_tpl', 'Globex Template', '', 1, '$WS_GLOBEX', '$BOB')
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, body_hash, status, created_by, released_by, released_at) VALUES
                        ('$TPL_ACME_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', 'seed-hash', 'RELEASED', '$ALICE', '$ALICE', NOW()),
                        ('$TPL_GLOBEX_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 2', 'seed-hash', 'RELEASED', '$BOB', '$BOB', NOW())
                    """.trimIndent(),
                )
                // One execution per pipeline — visibility scopes via the pipeline's workspace (§5.3).
                statement.execute(
                    """
                    INSERT INTO pipeline_executions
                        (execution_id, pipeline_id, pipeline_version, status, parameters_json, executed_by, triggered_via, root_execution_id)
                    VALUES
                        ('$EXEC_ACME', '$PIPE_ACME', 1, 'SUCCESS', '{}'::jsonb, '$ALICE', 'REST', '$EXEC_ACME'),
                        ('$EXEC_GLOBEX', '$PIPE_GLOBEX', 1, 'SUCCESS', '{}'::jsonb, '$BOB', 'REST', '$EXEC_GLOBEX')
                    """.trimIndent(),
                )
            }
        }

        /**
         * A datasource granted to `globex` and to NOBODY else (D-R7) — the fixture the sweep
         * points every `datasources_*` tool at. Under the grant model an ungranted datasource
         * is invisible, so this is the row that makes "a foreign datasource is not-found" a
         * claim about isolation rather than about a name nobody registered.
         */
        private fun seedGlobexOnlyDatasource(connection: java.sql.Connection) {
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO datasources (name, display_name, dialect, jdbc_url, username,
                                             credential_encrypted, created_by, owner_workspace_id)
                    VALUES ('globex-only-db', 'Globex only', 'H2', 'jdbc:h2:mem:globex_only', 'sa',
                            'x'::bytea, '$BOB', '$WS_GLOBEX')
                    ON CONFLICT (name) DO NOTHING
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)
                    VALUES ('globex-only-db', '$WS_GLOBEX', '$BOB')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
                // 118 — one WORKSPACE-scope learned fact that belongs to globex (metadata-db §4.18),
                // so `semantics_retire` can be swept with a REAL foreign id: an acme key must get
                // the same not-found for it as for an id that exists nowhere.
                statement.execute(
                    """
                    INSERT INTO learned_facts (id, scope, workspace_id, datasource_name, kind, fact, refs_json, trust,
                                               schema_fingerprint, recorded_by, recorded_via, recorded_in)
                    VALUES ('$FACT_GLOBEX', 'WORKSPACE', '$WS_GLOBEX', 'globex-only-db', 'definition',
                            'revenue = SUM(amount); tips excluded', '[{"schema": null, "table": "orders", "column": null}]',
                            'asserted', 'orders=seed', '$BOB', 'session', '$WS_GLOBEX')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
        }

        private fun seedKeys(connection: java.sql.Connection) {
            // Both identities FIRST (the api_keys rows FK to them): acme's api_caller key acts as
            // its own `service` identity (record §3.3), created by Alice; keys v2 A13 gives
            // Alice's mcp key one too.
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) " +
                        "VALUES ('$ACME_CALLER_IDENTITY', '${ACME_CALLER_KEY.id.lowercase()}@keys.invalid', " +
                        "'${ACME_CALLER_KEY.name}', 'key', '${ACME_CALLER_KEY.id}', TRUE, FALSE, 'service')",
                )
                statement.execute(
                    "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) " +
                        "VALUES ('$ALICE_KEY_IDENTITY', '${ALICE_KEY.id.lowercase()}@keys.invalid', '${ALICE_KEY.name}', 'key', " +
                        "'${ALICE_KEY.id}', TRUE, FALSE, 'service')",
                )
            }
            // Alice's MCP key (the sweep's `/mcp` principal) acts as its own identity and holds
            // the workspace_admin role its creator holds (keys v2 A13/A14).
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'workspace_admin')",
                ).use { ps ->
                    ps.setString(1, ALICE_KEY.id)
                    ps.setObject(2, UUID.fromString(ALICE_KEY_IDENTITY))
                    ps.setObject(3, UUID.fromString(ALICE))
                    ps.setString(4, ALICE_KEY.name)
                    ps.setString(5, ALICE_KEY.hash)
                    ps.setObject(6, UUID.fromString(WS_ACME))
                    ps.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                        " VALUES (?, ?, ?, ?, ?, ?, 'endpoint', 'api_caller')",
                ).use { ps ->
                    ps.setString(1, ACME_CALLER_KEY.id)
                    ps.setObject(2, UUID.fromString(ACME_CALLER_IDENTITY))
                    ps.setObject(3, UUID.fromString(ALICE))
                    ps.setString(4, ACME_CALLER_KEY.name)
                    ps.setString(5, ACME_CALLER_KEY.hash)
                    ps.setObject(6, UUID.fromString(WS_ACME))
                    ps.executeUpdate()
                }
        }

        /** One key, seeded by the test that needs it (a fresh key carries no AuthCache entry). */
        private fun seedKey(
            key: E2eAuth.SeededKey,
            workspaceId: String,
            creator: String,
        ) {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    // Keys v2 (A13): the key acts as its own identity (the key's ownerId), which
                    // the suite defines per key; the role only has to satisfy the CHECK — the key
                    // never gets past its workspace's liveness.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) " +
                            "VALUES ('${key.ownerId}', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', " +
                            "'${key.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'author')",
                    ).use { ps ->
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(key.ownerId))
                        ps.setObject(3, UUID.fromString(creator))
                        ps.setString(4, key.name)
                        ps.setString(5, key.hash)
                        ps.setObject(6, UUID.fromString(workspaceId))
                        ps.executeUpdate()
                    }
            }
        }

        /**
         * D-R10's reversible switch, flipped the way an operator flips it — the super admin's
         * REST verb. Not a direct SQL flip: since 180 the workspace's liveness is cached by id
         * for the TTL, and `WorkspaceService.deactivate`/`reactivate` are what evict it.
         */
        private fun setDeactivated(
            port: Int,
            name: String,
            deactivated: Boolean,
        ): io.restassured.response.ValidatableResponse {
            val csrf = "test-csrf-token"
            return given()
                .port(port)
                .cookie(SESSION_COOKIE, sessionJwt(EVE, "eve@instance.test", null))
                .cookie(CSRF_COOKIE, csrf)
                .header(CSRF_HEADER, csrf)
                .contentType(ContentType.JSON)
                .body("{}")
                .`when`()
                .post("/api/v1/workspaces/$name/${if (deactivated) "deactivate" else "reactivate"}")
                .then()
        }

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

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
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
