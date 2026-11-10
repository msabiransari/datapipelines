package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #841 (G0) R3 of the landing review — the native TRANSFER writers and the pipeline current switch, at the wire over the
 * real application and the shared test Postgres: the pipeline import (`PipelineImportService`), the visualization and
 * dashboard imports (`ArtifactTransferService`), the parameter-set import (`ParameterSetTransferService`) and the
 * pipeline current switch (`PipelineService.switchCurrent`). Each is a pin-affecting writer of record §4.3 that takes the
 * workspace writer guard first.
 *
 * The shape of `G0WriterContentionE2eTest`'s busy rows: a raw connection holds the workspace row (`FOR NO KEY UPDATE`, as
 * a release in flight holds it); the writer is observed WAITING on it (transitive `pg_blocking_pids`, on a fresh
 * connection), exhausts the production bound (5 s) and answers its family's busy code with details exactly
 * `{lock_wait_seconds}`; the stored versions are unchanged; once the row is free the same request succeeds (the
 * successful control — an export re-imported is the idempotent no-op, the switch lands).
 */
@SpringBootTest(
    classes = [co.datapipelines.DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class G0NativeTransferWritersE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val pool = Executors.newFixedThreadPool(2)
    private var rpcId = 0

    private lateinit var pipelineId: String
    private lateinit var visualizationId: String
    private lateinit var dashboardId: String
    private lateinit var parameterSetId: String

    // ---- the holder and the waiter probe ----------------------------------------------------------------------

    private class Holder(
        val connection: Connection,
        val pid: Int,
    ) : AutoCloseable {
        override fun close() {
            if (!connection.isClosed) {
                connection.rollback()
                connection.close()
            }
        }
    }

    private fun holdWorkspace(): Holder {
        val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        connection.autoCommit = false
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ?::uuid FOR NO KEY UPDATE").use { ps ->
            ps.setString(1, WORKSPACE_ID)
            ps.execute()
        }
        val pid =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }
        return Holder(connection, pid)
    }

    private fun jdbc() = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

    /** [writer] against a held workspace row, observed waiting (RED if it answers first), left to exhaust the bound. */
    private fun exhausts(writer: () -> Response): Response =
        holdWorkspace().use { holder ->
            val answer = CompletableFuture.supplyAsync({ writer() }, pool)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_PROBE_SECONDS)
            while ((jdbc().queryForObject(WAITERS_SQL, Int::class.java, holder.pid) ?: 0) < 1) {
                withClue("the writer answered without waiting on the held row: ${if (answer.isDone) answer.join().asString() else ""}") {
                    answer.isDone shouldBe false
                }
                withClue("no waiter on the holder within the probe window") { (System.nanoTime() < deadline) shouldBe true }
                Thread.sleep(PROBE_INTERVAL_MS)
            }
            answer.get(WRITER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

    private fun assertRestBusy(
        response: Response,
        code: String,
    ) {
        withClue(response.asString()) { response.statusCode shouldBe 409 }
        val error = mapper.readTree(response.asString())["error"]
        error["code"].asText() shouldBe code
        error["message"].asText() shouldBe BUSY_MESSAGE
        error["details"] shouldBe mapper.readTree("""{"lock_wait_seconds": $DEFAULT_LOCK_WAIT_SECONDS}""")
    }

    // ---- wire helpers -----------------------------------------------------------------------------------------

    private fun callTool(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<JsonNode, Boolean> {
        val id = synchronized(this) { ++rpcId }
        val body =
            mapOf("jsonrpc" to "2.0", "id" to id, "method" to "tools/call", "params" to mapOf("name" to name, "arguments" to arguments))
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/mcp"))
                .header("DP-API-Key", KEY.plaintext)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() shouldBe 200
        val tree = mapper.readTree(response.body())
        withClue("JSON-RPC error on $name: ${response.body()}") { tree.has("error") shouldBe false }
        val result = tree["result"]
        return mapper.readTree(result["content"][0]["text"].asText()) to result.path("isError").asBoolean(false)
    }

    private fun rest(): RequestSpecification = given().port(port).asSession(SESSION)

    private fun post(
        path: String,
        body: String,
    ): Response = rest().contentType(ContentType.JSON).body(body).post(path)

    /** The `data` of a `GET` [path] that must answer 200. */
    private fun dataOf(path: String): JsonNode {
        val response = rest().get(path)
        withClue(response.asString()) { response.statusCode shouldBe 200 }
        return mapper.readTree(response.asString())["data"]
    }

    /** Every stored version of the artifact [name] in [table], `version:STATUS` ascending — the persisted state. */
    private fun versions(
        table: String,
        name: String,
    ): String {
        val (versionsTable, fk) = requireNotNull(VERSION_TABLES[table]) { "not an artifact table: $table" }
        return jdbc().queryForObject(
            "SELECT coalesce(string_agg(v.version || ':' || v.status, ',' ORDER BY v.version), '') FROM $versionsTable v" +
                " JOIN $table a ON a.id = v.$fk WHERE a.workspace_id = ?::uuid AND a.name = ?",
            String::class.java,
            WORKSPACE_ID,
            name,
        ) ?: ""
    }

    private fun idOf(
        table: String,
        name: String,
    ): String {
        require(table in VERSION_TABLES) { "not an artifact table: $table" }
        val sql = "SELECT id::text FROM $table WHERE workspace_id = ?::uuid AND name = ?"
        return jdbc().queryForObject(sql, String::class.java, WORKSPACE_ID, name) ?: error("no $table row named $name")
    }

    /** The rows a real release writes, stamped by SQL — `PromotionTwoDeploymentE2eTest.stampReleased`'s precedent. */
    private fun stampReleased(
        table: String,
        id: String,
    ) {
        val (versionsTable, fk) = requireNotNull(VERSION_TABLES[table]) { "not an artifact table: $table" }
        jdbc().update(
            "UPDATE $versionsTable SET status = 'RELEASED', released_at = NOW(), released_by = ?::uuid" +
                " WHERE $fk = ?::uuid AND version = 1",
            ADMIN_ID,
            id,
        )
        jdbc().update("UPDATE $table SET current_version = 1 WHERE id = ?::uuid", id)
    }

    // ---- the fixture ------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `the fixture - a released pipeline, visualization, dashboard and parameter set`() {
        post(
            "/api/v1/datasources",
            """
            {"name": "$DATASOURCE", "display_name": "G0 transfer writers", "dialect": "H2",
             "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
            """.trimIndent(),
        ).statusCode shouldBe 201
        val (template, templateFailed) =
            callTool(
                "templates_create",
                mapOf(
                    "id" to TEMPLATE,
                    "dialect" to "H2",
                    "display_name" to "G0",
                    "description" to "Expects: nothing (plain SQL).",
                    "body" to "SELECT 1 AS n",
                ),
            )
        withClue(template) { templateFailed shouldBe false }
        val (created, pipelineFailed) = callTool("pipelines_create", PIPELINE_ARGUMENTS)
        withClue(created) { pipelineFailed shouldBe false }
        pipelineId = created["id"].asText()
        val released =
            rest()
                .header("If-Match", created["draft"]["body_hash"].asText())
                .queryParam("release_pinned_templates", "true")
                .post("/api/v1/pipelines/$pipelineId/release")
        withClue(released.asString()) { released.statusCode shouldBe 200 }

        val visualization = post("/api/v1/visualizations", VISUALIZATION_DOCUMENT)
        withClue(visualization.asString()) { visualization.statusCode shouldBe 201 }
        visualizationId = visualization.jsonPath().getString("data.id")
        stampReleased("visualizations", visualizationId)
        val dashboard = post("/api/v1/dashboards", DASHBOARD_DOCUMENT)
        withClue(dashboard.asString()) { dashboard.statusCode shouldBe 201 }
        dashboardId = dashboard.jsonPath().getString("data.id")
        stampReleased("dashboards", dashboardId)
        val (set, setFailed) = callTool("parameter_sets_create", PARAMETER_SET_ARGUMENTS)
        withClue(set) { setFailed shouldBe false }
        parameterSetId = idOf("parameter_sets", PARAMETER_SET)
        stampReleased("parameter_sets", parameterSetId)

        listOf("pipelines" to PIPELINE, "visualizations" to VISUALIZATION, "dashboards" to DASHBOARD, "parameter_sets" to PARAMETER_SET)
            .forEach { (table, name) -> withClue(table) { versions(table, name) shouldBe "1:RELEASED" } }
    }

    // ---- busy at each transfer boundary, then the idempotent re-import ----------------------------------------

    @Test
    @Order(2)
    fun `pipeline import over REST - pipeline release busy, nothing landed - then the re-import is the no-op`() {
        val exported = mapper.writeValueAsString(dataOf("/api/v1/pipelines/$pipelineId/export")["pipeline"])
        assertRestBusy(exhausts { post("/api/v1/pipelines/import", exported) }, "pipeline.release.busy")
        versions("pipelines", PIPELINE) shouldBe "1:RELEASED"

        val control = post("/api/v1/pipelines/import", exported)
        withClue("the successful control: ${control.asString()}") { control.statusCode shouldBe 200 }
        versions("pipelines", PIPELINE) shouldBe "1:RELEASED"
    }

    @Test
    @Order(3)
    fun `visualization import over REST - visualization release busy, nothing landed - then the re-import is the no-op`() {
        val exported = mapper.writeValueAsString(dataOf("/api/v1/visualizations/$visualizationId/export"))
        assertRestBusy(exhausts { post("/api/v1/visualizations/import", exported) }, "visualization.release.busy")
        versions("visualizations", VISUALIZATION) shouldBe "1:RELEASED"

        val control = post("/api/v1/visualizations/import", exported)
        withClue("the successful control: ${control.asString()}") { (control.statusCode in SUCCESS) shouldBe true }
        versions("visualizations", VISUALIZATION) shouldBe "1:RELEASED"
    }

    @Test
    @Order(4)
    fun `dashboard import over REST - dashboard release busy, nothing landed - then the re-import is the no-op`() {
        val exported = mapper.writeValueAsString(dataOf("/api/v1/dashboards/$dashboardId/export"))
        assertRestBusy(exhausts { post("/api/v1/dashboards/import", exported) }, "dashboard.release.busy")
        versions("dashboards", DASHBOARD) shouldBe "1:RELEASED"

        val control = post("/api/v1/dashboards/import", exported)
        withClue("the successful control: ${control.asString()}") { (control.statusCode in SUCCESS) shouldBe true }
        versions("dashboards", DASHBOARD) shouldBe "1:RELEASED"
    }

    @Test
    @Order(5)
    fun `parameter set import over REST - parameter release busy, nothing landed - then the re-import is the no-op`() {
        val exported = mapper.writeValueAsString(dataOf("/api/v1/parameter-sets/$parameterSetId/export"))
        assertRestBusy(exhausts { post("/api/v1/parameter-sets/import", exported) }, "parameter.release.busy")
        versions("parameter_sets", PARAMETER_SET) shouldBe "1:RELEASED"

        val control = post("/api/v1/parameter-sets/import", exported)
        withClue("the successful control: ${control.asString()}") { control.statusCode shouldBe 200 }
        control.jsonPath().getBoolean("data.import_unchanged") shouldBe true
        versions("parameter_sets", PARAMETER_SET) shouldBe "1:RELEASED"
    }

    @Test
    @Order(6)
    fun `pipeline current switch over REST - pipeline release busy, the pointer stays - then it switches`() {
        jdbc().update("UPDATE pipelines SET current_version = NULL WHERE id = ?::uuid", pipelineId)
        val switch = { post("/api/v1/pipelines/$pipelineId/current", """{"version": 1}""") }
        assertRestBusy(exhausts(switch), "pipeline.release.busy")
        pointer() shouldBe null

        val control = switch()
        withClue("the successful control: ${control.asString()}") { control.statusCode shouldBe 200 }
        pointer() shouldBe 1
    }

    private fun pointer(): Int? =
        jdbc().queryForObject("SELECT current_version FROM pipelines WHERE id = ?::uuid", Int::class.javaObjectType, pipelineId)

    @AfterAll
    fun shutdown() {
        pool.shutdownNow()
        oidc.close()
    }

    companion object {
        private const val TEMPLATE = "test/g0t/shared.sql"
        private const val PIPELINE = "test/g0t/rows"
        private const val VISUALIZATION = "test/g0t/chart"
        private const val DASHBOARD = "test/g0t/board"
        private const val PARAMETER_SET = "test/g0t/filters"
        private const val DATASOURCE = "h2-g0t-841"
        private const val H2_JDBC_URL = "jdbc:h2:mem:g0t841;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val WORKSPACE = "g0t-841"
        private val SUCCESS = setOf(200, 201)

        /** Each artifact table, its versions table and that table's foreign key — the tables this suite reads. */
        private val VERSION_TABLES =
            mapOf(
                "pipelines" to ("pipeline_versions" to "pipeline_id"),
                "visualizations" to ("visualization_versions" to "visualization_id"),
                "dashboards" to ("dashboard_versions" to "dashboard_id"),
                "parameter_sets" to ("parameter_set_versions" to "parameter_set_id"),
            )

        private val PIPELINE_ARGUMENTS =
            mapOf(
                "name" to PIPELINE,
                "display_name" to "G0 transfer writers",
                "description" to "One node pinning a template.",
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "n0",
                            "type" to "DQL",
                            "source" to DATASOURCE,
                            "description" to "One row",
                            "template" to mapOf("id" to TEMPLATE, "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            )
        private val PARAMETER_SET_ARGUMENTS =
            mapOf(
                "name" to PARAMETER_SET,
                "display_name" to "G0 filters",
                "parameters" to
                    listOf(
                        mapOf(
                            "name" to "min_amount",
                            "label" to "Minimum amount",
                            "type" to "DECIMAL",
                            "precision" to 12,
                            "scale" to 2,
                            "kind" to "INPUT",
                            "required" to false,
                            "default_value" to 0,
                        ),
                    ),
            )
        private val VISUALIZATION_DOCUMENT =
            """
            {"name": "$VISUALIZATION", "display_name": "G0 chart", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "tests": {"cases": [{"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                                  "assertions": [{"kind": "rendered"}]}]}}
            """.trimIndent()
        private val DASHBOARD_DOCUMENT =
            """
            {"name": "$DASHBOARD", "display_name": "G0 board",
             "sources": [{"name": "rows_source", "pipeline": {"name": "$PIPELINE", "version": 1}}],
             "visualizations": [{"name": "rows_chart", "type": "visualization", "visualization": {"name": "$VISUALIZATION", "version": 1},
                                 "inputs": {"revenue": {"source": "rows_source"}}}],
             "layout": {"grid": [{"name": "rows_chart", "x": 0, "y": 0, "w": 6, "h": 4}]}}
            """.trimIndent()

        private const val BUSY_MESSAGE = "Another change in this workspace is in progress. Nothing was written; try again in a moment."

        /** The production default of `datapipelines.release.dependencies.lock-wait-seconds`. */
        private const val DEFAULT_LOCK_WAIT_SECONDS = 5

        /** The probe gives up before the writer's own lock wait would, so a missing wait is RED, never a busy. */
        private const val WAIT_PROBE_SECONDS = 4L
        private const val PROBE_INTERVAL_MS = 20L
        private const val WRITER_TIMEOUT_SECONDS = 30L
        private val WAITERS_SQL =
            """
            WITH RECURSIVE waiting(pid) AS (
                SELECT a.pid FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid))
                UNION
                SELECT a.pid FROM pg_stat_activity a JOIN waiting w ON w.pid = ANY(pg_blocking_pids(a.pid))
            )
            SELECT count(*) FROM waiting
            """.trimIndent()

        private val WORKSPACE_ID: String = UUID.randomUUID().toString()
        private val ADMIN_ID: String = UUID.randomUUID().toString()
        private val KEY_IDENTITY: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val KEY = E2eAuth.generateKey("e2e-841-g0t-key")
        private val SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "e2e-841-g0t@datapipelines.test", WORKSPACE)

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
            registry.add("datapipelines.redis.password") { "" }
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
        fun seed() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'G0 transfer 841')",
                    )
                    // A super admin (the instance datasource) who is also this workspace's admin.
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES" +
                            " ('$ADMIN_ID', 'e2e-841-g0t@datapipelines.test', 'E2E 841 transfer', 'test', 'e2e-841-g0t-sub', TRUE, TRUE)",
                    )
                    statement.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES" +
                            " ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')",
                    )
                    statement.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                            "('$KEY_IDENTITY', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', '${KEY.id}'," +
                            " TRUE, FALSE, 'service')",
                    )
                }
                connection
                    .prepareStatement(
                        "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                            " VALUES (?, ?::uuid, ?::uuid, ?, ?, ?::uuid, 'mcp', 'workspace_admin')",
                    ).use { ps ->
                        ps.setString(1, KEY.id)
                        ps.setString(2, KEY_IDENTITY)
                        ps.setString(3, ADMIN_ID)
                        ps.setString(4, KEY.name)
                        ps.setString(5, KEY.hash)
                        ps.setString(6, WORKSPACE_ID)
                        ps.executeUpdate()
                    }
            }
        }
    }
}
