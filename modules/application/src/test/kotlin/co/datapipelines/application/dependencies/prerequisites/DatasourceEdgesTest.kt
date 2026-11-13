package co.datapipelines.application.dependencies.prerequisites

import co.datapipelines.dependencies.AccessMode
import co.datapipelines.dependencies.Constraint
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.Reference
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.Selector
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #873 §F.1/§F.2: the ONE datasource extractor, over bodies built in memory. The pipeline dispatch is `ReferenceRules`'
 * (DQL/DML/DDL source and output, PIPELINE output only, CALCULATOR and SCRIPT none, tempdb never a datasource); each
 * reference is the datasource NAME with an ENTITY selector at the native path, a DQL source read and every other use
 * write; a selector's datasource carries its template's dialect when one is known and none otherwise.
 */
class DatasourceEdgesTest {
    private val workspace = UUID.randomUUID()

    @Test
    fun `a DQL source is a read, a DML or DDL source a write, a datasource output a write - native paths, node order`() {
        val pipeline =
            pipeline(
                node("q", NodeType.DQL, source = "warehouse", output = NodeOutput.Datasource("mart", "facts", WriteMode.APPEND)),
                node("m", NodeType.DML, source = "mart"),
                node("d", NodeType.DDL, source = "mart"),
            )

        val references = DatasourceEdges.ofPipeline(workspace, pipeline)

        references.map(::describe) shouldContainExactly
            listOf(
                "nodes[0].source warehouse READ",
                "nodes[0].output.datasource mart WRITE",
                "nodes[1].source mart WRITE",
                "nodes[2].source mart WRITE",
            )
        references.forEach { reference ->
            reference.relationship shouldBe Relationship.DATASOURCE
            reference.request.family shouldBe FamilyKey.DATASOURCE
            reference.request.selector shouldBe Selector.Entity
            reference.request.address.lookupScope.workspaceId shouldBe workspace
            reference.spelledName shouldBe reference.request.address.lookupKey
        }
        DatasourceEdges.namesOf(references) shouldContainExactly listOf("warehouse", "mart")
    }

    @Test
    fun `tempdb is never a datasource - a tempdb source and a tempdb or caller output give no edge`() {
        val pipeline =
            pipeline(
                node("t", NodeType.DQL, source = "tempdb", output = NodeOutput.Tempdb("stage")),
                node("c", NodeType.DQL, source = "tempdb", output = NodeOutput.Caller),
            )

        DatasourceEdges.ofPipeline(workspace, pipeline).shouldBeEmpty()
    }

    @Test
    fun `CALCULATOR and SCRIPT nodes give no edge - a PIPELINE node gives its datasource output only`() {
        val pipeline =
            pipeline(
                node("calc", NodeType.CALCULATOR, source = "warehouse"),
                node("script", NodeType.SCRIPT, source = "warehouse", output = NodeOutput.Datasource("mart", "t", WriteMode.APPEND)),
                node(
                    "child",
                    NodeType.PIPELINE,
                    source = "warehouse",
                    output = NodeOutput.Datasource("mart", "t", WriteMode.APPEND),
                    pipeline = PipelineNodeRef("acme/child", 1),
                ),
                node("staged", NodeType.PIPELINE, source = "", output = NodeOutput.Tempdb("c"), pipeline = PipelineNodeRef("acme/c", 1)),
            )

        DatasourceEdges.ofPipeline(workspace, pipeline).map(::describe) shouldContainExactly
            listOf("nodes[2].output.datasource mart WRITE")
    }

    @Test
    fun `a selector datasource is a PARAMETER_SOURCE_DATASOURCE edge with its template's dialect - none when unknown`() {
        val body =
            ParameterSetJson.readBody(
                """{"display_name":"F","parameters":[""" +
                    """{"name":"a","label":"A","type":"INTEGER","kind":"SELECT",""" +
                    """"source":{"template":{"id":"acme/states","version":1},"datasource":"warehouse"}},""" +
                    """{"name":"b","label":"B","type":"INTEGER","kind":"INPUT"},""" +
                    """{"name":"c","label":"C","type":"INTEGER","kind":"SELECT",""" +
                    """"source":{"template":{"id":"acme/cities","version":4},"datasource":"warehouse"}}]}""",
            )

        val references = DatasourceEdges.ofParameterSet(workspace, body) { index -> "postgresql".takeIf { index == 0 } }

        references.map { it.sourcePath to it.constraints } shouldContainExactly
            listOf(
                "parameters[0].source.datasource" to listOf(Constraint.DatasourceDialect("postgresql")),
                "parameters[2].source.datasource" to emptyList(),
            )
        references.map { it.relationship }.distinct() shouldContainExactly listOf(Relationship.PARAMETER_SOURCE_DATASOURCE)
        DatasourceEdges.namesOf(references) shouldContainExactly listOf("warehouse")
    }

    private fun describe(reference: Reference): String {
        val access =
            reference.constraints
                .filterIsInstance<Constraint.DatasourceAccess>()
                .single()
                .mode
        check(access == AccessMode.READ || access == AccessMode.WRITE)
        return "${reference.sourcePath} ${reference.request.address.lookupKey} $access"
    }

    private fun pipeline(vararg nodes: Node) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = "acme/report",
            displayName = "R",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes = nodes.toList(),
        )

    private fun node(
        id: String,
        type: NodeType,
        source: String,
        output: NodeOutput? = NodeOutput.Caller,
        pipeline: PipelineNodeRef? = null,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = source,
        template = TemplateRef(),
        output = output,
        dependsOn = emptyList(),
        pipeline = pipeline,
    )
}
