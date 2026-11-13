package co.datapipelines.web.config

import co.datapipelines.application.dependencies.DatasourceReferencesAdapter
import co.datapipelines.application.dependencies.IncomingRows
import co.datapipelines.application.dependencies.InverseArm
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceRepository
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.ParameterSetDatasourceReference
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
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
 *
 * **061/T79 added the second axis.** The aggregate is across workspaces AND across pipeline
 * VERSIONS: the guard reads every stored version, never the working-version scan the pipelines
 * listing uses. Both axes are the same failure — a scan narrower than the promise — reached
 * from different directions.
 *
 * **#877 §C:** the bean is a [DatasourceReferencesAdapter] over the datasource family's two
 * inverse arms; both axes are now the QUESTION it asks — every workspace, entity-wide, the ANY
 * scope — which a recording inverse read pins here (the arms' SQL is the conformance suite's).
 */
class DatasourceReferencesWiringTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val answers = mutableMapOf<Triple<UUID, InverseArm, String>, List<InverseRow>>()
    private val asked = mutableListOf<Asked>()
    private val incoming =
        IncomingRows { ws, arm, key, version, scope ->
            asked += Asked(ws, arm, key, version, scope)
            answers[Triple(ws, arm, key)].orEmpty()
        }
    private val references = DatasourceReferencesAdapter(incoming) { workspaces.findAll().map { it.id } }

    private val wsA = workspace("acme")
    private val wsB = workspace("globex")

    private data class Asked(
        val workspaceId: UUID,
        val arm: InverseArm,
        val key: String,
        val version: Int?,
        val scope: InverseScope,
    )

    private fun pipelines(
        ws: Workspace,
        name: String,
        vararg rows: InverseRow,
    ) {
        answers[Triple(ws.id, InverseArm.DATASOURCE_FROM_PIPELINE, name)] = rows.toList()
    }

    @Test
    fun `a global datasource counts references across ALL workspaces`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        pipelines(wsB, "shared", ref("globex-report"))

        references.referencesTo("shared").map { it.pipelineName } shouldBe listOf("globex-report")
    }

    /**
     * The 023 re-bind scenario: the row is bound to A, but B still references the name from
     * when it was global. The aggregate is the whole truth — B's pipeline blocks the delete.
     */
    @Test
    fun `a bound datasource counts references across ALL workspaces - the re-bind cannot orphan them`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        pipelines(wsA, "bound", ref("acme-report"))
        pipelines(wsB, "bound", ref("globex-report"))

        val referencing = references.referencesTo("bound")

        referencing.map { it.pipelineName } shouldBe listOf("acme-report", "globex-report")
        // #873 §F.3 e: each reference carries the workspace it was found in — the evidence a refusal is scoped by.
        referencing.map { it.workspaceId } shouldBe listOf(wsA.id, wsB.id)
    }

    /**
     * 061/T79: the whole point of the second axis. A reference living only in a RELEASED v1
     * that v2 dropped is a real reference — v1 is immutable and still executable by explicit
     * version — and the projection carries the version so the refusal can name it. A node naming
     * the datasource as both source and output is ONE reference.
     */
    @Test
    fun `a reference in a HISTORICAL version blocks the delete and its version reaches the refusal`() {
        every { workspaces.findAll() } returns listOf(wsA)
        val pipeline = UUID.randomUUID().toString()
        pipelines(
            wsA,
            "retired",
            InverseRow(pipeline, "monthly_revenue", 1, PipelineVersionStatus.RELEASED, "nodes[0].source", null, "extract"),
            InverseRow(pipeline, "monthly_revenue", 1, PipelineVersionStatus.RELEASED, "nodes[0].output.datasource", null, "extract"),
        )

        references.referencesTo("retired") shouldBe
            listOf(DatasourceReference("monthly_revenue", 1, "RELEASED", "extract", wsA.id))
    }

    @Test
    fun `an unknown name scans globally and finds nothing - the registry's not-found path is unaffected`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)

        references.referencesTo("ghost") shouldBe emptyList()
        asked.map { it.workspaceId } shouldBe listOf(wsA.id, wsB.id)
    }

    /**
     * The guard must never fall back to the working-version scan: that join sees
     * `current_version` only, which is the defect this wiring exists to close. #877 §C: the
     * question is the inverse arm's — entity-wide (no version), at the ANY scope — and it is the
     * ONLY question the pipeline half asks, once per workspace.
     */
    @Test
    fun `the guard never reads the working-version listing scan`() {
        every { workspaces.findAll() } returns listOf(wsA)

        references.referencesTo("any")

        asked shouldBe listOf(Asked(wsA.id, InverseArm.DATASOURCE_FROM_PIPELINE, "any", null, InverseScope.ANY))
    }

    /**
     * #873 §F.3 e: the parameter-set arm reads the same two axes — every workspace, every version (a DISCARDED one
     * included) — and projects each selector occurrence with its version, status and path.
     */
    @Test
    fun `the parameter-set arm counts selector references across ALL workspaces and every version`() {
        every { workspaces.findAll() } returns listOf(wsA, wsB)
        val arm = InverseArm.DATASOURCE_FROM_PARAMETER_SET
        answers[Triple(wsA.id, arm, "warehouse")] =
            listOf(setRow("acme/filters", 2, PipelineVersionStatus.DISCARDED, "parameters[1].source.datasource"))
        answers[Triple(wsB.id, arm, "warehouse")] =
            listOf(setRow("globex/filters", 1, PipelineVersionStatus.RELEASED, "parameters[0].source.datasource"))

        references.parameterSetReferencesTo("warehouse") shouldBe
            listOf(
                ParameterSetDatasourceReference("acme/filters", 2, "DISCARDED", "parameters[1].source.datasource", wsA.id),
                ParameterSetDatasourceReference("globex/filters", 1, "RELEASED", "parameters[0].source.datasource", wsB.id),
            )
        asked shouldBe
            listOf(
                Asked(wsA.id, arm, "warehouse", null, InverseScope.ANY),
                Asked(wsB.id, arm, "warehouse", null, InverseScope.ANY),
            )
    }

    private fun ref(pipeline: String) =
        InverseRow(UUID.randomUUID().toString(), pipeline, 1, PipelineVersionStatus.RELEASED, "nodes[0].source", null, "n1")

    private fun setRow(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        path: String,
    ) = InverseRow(UUID.randomUUID().toString(), name, version, status, path, null, null)

    private fun workspace(name: String) =
        Workspace(
            UUID.randomUUID(),
            name,
            name,
            isPersonal = false,
            isDeleted = false,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.EPOCH,
        )
}
