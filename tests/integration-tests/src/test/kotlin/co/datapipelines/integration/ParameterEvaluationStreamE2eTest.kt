package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.net.Socket
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * **#375 — the observed parameter-set evaluation, end to end** (rest-api §21.5; the parameter-set workspace spec §4).
 * Everything is real HTTP/SSE against the real stack: a real Postgres customer source, released selector templates,
 * a released set, the real evaluator and bulkhead.
 *
 * The walk: the ordinary `/evaluate` envelope against a committed fixture (the variable id and run suffix normalised);
 * the observed cascade's frames — `evaluation_started` first, each parameter's true sequence, state resolved before city
 * is admitted, `evaluation_completed` last with the response byte-identical to `/evaluate`'s; the 404 bodies identical
 * on both routes; a POST without the CSRF header refused before anything opens; the promoter refused by role; the
 * reuse and cap refusals with a slow evaluation open; a member removed mid-stream cut at the next write while the
 * evaluation runs on; and the owner's §11.7 ruling — a client that closes its stream after `evaluation_started` has
 * its evaluation ABORTED once the 1 s grace elapses, the registry emptied and the pool's permits back, all well inside
 * the 20 s evaluate deadline (the abort, not the deadline, ended it).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk over one fixture, as the sibling E2Es are
class ParameterEvaluationStreamE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val mapper = ObjectMapper()

    // ---- the beans, by NAME (module-structure §4.2: this module compiles against :modules:app alone) ----------------

    private fun activeEvaluationStreams(): Int =
        context.getBean("parameterEvaluationStreamRegistry").let { it.javaClass.getMethod("getActiveStreams").invoke(it) as Int }

    private fun selectorsAdmitted(): Int = context.getBean("selectorPool").let { it.javaClass.getMethod("admitted").invoke(it) as Int }

    private fun refreshCapReached(user: String): Boolean =
        context.getBean("refreshStreamRegistry").let {
            it.javaClass.getMethod("atStreamLimit", UUID::class.java).invoke(it, UUID.fromString(user)) as Boolean
        }

    // ---- the walk ------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `fixture - people, a Postgres source, released selector templates, two released sets`() {
        seedPeople()
        registerDatasource()
        createAndReleaseTemplate(STATES, STATES_SQL)
        createAndReleaseTemplate(CITIES, CITIES_SQL)
        createAndReleaseTemplate(SLOW, SLOW_SQL)
        cascadeSetId = createAndReleaseSet(CASCADE_SET, cascadeParameters())
        slowSetId = createAndReleaseSet(SLOW_SET, slowParameters())
    }

    @Test
    @Order(2)
    fun `the ordinary evaluate's envelope matches the committed fixture byte for byte`() {
        val response =
            post("/api/v1/parameter-sets/$cascadeSetId/evaluate", """{"version":1,"selections":{"country":"US","state":"NJ"}}""", ADMIN)
        withClue(response.second.take(EXCERPT)) { response.first shouldBe 200 }
        // The per-request correlation id, the set's id and the run's folder are the only bytes that vary between runs.
        val normalised =
            CORRELATION_ID
                .replace(response.second, "\"correlation_id\":\"{{CORRELATION_ID}}\"")
                .replace(cascadeSetId, "{{SET_ID}}")
                .replace(RUN, "{{RUN}}") + "\n"
        val fixture = File("src/test/resources/golden/parameter-evaluate-envelope.json")
        if (System.getenv("REWRITE_GOLDEN") != null) {
            fixture.parentFile.mkdirs()
            fixture.writeText(normalised)
            return
        }
        withClue("the /evaluate envelope drifted (regenerate deliberately with REWRITE_GOLDEN=1):\n$normalised") {
            normalised shouldBe fixture.readText()
        }
    }

    @Test
    @Order(3)
    fun `the observed cascade - every frame in its true order, and the completed response byte-identical to evaluate's`() {
        awaitNoStreams()
        val id = uuid()
        val body = """{"version":1,"selections":{"country":"US","state":"NJ"},"evaluation_id":"$id","instance_id":"${uuid()}"}"""

        val stream = post("/api/v1/parameter-sets/$cascadeSetId/evaluations", body, ADMIN, accept = "text/event-stream")

        withClue(stream.second.take(EXCERPT)) { stream.first shouldBe 200 }
        val frames = E2eSse.parseEvents(stream.second, mapper)
        val names = frames.map { it.first }
        println("event=e2e375.cascade_frames ${frames.joinToString(" ") { "${it.first}(${it.second.path("parameter").asText("")})" }}")
        names.first() shouldBe "evaluation_started"
        names.last() shouldBe "evaluation_completed"
        frames.forEach { (name, data) -> withClue("$name carries the request's id") { data["evaluation_id"].asText() shouldBe id } }
        frames.first().second["parameter_set_id"].asText() shouldBe cascadeSetId
        frames.first().second["version"].asInt() shouldBe 1
        sequenceOf(frames, "country") shouldContainExactly listOf("parameter_resolved")
        sequenceOf(frames, "state") shouldContainExactly
            listOf("parameter_waiting", "parameter_admitted", "parameter_running", "parameter_resolved")
        sequenceOf(frames, "city") shouldContainExactly
            listOf("parameter_waiting", "parameter_admitted", "parameter_running", "parameter_resolved")
        withClue("the true sequencing: city is admitted only after state resolved") {
            index(frames, "parameter_resolved", "state") shouldBeLessThan index(frames, "parameter_admitted", "city")
        }
        frame(frames, "parameter_running", "state")["template"]["id"].asText() shouldBe STATES
        frame(frames, "parameter_running", "state")["datasource"].asText() shouldBe DATASOURCE
        frame(frames, "parameter_resolved", "state")["rows"].asInt() shouldBe 2
        frame(frames, "parameter_resolved", "state")["origin"].asText() shouldBe "client"
        ids(stream.second) shouldBe (1..frames.size).map(Int::toString)

        // Scenario 10 (R1's boundary): the REST evaluate's data, byte-identical to the frame's response.
        val plain =
            post("/api/v1/parameter-sets/$cascadeSetId/evaluate", """{"version":1,"selections":{"country":"US","state":"NJ"}}""", ADMIN)
        val restData = member(plain.second, "data")
        val completedData =
            stream.second
                .lines()
                .last { it.startsWith("data:") && it.contains("\"response\"") }
                .removePrefix("data:")
        val frameResponse = member(completedData, "response")
        frameResponse shouldBe restData
    }

    @Test
    @Order(4)
    fun `a missing version and an unknown set answer the ordinary evaluate's IDENTICAL 404`() {
        awaitNoStreams() // the previous case's ended stream goes at the next 1 s tick
        listOf(cascadeSetId to 99, uuid() to 1).forEach { (set, version) ->
            val observed =
                post(
                    "/api/v1/parameter-sets/$set/evaluations",
                    """{"version":$version,"selections":{},"evaluation_id":"${uuid()}","instance_id":"${uuid()}"}""",
                    ADMIN,
                )
            val plain = post("/api/v1/parameter-sets/$set/evaluate", """{"version":$version,"selections":{}}""", ADMIN)
            observed.first shouldBe 404
            plain.first shouldBe 404
            mapper.readTree(observed.second)["error"] shouldBe mapper.readTree(plain.second)["error"]
        }
        activeEvaluationStreams() shouldBe 0
    }

    @Test
    @Order(5)
    fun `a POST without the CSRF header is refused before anything opens, and the promoter is refused by role`() {
        awaitNoStreams()
        val body = """{"version":1,"selections":{},"evaluation_id":"${uuid()}","instance_id":"${uuid()}"}"""
        val noCsrf =
            given()
                .port(port)
                .cookie(E2eSession.COOKIE, ADMIN)
                .cookie(E2eSession.CSRF_COOKIE, E2eSession.CSRF_TOKEN)
                .contentType(ContentType.JSON)
                .accept("text/event-stream, application/json")
                .body(body)
                .post("/api/v1/parameter-sets/$cascadeSetId/evaluations")
        withClue(noCsrf.asString().take(EXCERPT)) { noCsrf.statusCode shouldBe 403 }
        noCsrf.asString() shouldNotContain "evaluation_started"

        val promoter = post("/api/v1/parameter-sets/$cascadeSetId/evaluations", body, PROMOTER)
        promoter.first shouldBe 403
        mapper.readTree(promoter.second)["error"]["code"].asText() shouldBe "auth.role_required"
        activeEvaluationStreams() shouldBe 0
    }

    @Test
    @Order(6)
    fun `with a slow evaluation open - a reused id is refused reused, and the one cap refuses a second stream`() {
        awaitNoStreams()
        val held = uuid()
        val socket = openRaw(slowSetId, held, VIEWER, selections = """{"mode":"slow"}""")
        try {
            readUntil(socket, "evaluation_started")
            awaitUntil("the slow stream is registered") { activeEvaluationStreams() == 1 }
            withClue("D7's wiring: the refresh registry counts the evaluation stream (cap = 1)") {
                refreshCapReached(VIEWER_ID) shouldBe
                    true
            }

            val reused = post("/api/v1/parameter-sets/$slowSetId/evaluations", observedBody(held, """{"mode":"fast"}"""), VIEWER)
            reused.first shouldBe 400
            mapper.readTree(reused.second)["error"]["details"]["reason"].asText() shouldBe "reused"

            val capped = post("/api/v1/parameter-sets/$slowSetId/evaluations", observedBody(uuid(), """{"mode":"fast"}"""), VIEWER)
            capped.first shouldBe 429
            mapper.readTree(capped.second)["error"]["code"].asText() shouldBe "rate_limit.exceeded"
            activeEvaluationStreams() shouldBe 1
        } finally {
            socket.close()
        }
    }

    @Test
    @Order(7)
    fun `a client gone past the grace has its evaluation ABORTED - the abort, not the deadline, ends it`() {
        awaitNoStreams()
        awaitUntil("the previous slow statement's slot came back") { selectorsAdmitted() == 0 }
        val appender = capture()
        val id = uuid()
        val begun = System.nanoTime()
        try {
            val socket = openRaw(slowSetId, id, ADMIN, selections = """{"mode":"slow"}""")
            readUntil(socket, "parameter_running")
            socket.close() // the client goes away mid-statement

            awaitUntil("the evaluation ended ABORTED") { lines(appender).any { it.contains("evaluation_id=$id outcome=ABORTED") } }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun)
            val evidence = lines(appender).filter { it.contains(id) }
            println("event=e2e375.abort elapsed_ms=$elapsedMs\n${evidence.joinToString("\n")}")

            evidence.any { it.contains("event=parameter.evaluation_grace_elapsed evaluation_id=$id action=abort") } shouldBe true
            evidence.any { it.contains("event=parameter.evaluation_ended evaluation_id=$id outcome=ABORTED") } shouldBe true
            evidence.none { it.contains("outcome=TIMEOUT") || it.contains("outcome=COMPLETED") } shouldBe true
            withClue("the deadline is 20 s; the abort fired after a 1 s grace") { elapsedMs shouldBeLessThan ABORT_WITHIN_MS }
            awaitUntil("the registry dropped the stream") { activeEvaluationStreams() == 0 }
            awaitUntil("the abandoned statement's slot came back (cancel + discard)") { selectorsAdmitted() == 0 }
        } finally {
            release(appender)
        }
    }

    @Test
    @Order(8)
    fun `a member removed mid-stream is cut at the next write - the evaluation runs on to its end`() {
        awaitNoStreams()
        awaitUntil("no statement is held") { selectorsAdmitted() == 0 }
        val appender = capture()
        val id = uuid()
        try {
            val socket = openRaw(slowSetId, id, MEMBER, selections = """{"mode":"brief"}""")
            readUntil(socket, "parameter_running")
            given()
                .port(port)
                .asSession(ADMIN)
                .delete("/api/v1/workspaces/$WORKSPACE/members/$MEMBER_ID")
                .then()
                .statusCode(204)

            // The cut completes the response (the chunked terminator); the keep-alive connection itself stays open.
            val rest = readUntil(socket, CHUNKED_END)
            socket.close()
            rest shouldContain ":revoked"
            rest shouldNotContain "evaluation_completed"
            awaitUntil("the evaluation ran on to its end") { lines(appender).any { it.contains("evaluation_id=$id outcome=COMPLETED") } }
            lines(appender).none { it.contains("event=parameter.evaluation_grace_elapsed evaluation_id=$id") } shouldBe true
        } finally {
            release(appender)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private fun observedBody(
        id: String,
        selections: String,
    ) = """{"version":1,"selections":$selections,"evaluation_id":"$id","instance_id":"${uuid()}"}"""

    private fun post(
        path: String,
        body: String,
        session: String,
        accept: String = "application/json",
    ): Pair<Int, String> {
        val response =
            given()
                .port(port)
                .asSession(session)
                .contentType(ContentType.JSON)
                .accept(accept)
                .body(body)
                .post(path)
        return response.statusCode to response.asString()
    }

    /** A raw HTTP/1.1 POST whose socket the test closes itself — the honest model of a browser tab going away. */
    private fun openRaw(
        setId: String,
        evaluationId: String,
        session: String,
        selections: String,
    ): Socket {
        val body = observedBody(evaluationId, selections).toByteArray(Charsets.UTF_8)
        val socket = Socket("localhost", port).apply { soTimeout = SOCKET_TIMEOUT_MS }
        val head =
            "POST /api/v1/parameter-sets/$setId/evaluations HTTP/1.1\r\n" +
                "Host: localhost:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Accept: text/event-stream\r\n" +
                "Cookie: ${E2eSession.cookieHeader(session)}\r\n" +
                "${E2eSession.CSRF_HEADER}: ${E2eSession.CSRF_TOKEN}\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
        return socket
    }

    private fun readUntil(
        socket: Socket,
        marker: String,
    ): String {
        val seen = StringBuilder()
        val buffer = ByteArray(BUFFER_BYTES)
        while (!seen.contains(marker)) {
            val read = socket.getInputStream().read(buffer)
            check(read >= 0) { "the stream ended before '$marker': ${seen.take(EXCERPT)}" }
            seen.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return seen.toString()
    }

    /** The `id:` values of an SSE body, in order. */
    private fun ids(body: String): List<String> = body.lines().filter { it.startsWith("id:") }.map { it.removePrefix("id:").trim() }

    /**
     * The raw text of the JSON value under top-level-or-nested [key] — the first occurrence of `"key":` and its balanced
     * value, strings honoured. Compares BYTES, which a parsed tree cannot (number spelling, key order).
     */
    private fun member(
        json: String,
        key: String,
    ): String {
        val start = json.indexOf("\"$key\":").also { check(it >= 0) { "no '$key' in ${json.take(EXCERPT)}" } } + key.length + 3
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> if (--depth == 0) return json.substring(start, i + 1)
            }
        }
        error("unbalanced '$key' in ${json.take(EXCERPT)}")
    }

    private fun sequenceOf(
        frames: List<Pair<String, JsonNode>>,
        parameter: String,
    ): List<String> = frames.filter { it.second.path("parameter").asText() == parameter }.map { it.first }

    private fun index(
        frames: List<Pair<String, JsonNode>>,
        name: String,
        parameter: String,
    ): Int = frames.indexOfFirst { it.first == name && it.second.path("parameter").asText() == parameter }

    private fun frame(
        frames: List<Pair<String, JsonNode>>,
        name: String,
        parameter: String,
    ): JsonNode = frames.first { it.first == name && it.second.path("parameter").asText() == parameter }.second

    private fun capture(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        LOGGERS.forEach { (LoggerFactory.getLogger(it) as Logger).addAppender(appender) }
        return appender
    }

    private fun release(appender: ListAppender<ILoggingEvent>) {
        LOGGERS.forEach { (LoggerFactory.getLogger(it) as Logger).detachAppender(appender) }
    }

    private fun lines(appender: ListAppender<ILoggingEvent>): List<String> =
        synchronized(appender.list) {
            appender.list.map { it.formattedMessage }
        }

    private fun awaitNoStreams() = awaitUntil("no evaluation stream is open") { activeEvaluationStreams() == 0 }

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun uuid() = UUID.randomUUID().toString()

    // ---- the fixture ---------------------------------------------------------------------------------------------------

    private fun seedPeople() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Observed evaluation E2E')")
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'pe375-admin@e2e.test', 'PE Admin', 'test', 'pe375-admin-sub', TRUE, TRUE),
                ('$VIEWER_ID', 'pe375-viewer@e2e.test', 'PE Viewer', 'test', 'pe375-viewer-sub', TRUE, FALSE),
                ('$MEMBER_ID', 'pe375-member@e2e.test', 'PE Member', 'test', 'pe375-member-sub', TRUE, FALSE),
                ('$PROMOTER_ID', 'pe375-promoter@e2e.test', 'PE Promoter', 'test', 'pe375-promoter-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$MEMBER_ID', 'viewer'),
                ('$WORKSPACE_ID', '$PROMOTER_ID', 'promoter')
            """.trimIndent(),
        )
    }

    private fun registerDatasource() {
        val source = SharedE2e.scratchDatabase("pe375_source")
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"$DATASOURCE","display_name":"Observed evaluation source","dialect":"POSTGRES",""" +
                        """"jdbc_url":"${source.jdbcUrl}","username":"${source.username}","password":"${source.password}"}""",
                ).post("/api/v1/datasources")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
    }

    private fun createAndReleaseTemplate(
        id: String,
        body: String,
    ) {
        val response =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to id,
                            "dialect" to "POSTGRES",
                            "display_name" to id,
                            "description" to "",
                            "imports" to emptyList<String>(),
                            "body" to body,
                        ),
                    ),
                ).post("/api/v1/templates")
        withClue("template $id: ${response.asString().take(EXCERPT)}") { response.statusCode shouldBe 201 }
        sql(
            "UPDATE template_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND template_id = (SELECT id FROM templates WHERE workspace_id = '$WORKSPACE_ID' AND name = '$id')",
        )
    }

    private fun createAndReleaseSet(
        name: String,
        parameters: List<Map<String, Any?>>,
    ): String {
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to name,
                            "display_name" to name,
                            "description" to "",
                            "parameters" to parameters,
                        ),
                    ),
                ).post("/api/v1/parameter-sets")
        withClue("set $name: ${created.asString().take(EXCERPT)}") { created.statusCode shouldBe 201 }
        val data = mapper.readTree(created.asString())["data"]
        val released =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .header("If-Match", data["body_hash"].asText())
                .post("/api/v1/parameter-sets/${data["id"].asText()}/release")
        withClue("release $name: ${released.asString().take(EXCERPT)}") { released.statusCode shouldBe 200 }
        return data["id"].asText()
    }

    private fun templateSelect(
        name: String,
        template: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "label" to name.replaceFirstChar { it.uppercase() },
            "type" to "STRING",
            "kind" to "SELECT",
            "cardinality" to "SINGLE",
            "source" to mapOf("template" to mapOf("id" to template, "version" to 1), "datasource" to DATASOURCE),
            "depends_on" to dependsOn,
        )

    private fun constants(
        name: String,
        vararg values: String,
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "label" to name.replaceFirstChar { it.uppercase() },
            "type" to "STRING",
            "kind" to "SELECT",
            "cardinality" to "SINGLE",
            "source" to
                mapOf(
                    "constants" to
                        values.mapIndexed { i, v -> mapOf("value" to v, "display_value" to v.uppercase(), "is_default" to (i == 0)) },
                ),
        )

    private fun cascadeParameters(): List<Map<String, Any?>> =
        listOf(
            constants("country", "US", "CA"),
            templateSelect("state", STATES, listOf("country")),
            templateSelect("city", CITIES, listOf("state")),
        )

    private fun slowParameters(): List<Map<String, Any?>> =
        listOf(constants("mode", "fast", "slow", "brief"), templateSelect("wait", SLOW, listOf("mode")))

    private fun sql(statement: String) {
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    companion object {
        private const val EXCERPT = 600
        private const val WAIT_MS = 30_000L
        private const val POLL_MS = 50L
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 4_096

        private val CORRELATION_ID = Regex("\"correlation_id\":\"[^\"]+\"")

        /** The last chunk of a chunked HTTP/1.1 body — the emitter completed. */
        private const val CHUNKED_END = "\r\n0\r\n\r\n"

        /** The evaluate deadline is 20 s; an abort after the 1 s grace (plus a 1 s tick) lands far inside it. */
        private const val ABORT_WITHIN_MS = 15_000L

        private const val WORKSPACE_ID = "e3750000-0000-0000-0000-000000000001"
        private const val WORKSPACE = "pe375-ws"
        private const val ADMIN_ID = "e3750000-0000-0000-0000-0000000000a1"
        private const val VIEWER_ID = "e3750000-0000-0000-0000-0000000000b1"
        private const val MEMBER_ID = "e3750000-0000-0000-0000-0000000000b2"
        private const val PROMOTER_ID = "e3750000-0000-0000-0000-0000000000c1"
        private const val DATASOURCE = "pe375-src"

        /** The metadata database persists across runs in the shared container; names are unique forever — a fresh folder per run. */
        val RUN: String = UUID.randomUUID().toString().substring(0, 8)
        val STATES = "test/pe$RUN/states.sql"
        val CITIES = "test/pe$RUN/cities.sql"
        val SLOW = "test/pe$RUN/slow.sql"
        val CASCADE_SET = "test/pe$RUN/geo_filters"
        val SLOW_SET = "test/pe$RUN/slow_filters"

        const val STATES_SQL =
            "SELECT v AS \"value\", v AS \"display_value\", (v = 'NY') AS \"is_default\" " +
                "FROM (VALUES ('NY'), ('NJ')) AS t(v) WHERE :country = 'US' ORDER BY v"
        const val CITIES_SQL =
            "SELECT c AS \"value\", c AS \"display_value\", FALSE AS \"is_default\" " +
                "FROM (VALUES ('NY', 'New York City'), ('NJ', 'Newark')) AS t(s, c) WHERE s = :state ORDER BY c"

        /** Slow only when asked: the save-time dry run binds no parent value and sleeps 0. */
        const val SLOW_SQL =
            "SELECT 'x' AS \"value\", 'x' AS \"display_value\", FALSE AS \"is_default\" " +
                "FROM pg_sleep(CASE WHEN :mode = 'slow' THEN 60 WHEN :mode = 'brief' THEN 4 ELSE 0 END) " +
                "ORDER BY 1"

        var cascadeSetId: String = ""
        var slowSetId: String = ""

        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "pe375-admin@e2e.test", WORKSPACE)
        private val VIEWER get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "pe375-viewer@e2e.test", WORKSPACE)
        private val MEMBER get() = E2eSession.jwt(JWT_SECRET, MEMBER_ID, "pe375-member@e2e.test", WORKSPACE)
        private val PROMOTER get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "pe375-promoter@e2e.test", WORKSPACE)

        private val LOGGERS =
            listOf(
                "co.datapipelines.web.parameters.stream.ParameterEvaluationStream",
                "co.datapipelines.web.parameters.stream.ParameterEvaluationStreamRegistry",
            )

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redisHost }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
            // The §11.7 abort, made observable: a 1 s grace noticed by a 1 s tick, against a 20 s evaluate deadline (and a
            // 20 s statement bound, so the slow selector outlives the grace by far).
            registry.add("datapipelines.sse.disconnect-grace-seconds") { "1" }
            registry.add("datapipelines.sse.heartbeat-interval-seconds") { "1" }
            registry.add("datapipelines.parameters.evaluate-timeout-seconds") { "20" }
            registry.add("datapipelines.parameters.selector-query-timeout-seconds") { "20" }
            // The one cap (D7) at its smallest, so one open evaluation stream reaches it.
            registry.add("datapipelines.sse.max-streams-per-user") { "1" }
        }
    }
}
