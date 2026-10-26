package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * 142 — the release cascade end to end, over the REAL wire (the [ReleaseChecksE2eTest]
 * harness: JSON-RPC per MCP leg, RestAssured per REST leg, a real Postgres for the metadata
 * and the audit table, an in-JVM H2 datasource the workspace owns):
 *
 * 1. two templates created over MCP land as DRAFT v1 (D55); pipeline A pins both, pipeline B
 *    pins the first — the shared-object shape;
 * 2. releasing A over REST WITHOUT `release_pinned_templates` is refused
 *    `409 pipeline.release.dependency_not_released` in the shared details shape of #517 (record
 *    §14: `root`, `blockers[]` with `node_id/family/name/version/status/reason/paths`,
 *    `blockers_truncated`) naming BOTH draft pins — the §14 switch replaced #142's
 *    `template_id` / `pins_not_released` keys at once, with no deprecation window;
 * 3. **#517, a plan over a changed snapshot:** a plan carrying the preview's `tree_hash` is
 *    first proven accepted (an unticked draft answers `dependency_not_released`, which only a
 *    matching digest reaches), then a template body is edited WITHOUT a version change and the
 *    same plan is refused `409 pipeline.release.plan_conflict` — statuses and audit unchanged.
 *    The digest is computed by an oracle of this test's own ([treeHash]), never by the product;
 * 4. **atomicity, falsified:** releasing A WITH the flag but a STALE `If-Match` makes the
 *    pipeline flip fail AFTER the two templates flipped inside the same transaction. The
 *    answer is `409 pipeline.version.conflict`, both templates are still DRAFT, A is still a
 *    draft, and the audit table carries no released event for any of the three — asserted at
 *    the TABLE, because an in-memory fake cannot see a skipped hop;
 * 5. releasing A with the flag and the right hash releases both templates at their pinned
 *    version and A itself, and the audit log holds three events: one
 *    `template.version.released` per template carrying `cascade_from_pipeline_id` /
 *    `cascade_from_version`, then A's `pipeline.version.released` with `templates_released`;
 * 6. B is untouched and now pins a RELEASED version: it releases WITHOUT the flag, its event
 *    carries an empty `templates_released`, and the shared template was released exactly once;
 * 7. **#517, the plan path:** pipeline C pins a new DRAFT template and the released one; a plan
 *    ticking the draft releases both in one transaction, the response appends
 *    `dependencies_released`, and every audit row carries the one `release_id`, the cascaded row
 *    its `cascade_root`.
 *
 * Cases 4–6 are #142's, byte for byte (the brief's falsification 6 replays their responses
 * captured at the base): the legacy alias keeps its exact behaviour until S6 (#522).
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReleaseCascadeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val http: HttpClient = HttpClient.newHttpClient()

    /** One JSON-RPC call over /mcp with the seeded API key; the parsed response tree back. */
    private fun mcp(
        id: Int,
        method: String,
        params: Map<String, Any?>,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                // The MCP key: /mcp takes a key and refuses a session (§8.5); REST below takes the session (#215 B2).
                .header("DP-API-Key", ADMIN_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val body = mapper.readTree(response.body())
        withClue("JSON-RPC error on $method: ${response.body()}") { body.has("error") shouldBe false }
        return body["result"]
    }

    /** A tools/call result: the single text block, parsed back into JSON (the tool payload). */
    private fun callTool(
        id: Int,
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val result = mcp(id, "tools/call", mapOf("name" to name, "arguments" to arguments))
        val payload = mapper.readTree(result["content"][0]["text"].asText())
        return payload to result.path("isError").asBoolean(false)
    }

    private fun createTemplate(
        id: Int,
        templateId: String,
    ) {
        val (payload, isError) =
            callTool(
                id,
                "templates_create",
                mapOf(
                    "id" to templateId,
                    "dialect" to "H2",
                    "display_name" to "Release cascade (142)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 1 AS n",
                ),
            )
        withClue("templates_create must succeed: $payload") { isError shouldBe false }
        withClue("a create lands a DRAFT (D55): $payload") { payload["status"].asText() shouldBe "DRAFT" }
        templateHashes[templateId] = payload["body_hash"].asText()
    }

    private fun createPipeline(
        id: Int,
        name: String,
        templateIds: List<String>,
    ): Pair<String, String> {
        val (created, createError) =
            callTool(
                id,
                "pipelines_create",
                mapOf(
                    "name" to name,
                    "display_name" to "Release cascade (142)",
                    "description" to "Pins draft templates the release will cascade to.",
                    "nodes" to
                        templateIds.mapIndexed { index, templateId ->
                            // At most one node answers the caller (§12): the rest stage to tempdb.
                            val node =
                                mutableMapOf<String, Any?>(
                                    "id" to "n$index",
                                    "type" to "DQL",
                                    "source" to DATASOURCE,
                                    "description" to "One row",
                                    "template" to mapOf("id" to templateId, "version" to 1),
                                    "depends_on" to emptyList<String>(),
                                )
                            if (index < templateIds.lastIndex) node["output"] = mapOf("target" to "tempdb", "table" to "step_$index")
                            node
                        },
                ),
            )
        withClue("pipelines_create must succeed: $created") { createError shouldBe false }
        withClue("a create lands a DRAFT (D55): $created") { created["draft"].isObject shouldBe true }
        return created["id"].asText() to created["draft"]["body_hash"].asText()
    }

    private fun release(
        pipelineId: String,
        hash: String,
        cascade: Boolean,
    ): ValidatableResponse {
        val request =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header("If-Match", hash)
        if (cascade) request.queryParam("release_pinned_templates", "true")
        return request.`when`().post("/api/v1/pipelines/$pipelineId/release").then()
    }

    /** A release with a plan body (#517, record §6): the preview's tree hash and the ticked node ids. */
    private fun releaseWithPlan(
        pipelineId: String,
        hash: String,
        treeHash: String,
        include: List<String>,
    ): ValidatableResponse =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .contentType(ContentType.JSON)
            .body(mapper.writeValueAsString(mapOf("tree_hash" to treeHash, "include_dependencies" to include)))
            .`when`()
            .post("/api/v1/pipelines/$pipelineId/release")
            .then()

    /** One node of the oracle's snapshot: `[family, pin key, version]`, status, body hash, current pointer. */
    private data class Snap(
        val family: String,
        val key: String,
        val version: Int,
        val status: String,
        val bodyHash: String,
        val current: Int?,
    ) {
        val id: String get() = "[\"$family\",\"$key\",$version]"
    }

    /**
     * The plan's tree hash, recomputed INDEPENDENTLY of the product (record §2.1, format `v1`): the canonical UTF-8
     * serialisation of the workspace, the root identity, every node (identity, status, body hash, current pointer) and
     * every pin occurrence (from, to, field path), each list sorted by its members' bytes, SHA-256, `v1:` + hex. Every
     * string in this fixture is plain ASCII without quotes or backslashes, so no escaping is needed and the natural
     * string order IS the byte order.
     */
    private fun treeHash(
        nodes: List<Snap>,
        edges: List<Triple<Snap, Snap, String>>,
    ): String {
        val root = nodes.first()
        val nodeJson =
            nodes.map { n ->
                val uuid = entityUuid(n)
                "{\"id\":${n.id},\"entity_uuid\":\"$uuid\",\"status\":\"${n.status}\"," +
                    "\"body_hash\":\"${n.bodyHash}\",\"current\":${n.current ?: "null"}}"
            }
        val edgeJson = edges.map { (from, to, path) -> "{\"from\":${from.id},\"to\":${to.id},\"path\":\"$path\"}" }
        val canonical =
            "{\"format\":\"v1\",\"workspace\":\"$MCP_WORKSPACE\",\"root\":${root.id}," +
                "\"nodes\":${nodeJson.sorted().joinToString(",", "[", "]")},\"edges\":${edgeJson.sorted().joinToString(",", "[", "]")}}"
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Independent UUID lookup, scoped to the workspace and one of the fixture's two static families. */
    private fun entityUuid(node: Snap): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            val sql =
                when (node.family) {
                    "pipeline" -> "SELECT id FROM pipelines WHERE workspace_id = ?::uuid AND name = ?"
                    "template" -> "SELECT id FROM templates WHERE workspace_id = ?::uuid AND name = ?"
                    else -> error("unsupported fixture family")
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, MCP_WORKSPACE)
                statement.setString(2, node.key)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    /** A's snapshot as the preview sees it now: A (n0) pins TEMPLATE_ONE (n1) and TEMPLATE_TWO (n2), all DRAFT. */
    private fun previewOfA(): String {
        val a = Snap("pipeline", PIPELINE_A, 1, "DRAFT", hashA, null)
        val one = Snap("template", TEMPLATE_ONE, 1, "DRAFT", templateHashes.getValue(TEMPLATE_ONE), null)
        val two = Snap("template", TEMPLATE_TWO, 1, "DRAFT", templateHashes.getValue(TEMPLATE_TWO), null)
        return treeHash(listOf(a, one, two), listOf(Triple(a, one, "nodes[0].template"), Triple(a, two, "nodes[1].template")))
    }

    private fun templateStatus(templateId: String): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .queryParam("name", templateId)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .extract()
            .path("data.status")

    private fun pipelineStatus(pipelineId: String): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/pipelines/$pipelineId")
            .then()
            .statusCode(200)
            .extract()
            .path("data.status")

    /** Every audit row of [event] whose details mention [needle], oldest first — read at the TABLE. */
    private fun auditRows(
        event: String,
        needle: String,
    ): List<JsonNode> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                val rows =
                    statement.executeQuery(
                        "SELECT details_json FROM audit_log WHERE event = '$event'" +
                            " AND details_json::text LIKE '%$needle%' ORDER BY timestamp ASC",
                    )
                val out = mutableListOf<JsonNode>()
                while (rows.next()) out.add(mapper.readTree(rows.getString("details_json")))
                out
            }
        }

    @Test
    @Order(1)
    fun `the fixture - an owned H2 datasource, two DRAFT templates, pipeline A pinning both and B pinning one`() {
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { it.createStatement().execute("SELECT 1") }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Release cascade (142)", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)

        createTemplate(1, TEMPLATE_ONE)
        createTemplate(2, TEMPLATE_TWO)
        val a = createPipeline(3, PIPELINE_A, listOf(TEMPLATE_ONE, TEMPLATE_TWO))
        pipelineA = a.first
        hashA = a.second
        val b = createPipeline(4, PIPELINE_B, listOf(TEMPLATE_ONE))
        pipelineB = b.first
        hashB = b.second
    }

    @Test
    @Order(2)
    fun `without the flag the release is refused dependency_not_released, naming both draft pins in the shared shape`() {
        release(pipelineA, hashA, cascade = false)
            .statusCode(409)
            .body("error.code", equalTo("pipeline.release.dependency_not_released"))
            .body(
                "error.message",
                equalTo("Release blocked: 2 dependencies are not released. Release them first, or include the drafts in this release."),
            )
            // #517 (record §14): the shared shape — the root's typed identity, then EVERY blocker in discovery order.
            .body("error.details.root", equalTo(mapOf("node_id" to "n0", "family" to "pipeline", "name" to PIPELINE_A, "version" to 1)))
            .body("error.details.blockers.size()", equalTo(2))
            .body("error.details.blockers.collect { it.node_id }", equalTo(listOf("n1", "n2")))
            .body("error.details.blockers.collect { it.family }.toSet()", equalTo(setOf("template")))
            .body("error.details.blockers.collect { it.name }", equalTo(listOf(TEMPLATE_ONE, TEMPLATE_TWO)))
            .body("error.details.blockers.collect { it.version }.toSet()", equalTo(setOf(1)))
            .body("error.details.blockers.collect { it.status }.toSet()", equalTo(setOf("DRAFT")))
            .body("error.details.blockers.collect { it.reason }.toSet()", equalTo(setOf("not_included")))
            .body("error.details.blockers.collect { it.paths }", equalTo(listOf(listOf("nodes[0].template"), listOf("nodes[1].template"))))
            .body("error.details.blockers_truncated", equalTo(false))
            // The §14 switch: the #142 keys are gone, not answered alongside.
            .body("error.details.template_id", nullValue())
            .body("error.details.pins_not_released", nullValue())

        templateStatus(TEMPLATE_ONE) shouldBe "DRAFT"
        templateStatus(TEMPLATE_TWO) shouldBe "DRAFT"
        pipelineStatus(pipelineA) shouldBe "DRAFT"
    }

    @Test
    @Order(3)
    fun `a plan over a template edited after the preview is plan_conflict - statuses and audit unchanged`() {
        val preview = previewOfA()
        // Non-vacuity: the oracle's digest is the product's — an unticked draft can only be judged after step 3 passed.
        releaseWithPlan(pipelineA, hashA, preview, listOf("n1"))
            .statusCode(409)
            .body("error.code", equalTo("pipeline.release.dependency_not_released"))
            .body("error.details.blockers.collect { it.name }", equalTo(listOf(TEMPLATE_TWO)))

        // The edit: a new body for the DRAFT, in place — same version, new hash (§5.2).
        val (updated, updateError) =
            callTool(
                5,
                "templates_update",
                mapOf(
                    "id" to TEMPLATE_ONE,
                    "expected_hash" to templateHashes.getValue(TEMPLATE_ONE),
                    "display_name" to "Release cascade (142)",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 2 AS n",
                ),
            )
        withClue("templates_update must succeed: $updated") { updateError shouldBe false }
        withClue("the edit keeps the version: $updated") { updated["version"].asInt() shouldBe 1 }
        templateHashes[TEMPLATE_ONE] = updated["body_hash"].asText()

        releaseWithPlan(pipelineA, hashA, preview, listOf("n1", "n2"))
            .statusCode(409)
            .body("error.code", equalTo("pipeline.release.plan_conflict"))
            .body("error.details.reason", equalTo("snapshot_changed"))

        withClue("nothing flipped and nothing was audited") {
            templateStatus(TEMPLATE_ONE) shouldBe "DRAFT"
            templateStatus(TEMPLATE_TWO) shouldBe "DRAFT"
            pipelineStatus(pipelineA) shouldBe "DRAFT"
            auditRows("template.version.released", TEMPLATE_ONE).size shouldBe 0
            auditRows("pipeline.version.released", pipelineA).size shouldBe 0
        }
        // The re-read preview is accepted again — the conflict was the edit, not the request.
        releaseWithPlan(pipelineA, hashA, previewOfA(), listOf("n1"))
            .statusCode(409)
            .body("error.code", equalTo("pipeline.release.dependency_not_released"))
    }

    @Test
    @Order(4)
    fun `atomicity falsified - a stale hash after the templates flipped rolls everything back and audits nothing`() {
        // The stale hash is the injected failure: the service releases both templates FIRST
        // (inside the metadata transaction), then the pipeline's flip statement matches no
        // row at this hash and throws — and the TransactionTemplate rolls the templates back.
        release(pipelineA, STALE_HASH, cascade = true)
            .statusCode(409)
            .body("error.code", equalTo("pipeline.version.conflict"))
            .body("error.details.current_body_hash", equalTo(hashA))

        withClue("no template is left released with the pipeline still a draft") {
            templateStatus(TEMPLATE_ONE) shouldBe "DRAFT"
            templateStatus(TEMPLATE_TWO) shouldBe "DRAFT"
            pipelineStatus(pipelineA) shouldBe "DRAFT"
        }
        withClue("the audit log carries nothing for a rolled-back cascade") {
            auditRows("template.version.released", TEMPLATE_ONE).size shouldBe 0
            auditRows("template.version.released", TEMPLATE_TWO).size shouldBe 0
            auditRows("pipeline.version.released", pipelineA).size shouldBe 0
        }
    }

    @Test
    @Order(5)
    fun `with the flag both templates release at their pinned version, then the pipeline - three audit events`() {
        release(pipelineA, hashA, cascade = true)
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
            .body("data.version", equalTo(1))

        templateStatus(TEMPLATE_ONE) shouldBe "RELEASED"
        templateStatus(TEMPLATE_TWO) shouldBe "RELEASED"
        pipelineStatus(pipelineA) shouldBe "RELEASED"

        // The released version is the PINNED one — v1 — and the templates' pointers name it.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .queryParam("name", TEMPLATE_ONE)
            .`when`()
            .get("/api/v1/templates")
            .then()
            .statusCode(200)
            .body("data.version", equalTo(1))
            .body("data.status", equalTo("RELEASED"))

        // Three events, asserted at the TABLE: each template's own event reconstructs "who
        // released X v1 and why" alone; the pipeline's names what it cascaded to.
        listOf(TEMPLATE_ONE, TEMPLATE_TWO).forEach { templateId ->
            val events = auditRows("template.version.released", templateId)
            withClue("exactly one template event for $templateId: $events") { events.size shouldBe 1 }
            val details = events.single()
            details["template_id"].asText() shouldBe templateId
            details["version"].asInt() shouldBe 1
            // The release ran on the admin's SESSION (REST is a session's surface since #215 B2).
            details["via"].asText() shouldBe "session"
            details["cascade_from_pipeline_id"].asText() shouldBe pipelineA
            details["cascade_from_version"].asInt() shouldBe 1
        }
        val pipelineEvents = auditRows("pipeline.version.released", pipelineA)
        withClue("exactly one pipeline event: $pipelineEvents") { pipelineEvents.size shouldBe 1 }
        val released = pipelineEvents.single()["templates_released"]
        withClue("the pipeline event lists both cascaded templates: $released") {
            released.map { it["template_id"].asText() to it["version"].asInt() } shouldBe
                listOf(TEMPLATE_ONE to 1, TEMPLATE_TWO to 1)
        }
    }

    @Test
    @Order(6)
    fun `the second draft pipeline now pins a RELEASED version - it releases without the flag, cascading nothing`() {
        pipelineStatus(pipelineB) shouldBe "DRAFT"

        release(pipelineB, hashB, cascade = false)
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))

        val events = auditRows("pipeline.version.released", pipelineB)
        events.size shouldBe 1
        events.single()["templates_released"].size() shouldBe 0
        // The shared template was released ONCE — by A's cascade, never again by B.
        auditRows("template.version.released", TEMPLATE_ONE).size shouldBe 1
    }

    @Test
    @Order(7)
    fun `a plan ticking a draft template releases it and the pipeline in one transaction, audited under one release_id`() {
        createTemplate(6, TEMPLATE_THREE)
        val (pipelineC, hashC) = createPipeline(7, PIPELINE_C, listOf(TEMPLATE_THREE, TEMPLATE_ONE))
        val c = Snap("pipeline", PIPELINE_C, 1, "DRAFT", hashC, null)
        val three = Snap("template", TEMPLATE_THREE, 1, "DRAFT", templateHashes.getValue(TEMPLATE_THREE), null)
        val one = Snap("template", TEMPLATE_ONE, 1, "RELEASED", templateHashes.getValue(TEMPLATE_ONE), 1)
        val preview = treeHash(listOf(c, three, one), listOf(Triple(c, three, "nodes[0].template"), Triple(c, one, "nodes[1].template")))

        releaseWithPlan(pipelineC, hashC, preview, listOf("n1"))
            .statusCode(200)
            .body("data.status", equalTo("RELEASED"))
            .body("data.dependencies_released", equalTo(listOf(mapOf("family" to "template", "name" to TEMPLATE_THREE, "version" to 1))))

        templateStatus(TEMPLATE_THREE) shouldBe "RELEASED"
        val templateRow = auditRows("template.version.released", TEMPLATE_THREE).single()
        val pipelineRow = auditRows("pipeline.version.released", pipelineC).single()
        templateRow["scope"].asText() shouldBe "version"
        templateRow["release_id"].asText() shouldBe pipelineRow["release_id"].asText()
        templateRow["cascade_root"]["family"].asText() shouldBe "pipeline"
        templateRow["cascade_root"]["id"].asText() shouldBe pipelineC
        templateRow["cascade_root"]["version"].asInt() shouldBe 1
        pipelineRow.has("cascade_root") shouldBe false
        pipelineRow["dependencies_released"].map { it["name"].asText() } shouldBe listOf(TEMPLATE_THREE)
        // TEMPLATE_ONE was RELEASED already: a released pin is shown, never flipped again.
        auditRows("template.version.released", TEMPLATE_ONE).size shouldBe 1
    }

    @Suppress("LongMethod") // the exact supported unpin/purge/recreate/restore/submit sequence is one acceptance witness
    @Test
    @Order(8)
    fun `purge and identical recreation under the same child name refuses the old plan`() {
        val childName = "test/recreated_517b_child"
        val parentName = "test/recreated_517b_parent"
        val (childId, childHash) = createPipeline(81, childName, listOf(TEMPLATE_ONE))
        val parentArgs =
            mapOf(
                "name" to parentName,
                "display_name" to "Parent",
                "description" to "Recreation witness",
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "child",
                            "type" to "PIPELINE",
                            "pipeline" to mapOf("name" to childName, "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            )
        val (parent, failed) = callTool(82, "pipelines_create", parentArgs)
        withClue("parent created: $parent") { failed shouldBe false }
        val parentId = parent["id"].asText()
        val parentHash = parent["draft"]["body_hash"].asText()
        val root = Snap("pipeline", parentName, 1, "DRAFT", parentHash, null)
        val child = Snap("pipeline", childName, 1, "DRAFT", childHash, null)
        val template = Snap("template", TEMPLATE_ONE, 1, "RELEASED", templateHashes.getValue(TEMPLATE_ONE), 1)
        val preview =
            treeHash(
                listOf(root, child, template),
                listOf(
                    Triple(root, child, "nodes[0].pipeline"),
                    Triple(child, template, "nodes[0].template"),
                ),
            )
        // A matching old plan reaches selection validation before any mutation.
        releaseWithPlan(parentId, parentHash, preview, emptyList())
            .statusCode(409)
            .body("error.code", equalTo("pipeline.release.dependency_not_released"))
        val unpinnedArgs =
            parentArgs +
                mapOf(
                    "nodes" to
                        listOf(
                            mapOf(
                                "id" to "n0",
                                "type" to "DQL",
                                "source" to DATASOURCE,
                                "template" to mapOf("id" to TEMPLATE_ONE, "version" to 1),
                                "depends_on" to emptyList<String>(),
                            ),
                        ),
                )
        val (unpinned, unpinError) = callTool(83, "pipelines_update", unpinnedArgs + mapOf("id" to parentId, "expected_hash" to parentHash))
        withClue("unpin succeeds: $unpinned") { unpinError shouldBe false }
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/pipelines/$childId/versions/1")
            .then()
            .statusCode(204)
        val (newId, newHash) = createPipeline(84, childName, listOf(TEMPLATE_ONE))
        (newId != childId) shouldBe true
        newHash shouldBe childHash
        val (restored, restoreError) =
            callTool(
                85,
                "pipelines_update",
                parentArgs +
                    mapOf(
                        "id" to parentId,
                        "expected_hash" to unpinned["draft"]["body_hash"].asText(),
                    ),
            )
        withClue("restore succeeds: $restored") { restoreError shouldBe false }
        restored["draft"]["body_hash"].asText() shouldBe parentHash
        val response = releaseWithPlan(parentId, parentHash, preview, listOf("n1")).extract().response()
        val parentStatus = pipelineStatus(parentId)
        val childStatus = pipelineStatus(newId)
        val parentAudits = auditRows("pipeline.version.released", parentId).size
        val childAudits = auditRows("pipeline.version.released", newId).size
        withClue("HTTP ${response.statusCode}; parent=$parentStatus child=$childStatus; release audits=$parentAudits/$childAudits") {
            response.statusCode shouldBe 409
            response.jsonPath().getString("error.code") shouldBe "pipeline.release.plan_conflict"
            parentStatus shouldBe "DRAFT"
            childStatus shouldBe "DRAFT"
            parentAudits shouldBe 0
            childAudits shouldBe 0
        }
    }

    /**
     * #517 round three — the lander's refusal of 887784d59 (comment 6399333063), replayed: a restore lands RELEASED with no
     * human's consent, so it rechecks the restored version's outbound pins under the workspace guard. A template version
     * discarded after the pipeline refuses the restore with the save rules' code (400, record §12.1 RULED 2026-10-25 — the
     * D7 precedent): the parent and the template stay DISCARDED and no restore audit row commits. Relies on order 1's
     * datasource.
     */
    @Test
    @Order(9)
    fun `restore rechecks outbound pins - a template discarded after the pipeline refuses 400 and writes nothing`() {
        val template = "test/restore_517_outbound.sql"
        createTemplate(901, template)
        val (id, hash) = createPipeline(902, "test/restore_517_outbound", listOf(template))
        release(id, hash, cascade = true).statusCode(200)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("/api/v1/pipelines/$id/versions/1/discard")
            .then()
            .statusCode(200)
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(mapOf("name" to template, "version" to 1))
            .post("/api/v1/templates/version/discard")
            .then()
            .statusCode(200)

        val response = given().port(port).asSession(ADMIN_SESSION).post("/api/v1/pipelines/$id/versions/1/restore")

        val (parent, target) = versionStatuses(id, template)
        val restores = auditRows("pipeline.version.restored", id).size
        withClue(
            "restore HTTP ${response.statusCode} ${response.body.asString()}; parent=$parent template=$target restoreAudits=$restores",
        ) {
            response.statusCode shouldBe 400
            response.jsonPath().getString("error.code") shouldBe "pipeline.validation.template_version_not_found"
            response.jsonPath().getString("error.details.failures[0].path") shouldBe "nodes[0].template"
            response.jsonPath().getString("error.details.failures[0].details.reason") shouldBe "discarded"
            parent shouldBe "DISCARDED"
            target shouldBe "DISCARDED"
            restores shouldBe 0
        }
    }

    /** #517 CI follow-up: a draft's proposed name is not its registry lookup name until release commits. */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Order(10)
    fun `compatibility release resolves a renamed draft by its registry identity and keeps native hash refusals`(hadRelease: Boolean) {
        val draft = renamedDraft(hadRelease)
        (draft.newHash == draft.oldHash) shouldBe false
        val auditBefore = auditRows("pipeline.version.released", draft.id).size

        release(draft.id, draft.oldHash, cascade = false)
            .statusCode(409)
            .body("error.code", equalTo("pipeline.version.conflict"))
            .body("error.details.current_body_hash", equalTo(draft.newHash))
        auditRows("pipeline.version.released", draft.id).size shouldBe auditBefore
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/pipelines/${draft.id}")
            .then()
            .statusCode(200)
            .body("data.draft.body_hash", equalTo(draft.newHash))
            .body("data.current_version", equalTo(if (hadRelease) 1 else null))

        release(draft.id, draft.newHash, cascade = false).statusCode(200).body("data.status", equalTo("RELEASED"))
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/pipelines/${draft.id}")
            .then()
            .statusCode(200)
            .body("data.name", equalTo(draft.name))
            .body("data.current_version", equalTo(if (hadRelease) 2 else 1))
        auditRows("pipeline.version.released", draft.id).size shouldBe auditBefore + 1
    }

    private data class RenamedDraft(
        val id: String,
        val name: String,
        val oldHash: String,
        val newHash: String,
    )

    /** A sole draft or a draft over a release, with proposed metadata that differs from the registry row. */
    private fun renamedDraft(hadRelease: Boolean): RenamedDraft {
        val originalName = "test/rename_517_$hadRelease"
        val renamed = "test/renamed_517_$hadRelease"
        val body =
            mapOf(
                "name" to originalName,
                "description" to "Before the draft rename",
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "fq",
                            "type" to "CALCULATOR",
                            "kind" to "fiscal_quarter",
                            "context_key" to "run_fiscal_quarter",
                            "inputs" to mapOf("date" to "2026-08-14", "fiscal_start" to "09-15"),
                        ),
                    ),
            )
        val created =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
                .response()
                .jsonPath()
        val id = created.getString("data.id")
        val oldHash = created.getString("data.body_hash")
        if (hadRelease) release(id, oldHash, cascade = false).statusCode(200)
        val edited =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .header("If-Match", oldHash)
                .body(body + mapOf("name" to renamed, "description" to "After the draft rename"))
                .put("/api/v1/pipelines/$id")
                .then()
                .statusCode(200)
                .extract()
                .response()
                .jsonPath()
        val newHash = edited.getString("data.body_hash")
        return RenamedDraft(id, renamed, oldHash, newHash)
    }

    /** The parent's v1 status and the template's v1 status, read at the TABLES (the parent is wholly discarded). */
    private fun versionStatuses(
        pipelineId: String,
        templateId: String,
    ): Pair<String, String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement(
                    "SELECT (SELECT status FROM pipeline_versions WHERE pipeline_id = ?::uuid AND version = 1)," +
                        " (SELECT tv.status FROM template_versions tv JOIN templates t ON t.id = tv.template_id" +
                        " WHERE t.name = ? AND t.workspace_id = ?::uuid AND tv.version = 1)",
                ).use { statement ->
                    statement.setString(1, pipelineId)
                    statement.setString(2, templateId)
                    statement.setString(3, MCP_WORKSPACE)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getString(1) to rows.getString(2)
                    }
                }
        }

    companion object {
        private const val TEMPLATE_ONE = "test/cascade_142_one.sql"
        private const val TEMPLATE_TWO = "test/cascade_142_two.sql"
        private const val TEMPLATE_THREE = "test/cascade_517_three.sql"
        private const val PIPELINE_C = "test/cascade_517_c"
        private const val PIPELINE_A = "test/cascade_142_a"
        private const val PIPELINE_B = "test/cascade_142_b"
        private const val DATASOURCE = "h2-cascade-142"
        private const val H2_JDBC_URL = "jdbc:h2:mem:cascade142;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val STALE_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()

        /** The key's own `service` identity (keys v2 A13). */
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret` and used to sign the session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()

        /** The admin's MCP key for the `/mcp` legs — pinned to `default`, acting as the admin (#215 PK4). */
        private val ADMIN_KEY = E2eAuth.generateKey("e2e-142-key")
        private const val MCP_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, "e2e-142@datapipelines.test")

        private var pipelineA: String = ""
        private var hashA: String = ""
        private var pipelineB: String = ""
        private var hashB: String = ""
        private val templateHashes = mutableMapOf<String, String>()

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val random = SecureRandom()
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
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })
            }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @BeforeAll
        fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                            ('$ADMIN_USER_ID', 'e2e-142@datapipelines.test', 'E2E 142', 'test', 'e2e-142-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    // #215 PK4: a super admin's MCP key with NO membership is a viewer, and the suite
                    // authors over MCP — so the admin is a workspace admin of `default` (an author there).
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$MCP_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin') ON CONFLICT DO NOTHING",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${ADMIN_KEY.id.lowercase()}@keys.invalid', '${ADMIN_KEY.name}', 'key', " +
                            "'${ADMIN_KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, ADMIN_KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_USER_ID)
                        ps.setString(4, ADMIN_KEY.name)
                        ps.setString(5, ADMIN_KEY.hash)
                        ps.setString(6, MCP_WORKSPACE)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
