package co.datapipelines.web.visualizations

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.ClientAddressResolver
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactRecord
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.ArtifactVersionDetail
import co.datapipelines.visualization.CaseVerdict
import co.datapipelines.visualization.ScreenshotBytes
import co.datapipelines.visualization.ScreenshotView
import co.datapipelines.visualization.SubmittedCase
import co.datapipelines.visualization.TestEnvironment
import co.datapipelines.visualization.TestRunStatus
import co.datapipelines.visualization.TestRunView
import co.datapipelines.visualization.TestSessionLinks
import co.datapipelines.visualization.TestSessionStarted
import co.datapipelines.visualization.TestSessionSubmitted
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationReader
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.visualization.VisualizationTestCapabilities
import co.datapipelines.visualization.VisualizationTestSessionService
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.requestlimits.RequestBodyCapFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The test-workflow routes over mocked services (the `VisualizationsControllerTest` shape): each route hands the
 * CALLER's workspace and identity to the service, the results body is judged by the shared reader before the
 * service, the screenshot route passes the capability, the declared type and a body bounded at the cap + 1 —
 * reading no principal — and every evidence read passes the caller's lens: a lens-hidden visualization is the
 * family's 404 and a run of a version the lens does not admit answers exactly as an unknown run.
 */
class VisualizationTestsControllerTest {
    private val sessions = mockk<VisualizationTestSessionService>()
    private val capabilities = mockk<VisualizationTestCapabilities>()
    private val visualizations = mockk<VisualizationService>()
    private val links = TestSessionLinks("https://dp.example.com/")

    /** A recording sink, not a strict mock: the upload's row is a "must be called" contract (a missing call must go red). */
    private val audit = RecordingAudit()
    private val controller = controller(co.datapipelines.web.EVERYTHING_LENS)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val id = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val runId = UUID.randomUUID()

