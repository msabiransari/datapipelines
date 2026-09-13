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
 * ## One key, or a named set (121)
 *
 * A kind is single-output and the node names its value with `context_key`, or multi-output and
 * the node maps every declared output through `context_keys` — never both fields, never
 * neither, every name known, none unmapped (`calculator_output_shape_mismatch` /
 * `calculator_output_unknown` / `calculator_outputs_incomplete`). Each mapped key then obeys
 * every rule a single key always has — name shape, collision, ordering, type resolution —
 * because the checks below run per key over one derivation ([calculatorOutputEntries]).
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
        kinds: (String) -> CalculatorKind? = CalculatorRegistry::find,
    ) {
        val writers = pipeline.nodes.filter { it.type == NodeType.CALCULATOR }
        // FIRST writer wins the key, so a duplicate is reported once — on the node that came
        // second — and names the one that already owns it. Keeping the last writer instead would
        // report the collision on the FIRST node and name the second, which reads backwards.
        val keyToWriter =
            writers
                .flatMap { node -> writtenKeys(node).map { it to node.id } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.first() }
        val ancestors = Ancestry.of(pipeline, keyToWriter.values)
        val deploymentKeys = orgContext.keys + ContextKeys.PLATFORM

        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.CALCULATOR) {
                checkCalculatorNode(index, node, pipeline, deploymentKeys, keyToWriter, ancestors, kinds, into)
            } else {
                checkForeignFields(index, node, into)
                checkSqlBindOrdering(index, node, templates, workspaceId, keyToWriter, ancestors, into)
            }
        }
    }

    /**
     * Every Context key [node] would write under either mapping shape — the shape verdicts are
     * §12.10's, and this set exists so a key an author named is tracked for collision and
     * ordering even while its mapping shape is being refused.
     */
    private fun writtenKeys(node: Node): List<String> =
        buildList {
            node.contextKey
                ?.takeUnless { it.isBlank() }
                ?.let(::add)
            node.contextKeys
                ?.values
                ?.filter { it.isNotBlank() }
                ?.let(::addAll)
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
                if (node.contextKeys != null) add("context_keys")
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
        kinds: (String) -> CalculatorKind?,
        into: FailureCollector,
    ) {
        checkNoSqlFields(index, node, into)
        val kind = checkKind(index, node, kinds, into)
        checkContextKeys(index, node, pipeline, keyToWriter, into)
        if (kind == null) return
        checkOutputShape(index, node, kind, into)
        checkInputs(index, node, kind, pipeline, deploymentKeys, keyToWriter, ancestors, kinds, into)
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
                "and writes only Context keys, so it carries none of them.",
            mapOf("node" to node.id.truncateForError(), "fields" to present),
        )
    }

    private fun checkKind(
        index: Int,
        node: Node,
        kinds: (String) -> CalculatorKind?,
        into: FailureCollector,
    ): CalculatorKind? {
        val kind = node.kind
        // A key mapping is `context_key` on a single-output kind or `context_keys` on a
        // multi-output one; §12.10's shape verdicts below decide which fits the kind. What is
        // missing HERE is kind or inputs — the fields no shape rule can substitute for.
        val hasKeyMapping = !node.contextKey.isNullOrBlank() || node.contextKeys != null
        if (kind.isNullOrBlank() || node.inputs == null) {
            val missing =
                buildList {
                    if (kind.isNullOrBlank()) add("kind")
                    if (node.inputs == null) add("inputs")
                    if (!hasKeyMapping) add("context_key")
                }
            into.add(
                Validation.CALCULATOR_NODE_INCOMPLETE,
                "nodes[$index]",
                "CALCULATOR node '${node.id.truncateForError()}' is missing ${missing.joinToString()}; a calculator " +
                    "node declares kind, inputs and a key mapping (context_key or context_keys).",
                mapOf("node" to node.id.truncateForError(), "missing" to missing),
            )
            if (kind.isNullOrBlank()) return null
        }
        return kinds(kind) ?: run {
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

    // ---- the output mapping (121: one key, or a named set) ----

    /**
     * §12.10's shape verdicts: `context_key` XOR `context_keys` — never both, never neither —
     * and the field that fits the KIND's shape. A single-output kind names its one value with
     * `context_key`; a multi-output kind maps every declared output through `context_keys`
     * (every name known, none unmapped).
     */
    private fun checkOutputShape(
        index: Int,
        node: Node,
        kind: CalculatorKind,
        into: FailureCollector,
    ) {
        val single = node.contextKey?.takeUnless { it.isBlank() }
        val mapping = node.contextKeys
        when {
            single != null && mapping != null -> {
                shapeMismatch(
                    index,
                    node,
                    kind,
                    "both_fields",
                    "declares both context_key and context_keys. A node carries exactly one: context_key " +
                        "on a single-output kind, context_keys on a multi-output one.",
                    into,
                )
            }

            single == null && mapping == null -> {
                shapeMismatch(
                    index,
                    node,
                    kind,
                    "neither_field",
                    "declares neither context_key nor context_keys. " + expectedShape(kind),
                    into,
                )
            }

            kind.outputs.isEmpty() && mapping != null -> {
                shapeMismatch(
                    index,
                    node,
                    kind,
                    "single_output_kind",
                    "maps its output with context_keys, but kind '${kind.kind}' writes ONE value. " + expectedShape(kind),
                    into,
                )
            }

            kind.outputs.isNotEmpty() && single != null -> {
                shapeMismatch(
                    index,
                    node,
                    kind,
                    "multi_output_kind",
                    "names one key with context_key, but kind '${kind.kind}' writes a named set. " + expectedShape(kind),
                    into,
                )
            }

            kind.outputs.isNotEmpty() && mapping != null -> {
                checkOutputMapping(index, node, kind, mapping, into)
            }
        }
    }

    /** How the kind's mapping must read — the sentence every shape refusal ends with. */
    private fun expectedShape(kind: CalculatorKind): String =
        if (kind.outputs.isEmpty()) {
            "Kind '${kind.kind}' is single-output: name its value with context_key."
        } else {
            "Kind '${kind.kind}' declares outputs ${kind.outputs.joinToString(", ") { it.name }}: " +
                "map every one of them with context_keys."
        }

    private fun shapeMismatch(
        index: Int,
        node: Node,
        kind: CalculatorKind,
        reason: String,
        what: String,
        into: FailureCollector,
    ) = into.add(
        Validation.CALCULATOR_OUTPUT_SHAPE_MISMATCH,
        "nodes[$index]",
        "CALCULATOR node '${node.id.truncateForError()}' $what",
        mapOf("node" to node.id.truncateForError(), "kind" to kind.kind, "reason" to reason),
    )

    /**
     * The two mapping-completeness verdicts on a multi-output node whose shape is right: every
     * name in the mapping is one the kind declares (`calculator_output_unknown`), and every
     * declared output is mapped (`calculator_outputs_incomplete`) — no partial mapping, so a
     * reader can never bind a half-written window.
     */
    private fun checkOutputMapping(
        index: Int,
        node: Node,
        kind: CalculatorKind,
        mapping: Map<String, String>,
        into: FailureCollector,
    ) {
        val declared = kind.outputs.map { it.name }
        mapping.keys.filter { it !in declared }.forEach { name ->
            into.add(
                Validation.CALCULATOR_OUTPUT_UNKNOWN,
                "nodes[$index].context_keys",
                "Kind '${kind.kind}' has no output '${name.truncateForError()}'; it declares ${declared.joinToString()}.",
                mapOf(
                    "node" to node.id.truncateForError(),
                    "kind" to kind.kind,
                    "output" to name.truncateForError(),
                    "known_outputs" to declared,
                ),
            )
        }
        val missing = declared.filter { mapping[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            into.add(
                Validation.CALCULATOR_OUTPUTS_INCOMPLETE,
                "nodes[$index].context_keys",
                "Kind '${kind.kind}' output ${missing.joinToString()} is not mapped; every declared output must be — " +
                    "a caller who needs one value still maps both, so no reader can bind a key the node never writes.",
                mapOf("node" to node.id.truncateForError(), "kind" to kind.kind, "missing" to missing),
            )
        }
    }

    // ---- the output keys ----

    /**
     * Every mapped key obeys the same rules a single node's `context_key` always has (121: the
     * mechanism is general — nothing about a key's checks changes because its kind writes more
     * than one). The path names where the key was declared: `context_key`, or the
     * `context_keys` entry's output name.
     */
    private fun checkContextKeys(
        index: Int,
        node: Node,
        pipeline: Pipeline,
        keyToWriter: Map<String, String>,
        into: FailureCollector,
    ) {
        val declared =
            buildList {
                node.contextKey?.takeUnless { it.isBlank() }?.let { add("context_key" to it) }
                node.contextKeys?.forEach { (output, key) ->
                    if (key.isNotBlank()) add("context_keys.$output" to key)
                }
            }
        declared.forEach { (field, key) ->
            val path = "nodes[$index].$field"
            if (!ContextKeys.NAME.matches(key)) {
                into.add(
                    Validation.CALCULATOR_OUTPUT_NAME_INVALID,
                    path,
                    "context key '${key.truncateForError()}' must match ${ContextKeys.NAME.pattern} (§6.1) — " +
                        "it is bound in SQL as :$key.",
                    mapOf("node" to node.id.truncateForError(), "context_key" to key.truncateForError()),
                )
                return@forEach
            }
            // A calculator MAY shadow an org or platform key (§0.2 tier 5). It may never shadow a
            // declared PARAMETER: that is the caller's input, and silently overwriting one makes an
            // execute request a lie about what ran.
            if (pipeline.parameters.containsKey(key)) {
                collision(node, path, key, "a declared parameter of the same name", "parameter", into)
                return@forEach
            }
            val other = keyToWriter[key]
            if (other != null && other != node.id) {
                collision(node, path, key, "node '${other.truncateForError()}'", "node", into)
            }
        }
    }

    private fun collision(
        node: Node,
        path: String,
        key: String,
        what: String,
        kind: String,
        into: FailureCollector,
    ) = into.add(
        Validation.CALCULATOR_OUTPUT_COLLISION,
        path,
        "context key '${key.truncateForError()}' is already written by $what — one writer per Context key.",
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
        kinds: (String) -> CalculatorKind?,
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
                checkInputValue(index, node, kind, input, value, pipeline, deploymentKeys, keyToWriter, ancestors, kinds, into)
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
        kinds: (String) -> CalculatorKind?,
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
                checkReference(path, node, kind, input, reference, deploymentKeys, pipeline, keyToWriter, ancestors, kinds, into)
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
        kinds: (String) -> CalculatorKind?,
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
        checkReferenceType(path, node, kind, input, reference, declaredType, pipeline, keyToWriter, kinds, into)
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
        kinds: (String) -> CalculatorKind?,
        into: FailureCollector,
    ) {
        val inputType = input.type ?: return
        val referenceType =
            declaredType
                ?: keyToWriter[reference]
                    ?.let { writer -> pipeline.nodes.firstOrNull { it.id == writer } }
                    ?.let { writer -> writer.kind?.let(kinds)?.let { kind -> outputTypeOf(writer, kind, reference) } }
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

    /**
     * The type a reference to one of [writer]'s keys resolves to: the kind's output for a
     * single-output node, the mapped output's own type for a multi-output one (121) — the same
     * derivation [Pipeline.calculatorOutputs] uses, so the declared set and this check cannot
     * disagree about what a key IS. Null when the key is unmapped or the kind pins no type:
     * §12.10 owns the mapping verdict, and an ANY-typed value is typed only by the run.
     */
    private fun outputTypeOf(
        writer: Node,
        kind: CalculatorKind,
        key: String,
    ): LogicalType? = calculatorOutputEntries(writer, kind).firstOrNull { it.first == key }?.second

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
