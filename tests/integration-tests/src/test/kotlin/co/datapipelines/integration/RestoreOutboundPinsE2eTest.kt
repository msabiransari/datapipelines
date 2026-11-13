package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #517 round three / #719 — RESTORE rechecks its outbound pins under the workspace writer guard (release-dependencies
 * record §12.1 "Restore DISCARDED", RULED 2026-10-25), over the REAL wired application and the shared test Postgres.
 *
 * A restore lands a version RELEASED without a human's consent, and a DISCARDED version protects nothing: what it pins may
 * have been discarded since. A missing or DISCARDED pin refuses with the family's validation code (400); a present but
 * DRAFT pin refuses `<family>.release.dependency_not_released` (409) — unreachable today, so its witness plants the state
 * by SQL and says so. Every refusal leaves the version DISCARDED and commits no restore audit row.
 *
 * The races (brief A.3) are forced, never timed: the test holds a lock on its own connection, waits until
 * `pg_stat_activity` (read on a fresh connection) shows the writer under test waiting on it, then lets go.
 * - **Order A (restore first):** the restore's version row is locked, so the restore stops at its write AFTER the
 *   recheck with the guard held; a dependency discard fired then must wait on the restore's guard, and once the restore
 *   commits it is refused (`pipeline.version.pinned` / `template.in_use`) — the dependency stays RELEASED.
 * - **Order B (discard first):** a connection holding the guard discards the dependency (status by SQL — the property
 *   under test is the lock and the status the recheck reads); the restore must wait on the guard, and after the commit
 *   the recheck sees the discard and refuses 400 with nothing written.
 *
 * #873 §D — the visualization and dashboard arms, and the TRANSITIVE recheck (record §25.2 row G2, §25.4 D15): the
 * restore walks the stored body's whole closure, so a DRAFT beneath a RELEASED visualization blocks a dashboard restore
 * (409 `dashboard.release.dependency_not_released`, `path` and `via`) and a DISCARDED one keeps the native 400 at the
 * pin it hides under. A node below a RELEASED pin cannot leave RELEASED through the product (its own pin guard refuses),
 * so those states are planted by SQL on an actually DISCARDED root and reverted after; each refusal is followed by the
 * same restore succeeding once the plant is gone, so the refusal is the plant's doing. The four race molds run again
 * for the new arms; order B for the dashboard discards a template TWO levels down, so the walk is what sees it.
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // one ordered scenario per restore arm of every family, sharing one booted context
class RestoreOutboundPinsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newFixedThreadPool(2)
    private var rpcId = 0

    // ---- wire helpers -----------------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val id = synchronized(this) { ++rpcId }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to id,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        val result = body["result"]
        withClue("$name must succeed: ${response.body()}") {
            body.has("error") shouldBe false
            result.path("isError").asBoolean(false) shouldBe false
        }
        return mapper.readTree(result["content"][0]["text"].asText())
    }

    private fun rest(): RequestSpecification = given().port(port).asSession(SESSION)

    /** #873 — the C fixture's REST authoring and SQL reads, over this class's workspace and datasource. */
    private val fx by lazy { FamilyRootsFixture(port, SESSION, WORKSPACE_ID, DATASOURCE) }

    /** A template created over MCP and released over REST; version 1 RELEASED. */
    private fun releasedTemplate(name: String) {
        val created =
            callTool(
                "templates_create",
                mapOf(
                    "id" to name,
                    "dialect" to "H2",
                    "display_name" to "Restore pins (517c)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to SELECTOR_BODY,
                    "confirm_new_root" to true,
                ),
            )
        rest()
            .header("If-Match", created["body_hash"].asText())
            .contentType(ContentType.JSON)
            .body(mapOf("name" to name))
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun templateNode(template: String): Map<String, Any?> =
        mapOf(
            "id" to "n0",
            "type" to "DQL",
            "source" to DATASOURCE,
            "description" to "One row",
            "template" to mapOf("id" to template, "version" to 1),
            "depends_on" to emptyList<String>(),
        )

    private fun childNode(child: String): Map<String, Any?> =
        mapOf(
            "id" to "child",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to child, "version" to 1),
            "depends_on" to emptyList<String>(),
        )

    /** A pipeline created over MCP and released over REST (its pins already RELEASED); answers its id. */
    private fun releasedPipeline(
        name: String,
        node: Map<String, Any?>,
    ): String {
        val created =
            callTool(
                "pipelines_create",
                mapOf(
                    "name" to name,
                    "display_name" to "Restore pins (517c)",
                    "description" to "One pinning node.",
                    "nodes" to listOf(node),
                    "confirm_new_root" to true,
                ),
            )
        val id = created["id"].asText()
        rest()
            .header("If-Match", created["draft"]["body_hash"].asText())
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    /** A set whose one selector pins [template] v1, created and released over REST; answers its id. */
    private fun releasedSet(
        name: String,
        template: String,
    ): String {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "name" to name,
                    "display_name" to "Restore pins (517c)",
                    "description" to "One template-backed selector.",
                    "parameters" to
                        listOf(
                            mapOf(
                                "name" to "country",
                                "label" to "Country",
                                "type" to "STRING",
                                "kind" to "SELECT",
                                "cardinality" to "SINGLE",
                                "required" to true,
                                "source" to mapOf("template" to mapOf("id" to template, "version" to 1), "datasource" to DATASOURCE),
                                "presentation" to mapOf("control" to "dropdown"),
                            ),
                        ),
                ),
            )
        val created = rest().contentType(ContentType.JSON).body(body).post("/api/v1/parameter-sets")
        withClue("set create: ${created.body.asString()}") { created.statusCode shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        rest()
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .body("")
            .post("/api/v1/parameter-sets/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    private fun discardPipeline(id: String): Response = rest().post("/api/v1/pipelines/$id/versions/1/discard")

    private fun restorePipeline(id: String): Response = rest().post("/api/v1/pipelines/$id/versions/1/restore")

    private fun discardSet(id: String): Response = rest().post("/api/v1/parameter-sets/$id/versions/1/discard")

    private fun restoreSet(id: String): Response = rest().post("/api/v1/parameter-sets/$id/versions/1/restore")

    private fun discardTemplate(name: String): Response =
        rest().contentType(ContentType.JSON).body(mapOf("name" to name, "version" to 1)).post("/api/v1/templates/version/discard")

    // ---- table reads ------------------------------------------------------------------------------------------

    private fun <T> sql(block: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    private fun scalar(
        query: String,
        vararg args: String,
    ): String? =
        sql { connection ->
            connection.prepareStatement(query).use { ps ->
                args.forEachIndexed { index, arg -> ps.setString(index + 1, arg) }
                ps.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    private fun pipelineStatus(id: String): String? =
        scalar("SELECT status FROM pipeline_versions WHERE pipeline_id = ?::uuid AND version = 1", id)

    private fun setStatus(id: String): String? =
        scalar("SELECT status FROM parameter_set_versions WHERE parameter_set_id = ?::uuid AND version = 1", id)

    private fun templateStatus(name: String): String? =
        scalar(
            "SELECT tv.status FROM template_versions tv JOIN templates t ON t.id = tv.template_id" +
                " WHERE t.workspace_id = ?::uuid AND t.name = ? AND tv.version = 1",
            WORKSPACE_ID,
            name,
        )

    /** Restore audit rows (any family) naming [id], read at the TABLE. */
    private fun restoreAudits(id: String): Int =
        checkNotNull(
            scalar("SELECT count(*) FROM audit_log WHERE event LIKE '%.version.restored' AND details_json::text LIKE ?", "%$id%"),
        ).toInt()

    // ---- the lock holders and the waiter probes ---------------------------------------------------------------

    private class Holder(
        val connection: Connection,
        val pid: Int,
    ) : AutoCloseable {
        override fun close() {
            if (!connection.isClosed) {
                connection.rollback()
                connection.close()
            }
        }
    }

    private fun openHolder(): Holder {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        val pid =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }
        return Holder(connection, pid)
    }

    private fun Holder.lock(
        statement: String,
        vararg args: String,
    ): Holder =
        apply {
            connection.prepareStatement(statement).use { ps ->
                args.forEachIndexed { index, arg -> ps.setString(index + 1, arg) }
                ps.executeQuery().use { rows -> withClue("the row to hold exists: $statement") { rows.next() shouldBe true } }
            }
        }

    /** A backend waiting on a lock [holderPid] owns — [guardOnly] narrows to the guard's statement. Fresh connection. */
    private fun waiter(
        holderPid: Int,
        guardOnly: Boolean,
    ): Int? =
        scalar(
            "SELECT pid FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ?::int = ANY(pg_blocking_pids(pid))" +
                (if (guardOnly) " AND query LIKE 'SELECT id FROM workspaces WHERE id = % FOR NO KEY UPDATE'" else "") +
                " ORDER BY pid LIMIT 1",
            holderPid.toString(),
        )?.toInt()

    /** Polls until a backend waits on [holderPid] (the writer under test), or [writer] answers first. */
    private fun awaitWaiter(
        holderPid: Int,
        writer: CompletableFuture<*>,
        guardOnly: Boolean,
    ): Int? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
        while (!writer.isDone && System.nanoTime() < deadline) {
            waiter(holderPid, guardOnly)?.let { return it }
            Thread.sleep(PROBE_INTERVAL_MS)
        }
        return null
    }

    /**
     * Order A: [restore] runs into the version row [versionRowLock] holds — after its recheck, with the guard held — and
     * [dependencyDiscard] fired then must wait on the RESTORE's guard. Answers (restore, discard).
     */
    private fun restoreFirst(
        versionRowLock: Pair<String, String>,
        restore: () -> Response,
        dependencyDiscard: () -> Response,
    ): Pair<Response, Response> =
        openHolder().lock(versionRowLock.first, versionRowLock.second).use { holder ->
            val restored = CompletableFuture.supplyAsync({ restore() }, pool)
            val restorePid = awaitWaiter(holder.pid, restored, guardOnly = false)
            withClue("the restore must reach its write and wait on the held version row (answered first: ${restored.isDone})") {
                (restorePid != null) shouldBe true
            }
            val discarded = CompletableFuture.supplyAsync({ dependencyDiscard() }, pool)
            withClue("the dependency discard must wait on the guard the restore holds (answered first: ${discarded.isDone})") {
                (awaitWaiter(checkNotNull(restorePid), discarded, guardOnly = true) != null) shouldBe true
            }
            restored.isDone shouldBe false
            holder.connection.rollback()
            restored.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS) to discarded.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    /** Order B: a holder takes the guard and discards the dependency uncommitted; [restore] must wait, then sees it. */
    private fun discardFirst(
        dependencyDiscardSql: Pair<String, String>,
        restore: () -> Response,
    ): Response =
        openHolder().lock("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE", WORKSPACE_ID).use { holder ->
            holder.connection.prepareStatement(dependencyDiscardSql.first).use { ps ->
                ps.setString(1, ADMIN_ID)
                ps.setString(2, dependencyDiscardSql.second)
                withClue("the planted discard flips exactly one version") { ps.executeUpdate() shouldBe 1 }
            }
            val restored = CompletableFuture.supplyAsync({ restore() }, pool)
            withClue("the restore must wait on the guard the discard holds (answered first: ${restored.isDone})") {
                (awaitWaiter(holder.pid, restored, guardOnly = true) != null) shouldBe true
            }
            holder.connection.commit()
            restored.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    // ---- the cases --------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - an owned H2 datasource`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        rest()
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Restore pins (517c)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .statusCode(201)
        // Non-vacuity of the probes: nobody waits on anything before a race starts.
        openHolder().use { holder -> waiter(holder.pid, guardOnly = false) shouldBe null }
    }

    @Test
    @Order(2)
    fun `a child pipeline discarded after its parent refuses the parent's restore 400 - repair is bottom-up`() {
        val template = "test/restore_517c/$RUN/child.sql"
        releasedTemplate(template)
        val child = releasedPipeline("test/restore_517c/$RUN/child", templateNode(template))
        val parent = releasedPipeline("test/restore_517c/$RUN/parent", childNode("test/restore_517c/$RUN/child"))
        discardPipeline(parent).then().statusCode(200)
        discardPipeline(child).then().statusCode(200)

        val refused = restorePipeline(parent)

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "pipeline.validation.pipeline_reference_not_released"
            refused.jsonPath().getString("error.details.failures[0].path") shouldBe "nodes[0].pipeline.version"
            refused.jsonPath().getString("error.details.failures[0].details.status") shouldBe "DISCARDED"
            pipelineStatus(parent) shouldBe "DISCARDED"
            restoreAudits(parent) shouldBe 0
        }

        restorePipeline(child).then().statusCode(200)
        restorePipeline(parent).then().statusCode(200)
        pipelineStatus(parent) shouldBe "RELEASED"
        restoreAudits(parent) shouldBe 1
    }

    @Test
    @Order(3)
    fun `defensive - a pin planted DRAFT by SQL refuses the restore 409 dependency_not_released in the shared shape`() {
        val template = "test/restore_517c/$RUN/planted.sql"
        releasedTemplate(template)
        val parent = releasedPipeline("test/restore_517c/$RUN/planted", templateNode(template))
        discardPipeline(parent).then().statusCode(200)
        // Unreachable through the product (a RELEASED version is never purged, names are unique forever): the stored body
        // is rewritten BY SQL to pin a fresh DRAFT template — the state the 409 branch defends against.
        val draft = "test/restore_517c/$RUN/draft.sql"
        callTool(
            "templates_create",
            mapOf(
                "id" to draft,
                "dialect" to "H2",
                "display_name" to "Draft",
                "description" to "Expects: nothing.",
                "body" to SELECTOR_BODY,
                "confirm_new_root" to true,
            ),
        )
        sql { connection ->
            connection
                .prepareStatement(
                    "UPDATE pipeline_versions SET body_json = jsonb_set(body_json, '{nodes,0,template,id}', to_jsonb(?::text))" +
                        " WHERE pipeline_id = ?::uuid AND version = 1",
                ).use { ps ->
                    ps.setString(1, draft)
                    ps.setString(2, parent)
                    ps.executeUpdate() shouldBe 1
                }
        }

        val refused = restorePipeline(parent)

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 409
            val json = refused.jsonPath()
            json.getString("error.code") shouldBe "pipeline.release.dependency_not_released"
            json.getString("error.details.root.family") shouldBe "pipeline"
            json.getString("error.details.blockers[0].family") shouldBe "template"
            json.getString("error.details.blockers[0].name") shouldBe draft
            json.getString("error.details.blockers[0].status") shouldBe "DRAFT"
            json.getString("error.details.blockers[0].reason") shouldBe "not_released"
            json.getList<String>("error.details.blockers[0].paths") shouldBe listOf("nodes[0].template")
            json.getBoolean("error.details.blockers_truncated") shouldBe false
            pipelineStatus(parent) shouldBe "DISCARDED"
            restoreAudits(parent) shouldBe 0
        }
    }

    @Test
    @Order(4)
    fun `a set whose selector template was discarded after it refuses the restore 400 - nothing written`() {
        val template = "test/restore_517c/$RUN/selector.sql"
        releasedTemplate(template)
        val set = releasedSet("test/restore_517c/$RUN/filters", template)
        discardSet(set).then().statusCode(200)
        discardTemplate(template).then().statusCode(200)

        val refused = restoreSet(set)

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "parameter.validation.template_version_not_found"
            refused.jsonPath().getString("error.details.failures[0].path") shouldBe "parameters[0].source.template"
            refused.jsonPath().getString("error.details.failures[0].details.reason") shouldBe "discarded"
            setStatus(set) shouldBe "DISCARDED"
            templateStatus(template) shouldBe "DISCARDED"
            restoreAudits(set) shouldBe 0
        }
    }

    @Test
    @Order(5)
    fun `race order A, pipeline - a child discard fired during a restore waits on its guard and is then refused pinned`() {
        val template = "test/restore_517c/$RUN/race_a.sql"
        releasedTemplate(template)
        val child = releasedPipeline("test/restore_517c/$RUN/race_a_child", templateNode(template))
        val parent = releasedPipeline("test/restore_517c/$RUN/race_a_parent", childNode("test/restore_517c/$RUN/race_a_child"))
        discardPipeline(parent).then().statusCode(200)

        val (restored, discarded) =
            restoreFirst(
                "SELECT 1 FROM pipeline_versions WHERE pipeline_id = ?::uuid AND version = 1 FOR UPDATE" to parent,
                { restorePipeline(parent) },
                { discardPipeline(child) },
            )

        withClue("restore ${restored.statusCode}; discard ${discarded.statusCode} ${discarded.body.asString()}") {
            restored.statusCode shouldBe 200
            discarded.statusCode shouldBe 409
            discarded.jsonPath().getString("error.code") shouldBe "pipeline.version.pinned"
            pipelineStatus(parent) shouldBe "RELEASED"
            pipelineStatus(child) shouldBe "RELEASED"
        }
    }

    @Test
    @Order(6)
    fun `race order A, set - a template discard fired during a restore waits on its guard and is then refused in use`() {
        val template = "test/restore_517c/$RUN/race_a_set.sql"
        releasedTemplate(template)
        val set = releasedSet("test/restore_517c/$RUN/race_a_filters", template)
        discardSet(set).then().statusCode(200)

        val (restored, discarded) =
            restoreFirst(
                "SELECT 1 FROM parameter_set_versions WHERE parameter_set_id = ?::uuid AND version = 1 FOR UPDATE" to set,
                { restoreSet(set) },
                { discardTemplate(template) },
            )

        withClue("restore ${restored.statusCode}; discard ${discarded.statusCode} ${discarded.body.asString()}") {
            restored.statusCode shouldBe 200
            discarded.statusCode shouldBe 409
            discarded.jsonPath().getString("error.code") shouldBe "template.in_use"
            setStatus(set) shouldBe "RELEASED"
            templateStatus(template) shouldBe "RELEASED"
        }
    }

    @Test
    @Order(7)
    fun `race order B, pipeline - a restore fired during a template discard waits on the guard, then refuses 400`() {
        val template = "test/restore_517c/$RUN/race_b.sql"
        releasedTemplate(template)
        val parent = releasedPipeline("test/restore_517c/$RUN/race_b", templateNode(template))
        discardPipeline(parent).then().statusCode(200)

        val refused = discardFirst(templateDiscardSql(template)) { restorePipeline(parent) }

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "pipeline.validation.template_version_not_found"
            pipelineStatus(parent) shouldBe "DISCARDED"
            restoreAudits(parent) shouldBe 0
        }
    }

    @Test
    @Order(8)
    fun `race order B, set - a restore fired during a template discard waits on the guard, then refuses 400`() {
        val template = "test/restore_517c/$RUN/race_b_set.sql"
        releasedTemplate(template)
        val set = releasedSet("test/restore_517c/$RUN/race_b_filters", template)
        discardSet(set).then().statusCode(200)

        val refused = discardFirst(templateDiscardSql(template)) { restoreSet(set) }

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "parameter.validation.template_version_not_found"
            setStatus(set) shouldBe "DISCARDED"
            restoreAudits(set) shouldBe 0
        }
    }

    // ---- #873 §D: the visualization and dashboard arms, and the transitive walk ----------------------------------

    private fun discardArtifact(node: FamilyRootsFixture.Node): Response =
        rest().post("/api/v1/${ARTIFACT_ROUTES.getValue(node.family)}/${fx.row(node).uuid}/versions/1/discard")

    private fun restoreArtifact(node: FamilyRootsFixture.Node): Response =
        rest().post("/api/v1/${ARTIFACT_ROUTES.getValue(node.family)}/${fx.row(node).uuid}/versions/1/restore")

    /** [family]'s closure under a fresh prefix, released through the plan path, its root then DISCARDED over REST. */
    private fun discardedClosure(
        family: String,
        tag: String,
    ): Pair<FamilyRootsFixture.Node, List<FamilyRootsFixture.Edge>> {
        val prefix = "test/restore_873/$RUN/$tag"
        val (root, edges) = if (family == "dashboard") fx.dashboardClosure(prefix) else fx.visualizationClosure(prefix)
        val released = fx.releaseWithPlan(root, edges)
        withClue("closure release: ${released.body.asString().take(EXCERPT)}") { released.statusCode shouldBe 200 }
        discardArtifact(root).then().statusCode(200)
        return root to edges
    }

    /** Plants [template] v1 at [status] by SQL (a discard carries its stamp; anything else clears it); exactly one row. */
    private fun plantTemplate(
        template: String,
        status: String,
    ) = sql { connection ->
        connection
            .prepareStatement(
                "UPDATE template_versions tv SET status = ?, discarded_by = NULL," +
                    " discarded_at = CASE WHEN ?::text = 'DISCARDED' THEN NOW() END FROM templates t" +
                    " WHERE t.id = tv.template_id AND t.workspace_id = ?::uuid AND t.name = ? AND tv.version = 1",
            ).use { ps ->
                listOf(status, status, WORKSPACE_ID, template).forEachIndexed { index, arg -> ps.setString(index + 1, arg) }
                withClue("the plant flips exactly one version") { ps.executeUpdate() shouldBe 1 }
            }
    }

    private fun List<FamilyRootsFixture.Edge>.target(path: String) = single { it.path == path }.to

    @Test
    @Order(9)
    fun `a dashboard over a released closure restores - a DRAFT beneath its RELEASED visualization refuses 409 with path and via`() {
        val (board, edges) = discardedClosure("dashboard", "draft_below")
        val boardId = fx.row(board).uuid
        restoreArtifact(board).then().statusCode(200)
        restoreAudits(boardId) shouldBe 1
        discardArtifact(board).then().statusCode(200)
        val chart = edges.target("visualizations[0].visualization")
        val shape = edges.target("transform.template")
        plantTemplate(shape.name, "DRAFT")

        val refused = restoreArtifact(board)

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 409
            val json = refused.jsonPath()
            json.getString("error.code") shouldBe "dashboard.release.dependency_not_released"
            json.getString("error.details.root.name") shouldBe board.name
            json.getString("error.details.blockers[0].family") shouldBe "template"
            json.getString("error.details.blockers[0].name") shouldBe shape.name
            json.getString("error.details.blockers[0].status") shouldBe "DRAFT"
            json.getString("error.details.blockers[0].reason") shouldBe "not_released"
            json.getList<String>("error.details.blockers[0].paths") shouldBe listOf("visualizations[0].visualization.transform.template")
            json.getString("error.details.blockers[0].via[0].family") shouldBe "visualization"
            json.getString("error.details.blockers[0].via[0].name") shouldBe chart.name
            json.getList<Any>("error.details.blockers").size shouldBe 1
            fx.row(board).status shouldBe "DISCARDED"
            restoreAudits(boardId) shouldBe 1
        }
        plantTemplate(shape.name, "RELEASED")
        restoreArtifact(board).then().statusCode(200)
        restoreAudits(boardId) shouldBe 2
    }

    @Test
    @Order(10)
    fun `a DISCARDED template beneath the dashboard's RELEASED visualization keeps the native 400 at that pin, with path and via`() {
        val (board, edges) = discardedClosure("dashboard", "discarded_below")
        val chart = edges.target("visualizations[0].visualization")
        val shape = edges.target("transform.template")
        plantTemplate(shape.name, "DISCARDED")

        val refused = restoreArtifact(board)

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            val json = refused.jsonPath()
            json.getString("error.code") shouldBe "dashboard.validation.dependency_not_found"
            json.getString("error.details.failures[0].path") shouldBe "visualizations[0].visualization"
            json.getString("error.details.failures[0].details.kind") shouldBe "template"
            json.getString("error.details.failures[0].details.name") shouldBe shape.name
            json.getString("error.details.failures[0].details.status") shouldBe "DISCARDED"
            json.getString("error.details.failures[0].details.path") shouldBe "visualizations[0].visualization.transform.template"
            json.getString("error.details.failures[0].details.via[0].name") shouldBe chart.name
            fx.row(board).status shouldBe "DISCARDED"
            restoreAudits(fx.row(board).uuid) shouldBe 0
        }
        plantTemplate(shape.name, "RELEASED")
        restoreArtifact(board).then().statusCode(200)
        fx.row(board).status shouldBe "RELEASED"
    }

    @Test
    @Order(11)
    fun `a visualization's DRAFT transform template refuses 409 with no via, a DISCARDED one the native 400 - then it restores`() {
        val (chart, edges) = discardedClosure("visualization", "viz_pin")
        val shape = edges.target("transform.template")
        plantTemplate(shape.name, "DRAFT")

        val draft = restoreArtifact(chart)

        withClue("restore ${draft.statusCode} ${draft.body.asString()}") {
            draft.statusCode shouldBe 409
            draft.jsonPath().getString("error.code") shouldBe "visualization.release.dependency_not_released"
            draft.jsonPath().getList<String>("error.details.blockers[0].paths") shouldBe listOf("transform.template")
            draft.jsonPath().getString("error.details.blockers[0].via") shouldBe null
        }
        plantTemplate(shape.name, "DISCARDED")
        val discarded = restoreArtifact(chart)
        withClue("restore ${discarded.statusCode} ${discarded.body.asString()}") {
            discarded.statusCode shouldBe 400
            discarded.jsonPath().getString("error.code") shouldBe "visualization.validation.transform_binding_invalid"
            discarded.jsonPath().getString("error.details.failures[0].details.reason") shouldBe "template_version_not_found"
            fx.row(chart).status shouldBe "DISCARDED"
            restoreAudits(fx.row(chart).uuid) shouldBe 0
        }
        plantTemplate(shape.name, "RELEASED")
        restoreArtifact(chart).then().statusCode(200)
        restoreAudits(fx.row(chart).uuid) shouldBe 1
    }

    @Test
    @Order(12)
    fun `race order A, visualization - a template discard fired during its restore waits on the guard, then is refused`() {
        val (chart, edges) = discardedClosure("visualization", "race_a_viz")
        val shape = edges.target("transform.template")

        val (restored, discarded) =
            restoreFirst(
                "SELECT 1 FROM visualization_versions WHERE visualization_id = ?::uuid AND version = 1 FOR UPDATE" to fx.row(chart).uuid,
                { restoreArtifact(chart) },
                { discardTemplate(shape.name) },
            )

        withClue("restore ${restored.statusCode}; discard ${discarded.statusCode} ${discarded.body.asString()}") {
            restored.statusCode shouldBe 200
            discarded.statusCode shouldBe 409
            discarded.jsonPath().getString("error.code") shouldBe "template.in_use"
            fx.row(chart).status shouldBe "RELEASED"
            templateStatus(shape.name) shouldBe "RELEASED"
        }
    }

    @Test
    @Order(13)
    fun `race order A, dashboard - a visualization discard fired during its restore waits on the guard, then is refused pinned`() {
        val (board, edges) = discardedClosure("dashboard", "race_a_board")
        val chart = edges.target("visualizations[0].visualization")

        val (restored, discarded) =
            restoreFirst(
                "SELECT 1 FROM dashboard_versions WHERE dashboard_id = ?::uuid AND version = 1 FOR UPDATE" to fx.row(board).uuid,
                { restoreArtifact(board) },
                { discardArtifact(chart) },
            )

        withClue("restore ${restored.statusCode}; discard ${discarded.statusCode} ${discarded.body.asString()}") {
            restored.statusCode shouldBe 200
            discarded.statusCode shouldBe 409
            discarded.jsonPath().getString("error.code") shouldBe "visualization.version.pinned"
            fx.row(board).status shouldBe "RELEASED"
            fx.row(chart).status shouldBe "RELEASED"
        }
    }

    @Test
    @Order(14)
    fun `race order B, visualization - a restore fired during a template discard waits on the guard, then refuses 400`() {
        val (chart, edges) = discardedClosure("visualization", "race_b_viz")

        val refused = discardFirst(templateDiscardSql(edges.target("transform.template").name)) { restoreArtifact(chart) }

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "visualization.validation.transform_binding_invalid"
            fx.row(chart).status shouldBe "DISCARDED"
            restoreAudits(fx.row(chart).uuid) shouldBe 0
        }
    }

    @Test
    @Order(15)
    fun `race order B, dashboard - a template discarded two levels down under the guard is what the walk then sees, 400`() {
        val (board, edges) = discardedClosure("dashboard", "race_b_board")
        val shape = edges.target("transform.template")

        val refused = discardFirst(templateDiscardSql(shape.name)) { restoreArtifact(board) }

        withClue("restore ${refused.statusCode} ${refused.body.asString()}") {
            refused.statusCode shouldBe 400
            refused.jsonPath().getString("error.code") shouldBe "dashboard.validation.dependency_not_found"
            refused.jsonPath().getString("error.details.failures[0].details.name") shouldBe shape.name
            refused.jsonPath().getString("error.details.failures[0].details.path") shouldBe
                "visualizations[0].visualization.transform.template"
            fx.row(board).status shouldBe "DISCARDED"
            restoreAudits(fx.row(board).uuid) shouldBe 0
        }
    }

    /** The template's v1 flipped DISCARDED with its stamps — parameters: the actor, then the template name. */
    private fun templateDiscardSql(template: String): Pair<String, String> =
        "UPDATE template_versions tv SET status = 'DISCARDED', discarded_at = NOW(), discarded_by = ?::uuid FROM templates t" +
            " WHERE t.id = tv.template_id AND t.workspace_id = '$WORKSPACE_ID'::uuid AND t.name = ? AND tv.version = 1" to template

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val DATASOURCE = "h2-restore-517c"
        private const val H2_JDBC_URL = "jdbc:h2:mem:restore517c;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "restore-517c"
        private const val SELECTOR_BODY =
            "SELECT 'US' AS \"value\", 'United States' AS \"display_value\", TRUE AS \"is_default\" ORDER BY 1"

        /** The probe gives up before the writer's own lock_timeout (5 s) would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L
        private const val EXCERPT = 600
        private val ARTIFACT_ROUTES = mapOf("visualization" to "visualizations", "dashboard" to "dashboards")

        /** Names are unique forever in a persisted metadata database: every run takes a fresh folder. */
        private val RUN: String = "r" + UUID.randomUUID().toString().substring(0, 8)
        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-517c-restore-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-517c-restore@datapipelines.test", WORKSPACE)

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
            // API mode: this suite only authors — no dispatching context may take other suites' runs.
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @BeforeAll
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE-$RUN', 'Restore pins 517c')",
                    )
                    // A super admin (instance datasources) who is also this workspace's admin (an author there).
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-517c-$RUN@datapipelines.test', 'E2E 517c', 'test', 'e2e-517c-$RUN', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                            "VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', '${KEY.id}', " +
                            "TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_ID)
                        ps.setString(4, KEY.name)
                        ps.setString(5, KEY.hash)
                        ps.setString(6, WORKSPACE_ID)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
