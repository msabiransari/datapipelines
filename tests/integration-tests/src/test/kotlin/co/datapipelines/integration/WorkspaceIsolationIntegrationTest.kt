package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import de.mkammerer.argon2.Argon2Factory
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
 *   non-member switch is `403 workspace.membership_required`, and an unknown name is the
 *   same 403 (no existence probe);
 * - a zero-membership principal (`carol`) authenticates and 403s on every workspace-scoped
 *   operation, API-key issuance included.
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
@Testcontainers
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
    fun `a session switch naming a non-membership is 403 membership_required - same as an unknown name`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.membership_required"))

        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "ghost-workspace")
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.membership_required"))
    }

    // ---------------------------------------------------------------- zero memberships

    @Test
    fun `a zero-membership principal 403s on workspace-scoped operations, issuance included`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(CAROL, "carol@nowhere.test", null))
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(403)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.membership_required"))

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
            .statusCode(403)
            .body("error.code", org.hamcrest.Matchers.equalTo("workspace.membership_required"))
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

    private data class SeededKey(
        val name: String,
        val ownerId: String,
        val scopes: Array<String>,
        val id: String,
        val plaintext: String,
        val hash: String,
    )

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private const val ALICE = "aaa00000-0000-0000-0000-000000000001"
        private const val BOB = "bbb00000-0000-0000-0000-000000000002"
        private const val CAROL = "ccc00000-0000-0000-0000-000000000003"
        private const val WS_ACME = "aca00000-0000-0000-0000-000000000001"
        private const val WS_GLOBEX = "b0b00000-0000-0000-0000-000000000002"
        private const val PIPE_ACME = "a1b00000-0000-0000-0000-000000000001"
        private const val PIPE_GLOBEX = "b2b00000-0000-0000-0000-000000000002"
        private const val TPL_ACME_ID = "a3b00000-0000-0000-0000-000000000001"
        private const val TPL_GLOBEX_ID = "b4b00000-0000-0000-0000-000000000002"
        private const val EXEC_ACME = "a5b00000-0000-0000-0000-000000000001"
        private const val EXEC_GLOBEX = "b6b00000-0000-0000-0000-000000000002"

        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"report","display_name":"Report","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"t","version":1}}]}"""

        private val random = SecureRandom()

        // Generated per run — no literal secret in any test fixture (HIGH-2). Kept as a
        // value: the session JWTs below are signed with the same secret the app validates.
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val ALICE_KEY = seededKey("alice-key", ALICE, arrayOf("read", "execute", "author"))
        private val BOB_KEY = seededKey("bob-key", BOB, arrayOf("read", "execute", "author"))

        private fun seededKey(
            name: String,
            ownerId: String,
            scopes: Array<String>,
        ): SeededKey {
            val id = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
            val plaintext = id + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
            return SeededKey(name, ownerId, scopes, id, plaintext, argon2Hash(plaintext))
        }

        /** Same library and parameters (2 / 19456 / 1) as auth's Argon2SecretHasher — see TracerBulletE2eTest. */
        private fun argon2Hash(plaintext: String): String =
            Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id, 32, 16).hash(2, 19456, 1, plaintext.toCharArray())

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

        private var seeded = false

        /**
         * Seeds once, on first use — inside a test method, i.e. AFTER the application's
         * Flyway migrations have run (a `@BeforeAll` would execute against the bare
         * container, before the context and its `workspaces` table exist).
         */
        fun ensureSeeded() {
            if (seeded) return
            seeded = true

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                seedRows(connection)
                seedContent(connection)
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
                    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                        ('$WS_ACME', '$ALICE', 'owner'),
                        ('$WS_GLOBEX', '$BOB', 'owner')
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
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by) VALUES
                        ('$PIPE_ACME', 1, '$PIPELINE_BODY'::jsonb, '$ALICE'),
                        ('$PIPE_GLOBEX', 1, '$PIPELINE_BODY'::jsonb, '$BOB')
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
                    INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, created_by) VALUES
                        ('$TPL_ACME_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', '$ALICE'),
                        ('$TPL_GLOBEX_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 2', '$BOB')
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

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        @Container
        @JvmStatic
        private val redis =
            GenericContainer("redis:7-alpine")
                .withCommand("redis-server", "--maxmemory-policy", "noeviction")
                .withExposedPorts(REDIS_PORT)

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
            registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { redis.getMappedPort(REDIS_PORT) }

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