    init {
        // The upload gate admits by default; the gate's own refusal case overrides it.
        every { capabilities.authorizeUpload(any(), any(), any()) } returns
            VisualizationTestCapabilities.UploadGrant(workspaceId, "granted")
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `an upload without a valid capability is refused before a byte of the body is read`() {
        every { capabilities.authorizeUpload(id, sessionId, "WRONG") } throws
            DatapipelinesException(
                VisualizationErrorCodes.TEST_SESSION_NOT_FOUND,
                "No such test session.",
                mapOf("reason" to "session_unknown"),
            )
        val stream = CountingStream()
        val request =
            object : HttpServletRequestWrapper(MockHttpServletRequest("POST", "/")) {
                override fun getInputStream(): ServletInputStream = stream
            }

        shouldThrow<DatapipelinesException> { controller.screenshot(id, sessionId, "WRONG", null, request) }.code shouldBe
            VisualizationErrorCodes.TEST_SESSION_NOT_FOUND

        stream.reads shouldBe 0 // the server buffered nothing for an unauthorised caller
        verify(exactly = 0) { capabilities.storeScreenshot(any(), any(), any(), any(), any(), any()) }
        audit.events.shouldBeEmpty() // a refused upload stores nothing and writes no row
    }

    @Test
    fun `an expired capability is the 410 - judged before a byte of the body is read (#373)`() {
        every { capabilities.authorizeUpload(id, sessionId, "EXPIRED") } throws
            DatapipelinesException(
                VisualizationErrorCodes.TEST_SESSION_EXPIRED,
                "The test session is expired or revoked.",
                mapOf("reason" to "capability_expired"),
            )
        val stream = CountingStream()
        val request =
            object : HttpServletRequestWrapper(MockHttpServletRequest("POST", "/")) {
                override fun getInputStream(): ServletInputStream = stream
            }

        val error = shouldThrow<DatapipelinesException> { controller.screenshot(id, sessionId, "EXPIRED", null, request) }

        error.code shouldBe VisualizationErrorCodes.TEST_SESSION_EXPIRED
        error.details["reason"] shouldBe "capability_expired"
        stream.reads shouldBe 0 // the body was never buffered for the one answer that names the expiry
        verify(exactly = 0) { capabilities.storeScreenshot(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `start opens the session as the caller and answers the preview URL from the configured origin`() {
        authenticate()
        every { sessions.start(workspaceId, id, userId) } returns started()

        val data = controller.start(id).data

        data["session_id"] shouldBe sessionId.toString()
        data["preview_url"] shouldBe "https://dp.example.com/visualizations/$id/preview?session=PREVIEW-MATERIAL"
        data["cases"] shouldBe listOf("twelve months")
        data["expires_at"] shouldBe EXPIRES.toString()
    }

    @Test
    fun `results parses the body with the shared reader and a GREEN run answers the upload capability once`() {
        authenticate()
        val verdicts = slot<List<SubmittedCase>>()
        val environment = slot<TestEnvironment>()
        every { sessions.submit(workspaceId, id, sessionId, userId, capture(verdicts), capture(environment)) } returns
            submitted(TestRunStatus.GREEN, "UPLOAD-MATERIAL")

        val data =
            controller
                .results(
                    id,
                    sessionId,
                    """{"cases": [{"name": "twelve months", "verdict": "green", "notes": "one bar"}], "environment": {"theme": "dark"}}""",
                ).data

        verdicts.captured shouldBe listOf(SubmittedCase("twelve months", CaseVerdict.GREEN, "one bar"))
        environment.captured.theme shouldBe "dark"
        data["status"] shouldBe "GREEN"
        @Suppress("UNCHECKED_CAST")
        val upload = data["upload"] as Map<String, Any?>
        upload["url"] shouldBe "https://dp.example.com/api/v1/visualizations/$id/tests/sessions/$sessionId/screenshot"
        upload["header"] shouldBe "DP-Upload-Token"
        upload["token"] shouldBe "UPLOAD-MATERIAL"
    }

    @Test
    fun `a RED run answers no upload capability`() {
        authenticate()
        every { sessions.submit(workspaceId, id, sessionId, userId, any(), any()) } returns submitted(TestRunStatus.RED, null)
        controller.results(id, sessionId, """{"cases": [{"name": "twelve months", "verdict": "red"}]}""").data["upload"].shouldBeNull()
    }

    @Test
    fun `a results body of the wrong shape is body_invalid naming the path - and the service is never asked`() {
        authenticate()
        val cases =
            mapOf(
                """{"cases": "green"}""" to ("wrong_type" to "cases"),
                """{"verdicts": []}""" to ("unknown_key" to "verdicts"),
                """{"cases": [{"name": "a", "verdict": "amber"}]}""" to ("verdict_invalid" to "cases[0].verdict"),
                """{"cases": [{"name": "a", "verdict": "green", "score": 1}]}""" to ("unknown_key" to "cases[0].score"),
                """{"cases": [], "environment": {"os": "linux"}}""" to ("environment_unknown" to "environment.os"),
                """{"cases": [], "environment": {"theme": 3}}""" to ("environment_field_invalid" to "environment.theme"),
                """{}""" to ("missing" to "cases"),
            )
        cases.forEach { (body, expected) ->
            val error = shouldThrow<DatapipelinesException> { controller.results(id, sessionId, body) }
            error.code shouldBe VisualizationErrorCodes.BODY_INVALID
            (error.details["reason"] to error.details["path"]) shouldBe expected
        }
        shouldThrow<ApiException> { controller.results(id, sessionId, """{"cases": [""") }.code shouldBe
            VisualizationErrorCodes.BODY_INVALID
        verify(exactly = 0) { sessions.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the screenshot route passes the capability, the bare declared type and the bytes - and reads no principal`() {
        // No authenticate(): the route must not need a principal.
        val bytes = ByteArray(64) { it.toByte() }
        val captured = slot<ByteArray>()
        every { capabilities.storeScreenshot(id, sessionId, "UPLOAD-MATERIAL", "image/png", capture(captured), "twelve months") } returns
            ScreenshotView(runId, "image/png", "ab".repeat(32), 3, 2, "twelve months", userId, EXPIRES)
        val request =
            MockHttpServletRequest("POST", "/").apply {
                contentType = "image/PNG; charset=binary"
                setContent(bytes)
            }

        val data = controller.screenshot(id, sessionId, "UPLOAD-MATERIAL", "twelve months", request).data

        captured.captured.toList() shouldBe bytes.toList()
        data["media_type"] shouldBe "image/png"
        data["run_id"] shouldBe runId.toString()
        data.containsKey("uploaded_by") shouldBe false // a session-less caller learns no identity
    }

    @Test
    fun `a stored screenshot writes ONE audit row - the run's starter, no key, the source IP, ids size type and case, never the token`() {
        val bytes = ByteArray(64) { it.toByte() }
        every { capabilities.storeScreenshot(id, sessionId, "UPLOAD-MATERIAL", "image/png", any(), "twelve months") } returns
            ScreenshotView(runId, "image/png", "ab".repeat(32), 3, 2, "twelve months", userId, EXPIRES)
        val request =
            MockHttpServletRequest("POST", "/").apply {
                contentType = "image/png"
                remoteAddr = "203.0.113.9"
                setContent(bytes)
            }

        controller.screenshot(id, sessionId, "UPLOAD-MATERIAL", "twelve months", request)

        audit.events.size shouldBe 1 // `expected:<1> but was:<0>` is the red this assertion gives without the log call
        val row = audit.events.single()
        row.event shouldBe VisualizationAuditEvents.SCREENSHOT_UPLOADED
        row.event shouldBe "visualization.test.screenshot_uploaded"
        row.userId shouldBe userId // the run's STARTER: the route has no principal
        row.keyId.shouldBeNull() // and no key
        row.sourceIp shouldBe "203.0.113.9"
        // Redaction: neither the presented capability nor the image's hash or bytes reach the row, in any value.
        withClue("the audit row carries the upload capability or the image's hash: ${row.details}") {
            row.details.values.none { it.toString().contains("UPLOAD-MATERIAL") || it.toString().contains("ab".repeat(32)) } shouldBe true
        }
        row.details shouldBe
            mapOf(
                "workspace_id" to workspaceId.toString(),
                "visualization_id" to id.toString(),
                "run_id" to runId.toString(),
                "media_type" to "image/png",
                "size_bytes" to 64,
                "case" to "twelve months",
            )
    }

    @Test
    fun `an upload that names no case writes a row without a case key`() {
        every { capabilities.storeScreenshot(id, sessionId, "UPLOAD-MATERIAL", "image/webp", any(), null) } returns
            ScreenshotView(runId, "image/webp", "cd".repeat(32), 3, 2, null, userId, EXPIRES)
        val request =
            MockHttpServletRequest("POST", "/").apply {
                contentType = "image/webp"
                setContent(ByteArray(8))
            }

        controller.screenshot(id, sessionId, "UPLOAD-MATERIAL", null, request)

        audit.events
            .single()
            .details
            .containsKey("case") shouldBe false
    }

    @Test
    fun `a refusal by the service after the gate writes no audit row`() {
        every { capabilities.storeScreenshot(id, sessionId, "UPLOAD-MATERIAL", null, any(), null) } throws
            DatapipelinesException(VisualizationErrorCodes.TEST_SCREENSHOT_INVALID, "invalid", mapOf("reason" to "already_stored"))
        val request = MockHttpServletRequest("POST", "/").apply { setContent(ByteArray(8)) }

        shouldThrow<DatapipelinesException> { controller.screenshot(id, sessionId, "UPLOAD-MATERIAL", null, request) }

        audit.events.shouldBeEmpty()
    }

    @Test
    fun `the screenshot body is read at most to the cap plus one - the service sees an over-size body as over-size`() {
        val captured = slot<ByteArray>()
        every { capabilities.storeScreenshot(id, sessionId, "T", null, capture(captured), null) } throws
            DatapipelinesException(VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE, "too large", emptyMap())
        val request = MockHttpServletRequest("POST", "/").apply { setContent(ByteArray(CAP + 4096)) }

        shouldThrow<DatapipelinesException> { controller.screenshot(id, sessionId, "T", null, request) }

        captured.captured.size shouldBe CAP + 1
    }

    @Test
    fun `the counting stream's refusal on a chunked body becomes the route's own 413 code`() {
        val request =
            object : HttpServletRequestWrapper(MockHttpServletRequest("POST", "/")) {
                override fun getInputStream(): ServletInputStream = ThrowingStream()
            }
        val error = shouldThrow<DatapipelinesException> { controller.screenshot(id, sessionId, "T", null, request) }
        error.code shouldBe VisualizationErrorCodes.TEST_SCREENSHOT_TOO_LARGE
        error.details["cap_bytes"] shouldBe CAP
        verify(exactly = 0) { capabilities.storeScreenshot(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the runs read answers only runs of versions the caller's lens admits`() {
        authenticate()
        every { visualizations.listVersions(workspaceId, any(), id) } returns listOf(detail(1, PipelineVersionStatus.RELEASED))
        every { sessions.runs(workspaceId, id) } returns listOf(view(2), view(1))

        @Suppress("UNCHECKED_CAST")
        val runs = controller.runs(id).data["runs"] as List<TestRunView>

        runs.map { it.version } shouldBe listOf(1)
        controller.runs(id).data["limit"] shouldBe 100
    }

    @Test
    fun `a lens-hidden visualization is the family's 404 on every evidence read`() {
        authenticate()
        val narrowed = controller(PromoterLens { LensedView(ReadLens.NOTHING, ReadLens.NOTHING, visualizations = ReadLens.NOTHING) })
        every { visualizations.listVersions(workspaceId, any(), id) } returns emptyList()

        listOf<() -> Any>(
            { narrowed.runs(id) },
            { narrowed.run(id, runId) },
            { narrowed.runScreenshot(id, runId) },
        ).forEach { read -> shouldThrow<ApiException> { read() }.code shouldBe VisualizationErrorCodes.NOT_FOUND }
        verify(exactly = 0) { sessions.runs(any(), any()) }
        verify(exactly = 0) { sessions.runById(any(), any(), any()) }
    }

    @Test
    fun `a run of a version the lens does not admit answers exactly as an unknown run`() {
        authenticate()
        every { visualizations.listVersions(workspaceId, any(), id) } returns listOf(detail(1, PipelineVersionStatus.RELEASED))
        every { sessions.runById(workspaceId, id, runId) } returns view(2)

        val hidden = shouldThrow<DatapipelinesException> { controller.run(id, runId) }
        val bytes = shouldThrow<DatapipelinesException> { controller.runScreenshot(id, runId) }

        hidden.code shouldBe VisualizationErrorCodes.TEST_SESSION_NOT_FOUND
        hidden.details shouldBe mapOf("reason" to "session_unknown")
        (bytes.code to bytes.details) shouldBe (hidden.code to hidden.details)
        verify(exactly = 0) { sessions.screenshotBytes(any(), any(), any()) }
    }

    @Test
    fun `the run screenshot streams the stored bytes with the stored media type`() {
        authenticate()
        every { visualizations.listVersions(workspaceId, any(), id) } returns listOf(detail(1, PipelineVersionStatus.DRAFT))
        every { sessions.runById(workspaceId, id, runId) } returns view(1)
        every { sessions.screenshotBytes(workspaceId, id, runId) } returns ScreenshotBytes("image/webp", byteArrayOf(1, 2, 3))

        val response = controller.runScreenshot(id, runId)

        response.headers.contentType.toString() shouldBe "image/webp"
        response.body?.toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `check reads the version through the lens and answers the mechanical report - a hidden version is the 404`() {
        authenticate()
        val loaded = ArtifactVersion(record(), detail(2, PipelineVersionStatus.DRAFT), body())
        every { visualizations.findVersion(workspaceId, any(), id, 2) } returns loaded
        every { visualizations.findVersion(workspaceId, any(), id, 9) } returns null
        every { sessions.check(workspaceId, loaded.body) } returns ArtifactJson.mapper.readTree("""{"ok": true, "failures_dropped": 0}""")

        controller
            .check(id, 2)
            .data
            .path("ok")
            .asBoolean() shouldBe true
        shouldThrow<ApiException> { controller.check(id, 9) }.code shouldBe VisualizationErrorCodes.NOT_FOUND
    }

    @Test
    fun `without a configured origin the links are root-relative - never built from a request`() {
        authenticate()
        every { sessions.start(workspaceId, id, userId) } returns started()
        val relative =
            VisualizationTestsController(
                sessions,
                capabilities,
                visualizations,
                TestSessionLinks(null),
                co.datapipelines.web.EVERYTHING_LENS,
                audit,
                ClientAddressResolver(emptyList()),
            )
        (relative.start(id).data["preview_url"] as String) shouldStartWith "/visualizations/$id/preview?session="
    }

    // ---- fixtures ---------------------------------------------------------------------------------------

    private fun controller(lens: PromoterLens) =
        VisualizationTestsController(sessions, capabilities, visualizations, links, lens, audit, ClientAddressResolver(emptyList()))

    private fun authenticate() {
        val principal = AuthenticatedPrincipal(userId, "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "acme"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    private fun started() = TestSessionStarted(runId, sessionId, id, 1, "hash-v1", EXPIRES, "PREVIEW-MATERIAL", listOf("twelve months"))

    private fun submitted(
        status: TestRunStatus,
        token: String?,
    ) = TestSessionSubmitted(
        runId,
        sessionId,
        status,
        EXPIRES,
        ArtifactJson.mapper.readTree("""{"ok": true}"""),
        token,
        token?.let { EXPIRES },
    )

    private fun view(version: Int) =
        TestRunView(
            runId,
            sessionId,
            version,
            "hash-v$version",
            TestRunStatus.GREEN,
            EXPIRES,
            EXPIRES,
            EXPIRES,
            null,
            null,
            null,
            true,
            null,
        )

    private fun detail(
        version: Int,
        status: PipelineVersionStatus,
    ) = ArtifactVersionDetail(id, version, status, "hash-v$version", EXPIRES, userId)

    private fun record() =
        ArtifactRecord(id, workspaceId, "finance/visualizations/monthly_revenue", "Monthly revenue", "", null, EXPIRES, EXPIRES, userId)

    private fun body() =
        VisualizationReader()
            .readOrThrow(
                ArtifactJson.mapper.readTree(
                    """
                    {"name": "finance/visualizations/monthly_revenue", "display_name": "Monthly revenue", "description": "",
                     "renderer": {"kind": "plotly", "version": "4"},
                     "inputs": {"revenue": {"columns": [{"name": "month", "type": "STRING", "nullable": false}]}},
                     "config": {"data": [{"type": "bar", "x": []}]}, "bindings": {"data[0].x": "month"},
                     "tests": {"cases": [{"name": "twelve months", "fixtures": {"revenue": [{"month": "Jan"}]}, "assertions": [{"kind": "rendered"}]}]}}
                    """.trimIndent(),
                ),
            ).body

    /** A container stream that counts every read — zero proves the body was never touched. */
    private class CountingStream : ServletInputStream() {
        var reads = 0
            private set

        override fun read(): Int {
            reads++
            return -1
        }

        override fun isFinished(): Boolean = true

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
    }

    /** A container stream that behaves like the cap filter's counting stream past its cap. */
    private class ThrowingStream : ServletInputStream() {
        override fun read(): Int = throw RequestBodyCapFilter.RequestBodyTooLargeException(CAP.toLong())

        override fun isFinished(): Boolean = false

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
    }

    /** One recorded `audit_log` write: every argument the controller passed, nothing derived. */
    private data class Recorded(
        val event: String,
        val userId: UUID?,
        val keyId: String?,
        val sourceIp: String?,
        val details: Map<String, Any?>,
    )

    /** An in-memory sink that records each write, so a missing call is observable (rule 14: not a strict mock). */
    private class RecordingAudit : AuditEventSink {
        val events = mutableListOf<Recorded>()

        override fun log(
            event: String,
            userId: UUID?,
            keyId: String?,
            sourceIp: String?,
            userAgent: String?,
            details: Map<String, Any?>,
        ) {
            events += Recorded(event, userId, keyId, sourceIp, details)
        }
    }

    private companion object {
        const val CAP = VisualizationTestSessionService.MAX_SCREENSHOT_BYTES
        val EXPIRES: Instant = Instant.parse("2026-10-01T22:00:00Z")
    }
}
