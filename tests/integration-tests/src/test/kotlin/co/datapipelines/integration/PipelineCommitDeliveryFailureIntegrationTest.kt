package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
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

/**
 * #1035 R4 — a delivery failure AFTER a confirmed commit, on the production seam.
 *
 * The family's customer COMMIT is real and confirmed; the failure is injected at the one collaborator that builds what the
 * caller is told after the commit (the result URL of `data_ready`), armed for exactly one call. Everything else is the
 * production assembly: the executor, the customer database, the real emitter and its stores, the REST and MCP surfaces.
 * (The production emitter swallows its own sink failures by design, so a throwing sink inside it is not reachable from
 * outside; the URL builder is the collaborator whose failure `deliverHeldResult` receives.) The contract: the execution is
 * SUCCESS, the result is reported unavailable separately, the target rows exist exactly once, and nothing that reads the
 * record — a status fetch, a result fetch, a duplicate request carrying the same idempotency key — runs the writes again.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(PipelineCommitDeliveryFailureIntegrationTest.FaultyDelivery::class)
class PipelineCommitDeliveryFailureIntegrationTest {
    /**
     * The injected fault: the next URL the delivery asks for fails once, as an unreachable result store would. A bean
     * post-processor wraps the application's own `resultUrlFactory` in a proxy of its interface (looked up by name: the
     * module rules keep `dag` off this module's compile classpath), so every other collaborator is the production one.
     */
    class FaultyDelivery : BeanPostProcessor {
        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (beanName != "resultUrlFactory") return bean
            val contract = Class.forName("co.datapipelines.executor.ResultUrlFactory")
            return Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, args ->
                if (method.name == "urlFor" && ARMED.compareAndSet(true, false)) {
                    error("result delivery unavailable (injected once)")
                }
                try {
                    method.invoke(bean, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val history by lazy { CommitHistoryReader(port, ADMIN_SESSION, MCP_KEY.plaintext) }

    @Test
    fun `a delivery failure after the confirmed commit leaves SUCCESS, an unavailable result and the writes exactly once`() {
        val api = CommitScenarioApi(port, ADMIN_SESSION, DATASOURCE)
        seed(api)
        val pipelineId =
            api.pipeline(
                "test/cd_delivery_failure",
                listOf(
                    api.copy("copy", "test/cd_read.sql", "cd_tgt", emptyList()),
                    api.caller("ret", "test/cd_read_one.sql", listOf("copy")),
                ),
            )

        val key = "cd1035-${UUID.randomUUID()}"
        ARMED.set(true)
        val events = stream(pipelineId, key)

        ARMED.get() shouldBe false
        val names = events.map { it.first }.filter { it != "node_progress" }
        withClue("events=$names") {
            names shouldContainExactly
                listOf("execution_started", "node_started", "node_completed", "node_started", "node_completed", "pipeline_completed")
        }
        val executionId = events.first { it.first == "execution_started" }.second["execution_id"].asText()
        val rest =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId")
                .thenReturn()
        withClue("REST ${rest.asString().take(EXCERPT)}") {
            rest.jsonPath().getString("data.status") shouldBe "SUCCESS"
            rest.jsonPath().getBoolean("data.result_unavailable") shouldBe true
            rest.jsonPath().getString("data.result_url").let { it == null } shouldBe true
        }
        val row = metaRow(executionId)
        row["commit_attempted_at"].shouldNotBeNull()
        row["commit_confirmed_at"].shouldNotBeNull()
        row["result_unavailable"] shouldBe "true"
        count("cd_tgt") shouldBe ROWS

        assertResultUnavailable(executionId)
        assertDuplicateRunsNothing(pipelineId, key)
    }

    private fun assertResultUnavailable(executionId: String) {
        // The result is unavailable on every reader, with the delivery error and without a rerun.
        val cursor =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .thenReturn()
        withClue("cursor ${cursor.asString().take(EXCERPT)}") {
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
        fetched.toString().contains("result_delivery_failed") shouldBe true
        history.assertCompletedWithoutResult(executionId, "delivery failure")
    }

    /** A duplicate of the request (same idempotency key) is answered from the record: the writes do not run again. */
    private fun assertDuplicateRunsNothing(
        pipelineId: String,
        key: String,
    ) {
        val duplicate = stream(pipelineId, key)
        duplicate.isNotEmpty() shouldBe true
        count("cd_tgt") shouldBe ROWS
        metaScalar("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = '$pipelineId'") shouldBe "1"
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
        response.statusCode() shouldBe 200
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

    private fun seed(api: CommitScenarioApi) {
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
                s.execute("CREATE TABLE co_src (id INT PRIMARY KEY)")
                s.execute("INSERT INTO co_src SELECT generate_series(1, $ROWS)")
                s.execute("CREATE TABLE cd_tgt (id INT PRIMARY KEY)")
            }
        }
        api.registerDatasource(db.jdbcUrl, db.username, db.password)
        api.template("test/cd_read.sql", "SELECT id FROM co_src ORDER BY id")
        api.template("test/cd_read_one.sql", "SELECT id FROM co_src WHERE id = 1")
    }

    private fun metaRow(id: String): Map<String, String?> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT status, commit_attempted_at::text, commit_confirmed_at::text, result_unavailable::text " +
                            "FROM pipeline_executions WHERE execution_id = '$id'",
                    ).use { rows ->
                        check(rows.next()) { "no execution row $id" }
                        mapOf(
                            "status" to rows.getString(1),
                            "commit_attempted_at" to rows.getString(2),
                            "commit_confirmed_at" to rows.getString(3),
                            "result_unavailable" to rows.getString(4),
                        )
                    }
            }
        }

    private fun metaScalar(sql: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { it.queryString(sql) }

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
        private const val DATASOURCE = "cd1035"
        private const val ROWS = 2500
        private const val EXCERPT = 300
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val ADMIN_EMAIL = "e2e-cd1035@datapipelines.test"
        private val ARMED = AtomicBoolean(false)
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val MCP_KEY = E2eAuth.generateKey("cd1035-mcp-reader")
        private val MCP_IDENTITY_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val postgres get() = SharedE2e.postgres
        private val db = SharedE2e.scratchDatabase("commitdelivery1035")
        private val oidc = OidcDiscoveryStub()

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
