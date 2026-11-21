package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * #1035 — a family that shares one local write transaction publishes NOTHING until its one commit settles, and an
 * execution whose commit may have happened is never labelled as if it had not. Production seam end to end: a plain
 * `POST /api/v1/pipelines/{id}/execute` under the real root assembly, a real pooled Postgres as the customer database,
 * and a SECOND connection (and the REST cursor) as the second client — every assertion reads durable rows or the wire.
 *
 * The commit boundary is controlled with the database itself: a `DEFERRABLE INITIALLY DEFERRED` foreign key makes the
 * customer's COMMIT refuse, which is exactly "a commit that was attempted and not acknowledged" — the engine must
 * answer `OUTCOME_UNKNOWN`, never replay the business SQL (a non-transactional sequence counts every execution of it)
 * and never expose a result.
 */
@Suppress("LargeClass") // one production-seam contract over one shared fixture family; a split would duplicate the whole set
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PipelineCommitOutcomeIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()

    private val history by lazy { CommitHistoryReader(port, ADMIN_SESSION, MCP_KEY.plaintext) }

    // ---------------------------------------------------------------- 1: the confirmed commit leaves its evidence

    @Test
    fun `a committed family records the attempt then the confirmation and settles SUCCESS`() {
        prepare()
        val pipelineId =
            createPipeline(
                "test/co_commit_ok",
                "Commit ok",
                listOf(
                    mapOf(
                        "id" to "read_all",
                        "description" to "Reads rows back into the same datasource",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "template" to mapOf("id" to "test/co_read.sql", "version" to 1),
                        "output" to
                            mapOf("target" to "datasource", "datasource" to DATASOURCE, "table" to "co_tgt_child", "mode" to "append"),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
            )

        val events = stream(pipelineId)

        withClue(describe(events)) {
            events.names().filter { it != "node_progress" } shouldContainExactly
                listOf("execution_started", "node_started", "node_completed", "pipeline_completed")
        }
        val row = executionRow(executionIdOf(events))
        row["status"] shouldBe "SUCCESS"
        row["commit_attempted_at"].shouldNotBeNull()
        row["commit_confirmed_at"].shouldNotBeNull()
        row["result_unavailable"] shouldBe "false"
        row["settlement_pending"] shouldBe "false"
        scalar("SELECT count(*) FROM co_tgt_child") shouldBe "2500"
    }

    // ---------------------------------------------------------------- 2: held until the family settles

    @Test
    fun `a finished child is held RUNNING and its rows are invisible to a second client until the root commits`() {
        prepare()
        val pipelineId =
            createPipeline(
                "test/co_held",
                "Held until settled",
                listOf(
                    pipelineNodeToDatasource("ap", "test/co_child_append", "co_tgt_child", "append", emptyList()),
                    pipelineNodeToDatasource("slow", "test/co_child_slow", "co_slow_tgt", "append", listOf("ap")),
                ),
            )
        val observed = mutableMapOf<String, String?>()
        var heldHistoryFailure: Throwable? = null

        val events =
            stream(pipelineId) { rootId ->
                val child = awaitPendingChild(rootId)
                observed["child"] = child
                // The second client, while the family is still open: the customer's rows, the child's record and its cursor.
                observed["rows"] = scalar("SELECT count(*) FROM co_tgt_child")
                observed["childStatus"] = metaScalar("SELECT status FROM pipeline_executions WHERE execution_id = '$child'")
                observed["childCompletedAt"] =
                    metaScalar("SELECT completed_at::text FROM pipeline_executions WHERE execution_id = '$child'")
                val cursor = get("/api/v1/executions/$child/result")
                observed["cursorStatus"] = cursor.statusCode().toString()
                observed["cursorCode"] = cursor.jsonPath().getString("error.code")
                observed["rootStatus"] = metaScalar("SELECT status FROM pipeline_executions WHERE execution_id = '$rootId'")
                // R2: the held child's history, on every surface, claims nothing terminal while its root is open.
                // Captured, not thrown: the reader thread must keep draining the stream; the failure is rethrown below.
                heldHistoryFailure =
                    runCatching { history.assertNothingTerminal(child, "held child before the root commits") }.exceptionOrNull()
            }

        withClue("observed=$observed ${describe(events)}") {
            observed["rows"] shouldBe "0"
            observed["childStatus"] shouldBe "RUNNING"
            observed["childCompletedAt"].shouldBeNull()
            observed["cursorStatus"] shouldBe "409"
            observed["cursorCode"] shouldBe "result.execution_incomplete"
            observed["rootStatus"] shouldBe "RUNNING"
        }
        events.names() shouldNotContain "pipeline_failed"
        heldHistoryFailure?.let { throw it }
        val child = observed.getValue("child")
        // Settled together with the root: the child inherits SUCCESS and the rows are durable.
        executionRow(child!!)["status"] shouldBe "SUCCESS"
        executionRow(child)["settlement_pending"] shouldBe "false"
        executionRow(child)["completed_at"].shouldNotBeNull()
        scalar("SELECT count(*) FROM co_tgt_child") shouldBe "2500"
        executionRow(executionIdOf(events))["status"] shouldBe "SUCCESS"
        // R2: after the root's SUCCESS the child's history ends in its one pipeline_completed, identical on all three surfaces.
        history.assertSettledAs(child, "pipeline_completed", "SUCCESS", "child after a SUCCESS root")
    }

    // ---------------------------------------------------------------- 3: before the attempt, a rollback

    @Test
    fun `a failure before the commit attempt rolls back, stamps nothing, and settles the finished child FAILED`() {
        prepare()
        val pipelineId =
            createPipeline(
                "test/co_rollback",
                "Rollback before attempt",
                listOf(
                    pipelineNodeToDatasource("ap", "test/co_child_append", "co_tgt_child", "append", emptyList()),
                    mapOf(
                        "id" to "boom",
                        "description" to "Reads a table that does not exist",
                        "type" to "DQL",
                        "source" to DATASOURCE,
                        "template" to mapOf("id" to "test/co_boom.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to listOf("ap"),
                    ),
                ),
            )

        val events = stream(pipelineId)

        withClue(describe(events)) { events.names().last() shouldBe "pipeline_failed" }
        events
            .last()
            .second
            .path("status")
            .asText() shouldBe "FAILED"
        val rootId = executionIdOf(events)
        val root = executionRow(rootId)
        root["status"] shouldBe "FAILED"
        root["commit_attempted_at"].shouldBeNull()
        scalar("SELECT count(*) FROM co_tgt_child") shouldBe "0"
        val child = metaScalar("SELECT execution_id FROM pipeline_executions WHERE parent_execution_id = '$rootId'")!!
        val childRow = executionRow(child)
        childRow["status"] shouldBe "FAILED"
        childRow["settlement_pending"] shouldBe "false"
        childRow["completed_at"].shouldNotBeNull()
        // R2: the rolled-back child never claimed success anywhere — its history ends pipeline_failed with the root's status.
        history.assertSettledAs(child, "pipeline_failed", "FAILED", "child after a rolled-back root")
    }

    // ---------------------------------------------------------------- 4: inside the window, OUTCOME_UNKNOWN and no replay

    @Test
    fun `a refused commit ends OUTCOME_UNKNOWN with no result, no replay and the same execution on a duplicate key`() {
        prepare()
        val pipelineId = createCommitRefusedPipeline()
        val key = "co1035-" + UUID.randomUUID()
        val before = localWriteOutcomes()

        val events = stream(pipelineId, idempotencyKey = key)

        withClue(describe(events)) {
            events.names() shouldNotContain "data_ready"
            events.names() shouldNotContain "pipeline_completed"
            events.names().last() shouldBe "pipeline_failed"
        }
        val terminal = events.last().second
        terminal.path("status").asText() shouldBe "OUTCOME_UNKNOWN"
        terminal.path("error").path("code").asText() shouldBe "pipeline.execution.local_write_commit_failed"
        val executionId = executionIdOf(events)
        val row = executionRow(executionId)
        row["status"] shouldBe "OUTCOME_UNKNOWN"
        row["commit_attempted_at"].shouldNotBeNull()
        row["commit_confirmed_at"].shouldBeNull()
        row["completed_at"].shouldNotBeNull()
        // No replay: the business SQL took its sequence value exactly once, and the refused commit left no row.
        scalar("SELECT last_value || '/' || is_called FROM co_runs_seq") shouldBe "1/true"
        scalar("SELECT count(*) FROM co_deferred") shouldBe "0"
        val after = localWriteOutcomes()
        withClue("meters before=$before after=$after") {
            delta(before, after, "unknown") shouldBe 1.0
            delta(before, after, "committed") shouldBe 0.0
        }

        assertEverySurfaceReportsUnknown(pipelineId, executionId)

        // The same Idempotency-Key within the reservation TTL: the same execution, and the SQL is not run again.
        val duplicate = stream(pipelineId, idempotencyKey = key)
        executionIdOf(duplicate) shouldBe executionId
        scalar("SELECT last_value || '/' || is_called FROM co_runs_seq") shouldBe "1/true"
        metaScalar("SELECT count(*) FROM pipeline_executions WHERE pipeline_id = '$pipelineId'") shouldBe "1"
    }

    /** Two nodes: a DML whose deferred foreign key the COMMIT refuses, and a caller read that must never be exposed. */
    private fun createCommitRefusedPipeline(): String =
        createPipeline(
            "test/co_unknown",
            "Commit refused",
            listOf(
                mapOf(
                    "id" to "write_deferred",
                    "description" to "Writes a row whose deferred foreign key the COMMIT will refuse",
                    "type" to "DML",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to "test/co_deferred.sql", "version" to 1),
                    "operation" to "update",
                    "output" to mapOf("target" to "discard"),
                    "depends_on" to emptyList<String>(),
                ),
                mapOf(
                    "id" to "read_one",
                    "description" to "The caller result that must never be exposed",
                    "type" to "DQL",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to "test/co_read_one.sql", "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to emptyList<String>(),
                ),
            ),
        )

    /** The record, the history filter and the cursor all report the OUTCOME_UNKNOWN execution - none invents a result. */
    private fun assertEverySurfaceReportsUnknown(
        pipelineId: String,
        executionId: String,
    ) {
        val record = get("/api/v1/executions/$executionId")
        record.jsonPath().getString("data.status") shouldBe "OUTCOME_UNKNOWN"
        record.jsonPath().getString("data.error.code") shouldBe "pipeline.execution.local_write_commit_failed"
        val listed = get("/api/v1/executions?pipeline_id=$pipelineId&status=OUTCOME_UNKNOWN")
        listed.statusCode() shouldBe 200
        listed.jsonPath().getList<String>("data.items.execution_id") shouldContainExactly listOf(executionId)
        val cursor = get("/api/v1/executions/$executionId/result")
        cursor.statusCode() shouldBe 410
        cursor.jsonPath().getString("error.code") shouldBe "result.execution_failed"
        cursor.jsonPath().getString("error.details.status") shouldBe "OUTCOME_UNKNOWN"
    }

    @Test
    fun `a child of a family whose commit ends OUTCOME_UNKNOWN reads pipeline_failed OUTCOME_UNKNOWN and never success`() {
        prepare()
        val pipelineId =
            createPipeline(
                "test/co_unknown_family",
                "Commit refused with a child",
                listOf(
                    pipelineNodeToDatasource("ap", "test/co_child_append", "co_tgt_child", "append", emptyList()),
                    mapOf(
                        "id" to "write_deferred",
                        "description" to "Writes a row whose deferred foreign key the COMMIT will refuse",
                        "type" to "DML",
                        "source" to DATASOURCE,
                        "template" to mapOf("id" to "test/co_deferred.sql", "version" to 1),
                        "operation" to "update",
                        "output" to mapOf("target" to "discard"),
                        "depends_on" to listOf("ap"),
                    ),
                ),
            )

        val events = stream(pipelineId)

        withClue(describe(events)) { events.names().last() shouldBe "pipeline_failed" }
        events
            .last()
            .second
            .path("status")
            .asText() shouldBe "OUTCOME_UNKNOWN"
        val rootId = executionIdOf(events)
        val child = metaScalar("SELECT execution_id FROM pipeline_executions WHERE parent_execution_id = '$rootId'")!!
        executionRow(child)["status"] shouldBe "OUTCOME_UNKNOWN"
        executionRow(child)["settlement_pending"] shouldBe "false"
        history.assertSettledAs(child, "pipeline_failed", "OUTCOME_UNKNOWN", "child after an OUTCOME_UNKNOWN root")
    }

    // ---------------------------------------------------------------- 5: a dead instance, swept by another

    @Test
    fun `the stale sweep labels by the commit boundary and a pending child inherits its root`() {
        prepare()
        val seed = seedStaleExecutions()

        val swept = sweepOnce()

        // Three roots are swept; the pending child is settled WITH its root and is not a swept row of its own.
        swept shouldBe 3
        executionRow(seed.confirmed).let {
            it["status"] shouldBe "SUCCESS"
            it["result_unavailable"] shouldBe "true"
        }
        executionRow(seed.attempted).let {
            it["status"] shouldBe "OUTCOME_UNKNOWN"
            it["error_code"] shouldBe "pipeline.execution.local_write_commit_failed"
        }
        executionRow(seed.neverAttempted).let {
            it["status"] shouldBe "ABORTED"
            it["error_code"] shouldBe "pipeline.execution.instance_lost"
        }
        // The pending child of the unknown root inherits OUTCOME_UNKNOWN and its error — never ABORTED, never SUCCESS.
        executionRow(seed.pendingChild).let {
            it["status"] shouldBe "OUTCOME_UNKNOWN"
            it["settlement_pending"] shouldBe "false"
            it["error_code"] shouldBe "pipeline.execution.local_write_commit_failed"
        }
        sweepOnce() shouldBe 0
    }

    // ------------------------------------------------------------ fixtures

    private data class StaleSeed(
        val confirmed: String,
        val attempted: String,
        val neverAttempted: String,
        val pendingChild: String,
    )

    /** Four RUNNING rows an instance that died left behind, old enough for the age condition (no heartbeat stamp). */
    private fun seedStaleExecutions(): StaleSeed {
        val pipelineId = metaScalar("SELECT id FROM pipelines WHERE name = 'test/co_child_append'")!!
        val confirmed = UUID.randomUUID().toString()
        val attempted = UUID.randomUUID().toString()
        val never = UUID.randomUUID().toString()
        val pending = UUID.randomUUID().toString()
        val admin = ADMIN_USER_ID
        meta(
            """
            INSERT INTO pipeline_executions (execution_id, pipeline_id, pipeline_version, status, executed_by, triggered_via,
                                             root_execution_id, parent_execution_id, parent_node_id, started_at,
                                             commit_attempted_at, commit_confirmed_at, settlement_pending) VALUES
              ('$confirmed', '$pipelineId', 1, 'RUNNING', '$admin', 'REST', '$confirmed', NULL, NULL,
               NOW() - INTERVAL '3 hours', NOW() - INTERVAL '3 hours', NOW() - INTERVAL '3 hours', FALSE),
              ('$attempted', '$pipelineId', 1, 'RUNNING', '$admin', 'REST', '$attempted', NULL, NULL,
               NOW() - INTERVAL '3 hours', NOW() - INTERVAL '3 hours', NULL, FALSE),
              ('$never', '$pipelineId', 1, 'RUNNING', '$admin', 'REST', '$never', NULL, NULL,
               NOW() - INTERVAL '3 hours', NULL, NULL, FALSE),
              ('$pending', '$pipelineId', 1, 'RUNNING', '$admin', 'PIPELINE', '$attempted', '$attempted', 'child',
               NOW() - INTERVAL '3 hours', NULL, NULL, TRUE)
            """.trimIndent(),
        )
        return StaleSeed(confirmed, attempted, never, pending)
    }

    /** One tick of the PRODUCTION sweeper bean — the same code another instance runs after this one died. */
    private fun sweepOnce(): Int {
        val type = Class.forName("co.datapipelines.executor.StaleExecutionSweeper")
        val sweeper = context.getBean(type)
        return type.getMethod("sweepOnce").invoke(sweeper) as Int
    }

    /** Waits (bounded) for a finished-but-held child of [rootId]: its record is RUNNING with settlement pending. */
    private fun awaitPendingChild(rootId: String): String {
        val deadline = System.currentTimeMillis() + POLL_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            metaScalar(
                "SELECT execution_id FROM pipeline_executions WHERE root_execution_id = '$rootId' " +
                    "AND parent_execution_id IS NOT NULL AND settlement_pending",
            )?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("no held child ever appeared under root $rootId")
    }

    /** A row of `pipeline_executions` as text, plus its error code, read on a connection of this test's own. */
    private fun executionRow(id: String): Map<String, String?> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT status, commit_attempted_at::text, commit_confirmed_at::text, completed_at::text, " +
                            "settlement_pending::text, result_unavailable::text, error_json ->> 'code' " +
                            "FROM pipeline_executions WHERE execution_id = '$id'",
                    ).use { rows ->
                        check(rows.next()) { "no execution row $id" }
                        mapOf(
                            "status" to rows.getString(1),
                            "commit_attempted_at" to rows.getString(2),
                            "commit_confirmed_at" to rows.getString(3),
                            "completed_at" to rows.getString(4),
                            "settlement_pending" to rows.getString(5),
                            "result_unavailable" to rows.getString(6),
                            "error_code" to rows.getString(7),
                        )
                    }
            }
        }

    /** One value read on a connection of this test's own to the CUSTOMER database — the second client. */
    private fun scalar(sql: String): String? = scalarOn(db.jdbcUrl, db.username, db.password, sql)

    /** One value read from the metadata database (the execution records). */
    private fun metaScalar(sql: String): String? = scalarOn(postgres.jdbcUrl, postgres.username, postgres.password, sql)

    private fun scalarOn(
        url: String,
        user: String,
        password: String,
        sql: String,
    ): String? =
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.executeQuery(sql).use(::firstColumn) }
        }

    private fun firstColumn(rows: ResultSet): String? = if (rows.next()) rows.getString(1) else null

    private fun meta(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun get(path: String): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .get(path)
            .thenReturn()

    private fun localWriteOutcomes(): Map<String, Double> {
        val registry: Any = context.getBean(Class.forName("io.micrometer.core.instrument.MeterRegistry"))
        val find = registry.javaClass.getMethod("find", String::class.java).invoke(registry, METRIC_NAME)
        val counters = find.javaClass.getMethod("counters").invoke(find) as Collection<*>
        return counters.associate { counter ->
            val id = counter!!.javaClass.getMethod("getId").invoke(counter)
            val outcome = id.javaClass.getMethod("getTag", String::class.java).invoke(id, "outcome") as String
            outcome to counter.javaClass.getMethod("count").invoke(counter) as Double
        }
    }

    private fun delta(
        before: Map<String, Double>,
        after: Map<String, Double>,
        outcome: String,
    ): Double = (after[outcome] ?: 0.0) - (before[outcome] ?: 0.0)

    private fun List<Pair<String, JsonNode>>.names(): List<String> = map { it.first }

    private fun executionIdOf(events: List<Pair<String, JsonNode>>): String =
        events
            .first {
                it.first == "execution_started"
            }.second["execution_id"]
            .asText()

    private fun describe(events: List<Pair<String, JsonNode>>): String =
        events.joinToString(prefix = "[", postfix = "]") { (name, payload) ->
            val code = payload.path("error").path("code").asText("")
            val status = payload.path("status").asText("")
            listOf(name, status.takeIf { it.isNotEmpty() && name == "pipeline_failed" }, code.takeIf { it.isNotEmpty() })
                .filterNotNull()
                .joinToString(":")
        }

    // ------------------------------------------------------------ the customer database and the fixtures

    private fun prepare() {
        ensureAuthSeeded()
        registerDatasource()
        seedScratch()
        createTemplate("test/co_read.sql", "SELECT id FROM co_src ORDER BY id")
        createTemplate("test/co_read_one.sql", "SELECT id FROM co_src WHERE id = 1")
        createTemplate("test/co_read_slow.sql", "SELECT id FROM co_src WHERE (SELECT pg_sleep($SLEEP_SECONDS)) IS NULL ORDER BY id LIMIT 1")
        createTemplate("test/co_boom.sql", "SELECT id FROM co_does_not_exist")
        createTemplate("test/co_deferred.sql", "INSERT INTO co_deferred SELECT nextval('co_runs_seq')")
        createReleasedPipeline("test/co_child_append", "Child append", listOf(dqlCaller("read_all", "test/co_read.sql")))
        createReleasedPipeline("test/co_child_slow", "Child slow", listOf(dqlCaller("read_slow", "test/co_read_slow.sql")))
    }

    private fun seedScratch() {
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { connection ->
            connection.createStatement().use { statement ->
                for (table in listOf("co_src", "co_tgt_child", "co_slow_tgt")) {
                    statement.execute("CREATE TABLE IF NOT EXISTS $table (id INT PRIMARY KEY)")
                }
                statement.execute("CREATE TABLE IF NOT EXISTS co_parent_none_big (id BIGINT PRIMARY KEY)")
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS co_deferred (id BIGINT, CONSTRAINT co_deferred_fk FOREIGN KEY (id) " +
                        "REFERENCES co_parent_none_big(id) DEFERRABLE INITIALLY DEFERRED)",
                )
                statement.execute("CREATE SEQUENCE IF NOT EXISTS co_runs_seq")
                statement.execute("TRUNCATE co_src, co_tgt_child, co_slow_tgt, co_deferred")
                statement.execute("ALTER SEQUENCE co_runs_seq RESTART")
                statement.execute("INSERT INTO co_src SELECT generate_series(1, 2500)")
            }
        }
    }

    private fun registerDatasource() {
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
                {"name": "$DATASOURCE", "display_name": "Commit outcomes 1035", "dialect": "POSTGRES",
                 "jdbc_url": "${db.jdbcUrl}", "username": "${db.username}", "password": "${db.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun dqlCaller(
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
            "output" to mapOf("target" to "datasource", "datasource" to DATASOURCE, "table" to table, "mode" to mode),
            "depends_on" to dependsOn,
        )

    private fun ensureAuthSeeded() {
        meta(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
            VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', '$ADMIN_EMAIL', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO workspace_members (workspace_id, user_id, role)
            VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER_ID', 'workspace_admin')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
        // The MCP reader of the history surfaces: a workspace-admin key, so it reads executions another identity ran.
        meta(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind)
            VALUES ('$MCP_IDENTITY_ID', '${MCP_KEY.id.lowercase()}@keys.invalid', '${MCP_KEY.name}', 'key', '${MCP_KEY.id}', TRUE, FALSE, 'service')
            ON CONFLICT (id) DO NOTHING;
            INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)
            VALUES ('${MCP_KEY.id}', '$MCP_IDENTITY_ID', '$ADMIN_USER_ID', '${MCP_KEY.name}', '${MCP_KEY.hash}', '$DEFAULT_WORKSPACE', 'mcp', 'workspace_admin')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }

    private fun createTemplate(
        id: String,
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
                    {"id": "$id", "dialect": "POSTGRES", "display_name": "$id", "description": "Commit outcome template", "imports": [],
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
                    "description" to "Commit outcome pipeline — $displayName",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        val response =
            given()
                .port(
                    port,
                ).contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(bodyJson)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        if (response.statusCode() !=
            201
        ) {
            throw AssertionError("Pipeline '$name' creation failed (${response.statusCode()}): ${response.body().asString()}")
        }
        return response.jsonPath().getString("data.id")
    }

    private fun createReleasedPipeline(
        name: String,
        displayName: String,
        nodes: List<Map<String, Any?>>,
    ) {
        synchronized(created) {
            if (name in createdChildren) return
        }
        val id = createPipeline(name, displayName, nodes)
        val hash = get("/api/v1/pipelines/$id").jsonPath().getString("data.body_hash")
        given()
            .port(
                port,
            ).asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        synchronized(created) { createdChildren += name }
    }

    // ------------------------------------------------------------ stream

    /**
     * Runs a pipeline over the ordinary SSE POST and returns its events. [onStarted] runs ONCE, on the reader thread,
     * right after `execution_started` — while the server is still executing — so a second client can observe the open family.
     */
    private fun stream(
        pipelineId: String,
        idempotencyKey: String? = null,
        onStarted: ((String) -> Unit)? = null,
    ): List<Pair<String, JsonNode>> =
        assertTimeoutPreemptively(Duration.ofMinutes(SSE_BUDGET_MINUTES)) {
            val builder =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/api/v1/pipelines/$pipelineId/execute"))
                    .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                    .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                    .header("DP-Correlation-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
            idempotencyKey?.let { builder.header("Idempotency-Key", it) }
            val request = builder.POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}""")).build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.statusCode() shouldBe 200
            val events = mutableListOf<Pair<String, JsonNode>>()
            BufferedReader(InputStreamReader(response.body())).use { reader ->
                var currentEvent: String? = null
                var observed = false
                for (line in reader.lines()) {
                    when {
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }

                        line.startsWith("data:") -> {
                            events +=
                                (currentEvent ?: "unknown") to mapper.readTree(line.removePrefix("data:").trim())
                        }
                    }
                    if (!observed && onStarted != null && events.any { it.first == "execution_started" }) {
                        observed = true
                        onStarted(events.first { it.first == "execution_started" }.second["execution_id"].asText())
                    }
                }
            }
            events
        }

    companion object {
        private const val DATASOURCE = "co1035"
        private const val METRIC_NAME = "datapipelines.executions.local_write_transactions"
        private const val SSE_BUDGET_MINUTES = 2L
        private const val SLEEP_SECONDS = 3
        private const val POLL_BUDGET_MS = 30_000L
        private const val POLL_INTERVAL_MS = 100L
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val ADMIN_EMAIL = "e2e-co1035@datapipelines.test"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val MCP_KEY = E2eAuth.generateKey("co1035-mcp-reader")
        private val MCP_IDENTITY_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val postgres get() = SharedE2e.postgres

        /** Read and write in ONE customer database — what makes these families local-write families. */
        private val db = SharedE2e.scratchDatabase("commitoutcome1035")
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()
        private val created = Any()
        private val createdTemplates = mutableSetOf<String>()
        private val createdChildren = mutableSetOf<String>()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

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
