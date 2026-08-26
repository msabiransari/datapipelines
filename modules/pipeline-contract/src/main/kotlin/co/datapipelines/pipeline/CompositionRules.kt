package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import com.fasterxml.jackson.databind.JsonNode

/**
 * pipeline-contract §12.9 — the composition validations for PIPELINE nodes.
 *
 * These are the §12 rules that need the **pipeline registry**: whether the pinned child exists,
 * whether its declared parameters match what the node supplies, whether it has a caller node,
 * and how deep the reference tree rooted here descends. Everything is computed against the
 * pinned (immutable) child bodies the [PipelineResolver] returns, so the verdicts are stable:
 * a pipeline that passed at save time cannot become invalid because the child later changed —
 * an edit to the child is a new version, and the pin still points at the old one (D5).
 *
 * ## Missing references mirror missing templates
 *
 * An absent or blank `pipeline` ref reports `pipeline_not_found`, exactly as an absent
 * `template` ref reports `template_not_found`: [Node.fromJson] binds the missing block leniently
 * so §17.2's "all failures together" holds, and the catalog carries no separate
 * "field is required" code.
 *
 * ## Distinguishing `pipeline_not_found` from `pipeline_version_not_found`
 *
 * [PipelineResolver.resolve] answers null for both, so on a null answer the name is probed at
 * version 1: every registered pipeline has a version 1 (create assigns it; versions are
 * immutable and never individually deleted — soft-delete is pipeline-level), so a non-null
 * answer there means the NAME is registered and the pinned VERSION is what is missing. The
 * probe runs only on the failure path, never for a resolvable reference.
 *
 * ## Iterative depth walk, and the bound is the backstop
 *
 * [referenceDepth] computes the static reference-tree depth with an explicit stack, never
 * recursion in graph depth (§12.2 crash-safety). Cycles are impossible by construction — a pin
 * can only point at an already-stored immutable version — but the walk does not rely on that:
 * it never descends past `maxDepth`, so a resolver that handed back a cyclic graph terminates
 * here too, reported as `composition_too_deep` (design §4.4).
 */
