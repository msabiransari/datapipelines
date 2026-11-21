package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * #1035 R4/R5 — the REAL stored caller result is lost after the commit is durably confirmed, on a running instance:
 * before the post-confirmation read-back starts, and (R5) inside it, after the store's existence check of the rows
 * answered true and before the row read.
 *
 * Nothing in the executor is stubbed: the customer COMMIT is real and confirmed, and the result's two actual Redis keys
 * are deleted (both checked to exist and to be removed) once `commit_confirmed_at` is durable, before anything announces
 * the result. The deletion is triggered from the first post-confirmation call the production code makes into the
 * application — the result-URL factory — which itself keeps working (the source of the round-2 review fixture
 * `CommitResultLossReviewIntegrationTest`). The run must stay SUCCESS with its writes exactly once and report the caller
 * result as unavailable on the record, the stream, REST and MCP — never a cached `data_ready` and never an empty success
 * that reads as a zero-caller run. The genuine zero-caller run is checked beside it and must stay an empty success.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(PipelineCommitResultLossIntegrationTest.ResultLoss::class)
class PipelineCommitResultLossIntegrationTest {
    /**
     * Deletes the armed run's actual stored result after the durable confirmation, the factory itself still answering:
     * [Timing.BEFORE_READ] at the first post-confirmation call (the result-URL factory), [Timing.DURING_READ] right after
     * the store's own existence probe of the rows answered true, before its row read.
     */
    class ResultLoss : BeanPostProcessor {
        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            // The Redis implementation types are not on this module's compile classpath; found at runtime, as elsewhere.
            if (Class.forName("org.springframework.data.redis.core.StringRedisTemplate").isInstance(bean)) return probed(bean)
            if (beanName != "resultUrlFactory") return bean
            val contract = Class.forName("co.datapipelines.executor.ResultUrlFactory")
            return Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, args ->
                if (method.name == "urlFor" &&
                    ARMED.compareAndSet(true, false)
                ) {
                    val executionId = requireNotNull(args?.firstOrNull()).toString()
                    when (TIMING.get()) {
                        Timing.BEFORE_READ -> loseStoredResult(executionId)
                        Timing.DURING_READ -> PROBE_ARMED.set("dp:result:$executionId:rows")
                    }
                }
                try {
                    method.invoke(bean, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }

        /** The real template; once armed, a real `EXISTS` of the run's rows that answers true is followed by the loss. */
        private fun probed(template: Any): Any {
            val factory = ProxyFactory(template)
            factory.isProxyTargetClass = true
            factory.addAdvice(
                MethodInterceptor { invocation ->
                    val observed = invocation.proceed()
                    if (invocation.method.name == "hasKey" && observed == true) loseIfArmed(invocation.arguments.firstOrNull())
                    observed
                },
            )
            return factory.proxy
        }

        /** Disarms by equality, then by the armed instance (compareAndSet compares identity), so the loss happens once. */
        private fun loseIfArmed(key: Any?) {
            val armed = PROBE_ARMED.get() ?: return
            if (key == armed && PROBE_ARMED.compareAndSet(armed, null)) {
                loseStoredResult(armed.removePrefix("dp:result:").removeSuffix(":rows"))
            }
        }

        private fun loseStoredResult(executionId: String) {
            val confirmed =
                metaScalar("SELECT commit_confirmed_at IS NOT NULL FROM pipeline_executions WHERE execution_id = '$executionId'")
            check(confirmed == "t") { "the loss must happen AFTER the durable confirmation (was $confirmed)" }
            val mode = MODE.get()
            val keys = mode.keys.map { "dp:result:$executionId:$it" }
            val deleted = SharedE2e.redis.execInContainer("redis-cli", "DEL", *keys.toTypedArray())
            check(deleted.exitCode == 0 && deleted.stdout.trim() == keys.size.toString()) {
                "the actual result keys ${mode.keys} must exist and be removed: $deleted"
            }
            if (mode == Loss.ROWS_ONLY) {
                val header = SharedE2e.redis.execInContainer("redis-cli", "EXISTS", "dp:result:$executionId:meta")
                check(header.exitCode == 0 && header.stdout.trim() == "1") { "the header must survive a rows-only loss: $header" }
            }
            LOST.set(executionId)
        }
    }

