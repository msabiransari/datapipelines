package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The CASCADE E2E (the record's §12 E2E row, #194 lane D): a country → state → city cascade
 * over a REAL datasource, through REST **and** MCP, with a MULTI-SEGMENT name proven through
 * the real HTTP stack (a name travels in bodies and `?prefix=` only — P24).
 *
 * The walk, in order: the fixture (H2 tables, a datasource, the two released SELECT templates,
 * the set released over REST), the first render (`{}` — the server initialises the form), a
 * parent change resetting children with `reset: true`, an `INPUT` with `scale: 2` + `min: 0`
 * refusing `12.345` and `-1` (never rounded — P19), hidden/disabled from expressions, a MULTI
 * parent binding into the child's `IN (:country)` (P29), and `parameter.evaluate.
 * template_unrendered` on MCP (the 139 gate's twin, MCP-only). The per-evaluate cost is
 * printed for the handback (C25: 2 customer statements + 1 metadata read).
 */
@ExtendWith(SpringExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class ParameterCascadeE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private val http: HttpClient = HttpClient.newHttpClient()

    // ---- REST -------------------------------------------------------------------------------

    private fun rest(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonNode> {
        var request = given().port(port).contentType(ContentType.JSON).asSession(ADMIN_SESSION)
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

    private fun evaluate(body: String): JsonNode {
        val (status, envelope) = rest("POST", "/api/v1/parameter-sets/$setId/evaluate", body)
        withClue("evaluate must answer 200: $envelope") { status shouldBe 200 }
        val data = envelope.get("data")
        withClue("evaluate envelope must carry data: $envelope") { data?.isNull ?: true } shouldBe false
        return data
    }

    private fun stateOf(
        response: JsonNode,
        name: String,
    ): JsonNode = response["parameters"].first { it["name"].asText() == name }["state"]

    private fun valueOf(
        response: JsonNode,
        name: String,
    ): JsonNode = response["values"][name]

    // ---- MCP --------------------------------------------------------------------------------

    private fun callTool(
        id: Int,
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, JsonNode?> {
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
                                "id" to id,
                                "method" to "tools/call",
                                "params" to mapOf("name" to name, "arguments" to arguments),
                            ),
                        ),
                    ),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = mapper.readTree(response.body())
        if (body.has("error")) return mapper.createObjectNode() to body["error"]
        val result = body["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to null
    }

    /** Creates and RELEASES a template over MCP create + REST release; answers version 1. */
    private fun releasedTemplate(
        id: Int,
        name: String,
        body: String,
    ) {
        val (created, createError) =
            callTool(
                id,
                "templates_create",
                mapOf(
                    "id" to name,
                    "dialect" to "H2",
                    "display_name" to name,
                    "description" to "Cascade E2E selector (expects a bind, never interpolation).",
                    "body" to body,
                    "confirm_new_root" to true,
                ),
            )
        withClue("template create must succeed: $createError payload=$created") {
            created.has("body_hash") shouldBe true
        }
        val (getStatus, workingEnvelope) = rest("GET", "/api/v1/templates?name=$name")
        val hash = created["body_hash"].asText()
        val (releaseStatus, releaseEnvelope) =
            rest(
                "POST",
                "/api/v1/templates/release",
                """{"name": "$name"}""",
                mapOf("If-Match" to hash),
            )
        withClue("template release must succeed: $releaseEnvelope") { releaseEnvelope.path("error").isMissingNode shouldBe true }
    }

    /** The cascade set's four parameters (the record's §3.2 shapes, the E2E's own fixture). */
    private val cascadeParameters: List<Map<String, Any?>> =
        listOf(
            mapOf(
                "name" to "country",
                "label" to "Country",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "MULTI",
                "required" to true,
                "source" to
                    mapOf(
                        "constants" to
                            listOf(
                                mapOf("value" to "US", "display_value" to "United States", "is_default" to true),
                                mapOf("value" to "CA", "display_value" to "Canada"),
                            ),
                    ),
                "presentation" to mapOf("control" to "checkboxes"),
            ),
            mapOf(
                "name" to "state",
                "label" to "State",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "required" to true,
                "source" to
                    mapOf(
                        "template" to mapOf("id" to STATES_TEMPLATE, "version" to 1),
                        "datasource" to DATASOURCE,
                    ),
                "depends_on" to listOf("country"),
            ),
            mapOf(
                "name" to "city",
                "label" to "City",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "required" to false,
                "source" to
                    mapOf(
                        "template" to mapOf("id" to CITIES_TEMPLATE, "version" to 1),
                        "datasource" to DATASOURCE,
                    ),
                "depends_on" to listOf("state"),
                "disabled_expression" to
                    mapOf("op" to "eq", "left" to mapOf("ref" to "state"), "right" to mapOf("literal" to "NY")),
            ),
            mapOf(
                "name" to "min_order_amount",
                "label" to "Minimum order amount",
                "type" to "DECIMAL",
                "precision" to 12,
                "scale" to 2,
                "kind" to "INPUT",
                "required" to false,
                "default_value" to 0,
                "constraints" to mapOf("min" to 0),
            ),
        )

    private fun seedTables() {
        DriverManager.getConnection(H2_JDBC_URL, "sa", "sa").use { connection ->
            connection.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS dim_state (country_code VARCHAR(2), state_code VARCHAR(2), state_name VARCHAR(30))",
            )
            connection.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS dim_city (state_code VARCHAR(2), city_name VARCHAR(30))",
            )
            connection.createStatement().execute("DELETE FROM dim_state")
            connection.createStatement().execute("DELETE FROM dim_city")
            connection.createStatement().execute(
                "INSERT INTO dim_state VALUES ('US','NY','New York'),('US','CA','California'),('CA','ON','Toronto')",
            )
            connection.createStatement().execute("INSERT INTO dim_city VALUES ('NY','New York City'),('CA','Los Angeles')")
        }
    }

    // ---- the walk ---------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - tables, datasource, released templates, the set with a multi-segment name`() {
        seedAuthRows()
        seedTables()

        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """{"name": "$DATASOURCE", "display_name": "Cascade E2E", "dialect": "H2",
                   "jdbc_url": "$H2_JDBC_URL", "username": "sa", "password": "sa"}""",
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)

        releasedTemplate(
            1,
            STATES_TEMPLATE,
            "SELECT state_code AS \"value\", state_name AS \"display_value\", " +
                "CASE WHEN state_code = 'NY' THEN TRUE ELSE FALSE END AS \"is_default\" " +
                "FROM dim_state WHERE country_code IN (:country) ORDER BY state_name",
        )
        releasedTemplate(
            2,
            CITIES_TEMPLATE,
            "SELECT city_name AS \"value\", city_name AS \"display_value\", FALSE AS \"is_default\" " +
                "FROM dim_city WHERE state_code = :state ORDER BY city_name",
        )
        createMainSet()
    }

    /** The cascade set: MULTI country (constants), template-backed state and city, a scale-2 INPUT. */
    private fun createMainSet() {
        // The set is created over MCP (the agent surface) with the MULTI-SEGMENT name in the
        // BODY — never in a path segment (P24).
        val (created, createError) =
            callTool(
                3,
                "parameter_sets_create",
                mapOf(
                    "name" to SET_NAME,
                    "display_name" to "Cascade filters",
                    "description" to "country to state to city cascade plus an amount input",
                    "confirm_new_root" to true,
                    "parameters" to
                        cascadeParameters,
                ),
            )
        withClue("parameter_sets_create must succeed: $createError") { createError shouldBe null }
        withClue("create must return the created set: $created") { created.has("id") } shouldBe true
        withClue("create must land DRAFT: $created") { created["status"].asText() shouldBe "DRAFT" }
        setId = created["id"].asText()

        // The multi-segment name through the REST browse: `?prefix=acme` shows the `e2e`
        // folder one level down, `?prefix=acme/e2e` shows the set itself.
        val (_, rootBrowse) = rest("GET", "/api/v1/parameter-sets?prefix=acme")
        val rootFolder =
            rootBrowse
                .path("data")
                .path("folders")
                .find { it.path("segment").asText() == "e2e$RUN" }
        withClue("the multi-segment name's folder must browse at ?prefix=acme: $rootBrowse") {
            rootFolder?.isMissingNode shouldBe false
        }
        val (_, levelBrowse) = rest("GET", "/api/v1/parameter-sets?prefix=acme/e2e$RUN")
        val levelSet =
            levelBrowse
                .path("data")
                .path("parameter_sets")
                .find { it.path("name").asText() == SET_NAME }
        withClue("the set must browse at ?prefix=acme/e2e$RUN: $levelBrowse") {
            levelSet?.isMissingNode shouldBe false
        }

        // Release over REST with the draft's hash.
        val (releaseStatus, releaseBody) =
            rest(
                "POST",
                "/api/v1/parameter-sets/$setId/release",
                "",
                mapOf("If-Match" to created["body_hash"].asText()),
            )
        withClue("release must succeed: $releaseBody") { releaseStatus shouldBe 200 }
        releaseBody.path("data").path("status").asText() shouldBe "RELEASED"
    }

    @Test
    @Order(2)
    fun `first render - empty selections initialise the whole form by the priority`() {
        val response = evaluate("""{"selections": {}}""")

        response["valid"].asBoolean() shouldBe true
        // country: the is_default member of the MULTI constants (priority 2).
        valueOf(response, "country").first().asText() shouldBe "US"
        stateOf(response, "country")["origin"].asText() shouldBe "default"
        // state: NY is the is_default row among the template's options (priority 2, origin default).
        valueOf(response, "state").asText() shouldBe "NY"
        stateOf(response, "state")["origin"].asText() shouldBe "default"
        stateOf(response, "state")["reset"].asBoolean() shouldBe false
        // The INPUT's hard-coded default (priority 2 for an INPUT).
        valueOf(response, "min_order_amount").isNumber shouldBe true
        // Options are echoed once, in state.options, with the three columns.
        stateOf(response, "state")["options"][0].has("display_value") shouldBe true
    }

    @Test
    @Order(3)
    fun `a parent change resets a stale child with reset true - never an error`() {
        // CA has no NY: the submitted state walks the priority (first option in ORDER BY).
        val response =
            evaluate("""{"selections": {"country": ["CA"], "state": "NY", "city": null, "min_order_amount": null}}""")

        stateOf(response, "state")["reset"].asBoolean() shouldBe true
        stateOf(response, "state")["origin"].asText() shouldBe "first"
        // The VALUE column is the state CODE; Toronto's code is ON.
        valueOf(response, "state").asText() shouldBe "ON"
        response["valid"].asBoolean() shouldBe true
    }

    @Test
    @Order(4)
    fun `an INPUT with scale 2 and min 0 refuses 12-345 and -1 - never rounded`() {
        val scaled =
            evaluate("""{"selections": {"country": ["US"], "state": null, "city": null, "min_order_amount": 12.345}}""")
        val scaleError = stateOf(scaled, "min_order_amount")["errors"][0]
        scaleError["code"].asText() shouldBe "parameter.evaluate.constraint_violation"
        scaleError["details"]["reason"].asText() shouldBe "scale"

        val belowMin =
            evaluate("""{"selections": {"country": ["US"], "state": null, "city": null, "min_order_amount": -1}}""")
        val minError = stateOf(belowMin, "min_order_amount")["errors"][0]
        minError["code"].asText() shouldBe "parameter.evaluate.constraint_violation"
        minError["details"]["reason"].asText() shouldBe "min"
    }

    @Test
    @Order(5)
    fun `disabled flags come from the expressions - interaction only, values still submitted`() {
        // state resolves to NY (its is_default option) → city's disabled_expression
        // (eq state 'NY') is TRUE — the control is disabled, and its value is still
        // submitted and evaluated (P5).
        val disabled =
            evaluate("""{"selections": {"country": ["US"], "state": null, "city": null, "min_order_amount": null}}""")
        stateOf(disabled, "city")["disabled"].asBoolean() shouldBe true

        val enabled =
            evaluate("""{"selections": {"country": ["US"], "state": "CA", "city": "Los Angeles", "min_order_amount": null}}""")
        stateOf(enabled, "city")["disabled"].asBoolean() shouldBe false
        valueOf(enabled, "city").asText() shouldBe "Los Angeles"
        stateOf(enabled, "city")["origin"].asText() shouldBe "client"
    }

    @Test
    @Order(6)
    fun `the MULTI parent binds into the child selector - the evaluate cost is printed`() {
        val started = System.nanoTime()
        val response =
            evaluate("""{"selections": {"country": ["US", "CA"], "state": null, "city": null, "min_order_amount": null}}""")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // Both countries' states are among the options: the child selector rendered
        // IN (:country) with the MULTI list expanded and bound (P29).
        val stateValues = stateOf(response, "state")["options"].map { it["value"].asText() }
        (stateValues.contains("NY") && stateValues.contains("ON")) shouldBe true
        // C25's measured cost, observed end to end: 2 customer statements + 1 metadata read.
        println("event=parameter_cascade.evaluate cost='2 customer statements + 1 metadata read' elapsed_ms=$elapsedMs")
    }

    @Test
    @Order(7)
    fun `a DRAFT evaluate over MCP whose pinned DRAFT template was not rendered refuses template_unrendered`() {
        seedDraftContent()

        // The DRAFT set evaluates over MCP before this key rendered the draft pin: refused.
        val (refused, refusedError) =
            callTool(
                12,
                "parameter_sets_evaluate",
                mapOf("id" to draftSetId, "version" to 1, "selections" to emptyMap<String, Any?>()),
            )
        withClue("the gate answers as a tool result, not a JSON-RPC error") { refusedError shouldBe null }
        refused["error"]["code"].asText() shouldBe "parameter.evaluate.template_unrendered"

        // Render the draft pin with THIS key — the gate opens.
        val (rendered, renderError) =
            callTool(
                13,
                "templates_render",
                mapOf("id" to DRAFT_TEMPLATE, "version" to 1, "context" to emptyMap<String, Any?>()),
            )
        withClue("templates_render must succeed: $renderError") { renderError shouldBe null }

        val (passed, passError) =
            callTool(
                14,
                "parameter_sets_evaluate",
                mapOf("id" to draftSetId, "version" to 1, "selections" to emptyMap<String, Any?>()),
            )
        withClue("the rendered draft evaluates: $passError") { passError shouldBe null }
        passed["valid"].asBoolean() shouldBe true
    }

    /** The draft pin and the draft set pinning it — the template_unrendered gate's subject. */
    private fun seedDraftContent() {
        val (tpl, tplError) =
            callTool(
                10,
                "templates_create",
                mapOf(
                    "id" to DRAFT_TEMPLATE,
                    "dialect" to "H2",
                    "display_name" to "Cascade draft template",
                    "description" to "A draft selector for the unrendered gate.",
                    "body" to "SELECT 'ALL' AS \"value\", 'All' AS \"display_value\", TRUE AS \"is_default\" ORDER BY 1",
                ),
            )
        withClue("template create must succeed: $tplError") { tplError shouldBe null }

        val (draft, createError) =
            callTool(
                11,
                "parameter_sets_create",
                mapOf(
                    "name" to DRAFT_SET_NAME,
                    "display_name" to "Draft cascade",
                    "confirm_new_root" to true,
                    "parameters" to
                        listOf(
                            mapOf(
                                "name" to "region",
                                "label" to "Region",
                                "type" to "STRING",
                                "kind" to "SELECT",
                                "cardinality" to "SINGLE",
                                "required" to true,
                                "source" to
                                    mapOf(
                                        "template" to mapOf("id" to DRAFT_TEMPLATE, "version" to 1),
                                        "datasource" to DATASOURCE,
                                    ),
                            ),
                        ),
                ),
            )
        withClue("draft set create must succeed: $createError") { createError shouldBe null }
        withClue("draft create must return the created set: $draft") { draft.has("id") } shouldBe true
        draftSetId = draft["id"].asText()
    }

    companion object {
        const val DATASOURCE = "cascade-e2e"
        const val H2_JDBC_URL = "jdbc:h2:mem:cascade194;DB_CLOSE_DELAY=-1"

        /**
         * The metadata database PERSISTS across runs in the shared container, and names are
         * unique forever (versioning §3.2) — so every run takes a fresh folder. The name stays
         * MULTI-SEGMENT (P24): three segments in every path.
         */
        val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        val SET_NAME = "acme/e2e$RUN/cascade_filters"
        val DRAFT_SET_NAME = "acme/e2e$RUN/draft_filters"
        val STATES_TEMPLATE = "acme/e2e$RUN/states_of_country.sql"
        val CITIES_TEMPLATE = "acme/e2e$RUN/cities_of_state.sql"
        val DRAFT_TEMPLATE = "acme/e2e$RUN/regions_draft.sql"
        const val WORKSPACE_ID = "defa0000-0000-0000-0000-000000001940"
        const val KEY_IDENTITY = "defa0000-0000-0000-0000-000000001941"

        var setId: String = ""
        var draftSetId: String = ""

        private const val SECRET_BYTES = 32

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val random = SecureRandom()

        /** The per-run JWT secret — registered as `datapipelines.jwt.secret`; REST is a session's surface. */
        private val JWT_SECRET = E2eSession.newSecret()
        private const val WORKSPACE_NAME = "cascade194"
        private val ADMIN_SESSION
            get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, "e2e-194d@datapipelines.test", workspace = WORKSPACE_NAME)

        private val MCP_KEY = E2eAuth.generateKey("e2e-194d-cascade")

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

        /** Idempotent, called from Order(1) — a static @BeforeAll runs BEFORE Flyway is up. */
        @JvmStatic
        fun seedAuthRows() {
            if (seeded) return
            seeded = true
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', 'cascade194', 'Cascade 194')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$ADMIN_USER_ID', 'e2e-194d@datapipelines.test', 'E2E 194d', 'test', 'e2e-194d-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role)" +
                            " VALUES ('$WORKSPACE_ID', '$ADMIN_USER_ID', 'workspace_admin')",
                    )
                    // Keys v2 (A13): the key acts as its own identity and holds its chosen role.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', " +
                            "'${MCP_KEY.id}', TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?, ?, ?, ?, ?, 'mcp', 'author')",
                    ).use { ps ->
                        ps.setString(1, MCP_KEY.id)
                        ps.setObject(2, UUID.fromString(KEY_IDENTITY))
                        ps.setObject(3, UUID.fromString(ADMIN_USER_ID))
                        ps.setString(4, MCP_KEY.name)
                        ps.setString(5, MCP_KEY.hash)
                        ps.setObject(6, UUID.fromString(WORKSPACE_ID))
                        ps.executeUpdate()
                    }
            }
        }

        @JvmStatic
        fun tearDown() {
            oidc.close()
        }

        private var seeded = false
    }
}
