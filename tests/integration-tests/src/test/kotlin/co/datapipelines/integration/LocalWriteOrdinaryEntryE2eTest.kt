package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
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
import java.sql.ResultSet
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * #1033 r5 — the local write transaction reached through the ORDINARY entry point: a plain
 * `POST /api/v1/pipelines/{id}/execute` under the production root assembly (web wiring, real
 * planner, real SubPipelineExecutionRunner, real DirectResultSink, real pooled Postgres).
 *
 * Everything here is production seam: no dag-module harness, no fake registry, no injected
 * emitter — the assertions read the durable rows (out-of-band JDBC), the streamed events and
 * the `local_write_transactions` meters off the running application.
 *
 * The families deliberately reuse ONE datasource (`lw1033`) for read and write so the planner
 * classifies them as local-write families: a write-back-only stored version (enrollment), a
 * child APPEND above one drain batch through the production child sink, a child REPLACE, a
 * later required failure that must roll the whole family back onto pre-existing rows, a
 * mid-run workspace-grant withdrawal (`datasources.is_readonly = TRUE` out of band) that must
 * refuse the write-back and roll the family back, and (R8) the ACTUAL visibility-grant
 * revocation (`datasource_workspaces` deleted through the product's own grant surface) that
 * must refuse the write-back as `datasource_not_found` and roll the family back.
 */
@Suppress("LargeClass") // one production-seam contract over one shared fixture family; a split would duplicate the whole set
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class LocalWriteOrdinaryEntryE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()

    // ---------------------------------------------------------------- Test 1: ordinary-POST enrollment

    @Test
    fun `a stored write-back-only version enrolls one local write transaction through the ordinary POST`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()

        val pipelineId =
            createPipeline(
                "test/lw_wb_only",
                "LocalWrite Write-Back Only",
                listOf(
                    mapOf(
                        "id" to "read_all",
                        "description" to "Reads 2500 rows back into the same datasource",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "template" to mapOf("id" to "test/lw_read.sql", "version" to 1),
                        "output" to
                            mapOf(
                                "target" to "datasource",
                                "datasource" to DATASOURCE,
                                "table" to "lw_tgt_append",
                                "mode" to "append",
                            ),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
            )

        val before = localWriteOutcomes()
        val events = consumeExecutionStream(pipelineId, ADMIN_SESSION, UUID.randomUUID().toString())

        withClue(describe(events)) {
            events.lifecycleNames() shouldContainExactly
                listOf("execution_started", "node_started", "node_completed", "pipeline_completed")
        }
        scratchCount("lw_tgt_append") shouldBe 2500L

        // Enrollment, judged on the meters the production executor records: exactly one
        // started and one committed local write transaction for this execution.
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "started") shouldBe 1.0
            delta(before, after, "committed") shouldBe 1.0
            delta(before, after, "rolled_back") shouldBe 0.0
        }
    }

    // ---------------------------------------------------------------- Test 2: production child sink, APPEND > one batch

    @Test
    fun `a child APPEND above one drain batch lands through the production sink in the family transaction`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createReadChildren()

        val pipelineId =
            createPipeline(
                "test/lw_parent_append",
                "LocalWrite Parent Append",
                listOf(
                    dmlNode("mark", "test/lw_mark1.sql"),
                    pipelineNodeToDatasource("run_child", "test/lw_child_append", "lw_tgt_child", "append", dependsOn = listOf("mark")),
                ),
            )

        val before = localWriteOutcomes()
        val events = consumeExecutionStream(pipelineId, ADMIN_SESSION, UUID.randomUUID().toString())

        withClue(describe(events)) { events.lifecycleNames().last() shouldBe "pipeline_completed" }
        // BOTH durable writes — the parent's marker and the child's 2500 rows (three drain
        // batches of 1000) — landed, and in ONE local write transaction for the whole family.
        scratchCount("lw_family") shouldBe 1L
        scratchCount("lw_tgt_child") shouldBe 2500L
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "started") shouldBe 1.0
            delta(before, after, "committed") shouldBe 1.0
        }
    }

    // ---------------------------------------------------------------- Test 3: child REPLACE through the production sink

    @Test
    fun `a child REPLACE swaps the target rows through the production sink`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createReadChildren()

        val pipelineId =
            createPipeline(
                "test/lw_parent_repl",
                "LocalWrite Parent Replace",
                listOf(
                    pipelineNodeToDatasource("run_child", "test/lw_child_repl", "lw_replace_tgt", "replace", dependsOn = emptyList()),
                ),
            )

        val before = localWriteOutcomes()
        val events = consumeExecutionStream(pipelineId, ADMIN_SESSION, UUID.randomUUID().toString())

        withClue(describe(events)) { events.lifecycleNames().last() shouldBe "pipeline_completed" }
        scratchIds("lw_replace_tgt") shouldBe listOf(1)
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "started") shouldBe 1.0
            delta(before, after, "committed") shouldBe 1.0
        }
    }

    // ---------------------------------------------------------------- Test 4: later required failure rolls the family back

    @Test
    fun `a later required failure rolls the family back and preserves the pre-existing replacement rows`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createReadChildren()
        createTemplate("test/lw_boom.sql", "POSTGRES", "LocalWrite Boom", "INSERT INTO lw_no_such_table VALUES (1)")

        val pipelineId =
            createPipeline(
                "test/lw_parent_fail",
                "LocalWrite Parent Failure",
                listOf(
                    dmlNode("mark", "test/lw_mark2.sql"),
                    pipelineNodeToDatasource("run_child", "test/lw_child_repl2", "lw_replace2_tgt", "replace", dependsOn = listOf("mark")),
                    dmlNode("boom", "test/lw_boom.sql", dependsOn = listOf("run_child")),
                ),
            )

        val before = localWriteOutcomes()
        val events = consumeExecutionStream(pipelineId, ADMIN_SESSION, UUID.randomUUID().toString())

        withClue(describe(events)) {
            events.lifecycleNames().last() shouldBe "pipeline_failed"
            events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.query_execution_failed"
        }
        // The child's REPLACE deleted (90, 91) and inserted (1) INSIDE the family transaction;
        // the later required DML failure rolled the whole family back onto the pre-existing rows.
        scratchIds("lw_replace2_tgt") shouldBe listOf(90, 91)
        scratchCount("lw_family2") shouldBe 0L
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "started") shouldBe 1.0
            delta(before, after, "rolled_back") shouldBe 1.0
            delta(before, after, "committed") shouldBe 0.0
        }
    }

    // ---------------------------------------------------------------- Test 5: mid-run grant withdrawal

    @Test
    fun `withdrawing write access mid-run refuses the write-back and rolls the family back`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createReadChildren()

        val pipelineId =
            createPipeline(
                "test/lw_parent_slow",
                "LocalWrite Parent Slow",
                listOf(
                    dmlNode("mark", "test/lw_mark3.sql"),
                    pipelineNodeToDatasource("run_child", "test/lw_child_slow", "lw_slow_tgt", "append", dependsOn = listOf("mark")),
                ),
            )

        val before = localWriteOutcomes()
        try {
            val events = withdrawThenDrain(pipelineId)

            withClue(describe(events)) {
                events.last().first shouldBe "pipeline_failed"
                // The refusal lands INSIDE the child's streaming sink (DirectResultSink →
                // JdbcWritebackRunner refuses a read-only write target), so the root carries
                // the composition wrapper with the child's own refusal code in `details` —
                // the same shape every wrapped child failure carries (composition §“refused”).
                events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.child_execution_failed"
                events.last().second["error"]["details"]["child_error_code"].asText("") shouldBe
                    "pipeline.node.datasource_readonly"
            }
            scratchCount("lw_slow_tgt") shouldBe 0L
            scratchCount("lw_family3") shouldBe 0L
            val after = localWriteOutcomes()
            withClue("meters before=$before after=$after") {
                delta(before, after, "started") shouldBe 1.0
                delta(before, after, "rolled_back") shouldBe 1.0
            }
        } finally {
            // The grant is this suite's own row, but restore it whatever this test's outcome —
            // the metadata database is shared by every E2E context on the container.
            setDatasourceReadonly(false)
        }
    }

    /**
     * Starts the execution on a stream read INCREMENTALLY: `execution_started` yields the root
     * id, then — while the child's `pg_sleep(3)` query still holds the family in flight — the
     * write grant is withdrawn out of band (`datasources.is_readonly = TRUE`, committed). The
     * rest is drained to the terminal event the refusal produces.
     */
    private fun withdrawThenDrain(pipelineId: String): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.statusCode() shouldBe 200

            val events = mutableListOf<Pair<String, JsonNode>>()
            BufferedReader(InputStreamReader(response.body())).use { reader ->
                var currentEvent: String? = null
                var rootId: String? = null
                var withdrawn = false
                for (line in reader.lines()) {
                    when {
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }

                        line.startsWith("data:") -> {
                            val payload = mapper.readTree(line.removePrefix("data:").trim())
                            events += (currentEvent ?: "unknown") to payload
                        }
                    }
                    if (rootId == null && events.any { it.first == "execution_started" }) {
                        rootId = events.first { it.first == "execution_started" }.second["execution_id"].asText()
                        awaitChildExecution(rootId!!)
                    }
                    // Withdraw exactly once, while the child's 3s sleep still holds the family
                    // in flight — the sink's refusal runs only after that sleep ends.
                    if (rootId != null && !withdrawn && childExecutionPresent(rootId)) {
                        setDatasourceReadonly(true)
                        withdrawn = true
                    }
                }
                withClue("grant was never withdrawn (rootId=$rootId): ${describe(events)}") {
                    withdrawn shouldBe true
                }
            }
            events
        }

    /** Polls the metadata DB until the child execution row exists (parent DML done, child started). */
    private fun awaitChildExecution(rootId: String) {
        val deadline = System.currentTimeMillis() + CHILD_POLL_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (childExecutionPresent(rootId)) return
            Thread.sleep(100)
        }
        throw AssertionError("the child execution row never appeared under root $rootId")
    }

    private fun childExecutionPresent(rootId: String): Boolean =
        DriverManager
            .getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { connection ->
                connection
                    .prepareStatement(
                        "SELECT count(*) FROM pipeline_executions WHERE parent_execution_id = ?::uuid AND triggered_via = 'PIPELINE'",
                    ).use { statement ->
                        statement.setString(1, rootId)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getLong(1) > 0
                        }
                    }
            }

    // ---------------------------------------------------------------- Test 6: mid-run grant revocation

    @Test
    fun `withdrawing the workspace grant mid-run refuses the write-back and rolls the family back`() {
        ensureAuthSeeded()
        registerLocalWriteDatasource()
        seedScratch()
        createReadChildren()

        val pipelineId =
            createPipeline(
                "test/lw_parent_grant",
                "LocalWrite Parent Grant",
                listOf(
                    dmlNode("mark", "test/lw_mark3.sql"),
                    pipelineNodeToDatasource("run_child", "test/lw_child_slow", "lw_grant_tgt", "append", dependsOn = listOf("mark")),
                ),
            )

        val before = localWriteOutcomes()
        try {
            val events = revokeGrantThenDrain(pipelineId)

            withClue(describe(events)) {
                events.last().first shouldBe "pipeline_failed"
                // The refusal lands inside the child's streaming sink (DirectResultSink →
                // JdbcWritebackRunner resolves the write-back target VISIBILITY live): the grant
                // is gone, so the target is not-found — the visibility-grant refusal, not the
                // readonly flag (Test 5) and not a soft delete.
                events.last().second["error"]["code"].asText("") shouldBe "pipeline.node.child_execution_failed"
                events.last().second["error"]["details"]["child_error_code"].asText("") shouldBe
                    "pipeline.node.datasource_not_found"
            }
            scratchCount("lw_grant_tgt") shouldBe 0L
            scratchCount("lw_family3") shouldBe 0L
            val after = localWriteOutcomes()
            withClue("meters before=$before after=$after") {
                delta(before, after, "started") shouldBe 1.0
                delta(before, after, "rolled_back") shouldBe 1.0
            }
        } finally {
            // The grant is this suite's own row on the shared metadata DB: restore it whatever
            // this test's outcome, or every later datasource read in the context sees nothing.
            regrant()
        }
    }

    /**
     * Starts the execution on a stream read INCREMENTALLY: `execution_started` yields the root
     * id, then — while the child's `pg_sleep(3)` query still holds the family in flight — the
     * workspace's visibility grant is revoked through the product's own grant surface
     * (`DELETE /datasources/{name}/grants/{workspace}`, super admin). The rest is drained to the
     * terminal event the refusal produces.
     */
    private fun revokeGrantThenDrain(pipelineId: String): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                    .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.statusCode() shouldBe 200

            val events = mutableListOf<Pair<String, JsonNode>>()
            BufferedReader(InputStreamReader(response.body())).use { reader ->
                var currentEvent: String? = null
                var rootId: String? = null
                var revoked = false
                for (line in reader.lines()) {
                    when {
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }

                        line.startsWith("data:") -> {
                            val payload = mapper.readTree(line.removePrefix("data:").trim())
                            events += (currentEvent ?: "unknown") to payload
                        }
                    }
                    if (rootId == null && events.any { it.first == "execution_started" }) {
                        rootId = events.first { it.first == "execution_started" }.second["execution_id"].asText()
                        awaitChildExecution(rootId!!)
                    }
                    // Revoke exactly once, while the child's 3s sleep still holds the family in
                    // flight — the sink's refusal runs only after that sleep ends.
                    if (rootId != null && !revoked && childExecutionPresent(rootId)) {
                        revokeGrant()
                        revoked = true
                    }
                }
                withClue("grant was never revoked (rootId=$rootId): ${describe(events)}") {
                    revoked shouldBe true
                }
            }
            events
        }

    /** The product's own grant revocation surface (super admin): removes the visibility grant. */
    private fun revokeGrant() {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/datasources/$DATASOURCE/grants/$WORKSPACE_NAME")
            .then()
            .statusCode(204)
    }

    /** Restores the grant the test revoked — the same product surface, idempotent. */
    private fun regrant() {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .post("/api/v1/datasources/$DATASOURCE/grants/$WORKSPACE_NAME")
            .then()
            .statusCode(200)
    }

    /** The out-of-band grant channel: [DefaultDatasourceRegistry.isReadonlyLive] reads this row live. */
    private fun setDatasourceReadonly(readonly: Boolean) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("UPDATE datasources SET is_readonly = $readonly WHERE name = '$DATASOURCE' AND is_deleted = FALSE")
            }
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
                {"name": "$DATASOURCE", "display_name": "LocalWrite 1033", "dialect": "POSTGRES",
                 "jdbc_url": "${db.jdbcUrl}", "username": "${db.username}", "password": "${db.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    /** The suite's own schema on its scratch database; TRUNCATEd so each test starts clean. */
    private fun seedScratch() {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                for (table in SCRATCH_TABLES) {
                    statement.execute("CREATE TABLE IF NOT EXISTS $table (id INT PRIMARY KEY)")
                }
                statement.execute("TRUNCATE " + SCRATCH_TABLES.joinToString(", "))
                statement.execute("INSERT INTO lw_src SELECT generate_series(1, 2500)")
                statement.execute("INSERT INTO lw_replace_tgt VALUES (90), (91)")
                statement.execute("INSERT INTO lw_replace2_tgt VALUES (90), (91)")
            }
        }
    }

    /** The child templates + the released children a parent's PIPELINE node pins (D58: released only). */
    private fun createReadChildren() {
        createTemplate("test/lw_read.sql", "POSTGRES", "LocalWrite Read All", "SELECT id FROM lw_src ORDER BY id")
        createTemplate("test/lw_read_one.sql", "POSTGRES", "LocalWrite Read One", "SELECT id FROM lw_src WHERE id = 1")
        // The scalar-subquery sleep runs ONCE (a bare pg_sleep() in WHERE would run per row);
        // one row, after a 3s window that holds the family in flight for the withdrawal test.
        createTemplate(
            "test/lw_read_slow.sql",
            "POSTGRES",
            "LocalWrite Read Slow",
            "SELECT id FROM lw_src WHERE (SELECT pg_sleep($SLEEP_SECONDS)) IS NULL ORDER BY id LIMIT 1",
        )
        createTemplate("test/lw_mark1.sql", "POSTGRES", "LocalWrite Mark 1", "INSERT INTO lw_family VALUES (1)")
        createTemplate("test/lw_mark2.sql", "POSTGRES", "LocalWrite Mark 2", "INSERT INTO lw_family2 VALUES (1)")
        createTemplate("test/lw_mark3.sql", "POSTGRES", "LocalWrite Mark 3", "INSERT INTO lw_family3 VALUES (1)")

        createReleasedPipeline(
            "test/lw_child_append",
            "LocalWrite Child Append",
            listOf(dqlCallerNode("read_all", "test/lw_read.sql")),
        )
        createReleasedPipeline(
            "test/lw_child_repl",
            "LocalWrite Child Replace",
            listOf(dqlCallerNode("read_one", "test/lw_read_one.sql")),
        )
        createReleasedPipeline(
            "test/lw_child_repl2",
            "LocalWrite Child Replace 2",
            listOf(dqlCallerNode("read_one2", "test/lw_read_one.sql")),
        )
        createReleasedPipeline(
            "test/lw_child_slow",
            "LocalWrite Child Slow",
            listOf(dqlCallerNode("read_slow", "test/lw_read_slow.sql")),
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
        dependsOn: List<String> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Durable write directly on the family datasource",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to templateId, "version" to 1),
            "operation" to "update",
            "output" to mapOf("target" to "discard"),
            "depends_on" to dependsOn,
        )

    /** A PIPELINE node whose child rows land on [table] of [DATASOURCE] — the production child sink. */
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
        // Several tests compose the same fixture set; create+release each template exactly once
        // per context (the GET-based idempotency other suites use needs a route this suite
        // does not otherwise touch — a name memo is enough inside one context).
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
                     "description": "LocalWrite E2E template", "imports": [],
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

    private fun postPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): Response {
        val bodyJson =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to displayName,
                    "description" to "LocalWrite E2E pipeline — $displayName",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        return given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(bodyJson)
            .`when`()
            .post("/api/v1/pipelines")
            .thenReturn()
    }

    private fun createPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val response = postPipeline(name, displayName, nodes)
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

    /** The CURRENT body hash of a stored pipeline (a draft's `data.body_hash` from its own GET). */
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

    private fun scratchCount(table: String): Long = scratchIds(table).size.toLong()

    /** The target's ids, in order, read out-of-band on a connection of this test's own. */
    private fun scratchIds(table: String): List<Int> {
        val ids = mutableListOf<Int>()
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                readIds(statement.executeQuery("SELECT id FROM $table ORDER BY id"), ids)
            }
        }
        return ids
    }

    private fun readIds(
        rows: ResultSet,
        into: MutableList<Int>,
    ) {
        rows.use { r ->
            while (r.next()) into += r.getInt(1)
        }
    }

    /**
     * The `local_write_transactions` outcome counters, read off the running application's
     * MeterRegistry. Micrometer is not on this module's compile classpath (deliberately —
     * integration-tests binds only the app's own API), so the registry travels as `Any` and
     * its counters are read reflectively: the numbers asserted are still the ones the
     * production executor recorded.
     */
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

    /** Each event's name, with a failure event's error code: what a red must show beside its cause. */
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
        private const val DATASOURCE = "lw1033"
        private const val METRIC_NAME = "datapipelines.executions.local_write_transactions"
        private const val SSE_BUDGET_MINUTES = 2L

        /** The child's in-flight window: pg_sleep seconds, and the budget to see its execution row. */
        private const val SLEEP_SECONDS = 3
        private const val CHILD_POLL_BUDGET_MS = 30_000L

        /** The suite's scratch tables, created and truncated together by [seedScratch]. */
        private val SCRATCH_TABLES =
            listOf(
                "lw_src",
                "lw_tgt_append",
                "lw_tgt_child",
                "lw_replace_tgt",
                "lw_replace2_tgt",
                "lw_slow_tgt",
                "lw_grant_tgt",
                "lw_family",
                "lw_family2",
                "lw_family3",
            )

        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val WORKSPACE_NAME = "default"

        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "e2e-lw1033@datapipelines.test"
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)

        private val JWT_SECRET = E2eSession.newSecret()

        private val postgres get() = SharedE2e.postgres

        /** Read and write in ONE database — what makes these families local-write families. */
        private val db = SharedE2e.scratchDatabase("localwrite1033")

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
