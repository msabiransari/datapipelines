package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The 113 owner scenario end to end (auth.md §4.6): a workspace admin invites
 * `bob@datapipelines.co` BEFORE Bob has ever signed in, then Bob's FIRST login — a real
 * OIDC code flow through a real Keycloak realm — lands him in the invited workspace with
 * the invited flags, and NOT in `demo`:
 *
 *  - the invite is a `workspace_invitations` row, and no `workspace_members` row exists;
 *  - the first login materialises it: member with the invited flags, `active_workspace`
 *    stamped to the invited workspace, the invitation row GONE, no `demo` membership (D-R11);
 *  - the three audit events are present: invited (at invite), materialised (at login), and
 *    the login itself.
 *
 * The INVITE half goes through the real [WorkspaceService] (the REST mapping of the same
 * verbs is pinned in `:modules:web` and `tests/integration-tests`); the LOGIN half is the
 * full browser flow — discovery, authorization-code exchange, JWKS, callback — exactly as
 * [OidcLoginIntegrationTest] drives it, which is the harness this suite reuses (its own
 * realm, with a second user, so a reused container's persisted realm cannot leak users
 * between the suites).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class WorkspaceInvitationOidcE2eTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var jwtService: JwtService

    @Autowired private lateinit var workspaceService: WorkspaceService

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate

    private val aliceJar = mutableMapOf<String, String>()
    private val bobJar = mutableMapOf<String, String>()
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    @Test
    fun `an invitation materialises at the invitee's first SSO login with the invited flags and no demo`() {
        // --- The admin half: alice (bootstrap admin) creates a workspace and INVITES bob.
        aliceLogin()
        val alice = alicePrincipal()
        workspaceService.create(alice, "invited-co", "Invited Co")
        val outcome =
            workspaceService.addMember(alice, "invited-co", "Bob@Datapipelines.CO", MembershipFlags(author = true))
        (outcome as WorkspaceService.AddMemberOutcome.Invited).email shouldBe "bob@datapipelines.co"

        // The invite is a row keyed by the NORMALIZED email; bob has NO membership and NO
        // users row yet — nothing pretends a person exists before they do.
        invitations() shouldContainExactlyInAnyOrder listOf("bob@datapipelines.co")
        bobMembershipCount() shouldBe 0
        bobUserRow() shouldBe null

        // --- The invitee half: bob's FIRST login, the real code flow.
        bobLogin()

        val bob = bobUserRow().shouldNotBeNull()
        bob["provider"] shouldBe "invites-keycloak"

        // Member with the invited flags; NOT a viewer of demo (D-R11: the invited workspace
        // REPLACES the default — the demo join fires only for a user with no membership).
        bobMembershipCount() shouldBe 1
        val membership = bobMembership().shouldNotBeNull()
        membership["workspace"] shouldBe "invited-co"
        membership["author"] shouldBe true
        membership["admin"] shouldBe false

        // The invitation row is gone — materialised, not copied.
        invitations() shouldBe emptyList()

        // The session JWT stamps the INVITED workspace, not demo.
        val session = bobJar["dp_session"].shouldNotBeNull()
        jwtService.validate(session)["active_workspace"] shouldBe "invited-co"

        // The three audit events, in the §10 vocabulary.
        events() shouldContain "workspace.member_invited"
        events() shouldContain "workspace.invitation_materialised"
        events() shouldContain "auth.login.success"
    }

    // --- The admin half, through the real service with the bootstrap principal.

    private fun alicePrincipal(): AuthenticatedPrincipal {
        val aliceId = aliceUserRow().shouldNotBeNull()["id"] as java.util.UUID
        return AuthenticatedPrincipal(
            userId = aliceId,
            email = "alice@datapipelines.co",
            displayName = "Alice",
            scopes = emptySet(),
            authMethod = AuthMethod.OIDC,
            superAdmin = true,
        )
    }

    private fun aliceLogin() {
        val jar = runFlow("alice", "alice-password", aliceJar)
        jar["dp_session"].shouldNotBeNull()
    }

    private fun bobLogin() {
        runFlow("bob", "bob-password", bobJar)
    }

    private fun aliceUserRow() = userRow("alice@datapipelines.co")

    private fun bobUserRow() = userRow("bob@datapipelines.co")

    private fun userRow(email: String): Map<String, Any?>? =
        jdbc.jdbcTemplate
            .queryForList("SELECT id, email, provider FROM users WHERE email = '$email'")
            .firstOrNull()

    private fun invitations(): List<String> = jdbc.jdbcTemplate.queryForList("SELECT email FROM workspace_invitations", String::class.java)

    private fun bobMembershipCount(): Int =
        jdbc
            .jdbcTemplate
            .queryForObject(
                "SELECT COUNT(*) FROM workspace_members m JOIN users u ON u.id = m.user_id WHERE u.email = 'bob@datapipelines.co'",
                Int::class.java,
            ) ?: 0

    private fun bobMembership(): Map<String, Any?>? =
        jdbc.jdbcTemplate
            .queryForList(
                "SELECT w.name AS workspace, m.author, m.promoter, m.admin FROM workspace_members m" +
                    " JOIN users u ON u.id = m.user_id JOIN workspaces w ON w.id = m.workspace_id" +
                    " WHERE u.email = 'bob@datapipelines.co'",
            ).firstOrNull()

    private fun events(): List<String> = jdbc.jdbcTemplate.queryForList("SELECT event FROM audit_log", String::class.java)

    // --- The OIDC code-flow driver (the OidcLoginIntegrationTest harness, reused).

    private fun runFlow(
        username: String,
        password: String,
        jar: MutableMap<String, String>,
    ): MutableMap<String, String> {
        val base = "http://localhost:$port"
        val start = send("GET", "$base/oauth2/authorization/invites-keycloak", jar)
        start.statusCode() shouldBe 302
        val loginPage = send("GET", location(start), jar)
        loginPage.statusCode() shouldBe 200
        val formAction = extractFormAction(loginPage.body())
        val afterLogin = send("POST", formAction, jar, body = "username=$username&password=$password&credentialId=")
        afterLogin.statusCode() shouldBe 302
        followUntilSession(location(afterLogin), jar)
        return jar
    }

    private fun followUntilSession(
        firstLocation: String,
        jar: MutableMap<String, String>,
    ) {
        var next: String? = firstLocation
        var hops = 0
        while (next != null && hops < MAX_HOPS) {
            val resp = send("GET", next, jar)
            if (jar.containsKey("dp_session")) return
            next = if (resp.statusCode() in 300..399) location(resp) else null
            hops++
        }
        throw AssertionError("The OIDC callback chain never set dp_session after $hops hop(s)")
    }

    private fun send(
        method: String,
        url: String,
        jar: MutableMap<String, String>,
        body: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
        if (jar.isNotEmpty()) builder.header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        when (method) {
            "POST" -> {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                builder.POST(HttpRequest.BodyPublishers.ofString(body ?: ""))
            }

            else -> {
                builder.GET()
            }
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        response.headers().allValues("Set-Cookie").forEach { setCookie ->
            val pair = setCookie.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "")
            val expired = Regex("(?i)max-age=0|expires=Thu, 01 Jan 1970").containsMatchIn(setCookie)
            if (name.isNotEmpty()) {
                if (expired) jar.remove(name) else jar[name] = value
            }
        }
        return response
    }

    private fun location(response: HttpResponse<String>): String =
        response.headers().firstValue("Location").orElseThrow { AssertionError("no Location on ${response.statusCode()}") }

    private fun extractFormAction(html: String): String {
        val match =
            Regex("""<form[^>]*\baction="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
                ?: error("no login form action in Keycloak page")
        return match.groupValues[1].replace("&amp;", "&")
    }

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(2)
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(20)
        const val MAX_HOPS = 10
        const val KEYCLOAK_PORT = 8080
        const val SECRET_BYTES = 32

        /** A pre-reserved local port, so `datapipelines.auth.base-url` names the exact origin (§5.2). */
        @JvmStatic
        val serverPort: Int = java.net.ServerSocket(0).use { it.localPort }

        val postgres get() = SharedPostgres.postgres

        @JvmStatic
        val keycloak: GenericContainer<*> =
            GenericContainer("quay.io/keycloak/keycloak:26.0")
                .withExposedPorts(KEYCLOAK_PORT)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/realm-invites.json"),
                    "/opt/keycloak/data/import/realm-invites.json",
                ).withCommand("start-dev", "--import-realm")
                .withReuse(true)
                .waitingFor(
                    Wait
                        .forHttp("/realms/invites/.well-known/openid-configuration")
                        .forPort(KEYCLOAK_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(20)),
                )

        init {
            keycloak.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            registry.add("server.port") { serverPort }
            registry.add("datapipelines.jwt.secret") { Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES) { 9 }) }
            registry.add("datapipelines.auth.base-url") { "http://localhost:$serverPort" }
            registry.add("datapipelines.auth.bootstrap-admin-email") { "Alice@Datapipelines.CO" }
            registry.add("datapipelines.auth.allowlist.domains") { "datapipelines.co" }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "invites-keycloak" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "dp-client" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "dp-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") {
                "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_PORT)}/realms/invites"
            }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Company SSO" }
        }
    }
}
