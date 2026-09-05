package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.ResolvedPipeline
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * The rule that makes serving a pipeline over `GET` safe (design §4.2).
 *
 * The suite is organised around the two ways this check gets written wrongly:
 *
 * 1. **Checking the type and stopping.** A `DQL` node with `output.target: datasource` is the
 *    write-back form of pipeline-contract §4.3 — a genuine write wearing a read's type. The
 *    `writes back to a datasource` case exists to fail a type-only implementation.
 * 2. **Checking the top level and stopping.** A `PIPELINE` node's child can contain anything, so
 *    the three-levels-down case exists to fail a non-transitive implementation.
 *
 * Every `NodeType` is covered explicitly rather than by a loop over `entries`, so that when 072
 * adds `CALCULATOR` this suite fails to compile-by-omission... which it would not, so the
 * `every NodeType is judged` test drives the enum itself and fails on an unjudged new value.
 */
class ReadOnlyPipelineRuleTest {
    @Test
    fun `a pipeline of DQL nodes into tempdb and the caller is read-only`() {
        val verdict =
            rule().check(
                pipeline(
                    dql("stage", NodeOutput.Tempdb("stg")),
                    dql("report", NodeOutput.Caller),
                ),
                WORKSPACE,
            )
        verdict.failures.shouldBeEmpty()
    }

    @Test
    fun `a DML node is refused, naming it`() {
        val verdict = rule().check(pipeline(dql("read", NodeOutput.Caller), node("load", NodeType.DML)), WORKSPACE)

        assertAll(
            { verdict.codes shouldContainExactly listOf(PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY) },
            { verdict.failures.single().details["node_id"] shouldBe "load" },
            { verdict.failures.single().details["node_type"] shouldBe "DML" },
        )
    }

    @Test
    fun `a DDL node is refused`() {
        rule()
            .check(pipeline(node("create", NodeType.DDL)), WORKSPACE)
            .failures
            .single()
            .details["node_id"] shouldBe "create"
    }

    @Test
    fun `a DQL node writing back to a datasource is refused despite its type`() {
        // The case a type-only check waves through, and the reason this rule inspects `output`.
        val verdict = rule().check(pipeline(dql("writeback", NodeOutput.Datasource("warehouse", "t", WriteMode.REPLACE))), WORKSPACE)

        assertAll(
            { verdict.failures.single().details["node_id"] shouldBe "writeback" },
            { verdict.failures.single().details["node_type"] shouldBe "DQL" },
            {
                verdict.failures
                    .single()
                    .message
                    .contains("datasource") shouldBe true
            },
        )
    }

    @Test
    fun `every NodeType is judged — a new value cannot arrive unjudged`() {
        // Drives the enum itself: when 072 adds CALCULATOR, this test starts reporting it as
        // refused, and admitting it is the one-line change to READ_ONLY_NODE_TYPES the design
        // describes. What it forbids is a new type silently becoming servable over GET.
        val judged =
            NodeType.entries.associateWith { type ->
                when (type) {
                    NodeType.PIPELINE -> rule(childless = true).check(pipeline(pipelineNode("child", "missing", 1)), WORKSPACE)
                    NodeType.DQL -> rule().check(pipeline(dql("read", NodeOutput.Caller)), WORKSPACE)
                    else -> rule().check(pipeline(node(type.wire.lowercase(), type)), WORKSPACE)
                }.isValid
            }

        withClue("DQL into the caller is the one shape that must pass") {
            judged shouldBe
                mapOf(
                    NodeType.DQL to true,
                    NodeType.DML to false,
                    NodeType.DDL to false,
                    // A PIPELINE node is a container: judged by its CHILD, and refused here only
                    // because this fixture's child is deliberately unresolvable.
                    NodeType.PIPELINE to false,
                    // 072's calculator touches no datasource — admitted at the 074 merge.
                    NodeType.CALCULATOR to true,
                )
        }
    }

