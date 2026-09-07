package co.datapipelines.pipeline

import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * pipeline-contract §12.10 — the `CALCULATOR`-node rules (§4.10, calculators design §0.3).
 *
 * Every verdict here is decided from the body plus two deployment constants (the kind registry
 * and the Context's org/platform tiers), so an author gets all of them at save time. That is the
 * whole design argument for calculators being NODES: sequencing, naming and typing become
 * questions the validator can answer, where a runtime-only feature would answer them at 3am.
 *
 * ## Ordering is the rule worth reading twice
 *
 * A `$reference` to another node's `context_key` — and a SQL node binding `:that_key` — is legal
 * only from a node that `depends_on` the writer, directly or transitively. Without the edge the
 * reader's value depends on which node the scheduler reached first, which is not a bug that shows
 * up in testing: with four parallel slots and two nodes, it is right most of the time. The
 * refusal is `calculator_input_unordered`, and the fix an author makes is one array entry.
 *
 * ## Typing reaches through `$references`, not just literals
 *
 * A reference whose type the body decides is checked against the input's declared type exactly as
 * a literal is: platform keys from [ContextKeys.PLATFORM_TYPES], org keys always STRING (§0.2), a
 * declared parameter its own type, another calculator's `context_key` the writer kind's output.
 * Either side may be unknowable — an `ANY` input (coalesce, if_null, map) takes everything, and
 * an `ANY`-output writer's value is typed only by the run — and then the check skips rather than
 * guesses. Without this, `$current_timestamp` into a DATE input passed save and failed at 3am.
 */
internal object CalculatorRules {
    @Suppress("LongParameterList")
    fun check(
        pipeline: Pipeline,
        orgContext: OrgContext,
        templates: TemplateDryRenderer,
        workspaceId: UUID,
        into: FailureCollector,
    ) {
        val writers = pipeline.nodes.filter { it.type == NodeType.CALCULATOR }
        // FIRST writer wins the key, so a duplicate is reported once — on the node that came
        // second — and names the one that already owns it. Keeping the last writer instead would
        // report the collision on the FIRST node and name the second, which reads backwards.
        val keyToWriter =
            writers
                .mapNotNull { node -> node.contextKey?.takeIf { it.isNotBlank() }?.let { it to node.id } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.first() }
        val ancestors = Ancestry.of(pipeline, keyToWriter.values)
        val deploymentKeys = orgContext.keys + ContextKeys.PLATFORM

        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.CALCULATOR) {
                checkCalculatorNode(index, node, pipeline, deploymentKeys, keyToWriter, ancestors, into)
            } else {
                checkForeignFields(index, node, into)
                checkSqlBindOrdering(index, node, templates, workspaceId, keyToWriter, ancestors, into)
            }
        }
    }

    // ---- shape ----

    private fun checkForeignFields(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        val present =
            buildList {
                if (node.kind != null) add("kind")
                if (node.inputs != null) add("inputs")
                if (node.contextKey != null) add("context_key")
            }
        if (present.isEmpty()) return
        into.add(
            Validation.CALCULATOR_FIELDS_ON_NON_CALCULATOR,
            "nodes[$index]",
            "${node.type.wire} node '${node.id.truncateForError()}' declares ${present.joinToString()}, " +
                "which only a CALCULATOR node carries.",
            mapOf("node" to node.id.truncateForError(), "fields" to present),
        )
    }

    @Suppress("LongParameterList")
    private fun checkCalculatorNode(
        index: Int,
        node: Node,
        pipeline: Pipeline,
        deploymentKeys: Set<String>,
        keyToWriter: Map<String, String>,
        ancestors: Ancestry,
        into: FailureCollector,
    ) {
        checkNoSqlFields(index, node, into)
        val kind = checkKind(index, node, into)
        checkContextKey(index, node, pipeline, keyToWriter, into)
        if (kind == null) return
        checkInputs(index, node, kind, pipeline, deploymentKeys, keyToWriter, ancestors, into)
    }

    private fun checkNoSqlFields(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        val present =
            buildList {
                if (node.source.isNotBlank()) add("source")
                if (node.template.id.isNotBlank() || node.template.version != 0) add("template")
                if (node.output != null) add("output")
            }
        if (present.isEmpty()) return
        into.add(
            Validation.CALCULATOR_NODE_HAS_SQL_FIELDS,
            "nodes[$index]",
            "CALCULATOR node '${node.id.truncateForError()}' declares ${present.joinToString()}; it runs no SQL " +
                "and writes one Context key, so it carries none of them.",
            mapOf("node" to node.id.truncateForError(), "fields" to present),
        )
    }

    private fun checkKind(
        index: Int,
        node: Node,
        into: FailureCollector,
    ): CalculatorKind? {
        val kind = node.kind
        if (kind.isNullOrBlank() || node.inputs == null || node.contextKey.isNullOrBlank()) {
            val missing =
                buildList {
                    if (kind.isNullOrBlank()) add("kind")
                    if (node.inputs == null) add("inputs")
                    if (node.contextKey.isNullOrBlank()) add("context_key")
                }
            into.add(
                Validation.CALCULATOR_NODE_INCOMPLETE,
                "nodes[$index]",
                "CALCULATOR node '${node.id.truncateForError()}' is missing ${missing.joinToString()}; a calculator " +
                    "node declares all three.",
                mapOf("node" to node.id.truncateForError(), "missing" to missing),
            )
            if (kind.isNullOrBlank()) return null
        }
        return CalculatorRegistry.find(kind) ?: run {
            into.add(
                Validation.CALCULATOR_UNKNOWN,
                "nodes[$index].kind",
                "No calculator kind named '${kind.truncateForError()}'. The catalog is docs/calculators.md; " +
                    "`calculators_list` returns it with typed schemas.",
                mapOf("node" to node.id.truncateForError(), "kind" to kind.truncateForError()),
            )
            null
        }
    }

    // ---- the output key ----

    private fun checkContextKey(
        index: Int,
        node: Node,
        pipeline: Pipeline,
        keyToWriter: Map<String, String>,
        into: FailureCollector,
    ) {
        val key = node.contextKey?.takeUnless { it.isBlank() } ?: return
        if (!ContextKeys.NAME.matches(key)) {
            into.add(
                Validation.CALCULATOR_OUTPUT_NAME_INVALID,
                "nodes[$index].context_key",
                "context_key '${key.truncateForError()}' must match ${ContextKeys.NAME.pattern} (§6.1) — " +
                    "it is bound in SQL as :$key.",
                mapOf("node" to node.id.truncateForError(), "context_key" to key.truncateForError()),
            )
            return
        }
        // A calculator MAY shadow an org or platform key (§0.2 tier 5). It may never shadow a
        // declared PARAMETER: that is the caller's input, and silently overwriting one makes an
        // execute request a lie about what ran.
        if (pipeline.parameters.containsKey(key)) {
            collision(index, node, key, "a declared parameter of the same name", "parameter", into)
            return
        }
        val other = keyToWriter[key]
        if (other != null && other != node.id) {
            collision(index, node, key, "node '${other.truncateForError()}'", "node", into)
        }
    }

    @Suppress("LongParameterList")
    private fun collision(
        index: Int,
        node: Node,
        key: String,
        what: String,
        kind: String,
        into: FailureCollector,
    ) = into.add(
        Validation.CALCULATOR_OUTPUT_COLLISION,
        "nodes[$index].context_key",
        "context_key '${key.truncateForError()}' is already written by $what — one writer per Context key.",
        mapOf("node" to node.id.truncateForError(), "context_key" to key.truncateForError(), "collides_with" to kind),
    )

    // ---- inputs ----

    @Suppress("LongParameterList")
    private fun checkInputs(
        index: Int,
        node: Node,
        kind: CalculatorKind,
        pipeline: Pipeline,
        deploymentKeys: Set<String>,
        keyToWriter: Map<String, String>,
        ancestors: Ancestry,
        into: FailureCollector,
    ) {
        val supplied = node.inputs.orEmpty()
        val declared = kind.inputs.associateBy { it.name }

        kind.inputs.filter { it.required && it.name !in supplied }.forEach { input ->
            into.add(
                Validation.CALCULATOR_INPUT_MISSING,
                "nodes[$index].inputs",
                "Kind '${kind.kind}' requires input '${input.name}': ${input.description}",
                mapOf("node" to node.id.truncateForError(), "kind" to kind.kind, "input" to input.name),
            )
        }

        supplied.forEach { (name, value) ->
            val input = declared[name]
            if (input == null) {
                into.add(
                    Validation.CALCULATOR_INPUT_UNKNOWN,
                    "nodes[$index].inputs.${name.truncateForError()}",
                    "Kind '${kind.kind}' has no input '${name.truncateForError()}'; it declares " +
                        "${declared.keys.joinToString()}.",
                    mapOf(
                        "node" to node.id.truncateForError(),
                        "kind" to kind.kind,
                        "reason" to "input",
                        "declared_inputs" to declared.keys.toList(),
                    ),
                )
            } else {
                checkInputValue(index, node, kind, input, value, pipeline, deploymentKeys, keyToWriter, ancestors, into)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun checkInputValue(
        index: Int,
        node: Node,
        kind: CalculatorKind,
        input: CalculatorInput,
        value: JsonNode,
        pipeline: Pipeline,
        deploymentKeys: Set<String>,
        keyToWriter: Map<String, String>,
        ancestors: Ancestry,
        into: FailureCollector,
    ) {
        val path = "nodes[$index].inputs.${input.name}"
        if (input.isList && !value.isArray && !isReference(value)) {
            typeMismatch(path, node, kind, input, "a JSON array", into)
            return
        }
        val elements = if (input.isList && value.isArray) value.toList() else listOf(value)
        elements.forEach { element ->
            val reference = referenceIn(element)
            if (reference != null) {
                checkReference(path, node, kind, input, reference, deploymentKeys, pipeline, keyToWriter, ancestors, into)
            } else {
                checkLiteral(path, node, kind, input, element, into)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun checkReference(
        path: String,
        node: Node,
        kind: CalculatorKind,
        input: CalculatorInput,
        reference: String,
        deploymentKeys: Set<String>,
        pipeline: Pipeline,
        keyToWriter: Map<String, String>,
        ancestors: Ancestry,
        into: FailureCollector,
    ) {
        // The reference's canonical type when the tier owning the key pins one: platform keys
        // from ContextKeys.PLATFORM_TYPES, every org value STRING on purpose (§0.2), a declared
        // parameter its own type. The deployment tiers are read FIRST because they already win
        // the existence check below without a depends_on edge — a calculator shadowing an org or
        // platform key (§0.2 tier 5) is typed by the key it shadows, consistently.
        val declaredType =
            when {
                reference in ContextKeys.PLATFORM_TYPES -> ContextKeys.PLATFORM_TYPES.getValue(reference)
                reference in deploymentKeys -> LogicalType.STRING
                else -> pipeline.parameters[reference]?.type
            }
        if (declaredType == null) {
            val writer = keyToWriter[reference]
            when {
                writer == null -> {
                    into.add(
                        Validation.CALCULATOR_INPUT_UNKNOWN,
                        path,
                        "Reference '\$$reference' names no Context key: it is not an org or platform key, not a " +
                            "declared parameter, and no node writes it.",
                        mapOf(
                            "node" to node.id.truncateForError(),
                            "reason" to "reference",
                            "reference" to reference.truncateForError(),
                        ),
                    )
                    return
                }

                writer != node.id && !ancestors.reaches(node.id, writer) -> {
                    unordered(path, node, reference, writer, into)
                    return
                }
            }
        }
        checkReferenceType(path, node, kind, input, reference, declaredType, pipeline, keyToWriter, into)
    }

    /**
     * The save-time type check for a `$reference`, the counterpart of [checkLiteral] for a value
     * the body does not carry inline. Both sides must be knowable: an `ANY` input (null
     * [CalculatorInput.type]) takes everything, and an `ANY`-output writer (null
     * [CalculatorKind.output]) is typed only by the run — either one skips the check rather than
     * guesses. A bare `"$ref"` for a LIST input resolves to one value at run, so it types against
     * the element type, which is what [CalculatorInput.type] holds either way.
     */
    @Suppress("LongParameterList")
    private fun checkReferenceType(
        path: String,
        node: Node,
        kind: CalculatorKind,
        input: CalculatorInput,
        reference: String,
        declaredType: LogicalType?,
        pipeline: Pipeline,
        keyToWriter: Map<String, String>,
        into: FailureCollector,
    ) {
        val inputType = input.type ?: return
        val referenceType =
            declaredType
                ?: keyToWriter[reference]
                    ?.let { writer -> pipeline.nodes.firstOrNull { it.id == writer }?.kind }
                    ?.let { CalculatorRegistry.find(it)?.output }
                ?: return
        if (referenceType != inputType) {
            typeMismatch(
                path,
                node,
                kind,
                input,
                "a ${inputType.wire} value; '\$$reference' resolves to ${referenceType.wire}",
                into,
                reference = reference,
            )
        }
    }

    private fun unordered(
        path: String,
        node: Node,
        key: String,
        writer: String,
        into: FailureCollector,
    ) = into.add(
        Validation.CALCULATOR_INPUT_UNORDERED,
        path,
        "'${key.truncateForError()}' is written by node '${writer.truncateForError()}', which " +
            "'${node.id.truncateForError()}' does not depend on. Add '${writer.truncateForError()}' to its " +
            "depends_on — sequencing is topology, not array order.",
        mapOf(
            "node" to node.id.truncateForError(),
            "context_key" to key.truncateForError(),
            "written_by" to writer.truncateForError(),
        ),
    )

    @Suppress("LongParameterList")
    private fun checkLiteral(
        path: String,
        node: Node,
        kind: CalculatorKind,
        input: CalculatorInput,
        value: JsonNode,
        into: FailureCollector,
    ) {
        // A null-typed input (`ANY`) accepts any scalar: the kinds that declare one — coalesce,
        // if_null, map — do not look at the value, so pinning a type would be a claim they do not
        // make. A container is still refused: an object has no canonical reading at all.
        val type = input.type
        if (type == null) {
            if (value.isContainerNode) typeMismatch(path, node, kind, input, "a scalar value", into)
            return
        }
        val outcome = ParameterCoercion.coerce(type, value)
        if (outcome is ParameterCoercion.Outcome.Rejected) {
            typeMismatch(path, node, kind, input, "a ${type.wire} value (§6.3 wire encoding)", into)
        }
    }

    @Suppress("LongParameterList")
    private fun typeMismatch(
        path: String,
        node: Node,
        kind: CalculatorKind,
        input: CalculatorInput,
        expected: String,
        into: FailureCollector,
        reference: String? = null,
    ) = into.add(
        Validation.CALCULATOR_INPUT_TYPE_MISMATCH,
        path,
        "Kind '${kind.kind}' input '${input.name}' takes $expected.",
        buildMap {
            put("node", node.id.truncateForError())
            put("kind", kind.kind)
            put("input", input.name)
            put("declared_type", input.type?.wire ?: CalculatorInput.ANY_TYPE)
            // Set only for the `$reference` shape — the key whose resolved type contradicted the input.
            if (reference != null) put("reference", reference.truncateForError())
        },
    )

    // ---- the SQL side of the same ordering rule ----

    @Suppress("LongParameterList")
    private fun checkSqlBindOrdering(
        index: Int,
        node: Node,
        templates: TemplateDryRenderer,
        workspaceId: UUID,
        keyToWriter: Map<String, String>,
        ancestors: Ancestry,
        into: FailureCollector,
    ) {
        if (keyToWriter.isEmpty() || node.template.id.isBlank()) return
        templates
            .boundParameters(workspaceId, node.template)
            .forEach { bound ->
                val writer = keyToWriter[bound] ?: return@forEach
                if (!ancestors.reaches(node.id, writer)) {
                    unordered("nodes[$index].template", node, bound, writer, into)
                }
            }
    }

    private fun isReference(value: JsonNode): Boolean = referenceIn(value) != null

    /** `"$name"` is a reference to a Context key; every other JSON value is a literal (§0.3). */
    private fun referenceIn(value: JsonNode): String? =
        value
            .takeIf { it.isTextual }
            ?.asText()
            ?.takeIf { it.startsWith("$") && it.length > 1 }
            ?.substring(1)
}

/**
 * Which nodes can see a given writer's Context key — transitive `depends_on` reachability,
 * computed **once per writer** rather than once per node.
 *
 * The direction is deliberate and it is a performance fact, not a style choice. A closure per
 * node is O(V·E), and §12.2 admits pipelines of 1000 nodes: on a densely-chained one that is
 * hundreds of millions of set insertions, which is exactly how this class first announced itself
 * — as an OOM in the validator's own suite, on the `pipeline_too_large` fixture. Walking FORWARD
 * from each writer instead costs O(W·(V+E)), and W is the number of CALCULATOR nodes, which is
 * small by construction: a pipeline with a thousand calculators has a different problem.
 *
 * Iterative, per §12.2's crash-safety rule. Dangling ids and cycles are tolerated rather than
 * diagnosed — §12.2 owns both verdicts, and a second report of the same defect helps nobody.
 */
internal class Ancestry private constructor(
    private val descendantsOf: Map<String, Set<String>>,
) {
    /** True when [node] depends on [ancestor], directly or through any chain. */
    fun reaches(
        node: String,
        ancestor: String,
    ): Boolean = descendantsOf[ancestor]?.contains(node) == true

    companion object {
        /** Reachability from each of [writers] forward along `depends_on`, reversed. */
        fun of(
            pipeline: Pipeline,
            writers: Collection<String>,
        ): Ancestry {
            if (writers.isEmpty()) return Ancestry(emptyMap())
            val dependents = mutableMapOf<String, MutableList<String>>()
            pipeline.nodes.forEach { node ->
                node.dependsOn.forEach { dependency -> dependents.getOrPut(dependency) { mutableListOf() } += node.id }
            }
            return Ancestry(
                writers.distinct().associateWith { writer ->
                    val seen = mutableSetOf<String>()
                    val queue = ArrayDeque(dependents[writer].orEmpty())
                    while (queue.isNotEmpty()) {
                        val next = queue.removeFirst()
                        if (!seen.add(next)) continue
                        queue.addAll(dependents[next].orEmpty())
                    }
                    seen
                },
            )
        }
    }
}
