package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ServerSocket
import java.sql.DriverManager
import java.util.UUID

/**
 * **The eight dependency tree routes over the real HTTP stack** (#875 G4 §C) — the interceptor chain, the handler and the
 * exception handler as deployed. Precedence, in the order a request meets it: no session → the application's existing
 * unauthenticated answer; a key of ANY kind → 403 `endpoint.key_kind_refused` (the interceptor, before the handler); a
 * malformed selector → 400 `dependencies.request.invalid`, even for a promoter whose lens is down (the parse comes before
 * the lens); a promoter whose promotion target is unreachable → 503 `navigation.view.unavailable` on each of the seven
 * lensed routes, while the datasource route (no lens) answers the same promoter its tree for a granted datasource and
 * the native 404 for an ungranted one. A hidden root — another workspace's pipeline, an ungranted datasource — answers
 * byte for byte (modulo `correlation_id`) what an absent one does; every seeded root answers 200 to every
 * non-lensed role. The unmapped paths (`/api/v1/dependencies/widget`, the bare prefix) get the application's existing
 * answer for an unmapped path, and nothing new handles them. The promoter's hidden-root half is `PromoterLensSweepTest`'s
 * (it needs a reachable target).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class DependenciesRouteE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `without a session every route answers what an existing session route answers`() {
        val existing = given().port(port).accept(JSON).get("/api/v1/pipelines/tree?root=")
        existing.statusCode shouldNotBe HTTP_OK
        DependencyTreeQueries.FAMILIES.forEach { family ->
            val path = DependencyTreeQueries.path(family, absentKey(family))
            val answer = given().port(port).accept(JSON).get(path)
            withClue("GET $path") {
                answer.statusCode shouldBe existing.statusCode
                answer.code() shouldBe existing.code()
            }
        }
    }

    @Test
    fun `a key of any kind is refused by kind at the interceptor on every route`() {
        ensureSeeded()
        KEYS.forEach { (kind, key) ->
            DependencyTreeQueries.FAMILIES.forEach { family ->
                val path = roots().path(family)
                val answer =
                    given()
                        .port(port)
                        .accept(JSON)
                        .header(API_KEY_HEADER, key.plaintext)
                        .get(path)
                withClue("$kind key GET $path: ${answer.excerpt()}") {
                    answer.statusCode shouldBe HTTP_FORBIDDEN
                    answer.code() shouldBe KEY_KIND_REFUSED
                }
            }
        }
    }

    @Test
    fun `a malformed selector is 400 request invalid on every route, before the promoter's lens is asked`() {
        ensureSeeded()
        listOf("author", "promoter").forEach { role ->
            MALFORMED.forEach { (family, query) ->
                val answer = get(session(role), "${DependencyTreeQueries.route(family)}?$query")
                withClue("$role GET $family?$query: ${answer.excerpt()}") {
                    answer.statusCode shouldBe HTTP_BAD_REQUEST
                    answer.code() shouldBe REQUEST_INVALID
                    answer.header("Retry-After") shouldBe null
                }
            }
        }
    }

    @Test
    fun `with the promotion target unreachable a promoter gets 503 on each lensed route and the datasource route still answers`() {
        ensureSeeded()
        val promoter = session("promoter")
        DependencyTreeQueries.LENSED.forEach { family ->
            val answer = get(promoter, roots().path(family))
            withClue("promoter GET $family: ${answer.excerpt()}") {
                answer.statusCode shouldBe HTTP_UNAVAILABLE
                answer.code() shouldBe VIEW_UNAVAILABLE
                answer.header("Retry-After") shouldBe "5"
            }
        }
        withClue("a granted datasource: no lens, so the tree") {
            get(promoter, roots().path("datasource")).statusCode shouldBe HTTP_OK
        }
        withClue("an ungranted datasource: the native 404, exactly as an absent one") {
            val ungranted = get(promoter, DependencyTreeQueries.path("datasource", UNGRANTED_DATASOURCE))
            ungranted.statusCode shouldBe HTTP_NOT_FOUND
            ungranted.code() shouldBe DATASOURCE_NOT_FOUND
            ungranted.envelope() shouldBe get(promoter, DependencyTreeQueries.path("datasource", absentKey("datasource"))).envelope()
        }
    }

    @Test
    fun `a hidden root answers byte for byte what an absent one does`() {
        ensureSeeded()
        val author = session("author")
        listOf(
            DependencyTreeQueries.path("pipeline", FOREIGN_PIPELINE) to DependencyTreeQueries.path("pipeline", absentKey("pipeline")),
            DependencyTreeQueries.path("datasource", UNGRANTED_DATASOURCE) to
                DependencyTreeQueries.path("datasource", absentKey("datasource")),
        ).forEach { (hidden, absent) ->
            val a = get(author, hidden)
            val b = get(author, absent)
            println("event=dependencies.hidden_vs_absent hidden=$hidden status=${a.statusCode} body=${a.envelope()}")
            withClue("GET $hidden vs GET $absent") {
                a.statusCode shouldBe HTTP_NOT_FOUND
                a.envelope() shouldBe b.envelope()
                a.body().asString().contains(hidden.substringAfter("=").substringBefore("&")) shouldBe false
            }
        }
    }

    @Test
    fun `every seeded root answers its tree to every role the lens does not decide`() {
        ensureSeeded()
        listOf("viewer", "author", "workspace_admin").forEach { role ->
            DependencyTreeQueries.FAMILIES.forEach { family ->
                val answer = get(session(role), roots().path(family))
                withClue("$role GET ${roots().path(family)}: ${answer.excerpt()}") {
                    answer.statusCode shouldBe HTTP_OK
                    answer.header("Cache-Control") shouldBe "no-store"
                    answer.jsonPath().getBoolean("data.complete") shouldBe true
                    answer.jsonPath().getString("data.nodes[0].family") shouldBe family
                }
            }
        }
    }

    @Test
    fun `an unknown segment and the bare prefix are unmapped paths with the application's existing answer`() {
        ensureSeeded()
        val author = session("author")
        val unmapped = get(author, "/api/v1/no-such-route-875")
        listOf("/api/v1/dependencies/widget?name=acme/x&version=1", "/api/v1/dependencies/test_widget?name=x", "/api/v1/dependencies")
            .forEach { path ->
                val answer = get(author, path)
                println("event=dependencies.unmapped path=$path status=${answer.statusCode} code=${answer.code()}")
                withClue("GET $path: ${answer.excerpt()}") {
                    answer.statusCode shouldBe unmapped.statusCode
                    answer.code() shouldBe unmapped.code()
                }
            }
    }

    // ------------------------------------------------------------------------------- helpers

    private fun session(role: String): String =
        E2eSession.jwt(JWT_SECRET, (USERS + ("super_admin" to SUPER_ADMIN)).getValue(role), "$role@deproute.test", WORKSPACE)

    private fun get(
        session: String,
        path: String,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .accept(JSON)
            .get(path)

    private fun Response.code(): String? = runCatching { jsonPath().getString("error.code") }.getOrNull()

    private fun Response.excerpt(): String = "$statusCode ${body().asString().take(EXCERPT)}"

    /** The §4.2 envelope without its `correlation_id` — the one field two identical answers may differ in. */
    private fun Response.envelope(): JsonNode = MAPPER.readTree(body().asString()).also { (it as ObjectNode).remove("correlation_id") }

    private fun roots(): DependencyTreeQueries.Roots =
        seededRoots ?: DependencyTreeQueries
            .seed(port, session("workspace_admin"), WORKSPACE_ID, PREFIX, GRANTED_DATASOURCE, registrar = session("super_admin"))
            .also { seededRoots = it }

    private fun absentKey(family: String): String =
        when (family) {
            "schedule" -> "0000875b-0000-4000-8000-000000000000"
            "datasource" -> "tree875_nowhere"
            "template" -> "test/tree875/nowhere.sql"
            "api_endpoint" -> "/api/test_tree875_nowhere/v1/tree"
            else -> "test/tree875/nowhere"
        }

    private companion object {
        private const val JSON = "application/json"
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val KEY_KIND_REFUSED = "endpoint.key_kind_refused"
        private const val REQUEST_INVALID = "dependencies.request.invalid"
        private const val VIEW_UNAVAILABLE = "navigation.view.unavailable"
        private const val DATASOURCE_NOT_FOUND = "datasource.not_found"
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAVAILABLE = 503
        private const val EXCERPT = 400
        private val MAPPER = ObjectMapper()

        private const val WORKSPACE = "deproute-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val FOREIGN_WORKSPACE_ID = UUID.randomUUID().toString()
        private const val PREFIX = "test/tree875/route"
        private const val GRANTED_DATASOURCE = "tree875_route_granted"
        private const val UNGRANTED_DATASOURCE = "tree875_route_foreign"
        private const val FOREIGN_PIPELINE = "test/tree875/foreign_only"
        private var seededRoots: DependencyTreeQueries.Roots? = null

        /** One malformed request per family route — each refused by the parse, whatever the root. */
        private val MALFORMED: Map<String, String> =
            DependencyTreeQueries.FAMILIES.associateWith { family ->
                when (family) {
                    "schedule" -> "id=NOT-A-UUID"
                    "datasource" -> "name=Upper_Case"
                    else -> "name=test/tree875/x&version=0"
                }
            }

        private val USERS: Map<String, String> =
            listOf("viewer", "author", "promoter", "workspace_admin").associateWith { UUID.randomUUID().toString() }

        /** The instance's super admin — no membership (D7); registers the granted datasource through REST. */
        private val SUPER_ADMIN = UUID.randomUUID().toString()

        /** One key of each kind (keys v2 A13: each acts as its own `service` identity) and the role it holds. */
        private val KEYS: Map<String, E2eAuth.SeededKey> =
            listOf("mcp", "endpoint", "dashboard", "server").associateWith { kind ->
                E2eAuth.generateKey("deproute-$kind", ownerId = UUID.randomUUID().toString())
            }
        private val KEY_ROLES =
            mapOf("mcp" to "author", "endpoint" to "api_caller", "dashboard" to "dashboard_viewer", "server" to "promotion_receiver")

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()

        /** A port nothing listens on: the promotion target is configured and unreachable. */
        private val CLOSED_PORT: Int = ServerSocket(0).use { it.localPort }

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        private var seeded = false

        /** Two workspaces (ours and a foreign one), our four members, a super admin, the keys, a foreign pipeline and datasource. */
        private fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { s ->
                    s.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Dependency routes'), " +
                            "('$FOREIGN_WORKSPACE_ID', 'deproute-foreign', 'Foreign')",
                    )
                    USERS.forEach { (role, id) ->
                        s.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                "('$id', '$role@deproute.test', '$role', 'test', 'deproute-$role', TRUE, FALSE)",
                        )
                        s.execute(
                            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$id', '$role')",
                        )
                    }
                    s.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$SUPER_ADMIN', 'super_admin@deproute.test', 'super admin', 'test', 'deproute-super', TRUE, TRUE)",
                    )
                    val owner = USERS.getValue("workspace_admin")
                    s.execute(
                        "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES " +
                            "('${UUID.randomUUID()}', '$FOREIGN_PIPELINE', 'Foreign', '', '$owner', '$FOREIGN_WORKSPACE_ID', NULL)",
                    )
                    // The ungranted one is never read, only named: SQL with a stand-in credential is enough for it.
                    mapOf(UNGRANTED_DATASOURCE to FOREIGN_WORKSPACE_ID).forEach { (name, ws) ->
                        s.execute(
                            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, credential_encrypted, " +
                                "created_by, owner_workspace_id) VALUES ('$name', '$name', 'H2', 'jdbc:h2:mem:$name', 'sa', " +
                                "'x'::bytea, '$owner', '$ws')",
                        )
                        s.execute(
                            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) " +
                                "VALUES ('$name', '$ws', '$owner')",
                        )
                    }
                }
                seedKeys(connection)
            }
        }

        /** One key of each kind, each its own `service` identity (keys v2 A13), created by the workspace admin. */
        private fun seedKeys(connection: java.sql.Connection) {
            connection.createStatement().use { s ->
                KEYS.values.forEach { key ->
                    s.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('${key.ownerId}', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}', " +
                            "TRUE, FALSE, 'service')",
                    )
                }
            }
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    KEYS.forEach { (kind, key) ->
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(key.ownerId))
                        ps.setObject(3, UUID.fromString(USERS.getValue("workspace_admin")))
                        ps.setString(4, key.name)
                        ps.setString(5, key.hash)
                        ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                        ps.setString(7, kind)
                        ps.setString(8, KEY_ROLES.getValue(kind))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // The promoter's lens has a target, and nothing answers there.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:$CLOSED_PORT" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "deproute-" + UUID.randomUUID() }
            // API mode: no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = oidc.close()
    }
}
