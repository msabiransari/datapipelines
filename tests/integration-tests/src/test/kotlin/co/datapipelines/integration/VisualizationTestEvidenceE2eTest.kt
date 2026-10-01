package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * **The L4a release gate over the REAL wired application (#352, #10 L4a)** — the L1b E2E's shape.
 *
 * The context here is the whole production graph: `VisualizationTestConfiguration`'s beans — the runs
 * repository, the fixture evaluator, the mechanical check, the session service and the REAL
 * `ReleaseEvidence` inside the visualization service — are component-scanned and booted against the real
 * database with Flyway's V44 applied. This suite asserts, on the RUNNING app:
 *
 * 1. the L4a beans EXIST and the wired `ReleaseEvidence` is the real gate (reflective checks: this
 *    module's allowed edge is `:modules:app` only, module-structure §5.11 — the types are runtime facts,
 *    not compile-time imports);
 * 2. a release over REST with no test run is refused `visualization.release.tests_missing` with the
 *    gate's own `no_runs` reason — the factory's evidence argument is load-bearing on the wire;
 * 3. an edited draft with no run of its own is refused again, and nothing partial lands.
 *
 * The session/capability lifecycle itself is proven at the module level (real database, real
 * transactions, forced races); #353 binds its HTTP transport and proves the token's wire confinement —
 * neither is claimable here.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VisualizationTestEvidenceE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val refusals = mutableListOf<String>()

    private lateinit var id: String
    private lateinit var hash: String

    @Test
    @Order(1)
    fun `the L4a beans exist on the running app and the wired evidence is the real gate`() {
        seed()
        val sessionService = Class.forName("co.datapipelines.visualization.VisualizationTestSessionService")
        val gate = Class.forName("co.datapipelines.visualization.VisualizationReleaseEvidence")
        context.getBeanNamesForType(sessionService).size shouldBe 1
        val evidenceBeans = context.getBeansOfType(gate)
        evidenceBeans.size shouldBe 1
        withClue("the ReleaseEvidence bean IS the real gate, not a fake") { evidenceBeans.keys.single() shouldBe "visualizationReleaseEvidence" }
    }

    @Test
    @Order(2)
    fun `a release before any session is refused tests_missing with the gate's own reason`() {
        val created = post("/api/v1/visualizations", DOCUMENT, ADMIN_SESSION)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        id = created.jsonPath().getString("data.id")
        hash = created.jsonPath().getString("data.body_hash")

        val gated = post("/api/v1/visualizations/$id/release", "", ADMIN_SESSION, ifMatch = hash)
        refused(gated, 409) shouldBe "visualization.release.tests_missing"
        gated.jsonPath().getString("error.details.reason") shouldBe "no_runs"
        // Nothing partial: the draft is intact.
        get("/api/v1/visualizations/$id", ADMIN_SESSION).jsonPath().getString("data.draft.body_hash") shouldBe hash
    }

    @Test
    @Order(3)
    fun `a stale release after an edit is refused and nothing partial lands`() {
        val node = mapper.readTree(DOCUMENT) as com.fasterxml.jackson.databind.node.ObjectNode
        (node.get("presentation") as com.fasterxml.jackson.databind.node.ObjectNode).put("title", "Evidence v2")
        val written = put("/api/v1/visualizations/$id", mapper.writeValueAsString(node), hash, ADMIN_SESSION)
        withClue(written.asString().take(EXCERPT)) { written.statusCode shouldBe 200 }
        val newHash = written.jsonPath().getString("data.body_hash")

        val gated = post("/api/v1/visualizations/$id/release", "", ADMIN_SESSION, ifMatch = newHash)
        // No run serves the new content — the sessions are #353's transport — so the gate's answer here
        // is tests_missing for the edited draft too (the stale refusal is proven on the real sessions at
        // the module level: VisualizationReleaseEvidenceIntegrationTest).
        refused(gated, 409) shouldBe "visualization.release.tests_missing"
        get("/api/v1/visualizations/$id", ADMIN_SESSION).jsonPath().getString("data.draft.body_hash") shouldBe newHash

        // Non-vacuity: the refusal the walk met, once per release attempt.
        refusals.count { it == "visualization.release.tests_missing" } shouldBe 2
    }

    // ---- HTTP helpers (the L1b E2E's shape) ------------------------------------------------------------

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    private fun refused(
        response: io.restassured.response.Response,
        status: Int,
    ): String {
        response.statusCode shouldBe status
        val code = response.jsonPath().getString("error.code")
        refusals += code
        return code
    }

    private fun post(
        path: String,
        body: String,
        session: String,
        ifMatch: String? = null,
    ): io.restassured.response.Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .post(path)

    private fun put(
        path: String,
        body: String,
        ifMatch: String,
        session: String,
    ): io.restassured.response.Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .header("If-Match", ifMatch)
            .body(body)
            .put(path)

    private fun get(
        path: String,
        session: String,
    ): io.restassured.response.Response =
        given().port(port).asSession(session).contentType(ContentType.JSON).get(path)

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Test evidence E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'te-admin@e2e.test', 'TE Admin', 'test', 'te-admin-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')")
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        const val EXCERPT = 600
        const val WORKSPACE = "te-e2e"
        const val NAME = "te/charts/evidence"

        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Evidence", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "presentation": {"title": "Evidence"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        val WORKSPACE_ID = UUID.randomUUID().toString()
        val ADMIN_ID = UUID.randomUUID().toString()

        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "te-admin@e2e.test", WORKSPACE)

        val postgres get() = SharedE2e.postgres
        val redis get() = SharedE2e.redis
        val oidc = OidcDiscoveryStub()

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
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // API mode: this suite only authors — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun closeOidc() {
            oidc.close()
        }
    }
}
