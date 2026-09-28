package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointRow
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.ui.ApiConsoleController
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * #286 item 4 — a legacy row's stored path is echoed BOUNDED on the two web surfaces that show it:
 * the REST listing (`GET /api/v1/endpoints`) and the API console's Legacy paths table. The MCP
 * twin is `EndpointsListLegacyToolsTest`.
 *
 * `path_pattern` is `TEXT` with no CHECK, so only a database write can store a path longer than
 * the grammar's [EndpointPath.MAX_LENGTH] (every API write has been held to it since the registry
 * was born). The bound is at the echo, never on the model: unpublish is BY PATH, so the stored
 * row keeps its path.
 */
class LegacyEndpointEchoBoundTest {
    private val workspaceId = UUID.randomUUID()
    private val long = "/nyc/" + "x".repeat(LONG_TAIL)
    private val legacy =
        EndpointRow.Legacy(
            id = UUID.randomUUID(),
            workspaceId = workspaceId,
            pathPattern = long,
            pipelineId = UUID.randomUUID(),
            reason = "Path is ${long.length} characters; the limit is ${EndpointPath.MAX_LENGTH} (§4.1).",
            enabled = false,
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `the REST listing echoes a legacy row's path and url cut at the grammar's limit`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(UUID.randomUUID(), "a@b.c", "A", AuthMethod.OIDC, workspace = WorkspaceContext(workspaceId, "nyc")),
                null,
                emptyList(),
            )
        val publishing = mockk<EndpointPublishService>()
        val pipelines = mockk<PipelineRepository>()
        every { publishing.list(any()) } returns emptyList()
        every { publishing.listLegacy(any()) } returns listOf(legacy)
        every { pipelines.findById(workspaceId, legacy.pipelineId) } returns null
        val controller = EndpointsController(publishing, mockk(), mockk(), mockk(), pipelines)

        @Suppress("UNCHECKED_CAST")
        val row = (controller.list(path = null).data as List<Map<String, Any?>>).single()

        row["path"] shouldBe long.take(EndpointPath.MAX_LENGTH)
        row["url"] shouldBe "/api" + long.take(EndpointPath.MAX_LENGTH)
        row["legacy"] shouldBe true
    }

    @Test
    fun `the console's Legacy paths row echoes the path and url cut at the grammar's limit`() {
        val row = ApiConsoleController.LegacyEndpointRow.of(legacy)

        row.path shouldBe long.take(EndpointPath.MAX_LENGTH)
        row.url shouldBe "/api" + long.take(EndpointPath.MAX_LENGTH)
        row.reason shouldBe legacy.reason
    }

    @Test
    fun `a legacy path within the limit is echoed exactly - the unpublish call addresses it`() {
        val short = legacy.copy(pathPattern = "/nyc/revenue-by-borough")

        ApiConsoleController.LegacyEndpointRow.of(short).path shouldBe "/nyc/revenue-by-borough"
    }

    private companion object {
        const val LONG_TAIL = 1_000
    }
}
