package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.DatasourceRef
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 056 moved the LISTING half of this class (the `scan` builder and its `q` filter) into
 * `PipelineService.list`, where REST, MCP and both UI screens share it — its three tests moved
 * with it, verbatim in intent, to `PipelineServiceIntegrationTest`. What is asserted here is what
 * is left: the datasource delete guard's any-version reference scan.
 */
class PipelineBodiesTest {
    private val repository = mockk<PipelineRepository>()
    private val bodies = PipelineBodies(repository)
    private val workspaceId = UUID.randomUUID()

    /**
     * 061/T79 — the delete guard's scan is the ANY-VERSION one, not `findAllByDatasource`.
     * The mock proves the delegation; `PipelineRepositoryDatasourceRefsIntegrationTest` proves
     * the SQL actually sees a historical version, which is the part that was broken.
     */
    @Test
    fun `anyVersionReferences delegates to findAnyVersionDatasourceRefs, never the current-version join`() {
        val refs =
            listOf(
                DatasourceRef(UUID.randomUUID(), "a", 1, PipelineVersionStatus.RELEASED, "n1"),
                DatasourceRef(UUID.randomUUID(), "b", 3, PipelineVersionStatus.DRAFT, "n2"),
            )
        every { repository.findAnyVersionDatasourceRefs(workspaceId, "pg-prod") } returns refs

        bodies.anyVersionReferences(workspaceId, "pg-prod") shouldBe refs
        verify(exactly = 0) { repository.findAllByDatasource(any(), "pg-prod", any(), any(), any()) }
    }
}
