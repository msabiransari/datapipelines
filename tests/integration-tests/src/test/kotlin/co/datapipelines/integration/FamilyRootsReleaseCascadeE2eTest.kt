package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import co.datapipelines.integration.FamilyRootsFixture.Edge
import co.datapipelines.integration.FamilyRootsFixture.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
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
 * #873 §C over the REAL wired application on PostgreSQL: the template, parameter-set, visualization and dashboard roots
 * release through the shared planner on their existing REST routes, every artifact authored through the product.
 *
 * - **A10** — per root family an ALL-DRAFT closure (the dashboard's: a DRAFT set and its DRAFT selector template, a
 *   DRAFT source pipeline and its DRAFT template, a DRAFT visualization and its DRAFT transform template) releases with a
 *   plan whose `tree_hash` this test recomputes from SQL ([FamilyRootsFixture.treeHash]): every node RELEASED at its
 *   unchanged hash and version 1, every pointer at 1, and exactly one `*.version.released` row per flip, all sharing one
 *   `release_id`, dependencies first and the root last (the coordinator is the one audit writer, §C.7).
 * - **A13** — a trigger plant refuses the dashboard root's own flip AFTER every dependency flipped (a witness sequence
 *   proves the visualization was already RELEASED inside the transaction): every flip, pointer and row rolls back, read
 *   on a second connection; the same plan with no plant then releases.
 * - **A14** — a write between preview and apply (the source pipeline's DRAFT template edited through REST) is the
 *   dashboard's `plan_conflict`, nothing flipped; without a plan the DRAFT source refuses kind `pipeline` (§C.4).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FamilyRootsReleaseCascadeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val fx by lazy { FamilyRootsFixture(port, SESSION, WORKSPACE_ID, DATASOURCE) }

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun scalar(sql: String): String =
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    check(r.next())
                    r.getString(1)
                }
            }
        }

    /**
     * Every `*.version.released` row this class's admin caused after audit id [after] — `event | release_id`, in insert
     * order. Not filtered on `release_id`: a second writer's row (a handler's post-commit audit, which carries none) is
     * counted and red.
     */
    private fun auditAfter(after: Long): List<Pair<String, String?>> =
        connect().use { c ->
            c
                .prepareStatement(
                    "SELECT event, details_json ->> 'release_id' FROM audit_log WHERE id > ? AND user_id = ?::uuid" +
                        " AND event LIKE '%.version.released' ORDER BY id",
                ).use { s ->
                    s.setLong(1, after)
                    s.setString(2, ADMIN_ID)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1) to r.getString(2)) } }
                }
        }

    private fun lastAuditId(): Long = scalar("SELECT COALESCE(MAX(id), 0)::text FROM audit_log").toLong()

    // ---- the four closures ------------------------------------------------------------------------------------

    private fun closure(
        family: String,
        prefix: String,
    ): Pair<Node, List<Edge>> = fx.closure(family, prefix)

    /** The committed state of every node of a closure: status, hash and pointer per node. */
    private fun state(
        root: Node,
        edges: List<Edge>,
    ): Map<Node, FamilyRootsFixture.Row> = fx.nodeIds(root, edges).keys.associateWith(fx::row)

    private fun refused(
        response: Response,
        status: Int,
    ): String {
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe status }
        return response.jsonPath().getString("error.code")
    }

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - the H2 datasource the pipelines and selectors read`() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .asSession(SESSION)
            .contentType(ContentType.JSON)
            .body(
                """{"name": "$DATASOURCE", "display_name": "Family roots", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
            ).post("/api/v1/datasources")
            .then()
            .statusCode(CREATED)
    }

    @Test
    @Order(2)
    fun `A10 - each root releases its all-draft closure dependency-first in one transaction, one row per flip`() {
        ROOT_EVENTS.forEach { (family, events) ->
            withClue(family) {
                val (root, edges) = closure(family, "test/fr873/$family")
                val before = state(root, edges)
                before.values.map { it.status }.toSet() shouldBe setOf("DRAFT")
                val audit = lastAuditId()

                val released = fx.releaseWithPlan(root, edges)

                withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
                val after = state(root, edges)
                after.forEach { (node, row) ->
                    withClue(node) {
                        row.status shouldBe "RELEASED"
                        row.hash shouldBe before.getValue(node).hash
                        row.current shouldBe "1"
                    }
                }
                val rows = auditAfter(audit)
                println("event=family_roots.a10 root=$family rows=$rows")
                rows.map { it.first } shouldContainExactly events
                val releaseIds = rows.map { it.second }.toSet()
                releaseIds.size shouldBe 1
                releaseIds.single() shouldNotBe null
            }
        }
    }

    @Test
    @Order(3)
    fun `A13 - a refused root flip after every dependency flipped rolls everything back, then the same plan releases`() {
        val (root, edges) = closure("dashboard", "test/fr873/a13")
        val before = state(root, edges)
        val audit = lastAuditId()
        plant(fx.row(root).uuid)
        try {
            val response = fx.releaseWithPlan(root, edges)
            val witness = scalar("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM $WITNESS")
            val after = state(root, edges)
            println("event=family_roots.a13 witness=$witness status=${after.map { (n, r) -> "${n.name}=${r.status}/${r.current}" }}")
            withClue("second-connection read: every flip and pointer rolled back") { after shouldBe before }
            withClue("the plant fired after the visualization flipped inside the transaction") { witness shouldBe "1" }
            auditAfter(audit) shouldBe emptyList()
            withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe INTERNAL_ERROR }
        } finally {
            dropPlant()
        }

        val released = fx.releaseWithPlan(root, edges)
        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        state(root, edges).values.map { it.status }.toSet() shouldBe setOf("RELEASED")
    }

    @Test
    @Order(4)
    fun `A14 - an edit between preview and apply is the dashboard's plan_conflict, nothing flipped - and no plan refuses kind pipeline`() {
        val (root, edges) = closure("dashboard", "test/fr873/a14")
        val previewed = fx.treeHash(root, edges)
        val query = Node("template", "test/fr873/a14/query.sql")
        val hashBefore = fx.row(query).hash
        val edit =
            """{"id": "${query.name}", "dialect": "H2", "display_name": "Family roots", "description": "#873 A14",
               "is_library": false, "imports": [], "body": "SELECT DATE '2026-02-01' AS \"month\", 1 AS \"amount\""}"""
        val edited = fx.send("PUT", "/api/v1/templates", edit, hashBefore)
        withClue(edited.asString().take(EXCERPT)) { edited.statusCode shouldBe OK }
        val before = state(root, edges)
        println("event=family_roots.a14 previewed=$previewed edited=${query.name} hash=$hashBefore->${before.getValue(query).hash}")

        refused(fx.releaseWithPlan(root, edges, previewed), CONFLICT) shouldBe "dashboard.release.plan_conflict"
        state(root, edges) shouldBe before

        val bare = fx.send("POST", "/api/v1/dashboards/${fx.row(root).uuid}/release", "", fx.row(root).hash)
        refused(bare, CONFLICT) shouldBe "dashboard.release.dependency_not_released"
        bare.jsonPath().getList<String>("error.details.dependencies_not_released.kind") shouldContain "pipeline"
        state(root, edges) shouldBe before
    }

    // ---- the A13 plant ----------------------------------------------------------------------------------------

    /** Refuses the dashboard [id]'s own DRAFT → RELEASED flip; witnesses that a visualization was RELEASED before it. */
    private fun plant(id: String) {
        UUID.fromString(id)
        connect().use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE SEQUENCE $WITNESS")
                s.execute(
                    """
                    CREATE FUNCTION $FUNCTION() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                    BEGIN
                        IF EXISTS (SELECT 1 FROM visualization_versions v JOIN visualizations a ON a.id = v.visualization_id
                                    WHERE a.workspace_id = '$WORKSPACE_ID'::uuid AND a.name = 'test/fr873/a13/chart'
                                      AND v.status = 'RELEASED') THEN
                            PERFORM nextval('$WITNESS');
                        END IF;
                        RAISE EXCEPTION 'planted by #873';
                    END
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
                s.execute(
                    "CREATE TRIGGER $TRIGGER BEFORE UPDATE ON dashboard_versions FOR EACH ROW WHEN" +
                        " (OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' AND OLD.dashboard_id = '$id'::uuid)" +
                        " EXECUTE FUNCTION $FUNCTION()",
                )
            }
        }
    }

    private fun dropPlant() {
        connect().use { c ->
            c.createStatement().use { s ->
                s.execute("DROP TRIGGER IF EXISTS $TRIGGER ON dashboard_versions")
                s.execute("DROP FUNCTION IF EXISTS $FUNCTION()")
                s.execute("DROP SEQUENCE IF EXISTS $WITNESS")
            }
        }
    }

    @AfterAll
    fun dropAndProve() {
        dropPlant()
        val leftovers =
            scalar(
                "SELECT ((SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'dp873%')" +
                    " + (SELECT count(*) FROM pg_proc WHERE proname LIKE 'dp873%')" +
                    " + (SELECT count(*) FROM pg_class WHERE relname LIKE 'dp873%'))::text",
            )
        println("pg_trigger/pg_proc/pg_class dp873 objects after the class = $leftovers")
        oidc.close()
        leftovers shouldBe "0"
    }

    companion object {
        private const val OK = 200
        private const val CREATED = 201
        private const val CONFLICT = 409
        private const val INTERNAL_ERROR = 500
        private const val EXCERPT = 800
        private const val DATASOURCE = "h2-family-roots-873"
        private const val H2_JDBC_URL = "jdbc:h2:mem:familyroots873;DB_CLOSE_DELAY=-1"
        private const val FUNCTION = "dp873_refuse"
        private const val WITNESS = "dp873_witness"
        private const val TRIGGER = "dp873_root_flip_refuse"
        private const val WORKSPACE = "family-roots-873"

        private const val TEMPLATE_EVENT = "template.version.released"

        /** Per root family, the release's rows in insert order: the dependencies first (dependency-first), the root last. */
        private val ROOT_EVENTS =
            linkedMapOf(
                "template" to listOf(TEMPLATE_EVENT, TEMPLATE_EVENT),
                "parameter_set" to listOf(TEMPLATE_EVENT, "parameter_set.version.released"),
                "visualization" to listOf(TEMPLATE_EVENT, "visualization.version.released"),
                "dashboard" to
                    listOf(
                        TEMPLATE_EVENT,
                        "parameter_set.version.released",
                        TEMPLATE_EVENT,
                        "pipeline.version.released",
                        TEMPLATE_EVENT,
                        "visualization.version.released",
                        "dashboard.version.released",
                    ),
            )

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-873-roots@datapipelines.test", WORKSPACE)

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
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Family roots 873')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-873-roots@datapipelines.test', 'E2E 873', 'test', 'e2e-873-roots-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                }
            }
        }
    }
}
