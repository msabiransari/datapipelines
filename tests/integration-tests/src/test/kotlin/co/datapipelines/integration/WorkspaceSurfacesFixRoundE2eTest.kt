package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers
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
 * The 022b fix round's wire proofs, split out of [WorkspaceSurfacesE2eTest] (detekt's
 * LargeClass cap) — same suite shape, same self-contained companion (the module's
 * no-shared-base convention):
 *
 * - **F4**: `open-join` end to end — a non-member joins with his own email (the suite runs
 *   with `open-join: true`).
 * - **F5**: a global datasource referenced only by ANOTHER workspace's pipeline is still
 *   `datasource.in_use` (409 naming the reference).
 * - **F6**: a read-scoped key cannot reach the mutating UI partials; an author key can.
 * - **F9**: the register modal's refusal is a 400 whose body the page injects into
 *   `#register-result`.
 * - **below-cap**: a missing member email is the generic bad-parameter 400, not the
 *   datasource domain's code.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class WorkspaceSurfacesFixRoundE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val aliceKey get() = ALICE_KEY.plaintext
    private val bobKey get() = BOB_KEY.plaintext
    private val adminKey get() = ADMIN_KEY.plaintext
    private val readonlyKey get() = READONLY_KEY.plaintext

    @Test
    fun `F4 - open-join - a non-member joins with their OWN email, then reads the workspace`() {
        ensureSeeded()
        // Bob owns globex and is NOT a member of acme. This row 403ed membership_required
        // before the fix — addMember's membership pre-check ran before the self-join branch.
        //
        // Driven by a SESSION, not a key: self-service join is a human act and the shipped
        // UI is session-gated (WorkspacesUiController.requireSessionPrincipal). Driving it
        // with a key is what forced the exemption that let a pinned key write a membership
        // row into any workspace — see the sibling test below.
        val csrf = "join-csrf"
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(SESSION_COOKIE, sessionJwt(BOB, "bob@globex.test", "globex"))
            .cookie(CSRF_COOKIE, csrf)
            .header(CSRF_HEADER, csrf)
            .body("""{"email":"bob@globex.test"}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            .body("data.role", Matchers.equalTo("member"))

        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(BOB, "bob@globex.test", "globex"))
            .`when`()
            .get("/api/v1/workspaces/acme")
            .then()
            .statusCode(200)
            .body("data.name", Matchers.equalTo("acme"))
    }

    /**
     * The other half of F4, and the reason the exemption is session-only.
     *
     * The open-join branch resolves the target by NAME with `read()`'s membership check
     * deliberately skipped. Exempting API keys from the pin therefore let a key pinned to
     * globex write a `workspace_members` row into ANY live workspace — a row that outlives
     * revocation of the key, and that alone passes the membership checks in `read` and
     * `members`, neither of which consults the pin. One leaked agent key could then walk
     * every workspace's roster (emails, display names, user ids) at scope `read`.
     */
    @Test
    fun `F4b - open-join does NOT exempt an API key from its workspace pin`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, bobKey) // pinned to globex
            .body("""{"email":"bob@globex.test"}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("workspace.membership_required"))
    }

    @Test
    fun `F5 - a global datasource referenced only by ANOTHER workspace's pipeline is still in_use - 409 naming the reference`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        // Bob (globex) authors a pipeline reading the GLOBAL datasource; the acme-pinned
        // admin's DELETE must still refuse — the guard counts every workspace now.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, bobKey)
            .body(
                """{"id": "globex_tpl", "dialect": "H2", "display_name": "Globex",
                   "description": "F5", "imports": [], "body": "SELECT 1"}""",
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)
        val pipelineId =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .header(API_KEY_HEADER, bobKey)
                .body(
                    """{"schema_version":1,"name":"globex_report","display_name":"G","description":"",""" +
                        """"nodes":[{"id":"n1","type":"DQL","source":"$DS_GLOBAL","template":{"id":"globex_tpl","version":1}}]}""",
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")

        try {
            given()
                .port(port)
                .header(API_KEY_HEADER, adminKey)
                .`when`()
                .delete("/api/v1/datasources/$DS_GLOBAL")
                .then()
                .statusCode(409)
                .body("error.code", Matchers.equalTo("datasource.in_use"))
                .body("error.details.referencing_pipelines", Matchers.hasItem("globex_report"))
        } finally {
            given()
                .port(port)
                .header(API_KEY_HEADER, bobKey)
                .`when`()
                .delete("/api/v1/pipelines/$pipelineId")
                .then()
                .statusCode(204)
            given()
                .port(port)
                .header(API_KEY_HEADER, bobKey)
                .`when`()
                .delete("/api/v1/templates/globex_tpl")
                .then()
                .statusCode(204)
        }
    }

    @Test
    fun `F6 - a read-scoped key cannot reach the mutating UI partials - the REST twin's floor applies`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .header(API_KEY_HEADER, readonlyKey)
            .contentType(ContentType.URLENC)
            .formParam("name", "readonly-smuggle")
            .formParam("dialect", "H2")
            .formParam("jdbcUrl", H2_ACME_URL)
            .formParam("username", H2_USER)
            .formParam("password", H2_PASSWORD)
            .`when`()
            .post("/partials/datasources")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.scope.insufficient"))

        given()
            .port(port)
            .header(API_KEY_HEADER, readonlyKey)
            .contentType(ContentType.URLENC)
            .`when`()
            .post("/partials/datasources/$DS_ACME/test")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.scope.insufficient"))

        // The read floor itself is untouched: the listing partial still serves a read key.
        given()
            .port(port)
            .header(API_KEY_HEADER, readonlyKey)
            .`when`()
            .get("/partials/datasources")
            .then()
            .statusCode(200)
    }

    @Test
    fun `F6 - an author key CAN register through the partial - the floor is not a wall`() {
        ensureSeeded()
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .contentType(ContentType.URLENC)
            .formParam("name", "partial-reg")
            .formParam("dialect", "H2")
            .formParam("jdbcUrl", "jdbc:h2:mem:fixround_partial;DB_CLOSE_DELAY=-1")
            .formParam("username", H2_USER)
            .formParam("password", H2_PASSWORD)
            .`when`()
            .post("/partials/datasources")
            .then()
            .statusCode(200)
            // 030: no HX-Redirect — success is the in-place Shape A response (the modal
            // success node, the OOB list refresh, and the OOB toast).
            .header("HX-Redirect", Matchers.nullValue())
            .body(Matchers.containsString("hx-swap-oob=\"beforeend:#toast\""))
            .body(Matchers.containsString("Datasource registered"))
            .body(Matchers.containsString("partial-reg"))

        // Cleanup through the REST twin.
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/datasources/partial-reg")
            .then()
            .statusCode(204)
    }

    @Test
    fun `F9 - the register modal's refusal arrives as a 400 with the markup the page injects into register-result`() {
        ensureSeeded()
        // The page handles htmx:responseError and writes THIS body into #register-result —
        // the smoke proves the partial route's side of that contract: 400, refusal text.
        val csrf = "register-csrf"
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .cookie(CSRF_COOKIE, csrf)
            .header(CSRF_HEADER, csrf)
            .contentType(ContentType.URLENC)
            .formParam("name", "bad-dialect")
            .formParam("dialect", "NOSUCH")
            .formParam("jdbcUrl", "jdbc:postgresql://db:5432/app")
            .formParam("username", "u")
            .formParam("password", "p")
            .`when`()
            .post("/partials/datasources")
            .then()
            .statusCode(400)
            .body(Matchers.containsString("Unknown dialect"))
    }

    @Test
    fun `below-cap - a missing member email is the generic bad-parameter 400 - not the datasource domain's code`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(400)
            .body("error.code", Matchers.equalTo("pipeline.execution.invalid_parameter_type"))
            .body("error.details.field", Matchers.equalTo("email"))
    }

    // ------------------------------------------------------------ helpers

    /**
     * Datasources are registered over REST (idempotent, once): the registry's save path
     * builds a REAL pool, so the H2 in-memory URLs make the test pool build succeed, and
     * the encryption key is random per boot — SQL seeding cannot produce valid ciphertext.
     */
    private fun ensureDatasourcesRegistered() {
        if (datasourcesRegistered) return
        datasourcesRegistered = true
        register(ALICE_KEY.plaintext, DS_ACME, H2_ACME_URL)
        register(BOB_KEY.plaintext, DS_GLOBEX, H2_GLOBEX_URL)
        register(ADMIN_KEY.plaintext, DS_GLOBAL, H2_GLOBAL_URL, global = true)
    }

    private fun register(
        key: String,
        name: String,
        jdbcUrl: String,
        global: Boolean = false,
    ) {
        val globalFlag = if (global) ",\"global\":true" else ""
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, key)
            .body(
                """{"name": "$name", "display_name": "Fix-round $name", "dialect": "H2",
                   "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD"$globalFlag}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    companion object {
        private var datasourcesRegistered = false

        private const val API_KEY_HEADER = "DP-API-Key"
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32

        private const val ALICE = "aaa00000-0000-0000-0000-000000000001"
        private const val BOB = "bbb00000-0000-0000-0000-000000000002"
        private const val ROOT = "ddd00000-0000-0000-0000-000000000004"
        private const val WS_ACME = "aca00000-0000-0000-0000-000000000001"
        private const val WS_GLOBEX = "b0b00000-0000-0000-0000-000000000002"

        private const val DS_ACME = "ds-acme"
        private const val DS_GLOBEX = "ds-globex"
        private const val DS_GLOBAL = "ds-global"

        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val H2_ACME_URL = "jdbc:h2:mem:fixround_acme;DB_CLOSE_DELAY=-1"
        private const val H2_GLOBEX_URL = "jdbc:h2:mem:fixround_globex;DB_CLOSE_DELAY=-1"
        private const val H2_GLOBAL_URL = "jdbc:h2:mem:fixround_global;DB_CLOSE_DELAY=-1"

        private val random = SecureRandom()

        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val ALICE_KEY = E2eAuth.generateKey("alice-key", arrayOf("read", "execute", "author"), ownerId = ALICE)
        private val BOB_KEY = E2eAuth.generateKey("bob-key", arrayOf("read", "execute", "author"), ownerId = BOB)
        private val ADMIN_KEY = E2eAuth.generateKey("admin-key", arrayOf("read", "execute", "author", "admin"), ownerId = ROOT)
        private val READONLY_KEY = E2eAuth.generateKey("readonly-key", arrayOf("read"), ownerId = ALICE)

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

        fun ensureSeeded() {
            if (seeded) return
            seeded = true

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
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
                            ('$ROOT', 'root@company.test', 'Root', 'test', 'root-sub', TRUE, TRUE)
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
                seedKeys(connection)
            }
        }

        private fun seedKeys(connection: java.sql.Connection) {
            val pins =
                mapOf(ALICE_KEY to WS_ACME, ADMIN_KEY to WS_ACME, BOB_KEY to WS_GLOBEX, READONLY_KEY to WS_ACME)
            connection
                .prepareStatement("INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)")
                .use { ps ->
                    for ((key, workspace) in pins) {
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

            // The application.yml OIDC defaults read env vars a test context does not
            // carry; the sibling suites override the provider list against the discovery
            // stub, and so does this one (the login flow itself is not under test here).
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }

            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }

            // design §7: the open-join self-service row runs against the FULL app in this
            // suite — bob (a globex owner, not an acme member) joins acme with his own email.
            registry.add("datapipelines.workspaces.open-join") { "true" }
        }

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
