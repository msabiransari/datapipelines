package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
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
        org: OrgContext,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.PIPELINE) checkNode(pipeline, index, node, pipelines, workspaceId, org, into)
        }
        checkDepth(pipeline, pipelines, maxDepth, workspaceId, into)
    }

    private fun checkNode(
        pipeline: Pipeline,
        index: Int,
        node: Node,
        pipelines: PipelineResolver,
        workspaceId: java.util.UUID,
        org: OrgContext,
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
        // 067: a child reference names a pipeline, so it obeys the SAME §3.2 path grammar the
        // referenced pipeline's own name does. Reported here and the probe skipped: a name the
        // grammar refuses cannot be in the registry either, and `pipeline_not_found` would send
        // the author looking for a missing pipeline instead of at a malformed name.
        if (!isValidPipelineName(ref.name)) {
            into.add(
                Validation.NAME_INVALID,
                "nodes[$index].pipeline.name",
                "PIPELINE node '${node.id.truncateForError()}' references '${ref.name.truncateForError()}', which is not a " +
                    "legal pipeline name: a path of 2-10 '/'-separated segments, each [a-z0-9][a-z0-9_.-], " +
                    "at most 64 chars, 200 total.",
                mapOf("node" to node.id.truncateForError(), "pipeline" to ref.name.truncateForError()),
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
        if (resolved.entityDiscarded) {
            into.add(
                Validation.PIPELINE_REFERENCE_DELETED,
                "nodes[$index].pipeline",
                "Pipeline '${ref.name.truncateForError()}' is discarded (every version discarded); existing pinned " +
                    "references still resolve, but a discarded pipeline cannot be referenced by a new save.",
                mapOf("node" to node.id.truncateForError(), "pipeline" to ref.name.truncateForError()),
            )
        }
        // D58 (101): composition references reviewed content only. A DRAFT child can be purged
        // out from under its parent, and a DISCARDED child is retired — an exact-version pin
        // must never name either.
        if (resolved.versionStatus != PipelineVersionStatus.RELEASED) {
            into.add(
                Validation.PIPELINE_REFERENCE_NOT_RELEASED,
                "nodes[$index].pipeline.version",
                "PIPELINE node '${node.id.truncateForError()}' pins '${ref.name.truncateForError()}' version " +
                    "${ref.version}, which is ${resolved.versionStatus.name}; a child pipeline pin must name a " +
                    "RELEASED version.",
                mapOf(
                    "node" to node.id.truncateForError(),
                    "pipeline" to ref.name.truncateForError(),
                    "pipeline_version" to ref.version,
                    "status" to resolved.versionStatus.name,
                ),
            )
        }
        checkParameters(pipeline, index, node, resolved.pipeline, org, into)
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
     * §12.9's parameter-mapping rules against the child's declared `parameters` **and** its
     * calculator output keys (078 A5-composition, owner ruling 2026-09-05).
     *
     * A supplied key names either a child declared parameter or one of the pinned child's
     * CALCULATOR `context_key`s (`calculatorOutputs()` — a key supplied this way skips the
     * child's node exactly as a direct execute-time supply does). Anything else is
     * `pipeline_parameter_unknown`. A child calculator key is OPTIONAL, never required, so
     * `pipeline_parameter_unmapped` keeps reading only declared parameters.
     *
     * A supplied value is either a typed literal obeying the target's §6.3 wire encoding —
     * checked by [ParameterCoercion], the same code path §12.7's `default_type_mismatch` and
     * execution-time coercion use — or the string form `${ref}` resolving against the PARENT's
     * Context tiers ([checkValue]). No expressions, no concatenation (design §3, v1), and NO
     * AUTO-PASSTHROUGH: a parent and child calculator key spelled the same are not implicitly
     * mapped — the mapping is always this explicit entry, or the child's node computes its own.
     */
    private fun checkParameters(
        pipeline: Pipeline,
        index: Int,
        node: Node,
        child: Pipeline,
        org: OrgContext,
        into: FailureCollector,
    ) {
        val supplied = node.parameters.orEmpty()
        val childCalculatorKeys = child.calculatorOutputs()
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
            when {
                declared != null -> {
                    checkValue(pipeline, index, node, key, value, Target.parameter(declared.type), org, into)
                }

                key in childCalculatorKeys -> {
                    checkValue(
                        pipeline,
                        index,
                        node,
                        key,
                        value,
                        Target.calculatorOutput(childCalculatorKeys.getValue(key)),
                        org,
                        into,
                    )
                }

                else -> {
                    into.add(
                        Validation.PIPELINE_PARAMETER_UNKNOWN,
                        "nodes[$index].parameters.${key.truncateForError()}",
                        "Node '${node.id.truncateForError()}' supplies '${key.truncateForError()}', " +
                            "which the child pipeline neither declares as a parameter nor computes as a " +
                            "calculator context_key.",
                        mapOf("node" to node.id.truncateForError(), "parameter" to key.truncateForError()),
                    )
                }
            }
        }
    }

    /**
     * The mapping target on the child side: a declared parameter or a CALCULATOR `context_key`
     * (078 A5-composition). [type] is null for an ANY-output kind — the value is typed only by
     * the run, so the save-time check skips rather than guesses (the A6 convention).
     */
    private class Target private constructor(
        val type: LogicalType?,
        val description: String,
    ) {
        companion object {
            fun parameter(type: LogicalType): Target = Target(type, "child parameter")

            fun calculatorOutput(type: LogicalType?): Target = Target(type, "child calculator output")
        }
    }

    /**
     * The `${ref}` resolution against the PARENT's Context tiers (078 A5-composition), in
     * [Pipeline]'s precedence: a declared parameter, then a CALCULATOR `context_key` (type via
     * `calculatorOutputs()`), then the deployment tiers — a platform key typed by
     * [ContextKeys.PLATFORM_TYPES], an org key always STRING (§0.2). A parameter/calculator-key
     * collision is §12.10's refusal, so the order between the first two can never mask one; a
     * declared parameter shadows an org key at run (tier 3 over tier 1), as it does here.
     *
     * [type] is null when the tier pins none — an ANY-output parent calculator key, typed only
     * by the run — and the type check then skips rather than guesses (the A6 convention).
     */
    private class ParentTier private constructor(
        val type: LogicalType?,
        val description: String,
    ) {
        companion object {
            fun resolve(
                name: String,
                pipeline: Pipeline,
                calculatorOutputs: Map<String, LogicalType?>,
                org: OrgContext,
            ): ParentTier? =
                when {
                    name in pipeline.parameters -> {
                        ParentTier(pipeline.parameters.getValue(name).type, "parent parameter '$name'")
                    }

                    name in calculatorOutputs -> {
                        ParentTier(calculatorOutputs.getValue(name), "parent calculator output '$name'")
                    }

                    name in ContextKeys.PLATFORM_TYPES -> {
                        ParentTier(ContextKeys.PLATFORM_TYPES.getValue(name), "platform key '$name'")
                    }

                    name in org.keys -> {
                        ParentTier(LogicalType.STRING, "org key '$name'")
                    }

                    else -> {
                        null
                    }
                }
        }
    }

    @Suppress("LongParameterList")
    private fun checkValue(
        pipeline: Pipeline,
        index: Int,
        node: Node,
        key: String,
        value: JsonNode,
        target: Target,
        org: OrgContext,
        into: FailureCollector,
    ) {
        val reference = value.takeIf { it.isTextual }?.asText()?.let { PARAMETER_REFERENCE.matchEntire(it) }
        val path = "nodes[$index].parameters.${key.truncateForError()}"
        if (reference != null) {
            val parentName = reference.groupValues[1]
            val tier = ParentTier.resolve(parentName, pipeline, pipeline.calculatorOutputs(), org)
            if (mismatched(tier, target)) {
                into.add(
                    Validation.PIPELINE_PARAMETER_TYPE_MISMATCH,
                    path,
                    "Node '${node.id.truncateForError()}' maps '\${$parentName}' onto ${target.description} " +
                        "'${key.truncateForError()}'" +
                        (target.type?.let { " (${it.wire})" } ?: " (ANY)") +
                        ", but " +
                        (
                            tier?.let { "${it.description} is ${it.type?.wire}" }
                                ?: "'\${$parentName}' names no parent parameter, parent calculator output, " +
                                "or org/platform key"
                        ) +
                        "; a reference must resolve to a value of the identical type.",
                    mapOf(
                        "node" to node.id.truncateForError(),
                        "parameter" to key.truncateForError(),
                        "reference" to parentName,
                    ),
                )
            }
            return
        }
        // A literal against an ANY-output child target takes any JSON scalar — the same reading
        // the execute-time binder gives an ANY-output key (078 A5).
        val targetType = target.type ?: return
        val outcome = ParameterCoercion.coerce(targetType, value)
        if (outcome is ParameterCoercion.Outcome.Rejected) {
            into.add(
                Validation.PIPELINE_PARAMETER_TYPE_MISMATCH,
                path,
                "Value for ${target.description} '${key.truncateForError()}' does not match its declared type: ${outcome.reason}.",
                mapOf("node" to node.id.truncateForError(), "parameter" to key.truncateForError(), "type" to targetType.wire),
            )
        }
    }

    /**
     * Whether the reference is a failure: unresolved, or resolved to a tier whose type disagrees
     * with the target's. Either side unknowable — an ANY-output parent key or an ANY-output
     * child target — SKIPS the check: the value is typed only by the run (078 A6's convention).
     */
    private fun mismatched(
        tier: ParentTier?,
        target: Target,
    ): Boolean {
        if (tier == null) return true
        val tierType = tier.type ?: return false
        val targetType = target.type ?: return false
        return tierType != targetType
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

    /** §12.9 — the whole `${ref}` reference form; a value is a literal or this, nothing in between. */
    private val PARAMETER_REFERENCE = Regex("^\\$\\{([a-z_][a-z0-9_]*)\\}$")
}
