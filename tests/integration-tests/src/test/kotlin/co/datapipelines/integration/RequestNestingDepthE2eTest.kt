package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.ExtractableResponse
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.sql.DriverManager
import java.util.UUID

/**
 * #291 on the WIRE: the stated request constraints (pipeline-contract §13.21 — nesting depth
 * [MAX_NESTING_DEPTH]) hold on EVERY surface that parses request bytes, not just
 * on the two mappers #279 built them into. The reflection lesson applies: a constraint set on a
 * factory is a belief until a document one level past it is refused through the real stack.
 *
 *  - Spring's injected REST mapper — read directly (the generated artifact), and proven on a
 *    route whose body Spring binds as a JSON tree (a bean binding fails on the SHAPE before it
 *    reads deep enough for the depth to matter — measured: a 101-deep array to a DTO route
 *    answers "cannot deserialize from Array", which proves nothing about depth);
 *  - every REST route that takes its body as a `String` and parses it itself — the sweep below,
 *    whose list is pinned against a source scan of every `@RequestBody … String` in the product
 *    so a new such route cannot join the product without joining the sweep;
 *  - the MCP transport's own mapper, on a real `tools/call` — whose refusal, before #291, went
 *    out as the SDK's serialized exception, stack trace and all.
 *
 * Each over-bound body is refused as the route family's own catalogued 400 with
 * `details.reason: malformed_json` — never the 500 backstop — and a body exactly AT the bound is
 * not refused for its depth. `1e10000` as an execute parameter, which the plain-decimal echo
 * cannot write, is the caller's named 400 as well.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RequestNestingDepthE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /** One route that parses its own `String` body, and the 400 its family answers a malformed one with. */
    private data class StringRoute(
        val method: String,
        val path: String,
        val code: String,
    )

    private val stringRoutes =
        listOf(
            StringRoute("POST", "/api/v1/templates", TEMPLATE_MALFORMED),
            StringRoute("PUT", "/api/v1/templates", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/release", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/draft/discard", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/version/discard", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/version/restore", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/current", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/render", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/evaluate", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/templates/import", TEMPLATE_MALFORMED),
            StringRoute("POST", "/api/v1/pipelines", PIPELINE_MALFORMED),
            StringRoute("PUT", "/api/v1/pipelines/${UUID.randomUUID()}", PIPELINE_MALFORMED),
            StringRoute("POST", "/api/v1/pipelines/import", PIPELINE_MALFORMED),
            StringRoute("POST", "/api/v1/pipelines/${UUID.randomUUID()}/execute", EXECUTE_MALFORMED),
            StringRoute("POST", "/api/v1/schedules", SCHEDULE_MALFORMED),
            StringRoute("PUT", "/api/v1/schedules/${UUID.randomUUID()}", SCHEDULE_MALFORMED),
            // #10 L1b: the two artifact families parse create, update and switch-current bodies themselves.
            StringRoute("POST", "/api/v1/visualizations", VISUALIZATION_MALFORMED),
            StringRoute("PUT", "/api/v1/visualizations/${UUID.randomUUID()}", VISUALIZATION_MALFORMED),
            StringRoute("POST", "/api/v1/visualizations/${UUID.randomUUID()}/current", VISUALIZATION_MALFORMED),
            // #10 L1c: the transfer routes read their envelope bodies themselves.
            StringRoute("POST", "/api/v1/visualizations/import", VISUALIZATION_MALFORMED),
            StringRoute("POST", "/api/v1/dashboards", DASHBOARD_MALFORMED),
            StringRoute("PUT", "/api/v1/dashboards/${UUID.randomUUID()}", DASHBOARD_MALFORMED),
            StringRoute("POST", "/api/v1/dashboards/${UUID.randomUUID()}/current", DASHBOARD_MALFORMED),
            // #10 L2: the three runtime POSTs parse their own body too — read whole BEFORE any lookup, so a random id is enough.
            StringRoute("POST", "/api/v1/dashboards/${UUID.randomUUID()}/runtime/parameters", DASHBOARD_MALFORMED),
            StringRoute("POST", "/api/v1/dashboards/${UUID.randomUUID()}/runtime/visualizations", DASHBOARD_MALFORMED),
            StringRoute(
                "POST",
                "/api/v1/dashboards/${UUID.randomUUID()}/runtime/refreshes/${UUID.randomUUID()}/abort",
                DASHBOARD_MALFORMED,
            ),
            StringRoute("POST", "/api/v1/dashboards/import", DASHBOARD_MALFORMED),
            // #323 (lane 321): the parameter-set routes read their body the #291 way too — swept, no longer allowlisted.
            StringRoute("POST", "/api/v1/parameter-sets", PARAMETER_MALFORMED),
            StringRoute("PUT", "/api/v1/parameter-sets/${UUID.randomUUID()}", PARAMETER_MALFORMED),
            StringRoute("POST", "/api/v1/parameter-sets/${UUID.randomUUID()}/evaluate", PARAMETER_MALFORMED),
            StringRoute("POST", "/api/v1/parameter-sets/import", PARAMETER_MALFORMED),
        )

    @Test
    fun `the injected rest mapper carries the stated constraints - read from the artifact itself`() {
        val constraints = objectMapper.factory.streamReadConstraints()
        constraints.maxNestingDepth shouldBe MAX_NESTING_DEPTH
        constraints.maxStringLength shouldBe MAX_STRING_LENGTH
        constraints.maxNumberLength shouldBe MAX_NUMBER_LENGTH
    }

    @Test
    fun `a spring-bound route refuses a body one level past the bound as its family's 400`() {
        ensureSeeded()
        val over = rest("POST", "/api/v1/pipelines/${UUID.randomUUID()}/versions/1/checks/run", deepArray(MAX_NESTING_DEPTH + 1))
        withClue(over.body().asString()) {
            over.statusCode() shouldBe 400
            over.body().asString() shouldContain """"reason":"malformed_json""""
            over.body().asString() shouldContain "nesting depth"
        }
    }

    @Test
    fun `every string-body route refuses a body one level past the bound as its family's named 400`() {
        ensureSeeded()
        assertSoftly {
            stringRoutes.forEach { route -> assertDepthRefusal(route) }
        }
    }

    private fun assertDepthRefusal(route: StringRoute) {
        val over = rest(route.method, route.path, deepArray(MAX_NESTING_DEPTH + 1))
        withClue("${route.method} ${route.path}: ${over.body().asString().take(400)}") {
            over.statusCode() shouldBe 400
            over.body().asString() shouldContain """"code":"${route.code}""""
            if (route.code != SCHEDULE_MALFORMED) {
                over.body().asString() shouldContain """"reason":"malformed_json""""
                over.body().asString() shouldContain "nesting depth"
            }
        }
        // At the bound: never refused for its depth (a 100-deep array is the wrong SHAPE
        // for every route, so it earns some other answer — just not this one).
        val at = rest(route.method, route.path, deepArray(MAX_NESTING_DEPTH))
        withClue("${route.method} ${route.path} at the bound: ${at.body().asString().take(400)}") {
            at.body().asString() shouldNotContain "nesting depth"
            at.statusCode() shouldNotBe 500
        }
    }

    @Test
    fun `a malformed string body is its family's named 400, never the 500 backstop`() {
        ensureSeeded()
        assertSoftly {
            stringRoutes.forEach { route ->
                val malformed = rest(route.method, route.path, """{"name": """)
                withClue("${route.method} ${route.path}: ${malformed.body().asString().take(400)}") {
                    malformed.statusCode() shouldBe 400
                    malformed.body().asString() shouldContain """"code":"${route.code}""""
                }
            }
        }
    }

    @Test
    fun `the sweep covers every string request body in the product - bar the allowlisted`() {
        // Non-vacuity and completeness: the scan finds the handlers; the sweep's list must name
        // exactly as many routes per controller as the controller declares.
        val found = stringBodyHandlersPerFile()
        val swept =
            mapOf(
                "TemplatesController.kt" to 10,
                "PipelinesController.kt" to 2,
                "PipelineTransferController.kt" to 1,
                "PipelineExecuteController.kt" to 1,
                "SchedulesController.kt" to 2,
                "VisualizationsController.kt" to 3,
                "DashboardsController.kt" to 3,
                "DashboardRuntimeController.kt" to 3,
                "ParameterSetsController.kt" to 4,
                // #10 L1c: the transfer routes read their envelope bodies themselves.
                "VisualizationTransferController.kt" to 1,
                "DashboardTransferController.kt" to 1,
            )
        withClue("files declaring an @RequestBody String parameter, and how many each declares") {
            found.entries.map { it.key to it.value } shouldContainExactlyInAnyOrder
                (swept + ALLOWLISTED).entries.map { it.key to it.value }
        }
        stringRoutes.size shouldBe swept.values.sum() // 28 routes sweep themselves: the allowlist is empty (#323, #10 L1c)
    }

    @Test
    fun `the mcp transport refuses a tools-call one level past the bound and parses one at it`() {
        ensureSeeded()
        // The envelope is three levels deep (the request, `params`, `arguments`); the pad array fills the rest.
        // The SDK names every unreadable body the same way (JSON-RPC -32600, "Invalid message
        // format"); the pair — refused at 101, parsed at 100 — is what shows the DEPTH refused it.
        val over = mcpCall(mcpBodyOfDepth(MAX_NESTING_DEPTH + 1))
        withClue(over.body().asString().take(400)) {
            over.statusCode() shouldBe 400
            over.body().asString() shouldContain "-32600"
            over.body().asString() shouldContain "Invalid message format"
            // Measured before #291: the SDK's McpError EXCEPTION went out whole — stackTrace,
            // every frame's class, file and line. The transport's mapper now drops the internals.
            over.body().asString() shouldNotContain "stackTrace"
            over.body().asString() shouldNotContain "className"
        }
        val at = mcpCall(mcpBodyOfDepth(MAX_NESTING_DEPTH))
        withClue(at.body().asString().take(400)) {
            at.statusCode() shouldBe 200
            at.body().asString() shouldNotContain "nesting depth"
        }
    }

    @Test
    fun `a number the plain-decimal echo cannot write is the caller's named 400 on execute`() {
        ensureSeeded()
        val answer =
            rest("POST", "/api/v1/pipelines/${UUID.randomUUID()}/execute", """{"parameters": {"start": 1e10000}}""")
        withClue(answer.body().asString()) {
            answer.statusCode() shouldBe 400
            answer.body().asString() shouldContain """"code":"$EXECUTE_MALFORMED""""
            answer.body().asString() shouldContain """"parameter":"start""""
            answer.body().asString() shouldContain "too_many_digits"
        }
    }

    private fun deepArray(depth: Int): String = "[".repeat(depth) + "]".repeat(depth)

    private fun mcpBodyOfDepth(depth: Int): String {
        val pad = depth - MCP_ENVELOPE_DEPTH
        return """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
            """"params":{"name":"executions_list","arguments":{"limit":1,"_pad":${deepArray(pad)}}}}"""
    }

    private fun jwt(): String = E2eSession.jwt(jwtSecret, adminId.toString(), "admin@depth291.test", workspace = WS_NAME)

    private fun rest(
        method: String,
        path: String,
        body: String,
    ): ExtractableResponse<Response> {
        val request =
            given()
                .port(port)
                .asSession(jwt())
                .contentType(ContentType.JSON)
                .header("If-Match", "\"0\"")
                .body(body)
                .`when`()
        return when (method) {
            "PUT" -> request.put(path)
            else -> request.post(path)
        }.then().extract()
    }

    private fun mcpCall(body: String): ExtractableResponse<Response> =
        given()
            .port(port)
            .header("DP-API-Key", authorKey.plaintext)
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body(body)
            .`when`()
            .post("/mcp")
            .then()
            .extract()

    /** Per production source file, how many `@RequestBody … String` parameters it declares. */
    private fun stringBodyHandlersPerFile(): Map<String, Int> {
        var root = File(".").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("repo root not found")
        val pattern = Regex("""@RequestBody(\([^)]*\))?\s+\w+:\s*String\b""")
        return File(root, "modules")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
            .mapNotNull { file ->
                pattern
                    .findAll(file.readText())
                    .count()
                    .takeIf { it > 0 }
                    ?.let { file.name to it }
            }.toMap()
    }

    companion object {
        /**
         * pipeline-contract §13.21's stated numbers (`RequestLimits`, which this module cannot
         * import): the doc is their authority, and this suite pins the running app to them.
         */
        const val MAX_NESTING_DEPTH = 100
        const val MAX_STRING_LENGTH = 4_194_304
        const val MAX_NUMBER_LENGTH = 1000

        const val TEMPLATE_MALFORMED = "template.validation.schema_version_unsupported"
        const val PIPELINE_MALFORMED = "pipeline.validation.schema_version_unsupported"
        const val EXECUTE_MALFORMED = "pipeline.execution.invalid_parameter_type"
        const val SCHEDULE_MALFORMED = "schedule.validation.request_invalid"
        const val VISUALIZATION_MALFORMED = "visualization.validation.body_invalid"
        const val DASHBOARD_MALFORMED = "dashboard.validation.body_invalid"
        const val PARAMETER_MALFORMED = "parameter.validation.body_invalid"

        /** `{` request, `params`, `arguments`: the nesting a tools/call spends before its arguments' values. */
        const val MCP_ENVELOPE_DEPTH = 3

        /**
         * String-body handlers NOT in the sweep, each with its reason. Empty since 321 (#323): the parameter-set
         * routes — allowlisted while they parsed with the parameters module's bare mapper (#291's shape landed
         * around them) — read their body through `RequestBodies.readTree(RequestLimits.requestMapper(…))` now
         * and are swept above. A new entry needs a reason and an issue.
         */
        private val ALLOWLISTED: Map<String, Int> = emptyMap()

        const val WS_NAME = "depth291"

        private val wsId = UUID.randomUUID()
        private val adminId = UUID.randomUUID()
        private val authorId = UUID.randomUUID()
        private val keyIdentity = UUID.randomUUID()
        private val authorKey = E2eAuth.generateKey("depth291-mcp", ownerId = authorId.toString())

        private val jwtSecret = E2eSession.newSecret()

        private var seeded = false

        private fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager
                .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
                .use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO workspaces (id, name, display_name) VALUES ('$wsId', '$WS_NAME', 'Depth 291')",
                        )
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                                "('$adminId', 'admin@depth291.test', 'admin', 'test', 'depth291-admin', TRUE, FALSE), " +
                                "('$authorId', 'author@depth291.test', 'author', 'test', 'depth291-author', TRUE, FALSE)",
                        )
                        statement.execute(
                            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES " +
                                "('$wsId', '$adminId', 'workspace_admin'), ('$wsId', '$authorId', 'author')",
                        )
                        statement.execute(
                            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                                "('$keyIdentity', '${authorKey.id.lowercase()}@keys.invalid', '${authorKey.name}', 'key', " +
                                "'${authorKey.id}', TRUE, FALSE, 'service')",
                        )
                        statement.execute(
                            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) VALUES " +
                                "('${authorKey.id}', '$keyIdentity', '$authorId', '${authorKey.name}', " +
                                "'${authorKey.hash}', '$wsId', 'mcp', 'author')",
                        )
                    }
                }
        }

        private val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { jwtSecret }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }

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
