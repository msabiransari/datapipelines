package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import co.datapipelines.logcapture.SnapshotListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #1035 R4 — REAL INSTANCE LOSS at the three points of the commit sequence, reconciled by ANOTHER instance.
 *
 * Instance A is the packaged `datapipelines-app.jar` booted as a child JVM against the same metadata database, the same
 * customer database, the same Redis and the same secrets; instance B is this test's own application. Four executions are
 * started on A and each is HELD at a different point by the databases themselves (a row lock, a deferred-constraint
 * lock, an advisory-lock trigger — nothing in the product is stubbed):
 *
 *  1. BEFORE the attempt — a node is still running; the family's writes are open and uncommitted;
 *  2. IN the window (twice) — the customer COMMIT is issued and cannot return; one family carries a held child;
 *  3. AFTER the confirmation — the customer COMMIT returned and was stamped; the terminal write is held.
 *
 * A is then killed with SIGKILL. B's crash sweep reconciles every row by its commit evidence, the customer rows and the
 * execution records are read independently on connections of this test's own, every history surface is read, and the
 * per-unknown alert (one ERROR line and one counter increment per unknown) is read off the sweeper's wire.
 */
@Suppress("LargeClass") // one process-loss scenario over one fixture; splitting it would boot the child JVM once per piece
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class CommitInstanceLossIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()
    private val history by lazy { CommitHistoryReader(port, ADMIN_SESSION, MCP_KEY.plaintext) }

    @Test
    fun `an instance killed before the attempt, in the window and after the confirmation is reconciled by another instance`() {
        val sweeperLog = captureSweeperLog()
        val unknownBefore = unknownMeter()
        val fixture = prepare()
        val holds = Holds()
        val instance = ChildInstance.boot(port = freePort())
        try {
            val runs = start(instance, fixture)
            awaitHolds(runs, holds)
            observeBeforeTheKill(runs)

            instance.kill()
            releaseTheDeadInstancesMetadataHold(holds)
            reconcile(runs)

            assertRecords(runs)
            assertSurfaces(runs)
            assertCustomerAfterReconcile(runs, holds)
            assertAlerts(runs, sweeperLog, unknownBefore)
            sweepOnce() shouldBe 0
        } finally {
            holds.close()
            instance.kill()
            terminateInstanceBackends()
            dropTrigger()
            (LoggerFactory.getLogger(SWEEPER_LOGGER) as Logger).detachAppender(sweeperLog)
        }
    }

    // ------------------------------------------------------------------ the four held executions

    private data class Fixture(
        val s1: String,
        val s2a: String,
        val s2b: String,
        val s3: String,
    )

    private class Runs(
        val s1: Run,
        val s2a: Run,
        val s2b: Run,
        val s3: Run,
    ) {
        val all get() = listOf(s1, s2a, s2b, s3)
    }

    /** One execution streamed from instance A: the id arrives with `execution_started`; the reader dies with A. */
    private class Run(
        val label: String,
        val executionId: CompletableFuture<String> = CompletableFuture(),
    ) {
        val id: String get() = executionId.get(HOLD_BUDGET_SECONDS, TimeUnit.SECONDS)
        var child: String? = null
    }

    private fun start(
        instance: ChildInstance,
        fixture: Fixture,
    ): Runs {
        val runs = Runs(Run("s1-before-attempt"), Run("s2a-in-window-family"), Run("s2b-in-window"), Run("s3-after-confirmation"))
        listOf(runs.s1 to fixture.s1, runs.s2a to fixture.s2a, runs.s2b to fixture.s2b, runs.s3 to fixture.s3).forEach { (run, pipeline) ->
            streamFromInstance(instance.port, pipeline, run)
        }
        return runs
    }

    private fun streamFromInstance(
        instancePort: Int,
        pipelineId: String,
        run: Run,
    ) {
        val thread =
            Thread {
                runCatching {
                    val request =
                        HttpRequest
                            .newBuilder(URI.create("http://localhost:$instancePort/api/v1/pipelines/$pipelineId/execute"))
                            .header("Cookie", E2eSession.cookieHeader(ADMIN_SESSION))
                            .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                            .header("DP-Correlation-Id", UUID.randomUUID().toString())
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString("""{"parameters": {}}"""))
                            .build()
                    val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
                    var event: String? = null
                    BufferedReader(InputStreamReader(response.body())).use { reader ->
                        reader.lines().forEach { line ->
                            if (line.startsWith("event:")) event = line.removePrefix("event:").trim()
                            if (line.startsWith("data:") && event == "execution_started") {
                                run.executionId.complete(mapper.readTree(line.removePrefix("data:").trim())["execution_id"].asText())
                            }
                        }
                    }
                }.onFailure { run.executionId.completeExceptionally(it) }
            }
        thread.isDaemon = true
        thread.name = "loss-stream-${run.label}"
        thread.start()
    }

    // ------------------------------------------------------------------ holding each execution at its point

    /** Three locks this test owns: two customer parent rows (the deferred-constraint waits) and one metadata advisory lock. */
    private inner class Holds : AutoCloseable {
        val parent1: Connection = customer().also { it.autoCommit = false }
        val parent2: Connection = customer().also { it.autoCommit = false }
        val advisory: Connection = metadata()
        val parent1Pid: Int
        val parent2Pid: Int
        val advisoryPid: Int

        init {
            parent1Pid = pid(parent1)
            parent2Pid = pid(parent2)
            advisoryPid = pid(advisory)
            parent1.createStatement().use { it.execute("SELECT id FROM cl_parent WHERE id = 1 FOR UPDATE") }
            parent2.createStatement().use { it.execute("SELECT id FROM cl_parent WHERE id = 2 FOR UPDATE") }
            advisory.createStatement().use { it.execute("SELECT pg_advisory_lock($ADVISORY_KEY)") }
        }

        private fun pid(connection: Connection): Int =
            connection.createStatement().use { s ->
                s.executeQuery("SELECT pg_backend_pid()").use {
                    it.next()
                    it.getInt(1)
                }
            }

        fun releaseParent1() = runCatching { parent1.rollback() }

        fun releaseParent2() = runCatching { parent2.rollback() }

        fun releaseAdvisory() = runCatching { advisory.createStatement().use { it.execute("SELECT pg_advisory_unlock($ADVISORY_KEY)") } }

        override fun close() {
            listOf(parent1, parent2, advisory).forEach { runCatching { it.close() } }
        }
    }

    private fun awaitHolds(
        runs: Runs,
        holds: Holds,
    ) {
        // 1 — the node that holds the family open has started on the customer database: the writes before it are open.
        await("s1: the slow node running on A") {
            backends(CUSTOMER_DB).any { it.query.contains("pg_sleep($HOLD_SLEEP)") && it.state == "active" }
        }
        await("s1: no attempt stamped") { executionRow(runs.s1.id)["commit_attempted_at"] == null }
        // 2 — the customer COMMIT is issued and cannot return (blocked behind the held parent row).
        runs.s2a.child = awaitPendingChild(runs.s2a.id)
        await("s2a: attempt stamped, COMMIT blocked behind parent 1") {
            executionRow(runs.s2a.id)["commit_attempted_at"] != null &&
                backends(CUSTOMER_DB).any { holds.parent1Pid in it.blockedBy }
        }
        await("s2b: attempt stamped, COMMIT blocked behind parent 2") {
            executionRow(runs.s2b.id)["commit_attempted_at"] != null &&
                backends(CUSTOMER_DB).any { holds.parent2Pid in it.blockedBy }
        }
        // 3 — the customer COMMIT returned and is stamped; the terminal write is held inside the trigger.
        await("s3: confirmation stamped, terminal write held") {
            executionRow(runs.s3.id)["commit_confirmed_at"] != null &&
                backends(metadataDatabase()).any { holds.advisoryPid in it.blockedBy }
        }
    }

    private fun observeBeforeTheKill(runs: Runs) {
        runs.all.forEach { executionRow(it.id)["status"] shouldBe "RUNNING" }
        // The second client's view of the customer rows, with A alive: nothing is visible except the one committed family.
        count("cl_s1_tgt") shouldBe 0
        count("cl_s2a_tgt") shouldBe 0
        count("cl_s2b_tgt") shouldBe 0
        count("cl_fk_a") + count("cl_fk_b") shouldBe 0
        count("cl_s3_tgt") shouldBe ROWS
        executionRow(runs.s2a.child!!)["settlement_pending"] shouldBe "true"
        executionRow(runs.s3.id)["commit_confirmed_at"].shouldNotBeNull()
    }

    private fun releaseTheDeadInstancesMetadataHold(holds: Holds) {
        // The dead instance's blocked terminal write must never land: its backend is terminated, then the lock is released.
        terminateInstanceBackends(database = metadataDatabase(), blockedBy = holds.advisoryPid)
        holds.releaseAdvisory()
    }

    // ------------------------------------------------------------------ reconcile on B and read the outcome

    private fun reconcile(runs: Runs) {
        // B's cutoff is three heartbeats (2 s each here): the rows become sweepable only once A's last beat is that old.
        await("every held root settled by B's sweep", timeoutSeconds = RECONCILE_BUDGET_SECONDS) {
            sweepOnce()
            runs.all.all { executionRow(it.id)["status"] != "RUNNING" }
        }
    }

    private fun assertRecords(runs: Runs) {
        executionRow(runs.s1.id).let {
            it["status"] shouldBe "ABORTED"
            it["error_code"] shouldBe "pipeline.execution.instance_lost"
            it["commit_attempted_at"].shouldBeNull()
            it["result_unavailable"] shouldBe "false"
        }
        listOf(runs.s2a, runs.s2b).forEach { run ->
            executionRow(run.id).let {
                it["status"] shouldBe "OUTCOME_UNKNOWN"
                it["error_code"] shouldBe "pipeline.execution.local_write_commit_failed"
                it["commit_attempted_at"].shouldNotBeNull()
                it["commit_confirmed_at"].shouldBeNull()
            }
        }
        executionRow(runs.s2a.child!!).let {
            it["status"] shouldBe "OUTCOME_UNKNOWN"
            it["settlement_pending"] shouldBe "false"
            it["error_code"] shouldBe "pipeline.execution.local_write_commit_failed"
        }
        executionRow(runs.s3.id).let {
            it["status"] shouldBe "SUCCESS"
            it["result_unavailable"] shouldBe "true"
            it["commit_confirmed_at"].shouldNotBeNull()
            it["completed_at"].shouldNotBeNull()
        }
    }

    private fun assertSurfaces(runs: Runs) {
        val expected =
            mapOf(
                runs.s1.id to ("ABORTED" to false),
                runs.s2a.id to ("OUTCOME_UNKNOWN" to false),
                runs.s2b.id to ("OUTCOME_UNKNOWN" to false),
                runs.s2a.child!! to ("OUTCOME_UNKNOWN" to false),
                runs.s3.id to ("SUCCESS" to true),
            )
        expected.forEach { (id, outcome) ->
            // REST, the MCP tool and the paged listing (one row per page) all carry the swept record.
            history.assertRecordOnEverySurface(id, outcome.first, outcome.second, "swept $id")
            // The sweep writes status only: no history surface claims a terminal event nobody ran, except the one a
            // confirmed commit really emitted before its instance died.
            if (id == runs.s3.id) {
                history.assertCompletedWithoutResult(id, "swept $id")
            } else {
                history.assertNothingTerminal(id, "swept $id")
            }
        }
        // The confirmed family's result is not fetchable, on REST and MCP, with the delivery error and not a rerun.
        val cursor =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .`when`()
                .get("/api/v1/executions/${runs.s3.id}/result")
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
                    "arguments" to mapOf("execution_id" to runs.s3.id),
                ),
            )
        fetched.toString() shouldContain "result_delivery_failed"
    }

    /** Both windows are resolved by the databases AFTER the sweep: the record stays UNKNOWN whichever way the customer ended. */
    private fun assertCustomerAfterReconcile(
        runs: Runs,
        holds: Holds,
    ) {
        // The committed family's rows exist exactly once, and nothing the sweep or any read did ran them again.
        count("cl_s3_tgt") shouldBe ROWS
        count("cl_s1_tgt") shouldBe 0

        // Window family (2a): the dead instance's blocked backend is cancelled, so the COMMIT never completes.
        terminateInstanceBackends(database = CUSTOMER_DB, blockedBy = holds.parent1Pid)
        holds.releaseParent1()
        // Window (2b): the lock is released and the dead instance's backend completes the commit nobody confirmed.
        holds.releaseParent2()
        await("s2b: the customer database finished the commit the dead instance never saw") { count("cl_s2b_tgt") == ROWS }
        terminateInstanceBackends(database = CUSTOMER_DB, query = "pg_sleep($HOLD_SLEEP)")
        await("no backend of the dead instance remains on the customer database") { backends(CUSTOMER_DB).isEmpty() }

        count("cl_s2a_tgt") shouldBe 0
        count("cl_fk_a") shouldBe 0
        count("cl_fk_b") shouldBe 1
        count("cl_s1_tgt") shouldBe 0
        // The two truths differ (rolled back vs committed) while the record says the same honest thing for both.
        listOf(runs.s2a, runs.s2b).forEach { executionRow(it.id)["status"] shouldBe "OUTCOME_UNKNOWN" }
        executionRow(runs.s2a.child!!)["status"] shouldBe "OUTCOME_UNKNOWN"
        executionRow(runs.s1.id)["status"] shouldBe "ABORTED"
        executionRow(runs.s3.id)["status"] shouldBe "SUCCESS"
        sweepOnce() shouldBe 0
        count("cl_s3_tgt") shouldBe ROWS
    }

    private fun assertAlerts(
        runs: Runs,
        log: SnapshotListAppender,
        meterBefore: Double,
    ) {
        val lines =
            log.events().filter {
                it.level.toString() == "ERROR" &&
                    it.formattedMessage.contains(
                        "event=execution.outcome_unknown",
                    )
            }
        // One line per unknown ROOT: two, each carrying its own id and never the other families' ids or any SQL.
        lines.map { it.formattedMessage.substringAfter("execution_id=").substringBefore(" ") } shouldContainExactlyIgnoringOrder
            listOf(runs.s2a.id, runs.s2b.id)
        (unknownMeter() - meterBefore) shouldBe 2.0
        lines.forEach { it.formattedMessage.contains("INSERT") shouldBe false }
    }

    private infix fun List<String>.shouldContainExactlyIgnoringOrder(expected: List<String>) =
        sorted() shouldContainExactly expected.sorted()

    // ------------------------------------------------------------------ the child JVM (instance A)

    private class ChildInstance(
        private val process: Process,
        val port: Int,
    ) {
        fun kill() {
            if (process.isAlive) {
                // Process.destroyForcibly is SIGKILL on Linux: no shutdown hook, no drain, no terminal write.
                process.destroyForcibly()
                process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }

        companion object {
            fun boot(port: Int): ChildInstance {
                val jar = jarFile()
                val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
                val log = Files.createTempFile("commit-loss-instance-a", ".log").toFile()
                val tmp = Files.createDirectories(File(System.getProperty("java.io.tmpdir"), "commit-loss-instance-a").toPath())
                val builder =
                    ProcessBuilder(javaBin, "-Djava.io.tmpdir=$tmp", "-jar", jar.absolutePath)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                builder.environment().remove("LOADER_PATH")
                builder.environment().remove("DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR")
                builder.environment().putAll(environment(port))
                val process = builder.start()
                val instance = ChildInstance(process, port)
                val deadline = System.currentTimeMillis() + BOOT_BUDGET_MS
                while (System.currentTimeMillis() < deadline) {
                    check(process.isAlive) { "instance A died during boot — log:\n${log.readText().takeLast(LOG_TAIL)}" }
                    if (healthy(port)) return instance
                    Thread.sleep(POLL_MS)
                }
                instance.kill()
                error("instance A did not become healthy — log:\n${log.readText().takeLast(LOG_TAIL)}")
            }

            private fun healthy(port: Int): Boolean =
                runCatching {
                    HttpClient
                        .newHttpClient()
                        .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health")).GET().build(),
                            HttpResponse.BodyHandlers.discarding(),
                        ).statusCode() == HTTP_OK
                }.getOrDefault(false)

            private fun environment(port: Int): Map<String, String> =
                mapOf(
                    "SERVER_PORT" to port.toString(),
                    "MANAGEMENT_SERVER_PORT" to "0",
                    "SPRING_DATASOURCE_URL" to withApplicationName(postgres.jdbcUrl),
                    "SPRING_DATASOURCE_USERNAME" to postgres.username,
                    "SPRING_DATASOURCE_PASSWORD" to postgres.password,
                    "DATAPIPELINES_REDIS_HOST" to SharedE2e.redisHost,
                    "DATAPIPELINES_REDIS_PORT" to SharedE2e.redisPort.toString(),
                    "DATAPIPELINES_JWT_SECRET" to JWT_SECRET,
                    "DATAPIPELINES_DB_ENCRYPTION_KEY" to ENCRYPTION_KEY,
                    "DATAPIPELINES_EXECUTOR_HEARTBEAT_SECONDS" to HEARTBEAT_SECONDS,
                    "DATAPIPELINES_AUTH_BASE_URL" to "http://127.0.0.1:$port",
                    "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_NAME" to "google",
                    "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_CLIENT_ID" to "loss",
                    "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_CLIENT_SECRET" to "loss",
                    "DATAPIPELINES_AUTH_OIDC_PROVIDERS_0_ISSUER_URI" to oidc.issuer,
                )

            private fun jarFile(): File {
                val override = System.getProperty("jarSmoke.jar")
                val jar = override?.let(::File) ?: File(repoRoot(), "modules/app/build/libs/datapipelines-app.jar")
                check(jar.isFile) { "bootJar output not found at ${jar.absolutePath}; build it with ./gradlew :modules:app:bootJar" }
                return jar
            }

            private fun repoRoot(): File {
                var dir: File? = File(System.getProperty("user.dir")).absoluteFile
                while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
                return requireNotNull(dir) { "no settings.gradle.kts on any ancestor of the working directory" }
            }
        }
    }

    // ------------------------------------------------------------------ sweeper, alerts, databases

    private fun sweepOnce(): Int {
        val type = Class.forName("co.datapipelines.executor.StaleExecutionSweeper")
        return type.getMethod("sweepOnce").invoke(context.getBean(type)) as Int
    }

    private fun captureSweeperLog(): SnapshotListAppender {
        val appender = SnapshotListAppender()
        (LoggerFactory.getLogger(SWEEPER_LOGGER) as Logger).addAppender(appender)
        return appender
    }

    private fun unknownMeter(): Double {
        val registryType = Class.forName("io.micrometer.core.instrument.MeterRegistry")
        val registry = context.getBean(registryType)
        val search = registryType.getMethod("find", String::class.java).invoke(registry, UNKNOWN_METER)
        val counter = search.javaClass.getMethod("counter").invoke(search) ?: return 0.0
        return Class.forName("io.micrometer.core.instrument.Counter").getMethod("count").invoke(counter) as Double
    }

    private data class Backend(
        val pid: Int,
        val state: String,
        val query: String,
        val blockedBy: List<Int>,
    )

    /** The backends instance A holds on [database]: found by the application name it connected with, never by guesswork. */
    private fun backends(database: String): List<Backend> =
        metadata().use { c ->
            c
                .prepareStatement(
                    "SELECT pid, coalesce(state, ''), coalesce(query, ''), array_to_string(pg_blocking_pids(pid), ',') " +
                        "FROM pg_stat_activity WHERE application_name = ? AND datname = ?",
                ).use { s ->
                    s.setString(1, INSTANCE_NAME)
                    s.setString(2, database)
                    s.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    Backend(
                                        rows.getInt(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows
                                            .getString(4)
                                            .split(',')
                                            .filter { it.isNotBlank() }
                                            .map { it.toInt() },
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun terminateInstanceBackends(
        database: String? = null,
        blockedBy: Int? = null,
        query: String? = null,
    ) {
        val targets = listOfNotNull(database ?: metadataDatabase(), CUSTOMER_DB.takeIf { database == null }).distinct()
        targets.forEach { target ->
            backends(target)
                .filter { blockedBy == null || blockedBy in it.blockedBy }
                .filter { query == null || it.query.contains(query) }
                .forEach { terminateBackend(it.pid) }
        }
    }

    private fun terminateBackend(pid: Int) {
        metadata().use { it.run("SELECT pg_terminate_backend($pid)") }
    }

    private fun metadataDatabase(): String = metaScalar("SELECT current_database()")!!

    private fun count(table: String): Int = scalarOn(db.jdbcUrl, db.username, db.password, "SELECT count(*) FROM $table")!!.toInt()

    private fun metaScalar(sql: String): String? = scalarOn(postgres.jdbcUrl, postgres.username, postgres.password, sql)

    private fun scalarOn(
        url: String,
        user: String,
        password: String,
        sql: String,
    ): String? = DriverManager.getConnection(url, user, password).use { it.queryString(sql) }

    private fun customer(): Connection = DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

    private fun metadata(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun metaUpdate(sql: String) = metadata().use { c -> c.createStatement().use { it.execute(sql) } }

    private fun executionRow(id: String): Map<String, String?> =
        metadata().use { connection ->
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

    private fun awaitPendingChild(rootId: String): String {
        var child: String? = null
        await("a held child under $rootId") {
            child =
                metaScalar(
                    "SELECT execution_id FROM pipeline_executions WHERE root_execution_id = '$rootId' " +
                        "AND parent_execution_id IS NOT NULL AND settlement_pending",
                )
            child != null
        }
        return child!!
    }

    private fun await(
        what: String,
        timeoutSeconds: Long = HOLD_BUDGET_SECONDS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out after ${timeoutSeconds}s waiting for: $what")
    }

    // ------------------------------------------------------------------ fixtures

    private fun prepare(): Fixture {
        val api = CommitScenarioApi(port, ADMIN_SESSION, DATASOURCE)
        seedAuth()
        seedCustomer()
        api.registerDatasource(withApplicationName(db.jdbcUrl), db.username, db.password)
        api.template("test/cl_read.sql", "SELECT id FROM co_src ORDER BY id")
        api.template("test/cl_read_one.sql", "SELECT id FROM co_src WHERE id = 1")
        api.template("test/cl_hold.sql", "SELECT id FROM co_src WHERE (SELECT pg_sleep($HOLD_SLEEP)) IS NULL ORDER BY id LIMIT 1")
        api.template("test/cl_fk_a.sql", "INSERT INTO cl_fk_a VALUES (1)")
        api.template("test/cl_fk_b.sql", "INSERT INTO cl_fk_b VALUES (2)")
        api.releasedPipeline("test/cl_child_append", listOf(api.caller("read_all", "test/cl_read.sql")))
        val s1 =
            api.pipeline(
                "test/cl_s1_before_attempt",
                listOf(
                    api.copy("copy", "test/cl_read.sql", "cl_s1_tgt", emptyList()),
                    api.caller("hold", "test/cl_hold.sql", listOf("copy")),
                ),
            )
        val s2a =
            api.pipeline(
                "test/cl_s2a_family_in_window",
                listOf(
                    api.child("ap", "test/cl_child_append", "cl_s2a_tgt", emptyList()),
                    api.deferredWrite("write_fk", "test/cl_fk_a.sql", listOf("ap")),
                ),
            )
        val s2b =
            api.pipeline(
                "test/cl_s2b_in_window",
                listOf(
                    api.copy("copy", "test/cl_read.sql", "cl_s2b_tgt", emptyList()),
                    api.deferredWrite("write_fk", "test/cl_fk_b.sql", listOf("copy")),
                ),
            )
        val s3 =
            api.pipeline(
                "test/cl_s3_after_confirmation",
                listOf(
                    api.copy("copy", "test/cl_read.sql", "cl_s3_tgt", emptyList()),
                    api.caller("ret", "test/cl_read_one.sql", listOf("copy")),
                ),
            )
        installTrigger(s3)
        return Fixture(s1, s2a, s2b, s3)
    }

    private fun seedAuth() {
        metaUpdate(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
            VALUES ('$ADMIN_USER_ID', '$ADMIN_EMAIL', '$ADMIN_EMAIL', 'test', 'sub-$ADMIN_USER_ID', TRUE, TRUE)
            ON CONFLICT (id) DO NOTHING;
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
                listOf("cl_s1_tgt", "cl_s2a_tgt", "cl_s2b_tgt", "cl_s3_tgt").forEach { s.execute("CREATE TABLE $it (id INT PRIMARY KEY)") }
                s.execute("CREATE TABLE cl_parent (id BIGINT PRIMARY KEY)")
                s.execute("INSERT INTO cl_parent VALUES (1), (2)")
                listOf("cl_fk_a", "cl_fk_b").forEach {
                    s.execute(
                        "CREATE TABLE $it (id BIGINT, CONSTRAINT ${it}_fk FOREIGN KEY (id) REFERENCES cl_parent(id) " +
                            "DEFERRABLE INITIALLY DEFERRED)",
                    )
                }
            }
        }
    }

    /**
     * The after-confirmation hold: a trigger (this test's, dropped afterwards) that waits on an advisory lock when the
     * INSTANCE-A session ends the execution row of [pipelineId]. The statements before it — the confirmation stamp included — run.
     */
    private fun installTrigger(pipelineId: String) {
        metaUpdate(
            """
            CREATE OR REPLACE FUNCTION cl1035_hold_terminal_write() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              IF current_setting('application_name') = '$INSTANCE_NAME' AND OLD.status = 'RUNNING' AND NEW.status <> 'RUNNING'
                 AND NEW.pipeline_id::text = '$pipelineId' THEN
                PERFORM pg_advisory_lock($ADVISORY_KEY);
                PERFORM pg_advisory_unlock($ADVISORY_KEY);
              END IF;
              RETURN NEW;
            END ${'$'}${'$'};
            DROP TRIGGER IF EXISTS cl1035_hold_terminal_write ON pipeline_executions;
            CREATE TRIGGER cl1035_hold_terminal_write BEFORE UPDATE ON pipeline_executions
              FOR EACH ROW EXECUTE FUNCTION cl1035_hold_terminal_write()
            """.trimIndent(),
        )
    }

    private fun dropTrigger() {
        runCatching { metaUpdate("DROP TRIGGER IF EXISTS cl1035_hold_terminal_write ON pipeline_executions") }
    }

    companion object {
        private const val DATASOURCE = "cl1035"
        private const val INSTANCE_NAME = "dp1035-instance-a"
        private const val SWEEPER_LOGGER = "co.datapipelines.executor.StaleExecutionSweeper"
        private const val UNKNOWN_METER = "datapipelines.executions.outcome_unknown_swept"
        private const val ADVISORY_KEY = 1_035_001
        private const val HOLD_SLEEP = 600
        private const val ROWS = 2500
        private const val HEARTBEAT_SECONDS = "2"
        private const val HOLD_BUDGET_SECONDS = 90L
        private const val RECONCILE_BUDGET_SECONDS = 120L
        private const val BOOT_BUDGET_MS = 180_000L
        private const val KILL_WAIT_SECONDS = 30L
        private const val POLL_MS = 200L
        private const val MILLIS = 1000L
        private const val LOG_TAIL = 4000
        private const val EXCERPT = 300
        private const val HTTP_OK = 200
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val ADMIN_EMAIL = "e2e-cl1035@datapipelines.test"
        private val ADMIN_USER_ID: String = UUID.randomUUID().toString()
        private val MCP_KEY = E2eAuth.generateKey("cl1035-mcp-reader")
        private val MCP_IDENTITY_ID: String = UUID.randomUUID().toString()
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER_ID, ADMIN_EMAIL)
        private val postgres get() = SharedE2e.postgres

        /** Read and write in ONE customer database — what makes these families local-write families. */
        private val db = SharedE2e.scratchDatabase("commitloss1035")
        private const val CUSTOMER_DB = "scratch_commitloss1035"
        private val oidc = OidcDiscoveryStub()

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** Every connection of instance A names itself, so its backends are found by name and no one else's are touched. */
        private fun withApplicationName(url: String): String = url + (if ('?' in url) "&" else "?") + "ApplicationName=$INSTANCE_NAME"

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
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.executor.heartbeat-seconds") { HEARTBEAT_SECONDS }
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
