package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
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
        pipelines(ALICE_KEY.plaintext).map { it["id"] } shouldContain PIPE_ACME
        pipelines(ALICE_KEY.plaintext).map { it["id"] } shouldNotContain PIPE_GLOBEX
        pipelines(BOB_KEY.plaintext).map { it["id"] } shouldContain PIPE_GLOBEX
        pipelines(BOB_KEY.plaintext).map { it["id"] } shouldNotContain PIPE_ACME
    }

    @Test
    fun `each principal sees only its own workspace's templates`() {
        ensureSeeded()
        templates(ALICE_KEY.plaintext).map { it["id"] } shouldContain "sales_tpl"
        templates(ALICE_KEY.plaintext).map { it["display_name"] } shouldContain "Acme Template"
        templates(ALICE_KEY.plaintext).map { it["display_name"] } shouldNotContain "Globex Template"
        templates(BOB_KEY.plaintext).map { it["display_name"] } shouldContain "Globex Template"
        templates(BOB_KEY.plaintext).map { it["display_name"] } shouldNotContain "Acme Template"
    }

    @Test
    fun `a cross-workspace pipeline UUID is a 404, not a leak`() {
        ensureSeeded()
        given()
            .port(port)
            .header(API_KEY_HEADER, ALICE_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/$PIPE_GLOBEX")
            .then()
            .statusCode(404)

        given()
            .port(port)
            .header(API_KEY_HEADER, BOB_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines/$PIPE_GLOBEX")
            .then()
            .statusCode(200)
    }

    // ---------------------------------------------------------------- executions

    @Test
    fun `executions are visible only within their pipeline's workspace`() {
        ensureSeeded()
        executions(ALICE_KEY.plaintext).map { it["execution_id"] } shouldContain EXEC_ACME
        executions(ALICE_KEY.plaintext).map { it["execution_id"] } shouldNotContain EXEC_GLOBEX

        given()
            .port(port)
            .header(API_KEY_HEADER, ALICE_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$EXEC_GLOBEX")
            .then()
            .statusCode(404)

        given()
            .port(port)
            .header(API_KEY_HEADER, BOB_KEY.plaintext)
            .`when`()
            .get("/api/v1/executions/$EXEC_GLOBEX")
            .then()
            .statusCode(200)
    }

    // ---------------------------------------------------------------- API-key pinning

    @Test
    fun `DP-Workspace on an API-key request is refused 400 header_forbidden`() {
        ensureSeeded()
        given()
            .port(port)
            .header(API_KEY_HEADER, ALICE_KEY.plaintext)
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(400)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.header_forbidden"))
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
            .header(API_KEY_HEADER, ALICE_KEY.plaintext)
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
                .header(API_KEY_HEADER, ALICE_KEY.plaintext)
                .`when`()
                .delete("/api/v1/workspaces/globex/invitations/$GLOBEX_INVITATION_EMAIL")
                .then()
                .extract()
        val absent =
            given()
                .port(port)
                .header(API_KEY_HEADER, ALICE_KEY.plaintext)
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
            .body("""{"name": "carol-key", "scopes": ["read"]}""")
            .`when`()
            .post("/api/v1/auth/api-keys")
            .then()
            .statusCode(404)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.not_found"))
    }

    // ---------------------------------------------------------------- helpers

    private fun pipelines(key: String): List<Map<String, String>> =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.items")

    private fun templates(key: String): List<Map<String, String>> =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.items")

    private fun executions(key: String): List<Map<String, String>> =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
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

        private val ALICE_KEY = E2eAuth.generateKey("alice-key", arrayOf("read", "execute", "author"), ownerId = ALICE)
        private val BOB_KEY = E2eAuth.generateKey("bob-key", arrayOf("read", "execute", "author"), ownerId = BOB)

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
                    """{"sub":"$userId","email":"$email","name":"Test User","scopes":["read","execute","author"],""" +
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
                        ('$CAROL', 'carol@nowhere.test', 'Carol', 'test', 'carol-sub', TRUE, FALSE)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin) VALUES
                        ('$WS_ACME', '$ALICE', TRUE, FALSE, TRUE),
                        ('$WS_GLOBEX', '$BOB', TRUE, FALSE, TRUE)
                    """.trimIndent(),
                )
                // 113: one PENDING invitation in globex — the row that must be invisible
                // from acme, in listings and by revoke (the sweep drives the verb).
                statement.execute(
                    """
                    INSERT INTO workspace_invitations (workspace_id, email, author, invited_by) VALUES
                        ('$WS_GLOBEX', '$GLOBEX_INVITATION_EMAIL', TRUE, '$BOB')
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
                        (execution_id, pipeline_id, pipeline_version, status, parameters_json, triggered_by, triggered_via, root_execution_id)
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
            connection
                .prepareStatement("INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)")
                .use { ps ->
                    for ((key, workspace) in listOf(ALICE_KEY to WS_ACME, BOB_KEY to WS_GLOBEX)) {
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(key.ownerId))
                        ps.setString(3, key.name)
                        ps.setString(4, key.hash)
                        ps.setArray(5, connection.createArrayOf("text", key.scopes))
                        ps.setObject(6, UUID.fromString(workspace))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
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