    /** Which of the result's two real Redis keys the loss removes; the header (`meta`) survives a rows-only loss. */
    enum class Loss(
        val keys: List<String>,
    ) {
        BOTH_KEYS(listOf("rows", "meta")),
        ROWS_ONLY(listOf("rows")),
    }

    /** When the loss happens: before the post-confirmation read-back starts, or inside it between `EXISTS` and `LRANGE`. */
    enum class Timing { BEFORE_READ, DURING_READ }

    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val history by lazy { CommitHistoryReader(port, ADMIN_SESSION, MCP_KEY.plaintext) }
    private val api by lazy { CommitScenarioApi(port, ADMIN_SESSION, DATASOURCE) }

    @Test
    fun `a stored result lost after the confirmed commit is SUCCESS with the result unavailable on every surface`() =
        restLoss(Loss.BOTH_KEYS, Timing.BEFORE_READ, "rl_rest")

    @Test
    fun `an MCP run whose stored result is lost after the confirmed commit answers SUCCESS with the result unavailable`() =
        mcpLoss(Loss.BOTH_KEYS, Timing.BEFORE_READ, "rl_mcp")

    @Test
    fun `a result whose rows are lost while its header survives is SUCCESS with the result unavailable on every surface`() =
        restLoss(Loss.ROWS_ONLY, Timing.BEFORE_READ, "rl_rest_rows")

    @Test
    fun `an MCP run whose result rows are lost while the header survives answers SUCCESS with the result unavailable`() =
        mcpLoss(Loss.ROWS_ONLY, Timing.BEFORE_READ, "rl_mcp_rows")

    @Test
    fun `a stored result lost between the existence check and the row read is SUCCESS with the result unavailable (R5)`() =
        restLoss(Loss.BOTH_KEYS, Timing.DURING_READ, "rl_rest_read")

    @Test
    fun `an MCP run whose stored result is lost between the existence check and the row read answers unavailable (R5)`() =
        mcpLoss(Loss.BOTH_KEYS, Timing.DURING_READ, "rl_mcp_read")

    @Test
    fun `result rows lost between the existence check and the row read, header kept, are SUCCESS and unavailable (R5)`() =
        restLoss(Loss.ROWS_ONLY, Timing.DURING_READ, "rl_rest_read_rows")

    @Test
    fun `an MCP run whose rows are lost between the existence check and the row read, header kept, answers unavailable (R5)`() =
        mcpLoss(Loss.ROWS_ONLY, Timing.DURING_READ, "rl_mcp_read_rows")

    /**
     * The REST/SSE run: the stream ends `pipeline_completed` with the result unavailable and no `data_ready`; record,
     * readers and history agree; a same-key duplicate replays no `data_ready`; the customer writes happened once.
     */
    private fun restLoss(
        mode: Loss,
        timing: Timing,
        name: String,
    ) {
        seedOnce()
        MODE.set(mode)
        TIMING.set(timing)
        val target = "rl_tgt_${name.removePrefix("rl_")}"
        val pipelineId = callerPipeline("test/$name", target)
        val key = "rl1035-${UUID.randomUUID()}"
        ARMED.set(true)

        val events = stream(pipelineId, key)

        val executionId = events.first { it.first == "execution_started" }.second["execution_id"].asText()
        LOST.get() shouldBe executionId
        val names = events.map { it.first }.filter { it != "node_progress" }
        withClue("the stream after the loss ($mode, $timing): $names") {
            names.last() shouldBe "pipeline_completed"
            names.contains("data_ready") shouldBe false
            events.last().second["result_unavailable"]?.asBoolean() shouldBe true
        }
        assertRecordUnavailable(executionId)
        assertReadersReportUnavailable(executionId)
        history.assertCompletedWithoutResult(executionId, "result lost after the confirmation ($mode, $timing)")

        val duplicate = stream(pipelineId, key).map { it.first }
        withClue("same-key duplicate: $duplicate") { duplicate.contains("data_ready") shouldBe false }
        count(target) shouldBe ROWS
        metaScalar("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = '$pipelineId'") shouldBe "1"
    }

