package co.datapipelines.application.visualization

import co.datapipelines.application.endpoints.ReadOnlyPipelineRule
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.OutputColumn
import co.datapipelines.visualization.PipelineParameterFact
import com.fasterxml.jackson.databind.node.IntNode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * The production pipeline-release port the dashboard validator reads (L1b): the status off the resolver, the
 * read-only rule's verdict, the parameters a caller must supply, and the caller columns — DECLARED ones only.
 * The `outputColumns` cases are the decision the lane records: a transform caller node's table contract is the
 * only declaration; no caller node is an empty output; a SQL caller node (or a non-table contract) is null — the
 * validator's cue to skip rather than guess.
 */
class PipelineReleaseFactsReaderTest {
    @Test
    fun `an unknown name or version is null - the validator's dependency_not_found`() {
        reader(emptyMap()).releaseOf(WORKSPACE, ArtifactRef("acme/pipelines/absent", 1)).shouldBeNull()
    }

    @Test
    fun `status, the read-only verdict and the parameters a caller must supply`() {
        val pipeline =
            pipeline(
                dql("report", NodeOutput.Caller),
                parameters =
                    mapOf(
                        "year" to Parameter(LogicalType.INTEGER, required = true),
                        "limit" to Parameter(LogicalType.INTEGER, required = true, default = IntNode(10)),
                        "currency" to Parameter(LogicalType.STRING),
                    ),
            )
        val fact = checkNotNull(reader(mapOf(REF to resolved(pipeline, PipelineVersionStatus.DRAFT))).releaseOf(WORKSPACE, REF))

        assertAll(
            { fact.status shouldBe PipelineVersionStatus.DRAFT },
            { fact.readOnly shouldBe true },
            {
                fact.parameters shouldBe
                    listOf(
                        PipelineParameterFact("year", required = true),
                        PipelineParameterFact("limit", required = false),
                        PipelineParameterFact("currency", required = false),
                    )
            },
        )
    }

    @Test
    fun `a write-back node makes the release not read-only - the endpoint rule, unchanged`() {
        val pipeline = pipeline(dql("report", NodeOutput.Caller), node("load", NodeType.DML, output = null))
        checkNotNull(reader(mapOf(REF to resolved(pipeline))).releaseOf(WORKSPACE, REF)).readOnly shouldBe false
    }

    @Test
    fun `a transform caller node declares its contract's table columns`() {
        val pipeline = pipeline(dql("stage", NodeOutput.Tempdb("stg")), node("shape", NodeType.TRANSFORM, NodeOutput.Caller))
        val columns =
            listOf(
                TransformContractView.Column("month", LogicalType.DATE, nullable = false),
                TransformContractView.Column("amount", LogicalType.DECIMAL, nullable = true),
            )
        val table = contract(TransformContractView.Output.Table(columns))
        val fact = reader(mapOf(REF to resolved(pipeline)), table).releaseOf(WORKSPACE, REF)

        checkNotNull(fact).outputColumns shouldBe
            listOf(OutputColumn("month", LogicalType.DATE, nullable = false), OutputColumn("amount", LogicalType.DECIMAL, nullable = true))
    }

    @Test
    fun `no caller node is an EMPTY output - the release returns no rows`() {
        val pipeline = pipeline(dql("stage", NodeOutput.Tempdb("stg")))
        checkNotNull(reader(mapOf(REF to resolved(pipeline))).releaseOf(WORKSPACE, REF)).outputColumns shouldBe emptyList()
    }

    @Test
    fun `a SQL caller node declares no columns - null, never a guess`() {
        val pipeline = pipeline(dql("report", NodeOutput.Caller))
        val column = TransformContractView.Column("x", LogicalType.STRING, nullable = true)
        val tableContract = contract(TransformContractView.Output.Table(listOf(column)))
        // Even with a contract lookup that WOULD answer, a DQL caller node is not a transform: nothing is declared.
        checkNotNull(reader(mapOf(REF to resolved(pipeline)), tableContract).releaseOf(WORKSPACE, REF)).outputColumns.shouldBeNull()
    }

    @Test
    fun `a transform caller whose contract is missing or not a table declares no columns`() {
        val pipeline = pipeline(node("shape", NodeType.TRANSFORM, NodeOutput.Caller))
        val known = mapOf(REF to resolved(pipeline))
        assertAll(
            { checkNotNull(reader(known) { _, _ -> null }.releaseOf(WORKSPACE, REF)).outputColumns.shouldBeNull() },
            {
                val value = contract(TransformContractView.Output.Value(LogicalType.INTEGER))
                checkNotNull(reader(known, value).releaseOf(WORKSPACE, REF)).outputColumns.shouldBeNull()
            },
            {
                val obj = contract(TransformContractView.Output.Obj)
                checkNotNull(reader(known, obj).releaseOf(WORKSPACE, REF)).outputColumns.shouldBeNull()
            },
        )
    }

    // ---- fixtures -------------------------------------------------------------------------------------

    private fun reader(
        known: Map<ArtifactRef, ResolvedPipeline>,
        contracts: (UUID, TemplateRef) -> TransformContractView? = { _, _ -> null },
    ): PipelineReleaseFactsReader {
        val resolver = PipelineResolver { _, name, version -> known[ArtifactRef(name, version)] }
        return PipelineReleaseFactsReader(resolver, ReadOnlyPipelineRule(resolver, MAX_DEPTH), contracts)
    }

    private fun contract(output: TransformContractView.Output): (UUID, TemplateRef) -> TransformContractView? =
        { _, _ -> TransformContractView(TransformContractView.Mode.TABLE, emptyMap(), output, rejects = false) }

    private fun resolved(
        pipeline: Pipeline,
        status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    ) = ResolvedPipeline(pipeline, entityDiscarded = false, versionStatus = status)

    private fun pipeline(
        vararg nodes: Node,
        parameters: Map<String, Parameter> = emptyMap(),
    ) = Pipeline(
        schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
        name = REF.name,
        displayName = "Monthly revenue",
        description = "",
        settings = PipelineSettings(),
        parameters = parameters,
        nodes = nodes.toList(),
    )

    private fun dql(
        id: String,
        output: NodeOutput,
    ) = node(id, NodeType.DQL, output)

    private fun node(
        id: String,
        type: NodeType,
        output: NodeOutput?,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = "pg",
        template = TemplateRef("finance/$id", 1),
        output = output,
        dependsOn = emptyList(),
    )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-00000000c1b0")
        val REF = ArtifactRef("finance/pipelines/monthly_revenue", 7)
        const val MAX_DEPTH = 5
    }
}
