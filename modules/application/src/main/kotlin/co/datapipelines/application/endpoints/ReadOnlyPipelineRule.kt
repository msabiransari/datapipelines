package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.OutputTarget
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.pipeline.ValidationResult
import java.util.UUID

/**
 * "Is this pipeline safe to expose as a `GET`?" (published-endpoints design §4.2/§5.1).
 *
 * ## Why this rule is what makes GET safe at all
 *
 * `GET` is defined to be safe and idempotent, and the whole endpoint surface leans on that: it is
 * unauthenticated-cacheable-looking, it is what a browser preloads, it is what a crawler follows,
 * and it is what a client retries on a timeout. A pipeline with a `DML` node behind such a URL
 * would insert rows every time any of those happened. So the rule is not a lint — it is the
 * precondition for the feature existing, which is why §7 names POST endpoints (for side-effecting
 * pipelines) as separate and out of scope.
 *
 * ## The rule
 *
 * Every node of the version must be:
 *  - a `DQL` node whose `output.target` is `tempdb` or `caller` — it reads, and it writes only
 *    into the execution's own scratch space or back to the caller; or
 *  - a `DDL`/`DML` node whose `source` is `tempdb` — it writes, but only into the execution's
 *    own in-memory H2, created for the run and discarded with it (staging.md §3), so the
 *    statement cannot outlive the request and has no effect outside it (#171); or
 *  - a `PIPELINE` node whose pinned child satisfies the same rule, transitively; or
 *  - any other type in [READ_ONLY_NODE_TYPES] (see below).
 *
 * A `DQL` node with `output.target: datasource` is a WRITE — the write-back form of pipeline-
 * contract §4.3 — and is refused despite its type, which is exactly the case a type-only check
 * would wave through. Symmetrically, a `DDL`/`DML` node whose `source` names a registered
 * datasource is a real, durable write outside the run and stays refused — `tempdb` is the only
 * exemption, named in the refusal message so an author can tell the two cases apart.
 *
 * ## One constant, so a new UNCONDITIONALLY safe node type is one line
 *
 * [READ_ONLY_NODE_TYPES] is the set of types that are safe no matter what `source` they carry.
 * `NodeType.CALCULATOR` (072) is in it: a pure in-memory computation over the context, no
 * datasource at all, so it has no side effect to be safe from. It was admitted at the 074 merge
 * (2026-09-05) — the one-line addition this design anticipated while the two rounds were in
 * flight together. `DDL`/`DML` are deliberately absent from this set — they are safe only
 * *conditionally*, by `source`, so [judge] checks them before consulting this constant at all.
 *
 * ## The walk
 *
 * Iterative with an explicit stack and a depth bound, copying [co.datapipelines.pipeline.CompositionRules]'
 * shape rather than inventing a second one: a deep reference tree must not exhaust the JVM stack
 * (§12.2's crash-safety rule), and a shared child is re-walked only when a strictly longer path
 * reaches it.
 *
 * A `PIPELINE` node whose child cannot be resolved is refused rather than skipped. Composition
 * validation already guarantees a pinned child exists at save time, so an unresolvable one here
 * means the registry is inconsistent — and "I could not read it" must never be reported as "it is
 * safe". The same reasoning covers exceeding the depth bound.
 *
 * ## Exhaustive, not fail-fast
 *
 * Every offending node is reported, the way every other validator in this codebase reports
 * (§17.2). An author fixing a pipeline wants the list, not the first item of it.
 */
