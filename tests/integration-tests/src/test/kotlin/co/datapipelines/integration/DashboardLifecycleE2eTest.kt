package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import java.net.InetSocketAddress
import java.sql.DriverManager
import java.util.UUID

/**
 * **The dashboard lifecycle and the promoter lens over the real HTTP stack (#10 L1b, rest-api §23).**
 *
 * The authoring walk: a workspace admin creates a visualization (DRAFT) and a dashboard that pins it and sources a
 * released, read-only pipeline; `validate` answers `valid: true` against the dependencies' current state; the
 * release is refused `dashboard.release.dependency_not_released` while the visualization is a DRAFT, and — with
 * `release_pinned_visualizations=true` — by the VISUALIZATION's own gate (`visualization.release.tests_missing`);
 * the pinned visualization cannot be purged (`visualization.version.pinned`); the draft is written under `If-Match`;
 * the version verbs refuse a draft (`not_released`, `not_discarded`); purging the sole draft version takes the
 * dashboard, and the entity purge takes another.
 *
 * The promoter walk (D50; the orchestrator's rule): the higher environment is a STUB holding the `hidden` pipeline at
 * its hash, so the promoter's pipeline lens admits only `visible`. Two dashboards are RELEASED (seeded — no release
 * path exists before L4): `shown` sources the visible pipeline, `hidden` the hidden one. The promoter sees `shown`
 * and the visualization it pins; `hidden` and ITS visualization answer exactly as an absent id (404), and the flat
 * listing's total is her lens's size. `validate` is an AUTHOR verb (owner ruling 2026-09-29, O5): the promoter and
 * the viewer are refused `auth.role_required` before the handler.
 *
 * Non-vacuity: every refusal is collected by code and the multiset asserted at the end.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardLifecycleE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val refusals = mutableListOf<String>()

    private lateinit var visualizationId: String
    private lateinit var dashboardId: String
    private lateinit var dashboardHash: String

    @Test
    @Order(1)
    fun `fixture - the workspace, two released pipelines, two released dashboards and their visualizations`() {
        seed()
    }

    @Test
    @Order(2)
    fun `create a visualization and a dashboard that pins it - and validate answers valid against current state`() {
        val visualization = post("/api/v1/visualizations", VISUALIZATION.replace("__NAME__", "$ROOT/charts/revenue"), ADMIN_SESSION)
        withClue(visualization.asString().take(EXCERPT)) { visualization.statusCode shouldBe 201 }
        visualizationId = visualization.jsonPath().getString("data.id")

        val dashboard = post("/api/v1/dashboards", revenueBoard(), ADMIN_SESSION)
        withClue(dashboard.asString().take(EXCERPT)) { dashboard.statusCode shouldBe 201 }
        dashboardId = dashboard.jsonPath().getString("data.id")
        dashboardHash = dashboard.jsonPath().getString("data.body_hash")
        dashboard.jsonPath().getString("data.status") shouldBe "DRAFT"

        val verdict = post("/api/v1/dashboards/$dashboardId/validate", "", ADMIN_SESSION)
        withClue(verdict.asString().take(EXCERPT)) { verdict.statusCode shouldBe 200 }
        verdict.jsonPath().getBoolean("data.valid") shouldBe true
        verdict.jsonPath().getList<Any>("data.failures") shouldBe emptyList()
        verdict.jsonPath().getString("data.body_hash") shouldBe dashboardHash
    }

    @Test
    @Order(3)
    fun `release is refused while the pinned visualization is a DRAFT - and the consented cascade runs the visualization's gate`() {
        val refusedRelease = post("/api/v1/dashboards/$dashboardId/release", "", ADMIN_SESSION, ifMatch = dashboardHash)
        refused(refusedRelease, 409) shouldBe "dashboard.release.dependency_not_released"

        val consented = "/api/v1/dashboards/$dashboardId/release?release_pinned_visualizations=true"
        val cascaded = post(consented, "", ADMIN_SESSION, ifMatch = dashboardHash)
        refused(cascaded, 409) shouldBe "visualization.release.tests_missing"

        get("/api/v1/dashboards/$dashboardId", ADMIN_SESSION).jsonPath().getString("data.status") shouldBe "DRAFT"
        get("/api/v1/visualizations/$visualizationId", ADMIN_SESSION).jsonPath().getString("data.status") shouldBe "DRAFT"
    }

    @Test
    @Order(4)
    fun `the pinned visualization cannot be purged - the pin guard names the dashboard`() {
        val pinned = delete("/api/v1/visualizations/$visualizationId", ADMIN_SESSION)
        refused(pinned, 409) shouldBe "visualization.version.pinned"
        pinned.jsonPath().getList<String>("error.details.pinned_by") shouldContainExactly listOf("$ROOT/boards/revenue@1")
    }

    @Test
    @Order(5)
    fun `the draft write needs If-Match, then moves the hash - and the version verbs refuse a draft`() {
        refused(put("/api/v1/dashboards/$dashboardId", revenueBoard(), null), 400)
        val written = put("/api/v1/dashboards/$dashboardId", revenueBoard("Rev 2"), dashboardHash)
        withClue(written.asString().take(EXCERPT)) { written.statusCode shouldBe 200 }
        written.jsonPath().getString("data.display_name") shouldBe "Rev 2"
        dashboardHash = written.jsonPath().getString("data.body_hash")

        refused(post("/api/v1/dashboards/$dashboardId/versions/1/discard", "", ADMIN_SESSION), 409) shouldBe
            "dashboard.version.not_released"
        refused(post("/api/v1/dashboards/$dashboardId/versions/1/restore", "", ADMIN_SESSION), 409) shouldBe
            "dashboard.version.not_discarded"
        refused(post("/api/v1/dashboards/$dashboardId/current", "{}", ADMIN_SESSION), 400) shouldBe "dashboard.validation.body_invalid"
        refused(post("/api/v1/dashboards", "[", ADMIN_SESSION), 400) shouldBe "dashboard.validation.body_invalid"
    }

    @Test
    @Order(6)
    fun `the promoter sees the dashboard her pipelines admit - the hidden one and its visualization are absent`() {
        val listing = get("/api/v1/dashboards", PROMOTER_SESSION).jsonPath()
        withClue("non-vacuity: the promoter's page must carry her visible dashboard before the total is judged") {
            listing.getList<String>("data.items.name") shouldContainExactly listOf("$ROOT/boards/shown")
        }
        listing.getInt("data.pagination.total") shouldBe 1
        get("/api/v1/dashboards/$SHOWN_DASHBOARD", PROMOTER_SESSION).statusCode shouldBe 200
        get("/api/v1/visualizations/$SHOWN_VISUALIZATION", PROMOTER_SESSION).statusCode shouldBe 200

        val hidden = get("/api/v1/dashboards/$HIDDEN_DASHBOARD", PROMOTER_SESSION)
        val absent = get("/api/v1/dashboards/${UUID.randomUUID()}", PROMOTER_SESSION)
        refused(hidden, 404) shouldBe "dashboard.not_found"
        refused(absent, 404) shouldBe "dashboard.not_found"
        withClue("a hidden dashboard answers exactly as an absent one - nothing but the id differs") {
            hidden.asString().contains("$ROOT/boards/hidden") shouldBe false
        }
        refused(get("/api/v1/visualizations/$HIDDEN_VISUALIZATION", PROMOTER_SESSION), 404) shouldBe "visualization.not_found"
        get("/api/v1/visualizations", PROMOTER_SESSION).jsonPath().getList<String>("data.items.name") shouldContainExactly
            listOf("$ROOT/charts/shown")
        // A draft dashboard is never in a promoter's view — the authoring walk's `revenue` is absent too.
        refused(get("/api/v1/dashboards/$dashboardId", PROMOTER_SESSION), 404) shouldBe "dashboard.not_found"
    }

    @Test
    @Order(7)
    fun `validate is an author verb - the promoter and the viewer are refused before the handler (O5)`() {
        refused(post("/api/v1/dashboards/$SHOWN_DASHBOARD/validate", "", PROMOTER_SESSION), 403) shouldBe "auth.role_required"
        refused(post("/api/v1/dashboards/$SHOWN_DASHBOARD/validate", "", VIEWER_SESSION), 403) shouldBe "auth.role_required"
        // The viewer READS every dashboard — the whole view, not the lens.
        get("/api/v1/dashboards?limit=1", VIEWER_SESSION).jsonPath().getBoolean("data.pagination.has_more") shouldBe true
    }

    @Test
    @Order(8)
    fun `the whole view's flat listing is truthful - total and has_more over every dashboard`() {
        val first = get("/api/v1/dashboards?offset=0&limit=2", ADMIN_SESSION).jsonPath()
        first.getList<String>("data.items.name").size shouldBe 2
        first.getInt("data.pagination.total") shouldBe 3
        first.getBoolean("data.pagination.has_more") shouldBe true
        get("/api/v1/dashboards?offset=2&limit=2", ADMIN_SESSION).jsonPath().getBoolean("data.pagination.has_more") shouldBe false
        get("/api/v1/dashboards?prefix=$ROOT/boards", ADMIN_SESSION).jsonPath().getInt("data.total") shouldBe 3
    }

    @Test
    @Order(9)
    fun `purging the sole draft version takes the dashboard - and the entity purge takes another`() {
        delete("/api/v1/dashboards/$dashboardId/versions/1", ADMIN_SESSION).statusCode shouldBe 204
        refused(get("/api/v1/dashboards/$dashboardId", ADMIN_SESSION), 404) shouldBe "dashboard.not_found"

        // The pin is gone with it: the visualization's entity purge now succeeds.
        delete("/api/v1/visualizations/$visualizationId", ADMIN_SESSION).statusCode shouldBe 204

        val spare = post("/api/v1/dashboards", dashboard("$ROOT/boards/spare", "$ROOT/charts/shown", VISIBLE_PIPELINE), ADMIN_SESSION)
        withClue(spare.asString().take(EXCERPT)) { spare.statusCode shouldBe 201 }
        val spareId = spare.jsonPath().getString("data.id")
        delete("/api/v1/dashboards/$spareId", ADMIN_SESSION).statusCode shouldBe 204
        refused(get("/api/v1/dashboards/$spareId", ADMIN_SESSION), 404) shouldBe "dashboard.not_found"
    }

    @Test
    @Order(10)
    fun `non-vacuity - every refusal the walk exists for happened, counted by code`() {
        println("event=dashboard_e2e.refusals total=${refusals.size} ${refusals.groupingBy { it }.eachCount()}")
        refusals.groupingBy { it }.eachCount() shouldBe
            mapOf(
                "dashboard.release.dependency_not_released" to 1,
                "visualization.release.tests_missing" to 1,
                "visualization.version.pinned" to 1,
                "pipeline.execution.invalid_parameter_type" to 1,
                "dashboard.version.not_released" to 1,
                "dashboard.version.not_discarded" to 1,
                "dashboard.validation.body_invalid" to 2,
                "dashboard.not_found" to 5,
                "visualization.not_found" to 1,
                "auth.role_required" to 2,
            )
    }

    // ------------------------------------------------------------------------------- helpers

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
    ): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .put(path)

    /** The authoring walk's dashboard: `revenue`, sourcing the visible pipeline and pinning the DRAFT visualization. */
    private fun revenueBoard(displayName: String = "Revenue"): String =
        dashboard("$ROOT/boards/revenue", "$ROOT/charts/revenue", VISIBLE_PIPELINE, displayName)

    /** A dashboard that sources [pipeline] v1 and places one occurrence of [visualization] v1 on the grid. */
    private fun dashboard(
        name: String,
        visualization: String,
        pipeline: String,
        displayName: String = "Revenue",
    ): String {
        val node = mapper.readTree(DASHBOARD) as ObjectNode
        node.put("name", name).put("display_name", displayName)
        ((node.get("sources").get(0) as ObjectNode).get("pipeline") as ObjectNode).put("name", pipeline)
        ((node.get("visualizations").get(0) as ObjectNode).get("visualization") as ObjectNode).put("name", visualization)
        return mapper.writeValueAsString(node)
    }

    private fun seed() {
        val admin = ADMIN_ID
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Dashboard E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'db-admin@e2e.test', 'DB Admin', 'test', 'db-admin-sub', TRUE, FALSE),
                ('$PROMOTER_ID', 'db-promoter@e2e.test', 'DB Promoter', 'test', 'db-promoter-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'db-viewer@e2e.test', 'DB Viewer', 'test', 'db-viewer-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')
            """.trimIndent(),
        )
        // Two released, read-only pipelines (one DQL caller node over tempdb). The stub target holds `hidden` at its
        // hash, so only `visible` is newer — the promoter's pipeline lens.
        sql(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                ('$VISIBLE_PIPELINE_ID', '$VISIBLE_PIPELINE', 'Visible', '', '$admin', '$WORKSPACE_ID', 1),
                ('$HIDDEN_PIPELINE_ID', '$HIDDEN_PIPELINE', 'Hidden', '', '$admin', '$WORKSPACE_ID', 1)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO pipeline_versions
                (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                ('$VISIBLE_PIPELINE_ID', 1, '${pipelineBody(VISIBLE_PIPELINE)}'::jsonb, 'hash-visible', 'RELEASED',
                 '$admin', '$admin', NOW()),
                ('$HIDDEN_PIPELINE_ID', 1, '${pipelineBody(HIDDEN_PIPELINE)}'::jsonb, '$HIDDEN_HASH', 'RELEASED',
                 '$admin', '$admin', NOW())
            """.trimIndent(),
        )
        // The released visualizations and dashboards — seeded because nothing can release a visualization before L4.
        listOf(SHOWN_VISUALIZATION to "$ROOT/charts/shown", HIDDEN_VISUALIZATION to "$ROOT/charts/hidden").forEach { (id, name) ->
            seedArtifact("visualizations", "visualization_versions", "visualization_id", id, name, visualizationBody())
        }
        seedArtifact(
            "dashboards",
            "dashboard_versions",
            "dashboard_id",
            SHOWN_DASHBOARD,
            "$ROOT/boards/shown",
            bodyOf(dashboard("$ROOT/boards/shown", "$ROOT/charts/shown", VISIBLE_PIPELINE)),
        )
        seedArtifact(
            "dashboards",
            "dashboard_versions",
            "dashboard_id",
            HIDDEN_DASHBOARD,
            "$ROOT/boards/hidden",
            bodyOf(dashboard("$ROOT/boards/hidden", "$ROOT/charts/hidden", HIDDEN_PIPELINE)),
        )
    }

    @Suppress("LongParameterList") // one row pair per family table
    private fun seedArtifact(
        table: String,
        versions: String,
        fk: String,
        id: String,
        name: String,
        body: String,
    ) {
        sql(
            "INSERT INTO $table (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', '$name', '$name', '', 1, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO $versions ($fk, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$id', 1, '$body'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
    }

    /** A stored body is the document without its `name` (V42's `chk_*_body`). */
    private fun bodyOf(document: String): String = (mapper.readTree(document) as ObjectNode).apply { remove("name") }.toString()

    private fun visualizationBody(): String = bodyOf(VISUALIZATION.replace("__NAME__", "$ROOT/charts/any"))

    private fun pipelineBody(name: String): String =
        """{"schema_version":1,"name":"$name","display_name":"P","description":"",""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"$ROOT/templates/read.sql","version":1}}]}"""

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        const val EXCERPT = 600
        const val WORKSPACE = "db-e2e"
        const val ROOT = "dbe"
        const val VISIBLE_PIPELINE = "$ROOT/pipelines/visible"
        const val HIDDEN_PIPELINE = "$ROOT/pipelines/hidden"
        const val HIDDEN_HASH = "on-target-hash"

        val VISUALIZATION =
            """
            {"name": "__NAME__", "display_name": "Revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        val DASHBOARD =
            """
            {"name": "__NAME__", "display_name": "Revenue", "description": "",
             "sources": [{"name": "revenue_source", "pipeline": {"name": "__PIPELINE__", "version": 1}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                 "visualization": {"name": "__VISUALIZATION__", "version": 1},
                                 "inputs": {"revenue": {"source": "revenue_source"}}}],
             "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()

        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val PROMOTER_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()
        private val VISIBLE_PIPELINE_ID = UUID.randomUUID().toString()
        private val HIDDEN_PIPELINE_ID = UUID.randomUUID().toString()
        private val SHOWN_VISUALIZATION = UUID.randomUUID().toString()
        private val HIDDEN_VISUALIZATION = UUID.randomUUID().toString()
        private val SHOWN_DASHBOARD = UUID.randomUUID().toString()
        private val HIDDEN_DASHBOARD = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "db-admin@e2e.test", WORKSPACE)
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "db-promoter@e2e.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "db-viewer@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()

        /** The stub higher environment: it already serves the HIDDEN pipeline at its released hash. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"$WORKSPACE","templates":[],"datasources":[],""" +
                                """"parameter_sets":[],"pipelines":[""" +
                                """{"name":"$HIDDEN_PIPELINE","current_version":1,"body_hash":"$HIDDEN_HASH"}]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }
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
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
            // The promoter lens reads the stub higher environment.
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "dbe-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
