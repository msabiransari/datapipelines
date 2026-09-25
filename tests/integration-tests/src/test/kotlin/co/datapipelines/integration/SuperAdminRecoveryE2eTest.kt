package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
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
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #113 — the super-admin recovery witness, against the FULL application.
 *
 * The trap this suite walks into on purpose: a super admin deactivates the LAST active
 * workspace. From that request on, [co.datapipelines.auth.WorkspaceService.resolveForSession]
 * has nothing to fall back to (D-R8's first-active-workspace fallback reads an empty set),
 * the session resolves a NULL workspace context, and `ScopeMatrix.allowed`'s null-context
 * branch used to refuse everything but `WORKSPACES_READ` — including the instance verbs
 * (create / reactivate a workspace, user administration) that are the only way OUT. The
 * no-workspace page even renders the create form to that super admin, and the form could
 * not work.
 *
 * The order matters and is pinned with @Order: the empty-instance state is established once
 * and the recovery assertions run inside it. The witness halves are the assertions that are
 * RED on the pre-fix tree: REST create/reactivate and user administration from a null-context
 * super-admin session, and the browser create post from the no-workspace page. The refusal
 * halves are green on BOTH trees: workspace-scoped operations stay the D-R5 404, a non-admin
 * with no memberships is refused the same way, and an API key gets no exception at all.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SuperAdminRecoveryE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    @Order(1)
    fun `the super admin reaches the instance through the D-R8 fallback while a workspace is active`() {
        ensureSeeded()
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .`when`()
            .get("/api/v1/workspaces")
            .then()
            .statusCode(200)
            .body("data.name", Matchers.hasItem("default"))

        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .`when`()
            .get("/api/v1/auth/users")
            .then()
            .statusCode(200)
    }

    @Test
    @Order(2)
    fun `deactivating the LAST active workspace refuses workspace-scoped operations - and nothing else changes hands`() {
        ensureSeeded()
        // The verb itself still has a context: the fallback resolved `default` for it.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .`when`()
            .post("/api/v1/workspaces/default/deactivate")
            .then()
            .statusCode(200)

        // From here on the instance has ZERO active workspaces and the session's context is
        // null. "Which workspaces do I belong to" stays meaningful (auth.md §11A.1).
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .`when`()
            .get("/api/v1/workspaces")
            .then()
            .statusCode(200)

        assertWorkspaceSideStaysClosed()
        assertNoWorkspacePages()
    }

    /** The refusal half, green on BOTH trees: the allowance is no null-context skeleton key. */
    private fun assertWorkspaceSideStaysClosed() {
        // Workspace-scoped operations keep the D-R5 answer, super admin or not — the recovery
        // allowance is for INSTANCE verbs.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("workspace.not_found"))

        // A key gets no exception at all: one pinned to the deactivated workspace is the
        // catalogued `auth.key_workspace_inactive`, and the matrix's null-context allowance is
        // session-only by construction. (The key is validated before its KIND is judged, so the
        // liveness code answers here even though a live MCP key is refused on REST — #215 B2.)
        given()
            .port(port)
            .header(API_KEY_HEADER, ROOT_KEY.plaintext)
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("auth.key_workspace_inactive"))

        // The negative case the issue asks for: a NON-admin with no memberships reaches the
        // same null context and is refused the instance verb exactly as before — the allowance
        // is the super-admin row of the matrix, not a null-context opening.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(SESSION_COOKIE, sessionJwt(NOBODY, "nobody@company.test", null))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .body("""{"name":"nobody-ws","display_name":"Nobody"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(404)
            .body("error.code", Matchers.equalTo("workspace.not_found"))
    }

    /** The browser's explanation state: the no-workspace page renders — WITH the create form
     * for the super admin (canCreate), WITHOUT it for the non-admin. */
    private fun assertNoWorkspacePages() {
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .header("Accept", "text/html")
            .`when`()
            .get("/workspaces")
            .then()
            .statusCode(200)
            .body(Matchers.containsString("Create a workspace"))

        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(NOBODY, "nobody@company.test", null))
            .header("Accept", "text/html")
            .`when`()
            .get("/workspaces")
            .then()
            .statusCode(200)
            .body(Matchers.not(Matchers.containsString("Create a workspace")))
    }

    @Test
    @Order(3)
    fun `WITNESS - the super admin administers the instance with ZERO active workspaces`() {
        ensureSeeded()
        // User administration is an instance verb (§7.6 USER_ADMINISTRATION): RED pre-fix with
        // `404 workspace.not_found` — the null-context branch refused it before any handler ran.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .`when`()
            .get("/api/v1/auth/users")
            .then()
            .statusCode(200)

        // The recovery verb itself: creating a workspace from nothing. RED pre-fix — the exact
        // trap the issue reports.
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .body("""{"name":"recovery-ws","display_name":"Recovery"}""")
            .`when`()
            .post("/api/v1/workspaces")
            .then()
            .statusCode(201)
            .body("data.name", Matchers.equalTo("recovery-ws"))

        // Deactivate the just-created one (the fallback context covers it), leaving zero active
        // again, then REACTIVATE the original from the null context — the second recovery verb.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "recovery-ws"))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .`when`()
            .post("/api/v1/workspaces/recovery-ws/deactivate")
            .then()
            .statusCode(200)

        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "recovery-ws"))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .`when`()
            .post("/api/v1/workspaces/default/reactivate")
            .then()
            .statusCode(200)
            .body("data.active", Matchers.equalTo(true))
    }

    @Test
    @Order(4)
    fun `WITNESS - the browser recovers from the no-workspace page`() {
        ensureSeeded()
        // Re-enter the empty-instance state (test 3 ended with `default` active again).
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .`when`()
            .post("/api/v1/workspaces/default/deactivate")
            .then()
            .statusCode(200)

        // Instance administration a BROWSER navigates to: RED pre-fix, a 302 to the
        // no-workspace page instead of the users screen.
        given()
            .port(port)
            .redirects()
            .follow(false)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .header("Accept", "text/html")
            .`when`()
            .get("/admin/users")
            .then()
            .statusCode(200)

        // The form the no-workspace page renders must WORK: RED pre-fix, when the interceptor
        // bounced the post back to /workspaces without creating anything.
        given()
            .port(port)
            .redirects()
            .follow(false)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "default"))
            .cookie(CSRF_COOKIE, "recovery-csrf")
            .header(CSRF_HEADER, "recovery-csrf")
            .header("Accept", "text/html")
            .formParam("name", "browser-ws")
            .formParam("displayName", "Browser Recovery")
            .`when`()
            .post("/workspaces/create")
            .then()
            .statusCode(302)
            .header("Location", Matchers.endsWith("/workspaces?ok=created"))
    }

    @Test
    @Order(5)
    fun `recovery restores a normal session context`() {
        ensureSeeded()
        // browser-ws is the one active workspace: the D-R8 fallback resolves it and a
        // workspace-scoped read answers normally again.
        given()
            .port(port)
            .cookie(SESSION_COOKIE, sessionJwt(ROOT, "root@company.test", "browser-ws"))
            .`when`()
            .get("/api/v1/pipelines")
            .then()
            .statusCode(200)
    }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val SESSION_COOKIE = "dp_session"
        private const val CSRF_COOKIE = "dp_csrf"
        private const val CSRF_HEADER = "DP-CSRF-Token"
        private const val SECRET_BYTES = 32

        private const val ROOT = "ddd00000-0000-0000-0000-000000000004"
        private const val NOBODY = "eee00000-0000-0000-0000-000000000005"
        private const val WS_DEFAULT = "defa0000-0000-0000-0000-000000000001"

        private val random = SecureRandom()

        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        /** Pinned to `default` — the proof that a key gets no null-context exception. */
        private val ROOT_KEY = E2eAuth.generateKey("root-key", ownerId = ROOT_KEY_IDENTITY)

        /** The key's own `service` identity (keys v2 A13). */
        private const val ROOT_KEY_IDENTITY = "5e500000-0000-0000-0000-000000000170"

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

        private var seeded = false

        /**
         * The empty-instance fixture: E2eClean leaves ONLY the migrated `default` workspace,
         * and this suite adds two users with NO memberships — the super admin reaches
         * `default` through D-R8's implicit-membership fallback alone, and the non-admin
         * reaches nothing.
         */
        fun ensureSeeded() {
            if (seeded) return
            seeded = true

            E2eClean.beforeSeeding()

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ROOT', 'root@company.test', 'Root', 'test', 'root-sub', TRUE, TRUE),
                            ('$NOBODY', 'nobody@company.test', 'Nobody', 'test', 'nobody-sub', TRUE, FALSE)
                        """.trimIndent(),
                    )
                    // The key's own identity (keys v2 A13) — its own statement: the column list above omits `kind`.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$ROOT_KEY_IDENTITY', '${ROOT_KEY.id.lowercase()}@keys.invalid', '${ROOT_KEY.name}', 'key', '${ROOT_KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, ROOT_KEY.id)
                        ps.setObject(2, UUID.fromString(ROOT_KEY_IDENTITY))
                        ps.setObject(3, UUID.fromString(ROOT))
                        ps.setString(4, ROOT_KEY.name)
                        ps.setString(5, ROOT_KEY.hash)
                        ps.setObject(6, UUID.fromString(WS_DEFAULT))
                        ps.executeUpdate()
                    }
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

            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        private val oidc = OidcDiscoveryStub()

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            if (seeded) restoreDefaultWorkspace()
        }

        /**
         * Leaves the instance the way the next suite expects it: `default` active again and the
         * two workspaces this suite created deactivated. SharedE2e's Postgres is per JVM, not
         * per suite, and test 4 ends with `default` deactivated on purpose. In CI's single fork
         * (`dp.test.forks.e2e=1`, alphabetical class order) every later suite that pins an API
         * key to `default` without calling E2eClean answered 404 `auth.key_workspace_inactive`
         * on its first request: run 35287573035 on e6d99e58, TemplateAddressingE2eTest and
         * TemplatesUpdateGoldenPathE2eTest, 12 failures. The dev-box gate's two forks split the
         * classes across JVMs, which is why it never saw it (#139).
         */
        private fun restoreDefaultWorkspace() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "UPDATE workspaces SET deactivated_at = NOW(), deactivated_by = NULL" +
                            " WHERE name IN ('recovery-ws', 'browser-ws') AND deactivated_at IS NULL",
                    )
                    val restored =
                        statement.executeUpdate(
                            "UPDATE workspaces SET deactivated_at = NULL, deactivated_by = NULL WHERE id = '$WS_DEFAULT'",
                        )
                    check(restored == 1) { "could not restore the default workspace (rows=$restored)" }
                }
            }
        }
    }
}
