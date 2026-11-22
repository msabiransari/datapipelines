package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * #1043 — runtime capture of published mutations, end to end over HTTP against a real metadata database, a real
 * PostgreSQL target and two application instances (published-mutation-apis §6, §7; rest-api §26).
 *
 * An administrator opens a window over the session REST API; endpoint keys call `/api/cap/v1/…`; every durable claim
 * is read back on this test's own JDBC connections — the reservation's stored decision, the private capture tables, the
 * ordinary execution row and its events, the audit log and the business target. Synthetic markers only: a RAW input
 * field no node selects, a SELECTED value two value-mode transformers keep, and an ERROR marker carried by the target's
 * check constraint name.
 *
 * The capture pipeline: `pick` (value-mode JSONata → `picked`) → `tag` (value-mode JSONata → `tagged`) → `write` (one
 * INSERT of both into `cap_rows`). The suite runs with `datapipelines.script.max-value-bytes=256`, so two long selected
 * values overflow the per-execution capture budget while every single value still passes the script gate.
 *
 * Ordering between a stop and an admission is FORCED, never raced: the test holds the window's row lock on its own
 * connection, queues the two statements in a chosen order (each observed waiting in `pg_stat_activity` on a third
 * connection), then releases it.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "datapipelines.endpoints.mutations-enabled=true",
        "datapipelines.script.max-value-bytes=256",
    ],
)
@Suppress("LargeClass") // one route family over one seed; the cases share it rather than re-seeding
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class PublishedMutationCaptureIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()
    private lateinit var writer: Key
    private lateinit var otherWriter: Key
    private lateinit var adminKey: Key
    private val routes = mutableMapOf<String, String>()
    private var replicaB: ConfigurableApplicationContext? = null
    private var portB: Int = 0

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        seedTarget()
        registerDatasource()
        createTemplates(workspace = null)
        createTemplates(workspace = OTHER_WORKSPACE)
        createPipeline(CAP_PIPELINE, withWrite = true, workspace = null)
        createPipeline(CAP_PIPELINE, withWrite = false, workspace = OTHER_WORKSPACE)
        createPipeline(BUDGET_PIPELINE, withWrite = true, workspace = null, tagTemplate = DOUBLE_TEMPLATE)
        // RECREATED stays draft-only: only an API whose every version is a DRAFT can be deleted (endpoint.version.last_release).
        for (path in ROUTES) {
            routes[path] =
                publish(path, "POST", workspace = null, pipeline = pipelineOf(path), release = path != RECREATED)
        }
        routes[GET_ROUTE] = publish(MAIN, "GET", workspace = null, pipeline = READ_PIPELINE)
        routes[OVERLAP_B] = publish(OVERLAP_IN_B, "POST", workspace = OTHER_WORKSPACE)
        routes[PUT_KEY] = publish(PUT_PATH, "PUT", workspace = null)
        routes[DELETE_KEY] = publish(DELETE_PATH, "DELETE", workspace = null, pipeline = DELETE_PIPELINE)
        mintKeys()
        bootReplicaB()
    }

    @AfterAll
    fun closeReplicaB() {
        replicaB?.close()
    }

    // ------------------------------------------------------------------------------------- off by default

    @Test
    fun `default off - with no window a mutation stores no decision, no detail row and reads as absent`() {
        val sel = marker("SEL-OFF")
        val answered = mutate(writer, OFF, "off-1", body(sel, "t-off"))
        withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
        val id = answered.let(::executionIdOf)

        text("SELECT coalesce(capture_session_id::text, 'none') FROM published_mutation_reservations WHERE execution_id = '$id'") shouldBe
            "none"
        captureRows(id) shouldBe 0
        targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
        detail(id).statusCode() shouldBe 404
    }

    // ------------------------------------------------------------------------- a window, its markers

    @Test
    fun `an unfiltered window keeps the declared outputs privately - every marker is checked on every surface`(output: CapturedOutput) {
        val raw = marker("RAW")
        val sel = marker("SEL")
        val capture = start(MAIN)
        try {
            val answered = mutate(writer, MAIN, "main-1", body(sel, "t-main", raw))
            withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
            val id = answered.let(::executionIdOf)

            // The decision and the detail expiry were stored with the admitting reservation: 24 h from admission.
            text("SELECT capture_session_id::text FROM published_mutation_reservations WHERE execution_id = '$id'") shouldBe capture
            text(
                "SELECT extract(epoch FROM capture_expires_at - created_at)::bigint FROM published_mutation_reservations " +
                    "WHERE execution_id = '$id'",
            ) shouldBe DETAIL_RETENTION_S
            awaitSealed(id)
            rows(
                "SELECT node_id, output_key, state, value_text FROM execution_capture_values WHERE execution_id = '$id'",
            ) shouldContainExactlyInAnyOrder
                listOf(listOf("pick", "picked", "CAPTURED", "\"$sel\""), listOf("tag", "tagged", "CAPTURED", "\"t-main\""))

            // The private read: no-store, the stored JSON text, complete.
            val read = detail(id)
            withClue(read.asString()) { read.statusCode() shouldBe 200 }
            read.header("Cache-Control") shouldContain "no-store"
            read.jsonPath().getString("data.capture_status") shouldBe "complete"
            read.jsonPath().getString("data.capture_id") shouldBe capture
            read.jsonPath().getList<String>("data.values.value_json") shouldContainExactlyInAnyOrder listOf("\"$sel\"", "\"t-main\"")

            // RAW: selected by nothing, so it is nowhere — not in the private tables either.
            for ((surface, text) in surfaces(id, answered, read, output)) {
                withClue("RAW marker on $surface") { text shouldNotContain raw }
            }
            // SELECTED: only in the private tables, the private read and the business row it was written to — never in
            // the ordinary execution row, its events, the audit log, the caller's answer or a log line.
            for ((surface, text) in surfaces(id, answered, read = null, output = output)) {
                withClue("SELECTED marker on $surface") { text shouldNotContain sel }
            }
            // The audit rows name the window and the inspection, with no value.
            text(
                "SELECT count(*) FROM audit_log WHERE event = 'execution.capture.started' AND details_json::text LIKE '%$capture%'",
            ) shouldBe
                "1"
            text("SELECT count(*) FROM audit_log WHERE event = 'execution.capture.inspected' AND details_json::text LIKE '%$id%'") shouldBe
                "1"
        } finally {
            stop(MAIN, capture)
        }
    }

    @Test
    fun `a failing write still leaves its successful transformers inspectable, and the error marker is not captured`(
        output: CapturedOutput,
    ) {
        val raw = marker("RAW-FAIL")
        val sel = marker("SEL-FAIL")
        val capture = start(FAILING)
        try {
            // The tag 'boom' trips the target's check constraint, whose NAME is the error marker.
            val answered = mutate(writer, FAILING, "fail-1", body(sel, "boom", raw))
            val id = executionIdOf(answered)
            awaitStatus(id, "FAILED")
            awaitSealed(id)

            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 0
            val read = detail(id)
            withClue(read.asString()) { read.statusCode() shouldBe 200 }
            read.header("Cache-Control") shouldContain "no-store"
            read.jsonPath().getString("data.execution_status") shouldBe "FAILED"
            read.jsonPath().getString("data.capture_status") shouldBe "complete"
            read.jsonPath().getList<String>("data.values.node_id") shouldContainExactlyInAnyOrder listOf("pick", "tag")
            read.jsonPath().getList<String>("data.values.value_json") shouldContainExactlyInAnyOrder listOf("\"$sel\"", "\"boom\"")

            // The same surface walk as the successful case: RAW nowhere, SELECTED only in the private read and tables,
            // and the ERROR marker — the run's own failure — never in the private read or tables.
            for ((surface, text) in surfaces(id, answered, read, output)) {
                withClue("RAW marker on $surface") { text shouldNotContain raw }
            }
            for ((surface, text) in surfaces(id, answered, read = null, output = output)) {
                withClue("SELECTED marker on $surface") { text shouldNotContain sel }
            }
            withClue("ERROR marker on the private read") { read.asString() shouldNotContain ERROR_MARKER }
            withClue("ERROR marker in the private tables") { captureText(id) shouldNotContain ERROR_MARKER }
            for ((surface, text) in surfaces(id, answered, read, output)) {
                withClue("ERROR marker on $surface") { text shouldNotContain ERROR_MARKER }
            }
        } finally {
            stop(FAILING, capture)
        }
    }

    @Test
    fun `two long values overflow the run's byte budget - the second is an omission marker with no preview`() {
        // pick keeps the 110-character selected value (112 JSON bytes); tag keeps it twice (222 bytes): each fits the
        // 256-byte output gate, together they exceed the 256-byte capture budget.
        val long1 = marker("SEL-BIG1") + "a".repeat(SELECTED_CHARS - marker("SEL-BIG1").length)
        val long2 = long1 + long1
        val capture = start(BUDGET)
        try {
            val answered = mutate(writer, BUDGET, "budget-1", body(long1, "t"))
            withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
            val id = answered.let(::executionIdOf)
            awaitSealed(id)

            rows(
                "SELECT node_id, state, coalesce(omitted_reason, ''), coalesce(value_text, '') " +
                    "FROM execution_capture_values WHERE execution_id = '$id'",
            ) shouldContainExactlyInAnyOrder
                listOf(listOf("pick", "CAPTURED", "", "\"$long1\""), listOf("tag", "OMITTED", "byte_budget_exhausted", ""))
            captureText(id) shouldNotContain long2
            text(
                "SELECT values_captured || '/' || values_omitted || '/' || bytes_used " +
                    "FROM execution_capture_runs WHERE execution_id = '$id'",
            ) shouldBe "1/1/${long1.length + 2}"
            // The business work is unaffected by the budget: the omitted value was still written.
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$long1' AND tagged = '$long2'") shouldBe 1
        } finally {
            stop(BUDGET, capture)
        }
    }

    @Test
    fun `a capture store that refuses every write leaves one committed business change and an honest incomplete status`() {
        val sel = marker("SEL-STORE")
        val capture = start(STORE_FAULT)
        try {
            val answered =
                trigger("cap1043_refuse_values", "execution_capture_values", "BEFORE INSERT", "TRUE") {
                    mutate(writer, STORE_FAULT, "store-1", body(sel, "t-store"))
                }
            withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
            val id = answered.let(::executionIdOf)
            awaitSealed(id)

            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
            count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id'") shouldBe 1
            count("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'") shouldBe 0
            text("SELECT write_failures FROM execution_capture_runs WHERE execution_id = '$id'") shouldBe "2"
            detail(id).jsonPath().getString("data.capture_status") shouldBe "incomplete"
            // A retry replays the one execution: the diagnostic failure never causes a second business run.
            mutate(writer, STORE_FAULT, "store-1", body(sel, "t-store")).let(::executionIdOf) shouldBe id
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
        } finally {
            stop(STORE_FAULT, capture)
        }
    }

    // --------------------------------------------------------------------- the filter and the window

    @Test
    fun `an exact correlation filter captures only the request carrying that correlation`() {
        val filter = UUID.randomUUID().toString()
        val capture = start(FILTERED, filter)
        try {
            val other = mutate(writer, FILTERED, "filter-other", body(marker("SEL-F1"), "t"), correlation = UUID.randomUUID().toString())
            val none = mutate(writer, FILTERED, "filter-none", body(marker("SEL-F2"), "t"))
            val exact = mutate(writer, FILTERED, "filter-exact", body(marker("SEL-F3"), "t"), correlation = filter)

            decisionOf(other) shouldBe "none"
            decisionOf(none) shouldBe "none"
            decisionOf(exact) shouldBe capture
            captureRows(other.let(::executionIdOf)) shouldBe 0
        } finally {
            stop(FILTERED, capture)
        }
    }

    @Test
    fun `early stop ends admission, the captured run stays readable after it, and a delayed stop never closes a newer window`() {
        val first = start(STOPPED)
        val captured = mutate(writer, STOPPED, "stop-1", body(marker("SEL-S1"), "t"))
        decisionOf(captured) shouldBe first
        stop(STOPPED, first).jsonPath().getString("data.end_reason") shouldBe "stopped"
        // Idempotent: the same stop answers the ended window.
        stop(STOPPED, first).jsonPath().getString("data.end_reason") shouldBe "stopped"

        val after = mutate(writer, STOPPED, "stop-2", body(marker("SEL-S2"), "t"))
        decisionOf(after) shouldBe "none"
        // Inspection after stop, until expiry.
        detail(captured.let(::executionIdOf)).statusCode() shouldBe 200

        val second = start(STOPPED)
        try {
            // A delayed repeat of the FIRST stop names the first window only.
            stop(STOPPED, first)
            list(STOPPED).getString("data.active.capture_id") shouldBe second
            decisionOf(mutate(writer, STOPPED, "stop-3", body(marker("SEL-S3"), "t"))) shouldBe second
        } finally {
            stop(STOPPED, second)
        }
    }

    @Test
    fun `a second start is 409 naming the active window, and a GET route, a malformed filter and an unknown route are refused`() {
        val capture = start(REFUSALS)
        try {
            val again = startRaw(REFUSALS, null)
            again.statusCode() shouldBe 409
            again.jsonPath().getString("error.code") shouldBe "execution.capture.already_active"
            again.jsonPath().getString("error.details.capture_id") shouldBe capture
        } finally {
            stop(REFUSALS, capture)
        }
        startRaw(GET_ROUTE, null).let {
            it.statusCode() shouldBe 400
            it.jsonPath().getString("error.details.reason") shouldBe "method_not_capturable"
        }
        startRaw(REFUSALS, "not-a-uuid").let {
            it.statusCode() shouldBe 400
            it.jsonPath().getString("error.details.reason") shouldBe "correlation_id_invalid"
        }
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("/api/v1/endpoints/${UUID.randomUUID()}/captures")
            .then()
            .statusCode(404)
    }

    @Test
    fun `a window whose deadline passed admits nothing more, lists as expired, and a new one can start`() {
        val capture = start(TIMED_OUT)
        // The deadline passes: the window's clock moved an hour back, as if it had been opened then.
        sql(
            "UPDATE execution_capture_sessions SET started_at = started_at - interval '2 hours', " +
                "deadline_at = deadline_at - interval '2 hours' WHERE id = '$capture'",
        )
        decisionOf(mutate(writer, TIMED_OUT, "timeout-1", body(marker("SEL-T1"), "t"))) shouldBe "none"
        val listed = list(TIMED_OUT)
        listed.getString("data.active") shouldBe null
        listed.getString("data.captures.find { it.capture_id == '$capture' }.end_reason") shouldBe "expired"
        val next = start(TIMED_OUT)
        stop(TIMED_OUT, next)
        text("SELECT end_reason FROM execution_capture_sessions WHERE id = '$capture'") shouldBe "EXPIRED"
    }

    @Test
    fun `a stop committed while an admission waited wins - the admission is not captured`() {
        val capture = start(RACE_STOP)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val (admitted, stopped) =
                holdingWindowRow(capture) { awaitWaiters ->
                    val stopping = pool.submit<Response> { stopRaw(RACE_STOP, capture) }
                    awaitWaiters(STOP_SQL, 1)
                    val admitting = pool.submit<Response> { mutate(writer, RACE_STOP, "race-stop-1", body(marker("SEL-RS"), "t")) }
                    awaitWaiters(ADMIT_SQL, 1)
                    admitting to stopping
                }
            stopped.get(FUTURE_S, TimeUnit.SECONDS).statusCode() shouldBe 200
            val answer = admitted.get(FUTURE_S, TimeUnit.SECONDS)
            withClue(answer.asString()) { answer.statusCode() shouldBe 200 }
            decisionOf(answer) shouldBe "none"
        } finally {
            pool.shutdownNow()
            stopRaw(RACE_STOP, capture)
        }
    }

    @Test
    fun `an admission queued before the stop wins - it is captured, and the stop ends the window after it`() {
        val capture = start(RACE_ADMIT)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val (admitted, stopped) =
                holdingWindowRow(capture) { awaitWaiters ->
                    val admitting = pool.submit<Response> { mutate(writer, RACE_ADMIT, "race-admit-1", body(marker("SEL-RA"), "t")) }
                    awaitWaiters(ADMIT_SQL, 1)
                    val stopping = pool.submit<Response> { stopRaw(RACE_ADMIT, capture) }
                    awaitWaiters(STOP_SQL, 1)
                    admitting to stopping
                }
            val answer = admitted.get(FUTURE_S, TimeUnit.SECONDS)
            withClue(answer.asString()) { answer.statusCode() shouldBe 200 }
            decisionOf(answer) shouldBe capture
            stopped.get(FUTURE_S, TimeUnit.SECONDS).jsonPath().getString("data.end_reason") shouldBe "stopped"
            decisionOf(mutate(writer, RACE_ADMIT, "race-admit-2", body(marker("SEL-RA2"), "t"))) shouldBe "none"
        } finally {
            pool.shutdownNow()
        }
    }

    // --------------------------------------------------------------------- replicas, retries, restart

    @Test
    fun `concurrent retries across two replicas share one execution and one decision - capture initially on`() {
        checkNotNull(replicaB).isActive shouldBe true
        val sel = marker("SEL-ON")
        val capture = start(REPLICAS_ON)
        val ids =
            try {
                contend(REPLICAS_ON, "replicas-on", body(sel, "t-on"))
            } finally {
                stop(REPLICAS_ON, capture)
            }
        ids.toSet() shouldHaveSize 1
        val id = ids.first()
        awaitEnded(id)
        targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
        text("SELECT capture_session_id::text FROM published_mutation_reservations WHERE execution_id = '$id'") shouldBe capture
        awaitSealed(id)
        count("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'") shouldBe 2
        // Whichever replica admitted it, the stored instant, decision and both expiries describe one admission.
        assertOneAdmission(id, capture)
        // After the stop, a retry on either replica — even with a changed correlation — reuses the stored decision.
        for (replica in listOf(port, portB)) {
            val retry = mutate(writer, REPLICAS_ON, "replicas-on", body(sel, "t-on"), replica, UUID.randomUUID().toString())
            retry.let(::executionIdOf) shouldBe id
        }
        text("SELECT capture_session_id::text FROM published_mutation_reservations WHERE execution_id = '$id'") shouldBe capture
        count("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'") shouldBe 2
        count("SELECT count(*) FROM execution_capture_runs WHERE execution_id = '$id'") shouldBe 1
    }

    @Test
    fun `concurrent retries across two replicas share one execution - capture initially off stays off for that execution`() {
        val sel = marker("SEL-OFF2")
        val ids = contend(REPLICAS_OFF, "replicas-off", body(sel, "t-off"))
        ids.toSet() shouldHaveSize 1
        val id = ids.first()
        awaitEnded(id)
        // A window opened afterwards, filtered on the retry's own changed correlation, does not reach the admitted run.
        val filter = UUID.randomUUID().toString()
        val capture = start(REPLICAS_OFF, filter)
        try {
            for (replica in listOf(port, portB)) {
                mutate(writer, REPLICAS_OFF, "replicas-off", body(sel, "t-off"), replica, filter).let(::executionIdOf) shouldBe
                    id
            }
            text(
                "SELECT coalesce(capture_session_id::text, 'none') FROM published_mutation_reservations WHERE execution_id = '$id'",
            ) shouldBe
                "none"
            captureRows(id) shouldBe 0
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
        } finally {
            stop(REPLICAS_OFF, capture)
        }
    }

    @Test
    fun `a restarted replica replays a captured run once and admits under the still-open window`() {
        val capture = start(RESTART)
        try {
            val request = body(marker("SEL-R1"), "t")
            val first = mutate(writer, RESTART, "restart-1", request, portB)
            val id = first.let(::executionIdOf)
            awaitSealed(id)
            // Admitted on replica B: the one admission's instant sits inside the window replica A opened.
            assertOneAdmission(id, capture)
            replicaB?.close()
            bootReplicaB()
            mutate(writer, RESTART, "restart-1", request, portB).let(::executionIdOf) shouldBe id
            count("SELECT count(*) FROM execution_capture_runs WHERE execution_id = '$id'") shouldBe 1
            decisionOf(mutate(writer, RESTART, "restart-2", body(marker("SEL-R2"), "t"), portB)) shouldBe capture
        } finally {
            stop(RESTART, capture)
        }
    }

    @Test
    fun `a definitely-running execution retried on both replicas with a changed correlation keeps its decision - capture initially on`() {
        val sel = marker("SEL-RUN-ON")
        val capture = start(RUNNING_ON)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val (id, first) =
                holdingTarget {
                    val first = pool.submit<Response> { mutate(writer, RUNNING_ON, "running-on", body(sel, "t-run-on")) }
                    awaitTargetWaiters(BUSINESS_WRITE_SQL, 1)
                    val id = reservedExecution(RUNNING_ON)
                    count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id' AND status = 'RUNNING'") shouldBe 1
                    // The window ends while the admitted run is held in its business write.
                    stop(RUNNING_ON, capture)
                    for (replica in listOf(port, portB)) {
                        val retry =
                            mutate(writer, RUNNING_ON, "running-on", body(sel, "t-run-on"), replica, UUID.randomUUID().toString())
                        withClue(retry.asString()) { retry.statusCode() shouldBe 202 }
                        retry.let(::executionIdOf) shouldBe id
                    }
                    decisionOf(id) shouldBe capture
                    id to first
                }
            first.get(FUTURE_S, TimeUnit.SECONDS).let(::executionIdOf) shouldBe id
            awaitEnded(id)
            awaitSealed(id)
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
            count("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'") shouldBe 2
            count("SELECT count(*) FROM execution_capture_runs WHERE execution_id = '$id'") shouldBe 1
            assertOneAdmission(id, capture)
        } finally {
            pool.shutdownNow()
            stopRaw(RUNNING_ON, capture)
        }
    }

    @Test
    fun `a definitely-running execution retried on both replicas with a changed correlation keeps its decision - capture initially off`() {
        val sel = marker("SEL-RUN-OFF")
        val pool = Executors.newSingleThreadExecutor()
        var capture: String? = null
        try {
            val (id, first) =
                holdingTarget {
                    val first = pool.submit<Response> { mutate(writer, RUNNING_OFF, "running-off", body(sel, "t-run-off")) }
                    awaitTargetWaiters(BUSINESS_WRITE_SQL, 1)
                    val id = reservedExecution(RUNNING_OFF)
                    count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id' AND status = 'RUNNING'") shouldBe 1
                    // An unfiltered window opens while the uncaptured run is held: it would admit ANY new request.
                    capture = start(RUNNING_OFF)
                    for (replica in listOf(port, portB)) {
                        val retry =
                            mutate(writer, RUNNING_OFF, "running-off", body(sel, "t-run-off"), replica, UUID.randomUUID().toString())
                        withClue(retry.asString()) { retry.statusCode() shouldBe 202 }
                        retry.let(::executionIdOf) shouldBe id
                    }
                    decisionOf(id) shouldBe "none"
                    id to first
                }
            first.get(FUTURE_S, TimeUnit.SECONDS).let(::executionIdOf) shouldBe id
            awaitEnded(id)
            decisionOf(id) shouldBe "none"
            captureRows(id) shouldBe 0
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
            // The control: the same open window does capture a NEW request.
            decisionOf(mutate(writer, RUNNING_OFF, "running-off-2", body(marker("SEL-RUN-OFF2"), "t"))) shouldBe capture
        } finally {
            pool.shutdownNow()
            capture?.let { stopRaw(RUNNING_OFF, it) }
        }
    }

    // ------------------------------------------------------------------- the run outlives its window

    @Test
    fun `a run admitted in the window that continues past its natural expiry keeps its decision until its detail deadline`() {
        val sel = marker("SEL-OUT")
        val capture = start(OUTLIVES)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val (id, answers) =
                holdingTarget {
                    val first = pool.submit<Response> { mutate(writer, OUTLIVES, "outlives-1", body(sel, "t-out")) }
                    awaitTargetWaiters(BUSINESS_WRITE_SQL, 1)
                    val id = reservedExecution(OUTLIVES)
                    // The window's natural deadline passes while the admitted run is held (moved, as a clock would).
                    sql(
                        "UPDATE execution_capture_sessions SET started_at = started_at - interval '2 hours', " +
                            "deadline_at = now() - interval '1 second' WHERE id = '$capture'",
                    )
                    // A new request after the deadline is admitted uncaptured; it too waits on the held target.
                    val later = pool.submit<Response> { mutate(writer, OUTLIVES, "outlives-2", body(marker("SEL-OUT2"), "t")) }
                    awaitTargetWaiters(BUSINESS_WRITE_SQL, 2)
                    id to (first to later)
                }
            val (first, later) = answers
            first.get(FUTURE_S, TimeUnit.SECONDS).let(::executionIdOf) shouldBe id
            decisionOf(later.get(FUTURE_S, TimeUnit.SECONDS)) shouldBe "none"
            awaitEnded(id)
            awaitSealed(id)
            decisionOf(id) shouldBe capture
            val read = detail(id)
            withClue(read.asString()) { read.statusCode() shouldBe 200 }
            read.jsonPath().getString("data.capture_status") shouldBe "complete"
            read.jsonPath().getList<String>("data.values.value_json") shouldContainExactlyInAnyOrder listOf("\"$sel\"", "\"t-out\"")
            text(
                "SELECT extract(epoch FROM capture_expires_at - created_at)::bigint FROM published_mutation_reservations " +
                    "WHERE execution_id = '$id'",
            ) shouldBe DETAIL_RETENTION_S
            // Until — and only until — its own detail deadline.
            sql("UPDATE published_mutation_reservations SET capture_expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
            sql("UPDATE execution_capture_values SET expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
            sql("UPDATE execution_capture_runs SET expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
            detail(id).statusCode() shouldBe 410
        } finally {
            pool.shutdownNow()
            stopRaw(OUTLIVES, capture)
        }
    }

    @Test
    fun `a run still going at its detail deadline writes no summary after it, and its detail reads as expired`() {
        val sel = marker("SEL-DL")
        val capture = start(DEADLINE)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val (id, first) =
                holdingTarget {
                    val first = pool.submit<Response> { mutate(writer, DEADLINE, "deadline-1", body(sel, "t-dl")) }
                    awaitTargetWaiters(BUSINESS_WRITE_SQL, 1)
                    val id = reservedExecution(DEADLINE)
                    awaitCount("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'", 2)
                    // The detail deadline passes while the run is held in its business write (moved, as a clock would).
                    sql(
                        "UPDATE published_mutation_reservations SET capture_expires_at = now() - interval '1 second' " +
                            "WHERE execution_id = '$id'",
                    )
                    sql("UPDATE execution_capture_values SET expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
                    id to first
                }
            first.get(FUTURE_S, TimeUnit.SECONDS).let(::executionIdOf) shouldBe id
            awaitEnded(id)
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
            // The summary the drain tried after the deadline was refused at storage time; give it the time it needs.
            Thread.sleep(SETTLE_MS)
            count("SELECT count(*) FROM execution_capture_runs WHERE execution_id = '$id'") shouldBe 0
            detail(id).statusCode() shouldBe 410
            val retention = context.getBean("executionCaptureRetention")
            retention.javaClass.getMethod("retainOnce").invoke(retention)
            captureRows(id) shouldBe 0
        } finally {
            pool.shutdownNow()
            stopRaw(DEADLINE, capture)
        }
    }

    // --------------------------------------------------------------------------- PUT and DELETE

    @Test
    fun `a PUT route captures its declared outputs like a POST`() {
        val sel = marker("SEL-PUT")
        val capture = start(PUT_KEY)
        try {
            val answered = mutate(writer, PUT_PATH, "put-1", body(sel, "t-put"), method = "PUT")
            withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
            val id = answered.let(::executionIdOf)
            decisionOf(id) shouldBe capture
            awaitSealed(id)
            val read = detail(id)
            withClue(read.asString()) { read.statusCode() shouldBe 200 }
            read.jsonPath().getString("data.capture_status") shouldBe "complete"
            read.jsonPath().getList<String>("data.values.value_json") shouldContainExactlyInAnyOrder listOf("\"$sel\"", "\"t-put\"")
            targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
        } finally {
            stop(PUT_KEY, capture)
        }
    }

    @Test
    fun `a bodyless DELETE with no value output is captured with an empty value set and a complete status`() {
        val capture = start(DELETE_KEY)
        try {
            val answered = mutate(writer, DELETE_PATH, "delete-1", body = null, method = "DELETE")
            withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
            val id = answered.let(::executionIdOf)
            decisionOf(id) shouldBe capture
            awaitSealed(id)
            val read = detail(id)
            withClue(read.asString()) { read.statusCode() shouldBe 200 }
            read.header("Cache-Control") shouldContain "no-store"
            read.jsonPath().getString("data.execution_status") shouldBe "SUCCESS"
            read.jsonPath().getString("data.capture_status") shouldBe "complete"
            read.jsonPath().getList<Any>("data.values") shouldHaveSize 0
            text(
                "SELECT values_captured || '/' || values_omitted || '/' || write_failures " +
                    "FROM execution_capture_runs WHERE execution_id = '$id'",
            ) shouldBe "0/0/0"
        } finally {
            stop(DELETE_KEY, capture)
        }
    }

    // ---------------------------------------------------------------------- slow diagnostic storage

    @Test
    fun `diagnostic storage held during the run - the answer arrives while a value write still waits, one business change`() {
        val sel = marker("SEL-SLOW")
        val capture = start(SLOW_STORE)
        try {
            val (id, pending) =
                holdingTable("execution_capture_values") {
                    val answered = mutate(writer, SLOW_STORE, "slow-1", body(sel, "t-slow"))
                    // The answer is here, and the diagnostic write it would have waited on is still queued on the lock.
                    val pending =
                        count(
                            "SELECT count(*) FROM pg_stat_activity " +
                                "WHERE wait_event_type = 'Lock' AND ltrim(query) LIKE '$CAPTURE_WRITE_SQL%'",
                        )
                    withClue(answered.asString()) { answered.statusCode() shouldBe 200 }
                    val id = answered.let(::executionIdOf)
                    targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
                    // A retry while storage is still held replays the one execution: no second business change.
                    mutate(writer, SLOW_STORE, "slow-1", body(sel, "t-slow"), portB).let(::executionIdOf) shouldBe id
                    targetCount("SELECT count(*) FROM cap_rows WHERE picked = '$sel'") shouldBe 1
                    id to pending
                }
            withClue("diagnostic writes still waiting when the answer arrived") { (pending >= 1) shouldBe true }
            awaitSealed(id)
            decisionOf(id) shouldBe capture
            count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id' AND status = 'SUCCESS'") shouldBe 1
            val status = detail(id).jsonPath().getString("data.capture_status")
            status shouldBeIn listOf("complete", "incomplete")
            val failures = text("SELECT write_failures FROM execution_capture_runs WHERE execution_id = '$id'").toInt()
            val kept = count("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'")
            withClue("status $status, $kept kept, $failures failed") {
                (kept + failures) shouldBe 2
                (status == "complete") shouldBe (failures == 0)
            }
        } finally {
            stop(SLOW_STORE, capture)
        }
    }

    // ------------------------------------------------------------------------- identity and access

    @Test
    fun `a recreated route with the same URL does not inherit the old route's window`() {
        val capture = start(RECREATED)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .delete("/api/v1/endpoints?path=$RECREATED&method=POST")
            .then()
            .statusCode(204)
        val recreated = publish(RECREATED, "POST", workspace = null)
        recreated shouldNotBe routes.getValue(RECREATED)
        grant(writer, RECREATED, "POST", workspace = null)
        awaitRoute(RECREATED)

        decisionOf(mutate(writer, RECREATED, "recreated-1", body(marker("SEL-NEW"), "t"))) shouldBe "none"
        // The old window is still the OLD route's, and listing the new route shows no window at all.
        text("SELECT endpoint_id::text FROM execution_capture_sessions WHERE id = '$capture'") shouldBe routes.getValue(RECREATED)
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/$recreated/captures")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList<Any>("data.captures") shouldHaveSize 0
    }

    @Test
    fun `two workspaces with overlapping route text - a window in one captures nothing in the other, and a read across is absent`() {
        val capture = start(OVERLAP)
        try {
            val inA = mutate(writer, OVERLAP, "overlap-a", body(marker("SEL-OA"), "t"))
            val inB = mutate(otherWriter, OVERLAP_IN_B, "overlap-b", body(marker("SEL-OB"), "t"))
            withClue(inB.asString()) { inB.statusCode() shouldBe 200 }
            decisionOf(inA) shouldBe capture
            decisionOf(inB) shouldBe "none"
            // The same administrator selecting the other workspace reads A's execution as absent, never forbidden.
            val across =
                given()
                    .port(port)
                    .asSession(ADMIN_SESSION)
                    .header(WORKSPACE_HEADER, OTHER_WORKSPACE)
                    .get("/api/v1/executions/${inA.let(::executionIdOf)}/capture")
            across.statusCode() shouldBe 404
            across.jsonPath().getString("error.code") shouldBe "execution.capture.not_found"
        } finally {
            stop(OVERLAP, capture)
        }
    }

    @Test
    fun `a key is refused every capture surface - an administrator-role mcp key and the route's own caller key`() {
        val id = routes.getValue(MAIN)
        val surfaces =
            listOf(
                "GET" to "/api/v1/endpoints/$id/captures",
                "POST" to "/api/v1/endpoints/$id/captures",
                "GET" to "/api/v1/executions/${UUID.randomUUID()}/capture",
            )
        for (key in listOf(adminKey, writer)) {
            for ((method, path) in surfaces) {
                val refused = given().port(port).header(API_KEY_HEADER, key.plaintext).request(method, path)
                withClue("${key.id} $method $path ${refused.asString()}") {
                    refused.statusCode() shouldBe 403
                    // The FIRST guard a key meets on REST is the kind confinement (auth.md §7.7), before any permission
                    // or handler: every key kind is off its surface here. The two capture-specific layers behind it —
                    // the session-only rule of the key-role column and the handler's session check — have their own
                    // witnesses (RoleMatrixTest, ExecutionCaptureControllerTest).
                    refused.jsonPath().getString("error.code") shouldBe "endpoint.key_kind_refused"
                }
            }
        }
        count("SELECT count(*) FROM execution_capture_sessions WHERE endpoint_id = '$id' AND stopped_at IS NULL") shouldBe 0
    }

    @Test
    fun `a revoked administrator role loses the capture surfaces within the membership cache TTL, and a restored one regains them`() {
        val capture = start(REVOKED)
        val id = mutate(writer, REVOKED, "revoked-1", body(marker("SEL-V"), "t")).let(::executionIdOf)
        try {
            // A membership changed in the database (not through the product, which would evict at once) takes effect
            // when this instance's cached membership expires — auth.md §11.4, one TTL (1 s here).
            sql("UPDATE workspace_members SET role = 'author' WHERE workspace_id = '$DEFAULT_WORKSPACE' AND user_id = '$ADMIN_USER'")
            awaitDetailStatus(id, 403)
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints/${routes.getValue(REVOKED)}/captures")
                .statusCode() shouldBe
                403
            stopRaw(REVOKED, capture).statusCode() shouldBe 403
        } finally {
            sql(
                "UPDATE workspace_members SET role = 'workspace_admin' " +
                    "WHERE workspace_id = '$DEFAULT_WORKSPACE' AND user_id = '$ADMIN_USER'",
            )
        }
        awaitDetailStatus(id, 200)
        stop(REVOKED, capture)
    }

    private fun awaitDetailStatus(
        id: String,
        status: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REVOCATION_WAIT_S)
        var last = 0
        while (System.nanoTime() < deadline) {
            last = detail(id).statusCode()
            if (last == status) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("the capture read never answered $status within ${REVOCATION_WAIT_S}s (last $last)")
    }

    // --------------------------------------------------------------------------- expiry and purge

    @Test
    fun `expired detail is refused before the purge and physically deleted by the retention step after it`() {
        val capture = start(EXPIRED)
        val id =
            try {
                mutate(writer, EXPIRED, "expired-1", body(marker("SEL-E"), "t")).let(::executionIdOf)
            } finally {
                stop(EXPIRED, capture)
            }
        awaitSealed(id)
        // The stored expiry passes (moved, as a clock would): the read refuses at once, the rows are still there.
        sql("UPDATE published_mutation_reservations SET capture_expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
        sql("UPDATE execution_capture_values SET expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
        sql("UPDATE execution_capture_runs SET expires_at = now() - interval '1 second' WHERE execution_id = '$id'")
        val refused = detail(id)
        refused.statusCode() shouldBe 410
        refused.jsonPath().getString("error.code") shouldBe "execution.capture.expired"
        captureRows(id) shouldBe 3

        // The production step itself, the bean the hourly tick calls (resolved by name: this module does not depend
        // on web, the SampleDataBootstrapE2eTest pattern).
        val retention = context.getBean("executionCaptureRetention")
        val deleted = retention.javaClass.getMethod("retainOnce").invoke(retention) as Int
        withClue("rows the step deleted") { (deleted >= 3) shouldBe true }
        captureRows(id) shouldBe 0
        detail(id).statusCode() shouldBe 410
        // The ordinary execution summary follows its own clock.
        count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id'") shouldBe 1
    }

    // ------------------------------------------------------------------------------------- helpers

    /** Every surface a marker must be checked on, by name; the private ones only when [read] is given. */
    private fun surfaces(
        id: String,
        answered: Response,
        read: Response?,
        output: CapturedOutput,
    ): List<Pair<String, String>> =
        listOfNotNull(
            "pipeline_executions row" to text("SELECT row_to_json(e)::text FROM pipeline_executions e WHERE execution_id = '$id'"),
            "execution_events" to
                text("SELECT coalesce(string_agg(row_to_json(v)::text, ''), '') FROM execution_events v WHERE execution_id = '$id'"),
            "audit_log" to text("SELECT coalesce(string_agg(row_to_json(a)::text, ''), '') FROM audit_log a"),
            "reservation row" to text("SELECT row_to_json(r)::text FROM published_mutation_reservations r WHERE execution_id = '$id'"),
            "caller answer" to answered.asString(),
            "status poll" to poll(statusUrlOf(answered)).asString(),
            "log output" to output.all,
            read?.let { "private read" to it.asString() },
            read?.let { "private tables" to captureText(id) },
        )

    /** The status URL of an answered run (`status_url`) or of a failed one (`error.details.status_url`). */
    private fun statusUrlOf(answer: Response): String =
        withClue(answer.asString()) {
            checkNotNull(answer.jsonPath().getString("status_url") ?: answer.jsonPath().getString("error.details.status_url")) {
                "no status_url on the answer"
            }
        }

    /** The run's id: the `DP-Execution-Id` header of an answered run, or `error.details.execution_id` of a failed one. */
    private fun executionIdOf(answer: Response): String =
        withClue(answer.asString()) {
            checkNotNull(answer.header(EXECUTION_HEADER) ?: answer.jsonPath().getString("error.details.execution_id")) {
                "no $EXECUTION_HEADER and no error.details.execution_id on the answer"
            }
        }

    private fun captureText(id: String): String =
        text("SELECT coalesce(string_agg(row_to_json(v)::text, ''), '') FROM execution_capture_values v WHERE execution_id = '$id'") +
            text("SELECT coalesce(string_agg(row_to_json(r)::text, ''), '') FROM execution_capture_runs r WHERE execution_id = '$id'")

    private fun captureRows(id: String): Int =
        count("SELECT count(*) FROM execution_capture_values WHERE execution_id = '$id'") +
            count("SELECT count(*) FROM execution_capture_runs WHERE execution_id = '$id'")

    private fun decisionOf(answer: Response): String = decisionOf(executionIdOf(answer))

    private fun decisionOf(id: String): String =
        text("SELECT coalesce(capture_session_id::text, 'none') FROM published_mutation_reservations WHERE execution_id = '$id'")

    /**
     * R2 — one admission: the stored instant lies inside the deciding window's `[started_at, stopped_at or deadline_at)`,
     * and the detail deadline and the replay expiry are both measured from that same instant.
     */
    private fun assertOneAdmission(
        id: String,
        capture: String,
    ) {
        rows(
            "SELECT r.created_at >= s.started_at AND r.created_at < s.deadline_at " +
                "AND (s.stopped_at IS NULL OR r.created_at < s.stopped_at), " +
                "extract(epoch FROM r.capture_expires_at - r.created_at)::bigint, " +
                "extract(epoch FROM r.expires_at - r.created_at)::bigint " +
                "FROM published_mutation_reservations r JOIN execution_capture_sessions s ON s.id = r.capture_session_id " +
                "WHERE r.execution_id = '$id' AND s.id = '$capture'",
        ) shouldBe listOf(listOf("t", DETAIL_RETENTION_S, replaySeconds()))
    }

    private fun replaySeconds(): String = checkNotNull(context.environment.getProperty("datapipelines.idempotency.ttl-seconds"))

    /** The one execution reserved on route [path] so far (each of these routes is called once before it is read). */
    private fun reservedExecution(path: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (System.nanoTime() < deadline) {
            val ids =
                rows("SELECT execution_id::text FROM published_mutation_reservations WHERE endpoint_id = '${routes.getValue(path)}'")
            if (ids.isNotEmpty()) return checkNotNull(ids.single().single())
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("no reservation on $path")
    }

    /** Holds the business target `cap_rows` exclusively on this test's own connection: every run's write waits on it. */
    private fun <T> holdingTarget(block: () -> T): T =
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE cap_rows IN ACCESS EXCLUSIVE MODE") }
            try {
                block()
            } finally {
                holder.commit()
            }
        }

    /** Holds metadata table [table] exclusively on this test's own connection: every write to it waits on the lock. */
    private fun <T> holdingTable(
        table: String,
        block: () -> T,
    ): T =
        connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE $table IN ACCESS EXCLUSIVE MODE") }
            try {
                block()
            } finally {
                holder.commit()
            }
        }

    private fun awaitTargetWaiters(
        prefix: String,
        waiters: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_S)
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen =
                targetCount("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ltrim(query) LIKE '$prefix%'")
            if (seen >= waiters) return
            Thread.sleep(LOCK_POLL_MS)
        }
        throw AssertionError("expected $waiters statement(s) '$prefix…' waiting on the held target, saw $seen")
    }

    private fun awaitCount(
        sql: String,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen = count(sql)
            if (seen == expected) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("expected $expected from '$sql', saw $seen")
    }

    /** [CONTENDERS] identical requests at once, alternating replicas; returns every answer's execution id. */
    private fun contend(
        path: String,
        idempotencyKey: String,
        body: String,
    ): List<String> {
        val pool = Executors.newFixedThreadPool(CONTENDERS)
        try {
            val futures: List<Future<Response>> =
                (0 until CONTENDERS).map { i ->
                    pool.submit<Response> { mutate(writer, path, idempotencyKey, body, if (i % 2 == 0) port else portB) }
                }
            return futures.map { f ->
                val answer = f.get(FUTURE_S, TimeUnit.SECONDS)
                // A contender that joins the run while it is still going answers 202 with the same execution id.
                withClue(answer.asString()) { answer.statusCode() shouldBeIn listOf(200, 202) }
                answer.let(::executionIdOf)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Holds the window row [capture] `FOR UPDATE` on this test's own connection while [queue] submits work and waits for
     * each statement to be observed waiting on a lock (on a separate connection); then releases it, unchanged.
     */
    private fun <T> holdingWindowRow(
        capture: String,
        queue: ((String, Int) -> Unit) -> T,
    ): T =
        connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT id FROM execution_capture_sessions WHERE id = '$capture' FOR UPDATE") }
            try {
                queue { prefix, waiters -> awaitLockWaiters(prefix, waiters) }
            } finally {
                holder.commit()
            }
        }

    private fun awaitLockWaiters(
        prefix: String,
        waiters: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_S)
        var seen = 0
        while (System.nanoTime() < deadline) {
            seen = count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ltrim(query) LIKE '$prefix%'")
            if (seen >= waiters) return
            Thread.sleep(LOCK_POLL_MS)
        }
        throw AssertionError("expected $waiters statement(s) '$prefix…' waiting on the held window row, saw $seen")
    }

    private fun start(
        path: String,
        correlation: String? = null,
    ): String {
        val opened = startRaw(path, correlation)
        withClue(opened.asString()) { opened.statusCode() shouldBe 201 }
        opened.header("Cache-Control") shouldContain "no-store"
        return opened.jsonPath().getString("data.capture_id")
    }

    private fun startRaw(
        path: String,
        correlation: String?,
    ): Response {
        val spec = given().port(port).asSession(ADMIN_SESSION)
        correlation?.let { spec.contentType(ContentType.JSON).body("""{"correlation_id": "$it"}""") }
        return spec.post("/api/v1/endpoints/${routes.getValue(path)}/captures").thenReturn()
    }

    private fun stop(
        path: String,
        capture: String,
    ): Response = stopRaw(path, capture).also { withClue(it.asString()) { it.statusCode() shouldBe 200 } }

    private fun stopRaw(
        path: String,
        capture: String,
    ): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("/api/v1/endpoints/${routes.getValue(path)}/captures/$capture/stop")
            .thenReturn()

    private fun list(path: String) =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/endpoints/${routes.getValue(path)}/captures")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()

    private fun detail(id: String): Response =
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .get("/api/v1/executions/$id/capture")
            .thenReturn()

    private fun mutate(
        key: Key,
        path: String,
        idempotencyKey: String,
        body: String?,
        replicaPort: Int = port,
        correlation: String? = null,
        method: String = "POST",
    ): Response {
        val spec =
            given()
                .port(replicaPort)
                .header(API_KEY_HEADER, key.plaintext)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
        body?.let { spec.contentType(ContentType.JSON).body(it) }
        correlation?.let { spec.header(CORRELATION_HEADER, it) }
        return spec.request(method, "/api$path").thenReturn()
    }

    private fun poll(url: String): Response =
        given()
            .port(port)
            .header(API_KEY_HEADER, writer.plaintext)
            .get(url)
            .thenReturn()

    private fun body(
        selected: String,
        tag: String,
        raw: String = "raw-unused",
    ): String = mapper.writeValueAsString(mapOf("selected" to selected, "tag" to tag, "raw" to raw))

    private fun marker(prefix: String): String = "$prefix-1043-${UUID.randomUUID().toString().take(8)}"

    /** Waits until execution [id] is no longer RUNNING — the business write has committed or failed. */
    private fun awaitEnded(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id' AND status <> 'RUNNING'") == 1) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("execution $id never ended")
    }

    /** The closing summary is written after the run, off the request thread; wait for it. */
    private fun awaitSealed(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM execution_capture_runs WHERE execution_id = '$id'") == 1) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("execution $id was never sealed")
    }

    private fun awaitStatus(
        id: String,
        status: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (System.nanoTime() < deadline) {
            if (text("SELECT coalesce(max(status), '') FROM pipeline_executions WHERE execution_id = '$id'") == status) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("execution $id never reached $status")
    }

    /** Waits until a freshly published route answers on both replicas (the route registry reloads on publish). */
    private fun awaitRoute(path: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_S)
        while (System.nanoTime() < deadline) {
            val probe =
                given()
                    .port(port)
                    .header(API_KEY_HEADER, writer.plaintext)
                    .get("/api$path")
                    .thenReturn()
            if (probe.statusCode() == 405) return
            Thread.sleep(POLL_MS)
        }
    }

    private fun connect(): Connection =
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password)

    private fun count(sql: String): Int = text(sql).toInt()

    private fun text(sql: String): String = rows(sql).firstOrNull()?.firstOrNull().orEmpty()

    private fun rows(sql: String): List<List<String?>> =
        connect().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val columns = rs.metaData.columnCount
                    generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
                }
            }
        }

    private fun sql(statement: String) {
        connect().use { c -> c.createStatement().use { it.execute(statement) } }
    }

    private fun targetCount(sql: String): Int =
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    /** Runs [block] with a trigger refusing [timing] on [table] when [condition] holds; the trigger is always dropped. */
    private fun <T> trigger(
        name: String,
        table: String,
        timing: String,
        condition: String,
        block: () -> T,
    ): T {
        sql(
            "CREATE OR REPLACE FUNCTION $name() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN RAISE EXCEPTION 'injected fault $name'; END \$\$",
        )
        sql("CREATE TRIGGER $name $timing ON $table FOR EACH ROW WHEN ($condition) EXECUTE FUNCTION $name()")
        try {
            return block()
        } finally {
            sql("DROP TRIGGER IF EXISTS $name ON $table")
            sql("DROP FUNCTION IF EXISTS $name()")
        }
    }

    // ---------------------------------------------------------------------------------------- seed

    private fun bootReplicaB() {
        replicaB =
            SpringApplicationBuilder(DatapipelinesApplication::class.java).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${SharedE2e.postgres.jdbcUrl}",
                "--spring.datasource.username=${SharedE2e.postgres.username}",
                "--spring.datasource.password=${SharedE2e.postgres.password}",
                "--datapipelines.redis.password=",
                "--datapipelines.redis.host=${SharedE2e.redisHost}",
                "--datapipelines.redis.port=${SharedE2e.redisPort}",
                "--datapipelines.jwt.secret=$JWT_SECRET",
                "--datapipelines.db.encryption-key=$ENCRYPTION_KEY",
                "--datapipelines.auth.local.enabled=true",
                "--datapipelines.endpoints.mutations-enabled=true",
                "--datapipelines.script.max-value-bytes=256",
                "--datapipelines.endpoints.key-request-budget.max-requests=$KEY_BUDGET",
                "--datapipelines.auth.api-keys.cache-ttl-seconds=$AUTH_CACHE_TTL_S",
            )
        portB = Integer.parseInt(checkNotNull(replicaB?.environment?.getProperty("local.server.port")))
    }

    private fun seedRows() {
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                "VALUES ('$ADMIN_USER', '$ADMIN_EMAIL', 'Cap Admin', 'test', 'cap1043-admin', TRUE, FALSE)",
        )
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER', 'workspace_admin')")
        sql(
            "INSERT INTO workspaces (id, name, display_name, created_by) " +
                "VALUES ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'Capture other workspace', '$ADMIN_USER')",
        )
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$OTHER_WORKSPACE_ID', '$ADMIN_USER', 'workspace_admin')")
    }

    private fun seedTarget() {
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE IF NOT EXISTS cap_rows (picked text NOT NULL, tagged text NOT NULL, " +
                        "CONSTRAINT $ERROR_MARKER CHECK (tagged <> 'boom'))",
                )
                s.execute("TRUNCATE cap_rows")
            }
        }
    }

    /**
     * Registering a datasource is a super administrator's (member-datasources-enabled is off by default), so the admin
     * holds it for this one call only: every capture case runs as a plain workspace administrator, which the
     * role-revocation case depends on.
     */
    private fun registerDatasource() {
        sql("UPDATE users SET is_admin = TRUE WHERE id = '$ADMIN_USER'")
        val registered =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    """
                    {"name": "$DATASOURCE", "display_name": "Capture 1043", "dialect": "POSTGRES",
                     "jdbc_url": "${target.jdbcUrl}", "username": "${target.username}", "password": "${target.password}"}
                    """.trimIndent(),
                ).post("/api/v1/datasources")
                .thenReturn()
        sql("UPDATE users SET is_admin = FALSE WHERE id = '$ADMIN_USER'")
        withClue("register $DATASOURCE: ${registered.asString()}") { registered.statusCode() shouldBe 201 }
    }

    private fun createTemplates(workspace: String?) {
        scriptTemplate(PICK_TEMPLATE, "inputs.order.selected", workspace)
        scriptTemplate(TAG_TEMPLATE, "inputs.order.tag", workspace)
        if (workspace == null) scriptTemplate(DOUBLE_TEMPLATE, "inputs.order.selected & inputs.order.selected", null, expect = "ss")
        if (workspace == null) {
            sqlTemplate(WRITE_TEMPLATE, "INSERT INTO cap_rows (picked, tagged) VALUES (CAST(:picked AS text), CAST(:tagged AS text))")
            sqlTemplate(READ_TEMPLATE, "SELECT count(*) AS n FROM cap_rows")
            sqlTemplate(DELETE_TEMPLATE, "DELETE FROM cap_rows WHERE tagged = '$NEVER_TAG'")
        }
    }

    private fun createPipeline(
        name: String,
        withWrite: Boolean,
        workspace: String?,
        tagTemplate: String = TAG_TEMPLATE,
    ) {
        val pick = valueNode("pick", PICK_TEMPLATE, "picked", emptyList())
        val tag = valueNode("tag", tagTemplate, "tagged", listOf("pick"))
        val write =
            mapOf(
                "id" to "write",
                "description" to "Writes the selected values",
                "type" to "DML",
                "source" to DATASOURCE,
                "template" to mapOf("id" to WRITE_TEMPLATE, "version" to 1),
                "operation" to "update",
                "output" to mapOf("target" to "discard"),
                "depends_on" to listOf("tag"),
            )
        pipeline(name, if (withWrite) listOf(pick, tag, write) else listOf(pick, tag), mapOf("required" to true), workspace)
        if (workspace == null && name == CAP_PIPELINE) {
            val read =
                mapOf(
                    "id" to "read",
                    "description" to "Reads",
                    "type" to "DQL",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to READ_TEMPLATE, "version" to 1),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to emptyList<String>(),
                )
            pipeline(READ_PIPELINE, listOf(read), null, null)
            // The DELETE route's pipeline: no request body, no value-mode transformer — a run with nothing to capture.
            val delete =
                mapOf(
                    "id" to "delete",
                    "description" to "Deletes nothing it was not asked to",
                    "type" to "DML",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to DELETE_TEMPLATE, "version" to 1),
                    "operation" to "update",
                    "output" to mapOf("target" to "discard"),
                    "depends_on" to emptyList<String>(),
                )
            pipeline(DELETE_PIPELINE, listOf(delete), null, null)
        }
    }

    private fun valueNode(
        id: String,
        template: String,
        key: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Selects one body field",
            "type" to "SCRIPT",
            "template" to mapOf("id" to template, "version" to 1),
            "inputs" to mapOf("order" to "@request_body"),
            "context_key" to key,
            "depends_on" to dependsOn,
        )

    private fun mintKeys() {
        writer = mint("cap-writer", "endpoint", null, workspace = null)
        for (path in ROUTES) grant(writer, path, "POST", workspace = null)
        grant(writer, PUT_PATH, "PUT", workspace = null)
        grant(writer, DELETE_PATH, "DELETE", workspace = null)
        otherWriter = mint("cap-other-writer", "endpoint", null, workspace = OTHER_WORKSPACE)
        grant(otherWriter, OVERLAP_IN_B, "POST", workspace = OTHER_WORKSPACE)
        adminKey = mint("cap-admin-mcp", "mcp", "workspace_admin", workspace = null)
    }

    private fun mint(
        name: String,
        kind: String,
        role: String?,
        workspace: String?,
    ): Key {
        val spec = given().port(port).contentType(ContentType.JSON).asSession(ADMIN_SESSION)
        workspace?.let { spec.header(WORKSPACE_HEADER, it) }
        val payload =
            mapOf("name" to name, "kind" to kind) + (role?.let { mapOf("role" to it) } ?: mapOf("bindings" to emptyList<String>()))
        val minted = spec.body(mapper.writeValueAsString(payload)).post("/api/v1/auth/api-keys").thenReturn()
        withClue("mint $name: ${minted.asString()}") { minted.statusCode() shouldBe 201 }
        return Key(minted.jsonPath().getString("data.id"), minted.jsonPath().getString("data.key"))
    }

    private fun grant(
        key: Key,
        path: String,
        method: String,
        workspace: String?,
    ) {
        val spec = given().port(port).contentType(ContentType.JSON).asSession(ADMIN_SESSION)
        workspace?.let { spec.header(WORKSPACE_HEADER, it) }
        spec
            .body("""{"api_key_id": "${key.id}", "path_prefix": "$path", "method": "$method"}""")
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
    }

    /** Publishes [path]/[method] pinning v1 of [pipeline] in [workspace] and releases that version. */
    private fun publish(
        path: String,
        method: String,
        workspace: String?,
        pipeline: String = CAP_PIPELINE,
        release: Boolean = true,
    ): String {
        fun session() = given().port(port).asSession(ADMIN_SESSION).also { s -> workspace?.let { s.header(WORKSPACE_HEADER, it) } }
        val created =
            session()
                .contentType(ContentType.JSON)
                .body(
                    """{"path": "$path", "method": "$method", "pipeline": "$pipeline", """ +
                        """"pipeline_version": 1, "timeout_seconds": $TIMEOUT_S}""",
                ).post("/api/v1/endpoints")
                .thenReturn()
        withClue("publish $method $path: ${created.asString()}") { created.statusCode() shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        if (!release) return id
        val hash =
            session()
                .get("/api/v1/endpoints/$id/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        session()
            .header("If-Match", hash)
            .post("/api/v1/endpoints/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    private fun pipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
        requestBody: Map<String, Any?>?,
        workspace: String?,
    ) {
        val document =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Capture 1043 $name",
                "description" to "#1043 runtime capture fixture",
                "parameters" to emptyMap<String, Any?>(),
                "nodes" to nodes,
            ) + (requestBody?.let { mapOf("request_body" to it) } ?: emptyMap())

        fun session() = given().port(port).asSession(ADMIN_SESSION).also { s -> workspace?.let { s.header(WORKSPACE_HEADER, it) } }
        val created =
            session()
                .contentType(
                    ContentType.JSON,
                ).body(mapper.writeValueAsString(document))
                .post("/api/v1/pipelines")
                .thenReturn()
        withClue("create $name: ${created.asString()}") { created.statusCode() shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        session()
            .header(
                "If-Match",
                created.jsonPath().getString("data.body_hash"),
            ).post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
    }

    private fun scriptTemplate(
        id: String,
        body: String,
        workspace: String?,
        expect: String = "s",
    ) {
        val payload =
            mapOf(
                "id" to id,
                "display_name" to id,
                "description" to "#1043 value transformer",
                "type" to "jsonata",
                "body" to body,
                "contract" to
                    mapOf(
                        "mode" to "value",
                        "inputs" to mapOf("order" to mapOf("kind" to "body")),
                        "output" to mapOf("kind" to "value", "type" to "STRING"),
                    ),
                "invariants" to emptyList<Any>(),
                "tests" to
                    listOf(
                        mapOf(
                            "name" to "selects",
                            "input" to mapOf("inputs" to mapOf("order" to mapOf("selected" to "s", "tag" to "s"))),
                            "expect" to mapOf("output" to expect),
                        ),
                    ),
            )
        createTemplate(id, payload, workspace)
    }

    private fun sqlTemplate(
        id: String,
        body: String,
    ) {
        createTemplate(
            id,
            mapOf(
                "id" to id,
                "dialect" to "POSTGRES",
                "display_name" to id,
                "description" to "#1043 template",
                "imports" to emptyList<Any>(),
                "body" to body,
            ),
            workspace = null,
        )
    }

    private fun createTemplate(
        id: String,
        payload: Map<String, Any?>,
        workspace: String?,
    ) {
        fun session() = given().port(port).asSession(ADMIN_SESSION).also { s -> workspace?.let { s.header(WORKSPACE_HEADER, it) } }
        val created =
            session()
                .contentType(
                    ContentType.JSON,
                ).body(mapper.writeValueAsString(payload))
                .post("/api/v1/templates")
                .thenReturn()
        withClue("template $id: ${created.asString()}") { created.statusCode() shouldBe 201 }
        session()
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private class Key(
        val id: String,
        val plaintext: String,
    )

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val CORRELATION_HEADER = "DP-Correlation-Id"
        private const val EXECUTION_HEADER = "DP-Execution-Id"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val DATASOURCE = "cap1043"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val OTHER_WORKSPACE_ID = "defa0000-0000-0000-0000-000000001043"
        private const val OTHER_WORKSPACE = "cap-other"

        private const val MAIN = "/cap/v1/main"
        private const val OFF = "/cap/v1/off"
        private const val FAILING = "/cap/v1/failing"
        private const val BUDGET = "/cap/v1/budget"
        private const val STORE_FAULT = "/cap/v1/store"
        private const val FILTERED = "/cap/v1/filtered"
        private const val STOPPED = "/cap/v1/stopped"
        private const val REFUSALS = "/cap/v1/refusals"
        private const val TIMED_OUT = "/cap/v1/timeout"
        private const val RACE_STOP = "/cap/v1/race-stop"
        private const val RACE_ADMIT = "/cap/v1/race-admit"
        private const val REPLICAS_ON = "/cap/v1/replicas-on"
        private const val REPLICAS_OFF = "/cap/v1/replicas-off"
        private const val RESTART = "/cap/v1/restart"
        private const val RECREATED = "/cap/v1/recreated"
        private const val OVERLAP = "/cap/v1/overlap"

        /**
         * Workspace B's route: its text CONTAINS A's (identical text is refused deployment-wide, `endpoint.path_conflict`),
         * so a window matched by path text — a prefix or LIKE — instead of by route entity would capture B's run.
         */
        private const val OVERLAP_IN_B = "/cap/v1/overlapping"
        private const val REVOKED = "/cap/v1/revoked"
        private const val EXPIRED = "/cap/v1/expired"
        private const val RUNNING_ON = "/cap/v1/running-on"
        private const val RUNNING_OFF = "/cap/v1/running-off"
        private const val OUTLIVES = "/cap/v1/outlives"
        private const val DEADLINE = "/cap/v1/deadline"
        private const val SLOW_STORE = "/cap/v1/slow-store"
        private const val PUT_PATH = "/cap/v1/put"
        private const val DELETE_PATH = "/cap/v1/delete"

        /** Route-map keys that are not a POST path of workspace A. */
        private const val GET_ROUTE = "GET $MAIN"
        private const val OVERLAP_B = "B $OVERLAP"
        private const val PUT_KEY = "PUT $PUT_PATH"
        private const val DELETE_KEY = "DELETE $DELETE_PATH"

        private val ROUTES =
            listOf(
                MAIN,
                OFF,
                FAILING,
                BUDGET,
                STORE_FAULT,
                FILTERED,
                STOPPED,
                REFUSALS,
                TIMED_OUT,
                RACE_STOP,
                RACE_ADMIT,
                REPLICAS_ON,
                REPLICAS_OFF,
                RESTART,
                RECREATED,
                OVERLAP,
                REVOKED,
                EXPIRED,
                RUNNING_ON,
                RUNNING_OFF,
                OUTLIVES,
                DEADLINE,
                SLOW_STORE,
            )

        private const val CAP_PIPELINE = "test/cap1043_capture"
        private const val READ_PIPELINE = "test/cap1043_read"
        private const val PICK_TEMPLATE = "test/cap1043_pick.jsonata"
        private const val TAG_TEMPLATE = "test/cap1043_tag.jsonata"

        /**
         * The budget route's `tag`: the selected value twice. `max-value-bytes` also caps the request BODY a transformer
         * reads, so only a transformer that enlarges its input can overflow the per-execution capture budget while
         * every single value still passes the output gate.
         */
        private const val DOUBLE_TEMPLATE = "test/cap1043_double.jsonata"
        private const val BUDGET_PIPELINE = "test/cap1043_budget"

        private fun pipelineOf(path: String): String = if (path == BUDGET) BUDGET_PIPELINE else CAP_PIPELINE

        private const val WRITE_TEMPLATE = "test/cap1043_write.sql"
        private const val READ_TEMPLATE = "test/cap1043_read.sql"
        private const val DELETE_TEMPLATE = "test/cap1043_delete.sql"
        private const val DELETE_PIPELINE = "test/cap1043_delete"

        /** A tag no request writes: the DELETE route's run changes nothing and selects nothing. */
        private const val NEVER_TAG = "never-1043"

        /** The target's check constraint name: it reaches the run's error, and must never reach the capture. */
        private const val ERROR_MARKER = "err_mark_1043_constraint"

        /**
         * The admission's window read (`FOR SHARE`) and the stop's window lock (`FOR UPDATE`), as `pg_stat_activity` shows
         * them while they wait on the held window row.
         */
        private const val ADMIT_SQL = "SELECT id, started_at, deadline_at, correlation_id FROM execution_capture_sessions"
        private const val STOP_SQL = "SELECT id FROM execution_capture_sessions WHERE id ="

        /** The business write and a diagnostic value write, as `pg_stat_activity` shows them while a held table blocks them. */
        private const val BUSINESS_WRITE_SQL = "INSERT INTO cap_rows"
        private const val CAPTURE_WRITE_SQL = "INSERT INTO execution_capture_values"

        private const val TIMEOUT_S = 60

        /** `datapipelines.endpoints.capture-detail-retention-hours` at its default: 24 h from admission, in seconds. */
        private const val DETAIL_RETENTION_S = "86400"

        /** How long the deadline case lets the drain attempt its refused summary after the run ended. */
        private const val SETTLE_MS = 3000L
        private const val CONTENDERS = 4
        private const val FUTURE_S = 90L
        private const val LOCK_WAIT_S = 30L
        private const val LOCK_POLL_MS = 50L
        private const val AWAIT_S = 60L
        private const val POLL_MS = 100L
        private const val KEY_BUDGET = "100000"
        private const val SELECTED_CHARS = 110

        /** Membership is cached per instance for this long (auth.md §11.4); the revocation case waits at most a few TTLs. */
        private const val AUTH_CACHE_TTL_S = "1"
        private const val REVOCATION_WAIT_S = 15L

        private val ADMIN_USER: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "cap1043-admin@datapipelines.test"
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, ADMIN_EMAIL)

        private val target = SharedE2e.scratchDatabase("capture1043_target")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.endpoints.key-request-budget.max-requests") { KEY_BUDGET }
            registry.add("datapipelines.auth.api-keys.cache-ttl-seconds") { AUTH_CACHE_TTL_S }
        }

        /** One key for both replicas: a datasource password replica A encrypted must decrypt on replica B. */
        private val ENCRYPTION_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    }
}
