package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.RepetitionInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/**
 * **The visualization test workflow on the REAL wire (#353, L4b)** — 352's backend through the routes, the
 * session-less preview page and the single-use upload, over real HTTP against the whole application:
 *
 * - **§A** the workflow end to end — start, the preview page with NO cookie, submit, the screenshot with the
 *   upload capability alone, the redacted evidence reads, the on-demand check, the release — and the gate's ladder on
 *   the same wire (`run_open`, `run_hash_mismatch`, `red`);
 * - **§B** the 352 obligations each on the wire — the capabilities' confinement to their one route (the SAME body an
 *   anonymous request gets anywhere else), revocation at submit, expiry, upload replay, another session's route,
 *   another workspace's visualization, and the CURRENT role: a demoted author's submit refused by `@RequiredScope`,
 *   their preview and upload capabilities void by the starter re-check — with nothing consumed by a refusal;
 * - **the caps** — 4 MiB + 1 on the screenshot route is `test.screenshot_too_large`, never the platform's code, while
 *   the platform's 2 MiB cap stands on every other route; a 3 MiB screenshot passes; CSRF still guards the session
 *   writes.
 *
 * Refusal bodies are compared WHOLE (the envelope minus its per-request correlation id; the preview page byte for
 * byte), never by status alone — "no oracle" is a claim about bytes.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VisualizationTestSurfacesE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    private lateinit var id: String
    private lateinit var hash: String

    // ---- §A — the workflow end to end ----------------------------------------------------------------

    @Test
    @Order(1)
    fun `A1 - a session author starts a session - the preview capability appears once, inside preview_url`() {
        seed()
        val created = post("/api/v1/visualizations", DOCUMENT, AUTHOR)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        id = created.jsonPath().getString("data.id")
        hash = created.jsonPath().getString("data.body_hash")

        val started = post("/api/v1/visualizations/$id/tests/sessions", "", AUTHOR)
        withClue(started.asString().take(EXCERPT)) { started.statusCode shouldBe 201 }
        val data = started.jsonPath()
        data.getList<String>("data.cases") shouldBe listOf("one month", "no rows")
        val previewUrl = data.getString("data.preview_url")
        previewUrl shouldStartWith "http://localhost:8080/visualizations/$id/preview?session="
        val token = previewUrl.substringAfter("session=")
        token.length shouldBe CAPABILITY_CHARS
        // The response is the ONLY place the material appears: no header carries it, the row holds a hash.
        started.headers().asList().none { it.value.contains(token) } shouldBe true
        scalar("SELECT preview_token_hash FROM visualization_test_runs WHERE session_id = '${data.getString("data.session_id")}'")
            .shouldNotBeNull() shouldNotBe token
        state.session = data.getString("data.session_id")
        state.run = data.getString("data.run_id")
        state.preview = token
    }

    @Test
    @Order(2)
    fun `A2 - the preview page answers with NO cookie - the run's fixtures, the live CSP, no cookie set, no echo`() {
        val page = anonymousGet("/visualizations/$id/preview?session=${state.preview}")
        withClue(page.asString().take(EXCERPT)) { page.statusCode shouldBe 200 }
        page.headers().hasHeaderWithName("Set-Cookie") shouldBe false
        page.header("Content-Security-Policy").shouldNotBeNull() shouldContain "script-src 'self'"
        page.header("Referrer-Policy").shouldNotBeNull()
        val html = page.asString()
        html shouldContain """<meta name="referrer" content="no-referrer">"""
        html shouldNotContain state.preview // the page never echoes its capability
        val block = previewBlock(html)
        block.path("visualization").path("id").asText() shouldBe id
        val cases = block.path("cases")
        cases.map { it.path("name").asText() } shouldBe listOf("one month", "no rows")
        val result = cases[0].path("results").path("preview")
        result.path("rows").asInt() shouldBe 1
        result.path("bindings").path("data[0].y").map { it.asDouble() } shouldBe listOf(10.5)
        cases[1]
            .path("results")
            .path("preview")
            .path("rows")
            .asInt() shouldBe 0
        cases[0]
            .path("inputs")
            .path("revenue")[0]
            .path("month")
            .asText() shouldBe "2026-01-01"
        // The runtime the page mounts names no server: fixture mode.
        html shouldContain "/js/visualization-preview.js"
        html shouldContain """data-dp-plotly-bundle="2d""""
    }

    @Test
    @Order(3)
    fun `A3 - a release while the session is open is tests_missing - run_open`() {
        val gated = post("/api/v1/visualizations/$id/release", "", AUTHOR, ifMatch = hash)
        gated.statusCode shouldBe 409
        gated.jsonPath().getString("error.code") shouldBe "visualization.release.tests_missing"
        gated.jsonPath().getString("error.details.reason") shouldBe "run_open"
    }

    @Test
    @Order(4)
    fun `A4 - all-green verdicts make the run GREEN, answer the upload capability once and revoke the preview`() {
        val submitted = post("/api/v1/visualizations/$id/tests/sessions/${state.session}/results", GREEN_RESULTS, AUTHOR)
        withClue(submitted.asString().take(EXCERPT)) { submitted.statusCode shouldBe 200 }
        val data = submitted.jsonPath()
        data.getString("data.status") shouldBe "GREEN"
        data.getBoolean("data.mechanical.ok") shouldBe true
        data.getString("data.upload.header") shouldBe "DP-Upload-Token"
        data.getString("data.upload.url") shouldBe
            "http://localhost:8080/api/v1/visualizations/$id/tests/sessions/${state.session}/screenshot"
        state.upload = data.getString("data.upload.token")

        // Revoked: the same preview URL is now the one unavailable page, byte-identical with a random capability's.
        val revoked = anonymousGet("/visualizations/$id/preview?session=${state.preview}")
        revoked.statusCode shouldBe 404
        revoked.asString() shouldBe anonymousGet("/visualizations/$id/preview?session=${randomCapability()}").asString()
    }

    @Test
    @Order(5)
    fun `A5 - the screenshot uploads with the capability alone - a replay is the no-oracle refusal and stores nothing`() {
        val png = png(320, 200)
        val stored = upload(id, state.session, state.upload, png, case = "one month")
        withClue(stored.asString().take(EXCERPT)) { stored.statusCode shouldBe 201 }
        stored.jsonPath().getString("data.media_type") shouldBe "image/png"
        stored.jsonPath().getInt("data.width") shouldBe 320
        stored.jsonPath().getString("data.depicted_case") shouldBe "one month"

        val replay = upload(id, state.session, state.upload, png)
        val wrong = upload(id, state.session, randomCapability(), png)
        replay.statusCode shouldBe 404
        envelope(replay) shouldBe envelope(wrong)
        replay.jsonPath().getString("error.code") shouldBe "visualization.test.session_not_found"
        scalar("SELECT count(*) FROM visualization_test_screenshots WHERE run_id = '${state.run}'") shouldBe "1"
        state.png = png
    }

    @Test
    @Order(6)
    fun `A6 - the evidence reads answer the redacted run, the runs list and the stored image`() {
        val run = get("/api/v1/visualizations/$id/tests/runs/${state.run}", VIEWER)
        withClue(run.asString().take(EXCERPT)) { run.statusCode shouldBe 200 }
        run.jsonPath().getString("data.status") shouldBe "GREEN"
        run.jsonPath().getBoolean("data.preview_revoked") shouldBe true
        run.jsonPath().getString("data.upload_capability.consumed_at").shouldNotBeNull()
        run.asString() shouldNotContain state.upload
        val storedHash = scalar("SELECT upload_token_hash FROM visualization_test_runs WHERE id = '${state.run}'").shouldNotBeNull()
        run.asString() shouldNotContain storedHash // presence and stamps only — never the hash

        val runs = get("/api/v1/visualizations/$id/tests/runs", VIEWER)
        runs.jsonPath().getList<String>("data.runs.run_id") shouldContain state.run
        runs.jsonPath().getInt("data.limit") shouldBe 100

        val image = get("/api/v1/visualizations/$id/tests/runs/${state.run}/screenshot", VIEWER)
        image.statusCode shouldBe 200
        image.contentType shouldStartWith "image/png"
        image.asByteArray().toList() shouldBe state.png.toList()
    }

    @Test
    @Order(7)
    fun `A7 - the on-demand check answers the mechanical report of a stored version and writes nothing`() {
        val before = scalar("SELECT count(*) FROM visualization_test_runs")
        val check = post("/api/v1/visualizations/$id/versions/1/check", "", VIEWER)
        withClue(check.asString().take(EXCERPT)) { check.statusCode shouldBe 200 }
        check.jsonPath().getBoolean("data.ok") shouldBe true
        check.jsonPath().getInt("data.failures_dropped") shouldBe 0
        scalar("SELECT count(*) FROM visualization_test_runs") shouldBe before
    }

    @Test
    @Order(8)
    fun `A8 - the release passes the gate on the GREEN run`() {
        val released = post("/api/v1/visualizations/$id/release", "", AUTHOR, ifMatch = hash)
        withClue(released.asString().take(EXCERPT)) { released.statusCode shouldBe 200 }
        released.jsonPath().getString("data.status") shouldBe "RELEASED"
    }

    @Test
    @Order(9)
    fun `A9 - the ladder on the wire - an edit after a GREEN run is run_hash_mismatch, a RED run is red`() {
        // A new draft, a GREEN run on it, then an edit: the latest run tested other content.
        val draftHash = edit(id, "Ladder v2", currentHash(id))
        val session = startSession(id, AUTHOR)
        submit(id, session.getString("data.session_id"), GREEN_RESULTS, AUTHOR).statusCode shouldBe 200
        val editedHash = edit(id, "Ladder v3", draftHash)
        val stale = post("/api/v1/visualizations/$id/release", "", AUTHOR, ifMatch = editedHash)
        stale.jsonPath().getString("error.code") shouldBe "visualization.release.tests_stale"
        stale.jsonPath().getString("error.details.reason") shouldBe "run_hash_mismatch"

        val red = startSession(id, AUTHOR)
        submit(id, red.getString("data.session_id"), RED_RESULTS, AUTHOR).jsonPath().getString("data.status") shouldBe "RED"
        val refused = post("/api/v1/visualizations/$id/release", "", AUTHOR, ifMatch = editedHash)
        refused.jsonPath().getString("error.code") shouldBe "visualization.release.tests_red"
        refused.jsonPath().getString("error.details.reason") shouldBe "red"
        // A RED run answers no upload capability.
        refused.statusCode shouldBe 409
    }

    // ---- §B — confinement, revocation, expiry, replay, the current role -------------------------------

    @Test
    @Order(20)
    fun `B1 - the preview capability on any other route is an anonymous request there - the same body`() {
        val session = startSession(id, AUTHOR)
        val token = session.getString("data.preview_url").substringAfter("session=")
        listOf(
            "/api/v1/visualizations/$id",
            "/api/v1/visualizations/$id/tests/runs",
            "/api/v1/dashboards",
            "/dashboards",
        ).forEach { path ->
            withClue(path) {
                val withToken = anonymousGet("$path?session=$token")
                val bare = anonymousGet(path)
                withToken.statusCode shouldBe bare.statusCode
                withToken.statusCode shouldNotBe 200
                normalised(withToken) shouldBe normalised(bare)
            }
        }
        // `/mcp` with the capability as a bearer: the transport's own anonymous refusal.
        val mcpWithToken = mcpPost("Bearer $token")
        val mcpBare = mcpPost(null)
        mcpWithToken.statusCode shouldBe mcpBare.statusCode
        normalised(mcpWithToken) shouldBe normalised(mcpBare)
        submit(id, session.getString("data.session_id"), GREEN_RESULTS, AUTHOR).statusCode shouldBe 200
    }

    /**
     * #399 — the workspace routes are NOT the capability page: the public `/visualizations/{id}/preview` glob admits
     * none of them, so every page, tab partial and lifecycle dialog answers an anonymous request — WITH the live
     * preview capability or without it — with the same refusal, never a 200, and an anonymous lifecycle POST (a valid
     * CSRF pair, so the answer is the route's) changes nothing.
     */
    @Test
    @Order(26)
    fun `B7 - the workspace's pages, tabs and dialogs refuse an anonymous caller - the capability opens none of them`() {
        val session = startSession(id, AUTHOR)
        val token = session.getString("data.preview_url").substringAfter("session=")
        val run = session.getString("data.run_id")
        val reads =
            listOf("/visualizations", "/visualizations/$id", "/partials/visualizations", "/partials/visualizations/tree") +
                listOf(
                    "preview",
                    "overview",
                    "evidence",
                    "evidence/$run",
                    "used-by",
                    "versions",
                ).map { "/partials/visualizations/$id/$it" } +
                LIFECYCLE.map { "/partials/visualizations/$id/lifecycle/$it" }
        reads.forEach { path ->
            withClue(path) {
                val bare = anonymousGet(path)
                val withToken = anonymousGet("$path?session=$token")
                bare.statusCode shouldNotBe 200
                withToken.statusCode shouldBe bare.statusCode
                normalised(withToken) shouldBe normalised(bare)
            }
        }
        val before = envelope(get("/api/v1/visualizations/$id", AUTHOR))
        LIFECYCLE.forEach { verb ->
            withClue("POST $verb") {
                val refused =
                    anonymous()
                        .cookie(E2eSession.CSRF_COOKIE, E2eSession.CSRF_TOKEN)
                        .header(E2eSession.CSRF_HEADER, E2eSession.CSRF_TOKEN)
                        .post("/partials/visualizations/$id/lifecycle/$verb?version=1&session=$token")
                refused.statusCode shouldNotBe 200
                refused.getHeader("HX-Redirect") shouldBe null
            }
        }
        envelope(get("/api/v1/visualizations/$id", AUTHOR)) shouldBe before
        submit(id, session.getString("data.session_id"), GREEN_RESULTS, AUTHOR).statusCode shouldBe 200
    }

    @Test
    @Order(21)
    fun `B2 - the upload capability on another session's route, on a lifecycle route, or as a preview is nothing`() {
        val first = greenSession(id, AUTHOR)
        val second = greenSession(id, AUTHOR)
        val png = png(10, 10)
        // Another session's route: the canonical refusal, and the second session's capability still works.
        val crossed = upload(id, second.session, first.upload, png)
        crossed.statusCode shouldBe 404
        envelope(crossed) shouldBe envelope(upload(id, second.session, randomCapability(), png))
        upload(id, second.session, second.upload, png).statusCode shouldBe 201
        // On a lifecycle route the header is no credential: an anonymous request's answer.
        val lifecycle = anonymous().header("DP-Upload-Token", first.upload).get("/api/v1/visualizations/$id")
        normalised(lifecycle) shouldBe normalised(anonymousGet("/api/v1/visualizations/$id"))
        // As a preview capability: the one unavailable page.
        anonymousGet("/visualizations/$id/preview?session=${first.upload}").asString() shouldBe
            anonymousGet("/visualizations/$id/preview?session=${randomCapability()}").asString()
    }

    @Test
    @Order(22)
    fun `B3 - an expired session's preview is the one 404 page - the deadline judged, no sweep needed`() {
        val session = startSession(id, AUTHOR)
        val token = session.getString("data.preview_url").substringAfter("session=")
        anonymousGet("/visualizations/$id/preview?session=$token").statusCode shouldBe 200
        // Drive the deadline (the service judges `statusAt(now)`; nothing has to sweep first).
        sql(
            "UPDATE visualization_test_runs SET expires_at = now() - interval '1 second' WHERE session_id = '${session.getString(
                "data.session_id",
            )}'",
        )
        val expired = anonymousGet("/visualizations/$id/preview?session=$token")
        expired.statusCode shouldBe 404
        expired.asString() shouldBe anonymousGet("/visualizations/$id/preview?session=${randomCapability()}").asString()
    }

    @Test
    @Order(23)
    fun `B4 - workspace confinement - a capability addressed at another workspace's visualization is the one 404`() {
        val session = startSession(id, AUTHOR)
        val token = session.getString("data.preview_url").substringAfter("session=")
        val foreign = otherWorkspaceVisualization()
        val crossed = anonymousGet("/visualizations/$foreign/preview?session=$token")
        crossed.statusCode shouldBe 404
        crossed.asString() shouldBe anonymousGet("/visualizations/$id/preview?session=${randomCapability()}").asString()
        // And the session's own runs are not readable from the other workspace.
        val fromB = get("/api/v1/visualizations/$id/tests/runs", B_AUTHOR)
        fromB.statusCode shouldBe 404
        fromB.jsonPath().getString("error.code") shouldBe "visualization.not_found"
    }

    @Test
    @Order(24)
    fun `B5 - a demoted author - the submit refused by the CURRENT role, the preview and upload void, nothing consumed`() {
        val running = startSession(id, DEMOTEE)
        val runningToken = running.getString("data.preview_url").substringAfter("session=")
        val green = greenSession(id, DEMOTEE)
        anonymousGet("/visualizations/$id/preview?session=$runningToken").statusCode shouldBe 200

        changeRole(DEMOTEE_ID, "viewer")
        // The session submit: the handler's @RequiredScope, judged per request.
        val submit =
            post("/api/v1/visualizations/$id/tests/sessions/${running.getString("data.session_id")}/results", GREEN_RESULTS, DEMOTEE)
        submit.statusCode shouldBe 403
        submit.jsonPath().getString("error.code") shouldBe "auth.role_required"
        // The capabilities: the starter re-check (DECISION 3) — the same answers as a wrong capability.
        anonymousGet("/visualizations/$id/preview?session=$runningToken").asString() shouldBe
            anonymousGet("/visualizations/$id/preview?session=${randomCapability()}").asString()
        val png = png(12, 12)
        val demoted = upload(id, green.session, green.upload, png)
        demoted.statusCode shouldBe 404
        envelope(demoted) shouldBe envelope(upload(id, green.session, randomCapability(), png))
        scalar("SELECT upload_consumed_at FROM visualization_test_runs WHERE session_id = '${green.session}'").shouldBeNull()

        // Restored: the SAME capabilities work again — the refusal consumed nothing.
        changeRole(DEMOTEE_ID, "author")
        anonymousGet("/visualizations/$id/preview?session=$runningToken").statusCode shouldBe 200
        upload(id, green.session, green.upload, png).statusCode shouldBe 201
    }

    @Test
    @Order(25)
    fun `B6 - an expired upload capability is the 410 before the body - nothing stored, and expiry is final (#373)`() {
        val green = greenSession(id, AUTHOR)
        val png = png(11, 11)
        // Age the UPLOAD deadline (not the session's): the run stays GREEN, the capability's own clock ends —
        // the B3 way, SQL on the row, no sweep involved.
        sql("UPDATE visualization_test_runs SET upload_expires_at = now() - interval '1 second' WHERE session_id = '${green.session}'")

        val expired = upload(id, green.session, green.upload, png)
        expired.statusCode shouldBe 410
        expired.jsonPath().getString("error.code") shouldBe "visualization.test.session_expired"
        expired.jsonPath().getString("error.details.reason") shouldBe "capability_expired"
        scalar("SELECT upload_consumed_at FROM visualization_test_runs WHERE session_id = '${green.session}'").shouldBeNull()

        // The body was never judged, never stored: the run answers no_screenshot.
        val runs = get("/api/v1/visualizations/$id/tests/runs", AUTHOR)
        val runId =
            runs
                .jsonPath()
                .getList<Map<String, Any?>>("data.runs")
                .first { it["session_id"] == green.session }["run_id"]
        val image = get("/api/v1/visualizations/$id/tests/runs/$runId/screenshot", AUTHOR)
        image.statusCode shouldBe 400
        image.jsonPath().getString("error.details.reason") shouldBe "no_screenshot"

        // Expiry is final: the same correct capability is still the 410; a wrong token keeps the one 404.
        upload(id, green.session, green.upload, png).statusCode shouldBe 410
        val wrong = upload(id, green.session, randomCapability(), png)
        wrong.statusCode shouldBe 404
        envelope(wrong) shouldBe envelope(upload(id, green.session, randomCapability(), png))
    }

    // ---- the caps and CSRF -----------------------------------------------------------------------------

    @RepeatedTest(value = 5, name = "{displayName} - repetition {currentRepetition} of {totalRepetitions}")
    @Order(30)
    fun `C1 - 4 MiB + 1 on the screenshot route is the route's own 413, refused before the capability is read`(
        repetition: RepetitionInfo,
    ) {
        val session = greenSession(id, AUTHOR)
        val over = upload(id, session.session, session.upload, ByteArray(SCREENSHOT_CAP + 1))
        over.statusCode shouldBe 413
        over.jsonPath().getString("error.code") shouldBe "visualization.test.screenshot_too_large"
        over.jsonPath().getInt("error.details.cap_bytes") shouldBe SCREENSHOT_CAP
        over.asString() shouldNotContain "request.body_too_large"
        scalar("SELECT upload_consumed_at FROM visualization_test_runs WHERE session_id = '${session.session}'").shouldBeNull()
        // Unconsumed by the refusal: a 3 MiB image — over the platform's 2 MiB — lands on the SAME capability.
        val large = upload(id, session.session, session.upload, paddedPng(3 * 1024 * 1024))
        withClue(large.asString().take(EXCERPT)) { large.statusCode shouldBe 201 }
        println("event=cap.screenshot trial=${repetition.currentRepetition} status=${over.statusCode} followup=${large.statusCode}")
        println("event=cap.screenshot.envelope body=${envelope(over)}")
        val boundary = greenSession(id, AUTHOR)
        upload(id, boundary.session, boundary.upload, paddedPng(SCREENSHOT_CAP)).statusCode shouldBe 201
        val chunked = greenSession(id, AUTHOR)
        val chunkedOver = upload(id, chunked.session, chunked.upload, ByteArray(SCREENSHOT_CAP + 1), chunked = true)
        chunkedOver.statusCode shouldBe 413
        chunkedOver.jsonPath().getString("error.code") shouldBe "visualization.test.screenshot_too_large"
        chunkedOver.jsonPath().getInt("error.details.cap_bytes") shouldBe SCREENSHOT_CAP
        scalar("SELECT upload_consumed_at FROM visualization_test_runs WHERE session_id = '${chunked.session}'").shouldBeNull()
        upload(id, chunked.session, chunked.upload, paddedPng(3 * 1024 * 1024)).statusCode shouldBe 201
        println(
            "event=cap.screenshot.controls trial=${repetition.currentRepetition} exact_cap=201 chunked_status=${chunkedOver.statusCode}",
        )
    }

    @Test
    @Order(31)
    fun `C2 - the platform's 2 MiB cap stands on every other route`() {
        val body = "{\"pad\":\"" + "x".repeat(PLATFORM_CAP) + "\"}"
        val put =
            given()
                .port(
                    port,
                ).asSession(AUTHOR)
                .contentType(ContentType.JSON)
                .header("If-Match", "x")
                .body(body)
                .put("/api/v1/visualizations/$id")
        put.statusCode shouldBe 413
        put.jsonPath().getString("error.code") shouldBe "request.body_too_large"
        val results = post("/api/v1/visualizations/$id/tests/sessions/${UUID.randomUUID()}/results", body, AUTHOR)
        results.jsonPath().getString("error.code") shouldBe "request.body_too_large"
        println("event=cap.platform status=${put.statusCode} body=${envelope(put)}")
    }

    @Test
    @Order(32)
    fun `C3 - CSRF still guards the cookie-authenticated test writes`() {
        val noCsrf =
            given()
                .port(port)
                .cookie(E2eSession.COOKIE, AUTHOR)
                .contentType(ContentType.JSON)
                .post("/api/v1/visualizations/$id/tests/sessions")
        noCsrf.statusCode shouldBe 403
        noCsrf.jsonPath().getString("error.code") shouldBe "auth.csrf.invalid"
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private class State {
        lateinit var session: String
        lateinit var run: String
        lateinit var preview: String
        lateinit var upload: String
        lateinit var png: ByteArray
    }

    private val state = State()

    private data class Green(
        val session: String,
        val upload: String,
    )

    private fun greenSession(
        visualization: String,
        who: String,
    ): Green {
        val started = startSession(visualization, who)
        val sid = started.getString("data.session_id")
        val submitted = submit(visualization, sid, GREEN_RESULTS, who)
        withClue(submitted.asString().take(EXCERPT)) { submitted.statusCode shouldBe 200 }
        return Green(sid, submitted.jsonPath().getString("data.upload.token"))
    }

    private fun startSession(
        visualization: String,
        who: String,
    ): io.restassured.path.json.JsonPath {
        val started = post("/api/v1/visualizations/$visualization/tests/sessions", "", who)
        withClue(started.asString().take(EXCERPT)) { started.statusCode shouldBe 201 }
        return started.jsonPath()
    }

    private fun submit(
        visualization: String,
        session: String,
        body: String,
        who: String,
    ): Response = post("/api/v1/visualizations/$visualization/tests/sessions/$session/results", body, who)

    private fun upload(
        visualization: String,
        session: String,
        capability: String,
        bytes: ByteArray,
        case: String? = null,
        chunked: Boolean = false,
    ): Response {
        val path = "/api/v1/visualizations/$visualization/tests/sessions/$session/screenshot"
        val query = case?.let { "?case=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""
        val body =
            if (chunked) {
                HttpRequest.BodyPublishers.ofInputStream {
                    ByteArrayInputStream(
                        bytes,
                    )
                }
            } else {
                HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) },
                    bytes.size.toLong(),
                )
            }
        if (chunked) {
            ScreenshotUploadTransportTest.WireCapture(port).use { wire ->
                val response = ScreenshotUploadTransport.post(wire.port, path + query, body, capability)
                wire.await()
                wire.declared shouldBe -1L
                wire.chunked shouldBe true
                println("event=cap.screenshot.chunked declared=${wire.declared} chunked=${wire.chunked} forwarded=${wire.forwarded.get()}")
                return response
            }
        }
        return ScreenshotUploadTransport.post(port, path + query, body, capability)
    }

    private fun edit(
        visualization: String,
        title: String,
        ifMatch: String,
    ): String {
        val node = mapper.readTree(DOCUMENT) as ObjectNode
        (node.get("presentation") as ObjectNode).put("title", title)
        val written =
            given()
                .port(port)
                .asSession(AUTHOR)
                .contentType(ContentType.JSON)
                .header("If-Match", ifMatch)
                .body(mapper.writeValueAsString(node))
                .put("/api/v1/visualizations/$visualization")
        withClue(written.asString().take(EXCERPT)) { written.statusCode shouldBe 200 }
        return written.jsonPath().getString("data.body_hash")
    }

    private fun currentHash(visualization: String): String =
        get("/api/v1/visualizations/$visualization", AUTHOR).jsonPath().getString("data.body_hash")

    private fun changeRole(
        userId: String,
        role: String,
    ) {
        val response =
            given()
                .port(port)
                .asSession(ADMIN)
                .contentType(ContentType.JSON)
                .body("""{"role":"$role"}""")
                .put("/api/v1/workspaces/$WORKSPACE/members/$userId")
        withClue(response.asString().take(EXCERPT)) { response.statusCode shouldBe 200 }
    }

    private fun otherWorkspaceVisualization(): String {
        val created = post("/api/v1/visualizations", DOCUMENT.replace(NAME, "tsb/charts/other"), B_AUTHOR)
        withClue(created.asString().take(EXCERPT)) { created.statusCode shouldBe 201 }
        return created.jsonPath().getString("data.id")
    }

    private fun anonymous(): RequestSpecification = given().port(port).redirects().follow(false)

    private fun anonymousGet(path: String): Response = anonymous().get(path)

    private fun mcpPost(authorization: String?): Response =
        anonymous()
            .apply { authorization?.let { header("Authorization", it) } }
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
            .post("/mcp")

    private fun post(
        path: String,
        body: String,
        session: String,
        ifMatch: String? = null,
    ): Response =
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .apply { ifMatch?.let { header("If-Match", it) } }
            .body(body)
            .post(path)

    private fun get(
        path: String,
        session: String,
    ): Response = given().port(port).asSession(session).get(path)

    /** The error envelope minus its per-request correlation id — what "the same answer" means for JSON. */
    private fun envelope(response: Response): JsonNode =
        (mapper.readTree(response.asString()) as ObjectNode).also { it.remove("correlation_id") }

    /** A response for comparison: the JSON envelope without its correlation id, or the body as it is. */
    private fun normalised(response: Response): Any = runCatching { envelope(response) }.getOrElse { response.asString() }

    private fun previewBlock(html: String): JsonNode {
        val json =
            Regex("""<script type="application/json" id="dp-preview-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
                .shouldNotBeNull()
                .groupValues[1]
        return mapper.readTree(json)
    }

    private fun randomCapability(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

    /** A real PNG, encoded by the JDK. */
    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, (x * 7 + y * 13) and 0xffffff)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A real PNG followed by trailing bytes to [size] — the validator reads the header; the transport carries the size. */
    private fun paddedPng(size: Int): ByteArray {
        val head = png(64, 64)
        return head + ByteArray(size - head.size)
    }

    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            firstColumn(connection.createStatement().executeQuery(query))
        }

    /** The first row's first column, or null — the result set closed with its statement. */
    private fun firstColumn(rs: java.sql.ResultSet): String? = rs.use { if (it.next()) it.getString(1) else null }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun seed() {
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Test surfaces E2E')")
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$B_WORKSPACE_ID', '$B_WORKSPACE', 'Test surfaces E2E (B)')")
        val users =
            listOf(
                ADMIN_ID to "ts-admin",
                AUTHOR_ID to "ts-author",
                DEMOTEE_ID to "ts-demotee",
                VIEWER_ID to "ts-viewer",
                B_AUTHOR_ID to "ts-b-author",
            )
        users.forEach { (userId, handle) ->
            sql(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                    "('$userId', '$handle@e2e.test', '$handle', 'test', '$handle-sub', TRUE, FALSE)",
            )
        }
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin')")
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$AUTHOR_ID', 'author')")
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$DEMOTEE_ID', 'author')")
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')")
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$B_WORKSPACE_ID', '$B_AUTHOR_ID', 'author')")
    }

    private companion object {
        const val EXCERPT = 800
        val LIFECYCLE = listOf("release", "purge-draft", "discard", "restore", "purge-version", "switch", "purge-entity")
        const val WORKSPACE = "ts-e2e"
        const val B_WORKSPACE = "ts-e2e-b"
        const val NAME = "ts/charts/evidence"
        const val CAPABILITY_CHARS = 43
        const val SCREENSHOT_CAP = 4 * 1024 * 1024
        const val PLATFORM_CAP = 2 * 1024 * 1024

        val DOCUMENT =
            """
            {"name": "$NAME", "display_name": "Evidence", "description": "",
             "renderer": {"kind": "plotly", "version": "4"},
             "inputs": {"revenue": {"columns": [{"name": "month", "type": "DATE", "nullable": false},
                                                {"name": "amount", "type": "DECIMAL", "nullable": false}]}},
             "config": {"data": [{"type": "bar", "x": [], "y": []}]},
             "bindings": {"data[0].x": "month", "data[0].y": "amount"},
             "presentation": {"title": "Evidence"},
             "tests": {"cases": [
                {"name": "one month", "fixtures": {"revenue": [{"month": "2026-01-01", "amount": 10.5}]},
                 "assertions": [{"kind": "rendered"}, {"kind": "trace_count", "equals": 1}]},
                {"name": "no rows", "fixtures": {"revenue": []}, "assertions": [{"kind": "no_data"}]}]}}
            """.trimIndent()

        const val GREEN_RESULTS =
            """{"cases": [{"name": "one month", "verdict": "green", "notes": "one bar"}, {"name": "no rows", "verdict": "green"}],
                "environment": {"browser": "e2e", "theme": "dark"}}"""
        const val RED_RESULTS = """{"cases": [{"name": "one month", "verdict": "red"}, {"name": "no rows", "verdict": "green"}]}"""

        val WORKSPACE_ID = UUID.randomUUID().toString()
        val B_WORKSPACE_ID = UUID.randomUUID().toString()
        val ADMIN_ID = UUID.randomUUID().toString()
        val AUTHOR_ID = UUID.randomUUID().toString()
        val DEMOTEE_ID = UUID.randomUUID().toString()
        val VIEWER_ID = UUID.randomUUID().toString()
        val B_AUTHOR_ID = UUID.randomUUID().toString()

        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        val ADMIN get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "ts-admin@e2e.test", WORKSPACE)
        val AUTHOR get() = E2eSession.jwt(JWT_SECRET, AUTHOR_ID, "ts-author@e2e.test", WORKSPACE)
        val DEMOTEE get() = E2eSession.jwt(JWT_SECRET, DEMOTEE_ID, "ts-demotee@e2e.test", WORKSPACE)
        val VIEWER get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "ts-viewer@e2e.test", WORKSPACE)
        val B_AUTHOR get() = E2eSession.jwt(JWT_SECRET, B_AUTHOR_ID, "ts-b-author@e2e.test", B_WORKSPACE)

        val postgres get() = SharedE2e.postgres
        val redis get() = SharedE2e.redis
        val oidc = OidcDiscoveryStub()

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { SharedE2e.redisPort }
            registry.add("spring.data.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
            // The walk sends many requests per role; the per-user limiter must not answer with a 429.
            registry.add("datapipelines.rate-limit.requests-per-second") { "100000" }
            registry.add("datapipelines.rate-limit.requests-per-minute") { "1000000" }
        }

        @JvmStatic
        @AfterAll
        fun closeOidc() {
            oidc.close()
        }
    }
}
