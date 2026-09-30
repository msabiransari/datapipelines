package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * The L1c acceptance (#10 L1c, the spec's §12): a REAL visualization export envelope travels — REST export, REST
 * import (whole, idempotent, the C29 refusal with its templates staying), and a promotion batch received by a REAL
 * receiver deployment — and the two transfer families land whole inside the receive's ONE transaction, or not at
 * all. The eight cases mirror [ParameterSetTransferE2eTest]'s; the mould's order numbers are kept.
 *
 * ## Three deployments, because ids are global (P24)
 * The artifact tables' ids are PRIMARY KEYS of the whole server (C29), so a kept id can land only once per
 * database: the source (which authors and exports), the import target (authoring on — the import is not an
 * authoring write), and the promotion receiver (authoring off, the pre-shared key). One scratch database each.
 * What landed in the receiver is asserted against its DATABASE, not against its own report.
 *
 * ## Releases are seeded by SQL (the L2 precedent)
 * No visualization can be RELEASED through the REST verb until L4 (the evidence gate refuses by design), so the
 * fixtures create drafts over REST and stamp `status='RELEASED'` + `current_version` by SQL — exactly the rows a
 * real release writes (V42's columns, the CHECKs included).
 */
@ExtendWith(SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class VisualizationTransferE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    /** REST on the SOURCE deployment. */
    private fun rest(
        method: String,
        path: String,
        session: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> {
        var request = given().port(port).contentType(ContentType.JSON).asSession(session)
        headers.forEach { (name, value) -> request = request.header(name, value) }
        if (body != null) request = request.body(body)
        val sent = request.`when`()
        val response =
            when (method) {
                "POST" -> sent.post(path)
                else -> sent.get(path)
            }.then().extract()
        return response.statusCode() to mapper.readTree(response.asString())
    }

    /** REST on one of the two hand-booted deployments. */
    private fun restOn(
        target: Int,
        method: String,
        path: String,
        session: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> {
        var request = given().port(target).contentType(ContentType.JSON).asSession(session)
        headers.forEach { (name, value) -> request = request.header(name, value) }
        if (body != null) request = request.body(body)
        val sent = request.`when`()
        val response =
            when (method) {
                "POST" -> sent.post(path)
                else -> sent.get(path)
            }.then().extract()
        return response.statusCode() to mapper.readTree(response.asString())
    }

    private fun errorCode(envelope: JsonNode): String = envelope.path("error").path("code").asText()

    @Test
    @Order(1)
    fun `fixture - tables, the released templates, visualizations, dashboard, pipeline and set in the source`() {
        seedWorkspaces(sourceJdbc, WS_SOURCE)
        seedTables()
        registerDatasourceOn(port, sessionFor(WS_SOURCE))
        createReleasedTransformTemplate()
        createReleasedLeafTemplate()
        createReleasedVisualization(VIZ_NAME, VIZ_DOCUMENT)
        createReleasedVisualization(VIZ_PLAIN_NAME, VIZ_PLAIN_DOCUMENT)
        createReleasedVisualization(VIZ_GHOST_NAME, VIZ_GHOST_DOCUMENT)
        createReleasedVisualization(VIZ_PLAIN_2_NAME, VIZ_PLAIN_2_DOCUMENT)
        // The dashboard's save-time validation judges the pins' CURRENT state: the pipeline must be
        // RELEASED before the dashboard is created.
        createReleasedPipeline()
        createReleasedSet()
        createReleasedDashboard()
        // The import target and the receiver: own deployments, datasource registered, the extra workspaces granted.
        registerDatasourceOn(restTargetPort, sessionFor(WS_REST))
        grantOn(checkNotNull(restTarget), DATASOURCE, WS_C29)
        registerDatasourceOn(receiverPort, sessionFor(WS_PROMO))
        grantOn(checkNotNull(receiver), DATASOURCE, WS_PROMO2)
    }

    @Test
    @Order(2)
    fun `the export envelope imports into a second deployment whole - id kept, released, hash equal`() {
        val envelope = exportOf(VIZ_NAME)
        val payload = envelope.path("visualization")
        val exportedHash = payload.path("body_hash").asText()

        val (status, imported) =
            restOn(restTargetPort, "POST", "/api/v1/visualizations/import", sessionFor(WS_REST), envelopeText(envelope))

        withClue("import must land the envelope: $imported") { status shouldBe 200 }
        val data = imported.path("data")
        data.path("id").asText() shouldBe payload.path("id").asText()
        data.path("import_created").asBoolean() shouldBe true
        data.path("status").asText() shouldBe "RELEASED"
        data.path("version").asInt() shouldBe payload.path("version").asInt()
        data.path("body_hash").asText() shouldBe exportedHash
    }

    @Test
    @Order(3)
    fun `a second identical import is the idempotent no-op`() {
        val (status, imported) =
            restOn(restTargetPort, "POST", "/api/v1/visualizations/import", sessionFor(WS_REST), envelopeText(exportOf(VIZ_NAME)))

        withClue("the no-op re-import must answer 200: $imported") { status shouldBe 200 }
        imported.path("data").path("import_created").asBoolean() shouldBe false
        imported.path("data").path("import_unchanged").asBoolean() shouldBe true
    }

    @Test
    @Order(4)
    fun `the same envelope into another workspace refuses id_taken - and the bundled template it landed stays`() {
        val (status, refused) =
            restOn(restTargetPort, "POST", "/api/v1/visualizations/import", sessionFor(WS_C29), envelopeText(exportOf(VIZ_NAME)))

        withClue("C29: the refusal, not a re-issue: $refused") { status shouldBe 409 }
        errorCode(refused) shouldBe "visualization.import.id_taken"
        // The transform template landed BEFORE the visualization was judged (the §12 order) and the
        // refusal leaves it (the import is not atomic across templates and artifact — C35's shape).
        withClue("the bundled template must have landed in $WS_C29 before the refusal") {
            templatePresentOn(restTargetPort, WS_C29)
        }
    }

    @Test
    @Order(5)
    fun `a promotion batch carrying a plain visualization is received whole - live in the target workspace`() {
        val entry = payloadOf(VIZ_PLAIN_NAME)
        val (status, applied) = pushBatch(batchOf(WS_PROMO, visualizations = listOf(entry)))

        withClue("the receiver must accept the batch: $applied") { status shouldBe 200 }
        applied.path("data").path("visualizations").asInt() shouldBe 1

        // What the TARGET holds — read from the receiver's database, not from its report.
        val exportedHash = entry.path("body_hash").asText()
        val exportedId = entry.path("id").asText()
        val receiverDb = checkNotNull(receiver).jdbc
        val row =
            receiverDb.row(
                "SELECT s.workspace_id::text AS ws, v.status AS status, v.body_hash AS hash" +
                    " FROM visualizations s JOIN visualization_versions v ON v.visualization_id = s.id AND v.version = 1" +
                    " WHERE s.id = '$exportedId'",
            )
        withClue("the visualization must be live in the promotion target") {
            row["ws"] shouldBe receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO'")
            row["status"] shouldBe "RELEASED"
            row["hash"] shouldBe exportedHash
        }
    }

    @Test
    @Order(6)
    fun `a batch whose visualization pins a transform template the SAME batch brings is received whole`() {
        // The D61 order on the receiver: the batch's templates land FIRST, inside the transaction, and the
        // visualization's import lens resolves its pin against the just-landed rows. Unlike the sets (#302)
        // nothing here opens a customer datasource, so there is no wall — but validating BEFORE the templates
        // land would refuse `visualization.import.missing_template`; the order pin in the web suite is that
        // falsification lever.
        val entry = payloadOf(VIZ_NAME)
        val batch = setTemplates(batchOf(WS_PROMO, visualizations = listOf(entry)), entry.templatePins(mapper))
        val (status, applied) = pushBatch(batch)

        withClue("the receiver must accept the template-backed batch: $applied") { status shouldBe 200 }
        applied.path("data").path("visualizations").asInt() shouldBe 1

        val exportedHash = entry.path("body_hash").asText()
        val receiverDb = checkNotNull(receiver).jdbc
        val row =
            receiverDb.row(
                "SELECT v.status AS status, v.body_hash AS hash FROM visualizations s" +
                    " JOIN visualization_versions v ON v.visualization_id = s.id AND v.version = 1 WHERE s.id = '${entry.path(
                        "id",
                    ).asText()}'",
            )
        withClue("the template-backed visualization must be live in the promotion target") {
            row["status"] shouldBe "RELEASED"
            row["hash"] shouldBe exportedHash
        }
        templatePresentOn(receiverPort, WS_PROMO)
    }

    @Test
    @Order(7)
    fun `a batch whose second visualization refuses rolls the whole batch back - the templates included (C36)`() {
        // The SAME plain-visualization id into the receiver's SECOND workspace: the id PK is global (P24),
        // so the landing refuses C29's id_taken — AFTER the batch's templates were pushed inside the one
        // transaction. The rollback is whole: the template is gone too.
        val entry = payloadOf(VIZ_PLAIN_NAME)
        val batch = setTemplates(batchOf(WS_PROMO2, visualizations = listOf(entry)), entry.templatePins(mapper))
        val (status, refused) = pushBatch(batch)

        withClue("C29 at the landing: $refused") { status shouldBe 409 }
        errorCode(refused) shouldBe "visualization.import.id_taken"

        val receiverDb = checkNotNull(receiver).jdbc
        val ws2 = receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO2'")
        withClue("nothing of the batch may have survived the rollback") {
            receiverDb.scalar("SELECT count(*) FROM templates WHERE workspace_id::text = '$ws2'") shouldBe "0"
            receiverDb
                .scalar(
                    "SELECT count(*) FROM visualizations WHERE workspace_id::text = '$ws2'" +
                        " AND id = '${entry.path("id").asText()}'",
                ) shouldBe "0"
        }
    }

    @Test
    @Order(8)
    fun `a batch whose second visualization refuses validation lands nothing - the refusal is the entry's own code`() {
        // Two visualizations: the first (plain) would land; the SECOND pins a transform template neither the
        // batch brings nor the receiver holds — `visualization.import.missing_template` INSIDE the transaction,
        // so the whole batch rolls back and the first visualization is absent too.
        val good = payloadOf(VIZ_PLAIN_2_NAME)
        val badEntry = ghostPinnedEntry(payloadOf(VIZ_GHOST_NAME))
        val batch = batchOf(WS_PROMO, visualizations = listOf(good, badEntry))
        val (status, refused) = pushBatch(batch)

        withClue("the second visualization's own refusal: $refused") { status shouldBe 400 }
        errorCode(refused) shouldBe "visualization.import.missing_template"

        val receiverDb = checkNotNull(receiver).jdbc
        val ws = receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO'")
        withClue("nothing of the batch may land") {
            receiverDb.scalar(
                "SELECT count(*) FROM visualizations WHERE workspace_id::text = '$ws' AND name = '$VIZ_PLAIN_2_NAME'",
            ) shouldBe
                "0"
            receiverDb.scalar("SELECT count(*) FROM visualizations WHERE workspace_id::text = '$ws' AND name = '$VIZ_GHOST_NAME'") shouldBe
                "0"
        }
    }

    @Test
    @Order(9)
    fun `a dashboard bundling its two pinned visualizations is received whole after its dependencies`() {
        // D61's full order: the pipeline and the set were promoted FIRST (case 9's first batch), then the
        // dashboard's batch carries its two pinned visualizations and the dashboard — all three land, the
        // dashboard's validation judging its pins against the rows the SAME transaction just landed.
        promoteDependencies()

        val plain = payloadOf(VIZ_PLAIN_NAME)
        val shaped = payloadOf(VIZ_PLAIN_2_NAME)
        val batch = batchOf(WS_PROMO, visualizations = listOf(plain, shaped), dashboards = listOf(dashboardPayload()))
        val (status, applied) = pushBatch(batch)

        withClue("the receiver must accept the dashboard batch: $applied") { status shouldBe 200 }
        applied.path("data").path("visualizations").asInt() shouldBe 2
        applied.path("data").path("dashboards").asInt() shouldBe 1

        val receiverDb = checkNotNull(receiver).jdbc
        val ws = receiverDb.scalar("SELECT id::text FROM workspaces WHERE name = '$WS_PROMO'")
        withClue("all three must be live in the promotion target") {
            receiverDb.scalar("SELECT count(*) FROM visualizations WHERE workspace_id::text = '$ws' AND name = '$VIZ_PLAIN_NAME'") shouldBe
                "1"
            receiverDb.scalar(
                "SELECT count(*) FROM visualizations WHERE workspace_id::text = '$ws' AND name = '$VIZ_PLAIN_2_NAME'",
            ) shouldBe
                "1"
            receiverDb.scalar("SELECT count(*) FROM dashboards WHERE workspace_id::text = '$ws' AND name = '$DASH_NAME'") shouldBe "1"
        }
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    /** The batch's template closure over one entry's transform pin — what the real sender merges. */
    private fun JsonNode.templatePins(mapper: ObjectMapper): com.fasterxml.jackson.databind.node.ArrayNode {
        val templates = mapper.createArrayNode()
        val pin = path("transform").path("template")
        if (pin.has("name")) {
            templateNode(pin.path("name").asText(), pin.path("version").asInt())?.let { templates.add(it) }
        }
        return templates
    }

    private fun templateNodes(vararg refs: Pair<String, Int>): com.fasterxml.jackson.databind.node.ArrayNode {
        val templates = mapper.createArrayNode()
        refs.forEach { (id, version) -> templateNode(id, version)?.let { templates.add(it) } }
        return templates
    }

    /** One stored template version as a wire node — the sender payload's shape, read from the source database. */
    private fun templateNode(
        id: String,
        version: Int,
    ): com.fasterxml.jackson.databind.node.ObjectNode? {
        val row =
            sourceJdbc.rowOrNull(
                "SELECT t.name AS id, engine, type::text AS type, dialect::text AS dialect, display_name, description, body, " +
                    "is_library, v.version, body_hash, contract_json::text AS contract, invariants_json::text AS invariants, " +
                    "tests_json::text AS tests FROM template_versions v JOIN templates t ON t.id = v.template_id " +
                    "WHERE t.name = '$id' AND v.version = $version",
            ) ?: return null
        val node = mapper.createObjectNode()
        node
            .put("schema_version", 1)
            .put("id", row.getValue("id"))
            .put("engine", row.getValue("engine"))
            .put("type", row.getValue("type"))
            .put("display_name", row.getValue("display_name"))
            .put("description", row.getValue("description"))
            .put("body", row.getValue("body"))
            .put("is_library", row.getValue("is_library").toBoolean())
            .put("version", row.getValue("version").toInt())
            .put("body_hash", row.getValue("body_hash"))
        row["dialect"]?.takeIf { it.isNotEmpty() }?.let { node.put("dialect", it) }
        // The transform blocks are version content — the import's hash guard recomputes over them.
        row["contract"]?.takeIf { it.isNotEmpty() && it != "null" }?.let { node.set<JsonNode>("contract", mapper.readTree(it)) }
        row["invariants"]?.takeIf { it.isNotEmpty() && it != "null" }?.let { node.set<JsonNode>("invariants", mapper.readTree(it)) }
        row["tests"]?.takeIf { it.isNotEmpty() && it != "null" }?.let { node.set<JsonNode>("tests", mapper.readTree(it)) }
        return node
    }

    private fun setTemplates(
        batch: com.fasterxml.jackson.databind.node.ObjectNode,
        templates: com.fasterxml.jackson.databind.node.ArrayNode,
    ): com.fasterxml.jackson.databind.node.ObjectNode {
        batch.set<JsonNode>("templates", templates)
        return batch
    }

    private fun promoteDependencies() {
        val pipelineId = sourceJdbc.scalar("SELECT id::text FROM pipelines WHERE name = '$PIPELINE'")
        val (pipelineStatus, pipelineExport) = rest("GET", "/api/v1/pipelines/$pipelineId/export", sessionFor(WS_SOURCE))
        withClue("pipeline export must answer: $pipelineExport") { pipelineStatus shouldBe 200 }
        // The wire node is the SENDER's shape: the body + id/version/body_hash — the export's server-assigned
        // fields (owner, stamps, current_version, status) are stripped exactly as the sender never sends them.
        val pipelinePayload = pipelineExport.path("data").path("pipeline").deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode
        listOf("status", "owner", "created_at", "updated_at", "current_version", "draft").forEach { pipelinePayload.remove(it) }
        val setEnvelope = exportSet()
        // The pipeline's DQL node pins the leaf template — the sender's closure brings it FIRST.
        val batch = setTemplates(batchOf(WS_PROMO), templateNodes(LEAF to 1))
        batch.set<JsonNode>("pipelines", mapper.createArrayNode().add(pipelinePayload))
        batch.set<JsonNode>("parameter_sets", mapper.createArrayNode().add(setEnvelope.path("parameter_set")))
        val (status, applied) = pushBatch(batch)
        withClue("the dependencies batch must be received: $applied") { status shouldBe 200 }
    }

    private fun seedTables() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS dim_state (country_code VARCHAR(2), state_code VARCHAR(2), state_name VARCHAR(30))",
                )
                statement.execute("DELETE FROM dim_state")
                statement.execute("INSERT INTO dim_state VALUES ('US','NY','New York'),('US','CA','California')")
            }
        }
    }

    private fun registerDatasourceOn(
        target: Int,
        session: String,
    ) {
        val (status, body) =
            restOn(
                target,
                "POST",
                "/api/v1/datasources",
                session,
                """{"name": "$DATASOURCE", "display_name": "Transfer E2E", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
            )
        withClue("datasource must register: $body") { status shouldBe 201 }
    }

    private fun grantOn(
        deployment: Deployment,
        datasource: String,
        workspace: String,
    ) {
        val workspaceId = deployment.jdbc.scalar("SELECT id::text FROM workspaces WHERE name = '$workspace'")
        deployment.jdbc.execute(
            "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)" +
                " VALUES ('$datasource', '$workspaceId', '$ADMIN_USER_ID')" +
                " ON CONFLICT DO NOTHING",
        )
    }

    /** The row-mode transform the template-backed visualization pins (the 7c E2E's fixture shape). */
    private fun createReleasedTransformTemplate() {
        val (status, created) =
            rest(
                "POST",
                "/api/v1/templates",
                sessionFor(WS_SOURCE),
                """
                {"id": "$TRANSFORM", "type": "jsonata", "display_name": "Revenue shape",
                 "description": "the pinned transform (month to label, amount to value)",
                 "body": ${mapper.writeValueAsString(TRANSFORM_BODY)},
                 "contract": ${TRANSFORM_CONTRACT}, "invariants": [], "tests": $TRANSFORM_TESTS}
                """.trimIndent(),
            )
        withClue("transform template must create: $created") { status shouldBe 201 }
        val hash = created.path("data").path("body_hash").asText()
        val (releaseStatus, released) =
            rest("POST", "/api/v1/templates/release", sessionFor(WS_SOURCE), """{"name": "$TRANSFORM"}""", mapOf("If-Match" to hash))
        withClue("transform template must release: $released") { releaseStatus shouldBe 200 }
    }

    private fun createReleasedVisualization(
        name: String,
        document: String,
    ) {
        val (status, created) =
            rest(
                "POST",
                "/api/v1/visualizations",
                sessionFor(WS_SOURCE),
                document.replaceFirst("NAME_PLACEHOLDER", name),
            )
        withClue("visualization $name must create: $created") { status shouldBe 201 }
        releaseBySql(created, "visualizations", "visualization_versions", "visualization_id", name)
    }

    private fun createReleasedDashboard() {
        val document = DASHBOARD_DOCUMENT.replaceFirst("NAME_PLACEHOLDER", DASH_NAME)
        val (status, created) = rest("POST", "/api/v1/dashboards", sessionFor(WS_SOURCE), document)
        withClue("dashboard must create: $created") { status shouldBe 201 }
        releaseBySql(created, "dashboards", "dashboard_versions", "dashboard_id", DASH_NAME)
    }

    /** The rows a real release writes (V42), stamped by SQL — the evidence gate refuses every REST release until L4. */
    private fun releaseBySql(
        created: JsonNode,
        table: String,
        versionsTable: String,
        fkColumn: String,
        name: String,
    ) {
        val id = created.path("data").path("id").asText()
        sourceJdbc.execute(
            "UPDATE $versionsTable SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_USER_ID'" +
                " WHERE $fkColumn = '$id' AND version = 1",
        )
        sourceJdbc.execute("UPDATE $table SET current_version = 1 WHERE id = '$id'")
        val status =
            sourceJdbc.scalar(
                "SELECT v.status FROM $table s JOIN $versionsTable v ON v.$fkColumn = s.id AND v.version = 1 WHERE s.name = '$name'",
            )
        withClue("$name must be RELEASED by the SQL stamp") { status shouldBe "RELEASED" }
    }

    /** The DQL node's template — the pipeline over the shared H2 datasource (the two-deployment E2E's shape). */
    private fun createReleasedLeafTemplate() {
        val create = mapper.createObjectNode()
        create.put("id", LEAF).put("dialect", "H2").put("display_name", "Revenue rows")
        create.put("description", "the DQL node's select (a bind, never interpolation)")
        create.put("body", LEAF_BODY)
        val (status, created) = rest("POST", "/api/v1/templates", sessionFor(WS_SOURCE), mapper.writeValueAsString(create))
        withClue("leaf template must create: $created") { status shouldBe 201 }
        val hash = created.path("data").path("body_hash").asText()
        val (releaseStatus, released) =
            rest("POST", "/api/v1/templates/release", sessionFor(WS_SOURCE), """{"name": "$LEAF"}""", mapOf("If-Match" to hash))
        withClue("leaf template must release: $released") { releaseStatus shouldBe 200 }
    }

    private fun createReleasedPipeline() {
        val (status, created) =
            rest(
                "POST",
                "/api/v1/pipelines",
                sessionFor(WS_SOURCE),
                """{"schema_version": 1, "name": "$PIPELINE", "display_name": "Revenue source", "description": "transfer round trip",
                   "nodes": [{"id": "read", "description": "read revenue", "type": "DQL", "source": "$DATASOURCE",
                              "template": {"id": "$LEAF", "version": 1},
                              "output": {"target": "caller"}, "depends_on": []}]}""",
            )
        withClue("pipeline must create: $created") { status shouldBe 201 }
        val id = created.path("data").path("id").asText()
        val hash = created.path("data").path("body_hash").asText()
        val (releaseStatus, released) =
            rest("POST", "/api/v1/pipelines/$id/release", sessionFor(WS_SOURCE), "", mapOf("If-Match" to hash))
        withClue("pipeline must release: $released") { releaseStatus shouldBe 200 }
    }

    private fun createReleasedSet() {
        val (status, created) = rest("POST", "/api/v1/parameter-sets", sessionFor(WS_SOURCE), SET_DOCUMENT)
        withClue("set must create: $created") { status shouldBe 201 }
        releaseSet(created)
    }

    private fun releaseSet(created: JsonNode) {
        val data = created.path("data")
        val hash = data.path("body_hash").asText()
        val (releaseStatus, released) =
            rest("POST", "/api/v1/parameter-sets/${data.path("id").asText()}/release", sessionFor(WS_SOURCE), "", mapOf("If-Match" to hash))
        withClue("set must release: $released") { releaseStatus shouldBe 200 }
    }

    private fun exportOf(name: String): JsonNode {
        val id = sourceJdbc.scalar("SELECT id::text FROM visualizations WHERE name = '$name'")
        val (status, body) = rest("GET", "/api/v1/visualizations/$id/export", sessionFor(WS_SOURCE))
        withClue("export must answer the envelope: $body") { status shouldBe 200 }
        val envelope = body.path("data")
        envelope.path("visualization").path("id").asText() shouldBe id
        return envelope
    }

    /** The dashboard export envelope (used to shape the dashboard's wire node from the REAL sender). */
    private fun exportDashboardEnvelope(): JsonNode {
        val id = sourceJdbc.scalar("SELECT id::text FROM dashboards WHERE name = '$DASH_NAME'")
        val (status, body) = rest("GET", "/api/v1/dashboards/$id/export", sessionFor(WS_SOURCE))
        withClue("dashboard export must answer the envelope: $body") { status shouldBe 200 }
        return body.path("data")
    }

    private fun exportSet(): JsonNode {
        val id = sourceJdbc.scalar("SELECT id::text FROM parameter_sets WHERE name = '$SET_NAME'")
        val (status, body) = rest("GET", "/api/v1/parameter-sets/$id/export", sessionFor(WS_SOURCE))
        withClue("set export must answer: $body") { status shouldBe 200 }
        return body.path("data")
    }

    /** The wire NODE of [name]'s current release — the body with its lifecycle fields, as the sender emits it. */
    private fun payloadOf(name: String): JsonNode = exportOf(name).path("visualization")

    private fun dashboardPayload(): JsonNode = exportDashboardEnvelope().path("dashboard")

    private fun envelopeText(envelope: JsonNode): String = mapper.writeValueAsString(envelope)

    /** The ghost entry of the validation-refusal case: the pin moved to a template nobody brings or holds. */
    private fun ghostPinnedEntry(good: JsonNode): JsonNode {
        val entry = good.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        entry.put("id", UUID.randomUUID().toString())
        entry.put("name", VIZ_GHOST_NAME)
        (
            (entry.get("transform") as com.fasterxml.jackson.databind.node.ObjectNode)
                .get("template") as com.fasterxml.jackson.databind.node.ObjectNode
        ).put("name", "vt$RUN/does_not_exist.jsonata")
        return entry
    }

    private fun batchOf(
        workspace: String,
        visualizations: List<JsonNode> = emptyList(),
        dashboards: List<JsonNode> = emptyList(),
    ): com.fasterxml.jackson.databind.node.ObjectNode =
        mapper
            .createObjectNode()
            .put("source_env", "vt-src")
            .put("key_fingerprint", "e2e-fingerprint")
            .put("workspace", workspace)
            .also { batch ->
                batch.set<JsonNode>("templates", mapper.createArrayNode())
                batch.set<JsonNode>("pipelines", mapper.createArrayNode())
                batch.set<JsonNode>("endpoints", mapper.createArrayNode())
                batch.set<JsonNode>("parameter_sets", mapper.createArrayNode())
                batch.set<JsonNode>("visualizations", mapper.createArrayNode().addAll(visualizations))
                batch.set<JsonNode>("dashboards", mapper.createArrayNode().addAll(dashboards))
            }

    /** Pushes [batch] to the receiver with the pre-shared key — the promotion channel's one route. */
    private fun pushBatch(batch: JsonNode): Pair<Int, JsonNode> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$receiverPort/api/v1/promotion/push"))
                .header("Content-Type", "application/json")
                .header(PROMOTION_HEADER, SERVER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(batch)))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body())
    }

    private fun templatePresentOn(
        target: Int,
        workspace: String,
    ) {
        val (status, body) = restOn(target, "GET", "/api/v1/templates?name=$TRANSFORM", sessionFor(workspace))
        withClue("template must resolve in $workspace: $body") { status shouldBe 200 }
        body.path("data").path("id").asText() shouldBe TRANSFORM
    }

    companion object {
        const val H2_JDBC_URL = "jdbc:h2:mem:visualtransfer10l1c;DB_CLOSE_DELAY=-1"
        const val DATASOURCE = "visual-transfer-e2e"
        const val PROMOTION_HEADER = "DP-Promotion-Key"

        /** Names unique forever — every run takes a fresh set (versioning §3.2). */
        val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        val WS_SOURCE = "vt_src_$RUN"
        val WS_REST = "vt_rest_$RUN"
        val WS_C29 = "vt_c29_$RUN"
        val WS_PROMO = "vt_promo_$RUN"
        val WS_PROMO2 = "vt_promo2_$RUN"
        val TRANSFORM = "vt$RUN/transforms/revenue_shape.jsonata"
        val LEAF = "vt$RUN/sql/revenue_rows.sql"

        /** The DQL node's select — value/display/is_default, a bind, never interpolation. */
        const val LEAF_BODY = "SELECT month, amount FROM revenue ORDER BY month"
        val VIZ_NAME = "vt$RUN/charts/revenue_shaped"
        val VIZ_PLAIN_NAME = "vt$RUN/charts/revenue_plain"
        val VIZ_PLAIN_2_NAME = "vt$RUN/charts/revenue_plain_two"
        val VIZ_GHOST_NAME = "vt$RUN/charts/revenue_ghost"
        val DASH_NAME = "vt$RUN/dashboards/revenue_overview"
        val PIPELINE = "vt$RUN/pipelines/revenue_source"
        val SET_NAME = "vt$RUN/acme/region_filters"

        /** The row-mode transform body: the rows array, one output row per input row. */
        const val TRANSFORM_BODY = """[ rows.{"month_labels": month, "amounts": amount} ]"""

        /** The contract the template-backed visualization's rules check the pin against. */
        val TRANSFORM_CONTRACT: String =
            """
            {"mode": "row",
             "inputs": {"rows": {"kind": "table", "columns": [
                        {"name": "month", "type": "DATE"},
                        {"name": "amount", "type": "DECIMAL", "precision": 12, "scale": 2}]}},
             "output": {"kind": "table", "columns": [
                        {"name": "month_labels", "type": "STRING"},
                        {"name": "amounts", "type": "DECIMAL", "precision": 12, "scale": 2}]}}
            """.trimIndent()

        /** One case — the mandatory empty input (a transform with no zero-row test does not save). */
        val TRANSFORM_TESTS: String =
            """
            [{"name": "empty input", "input": {"rows": [], "inputs": {}},
              "expect": {"output": []}}]
            """.trimIndent()

        /** The template-backed visualization: the transform pin, the bindings over the output columns. */
        val VIZ_DOCUMENT: String =
            """
            {"name": "NAME_PLACEHOLDER", "display_name": "Revenue (shaped)", "description": "transfer round trip",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "transform": {"template": {"name": "$TRANSFORM", "version": 1}, "inputs": {"rows": "revenue"}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}], "layout": {}},
             "bindings": {"data[0].x": "month_labels", "data[0].y": "amounts"},
             "presentation": {"title": "Revenue", "tokens": {"series": "categorical"}},
             "tests": {"cases": [{"name": "twelve months", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        /** A visualization with no transform: the renderer binds the single input's columns directly (spec §3.1). */
        val VIZ_PLAIN_DOCUMENT: String =
            """
            {"name": "NAME_PLACEHOLDER", "display_name": "Revenue (plain)", "description": "transfer round trip",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}], "layout": {}},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "presentation": {"title": "Revenue", "tokens": {"series": "categorical"}},
             "tests": {"cases": [{"name": "twelve months", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()

        /** The second plain visualization — the dashboard's second pin. */
        val VIZ_PLAIN_2_DOCUMENT: String = VIZ_PLAIN_DOCUMENT

        /** The ghost: a transform pin the batch never brings and the receiver does not hold. */
        val VIZ_GHOST_DOCUMENT: String =
            VIZ_DOCUMENT.replace("\"inputs\": {\"rows\": \"revenue\"}", "\"inputs\": {\"rows\": \"revenue\"}")

        /** No set pin — the set facts are skipped and the dashboard's deps are the pipeline and the two pins. */
        val DASHBOARD_DOCUMENT: String =
            """
            {"name": "NAME_PLACEHOLDER", "display_name": "Revenue overview", "description": "transfer round trip",
             "sources": [
               {"name": "revenue_source", "pipeline": {"name": "$PIPELINE", "version": 1}, "parameters": {}}
             ],
             "visualizations": [
               {"name": "plain_chart", "type": "visualization",
                "visualization": {"name": "$VIZ_PLAIN_NAME", "version": 1},
                "inputs": {"revenue": {"source": "revenue_source"}}, "timeout_seconds": 120},
               {"name": "shaped_chart", "type": "visualization",
                "visualization": {"name": "$VIZ_PLAIN_2_NAME", "version": 1},
                "inputs": {"revenue": {"source": "revenue_source"}}, "timeout_seconds": 120}
             ],
             "actions": [{"name": "refresh_overview", "type": "refresh", "scope": "targets",
                          "targets": ["plain_chart"], "initial": true}],
             "action_controls": [{"name": "refresh_button", "type": "action_control",
                                  "action": "refresh_overview", "label": "Apply"}],
             "layout": {"grid": [
                        {"name": "plain_chart", "x": 0, "y": 0, "w": 6, "h": 4},
                        {"name": "shaped_chart", "x": 6, "y": 0, "w": 6, "h": 4},
                        {"name": "refresh_button", "x": 0, "y": 4, "w": 2, "h": 1}], "columns": 12},
             "timeouts": {"refresh_seconds": 300}}
            """.trimIndent()

        val SET_DOCUMENT: String =
            """
            {
              "name": "$SET_NAME",
              "display_name": "Region filters",
              "description": "transfer round trip",
              "parameters": [
                {
                  "name": "region", "label": "Region", "type": "STRING", "kind": "SELECT",
                  "cardinality": "SINGLE", "required": true,
                  "source": {"constants": [{"value": "EMEA", "display_value": "EMEA", "is_default": true}]}
                }
              ]
            }
            """.trimIndent()

        /** A fresh empty database per deployment — ids are global, so a kept id lands only once per database. */
        private val sourceDb = SharedE2e.scratchDatabase("visual_transfer_l1c_src")
        private val restDb = SharedE2e.scratchDatabase("visual_transfer_l1c_rest")
        private val promoDb = SharedE2e.scratchDatabase("visual_transfer_l1c_promo")

        private val sourceJdbc = ScratchJdbc(sourceDb.jdbcUrl, sourceDb.username, sourceDb.password)

        private val JWT_SECRET: String = E2eSession.newSecret()
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-l1c@datapipelines.test"

        /** The deprecated pre-shared promotion credential — the receiver acts as the System actor. */
        private val SERVER_KEY: String = "dpk-visual-transfer-e2e-server-${UUID.randomUUID()}"

        private const val SECRET_BYTES = 32
        private val random = SecureRandom()

        private var restTarget: Deployment? = null
        private var restTargetPort: Int = 0
        private var receiver: Deployment? = null
        private var receiverPort: Int = 0

        private fun sessionFor(workspace: String): String = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL, workspace = workspace)

        /** The SOURCE deployment (this class's own @SpringBootTest context) on its scratch database. */
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { sourceDb.jdbcUrl }
            registry.add("spring.datasource.username") { sourceDb.username }
            registry.add("spring.datasource.password") { sourceDb.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        /**
         * The two hand-booted deployments, started BEFORE the authoring @SpringBootTest context:
         * a receiver refuses to boot beside ANY draft, and the source deployment this class
         * boots next is ABOUT to make some. Each boots its own fresh scratch database, so each
         * Flyway applies the schema itself; the auth rows are seeded right after.
         */
        @BeforeAll
        @JvmStatic
        fun bootTargetsAndSeed() {
            val encryptionKey = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })
            val common =
                arrayOf(
                    "--management.server.port=0",
                    "--spring.data.redis.host=${SharedE2e.redisHost}",
                    "--spring.data.redis.port=${SharedE2e.redisPort}",
                    "--spring.data.redis.password=",
                    "--datapipelines.redis.host=${SharedE2e.redisHost}",
                    "--datapipelines.redis.port=${SharedE2e.redisPort}",
                    "--datapipelines.jwt.secret=$JWT_SECRET",
                    "--datapipelines.db.encryption-key=$encryptionKey",
                    "--datapipelines.auth.local.enabled=true",
                    "--datapipelines.auth.base-url=http://localhost:8080",
                    "--datapipelines.posture=development",
                )
            val restContext =
                boot(
                    common,
                    restDb,
                    arrayOf(
                        "--datapipelines.env=vt-rest-target",
                        "--datapipelines.deployment.authoring-enabled=true",
                    ),
                )
            restTarget =
                Deployment(
                    restContext,
                    ScratchJdbc(restDb.jdbcUrl, restDb.username, restDb.password),
                )
            restTargetPort = portOf(restContext)
            val receiverContext =
                boot(
                    common,
                    promoDb,
                    arrayOf(
                        "--datapipelines.env=vt-receiver",
                        "--datapipelines.deployment.authoring-enabled=false",
                        "--datapipelines.deployment.promotion.server-key=$SERVER_KEY",
                    ),
                )
            receiver =
                Deployment(
                    receiverContext,
                    ScratchJdbc(promoDb.jdbcUrl, promoDb.username, promoDb.password),
                )
            receiverPort = portOf(receiverContext)

            seedWorkspaces(checkNotNull(restTarget).jdbc, WS_REST, WS_C29)
            seedWorkspaces(checkNotNull(receiver).jdbc, WS_PROMO, WS_PROMO2)
        }

        /** One deployment boot — command-line args, which win over the yml's `${...}` defaults. */
        private fun boot(
            common: Array<String>,
            db: SharedE2e.SharedPostgresRef,
            deploymentArgs: Array<String>,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .run(
                    *(
                        common +
                            arrayOf(
                                "--server.port=0",
                                "--spring.datasource.url=${db.jdbcUrl}",
                                "--spring.datasource.username=${db.username}",
                                "--spring.datasource.password=${db.password}",
                            ) +
                            deploymentArgs
                    ),
                )

        @AfterAll
        @JvmStatic
        fun closeTargets() {
            restTarget?.context?.close()
            receiver?.context?.close()
        }

        private fun portOf(context: ConfigurableApplicationContext): Int =
            Integer.parseInt(checkNotNull(context.environment.getProperty("local.server.port")))

        private fun seedWorkspaces(
            jdbc: ScratchJdbc,
            vararg names: String,
        ) {
            jdbc.execute(
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', 'E2E L1c', 'test', 'e2e-l1c-sub', TRUE, TRUE)
                """.trimIndent(),
            )
            names.forEach { name ->
                val id = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
                jdbc.execute(
                    "INSERT INTO workspaces (id, name, display_name) VALUES ('$id', '$name', 'Transfer $name')",
                )
                jdbc.execute(
                    "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$id', '$ADMIN_USER_ID', 'workspace_admin')",
                )
            }
        }

        /** One hand-booted deployment: its context (the port) and its database (the truth). */
        private class Deployment(
            val context: ConfigurableApplicationContext,
            val jdbc: ScratchJdbc,
        )
    }

    /**
     * A tiny JDBC reader — the suites in this module read rows directly rather than through
     * Spring, and what landed in a target is a DATABASE fact, not a service's own report.
     */
    private class ScratchJdbc(
        private val url: String,
        private val user: String,
        private val password: String,
    ) {
        fun scalar(sql: String): String = row(sql).values.first()

        fun rowOrNull(sql: String): Map<String, String>? =
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use(::rowAt)
                }
            }

        private fun rowAt(rs: java.sql.ResultSet): Map<String, String>? =
            if (!rs.next()) {
                null
            } else {
                (1..rs.metaData.columnCount).associate { i -> rs.metaData.getColumnLabel(i) to (rs.getString(i) ?: "") }
            }

        fun row(sql: String): Map<String, String> =
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        check(rs.next()) { "no row for: $sql" }
                        (1..rs.metaData.columnCount).associate { i ->
                            rs.metaData.getColumnLabel(i) to (rs.getString(i) ?: "")
                        }
                    }
                }
            }

        fun execute(sql: String) {
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }
    }
}
