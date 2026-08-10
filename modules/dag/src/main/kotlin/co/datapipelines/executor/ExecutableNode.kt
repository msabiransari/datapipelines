package co.datapipelines.executor

import co.datapipelines.dag.Dag
import co.datapipelines.pipeline.CallerNodeResolver
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.TemplateRef

/**
 * The executor's node representation (dag-executor.md §4) — separate from the wire [Node], which
 * keeps `source` as the raw string and carries Jackson binding concerns.
 *
 * ## Why the enums and the `NodeOutput` hierarchy are not re-declared here
 *
 * §4 shows `NodeType`, `NodeSource`, `NodeOutput`, `WriteMode` and `TemplateRef` declared in this
 * module. Every one of them already exists in `pipeline-contract`, which `dag` depends on, and
 * they exist there **because of this spec**: pipeline-contract §17.1 aligned to "dag-executor's
 * flat sealed interface wins" (SPEC-REVIEW 2.1.10), and pipeline-contract's `NodeOutput` KDoc
 * names dag-executor §4 as its source. Re-declaring them would create two structurally identical
 * type sets that every dispatch site would have to convert between — with no compiler help when
 * they drift. So this class composes the contract types; only [ExecutableNode] itself is new.
 * (Reported to the orchestrator as a spec/implementation note, not a behaviour change.)
 *
 * Conversion from `Pipeline.nodes` is mechanical ([from]); the pipeline is already validated at
 * save time (`PipelineValidator`), so this does not re-run validation — it resolves.
 */
data class ExecutableNode(
    val id: String,
    val description: String,
    val type: NodeType,
    val source: NodeSource,
    val template: TemplateRef,
    /** DQL: always non-null (an omitted block deserialized to [NodeOutput.Caller] — §4.1). */
    val output: NodeOutput?,
    val dependsOn: Set<String>,
) {
    /** True when this node's ResultSet is the pipeline's result (§4.1). */
    val isCallerNode: Boolean get() = output == NodeOutput.Caller

    companion object {
        /** Projects one wire [Node] onto its executable form. */
        fun from(node: Node): ExecutableNode =
            ExecutableNode(
                id = node.id,
                description = node.description,
                type = node.type,
                source = node.resolvedSource,
                template = node.template,
                output = node.output,
                dependsOn = node.dependsOn.toSet(),
            )
    }
}

/**
 * A pipeline resolved into the shape the executor walks: the DAG plus the caller node id (§5.1
 * steps 5–7).
 *
 * [callerNodeId] is **nullable and that is ordinary** — a pure write-back/ETL pipeline has no
 * caller node, emits no `data_ready`, and is not an error (§4.1, D1). Nothing here inspects
 * topology to find it: it is the node whose `output` resolves to `caller`, resolved by
 * `CallerNodeResolver`, which throws `pipeline.validation.multiple_caller_nodes` if an
 * unvalidated pipeline reaches the executor with two.
 */
class ExecutablePipeline private constructor(
    val dag: Dag<ExecutableNode>,
    val callerNodeId: String?,
) {
    companion object {
        /**
         * Builds the DAG and resolves the caller node.
         *
         * The cycle check §5.1 step 6 asks for is done by `DagBuilder.build()` itself, so an
         * acyclic graph is a construction invariant rather than a check the executor could
         * forget to run.
         */
        fun from(pipeline: Pipeline): ExecutablePipeline {
            val nodes = pipeline.nodes.map(ExecutableNode::from)
            val dag =
                Dag.build<ExecutableNode> {
                    nodes.forEach { addNode(it.id, it) }
                    nodes.forEach { node -> node.dependsOn.forEach { addDependency(node.id, it) } }
                }
            return ExecutablePipeline(dag, CallerNodeResolver.resolve(pipeline)?.id)
        }
    }
}
