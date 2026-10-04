package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.Socket
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * **#384 — one per-user SSE cap over execution, refresh and observed-evaluation streams.**
 *
 * The execution registry used to count only its own family, so a user already at
 * `datapipelines.sse.max-streams-per-user` through refresh or observed-evaluation streams could
 * still open an execution stream (and rest-api §12.1 claimed the counters were in Redis).
 *
 * This walk boots the real application and holds real streams at the cap (2), one family at a
 * time and mixed, then POSTs the existing execution route and asserts the ordinary
 * `429 rate_limit.exceeded` envelope — and that no execution row and no idempotency reservation
 * were started. Freeing one stream admits the SAME valid request (and the same `Idempotency-Key`
 * starts afresh, which a claimed reservation would have turned into a never-started attach).
 * Another user's held streams never count. Every case reads the production registries' own counts
 * by name (this module compiles against `:modules:app` alone), so a stream is proven registered
 * before the refused request — the non-vacuity floor.
 *
 * The cap is exercised SEQUENTIALLY: the check-then-open admission is non-atomic and this suite
 * makes no concurrent-admission claim (rest-api §12.1 documents the bounded overshoot).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk over one fixture, as the sibling E2Es are
class CrossFamilySseCapE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()

    /** The raw sockets this test holds open to keep a stream registered; closed in [drainStreams]. */
    private val held = mutableListOf<Socket>()

    private var execPipelineId: String = ""
    private var configId: String = ""
    private var slowSetId: String = ""

    // ---- the production registries' own counts, by bean name (module-structure §4.2) -------

    private fun activeFor(
        bean: String,
        user: String,
    ): Int =
        context.getBean(bean).let {
            it.javaClass.getMethod("activeStreamsFor", UUID::class.java).invoke(it, UUID.fromString(user)) as Int
        }

    private fun activeTotal(bean: String): Int = context.getBean(bean).let { it.javaClass.getMethod("getActiveStreams").invoke(it) as Int }

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    @AfterEach
    fun drainStreams() {
        held.forEach { runCatching { it.close() } }
        held.clear()
        awaitUntil("every SSE registry drains") {
            activeTotal("executionStreamRegistry") == 0 &&
                activeTotal("refreshStreamRegistry") == 0 &&
                activeTotal("parameterEvaluationStreamRegistry") == 0
        }
    }

    // ---- the walk -------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - one workspace, a source, released templates, pipelines, a board and a slow set`() {
        seedPeople()
        registerDatasource()
        createAndReleaseTemplate(FAST_TEMPLATE, FAST_SQL)
        createAndReleaseTemplate(HUNG_TEMPLATE, HUNG_SQL)
        createAndReleaseTemplate(SLOW_TEMPLATE, SLOW_SQL)
        execPipelineId = createAndReleasePipeline(EXEC_PIPELINE, FAST_TEMPLATE)
        createAndReleasePipeline(HUNG_PIPELINE, HUNG_TEMPLATE)
        seedBoard(DASHBOARD)
        configId = dashboardConfigurationId()
        slowSetId = createAndReleaseSet(SLOW_SET, slowParameters())
    }

    @Test
    @Order(2)
    fun `refresh streams at the cap refuse an execution and claim no idempotency reservation (#384)`() {
        holdRefresh(ADMIN)
        holdRefresh(ADMIN)
        awaitUntil("two refresh streams are registered on the production bean") {
            activeFor("refreshStreamRegistry", ADMIN_ID) == TWO && activeTotal("refreshStreamRegistry") == TWO
        }

        val key = UUID.randomUUID().toString()
        val before = executionRows()
        val refused = execute(ADMIN, key)
        withClue(refused.asString().take(EXCERPT)) {
            refused.statusCode shouldBe 429
            refused.jsonPath().getString("error.code") shouldBe "rate_limit.exceeded"
            refused.jsonPath().getString("error.details.window") shouldBe "concurrent_streams"
            refused.jsonPath().getInt("error.details.limit") shouldBe TWO
        }
        executionRows() shouldBe before // no execution started

        // Free one stream: the cap is one below, so the SAME valid request is admitted — and the
        // SAME key starts afresh, proving no reservation was ever claimed by the refused call.
        held.first().close()
        awaitUntil("one refresh stream remains") { activeFor("refreshStreamRegistry", ADMIN_ID) == 1 }
        val names = executeAdmitted(ADMIN, key)
        names.first() shouldBe "execution_started"
        names shouldContain "pipeline_completed"
    }

    @Test
    @Order(3)
    fun `observed-evaluation streams at the cap refuse an execution (#384)`() {
        holdEvaluation(ADMIN)
        holdEvaluation(ADMIN)
        awaitUntil("two evaluation streams are registered on the production bean") {
            activeFor("parameterEvaluationStreamRegistry", ADMIN_ID) == TWO &&
                activeTotal("parameterEvaluationStreamRegistry") == TWO
        }

        val before = executionRows()
        val refused = execute(ADMIN, null)
        withClue(refused.asString().take(EXCERPT)) {
            refused.statusCode shouldBe 429
            refused.jsonPath().getString("error.code") shouldBe "rate_limit.exceeded"
        }
        executionRows() shouldBe before
    }

    @Test
    @Order(4)
    fun `mixed refresh and evaluation streams at the cap refuse an execution (#384)`() {
        holdRefresh(ADMIN)
        holdEvaluation(ADMIN)
        awaitUntil("one refresh and one evaluation stream are registered") {
            activeFor("refreshStreamRegistry", ADMIN_ID) == 1 &&
                activeFor("parameterEvaluationStreamRegistry", ADMIN_ID) == 1
        }

        val refused = execute(ADMIN, null)
        withClue(refused.asString().take(EXCERPT)) {
            refused.statusCode shouldBe 429
            refused.jsonPath().getString("error.code") shouldBe "rate_limit.exceeded"
        }
    }

    @Test
    @Order(5)
    fun `another user's held streams do not count against this user (#384)`() {
        holdRefresh(OTHER)
        holdRefresh(OTHER)
        awaitUntil("the other user is at the cap") { activeFor("refreshStreamRegistry", OTHER_ID) == TWO }

        withClue("the caller holds no stream, so the same valid request is admitted") {
            val names = executeAdmitted(ADMIN, null)
            names.first() shouldBe "execution_started"
            names shouldContain "pipeline_completed"
        }
    }

    // ---- stream holders -------------------------------------------------------------------

    /** Opens the refresh route on its own socket and reads to `refresh_started`; the socket is the hold. */
    private fun holdRefresh(session: String): String {
        val refreshId = uuid()
        openRaw(
            "/api/v1/dashboards/$DASHBOARD/runtime/visualizations",
            """{"configuration_id":"$configId","instance_id":"$INSTANCE","refresh_id":"$refreshId",""" +
                """"parameter_revision":1,"selections":{},"scope":"all","targets":[]}""",
            session,
            "source_started",
        )
        return refreshId
    }

    /** Opens the observed-evaluation route on its own socket and reads to `evaluation_started`. */
    private fun holdEvaluation(session: String): String {
        val evaluationId = uuid()
        openRaw(
            "/api/v1/parameter-sets/$slowSetId/evaluations",
            """{"version":1,"selections":{"mode":"slow"},"evaluation_id":"$evaluationId","instance_id":"${uuid()}"}""",
            session,
            "evaluation_started",
        )
        return evaluationId
    }

    /** The honest browser-tab model: a hand-written HTTP/1.1 POST whose socket the test owns. */
    private fun openRaw(
        path: String,
        body: String,
        session: String,
        marker: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val socket = Socket("localhost", port).apply { soTimeout = SOCKET_TIMEOUT_MS }
        val head =
            "POST $path HTTP/1.1\r\n" +
                "Host: localhost:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Accept: text/event-stream\r\n" +
                "Cookie: ${E2eSession.cookieHeader(session)}\r\n" +
                "${E2eSession.CSRF_HEADER}: ${E2eSession.CSRF_TOKEN}\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
        readUntil(socket, marker)
        held += socket
    }

    private fun readUntil(
        socket: Socket,
        marker: String,
    ) {
        val seen = StringBuilder()
        val buffer = ByteArray(BUFFER_BYTES)
        while (!seen.contains(marker)) {
            val read = socket.getInputStream().read(buffer)
            check(read >= 0) { "the stream ended before '$marker': ${seen.take(EXCERPT)}" }
            seen.append(String(buffer, 0, read, Charsets.UTF_8))
        }
    }

    // ---- the execution route --------------------------------------------------------------

    private fun execute(
        session: String,
        idempotencyKey: String?,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .accept("application/json")
            .also { if (idempotencyKey != null) it.header("Idempotency-Key", idempotencyKey) }
            .body("""{"parameters":{}}""")
            .post("/api/v1/pipelines/$execPipelineId/execute")

    private fun executeAdmitted(
        session: String,
        idempotencyKey: String?,
    ): List<String> {
        val response =
            given()
                .port(port)
                .asSession(session)
                .contentType(ContentType.JSON)
                .accept("text/event-stream")
                .also { if (idempotencyKey != null) it.header("Idempotency-Key", idempotencyKey) }
                .body("""{"parameters":{}}""")
                .post("/api/v1/pipelines/$execPipelineId/execute")
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        return E2eSse.parseEvents(response.asString(), mapper).map { it.first }
    }

    // ---- fixture --------------------------------------------------------------------------

    private fun seedPeople() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Cross-family SSE cap E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', '$ADMIN_EMAIL', 'XCap Admin', 'test', 'xcap-admin-sub', TRUE, TRUE),
                ('$OTHER_ID', '$OTHER_EMAIL', 'XCap Other', 'test', 'xcap-other-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$OTHER_ID', 'workspace_admin')
            """.trimIndent(),
        )
    }

    private fun registerDatasource() {
        val source = SharedE2e.scratchDatabase("xcap_source")
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"$DATASOURCE","display_name":"Cross-family SSE source","dialect":"POSTGRES",""" +
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

    private fun createAndReleasePipeline(
        name: String,
        template: String,
    ): String {
        val node =
            mapOf(
                "id" to "read",
                "description" to "the cross-family source",
                "type" to "DQL",
                "source" to DATASOURCE,
                "template" to mapOf("id" to template, "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to emptyList<String>(),
            )
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "schema_version" to 1,
                            "name" to name,
                            "display_name" to name,
                            "description" to "cross-family SSE cap E2E",
                            "parameters" to emptyMap<String, Any>(),
                            "nodes" to listOf(node),
                        ),
                    ),
                ).post("/api/v1/pipelines")
        withClue("pipeline $name: ${created.asString().take(EXCERPT)}") { created.statusCode shouldBe 201 }
        sql(
            "UPDATE pipeline_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND pipeline_id = (SELECT id FROM pipelines WHERE name = '$name' AND workspace_id = '$WORKSPACE_ID')",
        )
        sql("UPDATE pipelines SET current_version = 1 WHERE name = '$name' AND workspace_id = '$WORKSPACE_ID'")
        return created.jsonPath().getString("data.id")
    }

    /** The board the refresh route drives — the abort-window E2E's SQL seam, one hung source. */
    private fun seedBoard(
        id: String,
        slug: String = "hung",
    ) {
        val visualizationId = uuid()
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$visualizationId', '$WORKSPACE_ID', 'xcap/charts/$slug', '$slug', '', 1, '$ADMIN_ID')",
        )
        val visualizationBody = VISUALIZATION_BODY.replace("'", "''")
        sql(
            "INSERT INTO visualization_versions " +
                "(visualization_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$visualizationId', 1, '$visualizationBody'::jsonb, 'RELEASED', " +
                "'seeded-$visualizationId', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
        val body =
            mapper.createObjectNode().apply {
                put("display_name", slug)
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "sources",
                    mapper.readTree(
                        """[{"name":"s1","pipeline":{"name":"$HUNG_PIPELINE","version":1},"parameters":{}}]""",
                    ),
                )
                set<com.fasterxml.jackson.databind.JsonNode>(
                    "visualizations",
                    mapper.readTree(
                        """[{"name":"v","type":"visualization","visualization":{"name":"xcap/charts/$slug","version":1},""" +
                            """"inputs":{"main":{"source":"s1"}}}]""",
                    ),
                )
                set<com.fasterxml.jackson.databind.JsonNode>("layout", mapper.readTree("{}"))
            }
        val dashboardBody = body.toString().replace("'", "''")
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', 'xcap/boards/$slug', '$slug', '', 1, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$id', 1, '$dashboardBody'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
    }

    private fun dashboardConfigurationId(): String {
        val response = given().port(port).asSession(ADMIN).get("/api/v1/dashboards/$DASHBOARD/runtime/config")
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        return response.jsonPath().getString("data.configuration_id")
    }

    private fun createAndReleaseSet(
        name: String,
        parameters: List<Map<String, Any?>>,
    ): String {
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf("name" to name, "display_name" to name, "description" to "", "parameters" to parameters),
                    ),
                ).post("/api/v1/parameter-sets")
        withClue("set $name: ${created.asString().take(EXCERPT)}") { created.statusCode shouldBe 201 }
        val data = mapper.readTree(created.asString())["data"]
        val setId = data["id"].asText()
        val bodyHash = data["body_hash"].asText()
        val released =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .header("If-Match", bodyHash)
                .post("/api/v1/parameter-sets/$setId/release")
        withClue("release $name: ${released.asString().take(EXCERPT)}") { released.statusCode shouldBe 200 }
        return setId
    }

    private fun slowParameters(): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "name" to "mode",
                "label" to "Mode",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "source" to mapOf("constants" to listOf(mapOf("value" to "slow", "display_value" to "Slow", "is_default" to true))),
            ),
            mapOf(
                "name" to "wait",
                "label" to "Wait",
                "type" to "STRING",
                "kind" to "SELECT",
                "cardinality" to "SINGLE",
                "source" to mapOf("template" to mapOf("id" to SLOW_TEMPLATE, "version" to 1), "datasource" to DATASOURCE),
                "depends_on" to listOf("mode"),
            ),
        )

    // ---- database helpers -----------------------------------------------------------------

    private fun executionRows(): Int = count("SELECT COUNT(*) FROM pipeline_executions WHERE pipeline_id = '$execPipelineId'")

    private fun count(query: String): Int =
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(query).use { rs ->
                        check(rs.next()) { "no row for: $query" }
                        rs.getInt(1)
                    }
                }
            }

    private fun sql(statement: String) {
        DriverManager
            .getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
            .use { connection ->
                connection.createStatement().use { it.execute(statement) }
            }
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private companion object {
        const val EXCERPT = 600
        const val WAIT_SECONDS = 60L
        const val POLL_MILLIS = 100L
        const val SOCKET_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 4_096
        const val TWO = 2

        const val WORKSPACE = "xcap"
        const val INSTANCE = "33333333-3333-4333-8333-333333333333"
        const val DATASOURCE = "xcap-src"
        const val FAST_TEMPLATE = "xcap/tpl/fast.sql"
        const val HUNG_TEMPLATE = "xcap/tpl/hung.sql"
        const val SLOW_TEMPLATE = "xcap/tpl/slow.sql"
        const val EXEC_PIPELINE = "xcap/pipelines/exec"
        const val HUNG_PIPELINE = "xcap/pipelines/hung"
        const val SLOW_SET = "xcap/params/slow"

        const val FAST_SQL = "SELECT 1 AS x UNION ALL SELECT 2 AS x"
        const val HUNG_SQL = "SELECT 1 AS x FROM pg_sleep(120)"
        const val SLOW_SQL =
            "SELECT 'x' AS \"value\", 'x' AS \"display_value\", FALSE AS \"is_default\" " +
                "FROM pg_sleep(CASE WHEN :mode = 'slow' THEN 25 ELSE 0 END) ORDER BY 1"

        const val VISUALIZATION_BODY =
            """{"display_name":"V","renderer":{"kind":"table","version":"1"},""" +
                """"inputs":{"main":{"columns":[{"name":"x","type":"INTEGER","nullable":false}]}},"config":{},"bindings":{"cells.x":"x"}}"""

        const val WORKSPACE_ID = "e3840000-0000-0000-0000-000000000001"
        const val ADMIN_ID = "e3840000-0000-0000-0000-0000000000a1"
        const val OTHER_ID = "e3840000-0000-0000-0000-0000000000b1"
        const val ADMIN_EMAIL = "xcap-admin@e2e.test"
        const val OTHER_EMAIL = "xcap-other@e2e.test"

        val DASHBOARD: String = UUID.randomUUID().toString()

        private val JWT_SECRET = E2eSession.newSecret()

        val ADMIN: String get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, ADMIN_EMAIL, WORKSPACE)
        val OTHER: String get() = E2eSession.jwt(JWT_SECRET, OTHER_ID, OTHER_EMAIL, WORKSPACE)

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
            // The one shared cap at its smallest useful value: two streams saturate, one is below.
            registry.add("datapipelines.sse.max-streams-per-user") { "2" }
            // A 1 s grace noticed by a 1 s tick, so a closed hold is dropped promptly.
            registry.add("datapipelines.sse.disconnect-grace-seconds") { "1" }
            registry.add("datapipelines.sse.heartbeat-interval-seconds") { "1" }
            // The slow evaluation's 25 s statement fits inside both budgets.
            registry.add("datapipelines.parameters.evaluate-timeout-seconds") { "40" }
            registry.add("datapipelines.parameters.selector-query-timeout-seconds") { "40" }
        }
    }
}