class ReadOnlyPipelineRule(
    private val pipelines: PipelineResolver,
    private val maxCompositionDepth: Int,
) {
    /**
     * The verdict for [pipeline] as the released version of an endpoint in [workspaceId].
     *
     * Returns [ValidationResult.VALID] when every node, transitively, is side-effect-free.
     */
    fun check(
        pipeline: Pipeline,
        workspaceId: UUID,
    ): ValidationResult {
        val failures = mutableListOf<ValidationFailure>()
        val expandedAt = HashMap<PipelineNodeRef, Int>()
        val stack = ArrayDeque<Walked>()
        stack.addLast(Walked(pipeline, depth = 1, trail = emptyList()))

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (node in current.pipeline.nodes) {
                if (node.type == NodeType.PIPELINE) {
                    expand(node, current, workspaceId, expandedAt, failures)?.let(stack::addLast)
                } else {
                    judge(node, current.trail)?.let(failures::add)
                }
            }
        }
        return ValidationResult(failures)
    }

    /**
     * The verdict on one non-`PIPELINE` node, or null when it is safe.
     *
     * Split from [check] so the two questions the walk asks — "is this leaf safe?" and "where
     * does this container lead?" — are each answerable on their own.
     */
    private fun judge(
        node: Node,
        trail: List<String>,
    ): ValidationFailure? =
        when {
            node.type == NodeType.DDL || node.type == NodeType.DML -> {
                judgeStatementSource(node, trail)
            }

            node.type !in READ_ONLY_NODE_TYPES -> {
                offending(node, trail, "is a ${node.type.wire} node, which writes")
            }

            node.type == NodeType.DQL && node.output?.target !in READ_ONLY_TARGETS -> {
                offending(
                    node,
                    trail,
                    "is a DQL node writing back to a datasource (output.target: ${node.output?.target?.wire}), which is a write",
                )
            }

            else -> {
                null
            }
        }

    /**
     * The verdict on a `DDL`/`DML` node, by its `source` (#171).
     *
     * `tempdb` is the execution's own in-memory H2, created for the run and discarded with it
     * (staging.md §3) — a statement against it cannot outlive the request, so it cannot make a
     * `GET` unsafe. Any registered datasource is a real, durable write and stays refused, named
     * in the message so the author can tell this case apart from an always-refused node type.
     */
    private fun judgeStatementSource(
        node: Node,
        trail: List<String>,
    ): ValidationFailure? =
        when (val source = node.resolvedSource) {
            is NodeSource.Tempdb -> null
            is NodeSource.Datasource -> {
                offending(
                    node,
                    trail,
                    "is a ${node.type.wire} node that writes to datasource '${source.name}', which is a write outside the run",
                )
            }
        }

    /**
     * The child frame a `PIPELINE` node adds to the walk, or null — either because it was already
     * expanded at least this deep, or because it could not be expanded at all, in which case a
     * failure has been recorded.
     *
     * Every "cannot expand" branch RECORDS rather than skips: "I could not read it" must never be
     * reported as "it is safe".
     */
    @Suppress("ReturnCount") // each "cannot expand" reason is its own refusal, and each names itself
    private fun expand(
        node: Node,
        current: Walked,
        workspaceId: UUID,
        expandedAt: MutableMap<PipelineNodeRef, Int>,
        failures: MutableList<ValidationFailure>,
    ): Walked? {
        val childDepth = current.depth + 1
        val ref = node.pipeline
        if (ref == null) {
            failures += unreadable(node, current.trail, "carries no pipeline reference")
            return null
        }
        if (childDepth > maxCompositionDepth) {
            failures += unreadable(node, current.trail, "is deeper than the composition limit of $maxCompositionDepth")
            return null
        }
        val child = pipelines.resolve(workspaceId, ref.name, ref.version)?.pipeline
        if (child == null) {
            failures += unreadable(node, current.trail, "pins '${ref.name}' v${ref.version}, which is not in the registry")
            return null
        }
        // A shared child is re-walked only when a strictly longer path reaches it, which with the
        // depth bound caps the whole walk at `references × maxDepth` resolutions.
        if ((expandedAt[ref] ?: 0) >= childDepth) return null
        expandedAt[ref] = childDepth
        return Walked(child, childDepth, current.trail + node.id)
    }

    private fun offending(
        node: Node,
        trail: List<String>,
        why: String,
    ): ValidationFailure = failure(node, trail, "Node '${node.id}' $why; a published endpoint serves GET, which must be side-effect-free.")

    private fun unreadable(
        node: Node,
        trail: List<String>,
        why: String,
    ): ValidationFailure =
        failure(
            node,
            trail,
            "Child pipeline of node '${node.id}' $why, so it cannot be shown to be side-effect-free. " +
                "An endpoint is refused rather than published on an unverifiable body.",
        )

    private fun failure(
        node: Node,
        trail: List<String>,
        message: String,
    ): ValidationFailure =
        ValidationFailure(
            code = PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY,
            path = (trail + node.id).joinToString(" > ", prefix = "nodes."),
            message = message,
            details =
                mapOf(
                    "node_id" to node.id,
                    "node_type" to node.type.wire,
                    // The chain of PIPELINE nodes that reached it — without this, "node_id: load"
                    // sends an author looking in the wrong pipeline for a node three levels down.
                    "via" to trail,
                ),
        )

    private data class Walked(
        val pipeline: Pipeline,
        val depth: Int,
        val trail: List<String>,
    )

    companion object {
        /**
         * The node types a published endpoint may contain UNCONDITIONALLY — safe no matter what
         * `source` they carry. `PIPELINE` is here because it is a container, not because it is
         * inherently safe — its child is walked and judged by this same rule. `DDL`/`DML` are
         * deliberately NOT here (#171): they are safe only when `source` is `tempdb`, which
         * [judgeStatementSource] checks before this set is ever consulted.
         *
         * **Adding a type is one line.** `NodeType.CALCULATOR` (round 072) is here because it
         * computes over the execution context in memory and touches no datasource, so it cannot
         * have a side effect to be safe from.
         */
        val READ_ONLY_NODE_TYPES: Set<NodeType> = setOf(NodeType.DQL, NodeType.PIPELINE, NodeType.CALCULATOR)

        /**
         * Where a `DQL` node's rows may go. `datasource` is absent on purpose: that is
         * pipeline-contract §4.3's write-back form, a genuine write wearing a DQL type.
         */
        val READ_ONLY_TARGETS: Set<OutputTarget> = setOf(OutputTarget.TEMPDB, OutputTarget.CALLER)
    }
}
