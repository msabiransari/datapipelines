package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
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
 * The semantic link end to end (lane 7e, #7; transform-nodes design §2.3, §8.2, §8.3 and §10.7)
 * — the full application, the real MCP dispatcher and matrix, a real source Postgres:
 *
 *  1. an acme author records a WORKSPACE `definition` over MCP and creates a jsonata transform
 *     citing it (`implements`);
 *  2. discovery from both ends: `semantics_list` and the `definitions` on `datasources_list`
 *     carry `implemented_by`, and `templates_list {implements}` lists the transform;
 *  3. a SECOND workspace — its MCP key on `/mcp` and its member's session over REST — sees
 *     neither the rule nor the transform, and cannot cite the rule (`implements_unresolved`);
 *  4. the rule is superseded: `templates_get` reads `needs_review`, naming the retired fact and
 *     its successor;
 *  5. a pipeline pinning the transform is RELEASED over REST: status RELEASED, and the body's
 *     `warnings` carries `pipeline.release.template_needs_review` for the pin;
 *  6. re-citing the successor on the RELEASED version opens no draft and clears the mark;
 *  7. an export → import round trip keeps the citation in the same workspace and drops it in
 *     another (owner ruling 2026-09-25).
 *
 * REST legs run as signed-in SESSIONS (#215 B2); only `/mcp` legs use MCP keys — each member's
 * key acts as the member, capped at author (PK4).
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class TransformImplementsE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    @Test
    fun `cite a rule, find it from both ends, drift, release with a warning, re-cite, move it`() {
        E2eClean.beforeSeeding()
        seedSourceTable()
        seedAuthRows()
        registerDatasource()

        val rule = recordRule("rainy = precipitation_mm at or above 2.5 on the day, whole days only")
        val transform = createTransform(listOf(rule))
        discoveryFromBothEnds(rule, transform)
        theOtherWorkspaceSeesNothing(rule)
        val pipeline = createPipeline()
        val successor = supersede(rule)
        val releasedHash = releaseWarnsAndProceeds(pipeline, rule, successor)
        reCiteClears(successor, releasedHash)
        importRoundTrip(successor)
    }

    /** 1a — the WORKSPACE rule, no refs (a rule may span tables), recorded over MCP. */
    private fun recordRule(text: String): String {
        val recorded =
            mcp(
                ALICE_KEY.plaintext,
                "semantics_record",
                mapOf("scope" to "WORKSPACE", "datasource" to DATASOURCE, "kind" to "definition", "fact" to text),
            )
        recorded["trust"].asText() shouldBe "asserted"
        return recorded["id"].asText()
    }

    /** 1b — the transform cites the rule; the create's own result carries the citation. */
    private fun createTransform(implements: List<String>): JsonNode {
        val created = mcp(ALICE_KEY.plaintext, "templates_create", transformArgs() + ("implements" to implements))
        println("event=implements.e2e.templates_create payload=${mapper.writeValueAsString(created)}")
        assertAll(
            { created["status"].asText() shouldBe "DRAFT" },
            { created["implements"].map { it.asText() } shouldContainExactly implements },
            { created["needs_review"].asBoolean() shouldBe false },
            { created.has("retired_facts") shouldBe false },
        )
        return created
    }

    /** 2 — from the rule to the transform, and from the transform list to the rule. */
    private fun discoveryFromBothEnds(
        rule: String,
        transform: JsonNode,
    ) {
        val expected = listOf(mapOf("template_id" to TRANSFORM, "version" to 1))
        val listed = mcp(ALICE_KEY.plaintext, "semantics_list", mapOf("datasource" to DATASOURCE))["facts"]
        println("event=implements.e2e.semantics_list payload=${mapper.writeValueAsString(listed)}")
        val onListing =
            mcp(ALICE_KEY.plaintext, "datasources_list", emptyMap()).first { it["name"].asText() == DATASOURCE }["definitions"]
        val byFilter = mcp(ALICE_KEY.plaintext, "templates_list", mapOf("implements" to rule))
        assertAll(
            { implementedBy(listed.single { it["id"].asText() == rule }) shouldBe expected },
            { implementedBy(onListing.single { it["id"].asText() == rule }) shouldBe expected },
            { byFilter.map { it["id"].asText() } shouldContainExactly listOf(transform["id"].asText()) },
            { mcp(ALICE_KEY.plaintext, "templates_list", mapOf("implements" to UUID.randomUUID().toString())).size() shouldBe 0 },
        )
        // The REST twins: the listing's definitions and the list filter, as Alice's session.
        val restDatasource = rest(ALICE_SESSION).get("/api/v1/datasources/$DATASOURCE").body()
        implementedBy(restDatasource["data"]["definitions"].single { it["id"].asText() == rule }) shouldBe expected
        rest(ALICE_SESSION).get("/api/v1/templates?implements=$rule").body()["data"]["items"].map { it["id"].asText() } shouldBe
            listOf(TRANSFORM)
    }

    /** 3 — globex (the same datasource is granted to it) sees neither half, on either surface, and cannot cite. */
    private fun theOtherWorkspaceSeesNothing(rule: String) {
        val facts = mcp(BOB_KEY.plaintext, "semantics_list", mapOf("datasource" to DATASOURCE))["facts"]
        val listing = mcp(BOB_KEY.plaintext, "datasources_get", mapOf("name" to DATASOURCE))["definitions"]
        assertAll(
            { facts.none { it["id"].asText() == rule } shouldBe true },
            { listing.size() shouldBe 0 },
            { mcp(BOB_KEY.plaintext, "templates_list", mapOf("implements" to rule)).size() shouldBe 0 },
            { mcpRaw(BOB_KEY.plaintext, "templates_get", mapOf("id" to TRANSFORM)) shouldContain "template.not_found" },
        )
        // Citing another workspace's rule is the not-found answer — never "it exists but is not yours".
        val theirs = transformArgs(id = "test/their_rainy.jsonata") + ("implements" to listOf(rule))
        val refused = mcpError(BOB_KEY.plaintext, "templates_create", theirs)
        // A validation refusal lists every failure under details.failures (the template house shape).
        val failure = refused["details"]["failures"].single()
        assertAll(
            { refused["code"].asText() shouldBe "template.implements_unresolved" },
            { failure["code"].asText() shouldBe "template.implements_unresolved" },
            { failure["details"]["reason"].asText() shouldBe "unknown" },
            { failure["details"]["fact_id"].asText() shouldBe rule },
        )
        // Bob's session over REST: the same blindness.
        rest(BOB_SESSION).get("/api/v1/templates?implements=$rule").body()["data"]["items"].size() shouldBe 0
        rest(BOB_SESSION).get("/api/v1/datasources/$DATASOURCE").body()["data"]["definitions"].size() shouldBe 0
    }

    /** A draft pipeline pinning the transform (and the SQL stage it reads), created as Alice's session. */
    private fun createPipeline(): Pair<String, String> {
        val stage =
            rest(ALICE_SESSION)
                .post(
                    "/api/v1/templates",
                    """
                    {"id": "$STAGE_TEMPLATE", "dialect": "POSTGRES", "display_name": "Days", "description": "7e E2E",
                     "imports": [], "body": "SELECT day, precipitation_mm FROM days ORDER BY day"}
                    """.trimIndent(),
                    expect = 201,
                ).body()
        stage["data"]["version"].asInt() shouldBe 1
        val body =
            mapOf(
                "schema_version" to 1,
                "name" to PIPELINE,
                "display_name" to "Rainy days",
                "description" to "7e E2E",
                "parameters" to emptyMap<String, Any>(),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "stage_days",
                            "description" to "the days to tempdb",
                            "type" to "DQL",
                            "source" to DATASOURCE,
                            "template" to mapOf("id" to STAGE_TEMPLATE, "version" to 1),
                            "output" to mapOf("target" to "tempdb", "table" to "stg_days"),
                            "depends_on" to emptyList<String>(),
                        ),
                        mapOf(
                            "id" to "flag_rainy",
                            "description" to "the workspace's rainy definition, as a transform",
                            "type" to "TRANSFORM",
                            "template" to mapOf("id" to TRANSFORM, "version" to 1),
                            "inputs" to mapOf("days" to "stg_days"),
                            "output" to mapOf("target" to "caller"),
                            "depends_on" to listOf("stage_days"),
                        ),
                    ),
            )
        val created = rest(ALICE_SESSION).post("/api/v1/pipelines", mapper.writeValueAsString(body), expect = 201).body()["data"]
        return created["id"].asText() to created["body_hash"].asText()
    }

    /** 4 — the rule is superseded; every citing version reads needs_review, naming both facts. */
    private fun supersede(rule: String): String {
        val successor =
            mcp(
                ALICE_KEY.plaintext,
                "semantics_record",
                mapOf(
                    "scope" to "WORKSPACE",
                    "datasource" to DATASOURCE,
                    "kind" to "definition",
                    "fact" to "rainy = precipitation_mm at or above 5.0 on the day, whole days only",
                    "supersedes" to rule,
                ),
            )["id"].asText()
        val read = mcp(ALICE_KEY.plaintext, "templates_get", mapOf("id" to TRANSFORM))
        println("event=implements.e2e.templates_get_needs_review payload=${mapper.writeValueAsString(read)}")
        val retired = read["retired_facts"].single()
        assertAll(
            { read["needs_review"].asBoolean() shouldBe true },
            { retired["fact_id"].asText() shouldBe rule },
            { retired["retired_reason"].asText() shouldBe "superseded" },
            { retired["superseded_by"].asText() shouldBe successor },
            {
                val rows = mcp(ALICE_KEY.plaintext, "templates_list", mapOf("q" to "rainy"))
                rows.single { it["id"].asText() == TRANSFORM }["needs_review"].asBoolean() shouldBe true
                rows.single { it["id"].asText() == STAGE_TEMPLATE }["needs_review"].asBoolean() shouldBe false
            },
        )
        return successor
    }

    /** 5 — the release proceeds (status RELEASED) and its body carries the warning; returns the transform's released hash. */
    private fun releaseWarnsAndProceeds(
        pipeline: Pair<String, String>,
        rule: String,
        successor: String,
    ): String {
        val (id, hash) = pipeline
        val released =
            rest(ALICE_SESSION)
                .post("/api/v1/pipelines/$id/release?release_pinned_templates=true", "", expect = 200, ifMatch = hash)
                .body()["data"]
        println("event=implements.e2e.release payload=${mapper.writeValueAsString(released["warnings"])}")
        val warning = released["warnings"].single()
        assertAll(
            { released["status"].asText() shouldBe "RELEASED" },
            { released["current_version"].asInt() shouldBe 1 },
            { warning["code"].asText() shouldBe "pipeline.release.template_needs_review" },
            { warning["template"].asText() shouldBe TRANSFORM },
            { warning["version"].asInt() shouldBe 1 },
            { warning["message"].asText() shouldContain "$rule — superseded by $successor" },
        )
        val template = mcp(ALICE_KEY.plaintext, "templates_get", mapOf("id" to TRANSFORM))
        template["status"].asText() shouldBe "RELEASED"
        return template["body_hash"].asText()
    }

    /** 6 — the deliberate clear: cite the successor ON the released version; no draft, no new version. */
    private fun reCiteClears(
        successor: String,
        releasedHash: String,
    ) {
        val written =
            mcp(
                ALICE_KEY.plaintext,
                "templates_update",
                transformArgs() + mapOf("expected_hash" to releasedHash, "implements" to listOf(successor)),
            )
        println("event=implements.e2e.recite payload=${mapper.writeValueAsString(written)}")
        val read = mcp(ALICE_KEY.plaintext, "templates_get", mapOf("id" to TRANSFORM))
        assertAll(
            { written["status"].asText() shouldBe "RELEASED" },
            { written.has("draft") shouldBe false },
            { read["version"].asInt() shouldBe 1 },
            { read["body_hash"].asText() shouldBe releasedHash },
            { read["implements"].map { it.asText() } shouldContainExactly listOf(successor) },
            { read["needs_review"].asBoolean() shouldBe false },
        )
    }

    /** 7 — export → import: kept in the same workspace, dropped in another (owner ruling 2026-09-25). */
    private fun importRoundTrip(successor: String) {
        val exported = rest(ALICE_SESSION).get("/api/v1/templates?name=$TRANSFORM").body()["data"] as ObjectNode
        exported.put("id", "test/rainy_days_copy.jsonata")
        listOf("version", "body_hash", "status", "created_at", "created_by", "needs_review", "retired_facts").forEach { exported.remove(it) }
        val bundle = mapper.writeValueAsString(mapOf("templates" to listOf(exported)))

        val sameWorkspace = rest(ALICE_SESSION).post("/api/v1/templates/import", bundle, expect = 200).body()["data"]["templates"][0]
        val otherWorkspace = rest(BOB_SESSION).post("/api/v1/templates/import", bundle, expect = 200).body()["data"]["templates"][0]
        println("event=implements.e2e.import same=${sameWorkspace["implements"]} other=${otherWorkspace["implements"]}")
        assertAll(
            { sameWorkspace["implements"].map { it.asText() } shouldContainExactly listOf(successor) },
            { otherWorkspace["implements"].map { it.asText() }.shouldBeEmpty() },
            // What landed is what the next read serves — the kept citation is stored, not echoed.
            {
                rest(ALICE_SESSION).get("/api/v1/templates?name=test/rainy_days_copy.jsonata").body()["data"]["implements"]
                    .map { it.asText() } shouldContainExactly listOf(successor)
            },
        )
    }

    private fun implementedBy(fact: JsonNode): List<Map<String, Any>> =
        withClue("implemented_by on ${fact["id"]}") {
            fact["implemented_by"].map { mapOf("template_id" to it["template_id"].asText(), "version" to it["version"].asInt()) }
        }

    // ------------------------------------------------------------------ the transform

    private fun transformArgs(id: String = TRANSFORM): Map<String, Any?> =
        mapOf(
            "id" to id,
            "type" to "jsonata",
            "display_name" to "Rainy days",
            "description" to "Flags each day rainy by the workspace's definition.",
            "body" to """[ inputs.days.{ "day": day, "rainy": precipitation_mm >= 2.5 } ]""",
            "contract" to
                mapOf(
                    "mode" to "table",
                    "inputs" to
                        mapOf(
                            "days" to
                                mapOf(
                                    "kind" to "table",
                                    "columns" to
                                        listOf(
                                            mapOf("name" to "day", "type" to "STRING"),
                                            mapOf("name" to "precipitation_mm", "type" to "DECIMAL", "precision" to 6, "scale" to 2),
                                        ),
                                ),
                        ),
                    "output" to
                        mapOf(
                            "kind" to "table",
                            "columns" to listOf(mapOf("name" to "day", "type" to "STRING"), mapOf("name" to "rainy", "type" to "BOOLEAN")),
                        ),
                ),
            "invariants" to emptyList<Any>(),
            "tests" to
                listOf(
                    mapOf(
                        "name" to "empty input",
                        "input" to mapOf("inputs" to mapOf("days" to emptyList<Any>())),
                        "expect" to mapOf("output" to emptyList<Any>()),
                    ),
                    mapOf(
                        "name" to "one wet day",
                        "input" to mapOf("inputs" to mapOf("days" to listOf(mapOf("day" to "2026-01-02", "precipitation_mm" to 3.1)))),
                        "expect" to mapOf("output" to listOf(mapOf("day" to "2026-01-02", "rainy" to true))),
                    ),
                ),
        )

    // ------------------------------------------------------------------ the wire

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

    /** A tool call expected to REFUSE: the §9.2 `error` object out of the result's text block. */
    private fun mcpError(
        key: String,
        tool: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val result = mapper.readTree(mcpRaw(key, tool, arguments))["result"]
        withClue("$tool was expected to refuse: $result") { result["isError"].asBoolean() shouldBe true }
        return mapper.readTree(result["content"][0]["text"].asText())["error"]
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

    /** A session-driven REST call site — the suite's only REST vocabulary (#215 B2). */
    private inner class Rest(
        private val session: String,
    ) {
        fun get(path: String): Answer = call("GET", path, null, 200, null)

        fun post(
            path: String,
            body: String,
            expect: Int,
            ifMatch: String? = null,
        ): Answer = call("POST", path, body, expect, ifMatch)

        private fun call(
            method: String,
            path: String,
            body: String?,
            expect: Int,
            ifMatch: String?,
        ): Answer {
            var spec = given().port(port).contentType(ContentType.JSON).asSession(session)
            if (ifMatch != null) spec = spec.header("If-Match", ifMatch)
            if (body != null) spec = spec.body(body)
            val response = if (method == "GET") spec.`when`().get(path) else spec.`when`().post(path)
            val text = response.body().asString()
            withClue("$method $path answered ${response.statusCode()}: $text") { response.statusCode() shouldBe expect }
            return Answer(mapper.readTree(text))
        }
    }

    private class Answer(
        private val json: JsonNode,
    ) {
        fun body(): JsonNode = json
    }

    private fun rest(session: String) = Rest(session)

    // ------------------------------------------------------------------ the world

    private fun registerDatasource() {
        rest(ALICE_SESSION).post(
            "/api/v1/datasources",
            """
            {"name": "$DATASOURCE", "display_name": "Weather", "dialect": "POSTGRES",
             "jdbc_url": "${source.jdbcUrl}", "username": "${source.username}", "password": "${source.password}"}
            """.trimIndent(),
            expect = 201,
        )
        // Granted to globex too: its blindness to acme's rule must be the WORKSPACE predicate,
        // not a datasource it cannot see.
        metadata {
            it.execute(
                "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) VALUES ('$DATASOURCE', '$WS_GLOBEX', '$ALICE')",
            )
        }
    }

    private fun seedSourceTable() {
        DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS days")
                st.execute("CREATE TABLE days (day TEXT PRIMARY KEY, precipitation_mm NUMERIC(6,2) NOT NULL)")
                st.execute("INSERT INTO days VALUES ('2026-01-01', 0.40), ('2026-01-02', 3.10), ('2026-01-03', 6.20)")
            }
        }
    }

    private fun seedAuthRows() {
        metadata { st ->
            st.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WS_ACME', 'acme', 'Acme'), ('$WS_GLOBEX', 'globex', 'Globex')")
            st.execute(
                """
                INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                    ('$ALICE', 'alice@acme.test', 'Alice', 'test', 'impl-alice', TRUE, TRUE),
                    ('$BOB', 'bob@globex.test', 'Bob', 'test', 'impl-bob', TRUE, FALSE)
                """.trimIndent(),
            )
            st.execute(
                """
                INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                    ('$WS_ACME', '$ALICE', 'author'),
                    ('$WS_GLOBEX', '$BOB', 'author')
                """.trimIndent(),
            )
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .prepareStatement("INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id) VALUES (?, ?, ?, ?, ?, ?)")
                .use { ps ->
                    for ((key, owner, workspace) in listOf(Triple(ALICE_KEY, ALICE, WS_ACME), Triple(BOB_KEY, BOB, WS_GLOBEX))) {
                        ps.setString(1, key.id)
                        ps.setObject(2, UUID.fromString(owner))
                        ps.setObject(3, UUID.fromString(owner))
                        ps.setString(4, key.name)
                        ps.setString(5, key.hash)
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

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val DATASOURCE = "weather-ds"
        private const val TRANSFORM = "test/rainy_days.jsonata"
        private const val STAGE_TEMPLATE = "test/rainy_days_stage.sql"
        private const val PIPELINE = "test/rainy_days"
        private const val SECRET_BYTES = 32

        private const val WS_ACME = "7e7e0000-0000-0000-0000-0000000000a1"
        private const val WS_GLOBEX = "7e7e0000-0000-0000-0000-0000000000b2"
        private const val ALICE = "7e7e0000-0000-0000-0000-00000000a11c"
        private const val BOB = "7e7e0000-0000-0000-0000-00000000b0b0"

        private val ALICE_KEY = E2eAuth.generateKey("impl-alice-key")
        private val BOB_KEY = E2eAuth.generateKey("impl-bob-key")

        private val random = SecureRandom()
        private val jwtSecret: String = E2eSession.newSecret()
        private val ALICE_SESSION get() = E2eSession.jwt(jwtSecret, ALICE, "alice@acme.test", "acme")
        private val BOB_SESSION get() = E2eSession.jwt(jwtSecret, BOB, "bob@globex.test", "globex")

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val source = SharedE2e.scratchDatabase("implements_source")
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
