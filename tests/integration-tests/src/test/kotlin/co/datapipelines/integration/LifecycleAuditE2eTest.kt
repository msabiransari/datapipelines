package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
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
import java.sql.DriverManager
import java.util.UUID

/**
 * #332 on the real wire, one family at a time: a SESSION purge — the person's act the MCP dispatcher could never
 * record — leaves exactly ONE `audit_log` row, written by the same handler under the same `@RequiredScope`, and
 * the row's `details` are asserted WHOLE (ids, names, versions, the workspace — never a body, a SQL text or a
 * bind value, the security brief's redaction bound). Parameter-set draft purge (rest-api §21), visualization
 * version purge (§22), dashboard entity purge (§23): the three families the lane brief's Check-first names.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LifecycleAuditE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @Test
    @Order(1)
    fun `a session purge of a parameter-set draft leaves ONE audit row naming actor, workspace, set and version`() {
        val name = "lae/sales/audit_set_${UUID.randomUUID()}"
        val created = post("/api/v1/parameter-sets", """{"name": "$name", "display_name": "Audit", "parameters": []}""", 201)
        val id = created["id"].asText()
        val hash = created["body_hash"].asText()

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .post("/api/v1/parameter-sets/$id/draft/discard")
            .then()
            .statusCode(204)

        assertOneAuditRow(
            event = "parameter_set.version.purged",
            needle = id,
            details =
                detailsNode(
                    "parameter_set_id" to id,
                    "parameter_set_name" to name,
                    "version" to 1,
                    "workspace_id" to WORKSPACE_ID,
                ),
        )
    }

    @Test
    @Order(2)
    fun `a session purge of a visualization version leaves ONE audit row naming actor, workspace, artifact and version`() {
        val name = "lae/charts/audit_viz_${UUID.randomUUID()}"
        val created = post("/api/v1/visualizations", VISUALIZATION.replace("__NAME__", name), 201)
        val id = created["id"].asText()

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .delete("/api/v1/visualizations/$id/versions/1")
            .then()
            .statusCode(204)

        assertOneAuditRow(
            event = "visualization.version.purged",
            needle = id,
            details =
                detailsNode(
                    "visualization_id" to id,
                    "visualization_name" to name,
                    "version" to 1,
                    "workspace_id" to WORKSPACE_ID,
                ),
        )
    }

    @Test
    @Order(3)
    fun `a session purge of a dashboard entity leaves ONE audit row naming actor, workspace, artifact and version`() {
        seedSourcePipeline()
        val visualization = post("/api/v1/visualizations", VISUALIZATION.replace("__NAME__", "lae/charts/audit_${UUID.randomUUID()}"), 201)
        val board =
            post(
                "/api/v1/dashboards",
                DASHBOARD
                    .replace("__NAME__", "lae/boards/audit_${UUID.randomUUID()}")
                    .replace("__PIPELINE__", SOURCE_PIPELINE)
                    .replace("__VISUALIZATION__", visualization["name"].asText()),
                201,
            )
        val id = board["id"].asText()

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .delete("/api/v1/dashboards/$id")
            .then()
            .statusCode(204)

        assertOneAuditRow(
            event = "dashboard.purged",
            needle = id,
            details =
                detailsNode(
                    "dashboard_id" to id,
                    "dashboard_name" to board["name"].asText(),
                    "version" to 1,
                    "workspace_id" to WORKSPACE_ID,
                ),
        )
    }

    // ---- the audit row ---------------------------------------------------------------------------------

    /** The expected row's `details` — the whole JSON, key by key. */
    private fun detailsNode(vararg pairs: Pair<String, Any?>): ObjectNode {
        val node = mapper.createObjectNode()
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> node.put(key, value)
                is String -> node.put(key, value)
            }
        }
        return node
    }

    /** Exactly ONE row for [event] naming [needle], by [ADMIN_ID], whose `details` equal [details] ENTIRELY. */
    private fun assertOneAuditRow(
        event: String,
        needle: String,
        details: ObjectNode,
    ) {
        val rows = auditRows(event, needle)
        io.kotest.assertions.withClue("expected exactly one $event row naming $needle") { rows.size shouldBe 1 }
        val row = rows.single()
        row["user_id"].asText() shouldBe ADMIN_ID
        withClue("the row's details must be exactly the ids/names/versions/workspace — nothing else rides it") {
            row["details"] shouldBe details
        }
    }

    private fun auditRows(
        event: String,
        needle: String,
    ): List<JsonNode> {
        val out = mutableListOf<JsonNode>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            collectRows(connection, event, needle, out)
        }
        return out
    }

    private fun collectRows(
        connection: java.sql.Connection,
        event: String,
        needle: String,
        out: MutableList<JsonNode>,
    ) {
        connection
            .prepareStatement(
                "SELECT user_id, details_json FROM audit_log WHERE event = ? AND details_json::text LIKE ? ORDER BY timestamp ASC",
            ).use { ps ->
                ps.setString(1, event)
                ps.setString(2, "%$needle%")
                ps.executeQuery().use { rows ->
                    while (rows.next()) {
                        out.add(
                            mapper
                                .createObjectNode()
                                .put("user_id", rows.getString("user_id"))
                                .set("details", mapper.readTree(rows.getString("details_json"))),
                        )
                    }
                }
            }
    }

    // ---- the REST helpers ------------------------------------------------------------------------------

    /** A POST create, answered with its `data` envelope — the id, the name and the body_hash the verbs need. */
    private fun post(
        path: String,
        body: String,
        expected: Int,
    ): JsonNode {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(body)
                .`when`()
                .post(path)
        response.then().statusCode(expected)
        return mapper.readTree(response.body().asString())["data"]
    }

    // ---- the fixture -----------------------------------------------------------------------------------

    /** One RELEASED, read-only pipeline the dashboard's source pin names (the seeded rows, the L1b shape). */
    private fun seedSourcePipeline() {
        val pipelineId = UUID.randomUUID().toString()
        sql(
            """
            INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version) VALUES
                ('$pipelineId', '$SOURCE_PIPELINE', 'Source', '', '$ADMIN_ID', '$WORKSPACE_ID', 1)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO pipeline_versions
                (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at) VALUES
                ('$pipelineId', 1, '${pipelineBody(SOURCE_PIPELINE)}'::jsonb, 'hash-audit-source', 'RELEASED',
                 '$ADMIN_ID', '$ADMIN_ID', NOW())
            """.trimIndent(),
        )
    }

    private fun pipelineBody(name: String): String =
        """{"schema_version":1,"name":"$name","display_name":"P","description":"",""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"lae/templates/read.sql","version":1}}]}"""

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private companion object {
        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()
        const val WORKSPACE = "lae-audit"
        const val SOURCE_PIPELINE = "lae/pipelines/audit_source"

        val WORKSPACE_ID = UUID.randomUUID().toString()
        val ADMIN_ID = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "lae-admin@e2e.test", WORKSPACE)

        /** The implementation spec's §2.1 worked visualization (the L1b fixture). */
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

        /** The L1b dashboard fixture: one source, one pinned visualization. */
        val DASHBOARD =
            """
            {"name": "__NAME__", "display_name": "Revenue", "description": "",
             "sources": [{"name": "revenue_source", "pipeline": {"name": "__PIPELINE__", "version": 1}}],
             "visualizations": [{"name": "revenue_chart", "type": "visualization",
                                 "visualization": {"name": "__VISUALIZATION__", "version": 1},
                                 "inputs": {"revenue": {"source": "revenue_source"}}}],
             "layout": {"grid": [{"name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()

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
        }

        @JvmStatic
        @BeforeAll
        fun seedAuthRows() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES " +
                            "('$WORKSPACE_ID', '$WORKSPACE', 'Lifecycle audit E2E') ON CONFLICT (id) DO NOTHING",
                    )
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active) VALUES
                            ('$ADMIN_ID', 'lae-admin@e2e.test', 'LAE Admin', 'test', 'lae-admin-sub', TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin') ON CONFLICT DO NOTHING",
                    )
                }
            }
        }
    }
}
