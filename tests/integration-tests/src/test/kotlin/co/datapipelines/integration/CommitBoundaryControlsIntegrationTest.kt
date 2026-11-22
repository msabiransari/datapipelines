package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #1035 R4 — the stop paths of a family that shares one local write transaction, on the production seam: the execution
 * DEADLINE and a CANCELLATION landing before the commit attempt, inside the ambiguity window and after the confirmation,
 * and the real SCHEDULER running a family to a confirmed and to an unknown outcome.
 *
 * Every hold is the database's own: a deferred foreign key whose parent row this test has locked makes the customer COMMIT
 * wait (the window); an advisory-lock trigger on the metadata terminal write holds the instant after the confirmation. The
 * record, the customer rows, the live stream and every history surface are then read independently.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass") // one production-seam contract over one fixture family; a split would boot the application per piece
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class CommitBoundaryControlsIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val history by lazy { CommitHistoryReader(port, ADMIN_SESSION, MCP_KEY.plaintext) }
    private val api by lazy { CommitScenarioApi(port, ADMIN_SESSION, DATASOURCE) }
    private val pipelines by lazy { prepare() }

    // ---------------------------------------------------------------- the deadline

    @Test
    fun `the execution deadline inside the commit window ends the family OUTCOME_UNKNOWN and frees the engine`() {
        ParentHold(1).use { hold ->
            val run = LiveRun(pipelines.getValue("deadline"))
            val events = run.events(FAMILY_BUDGET_SECONDS)

            events.last().first shouldBe "pipeline_failed"
            events
                .last()
                .second
                .path("status")
                .asText() shouldBe "OUTCOME_UNKNOWN"
            val rootId = run.id
            val child = metaScalar("SELECT execution_id FROM pipeline_executions WHERE parent_execution_id = '$rootId'")!!
            executionRow(rootId).let {
                it["status"] shouldBe "OUTCOME_UNKNOWN"
                it["commit_attempted_at"].shouldNotBeNull()
                it["commit_confirmed_at"].shouldBeNull()
            }
            executionRow(child)["status"] shouldBe "OUTCOME_UNKNOWN"
            executionRow(child)["settlement_pending"] shouldBe "false"
            history.assertSettledAs(child, "pipeline_failed", "OUTCOME_UNKNOWN", "deadline child")
            history.assertRecordOnEverySurface(rootId, "OUTCOME_UNKNOWN", false, "deadline root")
            history.assertRecordOnEverySurface(child, "OUTCOME_UNKNOWN", false, "deadline child")
            withClue("result cursor ${fetchResult(rootId)}") { fetchResult(rootId).path("error").isMissingNode shouldBe false }

            // The customer database decides afterwards, and atomically: all of it or none of it, never part.
            hold.release()
            await("the customer database settled the abandoned COMMIT") { waiters(hold.pid) == 0 }
            count("cc_t1_tgt").let { (it == 0 || it == ROWS) shouldBe true }
            executionRow(rootId)["status"] shouldBe "OUTCOME_UNKNOWN"

            // The abandoned commit did not wedge the engine: the next family commits.
            val next = LiveRun(pipelines.getValue("ok")).events(FAMILY_BUDGET_SECONDS)
            next.last().first shouldBe "pipeline_completed"
            count("cc_ok_tgt") shouldBe ROWS
        }
    }

    // ---------------------------------------------------------------- cancellation

    /**
     * The commit runs under NonCancellable: a cancellation that lands while the customer database has not answered cannot
     * interrupt it and cannot turn it into an abort — the family is neither rolled back nor reported ABORTED, because the
     * writes may have landed. Only the commit's own answer or the execution budget decides.
     */
    @Test
    fun `a cancellation inside the commit window cannot abort it - the budget ends it OUTCOME_UNKNOWN`() {
        ParentHold(2).use { hold ->
            val run = LiveRun(pipelines.getValue("cancel_window"))
            val rootId = run.id
            await("commit attempted and waiting on the customer database") {
                executionRow(rootId)["commit_attempted_at"] != null && waiters(hold.pid) > 0
            }
            cancel(rootId).statusCode shouldBe HTTP_NO_CONTENT

            val events = run.events(FAMILY_BUDGET_SECONDS)
            events.map { it.first } shouldNotContain "execution_aborted"
            events.last().first shouldBe "pipeline_failed"
            events
                .last()
                .second
                .path("status")
                .asText() shouldBe "OUTCOME_UNKNOWN"
            executionRow(rootId)["status"] shouldBe "OUTCOME_UNKNOWN"
            history.assertRecordOnEverySurface(rootId, "OUTCOME_UNKNOWN", false, "cancel in window")
            history.assertEndsAt(rootId, "pipeline_failed", "OUTCOME_UNKNOWN", "cancel in window")

            hold.release()
            await("the customer database settled the abandoned COMMIT") { waiters(hold.pid) == 0 }
            count("cc_t2_tgt").let { (it == 0 || it == ROWS) shouldBe true }
        }
    }

    @Test
    fun `a cancellation inside the commit window and an answer before the budget - SUCCESS, rows exactly once`() {
        ParentHold(3).use { hold ->
            val run = LiveRun(pipelines.getValue("cancel_window_answered"))
            val rootId = run.id
            await("commit attempted and waiting on the customer database") {
                executionRow(rootId)["commit_attempted_at"] != null && waiters(hold.pid) > 0
            }
            cancel(rootId).statusCode shouldBe HTTP_NO_CONTENT
            hold.release()

            val events = run.events(FAMILY_BUDGET_SECONDS)
            events.map { it.first } shouldNotContain "execution_aborted"
            events.map { it.first } shouldContain "pipeline_completed"
            executionRow(rootId)["status"] shouldBe "SUCCESS"
            count("cc_t7_tgt") shouldBe ROWS
            history.assertRecordOnEverySurface(rootId, "SUCCESS", false, "cancel in window, answered")
        }
    }

    @Test
    fun `a cancellation before the commit attempt rolls everything back and ends ABORTED`() {
        val run = LiveRun(pipelines.getValue("cancel_before"))
        val rootId = run.id
        await("the slow node running on the customer database") {
            backendsRunning("pg_sleep($SLOW_SLEEP)") > 0 && executionRow(rootId)["commit_attempted_at"] == null
        }
        count("cc_t3_tgt") shouldBe 0
        cancel(rootId).statusCode shouldBe HTTP_NO_CONTENT

        val events = run.events(FAMILY_BUDGET_SECONDS)
        events.last().first shouldBe "execution_aborted"
        executionRow(rootId).let {
            it["status"] shouldBe "ABORTED"
            it["commit_attempted_at"].shouldBeNull()
        }
        count("cc_t3_tgt") shouldBe 0
        history.assertRecordOnEverySurface(rootId, "ABORTED", false, "cancel before the attempt")
        history.assertEndsAt(rootId, "execution_aborted", "ABORTED", "cancel before the attempt")
    }

    @Test
    fun `a cancellation after the confirmed commit cannot undo it - SUCCESS, rows exactly once`() {
        val trigger = installTerminalHold(pipelines.getValue("cancel_after"))
        try {
            AdvisoryHold().use { hold ->
                val run = LiveRun(pipelines.getValue("cancel_after"))
                val rootId = run.id
                await("confirmation stamped, terminal write held") {
                    executionRow(rootId)["commit_confirmed_at"] != null && waiters(hold.pid) > 0
                }
                count("cc_t4_tgt") shouldBe ROWS
                cancel(rootId).statusCode shouldBe HTTP_NO_CONTENT
                hold.release()

                val events = run.events(FAMILY_BUDGET_SECONDS)
                events.map { it.first } shouldContain "pipeline_completed"
                events.map { it.first } shouldNotContain "execution_aborted"
                executionRow(rootId)["status"] shouldBe "SUCCESS"
                count("cc_t4_tgt") shouldBe ROWS
                history.assertRecordOnEverySurface(rootId, "SUCCESS", false, "cancel after the confirmation")
                // The execution is over: cancelling again is refused, and nothing ran the writes a second time.
                cancel(rootId).statusCode shouldBe HTTP_CONFLICT
                count("cc_t4_tgt") shouldBe ROWS
            }
        } finally {
            trigger.close()
        }
    }

    // ---------------------------------------------------------------- the real scheduler

    @Test
    fun `the scheduler runs a family to a confirmed commit and records the run as succeeded`() {
        val schedule = createSchedule("test/cc_sched_ok_run", "test/cc_sched_ok")
        val run = awaitRun(schedule, runNow(schedule))

        run["state"].asText() shouldBe "succeeded"
        val executionId = run["execution_id"].asText()
        executionRow(executionId).let {
            it["status"] shouldBe "SUCCESS"
            it["commit_confirmed_at"].shouldNotBeNull()
        }
        count("cc_t6_tgt") shouldBe ROWS
        history.assertRecordOnEverySurface(executionId, "SUCCESS", false, "scheduled success")
    }

    @Test
    fun `the scheduler records a run whose commit outcome is unknown as unknown, never as succeeded or failed`() {
        ParentHold(5).use { hold ->
            val schedule = createSchedule("test/cc_sched_unknown_run", "test/cc_sched_unknown")
            val runId = runNow(schedule)
            await("commit attempted and waiting on the customer database") {
                waiters(hold.pid) > 0
            }

            val run = awaitRun(schedule, runId)
            run["state"].asText() shouldBe "unknown"
            val executionId = run["execution_id"].asText()
            executionRow(executionId)["status"] shouldBe "OUTCOME_UNKNOWN"
            executionRow(executionId)["commit_attempted_at"].shouldNotBeNull()
            history.assertRecordOnEverySurface(executionId, "OUTCOME_UNKNOWN", false, "scheduled unknown")
            hold.release()
            await("the customer database settled the abandoned COMMIT") { waiters(hold.pid) == 0 }
            count("cc_t5_tgt").let { (it == 0 || it == ROWS) shouldBe true }
        }
    }

    // ---------------------------------------------------------------- the live run

    /** One execution streamed over the ordinary SSE POST: the id arrives with `execution_started`, the events at the end. */
    private inner class LiveRun(
        pipelineId: String,
    ) {
        private val started = CompletableFuture<String>()
        private val finished = CompletableFuture<List<Pair<String, JsonNode>>>()

        init {
            Thread {
                runCatching {
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
                    val events = mutableListOf<Pair<String, JsonNode>>()
                    BufferedReader(InputStreamReader(response.body())).use { reader ->
                        var current: String? = null
                        reader.lines().forEach { line ->
                            if (line.startsWith("event:")) current = line.removePrefix("event:").trim()
                            if (line.startsWith("data:")) {
                                val payload = mapper.readTree(line.removePrefix("data:").trim())
                                events += (current ?: "unknown") to payload
                                if (current == "execution_started") started.complete(payload["execution_id"].asText())
                            }
                        }
                    }
                    finished.complete(events)
                }.onFailure {
                    started.completeExceptionally(it)
                    finished.completeExceptionally(it)
                }
            }.apply {
                isDaemon = true
                name = "controls-stream-$pipelineId"
            }.start()
        }

        val id: String get() = started.get(HOLD_BUDGET_SECONDS, TimeUnit.SECONDS)

        /** The stream's events once it has ended (waits at most [seconds] plus a settling margin). */
        fun events(seconds: Long): List<Pair<String, JsonNode>> = finished.get(seconds + SETTLE_SECONDS, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- holds

    /** A lock this test holds on one customer parent row: the family's deferred-FK COMMIT waits behind it. */
    private inner class ParentHold(
        parentId: Int,
    ) : AutoCloseable {
        private val connection: Connection = customer().also { it.autoCommit = false }
        val pid: Int =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }

        init {
            connection.createStatement().use { it.execute("SELECT id FROM cc_parent WHERE id = $parentId FOR UPDATE") }
        }

        fun release() {
            runCatching { connection.rollback() }
        }

        override fun close() {
            release()
            runCatching { connection.close() }
        }
    }

    /** A session-level advisory lock on the metadata database: the terminal-write trigger waits behind it. */
    private inner class AdvisoryHold : AutoCloseable {
        private val connection: Connection = metadata()
        val pid: Int =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }

        init {
            connection.createStatement().use { it.execute("SELECT pg_advisory_lock($ADVISORY_KEY)") }
        }

        fun release() {
            runCatching { connection.createStatement().use { it.execute("SELECT pg_advisory_unlock($ADVISORY_KEY)") } }
        }

        override fun close() {
            release()
            runCatching { connection.close() }
        }
    }

    /** The trigger that holds the terminal write of [pipelineId] behind the advisory lock; closing it drops the trigger. */
    private fun installTerminalHold(pipelineId: String): AutoCloseable {
        metaUpdate(
            """
            CREATE OR REPLACE FUNCTION cc1035_hold_terminal_write() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              IF OLD.status = 'RUNNING' AND NEW.status <> 'RUNNING' AND NEW.pipeline_id::text = '$pipelineId' THEN
                PERFORM pg_advisory_lock($ADVISORY_KEY);
                PERFORM pg_advisory_unlock($ADVISORY_KEY);
              END IF;
              RETURN NEW;
            END ${'$'}${'$'};
            DROP TRIGGER IF EXISTS cc1035_hold_terminal_write ON pipeline_executions;
            CREATE TRIGGER cc1035_hold_terminal_write BEFORE UPDATE ON pipeline_executions
              FOR EACH ROW EXECUTE FUNCTION cc1035_hold_terminal_write()
            """.trimIndent(),
        )
        return AutoCloseable { metaUpdate("DROP TRIGGER IF EXISTS cc1035_hold_terminal_write ON pipeline_executions") }
    }

    /** Backends, in any database of the cluster, currently blocked by [holderPid]. */
    private fun waiters(holderPid: Int): Int =
        metadata().use { c ->
            c.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))").use { s ->
                s.setInt(1, holderPid)
                s.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun backendsRunning(fragment: String): Int =
        metadata().use { c ->
            c
                .prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity " +
                        "WHERE datname = ? AND state = 'active' AND pid <> pg_backend_pid() AND query LIKE ?",
                ).use { s ->
                    s.setString(1, CUSTOMER_DB)
                    s.setString(2, "%$fragment%")
                    s.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                }
        }

    // ---------------------------------------------------------------- reads and actions

    private fun cancel(executionId: String) =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/executions/$executionId")
            .thenReturn()

    private fun fetchResult(executionId: String): JsonNode =
        mapper.readTree(
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/$executionId/result")
                .asString(),
        )

    private fun createSchedule(
        name: String,
        pipeline: String,
    ): String {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to name,
                            "payload" to mapOf("pipeline" to pipeline, "version" to 1),
                            "cron" to YEARLY,
                            "timezone" to "UTC",
                        ),
                    ),
                ).`when`()
                .post("/api/v1/schedules")
                .thenReturn()
        withClue("schedule $name ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe HTTP_CREATED }
        return response.jsonPath().getString("data.id")
    }

    private fun runNow(scheduleId: String): String {
        val response =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .post("/api/v1/schedules/$scheduleId/run")
                .thenReturn()
        withClue("run now ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe HTTP_ACCEPTED }
        return response.jsonPath().getString("data.id")
    }

    private fun awaitRun(
        scheduleId: String,
        runId: String,
    ): JsonNode {
        var run: JsonNode? = null
        await("the schedule run $runId reached a final state", FAMILY_BUDGET_SECONDS + SETTLE_SECONDS) {
            val body =
                given()
                    .port(port)
                    .asSession(ADMIN_SESSION)
                    .`when`()
                    .get("/api/v1/schedules/$scheduleId/runs/$runId")
                    .asString()
            run = mapper.readTree(body).path("data").takeIf { it.path("state").asText() !in ACTIVE_RUN_STATES }
            run != null
        }
        return run!!
    }

    private fun executionRow(id: String): Map<String, String?> =
        metadata().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT status, commit_attempted_at::text, commit_confirmed_at::text, settlement_pending::text " +
                            "FROM pipeline_executions WHERE execution_id = '$id'",
                    ).use { rows ->
                        check(rows.next()) { "no execution row $id" }
                        mapOf(
                            "status" to rows.getString(1),
                            "commit_attempted_at" to rows.getString(2),
                            "commit_confirmed_at" to rows.getString(3),
                            "settlement_pending" to rows.getString(4),
                        )
                    }
            }
        }

    private fun await(
        what: String,
        seconds: Long = HOLD_BUDGET_SECONDS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + seconds * MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out after ${seconds}s waiting for: $what")
    }

    private fun count(table: String): Int =
        customer().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun metaScalar(sql: String): String? = metadata().use { it.queryString(sql) }

    private fun metaUpdate(sql: String) {
        metadata().use { it.run(sql) }
    }

    private fun customer(): Connection = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

    private fun metadata(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    // ---------------------------------------------------------------- fixtures

    /** Seeds the identities, the customer database, the templates and every pipeline; returns the pipeline ids by case. */
    private fun prepare(): Map<String, String> {
        seedAuth()
        seedCustomer()
        api.registerDatasource(db.jdbcUrl, db.username, db.password)
        api.template("test/cc_read.sql", "SELECT id FROM co_src ORDER BY id")
        api.template("test/cc_read_one.sql", "SELECT id FROM co_src WHERE id = 1")
        api.template("test/cc_slow.sql", "SELECT id FROM co_src, (SELECT pg_sleep($SLOW_SLEEP)) AS slow ORDER BY id LIMIT 1")
        listOf(1, 2, 3, 5).forEach { api.template("test/cc_fk$it.sql", "INSERT INTO cc_fk$it VALUES ($it)") }
        api.releasedPipeline("test/cc_child_append", listOf(api.caller("read_all", "test/cc_read.sql")))
        val read = "test/cc_read.sql"
        // The two scheduled pipelines are RELEASED: a schedule pins an exact released version. The rest run as drafts.
        api.releasedPipeline(
            "test/cc_sched_ok",
            listOf(api.copy("copy", read, "cc_t6_tgt", emptyList()), api.caller("ret", "test/cc_read_one.sql", listOf("copy"))),
        )
        api.releasedPipeline(
            "test/cc_sched_unknown",
            listOf(api.copy("copy", read, "cc_t5_tgt", emptyList()), api.deferredWrite("write_fk", "test/cc_fk5.sql", listOf("copy"))),
        )
        return mapOf(
            "deadline" to
                api.pipeline(
                    "test/cc_deadline_window",
                    listOf(
                        api.child("ap", "test/cc_child_append", "cc_t1_tgt", emptyList()),
                        api.deferredWrite("write_fk", "test/cc_fk1.sql", listOf("ap")),
                    ),
                ),
            "ok" to api.pipeline("test/cc_ok", listOf(api.copy("copy", read, "cc_ok_tgt", emptyList()))),
            "cancel_window" to
                api.pipeline(
                    "test/cc_cancel_window",
                    listOf(
                        api.copy("copy", read, "cc_t2_tgt", emptyList()),
                        api.deferredWrite("write_fk", "test/cc_fk2.sql", listOf("copy")),
                    ),
                ),
            "cancel_window_answered" to
                api.pipeline(
                    "test/cc_cancel_window_answered",
                    listOf(
                        api.copy("copy", read, "cc_t7_tgt", emptyList()),
                        api.deferredWrite("write_fk", "test/cc_fk3.sql", listOf("copy")),
                    ),
                ),
            "cancel_before" to
                api.pipeline(
                    "test/cc_cancel_before",
                    listOf(api.copy("copy", read, "cc_t3_tgt", emptyList()), api.caller("hold", "test/cc_slow.sql", listOf("copy"))),
                ),
            "cancel_after" to
                api.pipeline(
                    "test/cc_cancel_after",
                    listOf(api.copy("copy", read, "cc_t4_tgt", emptyList()), api.caller("ret", "test/cc_read_one.sql", listOf("copy"))),
                ),
            "sched_ok" to pipelineId("test/cc_sched_ok"),
            "sched_unknown" to pipelineId("test/cc_sched_unknown"),
        )
    }

    private fun pipelineId(name: String): String = metaScalar("SELECT id FROM pipelines WHERE name = '$name'")!!

    private fun seedAuth() {
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
    }

    private fun seedCustomer() {
        customer().use { connection ->
            connection.createStatement().use { s ->
                s.execute("CREATE TABLE co_src (id INT PRIMARY KEY)")
                s.execute("INSERT INTO co_src SELECT generate_series(1, $ROWS)")
                listOf("cc_ok_tgt", "cc_t1_tgt", "cc_t2_tgt", "cc_t3_tgt", "cc_t4_tgt", "cc_t5_tgt", "cc_t6_tgt", "cc_t7_tgt").forEach {
                    s.execute("CREATE TABLE $it (id INT PRIMARY KEY)")
                }
                s.execute("CREATE TABLE cc_parent (id BIGINT PRIMARY KEY)")
                s.execute("INSERT INTO cc_parent VALUES (1), (2), (3), (5)")
                listOf(1, 2, 3, 5).forEach {
                    s.execute(
                        "CREATE TABLE cc_fk$it (id BIGINT, CONSTRAINT cc_fk${it}_fk FOREIGN KEY (id) REFERENCES cc_parent(id) " +
                            "DEFERRABLE INITIALLY DEFERRED)",
                    )
                }
            }
        }
    }

    companion object {
        private const val DATASOURCE = "cc1035"
        private const val CUSTOMER_DB = "scratch_commitcontrols1035"
        private const val ADVISORY_KEY = 1_035_002
        private const val ROWS = 2500
        private const val SLOW_SLEEP = 60
        private const val FAMILY_BUDGET_SECONDS = 12L
        private const val SETTLE_SECONDS = 30L
        private const val HOLD_BUDGET_SECONDS = 90L
        private const val POLL_MS = 200L
        private const val MILLIS = 1000L
        private const val EXCERPT = 300
        private val ACTIVE_RUN_STATES = setOf("queued", "starting", "running")
        private const val YEARLY = "0 3 1 1 *"
        private const val HTTP_CREATED = 201
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_CONFLICT = 409
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val ADMIN_EMAIL = "e2e-cc1035@datapipelines.test"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val MCP_KEY = E2eAuth.generateKey("cc1035-mcp-reader")
        private val MCP_IDENTITY_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val postgres get() = SharedE2e.postgres

        /** Read and write in ONE customer database — what makes these families local-write families. */
        private val db = SharedE2e.scratchDatabase("commitcontrols1035")
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
            // The deadline is the family's budget: short enough to test, long enough for an ordinary family to commit.
            registry.add("datapipelines.executor.execution-timeout-seconds") { FAMILY_BUDGET_SECONDS.toString() }
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // The scheduled cases need a DISPATCHING context (the module default is API mode), on a one-second clock.
            registry.add("datapipelines.scheduler.enabled") { "true" }
            registry.add("datapipelines.scheduler.tick-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.polling-interval-seconds") { "1" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
