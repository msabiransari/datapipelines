package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import co.datapipelines.integration.FamilyRootsFixture.Edge
import co.datapipelines.integration.FamilyRootsFixture.Node
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * #873 §F (U2) over the REAL wired application on PostgreSQL: datasource edges are prerequisites, never released.
 * Every artifact is authored through the product's REST routes; a datasource's disappearance, recreation and grant move
 * are SQL plants on a second connection (the product's delete is refused while a body names the datasource — the name
 * lock this class also proves).
 *
 * 1. the name lock: a parameter-set selector alone refuses the delete `datasource.in_use`, including one held only by a
 *    DISCARDED version of a live set (the pipeline scan's any-version rule over a live entity);
 * 2. a datasource that disappears and is recreated under the same name (a new row, a new incarnation, a re-grant) between
 *    plan and apply does not invalidate consent: the plan still APPLIES, on the plan and the compatibility path;
 * 3. a datasource that disappears without recreation, held by a RELEASED child below a DRAFT root, refuses
 *    `pipeline.release.dependency_not_released` before any flip on both paths, nothing flipped and no audit row. A holder
 *    that FLIPS is refused earlier, outside the guarded transaction, by its native §12 revalidation
 *    (`pipeline.validation.unknown_datasource`, 400) — pinned here as observed, never reached by the prerequisite check;
 * 4. a legacy alias release whose closure names a datasource behaves as before U2;
 * 5. a datasource granted only to ANOTHER workspace is the absent case: every refusal above and the plan wire deep-compare
 *    equal, both paths;
 * 6. the production planner's `prerequisites` lists `warehouse` once — two edges reach it — with its `readonly` flag, and
 *    the plan's `tree_hash` is the artifact-only oracle's;
 * 7. the plan wire's absent row and other-workspace row are the identical `{kind, name, available:false, readonly:null}`
 *    and the plan is blocked by it.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DatasourceEdgesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired private lateinit var context: ApplicationContext

    private val planning by lazy { ProductionInternals.bean(context, "application.dependencies.ReleasePlanningWalk") }
    private val bounds by lazy { ProductionInternals.bean(context, "pipeline.release.ReleaseBounds") }

    private fun fx(datasource: String) = FamilyRootsFixture(port, SESSION, WORKSPACE_ID, datasource)

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - the datasources, each granted to this workspace`() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { it.createStatement().execute("SELECT 1") }
        DATASOURCES.forEach { name ->
            val created =
                rest()
                    .contentType(ContentType.JSON)
                    .body(
                        """{"name": "$name", "display_name": "Datasource edges", "dialect": "H2",
                           "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
                    ).post("/api/v1/datasources")
            withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe CREATED }
            withClue("non-vacuity: $name is granted to this workspace") { granted(name, WORKSPACE_ID) shouldBe true }
        }
    }

    @Test
    @Order(2)
    fun `1 - a parameter-set selector alone locks the name - one held only by a DISCARDED version included`() {
        val fx = fx(DS_SET_ONLY)
        val selector = fx.sqlTemplate("test/ds873/locked/selector.sql", FamilyRootsFixture.SELECTOR_BODY)
        val set = fx.parameterSet(LOCKED_SET, selector)

        val draft = delete(DS_SET_ONLY)

        refusedInUse(draft, "DRAFT")
        val released = fx.releaseCompatible(set)
        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        val repointed = fx.send("PUT", "/api/v1/parameter-sets/${fx.row(set).uuid}", setDocument(selector, DS_REPOINT), fx.row(set).hash)
        withClue(repointed.asString().take(EXCERPT)) { repointed.statusCode shouldBe OK }
        val discarded = fx.send("POST", "/api/v1/parameter-sets/${fx.row(set).uuid}/versions/1/discard", "")
        withClue(discarded.asString().take(EXCERPT)) { discarded.statusCode shouldBe OK }
        fx.row(set).status shouldBe "DISCARDED"

        withClue("v1 is DISCARDED and only v1 names the datasource; the set stays live through its v2 draft") {
            refusedInUse(delete(DS_SET_ONLY), "DISCARDED")
        }
        withClue("non-vacuity: an unreferenced datasource deletes") { delete(DS_FREE).statusCode shouldBe NO_CONTENT }
    }

    @Test
    @Order(3)
    fun `2 - a datasource recreated under the same name between plan and apply does not invalidate consent`() {
        val fx = fx(DS_RECREATE)
        val (planned, plannedEdges) = pipeline(fx, "test/ds873/recreate/planned")
        val (compatible, _) = pipeline(fx, "test/ds873/recreate/compatible")
        val treeHash = fx.treeHash(planned, plannedEdges)
        val before = incarnation(DS_RECREATE)

        recreate(DS_RECREATE)

        withClue("the plant made a NEW row: a new incarnation") { incarnation(DS_RECREATE) shouldNotBe before }
        val applied = fx.releaseWithPlan(planned, plannedEdges, treeHash)
        withClue(applied.asString().take(EXCERPT)) { applied.statusCode shouldBe OK }
        fx.row(planned).status shouldBe "RELEASED"
        val legacy = releaseLegacy(fx, compatible)
        withClue(legacy.asString().take(EXCERPT)) { legacy.statusCode shouldBe OK }
        fx.row(compatible).status shouldBe "RELEASED"
    }

    @Test
    @Order(4)
    fun `3, 5, 7 - absent and granted only to another workspace refuse alike before any flip, both paths and on the wire`() {
        val fx = fx(DS_GONE)
        // The prerequisite check's holder: a RELEASED child (it does not flip, so no native validation re-reads it).
        val (child, childEdges) = pipeline(fx, "test/ds873/gone/child")
        withClue("the child is released while its datasource is granted") { releaseLegacy(fx, child).statusCode shouldBe OK }
        val (parentPlanned, plannedEdges) = parent(fx, "test/ds873/gone/parent_planned", child, childEdges)
        val (parentCompatible, _) = parent(fx, "test/ds873/gone/parent_compatible", child, childEdges)
        // The native holder: a DRAFT pipeline that flips, so its §12 revalidation runs before the guarded transaction.
        val (flipPlanned, flipEdges) = pipeline(fx, "test/ds873/gone/flip_planned")
        val (flipCompatible, flipCompatibleEdges) = pipeline(fx, "test/ds873/gone/flip_compatible")
        val drafts = listOf(parentPlanned, parentCompatible) + (flipEdges + flipCompatibleEdges).flatMap { listOf(it.from, it.to) }

        fun observe() =
            Observed(
                prerequisite =
                    refusals(CONFLICT, { planRelease(fx, parentPlanned, plannedEdges) }, { releaseLegacy(fx, parentCompatible) }),
                native =
                    refusals(
                        BAD_REQUEST,
                        { planRelease(fx, flipPlanned, flipEdges, selectAll = true) },
                        { releaseLegacy(fx, flipCompatible) },
                    ),
                wire = planWire(parentPlanned),
            )

        moveGrant(DS_GONE, from = WORKSPACE_ID, to = OTHER_WORKSPACE_ID)
        val theirs = observe()
        moveGrant(DS_GONE, from = OTHER_WORKSPACE_ID, to = WORKSPACE_ID)
        softDelete(DS_GONE)
        val absent = observe()

        listOf(theirs, absent).flatMap { it.prerequisite }.forEach { refusal ->
            refusal["code"] shouldBe "pipeline.release.dependency_not_released"
            @Suppress("UNCHECKED_CAST")
            (refusal["details"] as Map<String, Any?>)["blockers"] shouldBe listOf(BLOCKER)
        }
        listOf(theirs, absent).flatMap { it.native }.forEach { it["code"] shouldBe "pipeline.validation.unknown_datasource" }
        withClue("other-workspace-only = absent, deep-compared: plan path, compatibility path, native holder, plan wire") {
            theirs shouldBe absent
        }
        withClue("nothing flipped") { drafts.map { fx.row(it).status }.toSet() shouldBe setOf("DRAFT") }
        withClue("the plan wire's prerequisite row and blocker") {
            theirs.wire["prerequisites"] shouldBe listOf(UNAVAILABLE)
            @Suppress("UNCHECKED_CAST")
            (theirs.wire["blockers"] as List<Any?>) shouldContainExactly listOf(BLOCKER)
            theirs.wire["tree_hash"] shouldBe fx.treeHash(parentPlanned, plannedEdges)
        }
    }

    /** One state's observations: the prerequisite refusals (plan, compatibility), the native ones, the plan wire. */
    private data class Observed(
        val prerequisite: List<Map<String, Any?>>,
        val native: List<Map<String, Any?>>,
        val wire: Map<String, Any?>,
    )

    @Test
    @Order(5)
    fun `4 - a legacy alias release whose closure names a datasource releases as before`() {
        val fx = fx(DS_LEGACY)
        val (root, edges) = pipeline(fx, "test/ds873/legacy/root")
        val audit = lastAuditId()

        val released = releaseLegacy(fx, root)

        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        listOf(root, edges.single().to).map { fx.row(it).status } shouldBe listOf("RELEASED", "RELEASED")
        releasedEventsAfter(audit) shouldBe listOf("template.version.released", "pipeline.version.released")
    }

    @Test
    @Order(6)
    fun `6 - two edges reach warehouse - the plan lists it once with its readonly flag, tree_hash the artifact oracle's`() {
        val fx = fx(WAREHOUSE)
        val (board, edges) = fx.dashboardClosure("test/ds873/warehouse")
        execute("UPDATE datasources SET is_readonly = TRUE WHERE name = ?", WAREHOUSE)

        val wire = planWire(board)

        wire["prerequisites"] shouldBe
            listOf(linkedMapOf("kind" to "datasource", "name" to WAREHOUSE, "available" to true, "readonly" to true))
        wire["blockers"] shouldBe emptyList<Any>()
        wire["tree_hash"] shouldBe fx.treeHash(board, edges)
        @Suppress("UNCHECKED_CAST")
        (wire["nodes"] as List<Map<String, Any?>>).size shouldBe fx.nodeIds(board, edges).size
        val released = fx.releaseWithPlan(board, edges)
        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
    }

    // ---- product calls ----------------------------------------------------------------------------------------

    private fun rest() = given().port(port).asSession(SESSION)

    private fun delete(name: String): Response = rest().delete("/api/v1/datasources/$name")

    /** A DRAFT pipeline under [name] reading the fixture's datasource through one DRAFT template. */
    private fun pipeline(
        fx: FamilyRootsFixture,
        name: String,
    ): Pair<Node, List<Edge>> {
        val template = fx.sqlTemplate("$name.sql")
        val pipeline = fx.pipeline(name, template)
        return pipeline to listOf(Edge(pipeline, template, "nodes[0].template"))
    }

    /** The compatibility path: `release_pinned_templates=true`, no plan, at the root's stored hash. */
    private fun releaseLegacy(
        fx: FamilyRootsFixture,
        root: Node,
    ): Response {
        val row = fx.row(root)
        return rest()
            .header("If-Match", row.hash)
            .queryParam("release_pinned_templates", "true")
            .post("/api/v1/pipelines/${row.uuid}/release")
    }

    /** A DRAFT parent pipeline under [name] whose one PIPELINE node pins [child]@1; the edges in pin order. */
    private fun parent(
        fx: FamilyRootsFixture,
        name: String,
        child: Node,
        childEdges: List<Edge>,
    ): Pair<Node, List<Edge>> {
        val node =
            mapOf(
                "id" to "child",
                "type" to "PIPELINE",
                "pipeline" to mapOf("name" to child.name, "version" to 1),
                "depends_on" to emptyList<String>(),
            )
        val document = mapOf("name" to name, "display_name" to "Datasource edges", "description" to "#873", "nodes" to listOf(node))
        val created = fx.send("POST", "/api/v1/pipelines", MAPPER.writeValueAsString(document))
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe CREATED }
        val parent = Node("pipeline", name)
        return parent to listOf(Edge(parent, child, "nodes[0].pipeline")) + childEdges
    }

    /**
     * The plan path at [root]'s stored hash, over the artifact-only oracle's `tree_hash`; with [selectAll] every
     * non-root node is selected (each is a DRAFT), otherwise none (the closure below the root is RELEASED).
     */
    private fun planRelease(
        fx: FamilyRootsFixture,
        root: Node,
        edges: List<Edge>,
        selectAll: Boolean = false,
    ): Response =
        if (selectAll) {
            fx.releaseWithPlan(root, edges)
        } else {
            val plan = mapOf("tree_hash" to fx.treeHash(root, edges), "include_dependencies" to emptyList<String>())
            fx.send("POST", "/api/v1/pipelines/${fx.row(root).uuid}/release", MAPPER.writeValueAsString(plan), fx.row(root).hash)
        }

    /** A v2 body for [LOCKED_SET]: its one selector over [selector], now reading [datasource]. */
    private fun setDocument(
        selector: Node,
        datasource: String,
    ): String {
        val source = mapOf("template" to mapOf("id" to selector.name, "version" to 1), "datasource" to datasource)
        val parameter =
            mapOf(
                "name" to "region",
                "label" to "Region",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "required" to false,
                "source" to source,
            )
        val document =
            mapOf("name" to LOCKED_SET, "display_name" to "Family roots", "description" to "#873", "parameters" to listOf(parameter))
        return MAPPER.writeValueAsString(document)
    }

    /** Each send's `{code, message, details}` at [status] (the correlation id differs by design); no audit row. */
    private fun refusals(
        status: Int,
        vararg sends: () -> Response,
    ): List<Map<String, Any?>> {
        val audit = lastAuditId()
        return sends.map { send ->
            val response = send()
            withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe status }
            withClue("no audit row for a refusal") { releasedEventsAfter(audit) shouldBe emptyList() }
            val error = response.jsonPath().getMap<String, Any?>("error")
            mapOf("code" to error["code"], "message" to error["message"], "details" to error["details"])
        }
    }

    private fun refusedInUse(
        response: Response,
        status: String,
    ) {
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe CONFLICT }
        response.jsonPath().getString("error.code") shouldBe "datasource.in_use"
        response.jsonPath().getList<String>("error.details.referencing_pipelines") shouldBe emptyList()
        response.jsonPath().getList<String>("error.details.referencing_parameter_sets") shouldBe listOf(LOCKED_SET)
        response.jsonPath().getList<Map<String, Any?>>("error.details.references") shouldBe
            listOf(
                mapOf(
                    "kind" to "parameter_set",
                    "parameter_set" to LOCKED_SET,
                    "parameter_set_version" to 1,
                    "version_status" to status,
                    "path" to "parameters[0].source.datasource",
                ),
            )
    }

    /** The production planner's plan wire for [root]@1 (`ReleasePlan.session` over its closure and prerequisites). */
    @Suppress("UNCHECKED_CAST")
    private fun planWire(root: Node): Map<String, Any?> {
        val family = ProductionInternals.enumOf("pipeline.release.ReleaseFamily", root.family.uppercase())
        val identity =
            ProductionInternals.construct("pipeline.release.ReleaseNodeIdentity", UUID.fromString(WORKSPACE_ID), family, root.name, 1)
        val planned = checkNotNull(ProductionInternals.call(planning, "planned", identity, UUID.fromString(ADMIN_ID))) { "$root resolves" }
        val plan =
            ProductionInternals.call(
                checkNotNull(ProductionInternals.companion("pipeline.release.ReleasePlan")),
                "session",
                ProductionInternals.call(planned, "getClosure"),
                ProductionInternals.call(bounds, "getMaxWireBytes"),
                ProductionInternals.call(planned, "getPrerequisites"),
            )
        val maxBlockers = ProductionInternals.call(bounds, "getMaxBlockers")
        return ProductionInternals.call(checkNotNull(plan), "toWire", maxBlockers) as Map<String, Any?>
    }

    // ---- SQL plants and reads, on their own connection ----------------------------------------------------------

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun execute(
        sql: String,
        vararg args: String,
    ) {
        connect().use { c ->
            c.prepareStatement(sql).use { s ->
                args.forEachIndexed { i, a -> s.setString(i + 1, a) }
                s.execute()
            }
        }
    }

    private fun granted(
        name: String,
        workspace: String,
    ): Boolean =
        connect().use { c ->
            c.prepareStatement("SELECT 1 FROM datasource_workspaces WHERE datasource_name = ? AND workspace_id = ?::uuid").use { s ->
                s.setString(1, name)
                s.setString(2, workspace)
                s.executeQuery().use { it.next() }
            }
        }

    private fun incarnation(name: String): String =
        connect().use { c ->
            c.prepareStatement("SELECT incarnation_id::text FROM datasources WHERE name = ?").use { s ->
                s.setString(1, name)
                s.executeQuery().use { r ->
                    check(r.next())
                    r.getString(1)
                }
            }
        }

    /** The other-workspace-only plant: the grant moves from [from] to [to]; the row itself is untouched. */
    private fun moveGrant(
        name: String,
        from: String,
        to: String,
    ) {
        connect().use { c ->
            c.autoCommit = false
            c.prepareStatement("DELETE FROM datasource_workspaces WHERE datasource_name = ? AND workspace_id = ?::uuid").use { s ->
                s.setString(1, name)
                s.setString(2, from)
                check(s.executeUpdate() == 1) { "the grant to move exists" }
            }
            c.prepareStatement(GRANT).use { s ->
                s.setString(1, name)
                s.setString(2, to)
                s.setString(3, ADMIN_ID)
                s.executeUpdate()
            }
            c.commit()
        }
    }

    private fun softDelete(name: String) = execute("UPDATE datasources SET is_deleted = TRUE WHERE name = ?", name)

    /**
     * The disappear-and-recreate plant, in ONE transaction: the row (its grants cascade) is deleted and a NEW row under the
     * same name is inserted from its copy with a fresh `incarnation_id` and generation 1, then re-granted to this
     * workspace — what a hard delete and a re-registration leave behind.
     */
    private fun recreate(name: String) {
        connect().use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("CREATE TEMP TABLE dp873_row ON COMMIT DROP AS SELECT * FROM datasources WHERE false") }
            c.prepareStatement("INSERT INTO dp873_row SELECT * FROM datasources WHERE name = ?").use { s ->
                s.setString(1, name)
                check(s.executeUpdate() == 1)
            }
            c.prepareStatement("DELETE FROM datasources WHERE name = ?").use { s ->
                s.setString(1, name)
                check(s.executeUpdate() == 1)
            }
            c.createStatement().use { s ->
                s.execute("UPDATE dp873_row SET incarnation_id = gen_random_uuid(), generation = 1")
                s.execute("INSERT INTO datasources SELECT * FROM dp873_row")
            }
            c.prepareStatement(GRANT).use { s ->
                s.setString(1, name)
                s.setString(2, WORKSPACE_ID)
                s.setString(3, ADMIN_ID)
                s.executeUpdate()
            }
            c.commit()
        }
    }

    private fun lastAuditId(): Long =
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM audit_log").use { r ->
                    check(r.next())
                    r.getLong(1)
                }
            }
        }

    /** This class's admin's `*.version.released` events after audit id [after], in insert order. */
    private fun releasedEventsAfter(after: Long): List<String> =
        connect().use { c ->
            c
                .prepareStatement(
                    "SELECT event FROM audit_log WHERE id > ? AND user_id = ?::uuid AND event LIKE '%.version.released' ORDER BY id",
                ).use { s ->
                    s.setLong(1, after)
                    s.setString(2, ADMIN_ID)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
                }
        }

    @AfterAll
    fun close() {
        oidc.close()
    }

    companion object {
        private const val OK = 200
        private const val CREATED = 201
        private const val NO_CONTENT = 204
        private const val BAD_REQUEST = 400
        private const val CONFLICT = 409
        private const val EXCERPT = 800
        private const val H2_JDBC_URL = "jdbc:h2:mem:dsedges873;DB_CLOSE_DELAY=-1"
        private const val WORKSPACE = "ds-edges-873"

        private const val WAREHOUSE = "warehouse"
        private const val DS_SET_ONLY = "ds873-set-only"
        private const val DS_FREE = "ds873-free"
        private const val DS_RECREATE = "ds873-recreate"
        private const val DS_GONE = "ds873-gone"
        private const val DS_LEGACY = "ds873-legacy"
        private const val DS_REPOINT = "ds873-repoint"
        private val DATASOURCES = listOf(WAREHOUSE, DS_SET_ONLY, DS_FREE, DS_RECREATE, DS_GONE, DS_LEGACY, DS_REPOINT)
        private const val LOCKED_SET = "test/ds873/locked/set"
        private val MAPPER = ObjectMapper()
        private const val GRANT =
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES (?, ?::uuid, ?::uuid)"

        private val BLOCKER = mapOf("kind" to "datasource", "name" to DS_GONE, "reason" to "unavailable")
        private val UNAVAILABLE = mapOf("kind" to "datasource", "name" to DS_GONE, "available" to false, "readonly" to null)

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val OTHER_WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-873-ds@datapipelines.test", WORKSPACE)

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
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-873-ds@datapipelines.test', 'E2E 873', 'test', 'e2e-873-ds-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Datasource edges 873')",
                    )
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$OTHER_WORKSPACE_ID', '$WORKSPACE-other', 'Other 873')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin.
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                }
            }
        }
    }
}
