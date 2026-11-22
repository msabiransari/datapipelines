package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
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
import org.springframework.test.util.ReflectionTestUtils
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * #1039 — published POST, PUT and DELETE served for real, end to end over HTTP, against a real PostgreSQL target
 * (published-mutation-apis §2–§7; rest-api §19).
 *
 * Everything goes through the product's own surfaces: templates, pipelines and routes over the session REST API,
 * grants over `POST /api/v1/endpoints/bindings`, calls on `/api/mut/v1/…` with `endpoint` keys. Every durable claim is
 * read back on this test's own JDBC connections — the target's rows, `pipeline_executions`, the reservation table and
 * `audit_log` — never inferred from an answer alone.
 *
 * Faults are injected with ordinary PostgreSQL triggers scoped to one business path of this suite (a refused
 * reservation insert, a refused execution insert plus a refused release), so a sibling suite on the shared database
 * is never touched.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // A script pool of one permit and one queue slot, so the pool-exhaustion case can fill it deterministically.
    properties = [
        "datapipelines.endpoints.mutations-enabled=true",
        "datapipelines.script.pool-size=1",
        "datapipelines.script.pool-queue=1",
    ],
)
@Suppress("LargeClass") // one route family over one seed; the cases share it rather than re-seeding
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension::class)
class PublishedMutationServeIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()

    private lateinit var writer: Key
    private lateinit var reader: Key
    private lateinit var foreign: Key
    private val routes = mutableMapOf<String, String>()

    /** Replica B: a second application context on the same metadata database and Redis — another instance. */
    private var replicaB: ConfigurableApplicationContext? = null
    private var portB: Int = 0
    private val pipelines = mutableMapOf<String, String>()

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        seedTarget()
        registerDatasource()
        createTemplates()
        createPipelines()
        publishRoutes()
        mintKeys()
        bootReplicaB()
    }

    @AfterAll
    fun closeReplicaB() {
        replicaB?.close()
    }

    // -----------------------------------------------------------------------------------------
    // Commit once; GET unchanged
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the REST management read answers each route's exact pin and its body contract - the GET sibling reads none`() {
        // #1040: what an author must see before acting on a route, on the REST surface (the MCP tools answer the same).
        fun read(method: String) =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .queryParam("path", ORDERS)
                .queryParam("method", method)
                .get("/api/v1/endpoints")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        val post = read("POST")
        post.getString("data.method") shouldBe "POST"
        post.getInt("data.pipeline_version") shouldBe 1
        post.getMap<String, Any?>("data.request_body") shouldBe
            mapOf("mode" to "required", "readers" to listOf(mapOf("node_id" to "shape", "template" to "$LINES_TEMPLATE@1")))
        read("GET").getMap<String, Any?>("data.request_body") shouldBe mapOf("mode" to "none", "readers" to emptyList<Any>())
    }

    @Test
    fun `a write-capable POST commits once, answers 200 with its result, and a retry replays it without rerunning`() {
        val first = mutate(writer, "POST", ORDERS, "commit-once-1", order("CO-1"))
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        first.header("DP-Execution-Id") shouldBe id
        first.jsonPath().getString("status") shouldBe "SUCCESS"
        first.jsonPath().getList<Any>("rows") shouldNotBe null
        first.jsonPath().getString("status_url") shouldBe "/api$ORDERS/executions/$id"
        first.jsonPath().getString("result_url") shouldBe "/api$ORDERS/executions/$id/result"
        skus("CO-1") shouldBe 1

        val retry = mutate(writer, "POST", ORDERS, "commit-once-1", order("CO-1"))
        withClue(retry.asString()) { retry.statusCode() shouldBe 200 }
        retry.jsonPath().getString("execution_id") shouldBe id
        skus("CO-1") shouldBe 1
        executionsOf(ORDERS_PIPELINE, id) shouldBe 1
    }

    @Test
    fun `a published GET is unchanged - a repeated GET with a key is a fresh execution and never reserves`() {
        val before = count("SELECT count(*) FROM published_mutation_reservations")
        val ids =
            (1..2).map {
                val response =
                    given()
                        .port(port)
                        .header(API_KEY_HEADER, reader.plaintext)
                        .header(IDEMPOTENCY_HEADER, "get-is-not-replayed")
                        .`when`()
                        .get("/api$ORDERS")
                withClue(response.asString()) { response.statusCode() shouldBe 200 }
                response.header("DP-Execution-Id")
            }
        ids[0] shouldNotBe ids[1]
        count("SELECT count(*) FROM published_mutation_reservations") shouldBe before
    }

    // -----------------------------------------------------------------------------------------
    // The key, the body and the envelope
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a missing or malformed key is refused 400 on POST, PUT and DELETE - bodyless and structured - before anything runs`() {
        val reservations = count("SELECT count(*) FROM published_mutation_reservations")
        val executions = count("SELECT count(*) FROM pipeline_executions")
        val cases =
            listOf(
                Triple("POST", ORDERS, order("KEY-1")),
                Triple("DELETE", ORDERS, order("KEY-2")),
                Triple("PUT", HITS, null),
                Triple("DELETE", HITS, null),
            )
        for ((method, path, body) in cases) {
            for ((key, reason) in listOf(null to "missing", "has space" to "malformed", "x".repeat(129) to "malformed")) {
                val response = mutate(writer, method, path, key, body)
                withClue("$method $path key=$key ${response.asString()}") {
                    response.statusCode() shouldBe 400
                    response.jsonPath().getString("error.code") shouldBe "endpoint.request.invalid"
                    response.jsonPath().getString("error.details.errors[0].code") shouldBe "endpoint.request.idempotency_key_invalid"
                    response.jsonPath().getString("error.details.errors[0].reason") shouldBe reason
                    key?.let { response.asString() shouldNotContain it }
                }
            }
        }
        count("SELECT count(*) FROM published_mutation_reservations") shouldBe reservations
        count("SELECT count(*) FROM pipeline_executions") shouldBe executions
        skus("KEY-1") shouldBe 0
        skus("KEY-2") shouldBe 0
    }

    @Test
    fun `a bodyless DELETE and a structured DELETE both run once under their key`() {
        val before = hits(HITS)
        val bodyless = mutate(writer, "DELETE", HITS, "delete-bodyless-1", null)
        withClue(bodyless.asString()) { bodyless.statusCode() shouldBe 200 }
        hits(HITS) shouldBe before + 1

        val structured = mutate(writer, "DELETE", ORDERS, "delete-structured-1", order("DEL-1"))
        withClue(structured.asString()) { structured.statusCode() shouldBe 200 }
        skus("DEL-1") shouldBe 1

        // The bodyless route takes no body; the structured one needs one — the pin's own rules, 400, nothing run.
        val withBody = mutate(writer, "DELETE", HITS, "delete-bodyless-2", order("DEL-2"))
        withClue(withBody.asString()) {
            withBody.statusCode() shouldBe 400
            withBody.jsonPath().getString("error.details.errors[0].reason") shouldBe "not_accepted"
        }
        val without = mutate(writer, "DELETE", ORDERS, "delete-structured-2", null)
        withClue(without.asString()) {
            without.statusCode() shouldBe 400
            without.jsonPath().getString("error.details.errors[0].reason") shouldBe "required"
        }
    }

    @Test
    fun `a repeated PUT and DELETE under distinct keys are separate audited executions with one stable final state`() {
        val calls = listOf("PUT" to "stable-put-1", "PUT" to "stable-put-2", "DELETE" to "stable-del-1", "DELETE" to "stable-del-2")
        val answers = calls.map { (method, key) -> mutate(writer, method, STABLE, key, null) }
        answers.forEach { withClue(it.asString()) { it.statusCode() shouldBe 200 } }
        val ids = answers.map { it.jsonPath().getString("execution_id") }
        ids.toSet() shouldHaveSize calls.size

        // The author's idempotent statements: PUT sets the same state twice, the second DELETE finds nothing left.
        answers.map {
            it
                .jsonPath()
                .getList<List<Any?>>("rows")
                .single()
                .single()
                .toString()
        } shouldBe
            listOf("1", "1", "1", "0")
        targetCount("SELECT count(*) FROM mut_state WHERE name = 'put-target' AND state = 'set'") shouldBe 1
        targetCount("SELECT count(*) FROM mut_state WHERE name = 'delete-target'") shouldBe 0

        // Each call has its own audit trail naming the caller, method and route — and never the request key.
        calls.zip(ids).forEach { (call, id) ->
            val (method, key) = call
            val audit =
                rows(
                    "SELECT key_id, details_json->>'method', details_json->>'endpoint_id', details_json->>'outcome', " +
                        "details_json::text FROM audit_log WHERE event = 'endpoint.served' " +
                        "AND details_json->>'execution_id' = '$id' ORDER BY id",
                )
            withClue("$method $id $audit") {
                audit.map { it[3] } shouldContain "completed"
                audit.forEach { row ->
                    row[0] shouldBe writer.id
                    row[1] shouldBe method
                    row[2] shouldBe routes.getValue("$STABLE $method")
                    row[4].orEmpty() shouldNotContain key
                }
            }
        }
    }

    @Test
    fun `the envelope answers 405, 413 and 415 before anything is reserved`() {
        val reservations = count("SELECT count(*) FROM published_mutation_reservations")
        val notGranted =
            given()
                .port(port)
                .header(API_KEY_HEADER, reader.plaintext)
                .header(IDEMPOTENCY_HEADER, "env-405")
                .`when`()
                .put("/api$ORDERS")
        withClue(notGranted.asString()) {
            notGranted.statusCode() shouldBe 405
            notGranted.header("Allow") shouldBe "GET, POST"
        }

        val tooLarge = mutate(writer, "POST", ORDERS, "env-413", """{"sku":"BIG","qty":1,"note":"${"x".repeat(BODY_MAX_BYTES)}"}""")
        withClue(tooLarge.asString()) {
            tooLarge.statusCode() shouldBe 413
            tooLarge.jsonPath().getString("error.code") shouldBe "endpoint.request.too_large"
        }

        val plain =
            base(writer, "env-415a")
                .contentType("text/plain")
                .body("sku=X")
                .`when`()
                .post("/api$ORDERS")
        withClue(plain.asString()) {
            plain.statusCode() shouldBe 415
            plain.jsonPath().getString("error.code") shouldBe "endpoint.request.unsupported_media_type"
        }
        val form =
            base(writer, "env-415b")
                .contentType(ContentType.URLENC)
                .formParam("sku", "X")
                .`when`()
                .put("/api$HITS")
        withClue(form.asString()) {
            form.statusCode() shouldBe 415
            form.jsonPath().getString("error.details.reason") shouldBe "media_type"
        }
        count("SELECT count(*) FROM published_mutation_reservations") shouldBe reservations

        // A client library that labels a BODYLESS call as a form (declared length 0) sent nothing: it runs, once.
        val labelled = base(writer, "env-empty-form").contentType(ContentType.URLENC).`when`().put("/api$HITS")
        withClue(labelled.asString()) { labelled.statusCode() shouldBe 200 }
    }

    // -----------------------------------------------------------------------------------------
    // Idempotency: changed input, concurrency, version switch, store failure, takeover
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the same key with a different input is 409 and returns no execution`() {
        val first = mutate(writer, "POST", ORDERS, "changed-input-1", order("CI-1"))
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val reused = mutate(writer, "POST", ORDERS, "changed-input-1", order("CI-2"))
        withClue(reused.asString()) {
            reused.statusCode() shouldBe 409
            reused.jsonPath().getString("error.code") shouldBe "idempotency.key_reused_for_different_request"
            reused.asString() shouldNotContain first.jsonPath().getString("execution_id")
        }
        skus("CI-2") shouldBe 0
    }

    @Test
    fun `concurrent identical requests share one execution and write once`() {
        // Forced: every contender passes the reservation lookup and blocks at the insert before any can win it.
        val answers =
            Executors.newFixedThreadPool(CONTENDERS).use { pool ->
                holdingReservationInserts(waiters = CONTENDERS) {
                    (1..CONTENDERS).map {
                        pool.submit<Pair<Int, String>> {
                            val response = mutate(writer, "POST", ORDERS, "concurrent-1", order("CC-1"))
                            response.statusCode() to response.jsonPath().getString("execution_id")
                        }
                    }
                }.map { it.get(FUTURE_TIMEOUT_S, TimeUnit.SECONDS) }
            }
        withClue(answers.toString()) {
            answers.forEach { (status, _) -> status shouldBeIn listOf(200, 202) }
            answers.map { it.second }.toSet() shouldHaveSize 1
        }
        awaitStatus(answers.first().second, "SUCCESS")
        skus("CC-1") shouldBe 1
        executionsOf(ORDERS_PIPELINE, answers.first().second) shouldBe 1
    }

    @Test
    fun `two replicas answering concurrent identical requests share one execution and write once`() {
        // Non-vacuity: replica B is its own server (own context, own port), not replica A reached twice.
        portB shouldNotBe port
        checkNotNull(replicaB).isActive shouldBe true
        // The race is FORCED, never hoped for: inserts into the reservation table wait behind a held SHARE lock (reads
        // pass), so both replicas find no reservation and both reach the insert before either can win it.
        val answers =
            Executors.newFixedThreadPool(2).use { pool ->
                holdingReservationInserts(waiters = 2) {
                    listOf(port, portB).map { replicaPort ->
                        pool.submit<Pair<Int, String>> {
                            val response = mutate(writer, "POST", ORDERS, "replicas-1", order("RP-1"), replicaPort)
                            response.statusCode() to response.jsonPath().getString("execution_id")
                        }
                    }
                }.map { it.get(FUTURE_TIMEOUT_S, TimeUnit.SECONDS) }
            }
        withClue(answers.toString()) {
            answers.forEach { (status, _) -> status shouldBeIn listOf(200, 202) }
            answers.map { it.second }.toSet() shouldHaveSize 1
        }
        val id = answers.first().second
        awaitStatus(id, "SUCCESS")
        skus("RP-1") shouldBe 1
        executionsOf(ORDERS_PIPELINE, id) shouldBe 1

        // Each replica answers a later retry from the one execution.
        for (replicaPort in listOf(port, portB)) {
            val retry = mutate(writer, "POST", ORDERS, "replicas-1", order("RP-1"), replicaPort)
            withClue("$replicaPort ${retry.asString()}") { retry.statusCode() shouldBe 200 }
            retry.jsonPath().getString("execution_id") shouldBe id
        }
        skus("RP-1") shouldBe 1
    }

    @Test
    fun `a write cannot run under GET - a write pipeline is refused as a GET route, and a GET on a write-only path runs nothing`() {
        val lines = targetCount("SELECT count(*) FROM mut_lines")
        val executions = count("SELECT count(*) FROM pipeline_executions")
        val refused =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"path": "/mut/v1/getwrite", "method": "GET", "pipeline": "$ORDERS_PIPELINE", "pipeline_version": 1}""")
                .`when`()
                .post("/api/v1/endpoints")
                .thenReturn()
        withClue(refused.asString()) {
            refused.statusCode() shouldBe 409
            refused.jsonPath().getString("error.code") shouldBe "endpoint.pipeline_not_readonly"
        }
        // No GET route exists at a POST-only path: the GET serve path answers its unmatched-path 404 (#1037).
        val get = poll(writer, "/api$OTHER")
        withClue(get.asString()) {
            get.statusCode() shouldBe 404
            get.jsonPath().getString("error.code") shouldBe "endpoint.not_found"
        }
        targetCount("SELECT count(*) FROM mut_lines") shouldBe lines
        count("SELECT count(*) FROM pipeline_executions") shouldBe executions
    }

    @Test
    fun `a retry after the route's version changed is answered from the original execution`() {
        val first = mutate(writer, "POST", VERSIONED, "versioned-1", order("VS-1"))
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        saveAndRelease(routes.getValue(VERSIONED), HIT_VERSIONED_PIPELINE)

        val retry = mutate(writer, "POST", VERSIONED, "versioned-1", order("VS-1"))
        withClue(retry.asString()) { retry.statusCode() shouldBe 200 }
        retry.jsonPath().getString("execution_id") shouldBe id
        skus("VS-1") shouldBe 1
        hits(VERSIONED) shouldBe 0
    }

    @Test
    fun `a reservation that cannot be written is 503 and nothing runs`() {
        val executions = count("SELECT count(*) FROM pipeline_executions")
        trigger(
            "mut1039_refuse_reservation",
            "published_mutation_reservations",
            "BEFORE INSERT",
            "NEW.business_path = '$FAULT'",
        ) {
            val refused = mutate(writer, "POST", FAULT, "fault-1", order("FT-1"))
            withClue(refused.asString()) {
                refused.statusCode() shouldBe 503
                refused.jsonPath().getString("error.code") shouldBe "idempotency.reservation_unavailable"
            }
        }
        skus("FT-1") shouldBe 0
        count("SELECT count(*) FROM pipeline_executions") shouldBe executions

        // The store is back: the same key is a fresh request now, and runs once.
        val retried = mutate(writer, "POST", FAULT, "fault-1", order("FT-1"))
        withClue(retried.asString()) { retried.statusCode() shouldBe 200 }
        skus("FT-1") shouldBe 1
    }

    @Test
    fun `a reservation whose owner never recorded the run is taken over once, on the original version`() {
        // The admitting replica "dies" between reserving and recording: its execution insert is refused and so is its
        // release, leaving a reservation with no execution row — what a crashed second instance leaves behind. The
        // store confirmed no execution state, so the answer is the reservation refusal (§4), never an assumed 202.
        val stranded =
            trigger(
                "mut1039_refuse_execution",
                "pipeline_executions",
                "BEFORE INSERT",
                "NEW.pipeline_id = '${pipelines.getValue(TAKEOVER_PIPELINE)}'",
            ) {
                trigger("mut1039_refuse_release", "published_mutation_reservations", "BEFORE DELETE", "OLD.business_path = '$TAKEOVER'") {
                    mutate(writer, "POST", TAKEOVER, "takeover-1", order("TO-1"))
                }
            }
        withClue(stranded.asString()) {
            stranded.statusCode() shouldBe 503
            stranded.jsonPath().getString("error.code") shouldBe "idempotency.reservation_unavailable"
            stranded.header("DP-Execution-Id") shouldBe null
        }
        // The reservation is KEPT (not released, not retired): the same key answers from it once the store is back.
        val id = text("SELECT execution_id FROM published_mutation_reservations WHERE business_path = '$TAKEOVER'")
        count("SELECT count(*) FROM published_mutation_reservations WHERE business_path = '$TAKEOVER'") shouldBe 1
        count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id'") shouldBe 0

        // Inside the start window the owner may still be starting it: 202, the same id, nothing run.
        val early = mutate(writer, "POST", TAKEOVER, "takeover-1", order("TO-1"))
        withClue(early.asString()) { early.statusCode() shouldBe 202 }
        early.jsonPath().getString("execution_id") shouldBe id
        skus("TO-1") shouldBe 0

        // A newer version is released meanwhile; the takeover must still run the ORIGINAL pin.
        saveAndRelease(routes.getValue(TAKEOVER), HIT_TAKEOVER_PIPELINE)
        sql("UPDATE published_mutation_reservations SET created_at = created_at - INTERVAL '10 minutes' WHERE execution_id = '$id'")

        val taken = mutate(writer, "POST", TAKEOVER, "takeover-1", order("TO-1"))
        withClue(taken.asString()) { taken.statusCode() shouldBe 200 }
        taken.jsonPath().getString("execution_id") shouldBe id
        skus("TO-1") shouldBe 1
        hits(TAKEOVER) shouldBe 0

        val again = mutate(writer, "POST", TAKEOVER, "takeover-1", order("TO-1"))
        again.jsonPath().getString("execution_id") shouldBe id
        skus("TO-1") shouldBe 1
        count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id'") shouldBe 1
    }

    // -----------------------------------------------------------------------------------------
    // A retry is judged by its ORIGINAL pin: typed parameters, result negotiation
    // -----------------------------------------------------------------------------------------

    @Test
    fun `equal typed query values replay one execution, a different value is 409, and a newer pin never changes that`() {
        val first = mutate(writer, "POST", "$QTY?quantity=01", "typed-1", null)
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        // The INTEGER 1 was bound (the template stores its text): "01" was never the value.
        quantities("1") shouldBe 1

        val respelled = mutate(writer, "POST", "$QTY?quantity=1", "typed-1", null)
        withClue(respelled.asString()) { respelled.statusCode() shouldBe 200 }
        respelled.jsonPath().getString("execution_id") shouldBe id
        quantities(null) shouldBe 1
        executionsOf(QTY_PIPELINE, id) shouldBe 1

        val different = mutate(writer, "POST", "$QTY?quantity=2", "typed-1", null)
        withClue(different.asString()) {
            different.statusCode() shouldBe 409
            different.jsonPath().getString("error.code") shouldBe "idempotency.key_reused_for_different_request"
            different.asString() shouldNotContain id
        }
        quantities(null) shouldBe 1

        // v2 declares quantity a STRING: under ITS rules "1" is not "01". The retry is still judged by v1's.
        saveAndRelease(routes.getValue(QTY), QTY_TEXT_PIPELINE)
        val afterRelease = mutate(writer, "POST", "$QTY?quantity=1", "typed-1", null)
        withClue(afterRelease.asString()) { afterRelease.statusCode() shouldBe 200 }
        afterRelease.jsonPath().getString("execution_id") shouldBe id
        quantities(null) shouldBe 1

        // Control: v2 IS current and STRING-typed — a fresh key runs it and keeps the text as sent.
        val fresh = mutate(writer, "POST", "$QTY?quantity=01", "typed-2", null)
        withClue(fresh.asString()) { fresh.statusCode() shouldBe 200 }
        fresh.jsonPath().getString("execution_id") shouldNotBe id
        quantities("v2:01") shouldBe 1
        quantities(null) shouldBe 2
    }

    @Test
    fun `an unsupported Accept is 406 on the first call, while running and after success - the reservation stays, nothing reruns`() {
        val reservations = count("SELECT count(*) FROM published_mutation_reservations")
        val executions = count("SELECT count(*) FROM pipeline_executions")
        val refused = mutate(writer, "POST", ORDERS, "accept-1", order("NA-1"), accept = "text/plain")
        withClue(refused.asString()) {
            refused.statusCode() shouldBe 406
            refused.jsonPath().getString("error.code") shouldBe "endpoint.not_acceptable"
        }
        count("SELECT count(*) FROM published_mutation_reservations") shouldBe reservations
        count("SELECT count(*) FROM pipeline_executions") shouldBe executions
        skus("NA-1") shouldBe 0

        // Refused before admission: the key was not consumed, so the acceptable request runs under it.
        val done = mutate(writer, "POST", ORDERS, "accept-1", order("NA-1"))
        withClue(done.asString()) { done.statusCode() shouldBe 200 }
        val id = done.jsonPath().getString("execution_id")
        val afterSuccess = mutate(writer, "POST", ORDERS, "accept-1", order("NA-1"), accept = "text/plain")
        withClue(afterSuccess.asString()) {
            afterSuccess.statusCode() shouldBe 406
            afterSuccess.jsonPath().getString("error.code") shouldBe "endpoint.not_acceptable"
            afterSuccess.asString() shouldNotContain id
        }
        count("SELECT count(*) FROM published_mutation_reservations WHERE execution_id = '$id'") shouldBe 1
        val replayed = mutate(writer, "POST", ORDERS, "accept-1", order("NA-1"))
        withClue(replayed.asString()) { replayed.statusCode() shouldBe 200 }
        replayed.jsonPath().getString("execution_id") shouldBe id
        skus("NA-1") shouldBe 1
        executionsOf(ORDERS_PIPELINE, id) shouldBe 1

        // Running: the slow route answers 202 after its one-second timeout and keeps running for three.
        val running = mutate(writer, "POST", SLOW, "accept-2", null)
        withClue(running.asString()) { running.statusCode() shouldBe 202 }
        val slowId = running.jsonPath().getString("execution_id")
        val whileRunning = mutate(writer, "POST", SLOW, "accept-2", null, accept = "text/plain")
        withClue(whileRunning.asString()) {
            whileRunning.statusCode() shouldBe 406
            whileRunning.jsonPath().getString("error.code") shouldBe "endpoint.not_acceptable"
        }
        text("SELECT status FROM pipeline_executions WHERE execution_id = '$slowId'") shouldBe "RUNNING"
        awaitStatus(slowId, "SUCCESS")
        mutate(writer, "POST", SLOW, "accept-2", null, accept = "text/plain").statusCode() shouldBe 406
        val slowReplay = mutate(writer, "POST", SLOW, "accept-2", null)
        withClue(slowReplay.asString()) { slowReplay.statusCode() shouldBe 200 }
        slowReplay.jsonPath().getString("execution_id") shouldBe slowId
        executionsOf(SLOW_PIPELINE, slowId) shouldBe 1
        count("SELECT count(*) FROM published_mutation_reservations WHERE execution_id = '$slowId'") shouldBe 1
    }

    // -----------------------------------------------------------------------------------------
    // Ported from the round-1 source review's defensive HTTP cases: pool exhaustion, restart, mutation budget
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a full script pool is 409 pool_exhausted with its execution on the first answer and the retry, writing nothing`() {
        // Hold the configured pool's single admission permit: a deterministic full pool, no timing involved.
        val pool = context.getBean("scriptEvaluationPool")
        val permits = ReflectionTestUtils.getField(pool, "admission") as Semaphore
        check(permits.tryAcquire()) { "the suite's one script-pool permit was already held" }
        try {
            val first = mutate(writer, "POST", ORDERS, "pool-1", order("POOL-1"))
            withClue(first.asString()) { first.statusCode() shouldBe 409 }
            first.jsonPath().getString("error.code") shouldBe "pipeline.script.pool_exhausted"
            first.jsonPath().getString("error.details.status") shouldBe "FAILED"
            val id = first.jsonPath().getString("error.details.execution_id")
            val retry = mutate(writer, "POST", ORDERS, "pool-1", order("POOL-1"))
            withClue(retry.asString()) { retry.statusCode() shouldBe 409 }
            retry.jsonPath().getString("error.code") shouldBe "pipeline.script.pool_exhausted"
            retry.jsonPath().getString("error.details.execution_id") shouldBe id
            skus("POOL-1") shouldBe 0
            executionsOf(ORDERS_PIPELINE, id) shouldBe 1
        } finally {
            permits.release()
        }
    }

    @Test
    fun `a restarted replica replays the original once, and a spent mutation budget refuses before any reservation`() {
        val first = mutate(writer, "POST", ORDERS, "restart-1", order("RESTART-1"), replicaPort = portB)
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        // A fresh application context and server on replica B: empty instance caches, the same durable stores.
        replicaB?.close()
        bootReplicaB(budget = "1")
        try {
            val retry = mutate(writer, "POST", ORDERS, "restart-1", order("RESTART-1"), replicaPort = portB)
            withClue(retry.asString()) { retry.statusCode() shouldBe 200 }
            retry.jsonPath().getString("execution_id") shouldBe id
            val reservations = count("SELECT count(*) FROM published_mutation_reservations")
            val executions = count("SELECT count(*) FROM pipeline_executions")
            val limited = mutate(writer, "POST", ORDERS, "restart-2", order("RESTART-2"), replicaPort = portB)
            withClue(limited.asString()) { limited.statusCode() shouldBe 429 }
            limited.jsonPath().getString("error.code") shouldBe "rate_limit.exceeded"
            count("SELECT count(*) FROM published_mutation_reservations") shouldBe reservations
            count("SELECT count(*) FROM pipeline_executions") shouldBe executions
            skus("RESTART-2") shouldBe 0
            skus("RESTART-1") shouldBe 1
        } finally {
            replicaB?.close()
            bootReplicaB()
        }
    }

    // -----------------------------------------------------------------------------------------
    // The terminal table
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a zero-caller success answers 200 with node stats, and its result poll is 204`() {
        val before = hits(HITS)
        val response = mutate(writer, "PUT", HITS, "zero-caller-1", null)
        withClue(response.asString()) { response.statusCode() shouldBe 200 }
        val id = response.jsonPath().getString("execution_id")
        response.jsonPath().getString("status") shouldBe "SUCCESS"
        response.jsonPath().getList<String>("node_stats.node_id") shouldContain "write"
        hits(HITS) shouldBe before + 1

        poll(writer, "/api$HITS/executions/$id/result").statusCode() shouldBe 204
    }

    @Test
    fun `a run past the route's timeout answers 202, keeps running, and is replayed as 200 once done`() {
        val accepted = mutate(writer, "POST", SLOW, "slow-1", null)
        withClue(accepted.asString()) { accepted.statusCode() shouldBe 202 }
        val id = accepted.jsonPath().getString("execution_id")
        accepted.jsonPath().getString("status") shouldBe "RUNNING"
        val statusUrl = accepted.jsonPath().getString("status_url")
        statusUrl shouldBe "/api$SLOW/executions/$id"

        awaitStatus(id, "SUCCESS")
        val status = poll(writer, statusUrl)
        withClue(status.asString()) { status.statusCode() shouldBe 200 }
        status.jsonPath().getString("data.status") shouldBe "SUCCESS"
        status.jsonPath().getMap<String, Any>("data").containsKey("parameters") shouldBe false

        val replay = mutate(writer, "POST", SLOW, "slow-1", null)
        withClue(replay.asString()) { replay.statusCode() shouldBe 200 }
        replay.jsonPath().getString("execution_id") shouldBe id
        executionsOf(SLOW_PIPELINE, id) shouldBe 1
    }

    @Test
    fun `an expired result is 410 with the success status, never a rerun`() {
        val first =
            base(
                writer,
                "expired-1",
            ).header("DP-Result-TTL-Seconds", "1").contentType(ContentType.JSON).body(order("EX-1")).post("/api$ORDERS")
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        Thread.sleep(EXPIRY_WAIT_MS)

        val retry = mutate(writer, "POST", ORDERS, "expired-1", order("EX-1"))
        withClue(retry.asString()) {
            retry.statusCode() shouldBe 410
            retry.jsonPath().getString("error.code") shouldBe "result.expired"
            retry.jsonPath().getString("error.details.status") shouldBe "SUCCESS"
            retry.jsonPath().getString("error.details.execution_id") shouldBe id
        }
        skus("EX-1") shouldBe 1
    }

    @Test
    fun `a strict rejection is 409 with its bounded code, the same on retry, and names no input`(output: CapturedOutput) {
        val body = """{"reject":true,"secret":"$CANARY_BODY"}"""
        val first = mutate(writer, "POST", FAIL, CANARY_KEY, body)
        withClue(first.asString()) {
            first.statusCode() shouldBe 409
            first.jsonPath().getString("error.code") shouldBe "pipeline.script.rejects_strict"
            first.jsonPath().getString("error.details.status") shouldBe "FAILED"
        }
        val id = first.jsonPath().getString("error.details.execution_id")
        val retry = mutate(writer, "POST", FAIL, CANARY_KEY, body)
        retry.statusCode() shouldBe 409
        retry.jsonPath().getString("error.code") shouldBe "pipeline.script.rejects_strict"
        retry.jsonPath().getString("error.details.execution_id") shouldBe id
        executionsOf(FAIL_PIPELINE, id) shouldBe 1

        val status = poll(writer, "/api$FAIL/executions/$id")
        withClue(status.asString()) { status.statusCode() shouldBe 200 }
        status.jsonPath().getString("data.error.code") shouldBe "pipeline.script.rejects_strict"

        // Redaction: neither the raw key nor the body reaches an answer, the audit, the reservation or the logs.
        listOf(first.asString(), retry.asString(), status.asString()).forEach { text ->
            text shouldNotContain CANARY_BODY
            text shouldNotContain CANARY_KEY
        }
        text("SELECT coalesce(string_agg(details_json::text, ' '), '') FROM audit_log WHERE details_json->>'execution_id' = '$id'").let {
            it shouldNotContain CANARY_BODY
            it shouldNotContain CANARY_KEY
        }
        text("SELECT row_to_json(r)::text FROM published_mutation_reservations r WHERE execution_id = '$id'").let {
            it shouldNotContain CANARY_BODY
            it shouldNotContain CANARY_KEY
        }
        output.all shouldNotContain CANARY_KEY
        output.all shouldNotContain CANARY_BODY
    }

    @Test
    fun `an aborted execution is 409 pipeline execution aborted on retry`() {
        val accepted = mutate(writer, "POST", SLEEPY, "aborted-1", null)
        withClue(accepted.asString()) { accepted.statusCode() shouldBe 202 }
        val id = accepted.jsonPath().getString("execution_id")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/executions/$id")
            .then()
            .statusCode(204)
        awaitStatus(id, "ABORTED")

        val retry = mutate(writer, "POST", SLEEPY, "aborted-1", null)
        withClue(retry.asString()) {
            retry.statusCode() shouldBe 409
            retry.jsonPath().getString("error.code") shouldBe "pipeline.execution.aborted"
            retry.jsonPath().getString("error.details.status") shouldBe "ABORTED"
        }
    }

    @Test
    fun `an unknown commit outcome is 409 with its status and never a rerun`() {
        val before = hits(HITS)
        val first = mutate(writer, "PUT", HITS, "unknown-1", null)
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        // The persisted state is the answer: the row now records an attempted, unconfirmed commit.
        sql("UPDATE pipeline_executions SET status = 'OUTCOME_UNKNOWN' WHERE execution_id = '$id'")

        val retry = mutate(writer, "PUT", HITS, "unknown-1", null)
        withClue(retry.asString()) {
            retry.statusCode() shouldBe 409
            retry.jsonPath().getString("error.details.status") shouldBe "OUTCOME_UNKNOWN"
            retry.jsonPath().getString("error.details.status_url") shouldBe "/api$HITS/executions/$id"
        }
        hits(HITS) shouldBe before + 1
    }

    // -----------------------------------------------------------------------------------------
    // Polling only from the initiating grant; audit
    // -----------------------------------------------------------------------------------------

    @Test
    fun `only the initiating grant polls - a write-only key reads its own run, nobody else does`() {
        val first = mutate(writer, "POST", ORDERS, "polling-1", order("PL-1"))
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")

        // The write-only key (no GET binding at all) polls its own execution and result.
        poll(writer, "/api$ORDERS/executions/$id").statusCode() shouldBe 200
        poll(writer, "/api$ORDERS/executions/$id/result").statusCode() shouldBe 200

        // The retry hands out the same business URLs, and they answer under the same rules.
        val retry = mutate(writer, "POST", ORDERS, "polling-1", order("PL-1"))
        withClue(retry.asString()) { retry.statusCode() shouldBe 200 }
        retry.jsonPath().getString("execution_id") shouldBe id
        val retryUrls = listOf(retry.jsonPath().getString("status_url"), retry.jsonPath().getString("result_url"))
        retryUrls shouldBe listOf("/api$ORDERS/executions/$id", "/api$ORDERS/executions/$id/result")
        retryUrls.forEach { url ->
            withClue(url) {
                poll(writer, url).statusCode() shouldBe 200
                poll(reader, url).statusCode() shouldBe 404
                poll(foreign, url).statusCode() shouldBe 404
            }
        }

        // Another key of the same workspace, holding a GET walk and the same POST grant: not found.
        poll(reader, "/api$ORDERS/executions/$id").statusCode() shouldBe 404
        poll(reader, "/api$ORDERS/executions/$id/result").statusCode() shouldBe 404
        // The same key under a different route it is granted: not found.
        poll(writer, "/api$OTHER/executions/$id").statusCode() shouldBe 404
        poll(writer, "/api$OTHER/executions/$id/result").statusCode() shouldBe 404
        // A key of another workspace: not found.
        poll(foreign, "/api$ORDERS/executions/$id").statusCode() shouldBe 404

        // Revoking the initiating grant closes the polling path too.
        revoke(writer, ORDERS, "POST")
        poll(writer, "/api$ORDERS/executions/$id").statusCode() shouldBe 404
        grant(writer, ORDERS, "POST")
        poll(writer, "/api$ORDERS/executions/$id").statusCode() shouldBe 200
    }

    @Test
    fun `a revoked grant refuses the retry without revealing the original, and a restored grant replays it`() {
        val first = mutate(writer, "PUT", HITS, "revoked-1", null)
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        val after = hits(HITS)

        revoke(writer, HITS, "PUT")
        try {
            // The key still holds DELETE on the path: 405 naming only that, and nothing about the admitted run.
            val refused = mutate(writer, "PUT", HITS, "revoked-1", null)
            withClue(refused.asString()) {
                refused.statusCode() shouldBe 405
                refused.jsonPath().getString("error.code") shouldBe "endpoint.method_not_allowed"
                refused.header("Allow").split(",").map { it.trim() } shouldBe listOf("DELETE")
                refused.header("DP-Execution-Id") shouldBe null
                refused.asString() shouldNotContain id
            }
            poll(writer, "/api$HITS/executions/$id").statusCode() shouldBe 404
            hits(HITS) shouldBe after
        } finally {
            grant(writer, HITS, "PUT")
        }

        // The reservation outlived the revocation: the restored grant replays the original, nothing reruns.
        val replay = mutate(writer, "PUT", HITS, "revoked-1", null)
        withClue(replay.asString()) { replay.statusCode() shouldBe 200 }
        replay.jsonPath().getString("execution_id") shouldBe id
        hits(HITS) shouldBe after
    }

    /**
     * #1040 — the same contract through an EXTERNAL HTTP client on the application's own listener, the supported ingress
     * (there is no bundled proxy: an operator's edge forwards to this listener). The JDK's `java.net.http.HttpClient` is
     * another client stack than RestAssured's, and it sends what real integrators send: a POST whose nested body streams
     * CHUNKED (no Content-Length), a repeated PUT under distinct keys, a bodyless DELETE and a DELETE that carries a
     * declared body (RFC 9110 §9.3.5: content on DELETE has no generally defined semantics — this API defines it per
     * route, and the JDK client sends it with a Content-Length), a run past the timeout answered 202 and polled to its
     * result from the originating grant only, and a revoked grant refused.
     */
    @Test
    fun `an external HTTP client posts chunked nested input, repeats PUT, deletes with and without a body, and polls`() {
        // A nested POST body streamed CHUNKED: a publisher of unknown length makes the client omit Content-Length.
        val nested = """{"order_no":"ING-1","lines":[{"sku":"ING-1","qty":2},{"sku":"ING-1B","qty":1}]}"""
        val chunked =
            java.net.http.HttpRequest.BodyPublishers.fromPublisher(
                java.net.http.HttpRequest.BodyPublishers
                    .ofString(nested),
            )
        chunked.contentLength() shouldBe -1L
        val posted = ingress(writer, "POST", "/api$ORDERS", "ingress-post-1", chunked, json = true)
        withClue(posted.body()) { posted.statusCode() shouldBe 200 }
        val postedId = jsonOf(posted).path("execution_id").asText()
        posted.headers().firstValue("DP-Execution-Id").orElse(null) shouldBe postedId
        skus("ING-1") shouldBe 1
        skus("ING-1B") shouldBe 1

        // Polling from the originating grant: the status and result URLs the answer handed out.
        val statusUrl = jsonOf(posted).path("status_url").asText()
        val resultUrl = jsonOf(posted).path("result_url").asText()
        ingress(writer, "GET", statusUrl, null).statusCode() shouldBe 200
        ingress(writer, "GET", resultUrl, null).statusCode() shouldBe 200
        // Another key of the workspace holding the same POST grant and a GET walk: not found — never the original's
        // state (the 404 echoes only the id the caller itself sent; no status, no result, no link).
        ingress(reader, "GET", statusUrl, null).let { other ->
            other.statusCode() shouldBe 404
            jsonOf(other).path("error").path("code").asText() shouldBe "result.execution_not_found"
            other.body() shouldNotContain "\"status\""
            other.body() shouldNotContain "result_url"
        }

        // A repeated PUT under distinct keys: two audited executions, one stable final state.
        val puts = listOf("ingress-put-1", "ingress-put-2").map { ingress(writer, "PUT", "/api$STABLE", it) }
        puts.forEach { withClue(it.body()) { it.statusCode() shouldBe 200 } }
        puts.map { jsonOf(it).path("execution_id").asText() }.toSet() shouldHaveSize 2
        targetCount("SELECT count(*) FROM mut_state WHERE name = 'put-target' AND state = 'set'") shouldBe 1

        // A bodyless DELETE (no Content-Length, no body) and a DELETE carrying its route's declared body.
        val beforeHits = hits(HITS)
        val bodyless = ingress(writer, "DELETE", "/api$HITS", "ingress-delete-1")
        withClue(bodyless.body()) { bodyless.statusCode() shouldBe 200 }
        hits(HITS) shouldBe beforeHits + 1
        val withBody =
            ingress(
                writer,
                "DELETE",
                "/api$ORDERS",
                "ingress-delete-2",
                java.net.http.HttpRequest.BodyPublishers
                    .ofString("""{"order_no":"ING-2","lines":[{"sku":"ING-2","qty":5}]}"""),
                json = true,
            )
        withClue(withBody.body()) { withBody.statusCode() shouldBe 200 }
        skus("ING-2") shouldBe 1
    }

    /** #1040 — the external client's long run (202, polled by its originating grant) and a revoked grant's 405. */
    @Test
    fun `an external HTTP client polls a run past the timeout and is refused a revoked method`() {
        // Past the route's timeout: 202 with the business status URL, polled by the originating grant until done.
        val accepted = ingress(writer, "POST", "/api$SLOW", "ingress-slow-1")
        withClue(accepted.body()) { accepted.statusCode() shouldBe 202 }
        val slowId = jsonOf(accepted).path("execution_id").asText()
        val slowStatus = jsonOf(accepted).path("status_url").asText()
        slowStatus shouldBe "/api$SLOW/executions/$slowId"
        awaitStatus(slowId, "SUCCESS")
        val done = ingress(writer, "GET", slowStatus, null)
        withClue(done.body()) { done.statusCode() shouldBe 200 }
        jsonOf(done).path("data").path("status").asText() shouldBe "SUCCESS"

        // Revocation: the key keeps PUT on the path, so DELETE is now 405 naming only PUT, and nothing runs.
        revoke(writer, HITS, "DELETE")
        try {
            val afterRevoke = hits(HITS)
            val refused = ingress(writer, "DELETE", "/api$HITS", "ingress-delete-revoked")
            withClue(refused.body()) {
                refused.statusCode() shouldBe 405
                refused.headers().firstValue("Allow").orElse("") shouldBe "PUT"
            }
            hits(HITS) shouldBe afterRevoke
        } finally {
            grant(writer, HITS, "DELETE")
        }
    }

    private val ingressClient: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient
            .newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(CLIENT_TIMEOUT_S))
            .build()
    }

    /** One call from the JDK client to the application's own listener (the supported ingress). */
    private fun ingress(
        key: Key,
        method: String,
        path: String,
        idempotencyKey: String?,
        body: java.net.http.HttpRequest.BodyPublisher =
            java.net.http.HttpRequest.BodyPublishers
                .noBody(),
        json: Boolean = false,
    ): java.net.http.HttpResponse<String> {
        val request =
            java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://localhost:$port$path"))
                .timeout(java.time.Duration.ofSeconds(CLIENT_TIMEOUT_S))
                .header(API_KEY_HEADER, key.plaintext)
                .method(method, body)
        idempotencyKey?.let { request.header(IDEMPOTENCY_HEADER, it) }
        if (json) request.header("Content-Type", "application/json")
        return ingressClient.send(
            request.build(),
            java.net.http.HttpResponse.BodyHandlers
                .ofString(),
        )
    }

    private fun jsonOf(response: java.net.http.HttpResponse<String>) = mapper.readTree(response.body())

    @Test
    fun `the audit records the route, method, version, execution and outcome of every mutation answer`() {
        val first = mutate(writer, "POST", ORDERS, "audit-1", order("AU-1"))
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")
        mutate(writer, "POST", ORDERS, "audit-1", order("AU-1")).statusCode() shouldBe 200

        val rows =
            rows(
                "SELECT key_id, details_json->>'outcome', details_json->>'method', details_json->>'api_version', " +
                    "details_json->>'endpoint_id' FROM audit_log WHERE event = 'endpoint.served' " +
                    "AND details_json->>'execution_id' = '$id' ORDER BY id",
            )
        rows.map { it[1] } shouldContainAll listOf("started", "completed", "replayed")
        rows.forEach { row ->
            row[0] shouldBe writer.id
            row[2] shouldBe "POST"
            row[3] shouldBe "1"
            row[4] shouldBe routes.getValue(ORDERS)
        }
    }

    // ------------------------------------------------------------------------------------- calls

    private fun base(
        key: Key,
        idempotencyKey: String?,
        replicaPort: Int = port,
    ): RequestSpecification {
        val spec = given().port(replicaPort).header(API_KEY_HEADER, key.plaintext)
        idempotencyKey?.let { spec.header(IDEMPOTENCY_HEADER, it) }
        return spec
    }

    private fun mutate(
        key: Key,
        method: String,
        path: String,
        idempotencyKey: String?,
        body: String?,
        replicaPort: Int = port,
        accept: String? = null,
    ): Response {
        val spec = base(key, idempotencyKey, replicaPort)
        body?.let { spec.contentType(ContentType.JSON).body(it) }
        accept?.let { spec.accept(it) }
        return spec.`when`().request(method, "/api$path").thenReturn()
    }

    private fun poll(
        key: Key,
        url: String,
    ): Response {
        url shouldStartWith "/api/"
        return given()
            .port(port)
            .header(API_KEY_HEADER, key.plaintext)
            .`when`()
            .get(url)
            .thenReturn()
    }

    private fun order(sku: String): String = """{"order_no":"$sku","lines":[{"sku":"$sku","qty":2}]}"""

    /** Polls the execution row until it reaches [status]; the run belongs to the application's scope. */
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

    // ---------------------------------------------------------------------------------- database

    private fun skus(sku: String): Int = targetCount("SELECT count(*) FROM mut_lines WHERE sku = '$sku'")

    /** Hit rows written by the pipeline that tags [path]; cases compare it before and after one call. */
    private fun hits(path: String): Int = targetCount("SELECT count(*) FROM mut_hits WHERE path = '$path'")

    /** Rows the typed-parameter route wrote, with [value] (null: all of them). */
    private fun quantities(value: String?): Int =
        targetCount("SELECT count(*) FROM mut_qty" + (value?.let { " WHERE quantity = '$it'" } ?: ""))

    private fun executionsOf(
        pipeline: String,
        executionId: String,
    ): Int =
        count(
            "SELECT count(*) FROM pipeline_executions WHERE pipeline_id = '${pipelines.getValue(pipeline)}' " +
                "AND execution_id = '$executionId'",
        )

    private fun count(sql: String): Int = text(sql).toInt()

    private fun text(sql: String): String = rows(sql).firstOrNull()?.firstOrNull().orEmpty()

    private fun rows(sql: String): List<List<String?>> =
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val columns = rs.metaData.columnCount
                    generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
                }
            }
        }

    /**
     * Runs [contend] while this test holds `SHARE` on the reservation table on its own connection — which blocks every
     * insert and no read — and releases it only once [waiters] inserts are observed blocked on it (polled on a THIRD
     * connection: `pg_stat_activity` read inside the holding transaction would be a frozen snapshot). Throws if they
     * never arrive, so a race that was not forced cannot pass as one that was.
     */
    private fun <T> holdingReservationInserts(
        waiters: Int,
        contend: () -> T,
    ): T =
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE published_mutation_reservations IN SHARE MODE") }
            try {
                val contended = contend()
                withClue("reservation inserts blocked behind the held lock (the forced race)") {
                    awaitBlockedReservationInserts(waiters) shouldBe waiters
                }
                contended
            } finally {
                holder.commit()
            }
        }

    /** Polls until [waiters] reservation inserts wait on a lock, or the deadline passes; returns the last count seen. */
    private fun awaitBlockedReservationInserts(waiters: Int): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_TIMEOUT_S)
        var blocked = 0
        while (blocked < waiters && System.nanoTime() < deadline) {
            blocked =
                count(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                        "AND query LIKE 'INSERT INTO published_mutation_reservations%'",
                )
            if (blocked < waiters) Thread.sleep(LOCK_POLL_MS)
        }
        return blocked
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { it.execute(statement) }
        }
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

    // ------------------------------------------------------------------------------------- seed

    /**
     * Boots replica B beside this suite's own context, AFTER the seed, so its route registry loads the published
     * routes. The same JWT secret (the replay digests' subkey derives from it) and encryption key as replica A — two
     * instances of one deployment. Command-line args, because builder properties are defaults that application.yml's
     * environment placeholders would override.
     */
    private fun bootReplicaB(budget: String = KEY_BUDGET) {
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
                "--datapipelines.endpoints.body-max-bytes=$BODY_MAX_BYTES",
                "--datapipelines.result.ttl-min-seconds=1",
                "--datapipelines.endpoints.key-request-budget.max-requests=$budget",
            )
        portB = Integer.parseInt(checkNotNull(replicaB?.environment?.getProperty("local.server.port")))
    }

    private fun seedRows() {
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
            VALUES ('$ADMIN_USER', '$ADMIN_EMAIL', 'Mut Admin', 'test', 'mut1039-admin', TRUE, TRUE)
            """.trimIndent(),
        )
        sql(
            "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                "VALUES ('$DEFAULT_WORKSPACE', '$ADMIN_USER', 'workspace_admin')",
        )
        sql(
            "INSERT INTO workspaces (id, name, display_name, created_by) " +
                "VALUES ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'Mut other workspace', '$ADMIN_USER')",
        )
        sql(
            "INSERT INTO workspace_members (workspace_id, user_id, role) " +
                "VALUES ('$OTHER_WORKSPACE_ID', '$ADMIN_USER', 'workspace_admin')",
        )
    }

    private fun seedTarget() {
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE IF NOT EXISTS mut_lines (line_id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                        "sku text NOT NULL, qty int NOT NULL, ext_id text, ext_number text)",
                )
                s.execute("CREATE TABLE IF NOT EXISTS mut_hits (path text NOT NULL, at timestamptz NOT NULL DEFAULT now())")
                s.execute("CREATE TABLE IF NOT EXISTS mut_state (name text PRIMARY KEY, state text NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS mut_qty (quantity text NOT NULL)")
                s.execute("TRUNCATE mut_lines, mut_hits, mut_state, mut_qty")
                s.execute("INSERT INTO mut_state (name, state) VALUES ('put-target', 'initial'), ('delete-target', 'present')")
            }
        }
    }

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Mutation 1039", "dialect": "POSTGRES",
                 "jdbc_url": "${target.jdbcUrl}", "username": "${target.username}", "password": "${target.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun createTemplates() {
        scriptTemplate(LINES_TEMPLATE, LINES_JSONATA, linesContract(rejects = false))
        scriptTemplate(FAIL_TEMPLATE, FAIL_JSONATA, linesContract(rejects = true))
        sqlTemplate(INSERT_TEMPLATE, INSERT_LINE)
        sqlTemplate(READ_TEMPLATE, "SELECT count(*) AS n FROM mut_lines")
        sqlTemplate(SLOW_TEMPLATE, "SELECT 1 AS n FROM pg_sleep($SLOW_SECONDS)")
        sqlTemplate(SLEEPY_TEMPLATE, "SELECT 1 AS n FROM pg_sleep($SLEEPY_SECONDS)")
        // The idempotent PUT and DELETE of one stable resource: the same final state however often they run.
        sqlTemplate(STABLE_PUT_TEMPLATE, "UPDATE mut_state SET state = 'set' WHERE name = 'put-target'")
        sqlTemplate(STABLE_DELETE_TEMPLATE, "DELETE FROM mut_state WHERE name = 'delete-target'")
        // The typed-parameter route: v1 binds quantity as an INTEGER, v2 as a STRING and tags its rows.
        sqlTemplate(QTY_TEMPLATE, "INSERT INTO mut_qty (quantity) VALUES (CAST(:quantity AS text))")
        sqlTemplate(QTY_TEXT_TEMPLATE, "INSERT INTO mut_qty (quantity) VALUES ('v2:' || CAST(:quantity AS text))")
        // One hit row per run, tagged with the route that ran it (a parameterless DML: the path is the template's own).
        for (path in listOf(HITS, VERSIONED, TAKEOVER)) {
            sqlTemplate(hitTemplate(path), "INSERT INTO mut_hits (path) VALUES ('$path')")
        }
    }

    private fun createPipelines() {
        pipelines[ORDERS_PIPELINE] = linesPipeline(ORDERS_PIPELINE)
        pipelines[TAKEOVER_PIPELINE] = linesPipeline(TAKEOVER_PIPELINE)
        pipelines[READ_PIPELINE] =
            pipeline(READ_PIPELINE, listOf(dql("read", READ_TEMPLATE)), null)
        pipelines[SLOW_PIPELINE] = pipeline(SLOW_PIPELINE, listOf(dql("slow", SLOW_TEMPLATE)), null)
        pipelines[SLEEPY_PIPELINE] = pipeline(SLEEPY_PIPELINE, listOf(dql("sleepy", SLEEPY_TEMPLATE)), null)
        pipelines[HIT_PIPELINE] = pipeline(HIT_PIPELINE, listOf(hit(HITS)), null)
        pipelines[STABLE_PUT_PIPELINE] = pipeline(STABLE_PUT_PIPELINE, listOf(affecting(STABLE_PUT_TEMPLATE, "update")), null)
        pipelines[STABLE_DELETE_PIPELINE] =
            pipeline(STABLE_DELETE_PIPELINE, listOf(affecting(STABLE_DELETE_TEMPLATE, "delete")), null)
        // The v2 pins of the version-switch and takeover cases: each tags its OWN route, so "v2 never ran" is countable.
        pipelines[HIT_VERSIONED_PIPELINE] = pipeline(HIT_VERSIONED_PIPELINE, listOf(hit(VERSIONED)), null)
        pipelines[HIT_TAKEOVER_PIPELINE] = pipeline(HIT_TAKEOVER_PIPELINE, listOf(hit(TAKEOVER)), null)
        pipelines[QTY_PIPELINE] =
            pipeline(QTY_PIPELINE, listOf(writing(QTY_TEMPLATE)), null, quantity("INTEGER"))
        pipelines[QTY_TEXT_PIPELINE] =
            pipeline(
                QTY_TEXT_PIPELINE,
                listOf(writing(QTY_TEXT_TEMPLATE)),
                null,
                quantity("STRING"),
            )
        pipelines[FAIL_PIPELINE] =
            pipeline(
                FAIL_PIPELINE,
                listOf(
                    mapOf(
                        "id" to "shape",
                        "description" to "Fails on demand",
                        "type" to "SCRIPT",
                        "template" to mapOf("id" to FAIL_TEMPLATE, "version" to 1),
                        "inputs" to mapOf("order" to "@request_body"),
                        "output" to mapOf("target" to "tempdb", "table" to "lines_in", "rejects" to "lines_rejected"),
                        "strict" to true,
                        "depends_on" to emptyList<String>(),
                    ),
                ),
                mapOf("required" to true),
            )
    }

    private fun publishRoutes() {
        routes[ORDERS] = publish(ORDERS, "POST", ORDERS_PIPELINE, TIMEOUT_S)
        routes["$ORDERS GET"] = publish(ORDERS, "GET", READ_PIPELINE, TIMEOUT_S)
        routes["$ORDERS DELETE"] = publish(ORDERS, "DELETE", ORDERS_PIPELINE, TIMEOUT_S)
        routes[HITS] = publish(HITS, "PUT", HIT_PIPELINE, TIMEOUT_S)
        routes["$HITS DELETE"] = publish(HITS, "DELETE", HIT_PIPELINE, TIMEOUT_S)
        routes["$STABLE PUT"] = publish(STABLE, "PUT", STABLE_PUT_PIPELINE, TIMEOUT_S)
        routes["$STABLE DELETE"] = publish(STABLE, "DELETE", STABLE_DELETE_PIPELINE, TIMEOUT_S)
        routes[VERSIONED] = publish(VERSIONED, "POST", ORDERS_PIPELINE, TIMEOUT_S)
        routes[TAKEOVER] = publish(TAKEOVER, "POST", TAKEOVER_PIPELINE, TIMEOUT_S)
        routes[FAULT] = publish(FAULT, "POST", ORDERS_PIPELINE, TIMEOUT_S)
        routes[OTHER] = publish(OTHER, "POST", ORDERS_PIPELINE, TIMEOUT_S)
        routes[SLOW] = publish(SLOW, "POST", SLOW_PIPELINE, 1)
        routes[SLEEPY] = publish(SLEEPY, "POST", SLEEPY_PIPELINE, 1)
        routes[FAIL] = publish(FAIL, "POST", FAIL_PIPELINE, TIMEOUT_S)
        routes[QTY] = publish(QTY, "POST", QTY_PIPELINE, TIMEOUT_S)
    }

    private fun mintKeys() {
        writer = mint("mut-writer", bindings = "[]", workspace = null)
        listOf(
            ORDERS to "POST",
            ORDERS to "DELETE",
            HITS to "PUT",
            HITS to "DELETE",
            STABLE to "PUT",
            STABLE to "DELETE",
            VERSIONED to "POST",
            TAKEOVER to "POST",
            FAULT to "POST",
            OTHER to "POST",
            SLOW to "POST",
            SLEEPY to "POST",
            FAIL to "POST",
            QTY to "POST",
        ).forEach { (path, method) -> grant(writer, path, method) }
        reader = mint("mut-reader", bindings = """["/"]""", workspace = null)
        grant(reader, ORDERS, "POST")
        foreign = mint("mut-foreign", bindings = """["/"]""", workspace = OTHER_WORKSPACE)
    }

    private fun mint(
        name: String,
        bindings: String,
        workspace: String?,
    ): Key {
        val spec = given().port(port).contentType(ContentType.JSON).asSession(ADMIN_SESSION)
        workspace?.let { spec.header(WORKSPACE_HEADER, it) }
        val minted =
            spec
                .body("""{"name": "$name", "kind": "endpoint", "bindings": $bindings}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
        return Key(minted.getString("data.id"), minted.getString("data.key"))
    }

    private fun grant(
        key: Key,
        path: String,
        method: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body("""{"api_key_id": "${key.id}", "path_prefix": "$path", "method": "$method"}""")
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .then()
            .statusCode(201)
    }

    private fun revoke(
        key: Key,
        path: String,
        method: String,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/endpoints/bindings?pathPrefix=$path&apiKeyId=${key.id}&method=$method")
            .then()
            .statusCode(204)
    }

    /** Publishes [path]/[method] pinning v1 of [pipeline] and releases that version. */
    private fun publish(
        path: String,
        method: String,
        pipeline: String,
        timeoutSeconds: Int,
    ): String {
        val id =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(
                    """{"path": "$path", "method": "$method", "pipeline": "$pipeline", "pipeline_version": 1, """ +
                        """"timeout_seconds": $timeoutSeconds}""",
                ).`when`()
                .post("/api/v1/endpoints")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        val hash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints/$id/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .post("/api/v1/endpoints/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    /** Saves a draft pinning v1 of [pipeline] on the entity's current version and releases it (it becomes current). */
    private fun saveAndRelease(
        entityId: String,
        pipeline: String,
    ) {
        val currentHash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints/$entityId/versions")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.find { it.is_current }.body_hash")
        val saved =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .header("If-Match", currentHash)
                .body("""{"pipeline": "$pipeline", "pipeline_version": 1, "timeout_seconds": $TIMEOUT_S}""")
                .put("/api/v1/endpoints/$entityId/draft")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", saved.getString("data.body_hash"))
            .post("/api/v1/endpoints/$entityId/release")
            .then()
            .statusCode(200)
    }

    private fun linesPipeline(name: String): String =
        pipeline(
            name,
            listOf(
                mapOf(
                    "id" to "shape",
                    "description" to "Turns the request body into order lines",
                    "type" to "SCRIPT",
                    "template" to mapOf("id" to LINES_TEMPLATE, "version" to 1),
                    "inputs" to mapOf("order" to "@request_body"),
                    "output" to mapOf("target" to "tempdb", "table" to "lines_in"),
                    "depends_on" to emptyList<String>(),
                ),
                mapOf(
                    "id" to "insert",
                    "description" to "Writes the lines",
                    "type" to "DML",
                    "source" to DATASOURCE,
                    "template" to mapOf("id" to INSERT_TEMPLATE, "version" to 1),
                    "operation" to "insert",
                    "key" to mapOf("column" to "line_id", "type" to "INTEGER"),
                    "input" to mapOf("table" to "lines_in"),
                    "output" to mapOf("target" to "caller"),
                    "depends_on" to listOf("shape"),
                ),
            ),
            mapOf("required" to true),
        )

    private fun dql(
        id: String,
        template: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Reads",
            "type" to "DQL",
            "source" to DATASOURCE,
            "template" to mapOf("id" to template, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    private fun hit(path: String): Map<String, Any?> =
        mapOf(
            "id" to "write",
            "description" to "One hit row",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to hitTemplate(path), "version" to 1),
            "operation" to "update",
            "output" to mapOf("target" to "discard"),
            "depends_on" to emptyList<String>(),
        )

    /** One `update` or `delete` statement whose `affected_rows` row goes to the caller. */
    private fun affecting(
        template: String,
        operation: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to "write",
            "description" to "One idempotent statement",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to template, "version" to 1),
            "operation" to operation,
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    /** The typed route's one optional query parameter, declared as [type]. */
    private fun quantity(type: String): Map<String, Any?> =
        mapOf("quantity" to mapOf("type" to type, "required" to false, "description" to "How many to write."))

    /** One parameterised write whose statistics are the zero-caller answer. */
    private fun writing(template: String): Map<String, Any?> =
        mapOf(
            "id" to "write",
            "description" to "One typed write",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to template, "version" to 1),
            "operation" to "update",
            "output" to mapOf("target" to "discard"),
            "depends_on" to emptyList<String>(),
        )

    private fun hitTemplate(path: String): String = "test/mut1039_hit${path.substringAfterLast('/')}.sql"

    /** Creates [name] and releases its v1 — an API pins an exact released version. */
    private fun pipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
        requestBody: Map<String, Any?>?,
        parameters: Map<String, Any?> = emptyMap(),
    ): String {
        val document =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Mutation 1039 $name",
                "description" to "#1039 published mutation fixture",
                "parameters" to parameters,
                "nodes" to nodes,
            ) + (requestBody?.let { mapOf("request_body" to it) } ?: emptyMap())
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(document))
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        withClue("create $name: ${created.asString()}") { created.statusCode() shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    private fun scriptTemplate(
        id: String,
        body: String,
        contract: Map<String, Any?>,
    ) {
        val expect = if (contract["rejects"] == true) mapOf("rows" to emptyList<Any>(), "rejects" to emptyList<Any>()) else emptyList<Any>()
        val payload =
            mapOf(
                "id" to id,
                "display_name" to id,
                "description" to "#1039 body transformer",
                "type" to "jsonata",
                "body" to body,
                "contract" to contract,
                "invariants" to emptyList<Any>(),
                "tests" to
                    listOf(
                        mapOf(
                            "name" to "quiet",
                            "input" to mapOf("inputs" to mapOf("order" to emptyMap<String, Any>())),
                            "expect" to mapOf("output" to expect),
                        ),
                    ),
            )
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(payload))
                .`when`()
                .post("/api/v1/templates")
                .thenReturn()
        withClue("template $id: ${created.asString()}") { created.statusCode() shouldBe 201 }
        releaseTemplate(id, created.jsonPath().getString("data.body_hash"))
    }

    private fun sqlTemplate(
        id: String,
        body: String,
    ) {
        val payload =
            mapOf(
                "id" to id,
                "dialect" to "POSTGRES",
                "display_name" to id,
                "description" to "#1039 template",
                "imports" to emptyList<Any>(),
                "body" to body,
            )
        val hash =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(payload))
                .`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        releaseTemplate(id, hash)
    }

    private fun releaseTemplate(
        id: String,
        hash: String,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun linesContract(rejects: Boolean) =
        mapOf(
            "mode" to "table",
            "rejects" to rejects,
            "inputs" to mapOf("order" to mapOf("kind" to "body")),
            "output" to
                mapOf(
                    "kind" to "table",
                    "columns" to
                        listOf(
                            mapOf("name" to "sku", "type" to "STRING"),
                            mapOf("name" to "qty", "type" to "INTEGER"),
                            mapOf("name" to "ext_id", "type" to "STRING", "nullable" to true),
                            mapOf("name" to "ext_number", "type" to "STRING", "nullable" to true),
                        ),
                ),
        )

    private class Key(
        val id: String,
        val plaintext: String,
    )

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val DATASOURCE = "mut1039"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val OTHER_WORKSPACE_ID = "defa0000-0000-0000-0000-000000001039"
        private const val OTHER_WORKSPACE = "mut-other"

        private const val ORDERS = "/mut/v1/orders"
        private const val HITS = "/mut/v1/hits"
        private const val STABLE = "/mut/v1/stable"
        private const val VERSIONED = "/mut/v1/versioned"
        private const val TAKEOVER = "/mut/v1/takeover"
        private const val FAULT = "/mut/v1/fault"
        private const val OTHER = "/mut/v1/other"
        private const val SLOW = "/mut/v1/slow"
        private const val SLEEPY = "/mut/v1/sleepy"
        private const val FAIL = "/mut/v1/fail"
        private const val QTY = "/mut/v1/qty"

        private const val ORDERS_PIPELINE = "test/mut1039_orders"
        private const val TAKEOVER_PIPELINE = "test/mut1039_takeover"
        private const val READ_PIPELINE = "test/mut1039_read"
        private const val SLOW_PIPELINE = "test/mut1039_slow"
        private const val SLEEPY_PIPELINE = "test/mut1039_sleepy"
        private const val HIT_PIPELINE = "test/mut1039_hit"
        private const val STABLE_PUT_PIPELINE = "test/mut1039_stable_put"
        private const val STABLE_DELETE_PIPELINE = "test/mut1039_stable_delete"
        private const val HIT_VERSIONED_PIPELINE = "test/mut1039_hit_versioned"
        private const val HIT_TAKEOVER_PIPELINE = "test/mut1039_hit_takeover"
        private const val FAIL_PIPELINE = "test/mut1039_fail"
        private const val QTY_PIPELINE = "test/mut1039_qty"
        private const val QTY_TEXT_PIPELINE = "test/mut1039_qty_text"

        private const val LINES_TEMPLATE = "test/mut1039_lines.jsonata"
        private const val FAIL_TEMPLATE = "test/mut1039_fail.jsonata"
        private const val INSERT_TEMPLATE = "test/mut1039_insert_line.sql"
        private const val READ_TEMPLATE = "test/mut1039_read.sql"
        private const val STABLE_PUT_TEMPLATE = "test/mut1039_stable_put.sql"
        private const val STABLE_DELETE_TEMPLATE = "test/mut1039_stable_delete.sql"
        private const val SLOW_TEMPLATE = "test/mut1039_slow.sql"
        private const val SLEEPY_TEMPLATE = "test/mut1039_sleepy.sql"
        private const val QTY_TEMPLATE = "test/mut1039_qty.sql"
        private const val QTY_TEXT_TEMPLATE = "test/mut1039_qty_text.sql"

        private const val TIMEOUT_S = 60
        private const val CLIENT_TIMEOUT_S = 60L
        private const val SLOW_SECONDS = 3
        private const val SLEEPY_SECONDS = 30
        private const val BODY_MAX_BYTES = 4096
        private const val CONTENDERS = 4
        private const val FUTURE_TIMEOUT_S = 90L
        private const val LOCK_WAIT_TIMEOUT_S = 30L
        private const val LOCK_POLL_MS = 50L
        private const val AWAIT_S = 90L
        private const val POLL_MS = 200L
        private const val EXPIRY_WAIT_MS = 2_500L
        private const val KEY_BUDGET = "100000"

        /** Synthetic markers: a grammar-valid key value and a body value that must never leave the request. */
        private const val CANARY_KEY = "CANARYkey-1039-7f3a91"
        private const val CANARY_BODY = "CANARY-body-1039-52c0de"

        private const val INSERT_LINE =
            "INSERT INTO mut_lines (sku, qty, ext_id, ext_number) " +
                "VALUES (CAST(:sku AS text), CAST(:qty AS int), CAST(:ext_id AS text), CAST(:ext_number AS text))"

        private const val LINES_JSONATA =
            """
            (${'$'}o := inputs.order;
             ${'$'}items := ${'$'}exists(${'$'}o.lines) ? ${'$'}o.lines : (${'$'}exists(${'$'}o.sku) ? [${'$'}o] : []);
             [${'$'}items.{"sku": sku, "qty": qty, "ext_id": null, "ext_number": null}])
            """

        /** A row the strict node rejects (with a reason quoting the input), or nothing. */
        private const val FAIL_JSONATA =
            """
            (${'$'}o := inputs.order;
             {"rows": [],
              "rejects": ${'$'}exists(${'$'}o.reject) ? [{"row": {"sku": "A", "qty": 1, "ext_id": null, "ext_number": null}, "reason": "rejected " & ${'$'}o.secret}] : []})
            """

        private val ADMIN_USER: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "mut1039-admin@datapipelines.test"
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, ADMIN_EMAIL)

        private val target = SharedE2e.scratchDatabase("mutation1039_target")

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
            registry.add("datapipelines.endpoints.body-max-bytes") { BODY_MAX_BYTES.toString() }
            // A one-second floor lets the expiry case ask for a one-second result TTL.
            registry.add("datapipelines.result.ttl-min-seconds") { "1" }
            // One writer key drives every case; the serve path's per-key budget (60 a minute) would refuse it mid-suite.
            registry.add("datapipelines.endpoints.key-request-budget.max-requests") { KEY_BUDGET }
        }

        /** One key for both replicas: a datasource password replica A encrypted must decrypt on replica B. */
        private val ENCRYPTION_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    }
}
