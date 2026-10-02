package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * **The reverse arrows over the real HTTP stack (#320, the #10 follow-up)** — a template, pipeline or parameter-set
 * version that a visualization or dashboard pins can no longer be purged or discarded out from under it.
 *
 * Every rows-with-dependents fixture is seeded by SQL where the release gate (`ReleaseEvidence.NOT_INSTALLED`) or a
 * validator stands between the API and the state under test: nothing can release a visualization before L4, and a
 * validator would refuse the very dangling shapes this suite exists to refuse a purge for.
 *
 * One case per arrow, each with its UNPINNED control (the guard refuses only what something holds): a DRAFT template
 * a parameter set pins and one a visualization pins (the draft purge, `template.in_use`); a pipeline release a
 * RELEASED dashboard pins (`pipeline.version.pinned`, `referencing_dashboards`); a DRAFT set a DRAFT dashboard pins
 * (`parameter.in_use`, on the draft purge and the entity purge); the cross-workspace case (a same-named artifact in
 * another workspace pins nothing here); `templates_used_by` listing the visualization; D7's restore; and the
 * exclusive-draft offer's kept report. A promoter — who cannot call any guarded verb (auth §7.6) — is shown the
 * lens through `templates_used_by`: a visualization no admitted dashboard pins is never named.
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
class DependencyGuardsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /** Every refusal the walk met, by code — the non-vacuity count. */
    private val refusals = mutableListOf<String>()

    @Test
    @Order(1)
    fun `fixture - the workspace and its admin`() {
        seed()
    }

    @Test
    @Order(2)
    fun `a DRAFT template a parameter set pins cannot be purged through the draft route - the pre-existing gap`() {
        val hash = createTemplate(SET_PINNED_TEMPLATE)
        seedSet(SET_ID, SET_NAME, "DRAFT", SET_PINNED_TEMPLATE)

        val purge = post("/api/v1/templates/draft/discard", """{"name": "$SET_PINNED_TEMPLATE"}""", ifMatch = hash)
        refused(purge, 409) shouldBe "template.in_use"
        purge.jsonPath().getList<String>("error.details.referencing_parameter_sets") shouldContainExactly listOf(SET_NAME)
        templateExists(SET_PINNED_TEMPLATE) shouldBe true
    }

    @Test
    @Order(3)
    fun `the pipeline purge's exclusive-draft offer keeps a DRAFT template a parameter set also pins - the pre-existing gap`() {
        val hash = createTemplate(SHARED_TEMPLATE)
        check(hash.isNotEmpty())
        seedSet(OTHER_SET_ID, OTHER_SET_NAME, "DRAFT", SHARED_TEMPLATE)
        seedDraftPipeline(PIPELINE_ID, PIPELINE_NAME, SHARED_TEMPLATE)

        val purged = delete("/api/v1/pipelines/$PIPELINE_ID?include_exclusive_draft_templates=true")
        withClue(purged.asString().take(EXCERPT)) { purged.statusCode shouldBe 200 }
        purged.jsonPath().getList<String>("data.exclusive_draft_templates") shouldContainExactly emptyList()
        purged.jsonPath().getBoolean("data.exclusive_draft_templates_purged") shouldBe false
        // D5: the response SAYS what was skipped and why, under the keys `template.in_use` uses.
        purged.jsonPath().getList<String>("data.kept_draft_templates.id") shouldContainExactly listOf(SHARED_TEMPLATE)
        purged.jsonPath().getList<String>("data.kept_draft_templates[0].referencing_parameter_sets") shouldContainExactly
            listOf(OTHER_SET_NAME)
        templateExists(SHARED_TEMPLATE) shouldBe true
        // The pipeline itself WAS purged — the purge proceeds, only the offer is narrowed.
        get("/api/v1/pipelines/$PIPELINE_ID").statusCode shouldBe 404
    }

    @Test
    @Order(4)
    fun `a DRAFT template a visualization pins cannot be purged - and templates_used_by names the visualization`() {
        val hash = createTemplate(VIZ_PINNED_TEMPLATE)
        seedVisualization(VIZ_ID, VIZ_NAME, WORKSPACE_ID, "DRAFT", VIZ_PINNED_TEMPLATE)

        val purge = post("/api/v1/templates/draft/discard", """{"name": "$VIZ_PINNED_TEMPLATE"}""", ifMatch = hash)
        refused(purge, 409) shouldBe "template.in_use"
        purge.jsonPath().getList<String>("error.details.referencing_visualizations") shouldContainExactly listOf(VIZ_NAME)
        purge.jsonPath().getList<String>("error.details.pinned_by") shouldContainExactly emptyList()
        templateExists(VIZ_PINNED_TEMPLATE) shouldBe true

        // The reverse arrow's read side: `templates_used_by` lists the visualization as a real reference.
        val used = callTool(ADMIN_KEY, "templates_used_by", mapOf("id" to VIZ_PINNED_TEMPLATE, "version" to 1))
        used["pipeline_count"].asInt() shouldBe 0
        used["visualization_references"].map { it["visualization"].asText() } shouldContainExactly listOf(VIZ_NAME)
        used["visualization_references"][0]["visualization_version_status"].asText() shouldBe "DRAFT"
    }

    @Test
    @Order(5)
    fun `control - an unpinned DRAFT template purges - the guard refuses only what something holds`() {
        val hash = createTemplate(FREE_TEMPLATE)

        post("/api/v1/templates/draft/discard", """{"name": "$FREE_TEMPLATE"}""", ifMatch = hash).statusCode shouldBe 204

        templateExists(FREE_TEMPLATE) shouldBe false
    }

    @Test
    @Order(6)
    fun `a pipeline release a RELEASED dashboard pins cannot be discarded - the refusal names the dashboard`() {
        val pinned = post("/api/v1/pipelines/$PINNED_PIPELINE_ID/versions/1/discard", "")

        refused(pinned, 409) shouldBe "pipeline.version.pinned"
        pinned.jsonPath().getList<Any>("error.details.pinned_by") shouldContainExactly emptyList()
        pinned.jsonPath().getList<Map<String, Any>>("error.details.referencing_dashboards") shouldContainExactly
            listOf(mapOf("dashboard" to PIN_DASHBOARD, "version" to 1, "status" to "RELEASED"))
        rowStatus("pipeline_versions", "pipeline_id", PINNED_PIPELINE_ID) shouldBe "RELEASED"

        // The control: the same verb on a release nothing pins.
        post("/api/v1/pipelines/$FREE_PIPELINE_ID/versions/1/discard", "").statusCode shouldBe 200
        rowStatus("pipeline_versions", "pipeline_id", FREE_PIPELINE_ID) shouldBe "DISCARDED"
    }

    @Test
    @Order(7)
    fun `a DRAFT parameter set a DRAFT dashboard pins cannot be purged - the draft purge and the entity purge`() {
        val draftPurge = post("/api/v1/parameter-sets/$PINNED_SET_ID/draft/discard", "", ifMatch = "seeded-$PINNED_SET_ID")
        refused(draftPurge, 409) shouldBe "parameter.in_use"
        draftPurge.jsonPath().getList<Map<String, Any>>("error.details.pinned_by") shouldContainExactly
            listOf(mapOf("dashboard" to DRAFT_DASHBOARD, "version" to 1, "status" to "DRAFT"))

        val entityPurge = delete("/api/v1/parameter-sets/$PINNED_SET_ID")
        refused(entityPurge, 409) shouldBe "parameter.in_use"
        entityPurge.jsonPath().getString("error.details.version") shouldBe null
        rowStatus("parameter_set_versions", "parameter_set_id", PINNED_SET_ID) shouldBe "DRAFT"

        // The control: an unpinned draft-only set goes.
        delete("/api/v1/parameter-sets/$FREE_SET_ID").statusCode shouldBe 204
    }

    @Test
    @Order(8)
    fun `another workspace's same-named artifact pins nothing here - the template purge and the pipeline discard proceed`() {
        // Workspace B's dashboard and visualization pin THESE NAMES; workspace A holds unpinned artifacts of the same names.
        val hash = createTemplate(SHARED_NAME_TEMPLATE)
        post("/api/v1/templates/draft/discard", """{"name": "$SHARED_NAME_TEMPLATE"}""", ifMatch = hash).statusCode shouldBe 204
        post("/api/v1/pipelines/$SHARED_NAME_PIPELINE_ID/versions/1/discard", "").statusCode shouldBe 200
    }

    @Test
    @Order(9)
    fun `D7 - restoring a dashboard whose pipeline release was discarded while it was discarded is refused dependency_not_found`() {
        post("/api/v1/dashboards/$RESTORE_DASHBOARD_ID/versions/1/discard", "").statusCode shouldBe 200
        // The window: a DISCARDED dashboard version protects nothing, so its release can be discarded.
        post("/api/v1/pipelines/$RESTORE_PIPELINE_ID/versions/1/discard", "").statusCode shouldBe 200

        val restore = post("/api/v1/dashboards/$RESTORE_DASHBOARD_ID/versions/1/restore", "")

        refused(restore, 400) shouldBe "dashboard.validation.dependency_not_found"
        restore.jsonPath().getList<String>("error.details.failures.details.kind") shouldContainExactly listOf("pipeline")
        rowStatus("dashboard_versions", "dashboard_id", RESTORE_DASHBOARD_ID) shouldBe "DISCARDED"
    }

    @Test
    @Order(10)
    fun `a promoter cannot reach any guarded verb - refused by role before a guard could name anything`() {
        refused(
            post("/api/v1/templates/draft/discard", """{"name": "$SET_PINNED_TEMPLATE"}""", ifMatch = "x", session = PROMOTER_SESSION),
            403,
        ) shouldBe
            "auth.role_required"
        refused(post("/api/v1/pipelines/$PINNED_PIPELINE_ID/versions/1/discard", "", session = PROMOTER_SESSION), 403) shouldBe
            "auth.role_required"
        refused(post("/api/v1/parameter-sets/$PINNED_SET_ID/draft/discard", "", ifMatch = "x", session = PROMOTER_SESSION), 403) shouldBe
            "auth.role_required"
        refused(delete("/api/v1/parameter-sets/$PINNED_SET_ID", session = PROMOTER_SESSION), 403) shouldBe "auth.role_required"
    }

    @Test
    @Order(11)
    fun `templates_used_by never names a visualization the promoter's lens does not admit`() {
        val hash = createTemplate(RELEASED_TEMPLATE)
        post("/api/v1/templates/release", """{"name": "$RELEASED_TEMPLATE"}""", ifMatch = hash).statusCode shouldBe 200
        seedVisualization(HIDDEN_VIZ_ID, HIDDEN_VIZ_NAME, WORKSPACE_ID, "RELEASED", RELEASED_TEMPLATE)

        val asAdmin = callTool(ADMIN_KEY, "templates_used_by", mapOf("id" to RELEASED_TEMPLATE, "version" to 1))
        val asPromoter = callTool(PROMOTER_KEY, "templates_used_by", mapOf("id" to RELEASED_TEMPLATE, "version" to 1))

        withClue("non-vacuity: the admin's answer must carry the visualization before the promoter's absence means anything") {
            asAdmin["visualization_references"].map { it["visualization"].asText() } shouldContainExactly listOf(HIDDEN_VIZ_NAME)
        }
        asPromoter["visualization_references"].size() shouldBe 0
        asPromoter.toString() shouldNotContain HIDDEN_VIZ_NAME
    }

    @Test
    @Order(12)
    fun `non-vacuity - every refusal the walk exists for happened, counted by code`() {
        println("event=dependency_guards_e2e.refusals total=${refusals.size} ${refusals.groupingBy { it }.eachCount()}")
        refusals.groupingBy { it }.eachCount() shouldBe
            mapOf(
                "template.in_use" to 2,
                "pipeline.version.pinned" to 1,
                "parameter.in_use" to 2,
                "dashboard.validation.dependency_not_found" to 1,
                "auth.role_required" to 4,
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

    private fun get(path: String): Response = given().port(port).asSession(ADMIN_SESSION).get(path)

    private fun delete(
        path: String,
        session: String = ADMIN_SESSION,
    ): Response = given().port(port).asSession(session).delete(path)

    private fun post(
        path: String,
        body: String,
        ifMatch: String? = null,
        session: String = ADMIN_SESSION,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .post(path)

    /** Creates a DRAFT template (D55: authoring lands version 1 DRAFT); returns its `body_hash`. */
    private fun createTemplate(name: String): String {
        val created =
            post(
                "/api/v1/templates",
                """{"id": "$name", "dialect": "POSTGRES", "display_name": "Guard fixture", "description": "",
                    "imports": [], "body": "SELECT 1 AS v"}""",
            )
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        created.jsonPath().getString("data.status") shouldBe "DRAFT"
        return created.jsonPath().getString("data.body_hash")
    }

    private fun templateExists(name: String): Boolean = get("/api/v1/templates?name=$name").statusCode == 200

    /** The answer of one MCP tool under [key] — the payload the tool returned, an error failing the call loudly. */
    private fun callTool(
        key: E2eAuth.SeededKey,
        tool: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", key.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to 1,
                                "method" to "tools/call",
                                "params" to mapOf("name" to tool, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        withClue("MCP $tool: ${response.body().take(EXCERPT)}") { response.statusCode() shouldBe 200 }
        val result = mapper.readTree(response.body())["result"]
        withClue("MCP $tool refused: ${result.toString().take(EXCERPT)}") { result.path("isError").asBoolean(false) shouldBe false }
        return mapper.readTree(result["content"][0]["text"].asText())
    }

    /** One version row's status, looked up by the owning entity's id — the ground truth behind a refusal. */
    private fun rowStatus(
        table: String,
        fk: String,
        id: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            firstString(connection, "SELECT status FROM $table WHERE $fk = ?::uuid ORDER BY version DESC LIMIT 1", id)
        }

    /** The first column of the first row [statement] answers for its one text parameter, or null when it answers none. */
    private fun firstString(
        connection: Connection,
        statement: String,
        parameter: String,
    ): String? =
        connection.prepareStatement(statement).use { prepared ->
            prepared.setString(1, parameter)
            prepared.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Dependency guards E2E')")
        sql(
            "INSERT INTO workspaces (id, name, display_name) " +
                "VALUES ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'Dependency guards E2E (B)')",
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'dg-admin@e2e.test', 'DG Admin', 'test', 'dg-admin-sub', TRUE, FALSE),
                ('$PROMOTER_ID', 'dg-promoter@e2e.test', 'DG Promoter', 'test', 'dg-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter')
            """.trimIndent(),
        )
        seedKey(ADMIN_KEY, "workspace_admin")
        seedKey(PROMOTER_KEY, "promoter")

        // The pipeline arm: a release a RELEASED dashboard pins, one nothing pins, one only workspace B's dashboard pins
        // by name, and the D7 pair (a RELEASED dashboard and the release it pins).
        seedReleasedPipeline(PINNED_PIPELINE_ID, PINNED_PIPELINE)
        seedReleasedPipeline(FREE_PIPELINE_ID, FREE_PIPELINE)
        seedReleasedPipeline(SHARED_NAME_PIPELINE_ID, SHARED_NAME_PIPELINE)
        seedReleasedPipeline(RESTORE_PIPELINE_ID, RESTORE_PIPELINE)
        seedVisualization(RESTORE_VIZ_ID, RESTORE_VIZ_NAME, WORKSPACE_ID, "RELEASED", template = null)
        seedDashboard(PIN_DASHBOARD_ID, PIN_DASHBOARD, WORKSPACE_ID, "RELEASED", pipeline = PINNED_PIPELINE)
        seedDashboard(
            RESTORE_DASHBOARD_ID,
            RESTORE_DASHBOARD,
            WORKSPACE_ID,
            "RELEASED",
            pipeline = RESTORE_PIPELINE,
            visualization = RESTORE_VIZ_NAME,
        )
        // Workspace B pins the SAME NAMES workspace A holds unpinned — the cross-workspace case.
        seedDashboard(OTHER_DASHBOARD_ID, OTHER_DASHBOARD, OTHER_WORKSPACE_ID, "RELEASED", pipeline = SHARED_NAME_PIPELINE)
        seedVisualization(OTHER_VIZ_ID, OTHER_VIZ_NAME, OTHER_WORKSPACE_ID, "DRAFT", SHARED_NAME_TEMPLATE)

        // The set arm: a draft-only set a DRAFT dashboard pins, and one nothing pins.
        seedSet(PINNED_SET_ID, PINNED_SET, "DRAFT", SET_ARM_TEMPLATE)
        seedSet(FREE_SET_ID, FREE_SET, "DRAFT", SET_ARM_TEMPLATE)
        seedDashboard(
            DRAFT_DASHBOARD_ID,
            DRAFT_DASHBOARD,
            WORKSPACE_ID,
            "DRAFT",
            pipeline = DRAFT_DASHBOARD_SOURCE,
            parameterSet = PINNED_SET,
        )
    }

    /** An MCP key of [role] in workspace A, with its own service identity (the shape `KeysV2` seeds). */
    private fun seedKey(
        key: E2eAuth.SeededKey,
        role: String,
    ) {
        val identity = UUID.randomUUID()
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                "('$identity', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}', TRUE, FALSE, 'service')",
        )
        sql(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) VALUES " +
                "('${key.id}', '$identity', '$ADMIN_ID', '${key.name}', '${key.hash}', '$WORKSPACE_ID', 'mcp', '$role')",
        )
    }

    private fun seedReleasedPipeline(
        id: String,
        name: String,
    ) {
        sql(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES ('$id', '$name', 'P', '', '$ADMIN_ID', '$WORKSPACE_ID', 1)",
        )
        val body =
            """{"schema_version":1,"name":"$name","display_name":"P","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"$ROOT/templates/read.sql","version":1}}]}"""
        sql(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) " +
                "VALUES ('$id', 1, '$body'::jsonb, 'seeded-$id', 'RELEASED', '$ADMIN_ID', '$ADMIN_ID', NOW())",
        )
    }

    /** A stored artifact of one family, version 1 in [status]; RELEASED rows carry their stamps and the pointer. */
    @Suppress("LongParameterList") // one row pair per family table
    private fun seedArtifact(
        index: String,
        versions: String,
        fk: String,
        id: String,
        name: String,
        workspaceId: String,
        status: String,
        body: String,
    ) {
        val released = status == "RELEASED"
        sql(
            "INSERT INTO $index (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$workspaceId', '$name', '$name', '', ${if (released) "1" else "NULL"}, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO $versions ($fk, version, body_json, status, body_hash, created_by" +
                "${if (released) ", released_at, released_by" else ""}) " +
                "VALUES ('$id', 1, '$body'::jsonb, '$status', 'seeded-$id', '$ADMIN_ID'${if (released) ", NOW(), '$ADMIN_ID'" else ""})",
        )
    }

    /** A visualization whose body binds fully; [template] adds the transform pin `template@1` the reverse arrow scans. */
    private fun seedVisualization(
        id: String,
        name: String,
        workspaceId: String,
        status: String,
        template: String?,
    ) {
        val node = mapper.readTree(VISUALIZATION) as ObjectNode
        node.remove("name")
        template?.let {
            node.set<JsonNode>(
                "transform",
                mapper.readTree("""{"template": {"name": "$it", "version": 1}, "inputs": {"revenue": "revenue"}}"""),
            )
        }
        seedArtifact("visualizations", "visualization_versions", "visualization_id", id, name, workspaceId, status, node.toString())
    }

    /** A dashboard sourcing [pipeline]@1, placing [visualization]@1, and pinning [parameterSet]@1 when named. */
    @Suppress("LongParameterList") // one dashboard's three pins
    private fun seedDashboard(
        id: String,
        name: String,
        workspaceId: String,
        status: String,
        pipeline: String,
        visualization: String = RESTORE_VIZ_NAME,
        parameterSet: String? = null,
    ) {
        val node = mapper.readTree(DASHBOARD) as ObjectNode
        node.remove("name")
        ((node.get("sources").get(0) as ObjectNode).get("pipeline") as ObjectNode).put("name", pipeline)
        ((node.get("visualizations").get(0) as ObjectNode).get("visualization") as ObjectNode).put("name", visualization)
        parameterSet?.let {
            node.set<JsonNode>("parameter_set", mapper.readTree("""{"name": "$it", "version": 1}"""))
        }
        seedArtifact("dashboards", "dashboard_versions", "dashboard_id", id, name, workspaceId, status, node.toString())
    }

    /** A set version whose one parameter is a template-backed selector pinning `template@1` (the record's §8.1 shape). */
    private fun seedSet(
        id: String,
        name: String,
        status: String,
        template: String,
    ) {
        val body =
            """{"display_name":"S","parameters":[{"name":"p","label":"P","type":"STRING","kind":"SELECT","source":{"template":{"id":"$template","version":1}}}]}"""
        sql(
            "INSERT INTO parameter_sets (id, workspace_id, name, display_name, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', '$name', 'S', ${if (status == "RELEASED") "1" else "NULL"}, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO parameter_set_versions (parameter_set_id, version, body_json, status, body_hash, created_by" +
                "${if (status == "RELEASED") ", released_at, released_by" else ""}) " +
                "VALUES ('$id', 1, '$body'::jsonb, '$status', 'seeded-$id', '$ADMIN_ID'" +
                "${if (status == "RELEASED") ", NOW(), '$ADMIN_ID'" else ""})",
        )
    }

    /** A never-released pipeline (one DRAFT version) whose one node pins `template@1`. */
    private fun seedDraftPipeline(
        id: String,
        name: String,
        template: String,
    ) {
        sql(
            "INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) " +
                "VALUES ('$id', '$name', 'P', '', '$ADMIN_ID', '$WORKSPACE_ID', NULL)",
        )
        val body =
            """{"schema_version":1,"name":"$name","display_name":"P","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"$template","version":1}}]}"""
        sql(
            "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by) " +
                "VALUES ('$id', 1, '$body'::jsonb, 'seeded-$id', 'DRAFT', '$ADMIN_ID')",
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        const val EXCERPT = 600
        const val WORKSPACE = "dg-e2e"
        const val OTHER_WORKSPACE = "dg-e2e-b"
        const val ROOT = "dg"
        const val SET_PINNED_TEMPLATE = "$ROOT/templates/set_pinned.sql"
        const val SHARED_TEMPLATE = "$ROOT/templates/shared.sql"
        const val SET_NAME = "$ROOT/sets/pins_a_draft_template"
        const val OTHER_SET_NAME = "$ROOT/sets/also_pins_it"
        const val PIPELINE_NAME = "$ROOT/pipelines/draft_only"

        // the visualization arm
        const val VIZ_PINNED_TEMPLATE = "$ROOT/templates/viz_pinned.sql"
        const val VIZ_NAME = "$ROOT/charts/pins_a_draft_template"
        const val FREE_TEMPLATE = "$ROOT/templates/free.sql"
        const val RELEASED_TEMPLATE = "$ROOT/templates/released.sql"
        const val HIDDEN_VIZ_NAME = "$ROOT/charts/no_admitted_dashboard"

        // the pipeline arm and D7
        const val PINNED_PIPELINE = "$ROOT/pipelines/pinned"
        const val FREE_PIPELINE = "$ROOT/pipelines/free"
        const val SHARED_NAME_PIPELINE = "$ROOT/pipelines/shared_name"
        const val RESTORE_PIPELINE = "$ROOT/pipelines/restore"
        const val PIN_DASHBOARD = "$ROOT/boards/pins_a_release"
        const val RESTORE_DASHBOARD = "$ROOT/boards/restore_me"
        const val RESTORE_VIZ_NAME = "$ROOT/charts/restore_pin"

        // the set arm (its own template name and source pipeline: the fixtures of the other arms must not share a pin with it)
        const val SET_ARM_TEMPLATE = "$ROOT/templates/set_arm.sql"
        const val DRAFT_DASHBOARD_SOURCE = "$ROOT/pipelines/source_of_the_draft_dashboard"
        const val PINNED_SET = "$ROOT/sets/pinned_by_a_draft_dashboard"
        const val FREE_SET = "$ROOT/sets/nobody_pins"
        const val DRAFT_DASHBOARD = "$ROOT/boards/draft_pinning_a_set"

        // workspace B pins the SAME NAMES workspace A holds unpinned
        const val SHARED_NAME_TEMPLATE = "$ROOT/templates/shared_name.sql"
        const val OTHER_DASHBOARD = "$ROOT/boards/b_pins_shared_pipeline"
        const val OTHER_VIZ_NAME = "$ROOT/charts/b_pins_shared_template"

        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val OTHER_WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val PROMOTER_ID = UUID.randomUUID().toString()
        private val SET_ID = UUID.randomUUID().toString()
        private val OTHER_SET_ID = UUID.randomUUID().toString()
        private val PIPELINE_ID = UUID.randomUUID().toString()
        private val VIZ_ID = UUID.randomUUID().toString()
        private val HIDDEN_VIZ_ID = UUID.randomUUID().toString()
        private val RESTORE_VIZ_ID = UUID.randomUUID().toString()
        private val OTHER_VIZ_ID = UUID.randomUUID().toString()
        private val PINNED_PIPELINE_ID = UUID.randomUUID().toString()
        private val FREE_PIPELINE_ID = UUID.randomUUID().toString()
        private val SHARED_NAME_PIPELINE_ID = UUID.randomUUID().toString()
        private val RESTORE_PIPELINE_ID = UUID.randomUUID().toString()
        private val PIN_DASHBOARD_ID = UUID.randomUUID().toString()
        private val RESTORE_DASHBOARD_ID = UUID.randomUUID().toString()
        private val OTHER_DASHBOARD_ID = UUID.randomUUID().toString()
        private val DRAFT_DASHBOARD_ID = UUID.randomUUID().toString()
        private val PINNED_SET_ID = UUID.randomUUID().toString()
        private val FREE_SET_ID = UUID.randomUUID().toString()

        private val ADMIN_KEY = E2eAuth.generateKey("dg-e2e-admin-key")
        private val PROMOTER_KEY = E2eAuth.generateKey("dg-e2e-promoter-key")

        /** A visualization that binds fully (its `name` is stripped: a stored body never carries it, V42's `chk_*_body`). */
        val VISUALIZATION =
            """
            {"name": "x", "display_name": "Revenue", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        /** A dashboard that binds fully: one source, one placed visualization. */
        val DASHBOARD =
            """
            {"name": "x", "display_name": "Revenue", "description": "",
             "sources": [{"name": "revenue_source", "pipeline": {"name": "x", "version": 1}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                 "visualization": {"name": "x", "version": 1},
                                 "inputs": {"revenue": {"source": "revenue_source"}}}],
             "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "dg-admin@e2e.test", WORKSPACE)
        private val PROMOTER_SESSION get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "dg-promoter@e2e.test", WORKSPACE)

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
            // The promoter lens reads the stub higher environment (an empty inventory: every RELEASED template is newer).
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { "dg-e2e-server-key" }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
        }

        /** The stub higher environment: it holds nothing yet, so the promoter's lens admits what is RELEASED here. */
        private val stub: HttpServer by lazy {
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .also { server ->
                    server.createContext("/api/v1/promotion/inventory") { exchange ->
                        val body =
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                                """"authoring_enabled":false,"workspace":"$WORKSPACE","templates":[],"datasources":[],""" +
                                """"parameter_sets":[],"pipelines":[]}}"""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    server.start()
                }
        }

        @JvmStatic
        @org.junit.jupiter.api.AfterAll
        fun tearDown() {
            oidc.close()
            stub.stop(0)
        }
    }
}
