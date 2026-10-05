package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * **The dashboard DRAFT preview over the real stack (#369; the implementation spec's §6.3, R1/R2).**
 *
 * A REAL Postgres (metadata + a scratch source database) and a real Redis; the drafts and their released pins are
 * SEEDED by SQL (the release gate is untouched), the source pipeline created through REST and flipped to RELEASED by
 * SQL — the runtime E2E's mould, minus the stream pathologies this lane does not touch.
 *
 * | Ruling / scenario | Test |
 * |---|---|
 * | R2 absent = today (the golden): the four routes answer the released board identically, without and with `?version=` | `golden` |
 * | the author previews the draft — a 303 onto the workspace (the chip, the attribute); its runtime configuration | `the author previews` |
 * | the draft's visualizations STREAM through the runtime, and the row names the draft version | `the draft streams` |
 * | a viewer reads the same draft (D50: every reader an executor) | `a viewer` |
 * | the promoter's lens refuses the draft — the family 404, page and route | `a promoter` |
 * | a DISCARDED version is the family 404 (route); a board with no live version is absent entirely (page 404) | `a discarded version` |
 * | #459: a draft pinning a live DRAFT visualization resolves without release | `a draft pinning` |
 * | R2: an edit between two calls is `configuration_stale`, no new machinery | `a draft edited between two calls` |
 * | a `dashboard` key never names a version (the version routes are the session page's) | `a dashboard_viewer key` |
 * | `version` is a bounded positive integer | `a malformed version` |
 *
 * Non-vacuity: the golden's two responses are compared field-stable (`correlation_id` dropped — it is per-request),
 * the stream is counted frame by frame, and the refresh row is read back from the DATABASE.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "datapipelines.rate-limit.requests-per-second=100000",
        "datapipelines.rate-limit.requests-per-minute=1000000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions") // one walk over one fixture, as the sibling E2Es are
class DashboardDraftPreviewE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val http = HttpClient.newHttpClient()

    // ------------------------------------------------------------------------------------------ 1 fixture

    @Test
    @Order(1)
    fun `fixture - workspace, people, a released source, released and draft boards, and the dashboard key`() {
        seedPeople()
        registerDatasource()
        createAndReleaseTemplate()
        createPipeline()
        releasePipelineBySql()
        seedVisualizations()
        seedBoards()
        seedKeys()
    }

    // ------------------------------------------------------------------------------------------ 2 the golden

    @Test
    @Order(2)
    fun `golden - the released board answers identically with and without the current version named`() {
        val without = get("/api/v1/dashboards/$RELEASED_BOARD/runtime/config", ADMIN)
        val with = get("/api/v1/dashboards/$RELEASED_BOARD/runtime/config?version=1", ADMIN)
        withClue("config: ${without.asString().take(EXCERPT)} vs ${with.asString().take(EXCERPT)}") {
            with.statusCode shouldBe 200
            with.statusCode shouldBe without.statusCode
            stable(with) shouldBe stable(without)
            with.jsonPath().getString("data.dashboard.version") shouldBe "1"
        }

        val withoutParameters =
            post(
                "/api/v1/dashboards/$RELEASED_BOARD/runtime/parameters",
                ADMIN,
                parametersBody(configurationId(RELEASED_BOARD, ADMIN, null), INSTANCE_A),
            )
        val withParameters =
            post(
                "/api/v1/dashboards/$RELEASED_BOARD/runtime/parameters?version=1",
                ADMIN,
                parametersBody(configurationId(RELEASED_BOARD, ADMIN, 1), INSTANCE_B),
            )
        withClue("parameters: ${withoutParameters.asString().take(EXCERPT)} vs ${withParameters.asString().take(EXCERPT)}") {
            withoutParameters.statusCode shouldBe 200
            stable(withParameters) shouldBe stable(withoutParameters)
        }

        // The same UNKNOWN refresh id both times: the refusal names the id the caller minted, so
        // the two arms must mint the SAME one for the bodies to be field-stable.
        val ghost = uuid()
        val withoutAbort = post("/api/v1/dashboards/$RELEASED_BOARD/runtime/refreshes/$ghost/abort", ADMIN, abortBody())
        val withAbort = post("/api/v1/dashboards/$RELEASED_BOARD/runtime/refreshes/$ghost/abort?version=1", ADMIN, abortBody())
        withClue("abort: ${withoutAbort.asString().take(EXCERPT)} vs ${withAbort.asString().take(EXCERPT)}") {
            withoutAbort.statusCode shouldBe 404
            withAbort.statusCode shouldBe withoutAbort.statusCode
            stable(withAbort) shouldBe stable(withoutAbort)
            withAbort.jsonPath().getString("error.code") shouldBe "dashboard.refresh.not_found"
        }

        // The stream route threads the same version (the golden's fourth arm): both arms complete,
        // and the frames a caller sees differ by nothing but the ids the caller minted.
        val withoutFrames = refresh(RELEASED_BOARD, ADMIN, refreshBody(configurationId(RELEASED_BOARD, ADMIN, null), null))
        val withFrames = refresh(RELEASED_BOARD, ADMIN, refreshBody(configurationId(RELEASED_BOARD, ADMIN, 1), null), 1)
        withClue("stream: ${withoutFrames.names()} vs ${withFrames.names()}") {
            withoutFrames.names() shouldBe withFrames.names()
            withoutFrames
                .of("source_completed")
                .single()
                .data["rows"]
                .asInt() shouldBe
                withFrames
                    .of("source_completed")
                    .single()
                    .data["rows"]
                    .asInt()
        }
    }

    // ------------------------------------------------------------------------------------------ 3 the draft

    @Test
    @Order(3)
    fun `the author previews the draft - a 303 onto the workspace, whose page carries the chip and the version attribute`() {
        // The compatibility route answers SEE_OTHER — pinned with redirects DISABLED, the
        // location naming the canonical workspace URL (built server-side, never echoed).
        val redirect =
            given()
                .port(port)
                .asSession(ADMIN)
                .redirects()
                .follow(false)
                .`when`()
                .get("/dashboards/$DRAFT_BOARD/preview?version=1")
        withClue(redirect.asString().take(EXCERPT)) {
            redirect.statusCode shouldBe 303
            redirect.getHeader("Location") shouldBe "/dashboards/$DRAFT_BOARD?version=1&tab=board"
        }

        val page = get("/dashboards/$DRAFT_BOARD/preview?version=1", ADMIN)
        withClue(page.asString().take(EXCERPT)) {
            page.statusCode shouldBe 200
            page.body().asString() shouldContain """data-dp-dashboard-id="$DRAFT_BOARD""""
            page.body().asString() shouldContain """data-dp-dashboard-version="1""""
            page.body().asString() shouldContain "data-dp-viewed-label"
            page.body().asString() shouldContain "v1 · draft"
            page.body().asString() shouldContain "dpprev/boards/draft_board"
            page.body().asString() shouldContain """data-dp-tab="board""""
            page.body().asString() shouldNotContain """data-dp-refusal-code=""""
        }

        val config = get("/api/v1/dashboards/$DRAFT_BOARD/runtime/config?version=1", ADMIN)
        withClue(config.asString().take(EXCERPT)) {
            config.statusCode shouldBe 200
            config.jsonPath().getString("data.dashboard.status") shouldBe "DRAFT"
            config.jsonPath().getInt("data.dashboard.version") shouldBe 1
        }
    }

    @Test
    @Order(4)
    fun `the draft streams - a refresh against the named version runs its sources and the row names the draft`() {
        val cid = configurationId(DRAFT_BOARD, ADMIN, 1)
        val refreshId = uuid()
        val frames = refresh(DRAFT_BOARD, ADMIN, refreshBody(cid, refreshId), 1)
        frames.of("source_started").size shouldBe 1
        frames
            .of("source_completed")
            .single()
            .data["rows"]
            .asInt() shouldBe 2
        frames
            .of("visualization_data")
            .single()
            .data["bindings"]["cells.x"]
            .map { it.asInt() } shouldBe listOf(1, 2)
        frames
            .of("refresh_completed")
            .single()
            .data["status"]
            .asText() shouldBe "COMPLETED"

        val row = rows("SELECT status, dashboard_version::text AS v FROM dashboard_refreshes WHERE id = '$refreshId'").single()
        row["status"] shouldBe "COMPLETED"
        row["v"] shouldBe "1" // the DRAFT version the caller named, not a release
    }

    @Test
    @Order(5)
    fun `a viewer reads the same draft - the page and the runtime configuration`() {
        // The redirect target carries the viewer the same workspace page (the family's read).
        val page = get("/dashboards/$DRAFT_BOARD/preview?version=1", VIEWER)
        withClue(page.asString().take(EXCERPT)) {
            page.statusCode shouldBe 200
            page.body().asString() shouldContain """data-dp-dashboard-version="1""""
        }
        val config = get("/api/v1/dashboards/$DRAFT_BOARD/runtime/config?version=1", VIEWER)
        withClue(config.asString().take(EXCERPT)) {
            config.statusCode shouldBe 200
            config.jsonPath().getString("data.dashboard.status") shouldBe "DRAFT"
        }
    }

    // ------------------------------------------------------------------------------------------ 4 the lens

    @Test
    @Order(6)
    fun `a promoter is refused the draft - the family 404 on the page and on the version route`() {
        val page = get("/dashboards/$DRAFT_BOARD/preview?version=1", PROMOTER)
        page.statusCode shouldBe 404

        val config = get("/api/v1/dashboards/$DRAFT_BOARD/runtime/config?version=1", PROMOTER)
        withClue(config.asString().take(EXCERPT)) {
            config.statusCode shouldBe 404
            config.jsonPath().getString("error.code") shouldBe "dashboard.not_found"
            config.jsonPath().getMap<String, Any?>("error.details")["version"] shouldBe 1
        }
    }

    // ------------------------------------------------------------------------------------------ 5 discarded

    @Test
    @Order(7)
    fun `a discarded version is the family 404 on the route, and a board with no live version is absent entirely`() {
        sql(
            // The DISCARDED stamp is a CHECK: a discarded version carries its release stamp too
            // (the versioning table's rule) — the runtime E2E's runtimeStatusStamp shape.
            "UPDATE dashboard_versions SET status = 'DISCARDED', " +
                "released_at = COALESCE(released_at, NOW()), released_by = COALESCE(released_by, '$ADMIN_ID'), " +
                "discarded_at = NOW(), discarded_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND dashboard_id = '$DISCARDED_BOARD'",
        )

        val config = get("/api/v1/dashboards/$DISCARDED_BOARD/runtime/config?version=1", ADMIN)
        withClue(config.asString().take(EXCERPT)) {
            config.statusCode shouldBe 404
            config.jsonPath().getString("error.code") shouldBe "dashboard.not_found"
            config.jsonPath().getMap<String, Any?>("error.details")["version"] shouldBe 1
        }

        // The visibility oracle runs FIRST (the preview handler's rule, unchanged by the
        // redirect): with the only version discarded and no release, the dashboard has nothing
        // the caller can see — the route is the family's 404, never a redirect to one. The
        // refusal IN PLACE is the answer for a dashboard the caller CAN see (the pin-refusal
        // case above), not this one.
        val page = get("/dashboards/$DISCARDED_BOARD/preview?version=1", ADMIN)
        page.statusCode shouldBe 404
    }

    // ------------------------------------------------------------------------------------------ 6 draft dependency admission (#459)

    @Test
    @Order(8)
    fun `a draft pinning a DRAFT visualization resolves without a release`() {
        val config = get("/api/v1/dashboards/$DRAFT_PIN_BOARD/runtime/config?version=1", ADMIN)
        withClue(config.asString().take(EXCERPT)) {
            config.statusCode shouldBe 200
            config.jsonPath().getString("data.dashboard.status") shouldBe "DRAFT"
            config.jsonPath().getString("data.configuration_id").length shouldBe 64
        }

        val page = get("/dashboards/$DRAFT_PIN_BOARD/preview?version=1", ADMIN)
        withClue(page.asString().take(EXCERPT)) {
            page.statusCode shouldBe 200
            page.body().asString() shouldNotContain "dashboard.runtime.dependency_missing"
            page.body().asString() shouldContain "data-dp-dashboard-version=\"1\""
        }
    }

    @Test
    @Order(8)
    fun `a published dashboard still refuses a draft dependency and release never publishes it implicitly`() {
        val hash = get("/api/v1/dashboards/$DRAFT_PIN_BOARD", ADMIN).jsonPath().getString("data.body_hash")
        val release =
            given()
                .port(port)
                .asSession(ADMIN)
                .header("If-Match", hash)
                .post("/api/v1/dashboards/$DRAFT_PIN_BOARD/release")
        release.statusCode shouldBe 409
        release.jsonPath().getString("error.code") shouldBe "dashboard.release.dependency_not_released"
        sql(
            "UPDATE dashboard_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' WHERE dashboard_id = '$DRAFT_PIN_BOARD'",
        )
        sql("UPDATE dashboards SET current_version = 1 WHERE id = '$DRAFT_PIN_BOARD'")
        try {
            val config = get("/api/v1/dashboards/$DRAFT_PIN_BOARD/runtime/config", ADMIN)
            config.statusCode shouldBe 409
            config.jsonPath().getString("error.code") shouldBe "dashboard.runtime.dependency_missing"
            config.jsonPath().getString("error.details.reason") shouldBe "not_released"
        } finally {
            sql(
                "UPDATE dashboard_versions SET status = 'DRAFT', released_at = NULL, released_by = NULL WHERE dashboard_id = '$DRAFT_PIN_BOARD'",
            )
            sql("UPDATE dashboards SET current_version = NULL WHERE id = '$DRAFT_PIN_BOARD'")
        }
    }

    // ------------------------------------------------------------------------------------------ 7 the stale guard (R2)

    @Test
    @Order(9)
    fun `a draft edited between two calls is configuration_stale - the served hash is in the configuration id`() {
        val before = configurationId(DRAFT_BOARD, ADMIN, 1)
        sql("UPDATE dashboard_versions SET body_hash = 'edited-by-the-author' WHERE version = 1 AND dashboard_id = '$DRAFT_BOARD'")
        val after = configurationId(DRAFT_BOARD, ADMIN, 1)
        withClue("the edited draft must resolve to a DIFFERENT configuration id") { after shouldNotBe before }

        val stale =
            post(
                "/api/v1/dashboards/$DRAFT_BOARD/runtime/parameters?version=1",
                ADMIN,
                parametersBody(before, INSTANCE_A),
            )
        withClue(stale.asString().take(EXCERPT)) {
            stale.statusCode shouldBe 409
            stale.jsonPath().getString("error.code") shouldBe "dashboard.runtime.configuration_stale"
        }
    }

    // ------------------------------------------------------------------------------------------ 8 the key kind

    @Test
    @Order(10)
    fun `a dashboard_viewer key serves the released view unnamed and never names a version`() {
        val bind =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body("""{"api_key_id": "${KEY.id}", "name_prefix": "dpprev/boards"}""")
                .post("/api/v1/dashboards/bindings")
        withClue(bind.asString().take(EXCERPT)) { bind.statusCode shouldBe 201 }

        val released = given().port(port).header("DP-API-Key", KEY.plaintext).get("/api/v1/dashboards/$RELEASED_BOARD/runtime/config")
        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe 200 }

        val versioned =
            given()
                .port(
                    port,
                ).header("DP-API-Key", KEY.plaintext)
                .get("/api/v1/dashboards/$RELEASED_BOARD/runtime/config?version=1")
        withClue(versioned.asString().take(EXCERPT)) {
            versioned.statusCode shouldBe 403
            versioned.jsonPath().getString("error.code") shouldBe "dashboard.key.kind_refused"
        }

        val page = given().port(port).header("DP-API-Key", KEY.plaintext).get("/dashboards/$DRAFT_BOARD/preview?version=1")
        withClue(page.asString().take(EXCERPT)) {
            page.statusCode shouldBe 403
            // The page is off the dashboard key's surface entirely: the interceptor's standard kind refusal.
            page.jsonPath().getString("error.code") shouldBe "endpoint.key_kind_refused"
            page.jsonPath().getString("error.details.reason") shouldBe "dashboard_key_off_surface"
        }
    }

    // ------------------------------------------------------------------------------------------ 9 the parameter

    @Test
    @Order(11)
    fun `a malformed version is the 400 before anything is looked up`() {
        val zero = get("/api/v1/dashboards/$DRAFT_BOARD/runtime/config?version=0", ADMIN)
        withClue(zero.asString().take(EXCERPT)) {
            zero.statusCode shouldBe 400
            zero.jsonPath().getString("error.code") shouldBe "pipeline.execution.invalid_parameter_type"
        }
        val notANumber = get("/api/v1/dashboards/$DRAFT_BOARD/runtime/config?version=abc", ADMIN)
        withClue(notANumber.asString().take(EXCERPT)) {
            notANumber.statusCode shouldBe 400
        }
    }

    // ------------------------------------------------------------------------------------------ wire helpers

    private fun get(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).get(path)

    private fun post(
        path: String,
        session: String,
        body: String,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .body(body)
            .post(path)

    /** The §8.2 body: the per-instance call whose answer is byte-stable across two instances of one version. */
    private fun parametersBody(
        configurationId: String,
        instance: String,
    ): String =
        """{"configuration_id":"$configurationId","instance_id":"$instance","intent":"bootstrap",""" +
            """"selections":{},"scope":"all"}"""

    /** The response body with the per-request `correlation_id` dropped — the stable field set, two calls compared. */
    private fun stable(response: Response): String =
        response
            .jsonPath()
            .getMap<String, Any?>("$")
            .toMutableMap()
            .apply { remove("correlation_id") }
            .toString()

    private fun configurationId(
        dashboard: String,
        session: String,
        version: Int?,
    ): String {
        val path =
            if (version == null) {
                "/api/v1/dashboards/$dashboard/runtime/config"
            } else {
                "/api/v1/dashboards/$dashboard/runtime/config?version=$version"
            }
        val response = get(path, session)
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
        return response.jsonPath().getString("data.configuration_id")
    }

    private fun abortBody(): String = """{"instance_id":"$INSTANCE_A"}"""

    private fun refreshBody(
        configurationId: String,
        refreshId: String?,
    ): String =
        """{"configuration_id":"$configurationId","instance_id":"$INSTANCE_A",""" +
            """"refresh_id":"${refreshId ?: uuid()}","parameter_revision":1,"selections":{},"scope":"all","targets":[]}"""

    private fun refresh(
        dashboard: String,
        session: String,
        body: String,
        version: Int? = null,
    ): List<Frame> {
        val suffix = if (version == null) "" else "?version=$version"
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/dashboards/$dashboard/runtime/visualizations$suffix"))
                .header("Cookie", E2eSession.cookieHeader(session))
                .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            error("status ${response.statusCode()}: ${response.body().readNBytes(EXCERPT).decodeToString()}")
        }
        val frames = CopyOnWriteArrayList<Frame>()
        val finished = CompletableFuture<Unit>()
        Thread {
            runCatching { parse(response.body()) { frames += it } }
            finished.complete(Unit)
        }.apply {
            isDaemon = true
            start()
        }
        finished.get(STREAM_WAIT_SECONDS, TimeUnit.SECONDS)
        return frames.toList().also { framesRead ->
            withClue("the stream must end with refresh_completed: ${framesRead.names()}") {
                framesRead
                    .last()
                    .event shouldBe "refresh_completed"
            }
        }
    }

    private fun parse(
        input: InputStream,
        onFrame: (Frame) -> Unit,
    ) {
        var event: String? = null
        var id = 0
        BufferedReader(InputStreamReader(input)).forEachLine { line ->
            when {
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("id:") -> id = line.removePrefix("id:").trim().toInt()
                line.startsWith("data:") -> onFrame(Frame(event ?: "", id, mapper.readTree(line.removePrefix("data:").trim())))
            }
        }
    }

    private data class Frame(
        val event: String,
        val id: Int,
        val data: JsonNode,
    )

    private fun List<Frame>.names(): List<String> = map { it.event }

    private fun List<Frame>.of(event: String): List<Frame> = filter { it.event == event }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun seedPeople() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Draft Preview')")
        listOf(ADMIN_ID to "admin", VIEWER_ID to "viewer", PROMOTER_ID to "promoter").forEach { (id, slug) ->
            // A suite-unique subject: the E2E suites share one Postgres per fork, and a bare
            // '$slug-sub' collides with DashboardKeyE2eTest's admin on uq_users_provider_subject (#423).
            sql(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                    "('$id', '$slug@dpprev.test', '$slug', 'test', 'dpprev-$slug-sub', TRUE, ${slug == "admin"})",
            )
            val role =
                when (slug) {
                    "admin" -> "workspace_admin"
                    "promoter" -> "promoter"
                    else -> "viewer"
                }
            sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$id', '$role')")
        }
    }

    private fun registerDatasource() {
        val created =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"name":"dpprev-src","display_name":"Draft preview source","dialect":"POSTGRES",""" +
                        """"jdbc_url":"${source.jdbcUrl}","username":"${source.username}","password":"${source.password}"}""",
                ).post("/api/v1/datasources")
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
    }

    private fun createAndReleaseTemplate() {
        val response =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body(
                    """{"id": "dpprev/templates/small.sql", "dialect": "POSTGRES", "display_name": "small", "description": "", """ +
                        """"imports": [], "body": "SELECT 1 AS x UNION ALL SELECT 2 AS x"}""",
                ).post("/api/v1/templates")
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 201 }
        sql(
            "UPDATE template_versions SET status = 'RELEASED', released_at = NOW(), released_by = '$ADMIN_ID' " +
                "WHERE version = 1 AND template_id IN (SELECT id FROM templates WHERE workspace_id = '$WORKSPACE_ID')",
        )
    }

    private fun createPipeline() {
        val node =
            mapOf(
                "id" to "read",
                "description" to "the draft preview's source",
                "type" to "DQL",
                "source" to "dpprev-src",
                "template" to mapOf("id" to "dpprev/templates/small.sql", "version" to 1),
                "output" to mapOf("target" to "caller"),
                "depends_on" to emptyList<String>(),
            )
        val body =
            mapOf(
                "schema_version" to 1,
                "name" to PIPELINE,
                "display_name" to PIPELINE,
                "description" to "draft preview E2E source",
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
        val releasedBody =
            """{"display_name":"X","renderer":{"kind":"table","version":"1"},"inputs":{"main":{"columns":[{"name":"x",""" +
                """"type":"INTEGER","nullable":false}]}},"config":{},"bindings":{"cells.x":"x"}}"""
        // RELEASED: the pin the draft boards are allowed to hold (R1).
        val releasedId = uuid()
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$releasedId', '$WORKSPACE_ID', '$VIZ_X', '$VIZ_X', '', 1, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, " +
                "released_at, released_by, created_by) " +
                "VALUES ('$releasedId', 1, '$releasedBody'::jsonb, 'RELEASED', 'seeded-$releasedId', NOW(), '$ADMIN_ID', '$ADMIN_ID')",
        )
        // DRAFT: admitted during explicit draft preview (#459).
        val draftId = uuid()
        sql(
            "INSERT INTO visualizations (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$draftId', '$WORKSPACE_ID', '$VIZ_DRAFT', '$VIZ_DRAFT', '', 1, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO visualization_versions (visualization_id, version, body_json, status, body_hash, created_by) " +
                "VALUES ('$draftId', 1, '$releasedBody'::jsonb, 'DRAFT', 'seeded-$draftId', '$ADMIN_ID')",
        )
    }

    private fun seedBoards() {
        // The DRAFT board: v1 DRAFT, no release — the preview's subject. Its pins are RELEASED.
        seedBoard(DRAFT_BOARD, "dpprev/boards/draft_board", VIZ_X, status = "DRAFT", currentVersion = null)
        // The released board's golden: v1 RELEASED (the pointer), v2 DRAFT — so an absent version
        // and ?version=1 must answer identically, and a plant that prefers the draft goes red.
        seedBoard(RELEASED_BOARD, "dpprev/boards/released_board", VIZ_X, status = "RELEASED", currentVersion = 1)
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, created_by) " +
                "SELECT dashboard_id, 2, body_json, 'DRAFT', 'seeded-draft-2', '$ADMIN_ID' FROM dashboard_versions " +
                "WHERE dashboard_id = '$RELEASED_BOARD' AND version = 1",
        )
        // The draft dependency case: a DRAFT board pinning the DRAFT visualization.
        seedBoard(DRAFT_PIN_BOARD, "dpprev/boards/draft_pin_board", VIZ_DRAFT, status = "DRAFT", currentVersion = null)
        // The discarded case: a DRAFT version flipped to DISCARDED in its own test.
        seedBoard(DISCARDED_BOARD, "dpprev/boards/discarded_board", VIZ_X, status = "DRAFT", currentVersion = null)
    }

    private fun seedBoard(
        id: String,
        name: String,
        visualization: String,
        status: String,
        currentVersion: Int?,
    ) {
        val body =
            """{"display_name":"${name.substringAfterLast(
                '/',
            )}","sources":[{"name":"s1","pipeline":{"name":"$PIPELINE","version":1},"parameters":{}}],""" +
                """"visualizations":[{"name":"v1","type":"visualization","visualization":{"name":"$visualization","version":1},""" +
                """"inputs":{"main":{"source":"s1"}}}],"layout":{}}"""
        sql(
            "INSERT INTO dashboards (id, workspace_id, name, display_name, description, current_version, created_by) " +
                "VALUES ('$id', '$WORKSPACE_ID', '$name', '$name', '', ${currentVersion ?: "NULL"}, '$ADMIN_ID')",
        )
        sql(
            "INSERT INTO dashboard_versions (dashboard_id, version, body_json, status, body_hash, released_at, released_by, created_by) " +
                "VALUES ('$id', 1, '${body.replace("'", "''")}'::jsonb, '$status', 'seeded-$id', " +
                (if (status == "RELEASED") "NOW(), '$ADMIN_ID', " else "NULL, NULL, ") + "'$ADMIN_ID')",
        )
    }

    private fun seedKeys() {
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                "('${KEY.ownerId}', '${KEY.id.lowercase()}@keys.invalid', '${KEY.name}', 'key', '${KEY.id}', TRUE, FALSE, 'service')",
        )
        sql(
            "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role) " +
                "VALUES ('${KEY.id}', '${KEY.ownerId}', '$ADMIN_ID', '${KEY.name}', '${KEY.hash}', " +
                "'$WORKSPACE_ID', 'dashboard', 'dashboard_viewer')",
        )
    }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    /** Each row as column → text (null stays null). */
    private fun rows(query: String): List<Map<String, String?>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    buildList { while (rs.next()) add(columns.associateWith { rs.getString(it) }) }
                }
            }
        }

    private companion object {
        const val EXCERPT = 600
        const val STREAM_WAIT_SECONDS = 60L
        const val WORKSPACE = "dpprev"
        const val PIPELINE = "dpprev/pipelines/small"
        const val VIZ_X = "dpprev/charts/x"
        const val VIZ_DRAFT = "dpprev/charts/drafty"
        const val INSTANCE_A = "aa000000-0000-4000-8000-000000000001"
        const val INSTANCE_B = "aa000000-0000-4000-8000-000000000002"

        private const val WORKSPACE_ID = "d9000000-0000-0000-0000-000000000001"
        private const val ADMIN_ID = "d9000000-0000-0000-0000-000000000002"
        private const val VIEWER_ID = "d9000000-0000-0000-0000-000000000003"
        private const val PROMOTER_ID = "d9000000-0000-0000-0000-000000000004"

        private val DRAFT_BOARD = UUID.randomUUID().toString()
        private val RELEASED_BOARD = UUID.randomUUID().toString()
        private val DRAFT_PIN_BOARD = UUID.randomUUID().toString()
        private val DISCARDED_BOARD = UUID.randomUUID().toString()

        private val KEY = E2eAuth.generateKey("dpprev-key", ownerId = "d9000000-0000-0000-0000-000000000009")

        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "admin@dpprev.test", WORKSPACE)
        private val VIEWER get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "viewer@dpprev.test", WORKSPACE)
        private val PROMOTER get() = E2eSession.jwt(JWT_SECRET, PROMOTER_ID, "promoter@dpprev.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres

        /** The preview E2E's SOURCE database: created ONCE, when the companion initialises. */
        private val source = SharedE2e.scratchDatabase("dpprev_source")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { SharedE2e.redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }
    }
}