    /** The MCP `pipelines_execute` run: SUCCESS with `result_unavailable` and no rows, never `isError`; writes once. */
    private fun mcpLoss(
        mode: Loss,
        timing: Timing,
        name: String,
    ) {
        seedOnce()
        MODE.set(mode)
        TIMING.set(timing)
        val target = "rl_tgt_${name.removePrefix("rl_")}"
        val pipelineId = callerPipeline("test/$name", target, released = true)
        ARMED.set(true)

        val payload =
            history.mcpCall(
                "tools/call",
                mapOf(
                    "name" to "pipelines_execute",
                    "arguments" to mapOf("id" to pipelineId, "parameters" to emptyMap<String, Any>()),
                ),
            )

        withClue("pipelines_execute after the loss ($mode, $timing): $payload") {
            payload.path("isError").asBoolean(false) shouldBe false
            val body = mapper.readTree(payload["content"][0]["text"].asText())
            body["status"].asText() shouldBe "SUCCESS"
            body["result_unavailable"]?.asBoolean() shouldBe true
            body.has("rows") shouldBe false
            LOST.get() shouldBe body["execution_id"].asText()
            assertRecordUnavailable(body["execution_id"].asText())
        }
        count(target) shouldBe ROWS
    }

    @Test
    fun `a confirmed commit with no caller node stays an empty success - not reported unavailable`() {
        seedOnce()
        val pipelineId = api.pipeline("test/rl_zero", listOf(api.copy("copy", "test/rl_read.sql", "rl_tgt_zero", emptyList())))
        ARMED.set(false)

        val events = stream(pipelineId, "rl1035-${UUID.randomUUID()}")

        val executionId = events.first { it.first == "execution_started" }.second["execution_id"].asText()
        events.map { it.first }.contains("data_ready") shouldBe false
        events.last().second.has("result_unavailable") shouldBe false
        val record = metaRow(executionId)
        record["status"] shouldBe "SUCCESS"
        record["commit_confirmed_at"].shouldNotBeNull()
        record["result_unavailable"] shouldBe "false"
        record["result_row_count"] shouldBe null
        val cursor = cursor(executionId)
        withClue("zero-caller cursor ${cursor.asString().take(EXCERPT)}") {
            cursor.statusCode shouldBe HTTP_OK
            cursor.jsonPath().getLong("data.total_rows") shouldBe 0L
        }
        val fetched =
            history.mcpCall(
                "tools/call",
                mapOf(
                    "name" to "executions_get_result",
                    "arguments" to mapOf("execution_id" to executionId),
                ),
            )
        withClue("zero-caller MCP $fetched") { fetched.path("isError").asBoolean(false) shouldBe false }
        count("rl_tgt_zero") shouldBe ROWS
    }

    /** The record: SUCCESS, both commit stamps, `result_unavailable`, the prepared caller result's count kept, no result URL. */
    private fun assertRecordUnavailable(executionId: String) {
        val row = metaRow(executionId)
        val rest =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .thenReturn()
        withClue("record $row / REST ${rest.asString().take(EXCERPT)}") {
            row["status"] shouldBe "SUCCESS"
            row["commit_attempted_at"].shouldNotBeNull()
            row["commit_confirmed_at"].shouldNotBeNull()
            row["result_unavailable"] shouldBe "true"
            row["result_row_count"] shouldBe "1"
            rest.jsonPath().getString("data.status") shouldBe "SUCCESS"
            rest.jsonPath().getBoolean("data.result_unavailable") shouldBe true
            rest.jsonPath().getString("data.result_url") shouldBe null
        }
    }

    /** The REST cursor and the MCP result tool refuse with the delivery failure — no empty page, no `isError: false`. */
    private fun assertReadersReportUnavailable(executionId: String) {
        val cursor = cursor(executionId)
        withClue("cursor ${cursor.asString().take(EXCERPT)}") {
            cursor.statusCode shouldBe HTTP_UNAVAILABLE
            cursor.jsonPath().getString("error.code") shouldBe "result.storage_unavailable"
            cursor.jsonPath().getString("error.details.reason") shouldBe "result_delivery_failed"
        }
        val fetched =
            history.mcpCall(
                "tools/call",
                mapOf(
                    "name" to "executions_get_result",
                    "arguments" to mapOf("execution_id" to executionId),
                ),
            )
        withClue("MCP executions_get_result $fetched") {
            fetched.path("isError").asBoolean(false) shouldBe true
            fetched.toString().contains("result_delivery_failed") shouldBe true
        }
    }

    private fun cursor(executionId: String): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get("/api/v1/executions/$executionId/result")
            .thenReturn()

