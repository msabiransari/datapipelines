package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * **The `dashboard` key kind over the real stack (L5, #367; spec §16's L5 row, the lane's §A–§B–§C).**
 *
 * A REAL Postgres (metadata + a scratch source), a real Redis, the real HTTP layer, and REAL
 * keys: minted through [E2eAuth] and inserted as their kind's rows, bound through the REST
 * routes, then presented at the runtime's surface and at every OTHER route family. The boards
 * are seeded by SQL (the L2 rule — nothing can release before the evidence gate lands); the
 * source pipeline is created through REST and flipped to RELEASED by SQL. §C's reference proxy
 * runs as a real child process against THIS application with a REAL key — the full path a host
 * would run, browser-less.
 *
 * Every case carries its own non-vacuity: streams are counted frame by frame, refusals are
 * named, and the DATABASE row is read back rather than inferred from a 200.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        // The walk sends more requests than the §12 per-user limiter would answer; none of these refusals is a 429.
        "datapipelines.rate-limit.requests-per-second=100000",
        "datapipelines.rate-limit.requests-per-minute=1000000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // one walk over one fixture, as the sibling E2Es are
class DashboardKeyE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    // ----------------------------------------------------------------------------- §A the kind, the bindings

    @Test
    @Order(1)
    fun `fixture - workspace, boards, a read-only source, and the dashboard keys minted`() {
        seedPeople()
        registerDatasource()
        createTemplate()
        createPipeline()
        releasePipelineBySql()
        seedVisualizations()
        seedBoards()
        seedKeys()
    }

    @Test
    @Order(2)
    fun `A an admin binds the key through the REST route and the key serves the boards beneath the folder`() {
        val bound =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body("""{"api_key_id": "${KEY.id}", "name_prefix": "$BOUND_FOLDER"}""")
                .post("/api/v1/dashboards/bindings")
        withClue(bound.asString().take(EXCERPT)) { bound.statusCode shouldBe 201 }

        val served =
            given()
                .port(port)
                .header("DP-API-Key", KEY.plaintext)
                .get("/api/v1/dashboards/$PUBLIC_BOARD/runtime/config")
        withClue(served.asString().take(EXCERPT)) { served.statusCode shouldBe 200 }
        served.jsonPath().getString("data.dashboard.status") shouldBe "RELEASED"
    }

    @Test
    @Order(3)
    fun `A one dashboard, three credentials that may not see it - ONE 404 body`() {
        // The brief's §A enumeration, verbatim: an UNBOUND key, a key bound ELSEWHERE, and a key
        // of ANOTHER workspace all get the SAME family 404 on the same runtime route — a hidden
        // dashboard is indistinguishable from an absent one (the correlation id is per-request
        // and stripped before the comparison).
        val unbound =
            given()
                .port(port)
                .header("DP-API-Key", UNBOUND_KEY.plaintext)
                .get("/api/v1/dashboards/$PUBLIC_BOARD/runtime/config")
        val elsewhere =
            given()
                .port(port)
                .header("DP-API-Key", PRIVATE_KEY.plaintext)
                .get("/api/v1/dashboards/$PUBLIC_BOARD/runtime/config")
        val foreign =
            given()
                .port(port)
                .header("DP-API-Key", FOREIGN_KEY.plaintext)
                .get("/api/v1/dashboards/$PUBLIC_BOARD/runtime/config")
        listOf("unbound" to unbound, "bound elsewhere" to elsewhere, "foreign workspace" to foreign).forEach { (who, response) ->
            withClue(who) {
                response.statusCode shouldBe 404
                response.jsonPath().getString("error.code") shouldBe "dashboard.not_found"
            }
        }
        stable(unbound) shouldBe stable(elsewhere)
        stable(unbound) shouldBe stable(foreign)
    }

    @Test
    @Order(4)
    fun `A deeper replaces - a key bound at the subtree serves it and the shallower key stops`() {
        val bound =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body("""{"api_key_id": "${PRIVATE_KEY.id}", "name_prefix": "$PRIVATE_FOLDER"}""")
                .post("/api/v1/dashboards/bindings")
        withClue(bound.asString().take(EXCERPT)) { bound.statusCode shouldBe 201 }

        // B (bound deeper) serves the subtree...
        val privateForB =
            given()
                .port(port)
                .header("DP-API-Key", PRIVATE_KEY.plaintext)
                .get("/api/v1/dashboards/$PRIVATE_BOARD/runtime/config")
        withClue(privateForB.asString().take(EXCERPT)) { privateForB.statusCode shouldBe 200 }
        // ...and A (bound at the folder above) STOPS there: R-EP2, not additive.
        val privateForA =
            given()
                .port(port)
                .header("DP-API-Key", KEY.plaintext)
                .get("/api/v1/dashboards/$PRIVATE_BOARD/runtime/config")
        privateForA.statusCode shouldBe 404
        privateForA.jsonPath().getString("error.code") shouldBe "dashboard.not_found"
        // B does not inherit upward: outside its subtree it is served nothing.
        val publicForB =
            given()
                .port(port)
                .header("DP-API-Key", PRIVATE_KEY.plaintext)
                .get("/api/v1/dashboards/$PUBLIC_BOARD/runtime/config")
        publicForB.statusCode shouldBe 404
        publicForB.jsonPath().getString("error.code") shouldBe "dashboard.not_found"
    }

    @Test
    @Order(5)
    fun `A a refresh with the key streams to completion and the DATABASE names the key`() {
        val cid = configurationId(PUBLIC_BOARD)
        val refreshId = uuid()
        val frames = refresh(PUBLIC_BOARD, KEY.plaintext, refreshBody(cid, refreshId, INSTANCE_A))

        withClue(frames.joinToString("|") { it.event }) {
            frames.first().event shouldBe "refresh_started"
            frames.last().event shouldBe "refresh_completed"
        }

        val row =
            rows(
                "SELECT principal_user_id::text AS u, principal_key_id AS k FROM dashboard_refreshes WHERE id = '$refreshId'",
            ).single()
        row["k"] shouldBe KEY.id
        row["u"] shouldBe "NULL" // V43's CHECK: exactly one non-null — the KEY, not the identity

        // The stream carries NO execution ids for this caller: a `dashboard_viewer` key holds no
        // `execution.read`, so the #343 projection strips them — the link is read from the
        // DATABASE (the pane rule, rest-api §23.3).
        withClue(frames.joinToString("|") { it.event }) {
            frames.count { it.event == "source_started" } shouldBe 1
        }
        val started =
            rows(
                "SELECT execution_id::text AS e FROM dashboard_refresh_executions WHERE refresh_id = '$refreshId'",
            ).single()["e"]!!
        val execution =
            rows(
                "SELECT executed_by::text AS by, executed_by_key_kind AS kind, triggered_via AS via " +
                    "FROM pipeline_executions WHERE execution_id = '$started'",
            ).single()
        execution["by"] shouldBe KEY.ownerId
        execution["kind"] shouldBe "dashboard"
        execution["via"] shouldBe "DASHBOARD"
    }

    @Test
    @Order(6)
    fun `A the key revoked MID-REFRESH ends the stream before the terminal frame`() {
        val cid = configurationId(PUBLIC_BOARD)
        val refreshId = uuid()
        val stream = openStream(PUBLIC_BOARD, KEY.plaintext, refreshBody(cid, refreshId, INSTANCE_A))
        try {
            val first = stream.readLine()
            withClue(first) { first.shouldContain("refresh_started") }
            // Revoke while the source is still sleeping: the NEXT write re-judges the credential.
            sql("UPDATE api_keys SET is_revoked = TRUE WHERE id = '${KEY.id}'")
            val rest = generateSequence(stream::readLine).joinToString("\n")
            withClue(rest.take(EXCERPT)) {
                rest.shouldNotContain("refresh_completed")
            }
        } finally {
            stream.close()
            sql("UPDATE api_keys SET is_revoked = FALSE WHERE id = '${KEY.id}'")
        }
        // The refresh itself still runs to its row (P4: a cut reads, never runs) — the row ends.
        waitUntil("the refresh row closes after the cut") {
            rows("SELECT status FROM dashboard_refreshes WHERE id = '$refreshId'").singleOrNull()?.get("status") != "RUNNING"
        }
    }

    // ----------------------------------------------------------------------------- §B confinement

    @Test
    @Order(7)
    fun `B every other route family refuses the kind with the SAME body - before any handler`() {
        val families =
            listOf(
                "GET" to "/api/v1/dashboards",
                "GET" to "/dashboards",
                "GET" to "/partials/dashboards/tree",
                "POST" to "/mcp",
                "GET" to "/api/$PROBE_CATEGORY/v1/anything",
            )
        val responses = families.map { (method, path) -> path to callKey(method, path) }
        responses.forEach { (path, response) ->
            withClue("$path -> ${response.asString().take(EXCERPT)}") {
                response.statusCode shouldBe 403
                response.jsonPath().getString("error.code") shouldBe "endpoint.key_kind_refused"
                response.jsonPath().getString("error.details.reason") shouldBe "dashboard_key_off_surface"
            }
        }
        // ONE refusal body across the families, modulo the request's own URI (details.path is
        // the requested path): same status, same code, same reason, same message — the
        // confinement is central, not per-handler.
        val firstFamily = responses.first().second
        responses.forEach { (path, response) ->
            withClue(path) {
                response.statusCode shouldBe firstFamily.statusCode
                response.jsonPath().getString("error.code") shouldBe firstFamily.jsonPath().getString("error.code")
                response.jsonPath().getString("error.message") shouldBe firstFamily.jsonPath().getString("error.message")
                response.jsonPath().getString("error.user_message") shouldBe firstFamily.jsonPath().getString("error.user_message")
                response.jsonPath().getString("error.details.reason") shouldBe firstFamily.jsonPath().getString("error.details.reason")
            }
        }

        // The promotion family is its own documented refusal (auth.md §7.7): the upstream
        // PromotionServerKeyFilter folds EVERY non-DP-Promotion-Key credential on its prefix —
        // wrong kind included — into the one `auth.promotion.key_invalid`, so a caller cannot
        // classify a stolen credential by asking. The kind never reaches the interceptor there.
        val promotion =
            given()
                .port(port)
                .header("DP-API-Key", KEY.plaintext)
                .get("/api/v1/promotion/inventory")
        promotion.statusCode shouldBe 401
        promotion.jsonPath().getString("error.code") shouldBe "auth.promotion.key_invalid"

        // The OTHER kinds on a runtime route are refused AS TODAY (their own arms, unchanged).
        listOf("mcp" to MCP_KEY.plaintext, "endpoint" to ENDPOINT_KEY.plaintext).forEach { (kind, plaintext) ->
            val answer =
                given()
                    .port(port)
                    .header("DP-API-Key", plaintext)
                    .contentType(ContentType.JSON)
                    .body("""{"instance_id": "$INSTANCE_A"}""")
                    .post("/api/v1/dashboards/$PUBLIC_BOARD/runtime/parameters")
            withClue(kind) {
                answer.statusCode shouldBe 403
                answer.jsonPath().getString("error.code") shouldBe "endpoint.key_kind_refused"
                answer.jsonPath().getString("error.details.reason") shouldBe "${kind}_key_off_surface"
            }
        }
    }

    @Test
    @Order(8)
    fun `B two application users of ONE key run independent refreshes and abort only their own`() {
        val cid = configurationId(PUBLIC_BOARD)
        val slowRefresh = uuid()
        openStream(PUBLIC_BOARD, KEY.plaintext, refreshBody(cid, slowRefresh, INSTANCE_A)).use { slow ->
            slow.readLine().shouldContain("refresh_started")

            // A SECOND instance of the SAME key starts its own refresh of the same dashboard.
            val second = uuid()
            val secondStream = openStream(PUBLIC_BOARD, KEY.plaintext, refreshBody(cid, second, INSTANCE_B))
            secondStream.readLine().shouldContain("refresh_started")

            // B aborts ITS refresh: 202. B aborts A's: the same 404 the #356 rule gives a stranger.
            val own =
                given()
                    .port(port)
                    .header("DP-API-Key", KEY.plaintext)
                    .contentType(ContentType.JSON)
                    .body("""{"instance_id": "$INSTANCE_B"}""")
                    .post("/api/v1/dashboards/$PUBLIC_BOARD/runtime/refreshes/$second/abort")
            withClue(own.asString().take(EXCERPT)) { own.statusCode shouldBe 202 }
            val foreign =
                given()
                    .port(port)
                    .header("DP-API-Key", KEY.plaintext)
                    .contentType(ContentType.JSON)
                    .body("""{"instance_id": "$INSTANCE_B"}""")
                    .post("/api/v1/dashboards/$PUBLIC_BOARD/runtime/refreshes/$slowRefresh/abort")
            foreign.statusCode shouldBe 404
            foreign.jsonPath().getString("error.code") shouldBe "dashboard.refresh.not_found"
            secondStream.close()

            // The refreshes list is the KEY's own rows — every instance of the shared budget.
            val list =
                given()
                    .port(port)
                    .header("DP-API-Key", KEY.plaintext)
                    .get("/api/v1/dashboards/$PUBLIC_BOARD/refreshes")
            list.statusCode shouldBe 200
            list.jsonPath().getList<String>("data.items.refresh_id") shouldContain slowRefresh
        }
    }

    @Test
    @Order(9)
    fun `B the endpoint twin's by-name path never binds a dashboard key - body-equal with an unknown key`() {
        val dashboardKeyName = rows("SELECT name FROM api_keys WHERE id = '${KEY.id}'").single()["name"]!!
        val byName =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body("""{"api_key_name": "$dashboardKeyName", "path_prefix": "/nyc"}""")
                .post("/api/v1/endpoints/bindings")
        val unknown =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body("""{"api_key_name": "no-such-key-anywhere", "path_prefix": "/nyc"}""")
                .post("/api/v1/endpoints/bindings")
        // The twin's own-not-found is `401 auth.api_key.invalid` (the code the endpoint surface
        // has always answered for a key the caller cannot bind); the KIND FILTER is what this
        // asserts: the dashboard key answers the SAME body an unknown key gets, and no row lands.
        byName.statusCode shouldBe 401
        byName.jsonPath().getString("error.code") shouldBe "auth.api_key.invalid"

        // Body-equal modulo the two strings the surface MUST echo (the correlation id and the
        // name it was asked for): the dashboard key meets exactly the answer an unknown key gets.
        fun normalized(response: io.restassured.response.Response): String =
            response
                .jsonPath()
                .getMap<String, Any?>("$")
                .toMutableMap()
                .apply {
                    remove("correlation_id")
                    @Suppress("UNCHECKED_CAST")
                    val error = (get("error") as? MutableMap<String, Any?>)
                    error?.remove("message")
                    @Suppress("UNCHECKED_CAST")
                    (error?.get("details") as? MutableMap<String, Any?>)?.remove("api_key_name")
                }.toString()
        normalized(byName) shouldBe normalized(unknown)
        rows("SELECT COUNT(*)::text AS n FROM endpoint_key_bindings WHERE api_key_id = '${KEY.id}'")
            .single()["n"] shouldBe "0"
    }

    // ----------------------------------------------------------------------------- §C the reference proxy

    @Test
    @Order(10)
    fun `C the reference proxy serves the runtime routes with the key held server-side and fences everything else`() {
        val proxy = Proxy()
        try {
            proxy.start()

            // The runtime route reaches the app through the proxy.
            val config = proxy.fetch("/api/v1/dashboards/$PUBLIC_BOARD/runtime/config")
            withClue(config.take(EXCERPT)) {
                config.shouldContain("RELEASED")
                config.shouldNotContain("proxy.not_found")
            }

            // A refresh streamed THROUGH the proxy runs end to end on the real stack.
            val cid = configurationId(PUBLIC_BOARD)
            val refreshId = uuid()
            val frames = proxy.stream(PUBLIC_BOARD, refreshBody(cid, refreshId, INSTANCE_A))
            withClue(frames.take(EXCERPT)) {
                frames.shouldContain("refresh_started")
                frames.shouldContain("refresh_completed")
            }

            // The fence: a lifecycle route, the refreshes reads, /mcp and a page are the PROXY's
            // 404 — the host application never sees them (§B's leak is closed at the proxy).
            for (p in listOf("/api/v1/dashboards", "/api/v1/dashboards/$PUBLIC_BOARD/refreshes", "/mcp", "/dashboards")) {
                val answer = proxy.raw("GET", p)
                withClue(p) {
                    answer.first shouldBe 404
                    answer.second.shouldContain("proxy.not_found")
                }
            }
        } finally {
            proxy.stop()
        }
    }

    // ----------------------------------------------------------------------------- harness

    private fun configurationId(dashboardId: String): String {
        val config =
            given()
                .port(port)
                .header("DP-API-Key", KEY.plaintext)
                .get("/api/v1/dashboards/$dashboardId/runtime/config")
        withClue(config.asString().take(EXCERPT)) { config.statusCode shouldBe 200 }
        return config.jsonPath().getString("data.configuration_id")
    }

    private fun refreshBody(
        cid: String,
        refreshId: String,
        instance: String,
    ): String =
        """{"refresh_id": "$refreshId", "instance_id": "$instance", "configuration_id": "$cid", "scope": "all", """ +
            """"selections": {}, "parameter_revision": 1, "targets": []}"""

    private fun refresh(
        dashboardId: String,
        key: String,
        body: String,
    ): List<Frame> = openStream(dashboardId, key, body).use { stream -> readAll(stream) }

    private fun openStream(
        dashboardId: String,
        key: String,
        body: String,
    ): BufferedReader {
        val connection =
            (
                URI
                    .create("http://localhost:$port/api/v1/dashboards/$dashboardId/runtime/visualizations")
                    .toURL()
                    .openConnection() as HttpURLConnection
            ).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("DP-API-Key", key)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                connectTimeout = 5_000
                readTimeout = 90_000
            }
        connection.outputStream.use { it.write(body.toByteArray()) }
        return BufferedReader(InputStreamReader(connection.inputStream))
    }

    private fun readAll(stream: BufferedReader): List<Frame> {
        val frames = mutableListOf<Frame>()
        var event: String? = null
        val data = StringBuilder()

        fun flush() {
            if (event != null) frames.add(Frame(event!!, data.toString()))
            event = null
            data.setLength(0)
        }
        generateSequence(stream::readLine).forEach { line ->
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                line.isEmpty() -> flush()
            }
        }
        flush()
        return frames
    }

    private data class Frame(
        val event: String,
        val raw: String,
    ) {
        private val mapper = ObjectMapper()

        /** The frame's `data:` JSON — one read per field, never a string probe. */
        fun data(field: String): String {
            val node = mapper.readTree(raw)
            return node.path(field).asText()
        }
    }

    /**
     * The response body as a map, with the per-request correlation id — and optionally the
     * echoed message — dropped: the shape two refusals must share before the comparison.
     */
    private fun stable(
        response: io.restassured.response.Response,
        vararg alsoDropped: String,
    ): String =
        response
            .jsonPath()
            .getMap<String, Any?>("$")
            .toMutableMap()
            .apply {
                remove("correlation_id")
                alsoDropped.forEach { remove(it) }
            }.toString()

    private fun callKey(
        method: String,
        path: String,
    ): io.restassured.response.Response {
        val spec =
            given()
                .port(port)
                .header("DP-API-Key", KEY.plaintext)
                .contentType(ContentType.JSON)
                .body("{}")
        return if (method == "POST") spec.post(path) else spec.get(path)
    }

    private fun waitUntil(
        what: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        error("timed out waiting: $what")
    }

    /** The reference proxy as a CHILD PROCESS (§C): the script, this app, the real key. */
    private inner class Proxy {
        private var process: Process? = null
        private val proxyPort = 18_367 + (port % 1000)

        fun start() {
            val nodePresent = runCatching { ProcessBuilder("node", "--version").start().waitFor() }.getOrDefault(-1)
            Assumptions.assumeTrue(
                nodePresent == 0,
                "editorJsTest-style skip: node not on PATH - the reference proxy needs Node >= 18",
            )
            process =
                ProcessBuilder("node", findProxyScript())
                    .apply {
                        environment()["DP_BASE_URL"] = "http://localhost:$port"
                        environment()["DASHBOARD_KEY"] = KEY.plaintext
                        environment()["PORT"] = proxyPort.toString()
                    }.start()
            waitUntil("the proxy answers on :$proxyPort") {
                runCatching { raw("GET", "/readiness-probe").first }.getOrDefault(-1) == 404
            }
        }

        private fun findProxyScript(): String {
            var dir: java.nio.file.Path? =
                java.nio.file.Paths
                    .get("")
                    .toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve("examples/dashboard-proxy/proxy.mjs")
                if (candidate.toFile().isFile) return candidate.toString()
                dir = dir.parent
            }
            error("examples/dashboard-proxy/proxy.mjs not found walking up")
        }

        /** Status + body, for the fence assertions. */
        fun raw(
            method: String,
            path: String,
        ): Pair<Int, String> {
            val connection =
                (URI.create("http://127.0.0.1:$proxyPort$path").toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 5_000
                    readTimeout = 10_000
                }
            val code = connection.responseCode
            val body = (connection.errorStream ?: connection.inputStream).bufferedReader().readText()
            return code to body
        }

        fun fetch(path: String): String {
            val connection =
                (URI.create("http://127.0.0.1:$proxyPort$path").toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 5_000
                    readTimeout = 60_000
                }
            return (connection.errorStream ?: connection.inputStream).bufferedReader().readText()
        }

        fun stream(
            dashboardId: String,
            body: String,
        ): String {
            val connection =
                (
                    URI
                        .create("http://127.0.0.1:$proxyPort/api/v1/dashboards/$dashboardId/runtime/visualizations")
                        .toURL()
                        .openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = 5_000
                    readTimeout = 90_000
                }
            connection.outputStream.use { it.write(body.toByteArray()) }
            return connection.inputStream.bufferedReader().readText()
        }

        fun stop() {
            process?.destroy()
            process?.waitFor(5, TimeUnit.SECONDS)
        }
    }

    // ----------------------------------------------------------------------------- seeding

    private fun seedPeople() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Dashboard Keys')")
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$FOREIGN_WS_ID', '$FOREIGN_WS', 'Other Shop')")
        listOf(ADMIN_ID to "admin", USER_B_ID to "userb").forEach { (id, slug) ->
            sql(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                    "('$id', '$slug@dbkey.test', '$slug', 'test', '$slug-sub', TRUE, ${slug == "admin"})",
            )
            sql(
                "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$id', " +
                    (if (slug == "admin") "'workspace_admin'" else "'viewer'") + ")",
            )
        }
    }

    private fun registerDatasource() {
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"dbkey-src","display_name":"Key source","dialect":"POSTGRES","jdbc_url":"${source.jdbcUrl}",""" +
                        """"username":"${source.username}","password":"${source.password}"}""",
                ).post("/api/v1/datasources")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
    }

    private fun createTemplate() {
        val response =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"id": "dbkey/templates/small.sql", "dialect": "POSTGRES", "display_name": "small", "description": "", """ +
                        """"imports": [], "body": "SELECT 1 AS x FROM pg_sleep(2)"}""",
                ).post("/api/v1/templates")
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 201 }
    }

    private fun createPipeline() {
        val node =
            mapOf(
                "id" to "read",
                "description" to "the dashboard key's source",
                "type" to "DQL",
                "source" to "dbkey-src",
                "template" to mapOf("id" to "dbkey/templates/small.sql", "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to emptyList<String>(),
            )
        val body =
            mapOf(
                "schema_version" to 1,
                "name" to PIPELINE,
                "display_name" to PIPELINE,
                "description" to "dashboard key E2E source",
                "parameters" to emptyMap<String, Any>(),
                "nodes" to listOf(node),
            )
        val response =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(body))
                .post("/api/v1/pipelines")
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 201 }
    }

    private fun releasePipelineBySql() {
        sql(
            "UPDATE pipeline_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND pipeline_id = (SELECT id FROM pipelines " +
                "WHERE name = '$PIPELINE' AND workspace_id = '$WORKSPACE_ID')",
        )
        sql("UPDATE pipelines SET current_version = 1 WHERE name = '$PIPELINE' AND workspace_id = '$WORKSPACE_ID'")
    }

    private fun seedVisualizations() {
        val body =
            """{"display_name":"X","renderer":{"kind":"table","version":"1"},"inputs":{"main":{"columns":[{"name":"x",""" +
                """"type":"INTEGER","nullable":false}]}},"config":{},"bindings":{"cells.x":"x"}}"""
        val id = uuid()
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', '$VIZ', '$VIZ', '', 1, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, " +
                "released_at, released_by, created_by) " +
                "VALUES ('$id', 1, '$body'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
    }

    private fun seedBoards() {
        listOf(
            PUBLIC_BOARD to "dbkey/boards/public_board",
            OUTSIDE_BOARD to "dbkey/other/outside_board",
            PRIVATE_BOARD to "dbkey/boards/private/secret_board",
        ).forEach { (id, name) ->
            val body =
                """{"display_name":"$name","sources":[{"name":"s1","pipeline":{"name":"$PIPELINE","version":1},"parameters":{}}],""" +
                    """"visualizations":[{"name":"v1","type":"visualization","visualization":{"name":"$VIZ","version":1},""" +
                    """"inputs":{"main":{"source":"s1"}}}],"layout":{}}"""
            sql(
                "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                    "VALUES ('$id', '$WORKSPACE_ID', '$name', '$name', '', 1, '$ADMIN_ID')",
            )
            sql(
                "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, " +
                    "released_at, released_by, created_by) " +
                    "VALUES ('$id', 1, '${body.replace("'", "''")}'::jsonb, 'RELEASED', 'seeded-$id', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
            )
        }
    }

    /** The dashboard keys, their identities, and the two decoy kinds — the role walk's seeding shape (keys v2 A13). */
    private fun seedKeys() {
        listOf(KEY, PRIVATE_KEY, UNBOUND_KEY, MCP_KEY, ENDPOINT_KEY, FOREIGN_KEY).forEach { key ->
            sql(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                    "('${key.ownerId}', '${key.id.lowercase()}@keys.invalid', '${key.name}', 'key', '${key.id}', TRUE, FALSE, 'service')",
            )
        }

        // The FOREIGN key is pinned to ANOTHER workspace — its 404 on our dashboards is the
        // non-disclosure rule (D-R5), body-equal with the unbound key's.
        data class KeyRow(
            val key: E2eAuth.SeededKey,
            val ws: String,
            val kind: String,
            val role: String,
        )
        listOf(
            KeyRow(KEY, WORKSPACE_ID, "dashboard", "dashboard_viewer"),
            KeyRow(PRIVATE_KEY, WORKSPACE_ID, "dashboard", "dashboard_viewer"),
            KeyRow(UNBOUND_KEY, WORKSPACE_ID, "dashboard", "dashboard_viewer"),
            KeyRow(MCP_KEY, WORKSPACE_ID, "mcp", "author"),
            KeyRow(ENDPOINT_KEY, WORKSPACE_ID, "endpoint", "api_caller"),
            KeyRow(FOREIGN_KEY, FOREIGN_WS_ID, "dashboard", "dashboard_viewer"),
        ).forEach { row ->
            sql(
                "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                    "VALUES ('${row.key.id}', '${row.key.ownerId}', '$ADMIN_ID', '${row.key.name}', '${row.key.hash}', " +
                    "'${row.ws}', '${row.kind}', '${row.role}')",
            )
        }
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun rows(query: String): List<Map<String, String>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection
                .createStatement()
                .executeQuery(query)
                .use { rs ->
                    val meta = rs.metaData
                    val out = mutableListOf<Map<String, String>>()
                    while (rs.next()) {
                        out.add((1..meta.columnCount).associate { meta.getColumnLabel(it) to (rs.getString(it) ?: "NULL") })
                    }
                    out
                }
        }

    private fun uuid(): String = UUID.randomUUID().toString()

    @AfterAll
    fun done() {
        // Nothing process-scoped survives the class: the proxy stops inside its test.
    }

    private val postgres get() = SharedE2e.postgres
    private val source = SharedE2e.scratchDatabase("dbkey_source")

    companion object {
        private const val EXCERPT = 400
        private const val WORKSPACE = "dbkey"
        private const val PIPELINE = "dbkey/pipelines/small"
        private const val VIZ = "dbkey/charts/x"
        private const val BOUND_FOLDER = "dbkey/boards"
        private const val PRIVATE_FOLDER = "dbkey/boards/private"
        private const val PROBE_CATEGORY = "dbkeyprobe"

        private const val WORKSPACE_ID = "db100000-0000-0000-0000-000000000001"
        private const val FOREIGN_WS_ID = "db100000-0000-0000-0000-000000000009"
        private const val FOREIGN_WS = "dbkey-other"
        private const val ADMIN_ID = "db100000-0000-0000-0000-000000000002"
        private const val USER_B_ID = "db100000-0000-0000-0000-000000000003"

        private const val PUBLIC_BOARD = "db200000-0000-0000-0000-000000000001"
        private const val OUTSIDE_BOARD = "db200000-0000-0000-0000-000000000002"
        private const val PRIVATE_BOARD = "db200000-0000-0000-0000-000000000003"

        private const val INSTANCE_A = "db400000-0000-4000-8000-000000000001"
        private const val INSTANCE_B = "db400000-0000-4000-8000-000000000002"

        /** The keys, minted like every suite's: id, hash for the row, plaintext for the header. */
        private val KEY = E2eAuth.generateKey("dbkey-bound", ownerId = "db300000-0000-0000-0000-000000000001")
        private val PRIVATE_KEY = E2eAuth.generateKey("dbkey-private", ownerId = "db300000-0000-0000-0000-000000000002")
        private val UNBOUND_KEY = E2eAuth.generateKey("dbkey-unbound", ownerId = "db300000-0000-0000-0000-000000000003")
        private val MCP_KEY = E2eAuth.generateKey("dbkey-mcp", ownerId = "db300000-0000-0000-0000-000000000004")
        private val ENDPOINT_KEY = E2eAuth.generateKey("dbkey-endpoint", ownerId = "db300000-0000-0000-0000-000000000005")
        private val FOREIGN_KEY = E2eAuth.generateKey("dbkey-foreign", ownerId = "db300000-0000-0000-0000-000000000006")

        private val E2E_SECRET = E2eSession.newSecret()
        private val ADMIN get() = E2eSession.jwt(E2E_SECRET, ADMIN_ID, "dbkey-admin@e2e.test", WORKSPACE)

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { E2E_SECRET }
            registry.add("datapipelines.db.encryption-key") { E2eSession.newSecret() }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // Local accounts: the OIDC-less deployment's credential (auth.md §5A.2) — the session
            // JWTs here are minted directly, but the context refuses to start with no provider at all.
            registry.add("datapipelines.auth.local.enabled") { "true" }
        }
    }
}