internal object CompositionRules {
    fun check(
        pipeline: Pipeline,
        pipelines: PipelineResolver,
        maxDepth: Int,
        workspaceId: java.util.UUID,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.PIPELINE) checkNode(pipeline, index, node, pipelines, workspaceId, into)
        }
        checkDepth(pipeline, pipelines, maxDepth, workspaceId, into)
    }

    private fun checkNode(
        pipeline: Pipeline,
        index: Int,
        node: Node,
        pipelines: PipelineResolver,
        workspaceId: java.util.UUID,
        into: FailureCollector,
    ) {
        checkNodeShape(index, node, into)
        val ref = node.pipeline
        if (ref == null || ref.name.isBlank()) {
            into.add(
                Validation.PIPELINE_NOT_FOUND,
                "nodes[$index].pipeline",
                "PIPELINE node '${node.id.truncateForError()}' must declare a pipeline reference {name, version}.",
                mapOf("node" to node.id.truncateForError()),
            )
            return
        }
        if (ref.name == pipeline.name) {
            into.add(
                Validation.PIPELINE_SELF_REFERENCE,
                "nodes[$index].pipeline.name",
                "PIPELINE node '${node.id.truncateForError()}' references '${ref.name.truncateForError()}', " +
                    "the pipeline that contains it; a pipeline may not invoke itself.",
                mapOf("node" to node.id.truncateForError(), "pipeline" to ref.name.truncateForError()),
            )
        }
        val resolved = resolve(ref, pipelines, workspaceId, index, into) ?: return
        if (resolved.deleted) {
            into.add(
                Validation.PIPELINE_REFERENCE_DELETED,
                "nodes[$index].pipeline",
                "Pipeline '${ref.name.truncateForError()}' is deleted; existing pinned references still resolve, " +
                    "but a deleted pipeline cannot be referenced by a new save.",
                mapOf("node" to node.id.truncateForError(), "pipeline" to ref.name.truncateForError()),
            )
        }
        checkParameters(pipeline, index, node, resolved.pipeline, into)
        checkOutput(index, node, resolved.pipeline, into)
    }

    /**
     * §12.9 `pipeline_node_has_source` / `pipeline_node_has_template` — a PIPELINE node runs no
     * SQL of its own, so the two SQL-node fields are forbidden (mirrors "output forbidden on
     * DML/DDL", §12.4). A blank value and an absent key are the same thing here, as they are for
     * the §12.4 companion fields: [Node.fromJson] binds both leniently precisely so this check —
     * not a Jackson exception — is what the author sees.
     */
    private fun checkNodeShape(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        if (node.source.isNotBlank()) {
            into.add(
                Validation.PIPELINE_NODE_HAS_SOURCE,
                "nodes[$index].source",
                "PIPELINE node '${node.id.truncateForError()}' declares a source " +
                    "('${node.source.truncateForError()}'); a PIPELINE node runs a child pipeline, not SQL.",
                mapOf("node" to node.id.truncateForError(), "source" to node.source.truncateForError()),
            )
        }
        if (node.template.id.isNotBlank()) {
            into.add(
                Validation.PIPELINE_NODE_HAS_TEMPLATE,
                "nodes[$index].template",
                "PIPELINE node '${node.id.truncateForError()}' declares a template " +
                    "('${node.template.key.truncateForError()}'); a PIPELINE node runs a child pipeline, not SQL.",
                mapOf("node" to node.id.truncateForError(), "template" to node.template.key.truncateForError()),
            )
        }
    }

    /**
     * Resolves the pinned reference, reporting `pipeline_not_found` /
     * `pipeline_version_not_found` and returning null when it does not resolve. See the class
     * KDoc for the version-1 probe that tells the two codes apart.
     */
    private fun resolve(
        ref: PipelineNodeRef,
        pipelines: PipelineResolver,
        workspaceId: java.util.UUID,
        index: Int,
        into: FailureCollector,
    ): ResolvedPipeline? {
        pipelines.resolve(workspaceId, ref.name, ref.version)?.let { return it }
        val nameKnown = ref.version != 1 && pipelines.resolve(workspaceId, ref.name, 1) != null
        if (nameKnown) {
            into.add(
                Validation.PIPELINE_VERSION_NOT_FOUND,
                "nodes[$index].pipeline",
                "Pipeline '${ref.name.truncateForError()}' has no version ${ref.version}.",
                mapOf("pipeline" to ref.name.truncateForError(), "version" to ref.version),
            )
        } else {
            into.add(
                Validation.PIPELINE_NOT_FOUND,
                "nodes[$index].pipeline",
                "Pipeline '${ref.name.truncateForError()}' is not in the pipeline registry.",
                mapOf("pipeline" to ref.name.truncateForError()),
            )
        }
        return null
    }

    /**
     * §12.9's parameter-mapping rules against the child's declared `parameters`.
     *
     * A supplied value is either a typed literal obeying the child parameter's §6.3 wire
     * encoding — checked by [ParameterCoercion], the same code path §12.7's
     * `default_type_mismatch` and execution-time coercion use — or the string form
     * `${parent_param}` naming one of the PARENT's declared parameters of the identical
     * declared type. No expressions, no concatenation (design §3, v1).
     */
    private fun checkParameters(
        pipeline: Pipeline,
        index: Int,
        node: Node,
        child: Pipeline,
        into: FailureCollector,
    ) {
        val supplied = node.parameters.orEmpty()
        child.parameters
            .filterValues { it.required && !it.hasDefault }
            .keys
            .filter { it !in supplied.keys }
            .forEach { name ->
                into.add(
                    Validation.PIPELINE_PARAMETER_UNMAPPED,
                    "nodes[$index].parameters",
                    "Child parameter '${name.truncateForError()}' is required and declares no default, " +
                        "but node '${node.id.truncateForError()}' does not supply it.",
                    mapOf("node" to node.id.truncateForError(), "parameter" to name.truncateForError()),
                )
            }
        supplied.forEach { (key, value) ->
            val declared = child.parameters[key]
            if (declared == null) {
                into.add(
                    Validation.PIPELINE_PARAMETER_UNKNOWN,
                    "nodes[$index].parameters.${key.truncateForError()}",
                    "Node '${node.id.truncateForError()}' supplies '${key.truncateForError()}', " +
                        "which the child pipeline does not declare.",
                    mapOf("node" to node.id.truncateForError(), "parameter" to key.truncateForError()),
                )
            } else {
                checkValue(pipeline, index, node, key, value, declared, into)
            }
        }
    }

    private fun checkValue(
        pipeline: Pipeline,
        index: Int,
        node: Node,
        key: String,
        value: JsonNode,
        declared: Parameter,
        into: FailureCollector,
    ) {
        val reference = value.takeIf { it.isTextual }?.asText()?.let { PARAMETER_REFERENCE.matchEntire(it) }
        val path = "nodes[$index].parameters.${key.truncateForError()}"
        if (reference != null) {
            val parentName = reference.groupValues[1]
            val parent = pipeline.parameters[parentName]
            if (parent == null || parent.type != declared.type) {
                into.add(
                    Validation.PIPELINE_PARAMETER_TYPE_MISMATCH,
                    path,
                    "Node '${node.id.truncateForError()}' maps '\${$parentName}' onto child parameter " +
                        "'${key.truncateForError()}' (${declared.type.wire}), but the parent " +
                        (parent?.let { "declares it as ${it.type.wire}" } ?: "declares no such parameter") +
                        "; a reference must name a parent parameter of the identical type.",
                    mapOf(
                        "node" to node.id.truncateForError(),
                        "parameter" to key.truncateForError(),
                        "reference" to parentName,
                    ),
                )
            }
            return
        }
        val outcome = ParameterCoercion.coerce(declared.type, value)
        if (outcome is ParameterCoercion.Outcome.Rejected) {
            into.add(
                Validation.PIPELINE_PARAMETER_TYPE_MISMATCH,
                path,
                "Value for child parameter '${key.truncateForError()}' does not match its declared type: ${outcome.reason}.",
                mapOf("node" to node.id.truncateForError(), "parameter" to key.truncateForError(), "type" to declared.type.wire),
            )
        }
    }

    /**
     * §12.9 `pipeline_output_on_sideeffect_child`: the `output` block is permitted only when the
     * pinned child has a caller node — a zero-caller child produces no result to land, so the
     * node is side-effect-only and downstream `depends_on` gives ordering. The block itself is a
     * standard §4.7 block, so its companion fields get the DQL checks.
     */
    private fun checkOutput(
        index: Int,
        node: Node,
        child: Pipeline,
        into: FailureCollector,
    ) {
        val output = node.output ?: return
        NodeTypeRules.checkOutputCompanions(index, node, into)
        if (CallerNodeResolver.resolve(child.nodes) != null) return
        into.add(
            Validation.PIPELINE_OUTPUT_ON_SIDEEFFECT_CHILD,
            "nodes[$index].output",
            "Node '${node.id.truncateForError()}' declares an output block (target '${output.target.wire}'), " +
                "but the pinned child pipeline has no caller node; a zero-caller child is side-effect-only.",
            mapOf("node" to node.id.truncateForError(), "target" to output.target.wire),
        )
    }

    private fun checkDepth(
        pipeline: Pipeline,
        pipelines: PipelineResolver,
        maxDepth: Int,
        workspaceId: java.util.UUID,
        into: FailureCollector,
    ) {
        if (pipeline.nodes.none { it.type == NodeType.PIPELINE }) return
        val depth = referenceDepth(pipeline, pipelines, maxDepth, workspaceId)
        if (depth <= maxDepth) return
        into.add(
            Validation.COMPOSITION_TOO_DEEP,
            "nodes[].pipeline",
            "The reference tree rooted at this pipeline is deeper than the configured maximum " +
                "composition depth of $maxDepth.",
            mapOf("max" to maxDepth),
        )
    }

    /**
     * The longest `parent → child` chain length rooted at [root] (a pipeline with no PIPELINE
     * nodes has depth 1), capped at `maxDepth + 1`: the walk never descends past the bound, so a
     * cyclic resolver answer terminates as surely as a deep one (see the class KDoc).
     *
     * Iterative with an explicit stack — §12.2's crash-safety rule, the same one [DagRules]
     * documents: a deep reference tree must not exhaust the JVM stack. [expandedAt] records the
     * greatest depth a reference was expanded at, so a shared child is re-walked only when a
     * strictly longer path reaches it; with the depth bound that caps the whole walk at
     * `references × maxDepth` resolutions.
     */
    private fun referenceDepth(
        root: Pipeline,
        pipelines: PipelineResolver,
        maxDepth: Int,
        workspaceId: java.util.UUID,
    ): Int {
        var deepest = 1
        val expandedAt = HashMap<PipelineNodeRef, Int>()
        val stack = ArrayDeque<Pair<Pipeline, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (body, depth) = stack.removeLast()
            if (depth > deepest) deepest = depth
            if (depth > maxDepth) continue
            body.nodes.forEach { node ->
                if (node.type != NodeType.PIPELINE) return@forEach
                val ref = node.pipeline ?: return@forEach
                val child = pipelines.resolve(workspaceId, ref.name, ref.version)?.pipeline ?: return@forEach
                if ((expandedAt[ref] ?: 0) >= depth + 1) return@forEach
                expandedAt[ref] = depth + 1
                stack.addLast(child to depth + 1)
            }
        }
        return deepest
    }

    /** §12.9 — the whole `${parent_param}` reference form; a value is a literal or this, nothing in between. */
    private val PARAMETER_REFERENCE = Regex("^\\$\\{([a-z_][a-z0-9_]*)\\}$")
}
