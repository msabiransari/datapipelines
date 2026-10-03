package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **#376 — the durable evaluation history, end to end** (the parameter-set workspace spec §12 scenarios 9 and 10, §13
 * Enumeration, §2.3). Real HTTP against the real stack: a real Postgres customer source, released selector templates, a
 * released set, a draft-only set, an MCP key, and a STUB higher environment (the promoter lens's inventory, the
 * `PromoterLensSweepTest` mould) so a promoter's lens is a real one.
 *
 * The walk: an MCP `parameter_sets_evaluate` and a REST evaluate each land ONE record with the right `caller` and
 * principal (the key's id, the session's user) and their statement rows — the two payloads identical; the History tab
 * and its partials list them for a viewer, a row's detail names its attempts; a DRAFT evaluated by its number is a
 * record the author sees and the promoter's lens does not; the promoter reads the released set's history but a set the
 * lens hides is the page's own 404 on the page, the list partial and the detail partial alike — same status, same body;
 * and the hourly retention tick, run through the real bean, closes a lost RUNNING record INCOMPLETE and deletes an
 * expired one with its attempts.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk over one fixture, as the sibling E2Es are
class ParameterEvaluationHistoryE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()

    // ---- the walk ------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - people, an MCP key, a Postgres source, released selector templates, a released set and a draft-only set`() {
        seedPeople()
        registerDatasource()
        createAndReleaseTemplate(STATES, STATES_SQL)
        createAndReleaseTemplate(CITIES, CITIES_SQL)
        visibleSetId = createSet(VISIBLE_SET, release = true)
        hiddenSetId = createSet(HIDDEN_SET, release = false)
    }

    @Test
    @Order(2)
    fun `scenario 10 - an MCP and a REST evaluate each land ONE record, caller and principal right, payloads identical`() {
        val selections = mapOf("country" to "US", "state" to "NJ")

        val mcp = callTool("parameter_sets_evaluate", mapOf("id" to visibleSetId, "selections" to selections))
        val rest =
            post("/api/v1/parameter-sets/$visibleSetId/evaluate", mapper.writeValueAsString(mapOf("selections" to selections)), ADMIN)

        withClue(rest.second.take(EXCERPT)) { rest.first shouldBe 200 }
        withClue("the record is a side channel: both callers' payloads are the same response") {
            mapper.readTree(rest.second)["data"] shouldBe mcp
        }
        val rows = records(visibleSetId)
        rows.map { it["caller"] } shouldContainExactlyInAnyOrder listOf("MCP", "REST")
        rows.forEach { withClue(it) { it["status"] shouldBe "COMPLETED" } }
        val byCaller = rows.associateBy { it["caller"] }
        (byCaller.getValue("MCP")["principal_key_id"] to byCaller.getValue("MCP")["principal_user_id"]) shouldBe (MCP_KEY.id to null)
        (byCaller.getValue("REST")["principal_user_id"] to byCaller.getValue("REST")["principal_key_id"]) shouldBe (ADMIN_ID to null)
        rows.forEach { row ->
            withClue("one EXECUTED attempt per template-backed parameter (state, city); the constants country runs none") {
                attempts(row["id"] as String) shouldContainExactlyInAnyOrder listOf("state|EXECUTED", "city|EXECUTED")
            }
        }
    }

    @Test
    @Order(3)
    fun `scenario 9 - a viewer reads the History tab, pages the partial and opens a record's attempts`() {
        val page = get("/parameter-sets/$visibleSetId?tab=history", VIEWER)
        withClue(page.second.take(EXCERPT)) { page.first shouldBe 200 }
        page.second shouldContain "data-ps-history"
        page.second shouldContain "data-caller=\"MCP\""
        page.second shouldContain "data-caller=\"REST\""
        withClue("the History arm mounts no live form, so opening it evaluates nothing") {
            page.second shouldNotContain "id=\"ps-form-host\""
            records(visibleSetId).size shouldBe 2
        }

        val partial = get("/partials/parameter-sets/$visibleSetId/evaluations?offset=0", VIEWER)
        partial.first shouldBe 200
        partial.second shouldContain "data-evaluation-id="

        val evaluationId = records(visibleSetId).first { it["caller"] == "MCP" }["id"] as String
        val detail = get("/partials/parameter-sets/$visibleSetId/evaluations/$evaluationId", VIEWER)
        withClue(detail.second.take(EXCERPT)) { detail.first shouldBe 200 }
        detail.second shouldContain "data-history-queries"
        detail.second shouldContain "data-parameter=\"state\""
        detail.second shouldContain "data-outcome=\"EXECUTED\""
        detail.second shouldContain MCP_KEY.id
    }

    @Test
    @Order(4)
    fun `a draft evaluated by its number is a record the author sees and the promoter's lens does not`() {
        writeDraft(visibleSetId)
        val draft = post("/api/v1/parameter-sets/$visibleSetId/evaluate", """{"version":2,"selections":{}}""", ADMIN)
        withClue(draft.second.take(EXCERPT)) { draft.first shouldBe 200 }
        val draftRecord = records(visibleSetId).single { it["parameter_set_version"] == 2 }["id"] as String

        get("/partials/parameter-sets/$visibleSetId/evaluations", ADMIN).second shouldContain draftRecord
        val promoter = get("/partials/parameter-sets/$visibleSetId/evaluations", PROMOTER)
        withClue(promoter.second.take(EXCERPT)) { promoter.first shouldBe 200 }
        withClue("non-vacuity: the promoter DOES read this set's released records") { promoter.second shouldContain "data-caller=\"REST\"" }
        promoter.second shouldNotContain draftRecord
        get("/partials/parameter-sets/$visibleSetId/evaluations/$draftRecord", PROMOTER).first shouldBe 404
    }

    @Test
    @Order(5)
    fun `scenario 9 - a set the promoter's lens hides is the page's own 404 on the page and on both partials`() {
        seedRecord(hiddenSetId, "COMPLETED", startedSecondsAgo = 10)
        val hiddenRecord = records(hiddenSetId).single()["id"] as String
        withClue("non-vacuity: the author reads the draft-only set's history") {
            get("/partials/parameter-sets/$hiddenSetId/evaluations", ADMIN).second shouldContain hiddenRecord
        }

        val page = get("/parameter-sets/$hiddenSetId?tab=history", PROMOTER)
        val list = get("/partials/parameter-sets/$hiddenSetId/evaluations", PROMOTER)
        val detail = get("/partials/parameter-sets/$hiddenSetId/evaluations/$hiddenRecord", PROMOTER)

        page.first shouldBe 404
        list.first shouldBe 404
        detail.first shouldBe 404
        withClue("one answer — the same body, no route confirms the set exists (correlation ids normalised)") {
            normalised(list.second) shouldBe normalised(page.second)
            normalised(detail.second) shouldBe normalised(page.second)
        }
        list.second shouldNotContain hiddenRecord
        withClue("and the promoter's lens really asked the stub target") { stubRequests.size shouldBeGreaterThanOrEqual 1 }
    }

    @Test
    @Order(6)
    fun `the hourly retention tick closes a lost RUNNING record INCOMPLETE and deletes an expired one with its attempts`() {
        val lost = seedRecord(visibleSetId, "RUNNING", startedSecondsAgo = 3_600)
        val young = seedRecord(visibleSetId, "RUNNING", startedSecondsAgo = 5)
        val expired = seedRecord(visibleSetId, "COMPLETED", startedSecondsAgo = 8L * 24 * 3_600)
        sql(
            "INSERT INTO parameter_evaluation_queries (id, evaluation_id, parameter, datasource, template_id, template_version, outcome) " +
                "VALUES ('${UUID.randomUUID()}', '$expired', 'state', '$DATASOURCE', '$STATES', 1, 'EXECUTED')",
        )

        context.getBean("executionEventRetentionScheduler").let { it.javaClass.getMethod("retain").invoke(it) }

        val after = records(visibleSetId).associateBy { it["id"] }
        after.getValue(lost)["status"] shouldBe "INCOMPLETE"
        after.getValue(lost)["finished_at"] shouldNotBe null
        after.getValue(young)["status"] shouldBe "RUNNING"
        withClue("the expired record and its attempt are gone; the walk's own fresh records are kept") {
            after.containsKey(expired) shouldBe false
            count("SELECT count(*) FROM parameter_evaluation_queries WHERE evaluation_id = '$expired'") shouldBe 0
            after.values.count { it["status"] == "COMPLETED" } shouldBe 3
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------------------------------

    private fun get(
        path: String,
        session: String,
    ): Pair<Int, String> {
        val response =
            given()
                .port(port)
                .asSession(session)
                .redirects()
                .follow(false)
                .get(path)
        return response.statusCode to response.asString()
    }

    private fun post(
        path: String,
        body: String,
        session: String,
    ): Pair<Int, String> {
        val response =
            given()
                .port(port)
                .asSession(session)
                .contentType(ContentType.JSON)
                .body(body)
                .post(path)
        return response.statusCode to response.asString()
    }

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", MCP_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(
                            mapOf(
                                "jsonrpc" to "2.0",
                                "id" to 1,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val body = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
        withClue("tool $name: $body") { body.has("error") shouldBe false }
        return mapper.readTree(body["result"]["content"][0]["text"].asText())
    }

    /** The UI 404 carries a per-request correlation id; everything else must be byte-identical. */
    private fun normalised(body: String): String = UUID_TEXT.replace(body, "{{UUID}}")

    // ---- the fixture ---------------------------------------------------------------------------------------------------

    private fun seedPeople() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Evaluation history E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'pe376-admin@e2e.test', 'PE Admin', 'test', 'pe376-admin-sub', TRUE, TRUE),
                ('$VIEWER_ID', 'pe376-viewer@e2e.test', 'PE Viewer', 'test', 'pe376-viewer-sub', TRUE, FALSE),
                ('$PROMOTER_ID', 'pe376-promoter@e2e.test', 'PE Promoter', 'test', 'pe376-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter')
            """.trimIndent(),
        )
        // Keys v2 (A13): the key acts as its own identity and holds its chosen role.
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                "('$KEY_IDENTITY', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', '${MCP_KEY.id}', " +
                "TRUE, FALSE, 'service')",
        )
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'author')",
                ).use { ps ->
                    ps.setString(1, MCP_KEY.id)
                    ps.setObject(2, UUID.fromString(KEY_IDENTITY))
                    ps.setObject(3, UUID.fromString(ADMIN_ID))
                    ps.setString(4, MCP_KEY.name)
                    ps.setString(5, MCP_KEY.hash)
                    ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                    ps.executeUpdate()
                }
        }
    }

    private fun registerDatasource() {
        val source = SharedE2e.scratchDatabase("pe376_source")
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"$DATASOURCE","display_name":"Evaluation history source","dialect":"POSTGRES",""" +
                        """"jdbc_url":"${source.jdbcUrl}","username":"${source.username}","password":"${source.password}"}""",
                ).post("/api/v1/datasources")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
    }

    private fun createAndReleaseTemplate(
        id: String,
        body: String,
    ) {
        val response =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to id,
                            "dialect" to "POSTGRES",
                            "display_name" to id,
                            "description" to "",
                            "imports" to emptyList<String>(),
                            "body" to body,
                        ),
                    ),
                ).post("/api/v1/templates")
        withClue("template $id: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 201 }
        sql(
            "UPDATE template_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND template_id = (SELECT id FROM templates WHERE workspace_id = '$WORKSPACE_ID' AND name = '$id')",
        )
    }

    private fun document(
        name: String,
        displayName: String,
    ): String =
        mapper.writeValueAsString(
            mapOf(
                "name" to name,
                "display_name" to displayName,
                "description" to "",
                "parameters" to cascadeParameters(),
            ),
        )

    private fun createSet(
        name: String,
        release: Boolean,
    ): String {
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(document(name, name))
                .post("/api/v1/parameter-sets")
        withClue("set $name: ${created.asString().take(EXCERPT)}") { created.statusCode shouldBe 201 }
        val data = mapper.readTree(created.asString())["data"]
        if (release) {
            val released =
                given()
                    .port(port)
                    .asSession(ADMIN)
                    .contentType(ContentType.JSON)
                    .header("If-Match", data["body_hash"].asText())
                    .post("/api/v1/parameter-sets/${data["id"].asText()}/release")
            withClue("release $name: ${released.asString().take(EXCERPT)}") { released.statusCode shouldBe 200 }
        }
        return data["id"].asText()
    }

    /** A DRAFT v2 over the released v1 — the same parameters under a new display name. */
    private fun writeDraft(setId: String) {
        val current = mapper.readTree(get("/api/v1/parameter-sets/$setId", ADMIN).second)["data"]
        val written =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .header("If-Match", current["body_hash"].asText())
                .body(document(VISIBLE_SET, "History draft"))
                .put("/api/v1/parameter-sets/$setId")
        withClue("draft: ${written.asString().take(EXCERPT)}") { written.statusCode shouldBe 200 }
    }

    private fun cascadeParameters(): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "name" to "country",
                "label" to "Country",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "source" to
                    mapOf(
                        "constants" to
                            listOf(
                                mapOf("value" to "US", "display_value" to "US", "is_default" to true),
                                mapOf("value" to "CA", "display_value" to "CA", "is_default" to false),
                            ),
                    ),
            ),
            templateSelect("state", STATES, listOf("country")),
            templateSelect("city", CITIES, listOf("state")),
        )

    private fun templateSelect(
        name: String,
        template: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "label" to name.replaceFirstChar { it.uppercase() },
            "type" to "STRING",
            "kind" to "SELECT",
            "cardinality" to "SINGLE",
            "source" to mapOf("template" to mapOf("id" to template, "version" to 1), "datasource" to DATASOURCE),
            "depends_on" to dependsOn,
        )

    private fun seedRecord(
        setId: String,
        status: String,
        startedSecondsAgo: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        val finished = if (status == "RUNNING") "NULL" else "NOW() - make_interval(secs => ${startedSecondsAgo - 1})"
        val valid = if (status == "COMPLETED") "TRUE" else "NULL"
        sql(
            "INSERT INTO parameter_evaluations (id, workspace_id, parameter_set_id, parameter_set_version, caller, principal_user_id, " +
                "status, valid, started_at, finished_at) VALUES ('$id', '$WORKSPACE_ID', '$setId', 1, 'REST', '$ADMIN_ID', '$status', " +
                "$valid, NOW() - make_interval(secs => $startedSecondsAgo), $finished)",
        )
        return id
    }

    /** The set's records as the database holds them — the persisted level is the evidence. */
    private fun records(setId: String): List<Map<String, Any?>> =
        query(
            "SELECT id::text AS id, caller, status, principal_user_id::text AS principal_user_id, principal_key_id, " +
                "parameter_set_version, finished_at FROM parameter_evaluations WHERE parameter_set_id = '$setId' ORDER BY started_at",
        )

    private fun attempts(evaluationId: String): List<String> =
        query("SELECT parameter, outcome FROM parameter_evaluation_queries WHERE evaluation_id = '$evaluationId'")
            .map { "${it["parameter"]}|${it["outcome"]}" }

    private fun count(statement: String): Int = (query(statement).single().values.single() as Number).toInt()

    private fun query(statement: String): List<Map<String, Any?>> {
        val pg = SharedE2e.postgres
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery(statement).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    buildList { while (rs.next()) add(columns.associateWith { rs.getObject(it) }) }
                }
            }
        }
    }

    private fun sql(statement: String) {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    companion object {
        private const val EXCERPT = 600
        private const val HTTP_OK = 200

        private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        private const val WORKSPACE_ID = "e3760000-0000-0000-0000-000000000001"
        private const val WORKSPACE = "pe376-ws"
        private const val ADMIN_ID = "e3760000-0000-0000-0000-0000000000a1"
        private const val VIEWER_ID = "e3760000-0000-0000-0000-0000000000b1"
        private const val PROMOTER_ID = "e3760000-0000-0000-0000-0000000000c1"
        private const val KEY_IDENTITY = "e3760000-0000-0000-0000-0000000000d1"
        private const val DATASOURCE = "pe376-src"

        private val MCP_KEY = E2eAuth.generateKey("pe376-history")

        /** The metadata database persists across runs in the shared container; names are unique forever — a fresh folder per run. */
        val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        val STATES = "test/ph$RUN/states.sql"
        val CITIES = "test/ph$RUN/cities.sql"
        val VISIBLE_SET = "test/ph$RUN/geo_filters"
        val HIDDEN_SET = "test/ph$RUN/draft_filters"

        const val STATES_SQL =
            "SELECT v AS \"value\", v AS \"display_value\", (v = 'NY') AS \"is_default\" " +
                "FROM (VALUES ('NY'), ('NJ')) AS t(v) WHERE :country = 'US' ORDER BY v"
        const val CITIES_SQL =
            "SELECT c AS \"value\", c AS \"display_value\", FALSE AS \"is_default\" " +
                "FROM (VALUES ('NY', 'New York City'), ('NJ', 'Newark')) AS t(s, c) WHERE s = :state ORDER BY c"

        var visibleSetId: String = ""
        var hiddenSetId: String = ""

        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "pe376-admin@e2e.test", WORKSPACE)
        private val VIEWER get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "pe376-viewer@e2e.test", WORKSPACE)
        private val PROMOTER get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "pe376-promoter@e2e.test", WORKSPACE)

        private val SERVER_KEY = "pe376-" + UUID.randomUUID()

        /** The stub higher environment: an inventory that holds no parameter set — a released set is visible, a draft is not. */
        private val stubRequests = CopyOnWriteArrayList<String>()
        private val stub: HttpServer by lazy {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
                server.createContext("/api/v1/promotion/inventory") { exchange ->
                    stubRequests += exchange.requestURI.toString()
                    val bytes =
                        (
                            """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat","authoring_enabled":false,""" +
                                """"workspace":"$WORKSPACE","pipelines":[],"templates":[],"datasources":[]}}"""
                        ).toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                server.start()
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${stub.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { SERVER_KEY }
            registry.add("datapipelines.deployment.promotion.inventory-cache-ttl-seconds") { "600" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            runCatching { stub.stop(0) }
        }
    }
}
