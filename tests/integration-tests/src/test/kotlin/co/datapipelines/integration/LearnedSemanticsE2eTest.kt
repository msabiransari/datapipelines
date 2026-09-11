package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
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
 * The learned semantic layer's loop, closed ON THE WIRE (design §10.8, §10.4) — the full
 * application, the real MCP dispatcher and matrix, a real source Postgres whose schema this
 * test then changes underneath a recorded fact:
 *
 *  1. an acme author probes a table (`sql_probe`), records what it learned (`semantics_record`
 *     with that SELECT as evidence and the pipeline it was building), and reads it back
 *     BESIDE the column (`datasources_get_columns`) with its evidence, its trust and its link;
 *  2. a globex author on the SAME datasource (granted to both) sees the fact with its evidence
 *     — and NOT the pipeline link (D-S9), and a globex VIEWER key is refused the record through
 *     the real matrix;
 *  3. the audit log holds `semantics.recorded` with the kind and never the fact text;
 *  4. the column is RENAMED in the source: the next introspection serves the fact `stale`,
 *     writes that on the row, and shows it beside the table's listing; a column ADDED makes a
 *     fact on a surviving column `needs_review`; the superseding fact retires the old one.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class LearnedSemanticsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    @Test
    fun `record beside the columns, read it from another workspace, watch it go stale`() {
        E2eClean.beforeSeeding()
        seedSourceTable()
        seedAuthRows()
        registerDatasource()

        val factId = probeRecordAndReadBack()
        readFromTheOtherWorkspace(factId)
        auditRowCounts(factId)
        val onUnit = driftMarks(factId)
        supersede(factId, onUnit)
    }

    /** 1 — probe, record with evidence and the pipeline link, read back beside the column. */
    private fun probeRecordAndReadBack(): String {
        val probe = mcp(ALICE_KEY.plaintext, "sql_probe", mapOf("name" to DATASOURCE, "sql" to EVIDENCE_SQL))
        probe["rows"].size() shouldBe 2

        val recorded =
            mcp(
                ALICE_KEY.plaintext,
                "semantics_record",
                mapOf(
                    "scope" to "DATASOURCE",
                    "datasource" to DATASOURCE,
                    "kind" to "unit",
                    "fact" to "value_c is already in degrees Celsius — never tenths",
                    "refs" to listOf(mapOf("table" to "readings", "column" to "value_c")),
                    "evidence_sql" to EVIDENCE_SQL,
                    "source_pipeline_id" to PIPELINE_ACME,
                ),
            )
        val factId = recorded["id"].asText()
        assertAll(
            { recorded["trust"].asText() shouldBe "observed" },
            { recorded["evidence_summary"].asText() shouldContain "value_c=" },
            { recorded["from_this_workspace"].asBoolean() shouldBe true },
            { recorded["source_pipeline"]["name"].asText() shouldBe "finance/revenue" },
        )

        val acmeColumns = mcp(ALICE_KEY.plaintext, "datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "readings"))
        // Printed, not merely asserted: the handback wants the shape AS SERVED, verbatim.
        println("event=semantics.e2e.get_columns payload=${mapper.writeValueAsString(acmeColumns)}")
        println("event=semantics.e2e.tools_list payload=${toolsList()}")
        val onValue = acmeColumns.first { it["name"].asText() == "value_c" }["facts"]
        assertAll(
            { onValue.size() shouldBe 1 },
            { onValue[0]["id"].asText() shouldBe factId },
            { onValue[0]["trust"].asText() shouldBe "observed" },
            { onValue[0]["source_pipeline"]["id"].asText() shouldBe PIPELINE_ACME },
            { acmeColumns.first { it["name"].asText() == "id" }.has("facts") shouldBe false },
        )
        return factId
    }

    /** 2 — the fact and its evidence cross to globex; the pipeline link does not; a viewer cannot record. */
    private fun readFromTheOtherWorkspace(factId: String) {
        val globexColumns = mcp(BOB_KEY.plaintext, "datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "readings"))
        val seenByGlobex = globexColumns.first { it["name"].asText() == "value_c" }["facts"][0]
        assertAll(
            { seenByGlobex["id"].asText() shouldBe factId },
            { seenByGlobex["evidence_summary"].asText() shouldContain "value_c=" },
            { seenByGlobex["from_this_workspace"].asBoolean() shouldBe false },
            { seenByGlobex.has("source_pipeline") shouldBe false },
        )
        // The REST twin serves the identical block from the same code.
        val restColumns =
            given()
                .port(port)
                .header(API_KEY_HEADER, BOB_KEY.plaintext)
                .`when`()
                .get("/api/v1/datasources/$DATASOURCE/tables/readings/columns")
                .then()
                .statusCode(200)
                .extract()
                .asString()
        mapper.readTree(restColumns)["data"].first { it["name"].asText() == "value_c" }["facts"][0]["id"].asText() shouldBe factId

        // A viewer key (read scope) is refused the record by the real matrix — nothing written.
        val refused =
            mcpRaw(
                VIEWER_KEY.plaintext,
                "semantics_record",
                mapOf(
                    "scope" to "DATASOURCE",
                    "datasource" to DATASOURCE,
                    "kind" to "caveat",
                    "fact" to "a viewer should not be able to write this",
                    "refs" to listOf(mapOf("table" to "readings")),
                ),
            )
        refused shouldContain "auth.scope.insufficient"
    }

    /** 3 — the audit row the §9 acceptance counts: kind and id, never the fact text. */
    private fun auditRowCounts(factId: String) {
        val audit = auditRows("semantics.recorded")
        val details = mapper.readTree(audit.single())
        assertAll(
            { details["kind"].asText() shouldBe "unit" },
            { details["fact_id"].asText() shouldBe factId },
            { details["evidence"].asBoolean() shouldBe true },
            { details["refs"].map { it.asText() } shouldContainExactly listOf("readings.value_c") },
            { audit.single().contains("Celsius") shouldBe false },
        )
    }

    /** 4a — rename → stale (row written, shown on the table); add → needs_review. Returns the second fact's id. */
    private fun driftMarks(factId: String): String {
        alterSource("ALTER TABLE readings RENAME COLUMN value_c TO value_celsius")
        val afterRename = mcp(ALICE_KEY.plaintext, "datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "readings"))
        afterRename.none { it.has("facts") } shouldBe true
        withClue("the §6 mark is WRITTEN on the read path") { trustOf(factId) shouldBe "stale" }
        val tables = mcp(ALICE_KEY.plaintext, "datasources_get_tables", mapOf("name" to DATASOURCE))
        val readingsEntry = tables["tables"].first { it["name"].asText() == "readings" }
        assertAll(
            { readingsEntry["facts"][0]["id"].asText() shouldBe factId },
            { readingsEntry["facts"][0]["trust"].asText() shouldBe "stale" },
            { readingsEntry["facts"][0]["drift"].asText() shouldContain "no longer exists" },
        )

        // A fact on a SURVIVING column, recorded against the renamed shape…
        val onUnit =
            mcp(
                ALICE_KEY.plaintext,
                "semantics_record",
                mapOf(
                    "scope" to "DATASOURCE",
                    "datasource" to DATASOURCE,
                    "kind" to "format",
                    "fact" to "unit is a lowercase SI symbol, never blank",
                    "refs" to listOf(mapOf("table" to "readings", "column" to "unit")),
                ),
            )["id"].asText()
        // …goes needs_review when a column is ADDED around it (the set changed, the ref resolves).
        alterSource("ALTER TABLE readings ADD COLUMN quality SMALLINT")
        val afterAdd = mcp(ALICE_KEY.plaintext, "datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "readings"))
        val unitFact = afterAdd.first { it["name"].asText() == "unit" }["facts"][0]
        assertAll(
            { unitFact["trust"].asText() shouldBe "needs_review" },
            { unitFact["drift"].asText() shouldBe "table columns changed since this was recorded" },
            { trustOf(onUnit) shouldBe "needs_review" },
        )
        return onUnit
    }

    /** 4b — the superseding fact retires its predecessor and takes its place beside the column. */
    private fun supersede(
        factId: String,
        onUnit: String,
    ) {
        val replacement =
            mcp(
                ALICE_KEY.plaintext,
                "semantics_record",
                mapOf(
                    "scope" to "DATASOURCE",
                    "datasource" to DATASOURCE,
                    "kind" to "unit",
                    "fact" to "value_celsius is already in degrees Celsius — never tenths",
                    "refs" to listOf(mapOf("table" to "readings", "column" to "value_celsius")),
                    "evidence_sql" to "SELECT value_celsius FROM readings ORDER BY id",
                    "supersedes" to factId,
                ),
            )
        val listing = mcp(ALICE_KEY.plaintext, "semantics_list", mapOf("datasource" to DATASOURCE, "include_retired" to true))["facts"]
        val old = listing.first { it["id"].asText() == factId }
        assertAll(
            { replacement["supersedes"].asText() shouldBe factId },
            { old["trust"].asText() shouldBe "retired" },
            { old["retired_reason"].asText() shouldBe "superseded" },
            { listing.map { it["id"].asText() }.toSet() shouldBe setOf(factId, onUnit, replacement["id"].asText()) },
            { auditRows("semantics.recorded").size shouldBe 3 },
        )
        val served = mcp(ALICE_KEY.plaintext, "datasources_get_columns", mapOf("name" to DATASOURCE, "table" to "readings"))
        val onCelsius = served.first { it["name"].asText() == "value_celsius" }["facts"]
        onCelsius.map { it["id"].asText() } shouldContainExactly listOf(replacement["id"].asText())
        onCelsius[0]["trust"].asText() shouldBe "observed"
    }

    // ------------------------------------------------------------------ the wire

    /** One `tools/call`; the result's payload as JSON. A tool error fails the test with the envelope. */
    private fun mcp(
        key: String,
        tool: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val body = mcpRaw(key, tool, arguments)
        val envelope = mapper.readTree(body)
        check(!envelope.has("error")) { "$tool over MCP failed at the protocol: $body" }
        val result = envelope["result"]
        check(result["isError"]?.asBoolean() != true) { "$tool refused: $body" }
        return mapper.readTree(result["content"][0]["text"].asText())
    }

    /** The three tools' `tools/list` entries, verbatim — the handback's evidence. */
    private fun toolsList(): String {
        val rpc =
            mapper.writeValueAsString(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 2,
                    "method" to "tools/list",
                    "params" to emptyMap<String, Any>(),
                ),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header(API_KEY_HEADER, ALICE_KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(rpc))
                .build()
        val tools = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())["result"]["tools"]
        return mapper.writeValueAsString(tools.filter { it["name"].asText().startsWith("semantics_") })
    }

    private fun mcpRaw(
        key: String,
        tool: String,
        arguments: Map<String, Any?>,
    ): String {
        val rpc =
            mapper.writeValueAsString(
                mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to arguments)),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header(API_KEY_HEADER, key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(rpc))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        return response.body()
    }

    // ------------------------------------------------------------------ the world

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, ALICE_KEY.plaintext)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Sensor warehouse", "dialect": "POSTGRES",
                 "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
        // Granted to globex too — the D-S9 half needs two workspaces on ONE datasource.
        metadata {
            it.execute(
                "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by)" +
                    " VALUES ('$DATASOURCE', '$WS_GLOBEX', '$ALICE')",
            )
        }
    }

    private fun seedSourceTable() {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS readings")
                st.execute(
                    "CREATE TABLE readings (id SERIAL PRIMARY KEY, station TEXT NOT NULL," +
                        " value_c NUMERIC(6,2) NOT NULL, unit TEXT NOT NULL)",
                )
                st.execute("INSERT INTO readings (station, value_c, unit) VALUES ('north', 21.40, 'c'), ('south', 19.05, 'c')")
            }
        }
    }

    private fun alterSource(ddl: String) {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { it.execute(ddl) }
        }
    }

    private fun seedAuthRows() {
        metadata { st ->
            st.execute(
                """
                INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ACME', 'acme', 'Acme'), ('$WS_GLOBEX', 'globex', 'Globex')
                """.trimIndent(),
            )
            st.execute(
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                    ('$ALICE', 'alice@acme.test', 'Alice', 'test', 'sem-alice', TRUE, TRUE),
                    ('$BOB', 'bob@globex.test', 'Bob', 'test', 'sem-bob', TRUE, FALSE),
                    ('$VERA', 'vera@globex.test', 'Vera', 'test', 'sem-vera', TRUE, FALSE)
                """.trimIndent(),
            )
            st.execute(
                """
                INSERT INTO workspace_members (workspace_id, user_id, author, promoter, admin) VALUES
                    ('$WS_ACME', '$ALICE', TRUE, FALSE, TRUE),
                    ('$WS_GLOBEX', '$BOB', TRUE, FALSE, FALSE),
                    ('$WS_GLOBEX', '$VERA', FALSE, FALSE, FALSE)
                """.trimIndent(),
            )
            st.execute(
                """
                INSERT INTO pipelines (id, name, display_name, description, owner_id, workspace_id, current_version)
                VALUES ('$PIPELINE_ACME', 'finance/revenue', 'Revenue', 'acme''s pipeline', '$ALICE', '$WS_ACME', 1)
                """.trimIndent(),
            )
            // A pipeline is LIVE only with a DRAFT/RELEASED version row (the entity's derived status).
            st.execute(
                """
                INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by, released_at)
                VALUES ('$PIPELINE_ACME', 1, '$PIPELINE_BODY'::jsonb, 'seed-hash', 'RELEASED', '$ALICE', '$ALICE', NOW())
                """.trimIndent(),
            )
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id) VALUES (?, ?, ?, ?, ?, ?)")
                .use { ps ->
                    for ((key, owner, workspace) in listOf(
                        Triple(ALICE_KEY, ALICE, WS_ACME),
                        Triple(BOB_KEY, BOB, WS_GLOBEX),
                        Triple(VIEWER_KEY, VERA, WS_GLOBEX),
                    )) {
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(owner))
                        ps.setString(3, key.name)
                        ps.setString(4, key.hash)
                        ps.setArray(5, connection.createArrayOf("text", key.scopes))
                        ps.setObject(6, UUID.fromString(workspace))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
        }
    }

    private fun metadata(block: (java.sql.Statement) -> Unit) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use(block)
        }
    }

    private fun trustOf(factId: String): String =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT trust FROM learned_facts WHERE id = ?::uuid").use { ps ->
                ps.setString(1, factId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "fact $factId is not in learned_facts" }
                    rs.getString(1)
                }
            }
        }

    private fun auditRows(event: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT details_json::text FROM audit_log WHERE event = ? ORDER BY id").use { ps ->
                ps.setString(1, event)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DATASOURCE = "sensor-warehouse"
        private const val EVIDENCE_SQL = "SELECT value_c, unit FROM readings ORDER BY id"
        private const val SECRET_BYTES = 32

        private const val WS_ACME = "acbe0000-0000-0000-0000-0000000000a1"
        private const val WS_GLOBEX = "acbe0000-0000-0000-0000-0000000000b2"
        private const val ALICE = "5e5a0000-0000-0000-0000-0000000000a1"
        private const val BOB = "5e5a0000-0000-0000-0000-0000000000b2"
        private const val VERA = "5e5a0000-0000-0000-0000-0000000000c3"
        private const val PIPELINE_ACME = "5e5a0000-0000-0000-0000-0000000000d4"
        private const val PIPELINE_BODY =
            """{"schema_version":1,"name":"finance/revenue","display_name":"Revenue","description":"",""" +
                """"nodes":[{"id":"n1","type":"DQL","source":"tempdb","template":{"id":"test/t","version":1}}]}"""

        private val ALICE_KEY = E2eAuth.generateKey("sem-alice-key", arrayOf("read", "execute", "author"))
        private val BOB_KEY = E2eAuth.generateKey("sem-bob-key", arrayOf("read", "execute", "author"))
        private val VIEWER_KEY = E2eAuth.generateKey("sem-vera-key", arrayOf("read"))

        private val random = SecureRandom()
        private val jwtSecret: String = Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val source = SharedE2e.scratchDatabase("semantics_source")
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
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(SECRET_BYTES).also { random.nextBytes(it) })
            }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
