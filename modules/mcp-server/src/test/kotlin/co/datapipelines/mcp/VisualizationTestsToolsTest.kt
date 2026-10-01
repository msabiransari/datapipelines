package co.datapipelines.mcp

import co.datapipelines.auth.Permission
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.CaseVerdict
import co.datapipelines.visualization.SubmittedCase
import co.datapipelines.visualization.TestEnvironment
import co.datapipelines.visualization.TestRunStatus
import co.datapipelines.visualization.TestSessionLinks
import co.datapipelines.visualization.TestSessionStarted
import co.datapipelines.visualization.TestSessionSubmitted
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationTestSessionService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The two test-session tools over a mocked session service: each starts and submits as the CALLING key's identity in
 * its pinned workspace, the submit's arguments are judged by the one reader REST uses (a wrong shape refuses before
 * the service), and both answer the REST shape — the preview URL on start, the single-use upload only on GREEN.
 */
class VisualizationTestsToolsTest {
    private val sessions = mockk<VisualizationTestSessionService>()
    private val links = TestSessionLinks("https://dp.example.com")
    private val ctx = McpFixtures.ctx()
    private val workspaceId = McpFixtures.WORKSPACE.id
    private val userId = ctx.principal.userId
    private val id = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val runId = UUID.randomUUID()
    private val at = Instant.parse("2026-10-01T22:00:00Z")

    @Test
    fun `both tools are catalogued as visualization-update writes`() {
        listOf("visualizations_test_start", "visualizations_test_submit").forEach { name ->
            McpToolCatalog.permissionOf(name) shouldBe Permission.VISUALIZATION_UPDATE
            McpToolCatalog.isMutating(name) shouldBe true
        }
        visualizationTestTools(sessions, links).map { it.name } shouldContainExactly
            listOf("visualizations_test_start", "visualizations_test_submit")
    }

    @Test
    fun `visualizations_test_start starts as the key's identity and answers the preview URL, cases and deadline`() {
        every { sessions.start(workspaceId, id, userId) } returns
            TestSessionStarted(runId, sessionId, id, 2, "hash-v2", at, "PREVIEW", listOf("one", "two"))

        val answer = VisualizationsTestStartTool(sessions, links).call(McpArguments(mapOf("id" to id.toString())), ctx) as Map<*, *>

        answer["preview_url"] shouldBe "https://dp.example.com/visualizations/$id/preview?session=PREVIEW"
        answer["cases"] shouldBe listOf("one", "two")
        answer["expires_at"] shouldBe at.toString()
        answer["session_id"] shouldBe sessionId.toString()
    }

    @Test
    fun `visualizations_test_submit binds the verdicts and environment and a GREEN answer carries the upload once`() {
        val verdicts = slot<List<SubmittedCase>>()
        val environment = slot<TestEnvironment>()
        every { sessions.submit(workspaceId, id, sessionId, userId, capture(verdicts), capture(environment)) } returns
            TestSessionSubmitted(runId, sessionId, TestRunStatus.GREEN, at, ArtifactJson.mapper.readTree("""{"ok":true}"""), "UPLOAD", at)

        val answer =
            VisualizationsTestSubmitTool(sessions, links).call(
                McpArguments(
                    mapOf(
                        "id" to id.toString(),
                        "session_id" to sessionId.toString(),
                        "cases" to listOf(mapOf("name" to "one", "verdict" to "green", "notes" to "ok")),
                        "environment" to mapOf("browser" to "chromium 130", "renderer_version" to "plotly 4.1.1"),
                    ),
                ),
                ctx,
            ) as Map<*, *>

        verdicts.captured shouldBe listOf(SubmittedCase("one", CaseVerdict.GREEN, "ok"))
        environment.captured.browser shouldBe "chromium 130"
        answer["status"] shouldBe "GREEN"
        val upload = answer["upload"] as Map<*, *>
        upload["url"] shouldBe "https://dp.example.com/api/v1/visualizations/$id/tests/sessions/$sessionId/screenshot"
        upload["header"] shouldBe "DP-Upload-Token"
        upload["token"] shouldBe "UPLOAD"
    }

    @Test
    fun `a RED or INCOMPLETE run answers no upload`() {
        every { sessions.submit(workspaceId, id, sessionId, userId, any(), any()) } returns
            TestSessionSubmitted(
                runId,
                sessionId,
                TestRunStatus.INCOMPLETE,
                at,
                ArtifactJson.mapper.readTree("""{"ok":true}"""),
                null,
                null,
            )
        val answer =
            VisualizationsTestSubmitTool(sessions, links).call(
                McpArguments(mapOf("id" to id.toString(), "session_id" to sessionId.toString(), "cases" to emptyList<Any>())),
                ctx,
            ) as Map<*, *>
        answer["upload"].shouldBeNull()
    }

    @Test
    fun `a submit of the wrong shape is refused by the shared reader before the service`() {
        val error =
            shouldThrow<DatapipelinesException> {
                VisualizationsTestSubmitTool(sessions, links).call(
                    McpArguments(
                        mapOf(
                            "id" to id.toString(),
                            "session_id" to sessionId.toString(),
                            "cases" to listOf(mapOf("name" to "one", "verdict" to "maybe")),
                        ),
                    ),
                    ctx,
                )
            }
        error.code shouldBe VisualizationErrorCodes.BODY_INVALID
        error.details["path"] shouldBe "cases[0].verdict"
        verify(exactly = 0) { sessions.submit(any(), any(), any(), any(), any(), any()) }
    }
}
