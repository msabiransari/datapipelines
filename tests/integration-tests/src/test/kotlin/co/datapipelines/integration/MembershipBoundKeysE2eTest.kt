package co.datapipelines.integration

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.SecureRandom
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64

/**
 * **A member's CREATED key ends with the membership it is pinned to — and a workspace admin
 * can revoke the keys a member created** (#200, roles record §3.7; keys v2 A17/B6 restatement),
 * proven at the wire against the FULL application: real logins through `POST /login`, the real
 * Keys-page creation path (`POST /api/v1/auth/api-keys` with a role — the login mint is
 * retired, A15), the real member-removal and created-key-revoke verbs, and three surfaces:
 * `/mcp` (`pipelines_list`), the ONE surface the key serves, and REST (`GET /api/v1/pipelines`)
 * and a published endpoint, which refuse a live MCP key (`403 endpoint.key_kind_refused`,
 * `mcp_key_off_surface` — #215 B2) but answer a DEAD one like any dead credential, because the
 * key is validated first.
 *
 *  - **(a) removal revokes the keys the member CREATED (§3.7 ruling 1, restated for
 *    created-by).** A member creates a key (role author, the subset rule) and it serves `/mcp`;
 *    the admin removes the member; the SAME key answers `auth.api_key.invalid` (401) on all
 *    three. Re-invite + a NEW created key serves again — removal is a membership fact, not an
 *    identity ban, and (A15) no key is minted at the next login: the member creates one.
 *  - **(b) an admin revoke keeps the member (§3.7 ruling 3).** A fresh member's created key
 *    serves `/mcp`; the admin revokes every key the member created
 *    (`DELETE .../members/{id}/key`, `204`); the key answers 401 on all three while the
 *    member's SESSION keeps working — revoking a key is not deactivation. The verb is
 *    idempotent: revoking again is another `204`, and no automatic rotation exists to do this
 *    for anyone (§3.7 ruling 2 — recovery is the member's own new key on the Keys page).
 *
 * Non-vacuity: each revocation is preceded by ≥ [SERVED_FLOOR] answered `200`s on `/mcp`, and
 * the two off-surface refusals, printed per phase (`event=keybound.phase …`).
 *
 * Namespaced (`kbound-*`) because the module's containers are shared between suites in one
 * JVM run; the suite creates its world through the app (the creation IS the subject), so it
 * cleans its OWN rows rather than truncating (the `ApiKeyMintingTest` discipline).
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MembershipBoundKeysE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @Order(1)
    fun `a - removing the member ends the keys they created on every surface they served, and re-entry creates fresh`() {
        val world = ensureWorld()
        val bob = memberKey(BOB_EMAIL, world.bobOneTime, "kbound-bob-key")

        // Non-vacuity: the key the member CREATED serves /mcp BEFORE anything ends it.
        val servedBefore = serveAll(bob.key)
        val before = servedBefore.entries.joinToString(" ") { "${it.key}:${it.value}" }
        println("event=keybound.phase phase=removal_before served=$before")
        servedBefore.values.forEach { it shouldBeGreaterThanOrEqual SERVED_FLOOR }

        withAdmin { removeMember(it, world.acme, bob.userId) }

        // The SAME key, all three surfaces: 401 — the credential, not the person.
        serveAllExpectInvalid(bob.key)

        // Re-entry: re-invite, sign in again, CREATE a new key on the Keys page — and the
        // surfaces answer again (A15: no key is minted at the login; the member creates one).
        withAdmin { reinvite(it, world.acme, BOB_EMAIL) }
        val bobAgain = memberKey(BOB_EMAIL, world.bobOneTime, "kbound-bob-key-2", settled = true)
        bobAgain.userId shouldBe bob.userId
        bobAgain.keyId shouldNotContain bob.keyId
        val servedAfter = serveAll(bobAgain.key)
        val afterReentry = servedAfter.entries.joinToString(" ") { "${it.key}:${it.value}" }
        println("event=keybound.phase phase=removal_after_reentry served=$afterReentry")
        servedAfter.values.forEach { it shouldBeGreaterThanOrEqual SERVED_FLOOR }
    }

    @Test
    @Order(2)
    fun `b - an admin revokes the keys a member created, the member's session survives, and they create a fresh one`() {
        val world = ensureWorld()
        val carol = memberKey(CAROL_EMAIL, world.carolOneTime, "kbound-carol-key")

        val servedBefore = serveAll(carol.key)
        val beforeRevoke = servedBefore.entries.joinToString(" ") { "${it.key}:${it.value}" }
        println("event=keybound.phase phase=revoke_before served=$beforeRevoke")
        servedBefore.values.forEach { it shouldBeGreaterThanOrEqual SERVED_FLOOR }

        withAdmin { admin ->
            revokeMemberKey(admin, world.acme, carol.userId)
            // Idempotent: no live created key any more, and the verb still answers the success it asked for.
            revokeMemberKey(admin, world.acme, carol.userId)
        }

        // The key is dead: 401 on all three surfaces…
        serveAllExpectInvalid(carol.key)

        // …but the MEMBER is not: the session keeps working (§3.7 ruling 2 — revoking the
        // key is not deactivation, and no identity event did it either).
        given()
            .port(port)
            .cookie("dp_session", carol.session)
            .`when`()
            .get("/api/v1/auth/me")
            .then()
            .statusCode(200)
            .body("data.email", Matchers.equalTo(CAROL_EMAIL))

        // The member creates a fresh key on the Keys page (A15 — no login mint), and it serves.
        val carolAgain = memberKey(CAROL_EMAIL, world.carolOneTime, "kbound-carol-key-2", settled = true)
        carolAgain.userId shouldBe carol.userId
        carolAgain.keyId shouldNotContain carol.keyId
        val servedAfter = serveAll(carolAgain.key)
        val afterRelogin = servedAfter.entries.joinToString(" ") { "${it.key}:${it.value}" }
        println("event=keybound.phase phase=revoke_after_relogin served=$afterRelogin")
        servedAfter.values.forEach { it shouldBeGreaterThanOrEqual SERVED_FLOOR }
    }

    // ------------------------------------------------------------------ the world

    /**
     * The fixture, created ONCE through the product's own surfaces: the admin's workspace
     * with a released pipeline published as an endpoint, and two local members (each created
     * with a one-time password, settled before their first clean login). Everything is
     * `kbound`-namespaced so a re-run on the shared container cannot collide with the last
     * run's rows.
     */
    private class World(
        val acme: String,
        val bobOneTime: String,
        val carolOneTime: String,
    )

    private fun ensureWorld(): World {
        world?.let { return it }
        ensureCleanSlate()
        // The admin is the slate's settled local account: one login, no forced change.
        val admin = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        admin.statusCode shouldBe 302

        // The workspace, then the admin's ENTRY into it. The re-stamped SESSION drives the
        // publishing fixture — its active workspace decides where every write lands. (No key
        // is minted at sign-in, A15 — the members create their own in the tests.)
        createWorkspace(admin.sessionCookie("slate-admin", userRow(ADMIN_EMAIL)), admin.csrfToken, WS_ACME)
        val publisher = AdminAuth(switch(admin.sessionCookie("slate-admin", userRow(ADMIN_EMAIL)), admin.csrfToken, WS_ACME), admin.csrfToken)

        registerDatasource(publisher)
        createTemplate(publisher)
        createPipeline(publisher)
        publish(publisher)

        val bobOneTime = createLocalUser(admin.sessionCookie("slate-admin", userRow(ADMIN_EMAIL)), admin.csrfToken, BOB_EMAIL, WS_ACME, "author")
        val carolOneTime = createLocalUser(admin.sessionCookie("slate-admin", userRow(ADMIN_EMAIL)), admin.csrfToken, CAROL_EMAIL, WS_ACME, "author")
        return World(WS_ACME, bobOneTime, carolOneTime).also { world = it }
    }

    // ------------------------------------------------------------------ the surfaces

    /**
     * `/mcp` serves the member's live key — counted, so non-vacuity has a number — and the two
     * other surfaces refuse it as an MCP key (#215 B2), counted the same way.
     */
    private fun serveAll(key: String): Map<String, Int> =
        mapOf(
            "mcp" to count(200) { mcp(key) },
            "rest_refused" to count(403) { rest(key).also { it.then().body("error.details.reason", Matchers.equalTo(OFF_SURFACE)) } },
            "endpoint_refused" to
                count(403) { endpoint(key).also { it.then().body("error.details.reason", Matchers.equalTo(OFF_SURFACE)) } },
        )

    /** The same three surfaces, all of which must now answer `401 auth.api_key.invalid`. */
    private fun serveAllExpectInvalid(key: String) {
        rest(key).then().statusCode(401).body("error.code", Matchers.equalTo("auth.api_key.invalid"))
        mcp(key).then().statusCode(401).body("error.code", Matchers.equalTo("auth.api_key.invalid"))
        endpoint(key).then().statusCode(401).body("error.code", Matchers.equalTo("auth.api_key.invalid"))
    }

    private fun rest(key: String): Response =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .`when`()
            .get("/api/v1/pipelines")

    private fun mcp(key: String): Response =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"pipelines_list","arguments":{}}}""")
            .`when`()
            .post("/mcp")

    private fun endpoint(key: String): Response =
        given()
            .port(port)
            .header(API_KEY_HEADER, key)
            .accept("application/json")
            .`when`()
            .get("/api/kbound/v1/report")

    /** A fixture step's verdict: a mismatch prints the BODY — a 400 without its code is a guess. */
    private fun expect(
        response: io.restassured.response.ExtractableResponse<Response>,
        status: Int,
        step: String,
    ): io.restassured.response.ExtractableResponse<Response> {
        if (response.statusCode() != status) {
            println("event=keybound.fixture_failed step=$step status=${response.statusCode()} body=${response.asString().take(400)}")
        }
        response.statusCode() shouldBe status
        return response
    }

    private fun count(
        status: Int,
        call: () -> Response,
    ): Int {
        var hits = 0
        repeat(SERVE_ATTEMPTS) { if (call().statusCode == status) hits++ }
        return hits
    }

    // ------------------------------------------------------------------ the verbs

    /** The admin's member removal — §17.8, whose NEW effect is the key revoke (#200 ruling 1). */
    private fun removeMember(
        admin: AdminAuth,
        workspace: String,
        userId: String,
    ) {
        given()
            .port(port)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .`when`()
            .delete("/api/v1/workspaces/$workspace/members/$userId")
            .then()
            .statusCode(204)
    }

    /** The admin's key revoke — §17.11 (#200 ruling 3): the member stays, the key dies. */
    private fun revokeMemberKey(
        admin: AdminAuth,
        workspace: String,
        userId: String,
    ) {
        given()
            .port(port)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .`when`()
            .delete("/api/v1/workspaces/$workspace/members/$userId/key")
            .then()
            .statusCode(204)
    }

    /** Re-invite a removed member by email — the membership the next login materialises. */
    private fun reinvite(
        admin: AdminAuth,
        workspace: String,
        email: String,
    ) {
        given()
            .port(port)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .contentType(ContentType.JSON)
            .body("""{"email":"$email","role":"author"}""")
            .`when`()
            .post("/api/v1/workspaces/$workspace/members")
            .then()
            .statusCode(200)
    }

    // ------------------------------------------------------------------ members and keys

    private class MemberSession(
        val userId: String,
        val session: String,
        val keyId: String,
        val key: String,
    )

    /** A fresh admin session (its own login), for the member-management verbs. */
    private class AdminAuth(
        val session: String,
        val csrf: String,
    )

    private inline fun withAdmin(block: (AdminAuth) -> Unit) {
        val login = postLogin(ADMIN_EMAIL, ADMIN_PASSWORD)
        check(login.statusCode == 302 && login.sessionCookieOrNull() != null) {
            "admin login failed: ${login.statusCode} -> ${login.location} user=[${userRow(ADMIN_EMAIL)}]"
        }
        block(AdminAuth(login.sessionCookie("admin", userRow(ADMIN_EMAIL)), login.csrfToken))
    }

    /**
     * Real login → settle the forced change on first entry → clean login → CREATE a key on the
     * Keys page's REST path (keys v2 A15: no login mint — `POST /api/v1/auth/api-keys`, kind
     * `mcp`, role `author`, the one role an author creator can offer). A member who already
     * settled their password in this run ([settled]) signs in with [MEMBER_PASSWORD] directly —
     * re-entry consumes no second one-time credential.
     */
    private fun memberKey(
        email: String,
        oneTime: String,
        keyName: String,
        settled: Boolean = false,
    ): MemberSession {
        val session: String
        val csrf: String
        if (settled) {
            val login = postLogin(email, MEMBER_PASSWORD)
            login.statusCode shouldBe 302
            session = login.sessionCookie("settled re-entry", userRow(email))
            csrf = login.csrfToken
        } else {
            val first = postLogin(email, oneTime)
            first.statusCode shouldBe 302
            postPasswordChange(first.sessionCookie("first", userRow(email)), first.csrfToken, oneTime, MEMBER_PASSWORD, MEMBER_PASSWORD)
                .statusCode shouldBe 200
            val clean = postLogin(email, MEMBER_PASSWORD)
            clean.statusCode shouldBe 302
            session = clean.sessionCookie("clean", userRow(email))
            csrf = clean.csrfToken
        }
        val created =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", csrf)
                .header("DP-CSRF-Token", csrf)
                .contentType(ContentType.JSON)
                .body("""{"name": "$keyName", "kind": "mcp", "role": "author"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .body("data.role", Matchers.equalTo("author"))
                .extract()
                .jsonPath()
        return MemberSession(
            userId = userIdOf(email),
            session = session,
            keyId = created.getString("data.id"),
            key = created.getString("data.key"),
        )
    }

    private fun userIdOf(email: String): String = query("SELECT id::text FROM users WHERE email = '$email'") { it.getString(1) }.single()

    // ------------------------------------------------------------------ the publishing fixture

    private fun createWorkspace(
        session: String,
        csrf: String,
        name: String,
    ) {
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", csrf)
            .header("DP-CSRF-Token", csrf)
            .contentType(ContentType.JSON)
            .body("""{"name":"$name"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .extract()
            .let { expect(it, 201, "create-workspace") }
    }

    /** The switcher POST; returns the RE-STAMPED session cookie (the old one is dead weight). */
    private fun switch(
        session: String,
        csrf: String,
        workspace: String,
    ): String {
        val response =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", csrf)
                .header("DP-CSRF-Token", csrf)
                .contentType(ContentType.URLENC)
                .formParam("name", workspace)
                .redirects()
                .follow(false)
                .`when`()
                .post("/workspace/switch")
                .then()
                .statusCode(302)
                .extract()
        return response.detailedCookies().firstOrNull { it.name == "dp_session" }?.value ?: session
    }

    /** Creates a local user through the REAL admin partial; returns the one-time password. */
    private fun createLocalUser(
        session: String,
        csrf: String,
        email: String,
        workspace: String,
        role: String,
    ): String {
        val response =
            given()
                .port(port)
                .cookie("dp_session", session)
                .cookie("dp_csrf", csrf)
                .header("DP-CSRF-Token", csrf)
                .contentType(ContentType.URLENC)
                .formParam("email", email)
                .formParam("displayName", email.substringBefore('@'))
                .formParam("workspace", workspace)
                .formParam("role", role)
                .`when`()
                .post("/partials/admin/users")
        response.statusCode shouldBe 200
        return checkNotNull(ONE_TIME_PASSWORD.find(response.body().asString())) {
            "no one-time password in the create response"
        }.groupValues[1]
    }

    private fun registerDatasource(admin: AdminAuth) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .body(
                """
                {"name": "kbound-source", "display_name": "Keybound source", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .extract()
            .let { expect(it, 201, "register-datasource") }
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE markers (x INT NOT NULL)")
                statement.execute("INSERT INTO markers (x) VALUES (42)")
            }
        }
    }

    private fun createTemplate(admin: AdminAuth) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .body(
                """
                {"id": "kbound/report.sql", "dialect": "POSTGRES", "display_name": "Keybound report",
                 "description": "#200 keybound E2E fixture.", "imports": [], "body": "SELECT x FROM markers"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .extract()
            .let { expect(it, 201, "create-template") }
        // Templates lock first (versioning §6): the pipeline pins a RELEASED version.
        val hash =
            given()
                .port(port)
                .cookie("dp_session", admin.session)
                .cookie("dp_csrf", admin.csrf)
                .header("DP-CSRF-Token", admin.csrf)
                .queryParam("name", "kbound/report.sql")
                .`when`()
                .get("/api/v1/templates")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .header("If-Match", hash)
            .body("""{"name": "kbound/report.sql"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun createPipeline(admin: AdminAuth) {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .cookie("dp_session", admin.session)
                .cookie("dp_csrf", admin.csrf)
                .header("DP-CSRF-Token", admin.csrf)
                .body(
                    """
                    {"schema_version": 1, "name": "kbound/report", "display_name": "Keybound report",
                     "description": "#200 keybound E2E.", "parameters": {},
                     "nodes": [{"id": "report", "description": "One row", "type": "DQL",
                                "source": "kbound-source", "template": {"id": "kbound/report.sql", "version": 1},
                                "depends_on": []}]}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/pipelines")
                .then()
                .extract()
                .also { expect(it, 201, "create-pipeline") }

        val id = created.jsonPath().getString("data.id")
        given()
            .port(port)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
            .body("data.status", Matchers.equalTo("RELEASED"))
    }

    private fun publish(admin: AdminAuth) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie("dp_session", admin.session)
            .cookie("dp_csrf", admin.csrf)
            .header("DP-CSRF-Token", admin.csrf)
            .body("""{"path": "/kbound/v1/report", "pipeline": "kbound/report", "timeout_seconds": 60}""")
            .`when`()
            .post("/api/v1/endpoints")
            .then()
            .extract()
            .let { expect(it, 201, "publish-endpoint") }
    }

    // ------------------------------------------------------------------ plumbing

    private fun postLogin(
        email: String,
        password: String,
    ): LoginResponse {
        val page =
            given()
                .port(port)
                .`when`()
                .get("/login")
                .then()
                .statusCode(200)
                .extract()
        val csrf =
            checkNotNull(CSRF_FIELD.find(page.asString())) { "no _csrf hidden input on the login page" }
                .groupValues[1]
        val response =
            given()
                .port(port)
                .cookies(page.detailedCookies().asList().associate { it.name to it.value })
                .contentType(ContentType.URLENC)
                .formParam("_csrf", csrf)
                .formParam("email", email)
                .formParam("password", password)
                .redirects()
                .follow(false)
                .`when`()
                .post("/login")
        // dp_csrf rides the PAGE response (the POST does not re-issue it); dp_session
        // rides the POST. Merge, POST winning.
        val cookies =
            page.detailedCookies().asList().associate { it.name to it.value } +
                response.detailedCookies().asList().associate { it.name to it.value }
        return LoginResponse(
            statusCode = response.statusCode,
            location = response.header("Location"),
            cookies = cookies,
        )
    }

    private fun postPasswordChange(
        session: String,
        csrf: String,
        current: String,
        new: String,
        confirm: String,
    ): Response =
        given()
            .port(port)
            .cookie("dp_session", session)
            .cookie("dp_csrf", csrf)
            .header("DP-CSRF-Token", csrf)
            .contentType(ContentType.URLENC)
            .formParam("currentPassword", current)
            .formParam("newPassword", new)
            .formParam("confirmPassword", confirm)
            .`when`()
            .post("/partials/account/password")

    private data class LoginResponse(
        val statusCode: Int,
        val location: String?,
        private val cookies: Map<String, String>,
    ) {
        /**
         * #221: a login that answers without a session must NAME its refusal — the handler's
         * three redirects are all in [location] (`/login?error=credentials|locked|inactive`)
         * and the status/cookies it already holds were being dropped (the old `diagnose`
         * printed them to stdout only, which the next cycle's XML overwrote). The subject
         * user's row state rides along at the call sites that know the email ([userRow]).
         */
        fun sessionCookie(
            step: String,
            userRow: String? = null,
        ): String =
            checkNotNull(sessionCookieOrNull()) {
                buildString {
                    append("no dp_session cookie after $step login: status=$statusCode location=$location cookies=$cookies")
                    if (userRow != null) append(" user=[$userRow]")
                }
            }

        fun sessionCookieOrNull(): String? = cookies["dp_session"]

        /** The `dp_csrf` cookie IS the token (plain double-submit, auth.md §8.4). */
        val csrfToken: String get() = checkNotNull(cookies["dp_csrf"]) { "no dp_csrf cookie in $cookies" }
    }

    /**
     * #221: the row state behind a refused login — the lockout columns decide which of the
     * three refusals it was (`credentials` needs no column, `locked` is [failed_login_count]
     * and [locked_until], `inactive` is [is_active]). No row is itself the answer.
     */
    private fun userRow(email: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT provider || '/' || provider_subject || ' active=' || is_active || " +
                        "' must_change=' || must_change_password || ' failed=' || failed_login_count || " +
                        "' locked_until=' || coalesce(locked_until::text, 'never') FROM users WHERE email = ?",
                )
                .use { ps ->
                    ps.setString(1, email)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) else "(no row)"
                    }
                }
        }

    private fun <T> query(
        sql: String,
        row: (ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) row(rs) else null }.toList()
                }
            }
        }

    companion object {
        private var world: World? = null
        private var cleaned = false

        /**
         * This suite creates its world through the APP, so it cannot `E2eClean.beforeSeeding`
         * (that would truncate the demo content the admin's first login needs). It deletes its
         * OWN namespaced rows so a re-run on the shared container cannot collide with the last
         * run's, and hands the bootstrap admin its seed credential BACK (a real Argon2id hash
         * of this run's seed password, from the same helper every suite's key fixture uses).
         *
         * The slate runs ONCE per JVM — the application caches workspaces by name (§11.4's
         * 60s TTL), so a mid-run delete would leave the switch minting into a cached ghost —
         * and the audit rows go FIRST: `audit_log` is append-only with a live FK to `users`,
         * and this suite's own events (the mints, the revocations) reference the very rows
         * the slate removes. @AfterAll re-runs it: the shared bootstrap-admin row goes back
         * to the seed credential with its forced change owed, for whatever suite shares this
         * JVM next (the 191 gate's test-state discipline).
         */
        private fun ensureCleanSlate() {
            if (cleaned) return
            cleaned = true
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "DELETE FROM audit_log WHERE user_id IN " +
                            "(SELECT id FROM users WHERE email LIKE 'kbound-%@datapipelines.test')",
                    )
                    statement.execute(
                        "DELETE FROM execution_events WHERE execution_id IN " +
                            "(SELECT execution_id FROM pipeline_executions WHERE pipeline_id IN " +
                            "(SELECT id FROM pipelines WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%')))",
                    )
                    statement.execute(
                        "DELETE FROM pipeline_executions WHERE pipeline_id IN " +
                            "(SELECT id FROM pipelines WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%'))",
                    )
                    statement.execute(
                        "DELETE FROM pipeline_check_runs WHERE pipeline_id IN " +
                            "(SELECT id FROM pipelines WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%'))",
                    )
                    statement.execute(
                        "DELETE FROM endpoint_key_bindings WHERE api_key_id IN " +
                            "(SELECT id FROM api_keys WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%'))",
                    )
                    statement.execute(
                        "DELETE FROM published_endpoints WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%')",
                    )
                    statement.execute(
                        "DELETE FROM pipelines WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%')",
                    )
                    // Since V4 a template is workspace-scoped with a UUID surrogate PK (the
                    // TEXT name lives in `templates.name`), and its versions cascade.
                    statement.execute(
                        "DELETE FROM templates WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%')",
                    )
                    statement.execute(
                        "DELETE FROM datasources WHERE owner_workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%')",
                    )
                    statement.execute(
                        "DELETE FROM workspace_invitations WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%')",
                    )
                    statement.execute(
                        "DELETE FROM api_keys WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%') " +
                            "OR user_id IN (SELECT id FROM users WHERE email LIKE 'kbound-%@datapipelines.test')",
                    )
                    statement.execute(
                        "DELETE FROM workspace_members WHERE workspace_id IN (SELECT id FROM workspaces WHERE name LIKE 'kbound%') " +
                            "OR user_id IN (SELECT id FROM users WHERE email LIKE 'kbound-%@datapipelines.test')",
                    )
                    statement.execute("DELETE FROM workspaces WHERE name LIKE 'kbound%'")
                    statement.execute("DELETE FROM users WHERE email LIKE 'kbound-%@datapipelines.test'")
                    seedAdmin(statement)
                }
            }
        }

        private const val API_KEY_HEADER = "DP-API-Key"
        private const val ADMIN_EMAIL = "kbound-admin@datapipelines.test"
        private const val BOB_EMAIL = "kbound-bob@datapipelines.test"
        private const val CAROL_EMAIL = "kbound-carol@datapipelines.test"
        private const val WS_ACME = "kbound-acme"
        private const val ADMIN_PASSWORD = "a-brand-new-admin-password"
        private const val MEMBER_PASSWORD = "a-brand-new-member-password"

        /** Non-vacuity: the key must serve, not merely authenticate — three answers per surface (200 on /mcp, 403 off it). */
        private const val SERVED_FLOOR = 3

        /** #215 B2: the reason a live MCP key is refused off `/mcp`. */
        private const val OFF_SURFACE = "mcp_key_off_surface"
        private const val SERVE_ATTEMPTS = 3

        private val CSRF_FIELD = Regex("""name="_csrf" value="([^"]+)"""")
        private val ONE_TIME_PASSWORD = Regex("""([A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4})""")

        private val random = SecureRandom()

        private val postgres get() = SharedE2e.postgres
        private val source = SharedE2e.scratchDatabase("kbound_source")
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        /**
         * The admin as a settled LOCAL account — created here, not by the boot seeder: a
         * reused container may hold ANOTHER suite's bootstrap actor, and the seeder rightly
         * seeds once per database and refuses to re-seed (§5A.2).
         */
        private fun seedAdmin(statement: java.sql.Statement) {
            val adminHash = E2eAuth.argon2Hash(ADMIN_PASSWORD)
            statement.execute(
                "INSERT INTO users (email, display_name, provider, provider_subject, is_active, is_admin, " +
                    "password_hash, password_changed_at, must_change_password) " +
                    "VALUES ('$ADMIN_EMAIL', 'Keybound Admin', 'local', 'kbound-admin', TRUE, TRUE, " +
                    "'$adminHash', NOW(), FALSE)",
            )
        }

        /**
         * Test-state discipline (the 191 gate's lesson): this suite changes the SHARED
         * bootstrap-admin row (settles its forced change), so it hands that row BACK — seed
         * credential, forced change owed — and removes its kbound world, leaving the shared
         * container as it found it for whatever suite shares this JVM.
         */
        @JvmStatic
        @AfterAll
        fun tearDown() {
            SharedE2e.postgres // first touch boots the container the slate runs against
            ensureCleanSlate()
        }

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
            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }
            // Local-only deployment (no OIDC at all); the admin account is the slate's own
            // INSERT, so no bootstrap seeder state is consulted (a reused container may hold
            // another suite's bootstrap actor and the seeder rightly refuses to re-seed).
            registry.add("datapipelines.auth.local.enabled") { true }
            // This suite logs in CONSTANTLY; the per-IP login limit would 429 the later
            // tests for the earlier ones' traffic.
            registry.add("datapipelines.auth.rate-limit.login-per-minute") { 100000 }
        }
    }
}
