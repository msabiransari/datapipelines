package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import co.datapipelines.integration.FamilyRootsFixture.Edge
import co.datapipelines.integration.FamilyRootsFixture.Node
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.BeforeAll
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
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * #876 round 4 (verdict R3) — the shared release DIALOG binds each DRAFT pipeline row's own check override into the plan
 * it posts (`check_override_node` + `check_override_reason`, paired by index into `check_overrides`), over the REAL
 * wired application on PostgreSQL, every artifact authored through the product and every outcome read from SQL:
 *
 * - a dashboard whose DRAFT source pipeline has a failing release check is refused `pipeline.check.failed` without an
 *   override, and releases with the source's OWN reason — that reason on the SOURCE's `pipeline.version.released` row,
 *   none on the dashboard's;
 * - a malformed pairing (counts differ), an unknown node, an override for an unticked node and a node overridden twice
 *   are each refused `dashboard.release.plan_invalid` with nothing flipped (the REST twin of the same plan names the
 *   field and the coordinator's reason, which the dialog's toast does not carry);
 * - a viewer's forced POST of a valid override is refused 403 by the route's `dashboard.release` scope, nothing flipped;
 * - a pipeline root's own `overrideChecksReason` is never its child pipeline's: with the root's reason alone the child's
 *   failing check refuses; with both, each release row carries its OWN reason.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReleaseDialogCheckOverridesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val fx by lazy { FamilyRootsFixture(port, SESSION, WORKSPACE_ID, DATASOURCE) }
    private val viewer by lazy { FamilyRootsFixture(port, VIEWER_SESSION, WORKSPACE_ID, DATASOURCE) }

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun lastAuditId(): Long =
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM audit_log").use { r ->
                    check(r.next())
                    r.getLong(1)
                }
            }
        }

    /** Every `*.version.released` row after audit id [after]: its event, the artifact's name and its override reason. */
    private fun releasedAfter(after: Long): List<Triple<String, String?, String?>> =
        connect().use { c ->
            c
                .prepareStatement(
                    "SELECT event, COALESCE(details_json ->> 'pipeline_name', details_json ->> 'name')," +
                        " details_json ->> 'override_reason'" +
                        " FROM audit_log WHERE id > ? AND event LIKE '%.version.released' ORDER BY id",
                ).use { s ->
                    s.setLong(1, after)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getString(1), r.getString(2), r.getString(3))) } }
                }
        }

    private fun state(
        root: Node,
        edges: List<Edge>,
    ): Map<Node, FamilyRootsFixture.Row> = fx.nodeIds(root, edges).keys.associateWith(fx::row)

    private fun overrides(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        mapOf(NODE to pairs.map { it.first }, REASON to pairs.map { it.second })

    /** The REST twin of a dialog POST: the same plan as JSON, whose refusal names the field and the reason. */
    private fun restPlan(
        root: Node,
        edges: List<Edge>,
        include: List<String>,
        checkOverrides: List<Pair<String, String>>,
    ): Response {
        val row = fx.row(root)
        val plan =
            mapOf(
                "tree_hash" to fx.treeHash(root, edges),
                "include_dependencies" to include,
                "check_overrides" to checkOverrides.map { mapOf("node_id" to it.first, "reason" to it.second) },
            )
        return fx.send("POST", "/api/v1/dashboards/${row.uuid}/release", MAPPER.writeValueAsString(plan), row.hash)
    }

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - the H2 datasource the pipelines, checks and selectors read`() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .asSession(SESSION)
            .contentType(ContentType.JSON)
            .body(
                """{"name": "$DATASOURCE", "display_name": "Check overrides", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
            ).post("/api/v1/datasources")
            .then()
            .statusCode(CREATED)
    }

    @Test
    @Order(2)
    fun `a dashboard's DRAFT source with a failing check - refused without an override, and every malformed override refused`() {
        val (board, edges) = fx.dashboardClosure("test/rd876/refused", sourceChecks = listOf(FAILING_CHECK))
        val ids = fx.nodeIds(board, edges)
        val source = ids.getValue(Node("pipeline", "test/rd876/refused/source"))
        val all = ids.filterKeys { it != board }.values.toList()
        val before = state(board, edges)
        before.values.map { it.status }.toSet() shouldBe setOf("DRAFT")
        val audit = lastAuditId()

        withClue("no override: the source's own check gate refuses") {
            val refused = fx.releaseDialog(board, edges, htmx = true)
            withClue(refused.asString().take(EXCERPT)) { refused.statusCode shouldBe CONFLICT }
            refused.asString() shouldContain "pipeline.check.failed"
        }

        val malformed =
            listOf(
                Triple("counts differ", all, mapOf(NODE to listOf(source, "n1"), REASON to listOf(REASON_TEXT))),
                Triple("an unknown node", all, overrides("n99" to REASON_TEXT)),
                Triple("an unticked node", all - source, overrides(source to REASON_TEXT)),
                Triple("a node twice", all, overrides(source to REASON_TEXT, source to "$REASON_TEXT again")),
            )
        for ((case, include, extra) in malformed) {
            withClue(case) {
                val refused = fx.releaseDialog(board, edges, include = include, extra = extra, htmx = true)
                withClue(refused.asString().take(EXCERPT)) { refused.statusCode shouldBe BAD_REQUEST }
                refused.asString() shouldContain "dashboard.release.plan_invalid"
                refused.header(HX_REDIRECT) shouldBe null
            }
        }
        // The coordinator's own reason for each well-formed refusal, read off the REST twin of the SAME plan.
        val reasons =
            listOf(
                Triple(all, listOf("n99" to REASON_TEXT), "not a node of the plan"),
                Triple(all - source, listOf(source to REASON_TEXT), "the node is not released by this plan"),
                Triple(all, listOf(source to REASON_TEXT, source to "$REASON_TEXT again"), "a node's override is given twice"),
            )
        for ((include, entries, reason) in reasons) {
            withClue(reason) {
                val refused = restPlan(board, edges, include, entries)
                withClue(refused.asString().take(EXCERPT)) { refused.statusCode shouldBe BAD_REQUEST }
                refused.jsonPath().getString("error.code") shouldBe "dashboard.release.plan_invalid"
                refused.jsonPath().getString("error.details.reason") shouldBe reason
                refused.jsonPath().getString("error.details.field") shouldContain "check_overrides"
            }
        }

        withClue("a viewer's forced POST of a valid override: the route's dashboard.release scope refuses") {
            val refused = viewer.releaseDialog(board, edges, extra = overrides(source to REASON_TEXT), htmx = true)
            withClue(refused.asString().take(EXCERPT)) { refused.statusCode shouldBe FORBIDDEN }
        }

        withClue("independent-connection read: nothing flipped, every pointer unmoved, no release row") {
            state(board, edges) shouldBe before
            releasedAfter(audit) shouldBe emptyList()
        }
    }

    @Test
    @Order(3)
    fun `the source's OWN override releases the dashboard - the reason rides the source's release row, not the board's`() {
        val (board, edges) = fx.dashboardClosure("test/rd876/released", sourceChecks = listOf(FAILING_CHECK))
        val sourceNode = Node("pipeline", "test/rd876/released/source")
        val source = fx.nodeIds(board, edges).getValue(sourceNode)
        val audit = lastAuditId()

        val released = fx.releaseDialog(board, edges, extra = overrides(source to REASON_TEXT))

        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        released.header(HX_REDIRECT) shouldContain "ok=released"
        state(board, edges).values.map { it.status }.toSet() shouldBe setOf("RELEASED")
        val rows = releasedAfter(audit)
        withClue(rows) {
            rows.single { it.first == "pipeline.version.released" && it.second == sourceNode.name }.third shouldBe REASON_TEXT
            rows.filter { it.third != null }.size shouldBe 1
            rows.last().first shouldBe "dashboard.version.released"
        }
    }

    @Test
    @Order(4)
    fun `a pipeline root's own reason is never its child's - the child refuses on it alone, and each row carries its OWN reason`() {
        val query = fx.sqlTemplate("test/rd876/pipe/query.sql")
        val childNode = fx.pipeline("test/rd876/pipe/child", query, checks = listOf(FAILING_CHECK))
        val parentNode = parent("test/rd876/pipe/parent", childNode)
        val edges = listOf(Edge(parentNode, childNode, "nodes[0].pipeline"), Edge(childNode, query, "nodes[0].template"))
        val child = fx.nodeIds(parentNode, edges).getValue(childNode)
        val before = state(parentNode, edges)
        val audit = lastAuditId()

        withClue("the root's overrideChecksReason alone: the child's failing check still refuses") {
            val refused = fx.releaseDialog(parentNode, edges, extra = mapOf(ROOT_REASON to listOf(ROOT_REASON_TEXT)), htmx = true)
            withClue(refused.asString().take(EXCERPT)) { refused.statusCode shouldBe CONFLICT }
            refused.asString() shouldContain "pipeline.check.failed"
            state(parentNode, edges) shouldBe before
            releasedAfter(audit) shouldBe emptyList()
        }

        val released =
            fx.releaseDialog(parentNode, edges, extra = mapOf(ROOT_REASON to listOf(ROOT_REASON_TEXT)) + overrides(child to REASON_TEXT))

        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        state(parentNode, edges).values.map { it.status }.toSet() shouldBe setOf("RELEASED")
        val rows = releasedAfter(audit).filter { it.first == "pipeline.version.released" }
        withClue(rows) {
            rows.single { it.second == childNode.name }.third shouldBe REASON_TEXT
            rows.single { it.second == parentNode.name }.third shouldBe ROOT_REASON_TEXT
        }
    }

    /** A DRAFT pipeline whose one PIPELINE node pins [child] v1, carrying its own failing check. */
    private fun parent(
        name: String,
        child: Node,
    ): Node {
        val node =
            mapOf(
                "id" to "child",
                "type" to "PIPELINE",
                "pipeline" to mapOf("name" to child.name, "version" to 1),
                "depends_on" to emptyList<String>(),
            )
        val document =
            mapOf(
                "name" to name,
                "display_name" to "Check overrides",
                "description" to "#876",
                "nodes" to listOf(node),
                "checks" to listOf(FAILING_CHECK),
            )
        val created = fx.send("POST", "/api/v1/pipelines", MAPPER.writeValueAsString(document))
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe CREATED }
        return Node("pipeline", name)
    }

    private companion object {
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val CONFLICT = 409
        const val EXCERPT = 800
        const val HX_REDIRECT = "HX-Redirect"
        const val NODE = "check_override_node"
        const val REASON = "check_override_reason"
        const val ROOT_REASON = "overrideChecksReason"
        const val REASON_TEXT = "Source feed is late today, checked by hand"
        const val ROOT_REASON_TEXT = "The root's row count is known short, ok"
        const val DATASOURCE = "h2-check-overrides-876"
        const val H2_JDBC_URL = "jdbc:h2:mem:checkoverrides876;DB_CLOSE_DELAY=-1"
        const val WORKSPACE = "check-overrides-876"
        val MAPPER = ObjectMapper()

        /** One row where three are expected: the check FAILS, so the version releases only under an override. */
        val FAILING_CHECK: Map<String, Any?> =
            mapOf(
                "id" to "three_rows",
                "name" to "Three rows expected",
                "datasource" to DATASOURCE,
                "sql" to "SELECT 1 AS n",
                "expected" to mapOf("kind" to "rows", "rows" to 3),
            )

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val VIEWER_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-876-overrides@datapipelines.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "e2e-876-viewer@datapipelines.test", WORKSPACE)

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
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Check overrides 876')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin, and a plain viewer.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-876-overrides@datapipelines.test', 'E2E 876', 'test'," +
                            " 'e2e-876-overrides-sub', TRUE, TRUE)," +
                            " ('$VIEWER_ID', 'e2e-876-viewer@datapipelines.test', 'E2E 876 viewer', 'test', 'e2e-876-viewer-sub', TRUE, FALSE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES" +
                            " ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'), ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')",
                    )
                }
            }
        }
    }
}