    private fun callerPipeline(
        name: String,
        target: String,
        released: Boolean = false,
    ): String {
        val nodes =
            listOf(api.copy("copy", "test/rl_read.sql", target, emptyList()), api.caller("ret", "test/rl_read_one.sql", listOf("copy")))
        return if (released) api.releasedPipeline(name, nodes) else api.pipeline(name, nodes)
    }

    private fun stream(
        pipelineId: String,
        idempotencyKey: String,
    ): List<Pair<String, JsonNode>> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("DP-Correlation-Id", UUID.randomUUID().toString())
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.statusCode() shouldBe HTTP_OK
        val events = mutableListOf<Pair<String, JsonNode>>()
        BufferedReader(InputStreamReader(response.body())).use { reader ->
            var current: String? = null
            reader.lines().forEach { line ->
                if (line.startsWith("event:")) current = line.removePrefix("event:").trim()
                if (line.startsWith("data:")) events += (current ?: "unknown") to mapper.readTree(line.removePrefix("data:").trim())
            }
        }
        return events
    }

    /** One seed per class: the shared Spring context keeps the users, key, datasource and templates across tests. */
    private fun seedOnce() {
        if (!SEEDED.compareAndSet(false, true)) return
        metaUpdate(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
            VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', '$ADMIN_EMAIL', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE) ON CONFLICT (id) DO NOTHING;
            INSERT INTO workspace_members (workspace_id, user_id, role)
            VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin') ON CONFLICT DO NOTHING;
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind)
            VALUES ('$MCP_IDENTITY_ID', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', '${MCP_KEY.id}', TRUE, FALSE, 'service')
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)
            VALUES ('${MCP_KEY.id}', '$MCP_IDENTITY_ID', '$ADMIN_USER_ID', '${MCP_KEY.name}', '${MCP_KEY.hash}', '$DEFAULT_WORKSPACE', 'mcp', 'workspace_admin')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE rl_src (id INT PRIMARY KEY)")
                s.execute("INSERT INTO rl_src SELECT generate_series(1, $ROWS)")
                TARGETS.forEach {
                    s.execute("CREATE TABLE $it (id INT PRIMARY KEY)")
                }
            }
        }
        api.registerDatasource(db.jdbcUrl, db.username, db.password)
        api.template("test/rl_read.sql", "SELECT id FROM rl_src ORDER BY id")
        api.template("test/rl_read_one.sql", "SELECT id FROM rl_src WHERE id = 1")
    }

    private fun metaRow(id: String): Map<String, String?> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT status, commit_attempted_at::text, commit_confirmed_at::text, result_unavailable::text, " +
                            "result_row_count::text FROM pipeline_executions WHERE execution_id = '$id'",
                    ).use { rows ->
                        check(rows.next()) { "no execution row $id" }
                        listOf("status", "commit_attempted_at", "commit_confirmed_at", "result_unavailable", "result_row_count")
                            .mapIndexed { i, column -> column to rows.getString(i + 1) }
                            .toMap()
                    }
            }
        }

    private fun metaUpdate(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { it.run(sql) }
    }

    private fun count(table: String): Int =
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    companion object {
        private const val DATASOURCE = "rl1035"
        private const val ROWS = 2500
        private const val EXCERPT = 600
        private const val HTTP_OK = 200
        private const val HTTP_UNAVAILABLE = 503
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val ADMIN_EMAIL = "e2e-rl1035@datapipelines.test"
        private val ARMED = AtomicBoolean(false)
        private val MODE = AtomicReference(Loss.BOTH_KEYS)
        private val TIMING = AtomicReference(Timing.BEFORE_READ)
        private val PROBE_ARMED = AtomicReference<String?>()
        private val TARGETS =
            listOf("rest", "mcp", "zero", "rest_rows", "mcp_rows", "rest_read", "mcp_read", "rest_read_rows", "mcp_read_rows")
                .map { "rl_tgt_$it" }
        private val SEEDED = AtomicBoolean(false)
        private val LOST = AtomicReference<String?>()
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val MCP_KEY = E2eAuth.generateKey("rl1035-mcp-reader")
        private val MCP_IDENTITY_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val postgres get() = SharedE2e.postgres
        private val db = SharedE2e.scratchDatabase("commitresultloss1035")
        private val oidc = OidcDiscoveryStub()

        private fun metaScalar(sql: String): String? =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { it.queryString(sql) }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") {
                Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
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
