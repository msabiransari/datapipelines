package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import de.mkammerer.argon2.Argon2Factory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
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
 * The workspaces SURFACES slice's wire proofs (design §5.3/§8/§9), against the FULL
 * application:
 *
 * - **Datasource isolation extended to the row-level rule's little sibling**: listings and
 *   by-name GETs assert BOTH what is seen and what is NOT, with exact paging totals — the
 *   totals prove the visibility predicate ran in SQL (a controller-side post-filter leaks
 *   through them).
 * - **The workspace CRUD §8 codes, red over HTTP** — name_invalid, duplicate_name, the
 *   no-oracle 403 vs the admin's 404, in_use with each content kind, owner-member gates.
 * - **T23**: a duplicate template name is the catalogued 409 (falsified against the old 500
 *   in the slice's verification run — see the handback).
 * - **T31**: a browser-shaped unauthenticated request 302s to the relative `/login`; the API
 *   401 JSON envelope is byte-identical under a fixed correlation id.
 * - **T33 on the wire**: this suite runs with an `http://` base-url, so the re-stamped
 *   `dp_session` cookie of a workspace switch is NOT Secure — the http half of the rule;
 *   the https half and the fail-secure default are pinned by `SecureCookiesT33Test`.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Testcontainers
class WorkspaceSurfacesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val aliceKey get() = ALICE_KEY.plaintext
    private val bobKey get() = BOB_KEY.plaintext
    private val carolKey get() = CAROL_KEY.plaintext
    private val adminKey get() = ADMIN_KEY.plaintext

    // ------------------------------------------------------------ datasource isolation (§5.3)

    @Test
    fun `a listing sees the active workspace's bound datasources plus global - and NOTHING else, with an exact total`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        val alice = datasources(aliceKey)
        alice.map { it["name"] } shouldContainExactlyInAnyOrder listOf(DS_ACME, DS_GLOBAL)
        val bob = datasources(bobKey)
        bob.map { it["name"] } shouldContainExactlyInAnyOrder listOf(DS_GLOBEX, DS_GLOBAL)
    }

    @Test
    fun `the paging total counts exactly the visible set - repository-level filtering, not a post-filter`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/datasources?offset=0&limit=1")
            .then()
            .statusCode(200)
            .body("data.pagination.total", Matchers.equalTo(2))
            .body("data.items.size()", Matchers.equalTo(1))
            .body("data.pagination.has_more", Matchers.equalTo(true))
    }

    @Test
    fun `a by-name GET of another workspace's bound datasource is not-found`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/datasources/$DS_GLOBEX")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("datasource.not_found"))

        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/datasources/$DS_ACME")
            .then()
            .statusCode(200)
            .body("data.workspace", Matchers.equalTo("acme"))
            .body("data.readonly", Matchers.equalTo(false))
    }

    @Test
    fun `the session switcher path scopes datasource visibility exactly like the pinned key`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "acme")
            .`when`()
            .get("/api/v1/datasources")
            .then()
            .statusCode(200)
            .body("data.items.name", Matchers.hasItem(DS_ACME))
            .body("data.items.name", Matchers.not(Matchers.hasItem(DS_GLOBEX)))

        // A switch to a non-membership stays the 019 403 — including on this surface.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/datasources")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("workspace.membership_required"))
    }

    @Test
    fun `datasource names are a global namespace - a cross-workspace collision is duplicate_name`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, bobKey)
            .body("""{"name": "$DS_ACME", "dialect": "H2", "jdbc_url": "$H2_GLOBEX_URL", "username": "sa", "password": "sa"}""")
            .`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("datasource.validation.duplicate_name"))
    }

    // ------------------------------------------------------------ workspace CRUD §8 codes

    @Test
    fun `create returns 201 with the creator as owner`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"name":"$FRESH_WS","display_name":"Fresh"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(201)
            .body("data.name", Matchers.equalTo(FRESH_WS))

        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/workspaces")
            .then()
            .statusCode(200)
            .body("data.find { it.name == '$FRESH_WS' }.role", Matchers.equalTo("owner"))
    }

    @Test
    fun `a malformed name is 400 name_invalid`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"name":"Bad Name!"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(400)
            .body("error.code", Matchers.equalTo("workspace.validation.name_invalid"))
    }

    @Test
    fun `a taken name is 409 duplicate_name`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"name":"globex"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("workspace.validation.duplicate_name"))
    }

    @Test
    fun `unknown and non-member are the same 403 for a member - the admin gets the 404`() {
        ensureSeeded()
        for (name in listOf("ghost", "globex")) {
            given()
                .port(port)
                .header(API_KEY_HEADER, aliceKey)
                .`when`()
                .get("/api/v1/workspaces/$name")
                .then()
                .statusCode(403)
                .body("error.code", Matchers.equalTo("workspace.membership_required"))
        }

        given()
            .port(port)
            .header(API_KEY_HEADER, adminKey)
            .`when`()
            .get("/api/v1/workspaces/ghost")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("workspace.not_found"))
    }

    @Test
    fun `delete is 409 in_use naming each blocking content kind`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("workspace.in_use"))
            .body("error.details.counts.pipelines", Matchers.equalTo(1))
            .body("error.details.counts.templates", Matchers.equalTo(1))
            .body("error.details.counts.datasources", Matchers.equalTo(1))

        // globex owns ONLY a bound datasource — the count names exactly that kind.
        given()
            .port(port)
            .header(API_KEY_HEADER, bobKey)
            .`when`()
            .delete("/api/v1/workspaces/globex")
            .then()
            .statusCode(409)
            .body("error.details.counts.datasources", Matchers.equalTo(1))
            .body("error.details.counts.pipelines", Matchers.nullValue())
    }

    @Test
    fun `update is owner-or-admin - a plain member gets the same 403, the owner renames`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, carolKey)
            .body("""{"display_name":"Hijacked"}""")
            .`when`()
            .put("/api/v1/workspaces/acme")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("workspace.membership_required"))

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"display_name":"Acme Renamed"}""")
            .`when`()
            .put("/api/v1/workspaces/acme")
            .then()
            .statusCode(200)
            .body("data.display_name", Matchers.equalTo("Acme Renamed"))
    }

    // ------------------------------------------------------------ members §9

    @Test
    fun `an owner lists, adds and removes members - removing an OWNER is in_use blocked_by owner_membership`() {
        ensureSeeded()
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            .body("data.email", Matchers.hasItems("alice@acme.test", "carol@acme.test"))

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"email":"bob@globex.test"}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            .body("data.role", Matchers.equalTo("member"))

        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme/members/$BOB")
            .then()
            .statusCode(204)

        // An owner target is the in_use refusal — ownership transfer is not a v1 operation.
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme/members/$ALICE")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("workspace.in_use"))
            .body("error.details.blocked_by", Matchers.equalTo("owner_membership"))
    }

    @Test
    fun `an unknown member email is the §16-3 unknown-user stand-in`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"email":"ghost@nowhere.test"}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("pipeline.execution.not_found"))
            .body("error.details.reason", Matchers.equalTo("user_not_found"))
    }

    // ------------------------------------------------------------ UI screens smoke

    @Test
    fun `the workspaces screen renders for an authenticated session`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header("Accept", "text/html")
            .`when`()
            .get("/workspaces")
            .then()
            .statusCode(200)
    }

    @Test
    fun `the datasources screen renders for an authenticated session`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header("Accept", "text/html")
            .`when`()
            .get("/datasources")
            .then()
            .statusCode(200)
    }

    // ------------------------------------------------------------ T23

    @Test
    fun `a duplicate template name is the catalogued 409, never a 500`() {
        ensureSeeded()
        val body =
            """
            {"id": "dup_tpl", "dialect": "POSTGRES", "display_name": "Dup",
             "description": "T23", "imports": [], "body": "SELECT 1"}
            """.trimIndent()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body(body)
            .`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(201)

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body(body)
            .`when`()
            .post("/api/v1/templates")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("template.validation.duplicate_name"))
    }

    // ------------------------------------------------------------ T31

    @Test
    fun `an unauthenticated browser request 302s to the RELATIVE login path`() {
        given()
            .port(port)
            .redirects()
            .follow(false)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .`when`()
            .get("/pipelines")
            .then()
            .statusCode(302)
            .header("Location", Matchers.equalTo("/login"))
    }

    @Test
    fun `an unauthenticated API call keeps the byte-identical 401 JSON envelope`() {
        ensureSeeded()
        // Fixed correlation id echoed by §3.4's shape-conditional rule — with it, the body
        // is byte-deterministic and comparable across the T31 change.
        val body =
            given()
                .port(port)
                .header("Accept", "application/json")
                .header("DP-Correlation-Id", FIXED_CORRELATION)
                .`when`()
                .get("/api/v1/pipelines")
                .then()
                .statusCode(401)
                .extract()
                .body()
                .asString()

        val expected =
            """{"schema_version":1,"correlation_id":"$FIXED_CORRELATION","error":{"code":"auth.api_key.missing",""" +
                """"message":"No credentials provided","user_message":"You are not signed in. Sign in and try again.",""" +
                """"details":{},"doc_url":"https://docs.datapipelines.co/errors/auth-api-key-missing"}}"""
        body shouldBe expected
    }

    // ------------------------------------------------------------ T33 + the switcher action

    @Test
    fun `the switcher action re-stamps the session - and with this suite's http base-url the cookie is NOT Secure`() {
        ensureSeeded()
        val csrf = "switch-csrf"
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .cookie(CSRF_COOKIE, csrf)
            .header(CSRF_HEADER, csrf)
            .`when`()
            .post("/workspace/switch?name=acme")
            .then()
            .statusCode(302)
            .header("Location", Matchers.endsWith("/"))

        val cookie =
            given()
                .port(port)
                .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
                .cookie(CSRF_COOKIE, csrf)
                .header(CSRF_HEADER, csrf)
                .`when`()
                .post("/workspace/switch?name=acme")
                .then()
                .statusCode(302)
                .extract()
                .detailedCookie(SESSION_COOKIE)
        // T33's http half, on the wire: base-url http://localhost:8080 → no Secure flag.
        // (The https half and the fail-secure default: SecureCookiesT33Test.)
        cookie.isSecured shouldBe false
        cookie.value.shouldNotBeBlank()
    }

    @Test
    fun `a refused switch redirects back with the error state`() {
        ensureSeeded()
        val csrf = "switch-csrf-2"
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .cookie(CSRF_COOKIE, csrf)
            .header(CSRF_HEADER, csrf)
            .`when`()
            .post("/workspace/switch?name=globex")
            .then()
            .statusCode(302)
            .header("Location", Matchers.endsWith("/workspaces?error=switch_refused"))
    }

    // ------------------------------------------------------------ helpers

    private fun datasources(key: String): List<Map<String, String>> =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .get("/api/v1/datasources?limit=200")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("data.items")

    /**
     * Datasources are registered over REST (idempotent, once): the registry's save path
     * builds a REAL pool, so the H2 in-memory URLs make the test pool build succeed, and
     * the encryption key is random per boot — SQL seeding cannot produce valid ciphertext.
     * Alice binds to her ACTIVE workspace (the default binding), bob names his explicitly
     * (the member gate is ON by default), and the admin creates the global one.
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
                """{"name": "$name", "display_name": "Surfaces $name", "dialect": "H2",
                   "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD"$globalFlag}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private data class SeededKey(
        val name: String,
        val ownerId: String,
        val scopes: Array<String>,
        val id: String,
        val plaintext: String,
        val hash: String,
    )

    companion object {
        private var datasourcesRegistered = false

        private const val API_KEY_HEADER = "DP-API-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val REDIS_PORT = 6379
        private const val SECRET_BYTES = 32
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val FIXED_CORRELATION = "02202202-0222-4222-8222-022022020222"

        private const val ALICE = "aaa00000-0000-0000-0000-000000000001"
        private const val BOB = "bbb00000-0000-0000-0000-000000000002"
        private const val CAROL = "ccc00000-0000-0000-0000-000000000003"
        private const val ROOT = "ddd00000-0000-0000-0000-000000000004"
        private const val WS_ACME = "aca00000-0000-0000-0000-000000000001"
        private const val WS_GLOBEX = "b0b00000-0000-0000-0000-000000000002"

        private const val PIPE_ACME = "a1b00000-0000-0000-0000-000000000001"
        private const val TPL_ACME_ID = "a3b00000-0000-0000-0000-000000000001"

        private const val DS_ACME = "ds-acme"
        private const val DS_GLOBEX = "ds-globex"
        private const val DS_GLOBAL = "ds-global"
        private const val FRESH_WS = "fresh-team"

        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val H2_ACME_URL = "jdbc:h2:mem:surfaces_acme;DB_CLOSE_DELAY=-1"
        private const val H2_GLOBEX_URL = "jdbc:h2:mem:surfaces_globex;DB_CLOSE_DELAY=-1"
        private const val H2_GLOBAL_URL = "jdbc:h2:mem:surfaces_global;DB_CLOSE_DELAY=-1"

        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"report","display_name":"Report","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"t","version":1}}]}"""

        private val random = SecureRandom()

        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val ALICE_KEY = seededKey("alice-key", ALICE, arrayOf("read", "execute", "author"))
        private val BOB_KEY = seededKey("bob-key", BOB, arrayOf("read", "execute", "author"))
        private val CAROL_KEY = seededKey("carol-key", CAROL, arrayOf("read", "execute", "author"))
        private val ADMIN_KEY = seededKey("admin-key", ROOT, arrayOf("read", "execute", "author", "admin"))

        private fun seededKey(
            name: String,
            ownerId: String,
            scopes: Array<String>,
        ): SeededKey {
            val id = "dpk_" + (1..12).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
            val plaintext = id + "." + (1..48).map { BASE32[random.nextInt(BASE32.length)] }.joinToString("")
            return SeededKey(name, ownerId, scopes, id, plaintext, argon2Hash(plaintext))
        }

        private fun argon2Hash(plaintext: String): String =
            Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id, 32, 16).hash(2, 19456, 1, plaintext.toCharArray())

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
                            ('$CAROL', 'carol@acme.test', 'Carol', 'test', 'carol-sub', TRUE, FALSE),
                            ('$ROOT', 'root@company.test', 'Root', 'test', 'root-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                            ('$WS_ACME', '$ALICE', 'owner'),
                            ('$WS_ACME', '$CAROL', 'member'),
                            ('$WS_GLOBEX', '$BOB', 'owner')
                        """.trimIndent(),
                    )
                    seedInUseContent(statement)
                }
                seedKeys(connection)
            }
        }

        /** Content for the in_use proofs: one pipeline + one template in acme. */
        private fun seedInUseContent(statement: java.sql.Statement) {
            statement.execute(
                """
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                    ('$PIPE_ACME', 'report', 'Acme Report', '', '$ALICE', '$WS_ACME', 1)
                """.trimIndent(),
            )
            statement.execute(
                """
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by) VALUES
                    ('$PIPE_ACME', 1, '$PIPELINE_BODY'::jsonb, '$ALICE')
                """.trimIndent(),
            )
            statement.execute(
                """
                INSERT INTO templates (id, name, display_name, description, current_version, workspace_id, created_by) VALUES
                    ('$TPL_ACME_ID', 'sales_tpl', 'Acme Template', '', 1, '$WS_ACME', '$ALICE')
                """.trimIndent(),
            )
            statement.execute(
                """
                INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, created_by) VALUES
                    ('$TPL_ACME_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', '$ALICE')
                """.trimIndent(),
            )
        }

        private fun seedKeys(connection: java.sql.Connection) {
            val pins = mapOf(ALICE_KEY to WS_ACME, CAROL_KEY to WS_ACME, ADMIN_KEY to WS_ACME, BOB_KEY to WS_GLOBEX)
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

            // T33's http half is provable on the wire only with an http base-url.
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
