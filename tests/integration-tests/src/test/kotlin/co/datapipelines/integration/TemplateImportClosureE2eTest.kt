package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.FamilyRootsFixture.Edge
import co.datapipelines.integration.FamilyRootsFixture.Node
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
 * #873 §E (release) and §D.2 (restore) over the REAL wired application on PostgreSQL: a template's declared import
 * closure is a release dependency through the template provider's `IMPORT` edges and the shared planner — no
 * template-specific guard class. Every template is authored through REST; the plans' `tree_hash` is recomputed here
 * from SQL ([FamilyRootsFixture.treeHash]), so a plan the product accepts is a closure the product walked exactly as
 * this test drew it (nodes AND edges).
 *
 * - (a) a DRAFT library selected in the plan releases first, then its importer; (b) left unticked it blocks
 *   `template.release.dependency_not_released` and nothing flips; (c) a DISCARDED library is a blocker and is never
 *   restored for you; (d) the walk goes BENEATH a RELEASED library (record §7.2 step 5, §23.3 item 2): its DRAFT
 *   grand-library blocks until it is selected; (e) a diamond loads the shared library once with two edge occurrences and
 *   releases it once (A02); (f) an import cycle (planted by SQL — the save rules refuse one) is
 *   `template.release.dependency_cycle`, nothing flipped.
 * - Restore: over a released import closure a DISCARDED importer restores; a DRAFT beneath its RELEASED library is the
 *   409 with `via`; a DISCARDED import (a real REST discard — a DISCARDED importer protects nothing) is the save rules'
 *   400 `template.validation.import_not_found`, `reason: discarded`.
 *
 * A library cannot leave RELEASED from under a live importer through the product, so (d) and its restore twin plant
 * the DRAFT grand-library by SQL and say so.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [DatapipelinesApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TemplateImportClosureE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val fx by lazy { FamilyRootsFixture(port, SESSION, WORKSPACE_ID, "no-datasource") }

    private fun connect(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun lastAuditId(): Long =
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM audit_log").use { r ->
                    r.next()
                    r.getLong(1)
                }
            }
        }

    /** The `template_id` of each `template.version.released` row after audit id [after] (this admin's), in insert order. */
    private fun releasedAfter(after: Long): List<String> =
        connect().use { c ->
            c
                .prepareStatement(
                    "SELECT details_json ->> 'template_id' FROM audit_log WHERE id > ? AND user_id = ?::uuid" +
                        " AND event = 'template.version.released' ORDER BY id",
                ).use { s ->
                    s.setLong(1, after)
                    s.setString(2, ADMIN_ID)
                    s.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
                }
        }

    private fun status(node: Node) = fx.row(node).status

    private fun lib(
        name: String,
        macro: String,
        imports: List<Node> = emptyList(),
        body: String = "<#macro $macro>SELECT 1 AS \"n\"</#macro>",
    ) = fx.sqlTemplate("$prefix/$name.sql", body, library = true, imports = imports.map { it.name })

    private fun importer(
        name: String,
        imports: List<Node>,
        body: String,
    ) = fx.sqlTemplate("$prefix/$name.sql", body, imports = imports.map { it.name })

    private lateinit var prefix: String

    private fun prefix(tag: String) {
        prefix = "test/imports873/$RUN/$tag"
    }

    /** Plans [root]'s release over [edges] selecting [selected] (by node), at the recomputed tree hash. */
    private fun releaseSelecting(
        root: Node,
        edges: List<Edge>,
        selected: List<Node>,
    ): Response {
        val ids = fx.nodeIds(root, edges)
        val plan =
            mapOf("name" to root.name, "tree_hash" to fx.treeHash(root, edges), "include_dependencies" to selected.map(ids::getValue))
        return fx.send("POST", "/api/v1/templates/release", MAPPER.writeValueAsString(plan), fx.row(root).hash)
    }

    private fun refused(
        response: Response,
        status: Int,
        code: String,
    ): Response {
        withClue(response.asString().take(EXCERPT)) {
            response.statusCode shouldBe status
            response.jsonPath().getString("error.code") shouldBe code
        }
        println("event=template_imports.refusal code=$code body=${response.asString().take(EXCERPT)}")
        return response
    }

    private fun versionVerb(
        verb: String,
        node: Node,
    ): Response = fx.send("POST", "/api/v1/templates/version/$verb", MAPPER.writeValueAsString(mapOf("name" to node.name, "version" to 1)))

    /** Plants [node] v1 at [status] by SQL (a discard carries its stamp); exactly one row. */
    private fun plant(
        node: Node,
        status: String,
    ) = connect().use { c ->
        c
            .prepareStatement(
                "UPDATE template_versions tv SET status = ?, discarded_by = NULL," +
                    " discarded_at = CASE WHEN ?::text = 'DISCARDED' THEN NOW() END FROM templates t" +
                    " WHERE t.id = tv.template_id AND t.workspace_id = ?::uuid AND t.name = ? AND tv.version = 1",
            ).use { s ->
                listOf(status, status, WORKSPACE_ID, node.name).forEachIndexed { i, arg -> s.setString(i + 1, arg) }
                withClue("the plant flips exactly one version") { s.executeUpdate() shouldBe 1 }
            }
    }

    // ---- release ----------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `(a) a DRAFT library selected in the plan releases first, then the importer - one release`() {
        prefix("a")
        val library = lib("lib", "one")
        val root = importer("report", listOf(library), "<@lib0.one/>")
        val edges = listOf(Edge(root, library, "imports[0]"))
        val audit = lastAuditId()

        val released = releaseSelecting(root, edges, listOf(library))

        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        releasedAfter(audit) shouldContainExactly listOf(library.name, root.name)
        status(library) shouldBe "RELEASED"
        status(root) shouldBe "RELEASED"
    }

    @Test
    @Order(2)
    fun `(b) an unticked DRAFT library refuses dependency_not_released and nothing flips`() {
        prefix("b")
        val library = lib("lib", "one")
        val root = importer("report", listOf(library), "<@lib0.one/>")
        val edges = listOf(Edge(root, library, "imports[0]"))
        val audit = lastAuditId()

        val response = refused(releaseSelecting(root, edges, emptyList()), CONFLICT, "template.release.dependency_not_released")

        response.jsonPath().getString("error.details.blockers[0].name") shouldBe library.name
        response.jsonPath().getString("error.details.blockers[0].status") shouldBe "DRAFT"
        status(library) shouldBe "DRAFT"
        status(root) shouldBe "DRAFT"
        releasedAfter(audit) shouldBe emptyList()
    }

    @Test
    @Order(3)
    fun `(c) a DISCARDED library is a blocker and is never restored for you`() {
        prefix("c")
        val library = lib("lib", "one")
        val root = importer("report", listOf(library), "<@lib0.one/>")
        // A DRAFT importer is a live pin, so the product refuses this discard: the state is planted by SQL.
        plant(library, "DISCARDED")
        val edges = listOf(Edge(root, library, "imports[0]"))

        val response = refused(releaseSelecting(root, edges, emptyList()), CONFLICT, "template.release.dependency_not_released")

        response.jsonPath().getString("error.details.blockers[0].name") shouldBe library.name
        response.jsonPath().getString("error.details.blockers[0].status") shouldBe "DISCARDED"
        status(library) shouldBe "DISCARDED"
        status(root) shouldBe "DRAFT"
    }

    @Test
    @Order(4)
    fun `(d) the walk goes beneath a RELEASED library - its DRAFT grand-library blocks until it is selected`() {
        prefix("d")
        val grand = lib("grand", "two")
        val library = lib("lib", "one", listOf(grand), "<#macro one><@lib0.two/></#macro>")
        val libraryEdges = listOf(Edge(library, grand, "imports[0]"))
        releaseSelecting(library, libraryEdges, listOf(grand)).statusCode shouldBe OK
        // Unreachable through the product (a RELEASED library pins its import): the grand-library is planted DRAFT.
        plant(grand, "DRAFT")
        val root = importer("report", listOf(library), "<@lib0.one/>")
        val edges = listOf(Edge(root, library, "imports[0]"), Edge(library, grand, "imports[0]"))
        val audit = lastAuditId()

        val response = refused(releaseSelecting(root, edges, emptyList()), CONFLICT, "template.release.dependency_not_released")
        response.jsonPath().getString("error.details.blockers[0].name") shouldBe grand.name
        status(root) shouldBe "DRAFT"

        val released = releaseSelecting(root, edges, listOf(grand))

        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        releasedAfter(audit) shouldContainExactly listOf(grand.name, root.name)
        listOf(root, library, grand).map(::status) shouldBe listOf("RELEASED", "RELEASED", "RELEASED")
    }

    @Test
    @Order(5)
    fun `(e) a diamond loads the shared library once with two edge occurrences and releases it once`() {
        prefix("e")
        val common = lib("common", "c")
        val dates = lib("dates", "d", listOf(common), "<#macro d><@lib0.c/></#macro>")
        val root = importer("report", listOf(dates, common), "<@lib0.d/> UNION ALL <@lib1.c/>")
        // Three nodes, three edges: the hash below is the product's only if its walk drew exactly this graph.
        val edges = listOf(Edge(root, dates, "imports[0]"), Edge(dates, common, "imports[0]"), Edge(root, common, "imports[1]"))
        fx.nodeIds(root, edges).size shouldBe 3
        val audit = lastAuditId()

        val released = releaseSelecting(root, edges, listOf(dates, common))

        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe OK }
        val rows = releasedAfter(audit)
        println("event=template_imports.diamond rows=$rows")
        rows.count { it == common.name } shouldBe 1
        rows shouldContainExactly listOf(common.name, dates.name, root.name)
    }

    @Test
    @Order(6)
    fun `(f) an import cycle planted by SQL is dependency_cycle and nothing flips`() {
        prefix("f")
        val back = lib("back", "b")
        val root = importer("loop", listOf(back), "<@lib0.b/>")
        // The save rules refuse a cycle: the library's stored imports are rewritten BY SQL to import its importer.
        connect().use { c ->
            c
                .prepareStatement(
                    "UPDATE template_versions tv SET imports_json = jsonb_build_array(jsonb_build_object('id', ?::text, 'version', 1," +
                        " 'alias', 'back')) FROM templates t WHERE t.id = tv.template_id AND t.workspace_id = ?::uuid AND t.name = ?" +
                        " AND tv.version = 1",
                ).use { s ->
                    s.setString(1, root.name)
                    s.setString(2, WORKSPACE_ID)
                    s.setString(3, back.name)
                    s.executeUpdate() shouldBe 1
                }
        }

        refused(
            fx.send("POST", "/api/v1/templates/release", MAPPER.writeValueAsString(mapOf("name" to root.name)), fx.row(root).hash),
            CONFLICT,
            "template.release.dependency_cycle",
        )

        status(root) shouldBe "DRAFT"
        status(back) shouldBe "DRAFT"
    }

    // ---- restore (§D.2) ---------------------------------------------------------------------------------------

    /** A RELEASED importer over a RELEASED library over a RELEASED grand-library, the importer then DISCARDED. */
    private fun discardedImporter(tag: String): Triple<Node, Node, Node> {
        prefix(tag)
        val grand = lib("grand", "two")
        val library = lib("lib", "one", listOf(grand), "<#macro one><@lib0.two/></#macro>")
        val root = importer("report", listOf(library), "<@lib0.one/>")
        val edges = listOf(Edge(root, library, "imports[0]"), Edge(library, grand, "imports[0]"))
        withClue("closure release") { releaseSelecting(root, edges, listOf(library, grand)).statusCode shouldBe OK }
        versionVerb("discard", root).then().statusCode(OK)
        return Triple(root, library, grand)
    }

    @Test
    @Order(7)
    fun `restore - over a released import closure the DISCARDED importer restores`() {
        val (root, _, _) = discardedImporter("restore_ok")

        versionVerb("restore", root).then().statusCode(OK)

        status(root) shouldBe "RELEASED"
    }

    @Test
    @Order(8)
    fun `restore - a DRAFT grand-library beneath the RELEASED library refuses 409 with path and via, nothing written`() {
        val (root, library, grand) = discardedImporter("restore_draft")
        plant(grand, "DRAFT")

        val response = refused(versionVerb("restore", root), CONFLICT, "template.release.dependency_not_released")

        val json = response.jsonPath()
        json.getString("error.details.blockers[0].name") shouldBe grand.name
        json.getList<String>("error.details.blockers[0].paths") shouldBe listOf("imports[0].imports[0]")
        json.getString("error.details.blockers[0].via[0].name") shouldBe library.name
        status(root) shouldBe "DISCARDED"
        plant(grand, "RELEASED")
        versionVerb("restore", root).then().statusCode(OK)
    }

    @Test
    @Order(9)
    fun `restore - an import discarded while the importer was DISCARDED is the save rules' 400 import_not_found, reason discarded`() {
        val (root, library, _) = discardedImporter("restore_discarded")
        withClue("a DISCARDED importer protects nothing: the library's discard is the product's") {
            versionVerb("discard", library).then().statusCode(OK)
        }

        val response = refused(versionVerb("restore", root), BAD_REQUEST, "template.validation.import_not_found")

        response.jsonPath().getString("error.details.failures[0].details.reason") shouldBe "discarded"
        status(root) shouldBe "DISCARDED"
        status(library) shouldBe "DISCARDED"
    }

    @AfterAll
    fun shutdown() {
        oidc.close()
    }

    companion object {
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val CONFLICT = 409
        private const val EXCERPT = 1200
        private const val WORKSPACE = "template-imports-873"
        private val MAPPER =
            com.fasterxml.jackson.databind
                .ObjectMapper()

        /** Names are unique forever in a persisted metadata database: every run takes a fresh folder. */
        private val RUN: String = "r" + UUID.randomUUID().toString().substring(0, 8)
        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-873-imports@datapipelines.test", WORKSPACE)

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
                        "INSERT INTO workspaces (id, name, display_name)" +
                            " VALUES ('$WORKSPACE_ID', '$WORKSPACE-$RUN', 'Template imports 873')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-873-imports-$RUN@datapipelines.test', 'E2E 873', 'test'," +
                            " 'e2e-873-imports-$RUN', TRUE, TRUE)",
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
