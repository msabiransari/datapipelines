package co.datapipelines.web.config

import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.pipeline.DatasourceRef
import co.datapipelines.pipeline.PipelineVersionStatus
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
 * 023 verified (025 A4): the binding-scoped branch reintroduced the same hole by a two-step
 * bypass — `PUT {"global": false, "workspace": "x"}` re-binds a referenced GLOBAL datasource
 * with no cross-workspace check, and the bound branch then counted only the new workspace.
 * §6.2's promise is unconditional ("any non-deleted pipeline"), so the count now ALWAYS
 * aggregates across workspaces, binding included: the bound branch was a loop-avoidance
 * optimization whose correctness premise (a bound datasource is referenceable only from its
 * own workspace) is exactly what the re-bind falsifies — pre-re-bind references in other
 * workspaces exist and must still block the delete.
 *
 * **061/T79 added the second axis.** The aggregate is across workspaces AND across pipeline
 * VERSIONS: the bean reads [PipelineBodies.anyVersionReferences], never the working-version
 * scan the pipelines listing uses. Both axes are the same failure — a scan narrower than the
 * promise — reached from different directions.
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
        every { bodies.anyVersionReferences(wsA.id, "shared") } returns emptyList()
        every { bodies.anyVersionReferences(wsB.id, "shared") } returns listOf(ref("globex-report"))

        references.referencesTo("shared").map { it.pipelineName } shouldBe listOf("globex-report")
    }

    /**
     * The 023 re-bind scenario: the row is bound to A, but B still references the name from
     * when it was global. The aggregate is the whole truth — B's pipeline blocks the delete.
     */
    @Test
    fun `a bound datasource counts references across ALL workspaces - the re-bind cannot orphan them`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.anyVersionReferences(wsA.id, "bound") } returns listOf(ref("acme-report"))
        every { bodies.anyVersionReferences(wsB.id, "bound") } returns listOf(ref("globex-report"))

        val referencing = references.referencesTo("bound")

        referencing.map { it.pipelineName } shouldBe listOf("acme-report", "globex-report")
    }

    /**
     * 061/T79: the whole point of the second scan. A reference living only in a RELEASED v1
     * that v2 dropped is a real reference — v1 is immutable and still executable by explicit
     * version — and the projection carries the version so the refusal can name it.
     */
    @Test
    fun `a reference in a HISTORICAL version blocks the delete and its version reaches the refusal`() {
        every { workspaces.findAll() } returns listOf(wsA)
        every { bodies.anyVersionReferences(wsA.id, "retired") } returns
            listOf(DatasourceRef(UUID.randomUUID(), "monthly_revenue", 1, PipelineVersionStatus.RELEASED, "extract"))

        references.referencesTo("retired") shouldBe
            listOf(DatasourceReference("monthly_revenue", 1, "RELEASED", "extract"))
    }

    @Test
    fun `an unknown name scans globally and finds nothing - the registry's not-found path is unaffected`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        every { bodies.anyVersionReferences(any(), "ghost") } returns emptyList()

        references.referencesTo("ghost") shouldBe emptyList()
    }

    /**
     * The guard must never fall back to the working-version scan: that join sees
     * `current_version` only, which is the defect this wiring exists to close.
     */
    @Test
    fun `the guard never reads the working-version listing scan`() {
        every { workspaces.findAll() } returns listOf(wsA)
        every { bodies.anyVersionReferences(wsA.id, "any") } returns emptyList()

        references.referencesTo("any")

        verify(exactly = 0) { bodies.scan(any(), any(), any()) }
    }

    private fun ref(pipeline: String) = DatasourceRef(UUID.randomUUID(), pipeline, 1, PipelineVersionStatus.RELEASED, "n1")

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
