package co.datapipelines.web.config

import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.DatasourceRow
import co.datapipelines.web.pipelines.PipelineBodies
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The `datasource.in_use` delete guard's reference scan (datasources §6.2), wired in
 * [DomainConfiguration.datasourceReferences]. 022 review F5: the guard used to count only the
 * CALLER'S workspace, so an admin in A could delete a global datasource referenced by B/C.
 * The scope now follows the datasource's BINDING: global counts across ALL workspaces; bound
 * keeps its own workspace's count.
 */
class DatasourceReferencesWiringTest {
    private val bodies = mockk<PipelineBodies>()
    private val repository = mockk<DatasourceRepository>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val references = DomainConfiguration().datasourceReferences(bodies, repository, workspaces)

    private val wsA = workspace("acme")
    private val wsB = workspace("globex")

    @Test
    fun `a global datasource counts references across ALL workspaces`() {
        every { repository.findByName("shared") } returns row(boundTo = null)
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.pipelinesReferencing(wsA.id, "shared") } returns emptyList()
        every { bodies.pipelinesReferencing(wsB.id, "shared") } returns listOf("globex-report")

        references.pipelinesReferencing("shared") shouldBe listOf("globex-report")
    }

    @Test
    fun `a bound datasource keeps its own workspace's count - no cross-workspace scan`() {
        every { repository.findByName("bound") } returns row(boundTo = wsA.id)
        every { bodies.pipelinesReferencing(wsA.id, "bound") } returns listOf("report")

        references.pipelinesReferencing("bound") shouldBe listOf("report")

        verify(exactly = 0) { workspaces.findAll() }
        verify(exactly = 0) { bodies.pipelinesReferencing(wsB.id, any()) }
    }

    @Test
    fun `an unknown name scans globally and finds nothing - the registry's not-found path is unaffected`() {
        every { repository.findByName("ghost") } returns null
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.pipelinesReferencing(any(), "ghost") } returns emptyList()

        references.pipelinesReferencing("ghost") shouldBe emptyList()
    }

    private fun workspace(name: String) =
        Workspace(UUID.randomUUID(), name, name, isPersonal = false, createdBy = UUID.randomUUID(), isDeleted = false, createdAt = Instant.EPOCH)

    private fun row(boundTo: UUID?): DatasourceRow =
        mockk {
            every { workspaceId } returns boundTo
        }
}
