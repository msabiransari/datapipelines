package co.datapipelines.web.config

import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.web.pipelines.PipelineBodies
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The `datasource.in_use` delete guard's reference scan (datasources §6.2), wired in
 * [DomainConfiguration.datasourceReferences]. 022 review F5: the guard used to count only the
 * CALLER'S workspace, so an admin in A could delete a global datasource referenced by B/C.
 * 023 verified (025 A4): the binding-scoped branch reintroduced the same hole by a two-step
 * bypass — `PUT {"global": false, "workspace": "x"}` re-binds a referenced GLOBAL datasource
 * with no cross-workspace check, and the bound branch then counted only the new workspace.
 * §6.2's promise is unconditional ("any non-deleted pipeline"), so the count now ALWAYS
 * aggregates across workspaces, binding included: the bound branch was a loop-avoidance
 * optimization whose correctness premise (a bound datasource is referenceable only from its
 * own workspace) is exactly what the re-bind falsifies — pre-re-bind references in other
 * workspaces exist and must still block the delete.
 */
class DatasourceReferencesWiringTest {
    private val bodies = mockk<PipelineBodies>()
    private val workspaces = mockk<WorkspaceRepository>()
    private val references = DomainConfiguration().datasourceReferences(bodies, workspaces)

    private val wsA = workspace("acme")
    private val wsB = workspace("globex")

    @Test
    fun `a global datasource counts references across ALL workspaces`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.pipelinesReferencing(wsA.id, "shared") } returns emptyList()
        every { bodies.pipelinesReferencing(wsB.id, "shared") } returns listOf("globex-report")

        references.pipelinesReferencing("shared") shouldBe listOf("globex-report")
    }

    /**
     * The 023 re-bind scenario: the row is bound to A, but B still references the name from
     * when it was global. The aggregate is the whole truth — B's pipeline blocks the delete.
     */
    @Test
    fun `a bound datasource counts references across ALL workspaces - the re-bind cannot orphan them`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.pipelinesReferencing(wsA.id, "bound") } returns listOf("acme-report")
        every { bodies.pipelinesReferencing(wsB.id, "bound") } returns listOf("globex-report")

        val referencing = references.pipelinesReferencing("bound")

        referencing shouldBe listOf("acme-report", "globex-report")
    }

    @Test
    fun `an unknown name scans globally and finds nothing - the registry's not-found path is unaffected`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.pipelinesReferencing(any(), "ghost") } returns emptyList()

        references.pipelinesReferencing("ghost") shouldBe emptyList()
    }

    private fun workspace(name: String) =
        Workspace(
            UUID.randomUUID(),
            name,
            name,
            isPersonal = false,
            createdBy = UUID.randomUUID(),
            isDeleted = false,
            createdAt = Instant.EPOCH,
        )
}