    @Test
    fun `a child pipeline with a DML node three levels down is refused, with the trail`() {
        val resolver =
            resolver(
                "level2" to pipeline(pipelineNode("to_level3", "level3", 1)),
                "level3" to pipeline(dql("read", NodeOutput.Caller), node("load", NodeType.DML)),
            )
        val verdict = ReadOnlyPipelineRule(resolver, MAX_DEPTH).check(pipeline(pipelineNode("to_level2", "level2", 1)), WORKSPACE)

        assertAll(
            { verdict.codes shouldContainExactly listOf(PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY) },
            { verdict.failures.single().details["node_id"] shouldBe "load" },
            // Without the trail, "node_id: load" sends an author looking in the wrong pipeline.
            { verdict.failures.single().details["via"] shouldBe listOf("to_level2", "to_level3") },
            { verdict.failures.single().path shouldBe "nodes.to_level2 > to_level3 > load" },
        )
    }

    @Test
    fun `a clean three-level composition is read-only`() {
        val resolver =
            resolver(
                "level2" to pipeline(pipelineNode("to_level3", "level3", 1)),
                "level3" to pipeline(dql("read", NodeOutput.Caller)),
            )
        ReadOnlyPipelineRule(resolver, MAX_DEPTH)
            .check(pipeline(pipelineNode("to_level2", "level2", 1)), WORKSPACE)
            .failures
            .shouldBeEmpty()
    }

    @Test
    fun `an unresolvable child is refused, never assumed safe`() {
        // "I could not read it" must not be reported as "it is safe" — the failure mode that
        // would let a pipeline become servable by breaking its own registry entry.
        val verdict = rule(childless = true).check(pipeline(pipelineNode("orphan", "gone", 3)), WORKSPACE)

        assertAll(
            { verdict.isValid shouldBe false },
            {
                verdict.failures
                    .single()
                    .message
                    .contains("not in the registry") shouldBe true
            },
        )
    }

    @Test
    fun `a composition deeper than the limit is refused, not silently truncated`() {
        // The walk stops at the bound; stopping must mean "refused", because the nodes past the
        // bound are exactly the ones nobody looked at.
        val resolver = resolver("deep" to pipeline(pipelineNode("deeper", "deep", 1)))
        val verdict = ReadOnlyPipelineRule(resolver, maxCompositionDepth = 2).check(pipeline(pipelineNode("d1", "deep", 1)), WORKSPACE)

        verdict.failures.any { it.message.contains("composition limit") } shouldBe true
    }

    @Test
    fun `every offending node is reported, not just the first`() {
        val verdict = rule().check(pipeline(node("a", NodeType.DML), node("b", NodeType.DDL), dql("ok", NodeOutput.Caller)), WORKSPACE)
        verdict.failures.map { it.details["node_id"] } shouldContainExactly listOf("a", "b")
    }

    @Test
    fun `the allowed set is the single constant the design promises`() {
        // The whole point of READ_ONLY_NODE_TYPES: admitting CALCULATOR (072) was one line here,
        // not a hunt through branches.
        ReadOnlyPipelineRule.READ_ONLY_NODE_TYPES shouldBe setOf(NodeType.DQL, NodeType.PIPELINE, NodeType.CALCULATOR)
    }

    private fun rule(childless: Boolean = false) =
        ReadOnlyPipelineRule(if (childless) PipelineResolver { _, _, _ -> null } else resolver(), MAX_DEPTH)

    private fun resolver(vararg children: Pair<String, Pipeline>): PipelineResolver {
        val byName = children.toMap()
        return PipelineResolver { _, name, _ -> byName[name]?.let { ResolvedPipeline(it, deleted = false) } }
    }

    private fun pipeline(vararg nodes: Node) =
        Pipeline(
            schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
            name = "fixture",
            displayName = "Fixture",
            description = "",
            settings = PipelineSettings(),
            parameters = emptyMap(),
            nodes = nodes.toList(),
        )

    private fun dql(
        id: String,
        output: NodeOutput,
    ) = node(id, NodeType.DQL, output)

    private fun node(
        id: String,
        type: NodeType,
        output: NodeOutput? = null,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = "pg",
        template = TemplateRef("$id.sql", 1),
        output = output,
        dependsOn = emptyList(),
    )

    private fun pipelineNode(
        id: String,
        childName: String,
        childVersion: Int,
    ) = Node(
        id = id,
        description = "",
        type = NodeType.PIPELINE,
        source = "",
        template = TemplateRef(),
        output = null,
        dependsOn = emptyList(),
        pipeline = PipelineNodeRef(childName, childVersion),
    )

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        const val MAX_DEPTH = 5
    }
}
