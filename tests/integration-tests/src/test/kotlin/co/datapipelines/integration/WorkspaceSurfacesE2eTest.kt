package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
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
class WorkspaceSurfacesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val aliceKey get() = ALICE_KEY.plaintext
    private val bobKey get() = BOB_KEY.plaintext
    private val carolKey get() = CAROL_KEY.plaintext

    // ------------------------------------------------------------ datasource isolation (§5.3)

    @Test
    fun `a listing sees the active workspace's bound datasources plus global - and NOTHING else, with an exact total`() {
        ensureSeeded()
        ensureDatasourcesRegistered()
        // D-R7: "global" is gone. An INSTANCE datasource (no owning workspace) is visible to
        // nobody until a super admin GRANTS it — which is the whole change, so this asserts
        // both sides of it rather than only the end state.
        datasources(aliceKey).map { it["name"] } shouldContainExactlyInAnyOrder listOf(DS_ACME, DS_GLOBAL)
        datasources(bobKey).map { it["name"] } shouldContainExactlyInAnyOrder listOf(DS_GLOBEX, DS_GLOBAL)

        // …and a datasource granted to NEITHER is invisible to both, which is the half that
        // makes the two above a statement about grants rather than about registration.
        registerInstanceDatasource(DS_UNGRANTED, H2_GLOBAL_URL)
        datasources(aliceKey).map { it["name"] } shouldNotContain DS_UNGRANTED
        datasources(bobKey).map { it["name"] } shouldNotContain DS_UNGRANTED
    }

    /** Grants [datasource] to [workspace] as the super admin — the D-R7 verb, session-only. */
    private fun grant(
        datasource: String,
        workspace: String,
    ) {
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
            .`when`()
            .post("/api/v1/datasources/$datasource/grants/$workspace")
            .then()
            .statusCode(200)
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
            // Two: acme's own plus the granted instance one — and NOT the ungranted third.
            // The point of this case is that the TOTAL knows it; a post-filter would count
            // rows it then hides.
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

        // A switch to a non-membership is the D-R5 404 — including on this surface. It was the
        // 019 403 until RBAC round 1: "forbidden" told the caller the workspace EXISTS.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ALICE, "alice@acme.test", "acme"))
            .header(WORKSPACE_HEADER, "globex")
            .`when`()
            .get("/api/v1/datasources")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("workspace.not_found"))
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
    fun `create returns 201 with the creator as the workspace ADMIN - and it is session-only`() {
        // D-R11: workspaces are created by SUPER ADMINS, and O-2 removed `admin` from the key
        // wire — so no API key reaches this at all, on either axis. Alice's key is refused
        // below; the verb runs on ROOT's session.
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"name":"$FRESH_WS","display_name":"Fresh"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.scope.insufficient"))

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
            .body("""{"name":"$FRESH_WS","display_name":"Fresh"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(201)
            .body("data.name", Matchers.equalTo(FRESH_WS))

        // `role` left the wire with the column (D-R2): the flags are what a membership is.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .`when`()
            .get("/api/v1/workspaces")
            .then()
            .statusCode(200)
            .body("data.find { it.name == '$FRESH_WS' }.admin", Matchers.equalTo(true))
            .body("data.find { it.name == '$FRESH_WS' }.author", Matchers.equalTo(true))
    }

    @Test
    fun `a malformed name is 400 name_invalid`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
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
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
            .body("""{"name":"globex"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("workspace.validation.duplicate_name"))
    }

    @Test
    fun `unknown, non-member and deactivated are ONE 404 - the super admin sees the same code (D-R5)`() {
        ensureSeeded()
        for (name in listOf("ghost", "globex")) {
            given()
                .port(port)
                .header(API_KEY_HEADER, aliceKey)
                .`when`()
                .get("/api/v1/workspaces/$name")
                .then()
                .statusCode(404)
                .body("error.code", Matchers.equalTo("workspace.not_found"))
        }

        // …and the SUPER ADMIN gets the identical answer. Before D-R5 this was the one split
        // that existed: members got 403 and only an admin got a real 404. One answer now, so
        // the status itself stops being a signal about who is asking.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .`when`()
            .get("/api/v1/workspaces/ghost")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("workspace.not_found"))
    }

    @Test
    fun `delete is 409 in_use naming each blocking content kind`() {
        // D-R10 made delete an INSTANCE verb (deactivation is what operators want), so it runs
        // on a super admin's session — the content check it is really about is unchanged.
        ensureSeeded()
        ensureDatasourcesRegistered()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
            .`when`()
            .delete("/api/v1/workspaces/acme")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("workspace.in_use"))
            .body("error.details.counts.pipelines", Matchers.equalTo(1))
            .body("error.details.counts.templates", Matchers.equalTo(1))
            .body("error.details.counts.datasources", Matchers.equalTo(1))

        // globex owns ONLY a bound datasource — the count names exactly that kind. Same
        // credential class as above: delete is an instance verb (D-R10), so no key reaches it.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
            .`when`()
            .delete("/api/v1/workspaces/globex")
            .then()
            .statusCode(409)
            .body("error.details.counts.datasources", Matchers.equalTo(1))
            .body("error.details.counts.pipelines", Matchers.nullValue())
    }

    @Test
    fun `update is ws_admin - a plain author's key is refused, the workspace admin renames`() {
        ensureSeeded()
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, carolKey)
            .body("""{"display_name":"Hijacked"}""")
            .`when`()
            .put("/api/v1/workspaces/acme")
            .then()
            // Carol is an AUTHOR of acme, not an admin. Her key's SCOPE is fine, so the axis
            // that refuses is the role one — and because the credential is a key, the code
            // says the issuer's role is short rather than the caller's (D-R12).
            .statusCode(403)
            .body("error.code", Matchers.equalTo("auth.key_issuer_role_lost"))

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
    fun `a ws admin lists, adds and removes members - and cannot remove the LAST admin`() {
        ensureSeeded()
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            // 113: the listing is TWO arrays — `members[]` (real people) and `invitations[]`
            // (pending ghosts), never mixed.
            .body("data.members.email", Matchers.hasItems("alice@acme.test", "carol@acme.test"))
            .body("data.invitations", Matchers.empty<Any>())

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"email":"bob@globex.test"}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            // Silence on a permission grant means the least a membership can be: a VIEWER
            // (D-R11). `role` left the wire with the column — the flags are the membership.
            .body("data.author", Matchers.equalTo(false))
            .body("data.promoter", Matchers.equalTo(false))
            .body("data.admin", Matchers.equalTo(false))

        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme/members/$BOB")
            .then()
            .statusCode(204)

        // Alice is acme's only admin. Removing her is `workspace.last_admin` — its own code
        // rather than the old `in_use`, because the caller's next step is "promote somebody
        // else first", which is a different instruction from "empty the workspace first".
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme/members/$ALICE")
            .then()
            .statusCode(409)
            .body("error.code", Matchers.equalTo("workspace.last_admin"))
    }

    @Test
    fun `an unknown member email becomes a 202 INVITATION, revoked after so the listing stays clean`() {
        ensureSeeded()
        // 113 §B.1: an email with no users row is no longer the §16.3 unknown-user 404 —
        // it is a `202` invitation, distinguishable from the `200` membership by status AND
        // body, so a client never mistakes a ghost for a member.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, aliceKey)
            .body("""{"email":"ghost@nowhere.test"}""")
            .`when`()
            .post("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(202)
            .body("data.invited", Matchers.equalTo(true))
            .body("data.email", Matchers.equalTo("ghost@nowhere.test"))
            .body("data.author", Matchers.equalTo(false))

        // The ghost sits in ITS array, and in no member's array.
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .get("/api/v1/workspaces/acme/members")
            .then()
            .statusCode(200)
            .body("data.members.email", Matchers.not(Matchers.hasItem("ghost@nowhere.test")))
            .body("data.invitations.email", Matchers.hasItem("ghost@nowhere.test"))

        // Revoking is the admin's undo — and this suite's cleanup, so the members listing
        // test below sees the empty invitations[] it asserts.
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme/invitations/ghost@nowhere.test")
            .then()
            .statusCode(204)

        // A second revoke has nothing to remove: `workspace.invitation.not_found`.
        given()
            .port(port)
            .header(API_KEY_HEADER, aliceKey)
            .`when`()
            .delete("/api/v1/workspaces/acme/invitations/ghost@nowhere.test")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("workspace.invitation.not_found"))
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
            {"id": "test/dup_tpl", "dialect": "POSTGRES", "display_name": "Dup",
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
            .header("Location", Matchers.endsWith("/dashboard"))

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
        registerInstanceDatasource(DS_GLOBAL, H2_GLOBAL_URL)
        // Granted HERE, once, rather than inside the visibility case: a test that grants makes
        // every later test's answer depend on execution order, and the paging total is exactly
        // the assertion that would then pass or fail by accident.
        grant(DS_GLOBAL, "acme")
        grant(DS_GLOBAL, "globex")
    }

    /**
     * Registers a datasource no workspace owns. Session-only since O-2: it is
     * `MUTATE_DATASOURCES`, which needs `admin` on the credential axis, and no key holds that.
     */
    private fun registerInstanceDatasource(
        name: String,
        jdbcUrl: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "acme"))
            .cookie(CSRF_COOKIE, "surfaces-csrf")
            .header(CSRF_HEADER, "surfaces-csrf")
            .body(
                """{"name": "$name", "display_name": "Surfaces $name", "dialect": "H2",
                   "jdbc_url": "$jdbcUrl", "username": "$H2_USER", "password": "$H2_PASSWORD","global":true}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
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

    companion object {
        private var datasourcesRegistered = false

        private const val API_KEY_HEADER = "DP-API-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val SECRET_BYTES = 32
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

        /** Registered and granted to NOBODY — the D-R7 control (see the visibility case). */
        private const val DS_UNGRANTED = "ds-ungranted"
        private const val FRESH_WS = "fresh-team"

        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val H2_ACME_URL = "jdbc:h2:mem:surfaces_acme;DB_CLOSE_DELAY=-1"
        private const val H2_GLOBEX_URL = "jdbc:h2:mem:surfaces_globex;DB_CLOSE_DELAY=-1"
        private const val H2_GLOBAL_URL = "jdbc:h2:mem:surfaces_global;DB_CLOSE_DELAY=-1"

        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"report","display_name":"Report","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"test/t","version":1}}]}"""

        private val random = SecureRandom()

        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val ALICE_KEY = E2eAuth.generateKey("alice-key", arrayOf("read", "execute", "author"), ownerId = ALICE)
        private val BOB_KEY = E2eAuth.generateKey("bob-key", arrayOf("read", "execute", "author"), ownerId = BOB)
        private val CAROL_KEY = E2eAuth.generateKey("carol-key", arrayOf("read", "execute", "author"), ownerId = CAROL)
        private val ADMIN_KEY = E2eAuth.generateKey("admin-key", arrayOf("read", "execute", "author"), ownerId = ROOT)

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

            // Exact-set listing assertions (§5.3): the shared database must not carry any
            // earlier suite's rows — least of all another suite's GLOBAL datasources.
            E2eClean.beforeSeeding()

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
                        INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin) VALUES
                            ('$WS_ACME', '$ALICE', TRUE, FALSE, TRUE),
                            ('$WS_ACME', '$CAROL', TRUE, FALSE, FALSE),
                            ('$WS_GLOBEX', '$BOB', TRUE, FALSE, TRUE)
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
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                    ('$PIPE_ACME', 1, '$PIPELINE_BODY'::jsonb, 'seed-hash', 'RELEASED', '$ALICE', '$ALICE', NOW())
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
                INSERT INTO template_versions (template_id, version, engine, dialect, is_library, imports_json, body, body_hash, status, created_by, released_by, released_at) VALUES
                    ('$TPL_ACME_ID', 1, 'freemarker', 'POSTGRES', FALSE, '[]'::jsonb, 'SELECT 1', 'seed-hash', 'RELEASED', '$ALICE', '$ALICE', NOW())
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

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedE2e.postgres

        private val redis get() = SharedE2e.redis

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
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }

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
