package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * #1033 r5 — the configured drain budget (`datapipelines.result.max-size-bytes`) refuses a
 * child's APPEND through the PRODUCTION child sink (DirectResultSink → JdbcWritebackRunner),
 * and the whole local write family rolls back; a below-limit child in the same context
 * commits. This context sets the budget to 64 bytes, so the big child's FIRST row exceeds it.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class LocalWriteDrainBudgetE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()

    @Test
    fun `a child APPEND above the configured drain budget is refused and the family rolls back`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createFixtures()

        val pipelineId =
            createPipeline(
                "test/lwb_parent_big",
                "LocalWrite Budget Parent",
                listOf(
                    dmlNode("mark", "test/lwb_mark1.sql"),
                    pipelineNodeToDatasource("run_child", "test/lwb_child_big", "lwb_tgt", "append", dependsOn = listOf("mark")),
                ),
            )

        val before = localWriteOutcomes()
        val events = consumeExecutionStream(pipelineId, ADMIN_SESSION, UUID.randomUUID().toString())

        withClue(describe(events)) {
            events.last().first shouldBe "pipeline_failed"
            events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.child_execution_failed"
            // The refusal is the drain budget's, raised in the production child sink.
            events.last().second["error"]["details"]["child_error_code"].asText("") shouldBe
                "pipeline.node.writeback_failed"
        }
        scratchCount("lwb_tgt") shouldBe 0L
        scratchCount("lwb_family") shouldBe 0L
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "started") shouldBe 1.0
            delta(before, after, "rolled_back") shouldBe 1.0
            delta(before, after, "committed") shouldBe 0.0
        }
    }

    @Test
    fun `a below-limit child APPEND in the same context commits the family`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createFixtures()

        val pipelineId =
            createPipeline(
                "test/lwb_parent_small",
                "LocalWrite Budget Control Parent",
                listOf(
                    dmlNode("mark", "test/lwb_mark2.sql"),
                    pipelineNodeToDatasource("run_child", "test/lwb_child_small", "lwb_ctl", "append", dependsOn = listOf("mark")),
                ),
            )

        val before = localWriteOutcomes()
        val events = consumeExecutionStream(pipelineId, ADMIN_SESSION, UUID.randomUUID().toString())

        withClue(describe(events)) { events.lifecycleNames().last() shouldBe "pipeline_completed" }
        scratchCount("lwb_ctl") shouldBe 1L
        scratchCount("lwb_family2") shouldBe 1L
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "started") shouldBe 1.0
            delta(before, after, "committed") shouldBe 1.0
        }
    }

    // ------------------------------------------------------------ fixtures

    private fun registerLocalWriteDatasource() {
        val existing =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/datasources/$DATASOURCE")
                .then()
                .extract()
        if (existing.statusCode() == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "LocalWrite 1033 Budget", "dialect": "POSTGRES",
                 "jdbc_url": "${db.jdbcUrl}", "username": "${db.username}", "password": "${db.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun seedScratch() {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS lwb_src (id INT PRIMARY KEY, payload TEXT NOT NULL)")
                for (table in listOf("lwb_tgt", "lwb_ctl", "lwb_family", "lwb_family2")) {
                    statement.execute("CREATE TABLE IF NOT EXISTS $table (id INT PRIMARY KEY, payload TEXT)")
                }
                statement.execute("TRUNCATE lwb_src, lwb_tgt, lwb_ctl, lwb_family, lwb_family2")
                statement.execute("INSERT INTO lwb_src VALUES (1, '$PAYLOAD'), (2, '$PAYLOAD')")
            }
        }
    }

    private fun createFixtures() {
        createTemplate("test/lwb_big.sql", "POSTGRES", "LocalWrite Budget Big", "SELECT id, payload FROM lwb_src ORDER BY id")
        createTemplate("test/lwb_small.sql", "POSTGRES", "LocalWrite Budget Small", "SELECT id FROM lwb_src WHERE id = 1")
        createTemplate("test/lwb_mark1.sql", "POSTGRES", "LocalWrite Budget Mark 1", "INSERT INTO lwb_family VALUES (1, NULL)")
        createTemplate("test/lwb_mark2.sql", "POSTGRES", "LocalWrite Budget Mark 2", "INSERT INTO lwb_family2 VALUES (1, NULL)")

        createReleasedPipeline(
            "test/lwb_child_big",
            "LocalWrite Budget Child Big",
            listOf(dqlCallerNode("read_big", "test/lwb_big.sql")),
        )
        createReleasedPipeline(
            "test/lwb_child_small",
            "LocalWrite Budget Child Small",
            listOf(dqlCallerNode("read_small", "test/lwb_small.sql")),
        )
    }

    private fun dqlCallerNode(
        id: String,
        templateId: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Returns rows to the invoking parent",
            "type" to "DQL",
            "source" to DATASOURCE,
            "template" to mapOf("id" to templateId, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    private fun dmlNode(
        id: String,
        templateId: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Durable write directly on the family datasource",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to templateId, "version" to 1),
            "operation" to "update",
            "output" to mapOf("target" to "discard"),
            "depends_on" to emptyList<String>(),
        )

    private fun pipelineNodeToDatasource(
        id: String,
        childName: String,
        table: String,
        mode: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Invoke $childName v1 onto $table",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to childName, "version" to 1),
            "output" to
                mapOf(
                    "target" to "datasource",
                    "datasource" to DATASOURCE,
                    "table" to table,
                    "mode" to mode,
                ),
            "depends_on" to dependsOn,
        )

    private fun ensureAuthSeeded() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                    VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', '$ADMIN_EMAIL', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }
        }
    }

    /** 101/D58 chain: templates lock first — a child cannot release while its pin is a draft. */
    private fun createTemplate(
        id: String,
        dialect: String,
        displayName: String,
        body: String,
    ) {
        synchronized(created) {
            if (id in createdTemplates) return
            createdTemplates += id
        }
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    """
                    {"id": "$id", "dialect": "$dialect", "display_name": "$displayName",
                     "description": "LocalWrite Budget E2E template", "imports": [],
                     "body": ${mapper.writeValueAsString(body)}}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", response.jsonPath().getString("data.body_hash"))
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun createPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val bodyJson =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to displayName,
                    "description" to "LocalWrite Budget E2E pipeline — $displayName",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        val response: Response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(bodyJson)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() != 201) {
            throw AssertionError("Pipeline '$name' creation failed (${response.statusCode()}): ${response.body().asString()}")
        }
        return response.jsonPath().getString("data.id")
    }

    private fun createReleasedPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        synchronized(created) {
            if (name in createdChildren) return cachedChildIds.getValue(name)
        }
        val id = createPipeline(name, displayName, nodes)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", pipelineHash(id))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        synchronized(created) {
            createdChildren += name
            cachedChildIds[name] = id
        }
        return id
    }

    private fun pipelineHash(id: String): String =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/pipelines/$id")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getString("data.body_hash")

    // ------------------------------------------------------------ reads

    private fun scratchCount(table: String): Long =
        DriverManager
            .getConnection(db.jdbcUrl, db.username, db.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM $table").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }

    /** The `local_write_transactions` outcome counters — see the twin suite for the rationale. */
    private fun localWriteOutcomes(): Map<String, Double> {
        val registry: Any = context.getBean(Class.forName("io.micrometer.core.instrument.MeterRegistry"))
        val find = registry.javaClass.getMethod("find", String::class.java).invoke(registry, METRIC_NAME)
        val counters = find.javaClass.getMethod("counters").invoke(find) as Collection<*>
        return counters.associate { counter ->
            val id = counter!!.javaClass.getMethod("getId").invoke(counter)
            val outcome = id.javaClass.getMethod("getTag", String::class.java).invoke(id, "outcome") as String
            val count = counter.javaClass.getMethod("count").invoke(counter) as Double
            outcome to count
        }
    }

    private fun delta(
        before: Map<String, Double>,
        after: Map<String, Double>,
        outcome: String,
    ): Double = (after[outcome] ?: 0.0) - (before[outcome] ?: 0.0)

    private fun describe(events: List<Pair<String, JsonNode>>): String =
        events.joinToString(prefix = "[", postfix = "]") { (name, payload) ->
            val code = payload.path("error").path("code").asText("")
            if (code.isEmpty()) name else "$name($code)"
        }

    // ------------------------------------------------------------ stream

    private fun consumeExecutionStream(
        pipelineId: String,
        session: String,
        correlationId: String,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(session))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", correlationId)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe 200
            E2eSse.parseEvents(response.body(), mapper)
        }

    // ------------------------------------------------------------ companion

    companion object {
        private const val DATASOURCE = "lw1033b"
        private const val METRIC_NAME = "datapipelines.executions.local_write_transactions"
        private const val SSE_BUDGET_MINUTES = 2L

        /** 100 chars — the first row of the big child alone exceeds the 64-byte drain budget. */
        private val PAYLOAD = "x".repeat(100)

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-lw1033b@datapipelines.test"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)

        private val JWT_SECRET = E2eSession.newSecret()

        private val postgres get() = SharedE2e.postgres
        private val db = SharedE2e.scratchDatabase("localwrite_budget")
        private val redis get() = SharedE2e.redis

        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private val oidc = OidcDiscoveryStub()

        /** Fixture memos: one creation per context, shared by every test in the class. */
        private val created = Any()
        private val createdTemplates = mutableSetOf<String>()
        private val createdChildren = mutableSetOf<String>()
        private val cachedChildIds = mutableMapOf<String, String>()

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
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // THE subject of this suite: the drain budget the production child sink enforces.
            registry.add("datapipelines.result.max-size-bytes") { "64" }

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
