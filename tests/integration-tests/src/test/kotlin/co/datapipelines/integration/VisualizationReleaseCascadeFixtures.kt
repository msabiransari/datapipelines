package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.config.HttpClientConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.http.ContentType
import io.restassured.response.Response
import java.sql.DriverManager
import java.util.UUID

/** Each fixture owns one workspace; JDBC assertions use new auto-commit connections after HTTP completes. */
internal class VisualizationReleaseCascadeFixtures(
    private val port: Int,
    secret: String,
) : AutoCloseable {
    val workspaceId = UUID.randomUUID().toString()
    private val userId = UUID.randomUUID().toString()
    private val workspace = "cascade453" + UUID.randomUUID().toString().replace("-", "")
    val template = "$workspace/shape.jsonata"
    private val session = E2eSession.jwt(secret, userId, "$workspace@e2e.test", workspace)
    lateinit var id: String
        private set
    lateinit var staleHash: String
        private set
    lateinit var hash: String
        private set

    init {
        execute("INSERT INTO workspaces (id, name, display_name) VALUES (?::uuid, ?, ?)", workspaceId, workspace, workspace)
        execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                "VALUES (?::uuid, ?, ?, 'test', ?, TRUE, FALSE)",
            userId,
            "$workspace@e2e.test",
            workspace,
            workspace,
        )
        execute(
            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (?::uuid, ?::uuid, 'workspace_admin')",
            workspaceId,
            userId,
        )
    }

    fun prepare() {
        val createdTemplate = request("POST", "/api/v1/templates", templateDocument())
        checkStatus(createdTemplate, 201)
        val created = request("POST", "/api/v1/visualizations", document("A"))
        checkStatus(created, 201)
        id = created.jsonPath().getString("data.id")
        staleHash = created.jsonPath().getString("data.body_hash")
        val edited = request("PUT", "/api/v1/visualizations/$id", document("B"), staleHash)
        checkStatus(edited, 200)
        hash = edited.jsonPath().getString("data.body_hash")
        (hash == staleHash) shouldBe false
        val started = request("POST", "/api/v1/visualizations/$id/tests/sessions", "")
        checkStatus(started, 201)
        started.jsonPath().getString("data.body_hash") shouldBe hash
        val sid = started.jsonPath().getString("data.session_id")
        val completed = request("POST", "/api/v1/visualizations/$id/tests/sessions/$sid/results", RESULTS)
        checkStatus(completed, 200)
        completed.jsonPath().getString("data.status") shouldBe "GREEN"
        completed.jsonPath().getBoolean("data.mechanical.ok") shouldBe true
        // The worklist really contains this eligible DRAFT pin; GREEN is persisted for B, not A.
        scalar(
            "SELECT count(*)::text FROM visualization_test_runs WHERE visualization_id = ?::uuid AND body_hash = ? AND status = 'GREEN'",
            id,
            hash,
        ) shouldBe
            "1"
    }

    fun release(
        expected: String,
        consent: Boolean,
    ): Response = request("POST", "/api/v1/visualizations/$id/release?release_pinned_templates=$consent", "", expected)

    fun templateState(): List<String?> = state("templates", "template_versions", "template_id", template, workspaceId)

    fun visualizationState(): List<String?> =
        state("visualizations", "visualization_versions", "visualization_id", "$workspace/chart", workspaceId)

    private fun state(
        table: String,
        versions: String,
        foreignKey: String,
        name: String,
        ws: String,
    ): List<String?> =
        connection().use { c ->
            c
                .prepareStatement(
                    "SELECT v.status, v.body_hash, a.current_version::text, v.version::text FROM $table a " +
                        "JOIN $versions v ON v.$foreignKey = a.id WHERE a.name = ? AND a.workspace_id = ?::uuid ORDER BY v.version",
                ).use { s ->
                    s.setString(1, name)
                    s.setString(2, ws)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "owned artifact missing" }
                        val result = (1..4).map { rows.getString(it) }
                        rows.next() shouldBe false
                        result
                    }
                }
        }

    private fun request(
        method: String,
        path: String,
        body: String,
        hash: String? = null,
    ): Response =
        given()
            .baseUri("http://127.0.0.1")
            .port(port)
            .config(
                RestAssuredConfig.config().httpClient(
                    HttpClientConfig
                        .httpClientConfig()
                        .setParam("http.connection.timeout", HTTP_TIMEOUT_MS)
                        .setParam("http.socket.timeout", HTTP_TIMEOUT_MS),
                ),
            ).asSession(session)
            .contentType(ContentType.JSON)
            .apply { hash?.let { header("If-Match", it) } }
            .body(body)
            .request(method, path)

    private fun execute(
        sql: String,
        vararg values: String,
    ) {
        connection().use { c ->
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setString(index + 1, value) }
                s.executeUpdate()
            }
        }
    }

    private fun scalar(
        sql: String,
        vararg values: String,
    ): String =
        connection().use { c ->
            c.prepareStatement(sql).use { s ->
                values.forEachIndexed { index, value -> s.setString(index + 1, value) }
                s.executeQuery().use { r ->
                    check(r.next())
                    r.getString(1)
                }
            }
        }

    override fun close() {
        execute("DELETE FROM visualizations WHERE workspace_id = ?::uuid", workspaceId)
        execute("DELETE FROM templates WHERE workspace_id = ?::uuid", workspaceId)
        execute("DELETE FROM workspace_members WHERE workspace_id = ?::uuid", workspaceId)
        execute("DELETE FROM audit_log WHERE user_id = ?::uuid", userId)
        execute("DELETE FROM workspaces WHERE id = ?::uuid", workspaceId)
        execute("DELETE FROM users WHERE id = ?::uuid", userId)
    }

    private fun document(title: String): String =
        """
        {"name":"$workspace/chart","display_name":"Cascade","description":"453",
         "renderer":{"kind":"plotly","version":"4"},
         "inputs":{"revenue":{"columns":[{"name":"month","type":"DATE","nullable":false},
                                            {"name":"amount","type":"DECIMAL","nullable":false}]}},
         "transform":{"template":{"name":"$template","version":1},"inputs":{"rows":"revenue"}},
         "config":{"data":[{"type":"bar","x":[],"y":[]}]},
         "bindings":{"data[0].x":"month_labels","data[0].y":"amounts"},
         "presentation":{"title":"$title"},
         "tests":{"cases":[{"name":"one month","fixtures":{"revenue":[{"month":"2026-01-01","amount":10.5}]},
                              "assertions":[{"kind":"rendered"},{"kind":"trace_count","equals":1}]}]}}
        """.trimIndent()

    private fun templateDocument(): String =
        """
        {"id":"$template","type":"jsonata","display_name":"Shape","description":"453",
         "body":"[ rows.{\"month_labels\": month, \"amounts\": amount} ]",
         "contract":{"mode":"row","inputs":{"rows":{"kind":"table","columns":[
                        {"name":"month","type":"DATE"},{"name":"amount","type":"DECIMAL","precision":12,"scale":2}]}},
                     "output":{"kind":"table","columns":[{"name":"month_labels","type":"STRING"},
                        {"name":"amounts","type":"DECIMAL","precision":12,"scale":2}]}},
         "invariants":[],"tests":[{"name":"empty input","input":{"rows":[],"inputs":{}},"expect":{"output":[]}}]}
        """.trimIndent()

    companion object {
        private const val HTTP_TIMEOUT_MS = 90_000
        private const val RESULTS = """{"cases":[{"name":"one month","verdict":"green"}],"environment":{"browser":"e2e","theme":"dark"}}"""

        fun checkStatus(
            response: Response,
            expected: Int,
        ) {
            withClue("HTTP expected=$expected actual=${response.statusCode}") { response.statusCode shouldBe expected }
        }

        private fun connection() =
            DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)
    }
}
