package co.datapipelines.application.dependencies

import co.datapipelines.application.dependencies.prerequisites.DatasourceEdges
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.pipeline.inverse.PinNodeRules
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

/**
 * #877 §B.1, sweep finding 7: the node-type rule the pipeline inverse SQL reads ([PinNodeRules]) is the rule the
 * outgoing extractors apply — for every [NodeType], a node carrying every reference shape at once (a template, a child
 * pipeline, a datasource source, a datasource output) yields exactly the references the rule sets admit:
 * [PipelineDependencySource.pinsOf] for template and child pins, [DatasourceEdges.ofPipeline] for datasources. A new
 * node type, or an extractor that changes its dispatch, turns this red before the inverse and the walk can disagree.
 */
class PinNodeRulesTest {
    @ParameterizedTest
    @EnumSource(NodeType::class)
    fun `each node type's references are the ones the rule sets admit`(type: NodeType) {
        val pipeline = pipelineOf(node(type, source = WAREHOUSE))
        val pins = PipelineDependencySource.pinsOf(WORKSPACE, pipeline)
        pins.filter { it.target.family == ReleaseFamily.TEMPLATE }.map { it.path } shouldBe
            listOfNotNull("nodes[0].template".takeIf { type in PinNodeRules.TEMPLATE })
        pins.filter { it.target.family == ReleaseFamily.PIPELINE }.map { it.path } shouldBe
            listOfNotNull("nodes[0].pipeline".takeIf { type in PinNodeRules.CHILD })
        DatasourceEdges.ofPipeline(WORKSPACE, pipeline).map { it.sourcePath } shouldBe
            listOfNotNull(
                "nodes[0].source".takeIf { type in PinNodeRules.DATASOURCE_SOURCE },
                "nodes[0].output.datasource".takeIf { type in PinNodeRules.DATASOURCE_OUTPUT },
            )
    }

    @ParameterizedTest
    @EnumSource(NodeType::class)
    fun `a tempdb source is never a datasource reference`(type: NodeType) {
        val staged = node(type, source = "tempdb", output = NodeOutput.Tempdb("stg"))
        DatasourceEdges.ofPipeline(WORKSPACE, pipelineOf(staged)).shouldBeEmpty()
    }

    @Test
    fun `the SQL predicate names the rule's types and only those`() {
        PinNodeRules.typeIn("n.node", setOf(NodeType.DML, NodeType.DQL)) shouldBe
            "n.node->>'type' IN ('${NodeType.DML.wire}', '${NodeType.DQL.wire}')"
    }

    private fun node(
        type: NodeType,
        source: String,
        output: NodeOutput = NodeOutput.Datasource(WAREHOUSE, "t", WriteMode.REPLACE),
    ) = Node(
        id = "n",
        description = "",
        type = type,
        source = source,
        template = TemplateRef("lib", 1),
        output = output,
        dependsOn = emptyList(),
        pipeline = PipelineNodeRef("child", 1),
    )

    private fun pipelineOf(node: Node) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = "p",
            displayName = "P",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes = listOf(node),
        )

    private companion object {
        val WORKSPACE: UUID = UUID.randomUUID()
        const val WAREHOUSE = "warehouse"
    }
}
