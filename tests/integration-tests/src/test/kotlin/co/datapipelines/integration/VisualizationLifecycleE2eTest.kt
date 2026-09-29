package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager
import java.util.UUID

/**
 * **The visualization lifecycle over the real HTTP stack (#10 L1b, rest-api §22)** — the parameter-set E2E's shape.
 *
 * A workspace admin creates a visualization (DRAFT, server-assigned id), reads it (the working version with its
 * draft pointer), writes it under `If-Match`, and asks to release it: refused `visualization.release.tests_missing`
 * with `reason: gate_not_installed` — the evidence gate refuses every release until L4 — and, for a body with NO
 * test case, with `reason: no_cases`. A malformed and a MISSING body are the family's own 400 (#291 and the
 * exception handler's row), never the pipeline family's code and never a 500. The flat listing's `total` and
 * `has_more` are truthful (#312), the tree browse separates folders from visualizations, and the human verbs purge
 * a draft (If-Match) and the entity. A VIEWER reads but cannot create (the interceptor's `auth.role_required`).
 *
 * Non-vacuity: every refusal is collected by its code and the multiset is asserted at the end — a suite whose
 * refusals silently stopped happening would fail the count, not pass green.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VisualizationLifecycleE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /** Every refusal the walk met, by code — the non-vacuity count. */
    private val refusals = mutableListOf<String>()

    private lateinit var id: String
    private lateinit var hash: String

    @Test
    @Order(1)
    fun `create lands version 1 DRAFT with a server-assigned id - then reads back as the working version`() {
        seed()
        val created = post("/api/v1/visualizations", document(NAME), ADMIN_SESSION)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        id = created.jsonPath().getString("data.id")
        hash = created.jsonPath().getString("data.body_hash")
        created.jsonPath().getString("data.status") shouldBe "DRAFT"

        val read = get("/api/v1/visualizations/$id", ADMIN_SESSION)
        read.statusCode shouldBe 200
        read.jsonPath().getString("data.name") shouldBe NAME
        read.jsonPath().getString("data.draft.body_hash") shouldBe hash
        read.jsonPath().getString("data.renderer.kind") shouldBe "plotly"
    }

    @Test
    @Order(2)
    fun `an update needs If-Match - then writes the draft in place and moves the hash`() {
        refused(put("/api/v1/visualizations/$id", document(NAME, title = "Revenue v2"), null, ADMIN_SESSION), 400)

        val stale = put("/api/v1/visualizations/$id", document(NAME, title = "Revenue v2"), "not-the-hash", ADMIN_SESSION)
        refused(stale, 409) shouldBe "visualization.version.conflict"

        val written = put("/api/v1/visualizations/$id", document(NAME, title = "Revenue v2"), hash, ADMIN_SESSION)
        withClue(written.asString().take(EXCERPT)) { written.statusCode shouldBe 200 }
        written.jsonPath().getInt("data.version") shouldBe 1
        written.jsonPath().getString("data.presentation.title") shouldBe "Revenue v2"
        val moved = written.jsonPath().getString("data.body_hash")
        moved shouldNotBe hash
        hash = moved
    }

    @Test
    @Order(3)
    fun `release is refused tests_missing until L4 installs the evidence gate - and no_cases for a body without a case`() {
        val gated = post("/api/v1/visualizations/$id/release", "", ADMIN_SESSION, ifMatch = hash)
        refused(gated, 409) shouldBe "visualization.release.tests_missing"
        gated.jsonPath().getString("error.details.reason") shouldBe "gate_not_installed"

        val caseless = post("/api/v1/visualizations", document("$ROOT/charts/caseless", cases = false), ADMIN_SESSION)
        caseless.statusCode shouldBe 201
        val noCases =
            post(
                "/api/v1/visualizations/${caseless.jsonPath().getString("data.id")}/release",
                "",
                ADMIN_SESSION,
                ifMatch = caseless.jsonPath().getString("data.body_hash"),
            )
        refused(noCases, 409) shouldBe "visualization.release.tests_missing"
        noCases.jsonPath().getString("error.details.reason") shouldBe "no_cases"

        // Nothing was released: the working version is still the DRAFT.
        get("/api/v1/visualizations/$id", ADMIN_SESSION).jsonPath().getString("data.status") shouldBe "DRAFT"
    }

    @Test
    @Order(4)
    fun `a malformed and a missing body are the family's 400 - never another family's code, never a 500`() {
        refused(post("/api/v1/visualizations", """{"name": "$ROOT/charts/x", """, ADMIN_SESSION), 400) shouldBe
            "visualization.validation.body_invalid"
        val missing =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .post("/api/v1/visualizations/$id/current")
        refused(missing, 400) shouldBe "visualization.validation.body_invalid"
        refused(post("/api/v1/visualizations/$id/current", """{"version": "1"}""", ADMIN_SESSION), 400) shouldBe
            "visualization.validation.body_invalid"
        // An unknown key is the READER's refusal, naming the path — the strict read the surfaces share.
        val colourful = document("$ROOT/charts/y").replace("\"description\"", "\"colour\":1,\"description\"")
        val unknown = post("/api/v1/visualizations", colourful, ADMIN_SESSION)
        refused(unknown, 400) shouldBe "visualization.validation.body_invalid"
        unknown.jsonPath().getString("error.details.failures[0].details.reason") shouldBe "unknown_key"
    }

    @Test
    @Order(5)
    fun `the flat listing's total and has_more are truthful - and the browse separates folders from visualizations`() {
        val first = get("/api/v1/visualizations?offset=0&limit=1", ADMIN_SESSION).jsonPath()
        withClue("non-vacuity: the page must carry a row before its totals are judged") {
            first.getList<String>("data.items.name").size shouldBe 1
        }
        first.getInt("data.pagination.total") shouldBe 2
        first.getBoolean("data.pagination.has_more") shouldBe true
        get("/api/v1/visualizations?offset=1&limit=1", ADMIN_SESSION).jsonPath().getBoolean("data.pagination.has_more") shouldBe false

        val root = get("/api/v1/visualizations?prefix=", ADMIN_SESSION).jsonPath()
        root.getList<String>("data.folders.segment") shouldContainExactly listOf(ROOT)
        root.getInt("data.total") shouldBe 0
        val level = get("/api/v1/visualizations?prefix=$ROOT/charts", ADMIN_SESSION).jsonPath()
        level.getList<String>("data.visualizations.name") shouldContainExactly listOf("$ROOT/charts/caseless", NAME)
        val versions = get("/api/v1/visualizations/$id/versions", ADMIN_SESSION).jsonPath()
        versions.getList<String>("data.status") shouldContainExactly listOf("DRAFT")
    }

    @Test
    @Order(6)
    fun `a viewer reads but cannot author - the interceptor's role refusal`() {
        get("/api/v1/visualizations/$id", VIEWER_SESSION).statusCode shouldBe 200
        refused(post("/api/v1/visualizations", document("$ROOT/charts/viewer"), VIEWER_SESSION), 403) shouldBe "auth.role_required"
        refused(put("/api/v1/visualizations/$id", document(NAME), hash, VIEWER_SESSION), 403) shouldBe "auth.role_required"
    }

    @Test
    @Order(7)
    fun `the human verbs - purge the draft at If-Match, the lifecycle refusals, the entity purge - and absent is 404`() {
        refused(post("/api/v1/visualizations/$id/versions/1/discard", "", ADMIN_SESSION), 409) shouldBe
            "visualization.version.not_released"
        refused(post("/api/v1/visualizations/$id/versions/1/restore", "", ADMIN_SESSION), 409) shouldBe
            "visualization.version.not_discarded"
        refused(post("/api/v1/visualizations/$id/draft/discard", "", ADMIN_SESSION, ifMatch = "stale"), 409) shouldBe
            "visualization.version.conflict"
        post("/api/v1/visualizations/$id/draft/discard", "", ADMIN_SESSION, ifMatch = hash).statusCode shouldBe 204
        refused(get("/api/v1/visualizations/$id", ADMIN_SESSION), 404) shouldBe "visualization.not_found"

        val caseless = get("/api/v1/visualizations?prefix=$ROOT/charts", ADMIN_SESSION).jsonPath().getString("data.visualizations[0].id")
        delete("/api/v1/visualizations/$caseless", ADMIN_SESSION).statusCode shouldBe 204
        refused(get("/api/v1/visualizations/$caseless", ADMIN_SESSION), 404) shouldBe "visualization.not_found"
        refused(get("/api/v1/visualizations/${UUID.randomUUID()}/versions", ADMIN_SESSION), 404) shouldBe "visualization.not_found"
    }

    @Test
    @Order(8)
    fun `non-vacuity - every refusal the walk exists for happened, counted by code`() {
        println("event=visualization_e2e.refusals total=${refusals.size} ${refusals.groupingBy { it }.eachCount()}")
        refusals.groupingBy { it }.eachCount() shouldBe
            mapOf(
                "pipeline.execution.invalid_parameter_type" to 1,
                "visualization.version.conflict" to 2,
                "visualization.release.tests_missing" to 2,
                "visualization.validation.body_invalid" to 4,
                "auth.role_required" to 2,
                "visualization.version.not_released" to 1,
                "visualization.version.not_discarded" to 1,
                "visualization.not_found" to 3,
            )
    }

    // ------------------------------------------------------------------------------- helpers

    /** Records [response]'s refusal code after asserting its status; returns the code. */
    private fun refused(
        response: Response,
        status: Int,
    ): String {
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe status }
        return response.jsonPath().getString("error.code").also { refusals += it }
    }

    private fun get(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).get(path)

    private fun delete(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).delete(path)

    private fun post(
        path: String,
        body: String,
        session: String,
        ifMatch: String? = null,
    ): Response =
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
        ifMatch: String?,
        session: String,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .put(path)

    /** A visualization with no transform: the renderer binds the single input's columns directly (spec §3.1). */
    private fun document(
        name: String,
        title: String = "Revenue",
        cases: Boolean = true,
    ): String {
        val node = mapper.readTree(DOCUMENT) as ObjectNode
        node.put("name", name)
        (node.get("presentation") as ObjectNode).put("title", title)
        if (!cases) node.remove("tests")
        return mapper.writeValueAsString(node)
    }

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Visualization E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'vz-admin@e2e.test', 'VZ Admin', 'test', 'vz-admin-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'vz-viewer@e2e.test', 'VZ Viewer', 'test', 'vz-viewer-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')
            """.trimIndent(),
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        const val EXCERPT = 600
        const val WORKSPACE = "vz-e2e"
        const val ROOT = "vz"
        const val NAME = "$ROOT/charts/revenue"

        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "presentation": {"title": "Revenue"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "vz-admin@e2e.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "vz-viewer@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
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
        fun tearDown() {
            oidc.close()
        }
    }
}
