package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * pipeline-contract §12.13 — the `TRANSFORM`-node rules (§4.12, transform-nodes design §3.1,
 * R3–R5).
 *
 * A TRANSFORM node pins a `jsonata`/`javascript` template and conforms to the pinned
 * version's contract (D-T8's scoped exception: the template declares, the pipeline conforms —
 * proven at save, exactly as D3's dry render proves a SQL pin). The contract reaches this
 * module as [TransformContractView] through [TemplateDryRenderer.transformContract]; a null
 * view means [ReferenceRules] already reported the pin (not-found, version, or type), and
 * every contract-dependent check below skips rather than double-reporting.
 *
 * ## What a TRANSFORM reads and writes
 *
 * `inputs` follows the CALCULATOR convention (§3.1): `"$name"` is a Context key of any tier,
 * anything else is a tempdb table an ancestor stages — the writer map and the ancestor-staged
 * set are built once per pipeline, the same derivation §12.10 uses, plus the value-mode keys
 * a TRANSFORM writes under its own `context_key`. Sequencing is topology here too: a `$key`
 * bound without a `depends_on` path to its writer is `calculator_input_unordered`, reused.
 *
 * ## R4 — object keys have one reader
 *
 * A key whose writer's contract output is `kind: object` may be bound ONLY by a TRANSFORM
 * `inputs` entry. A SQL `:bind`, a calculator `inputs` `$reference`, or a PIPELINE
 * `parameters` `${ref}` naming one is refused here with `transform_object_key_bound`, because
 * the run-time bind path has no object-shaped value channel (Spring's named-parameter bind
 * would fail at 3am on a `Map` value — the save-time refusal is the whole point of R4).
 */
internal object TransformRules {
    fun check(
        pipeline: Pipeline,
        orgContext: OrgContext,
        templates: TemplateDryRenderer,
        workspaceId: UUID,
        into: FailureCollector,
        kinds: (String) -> co.datapipelines.calculators.CalculatorKind? = co.datapipelines.calculators.CalculatorRegistry::find,
    ) {
        val contracts =
            pipeline.nodes
                .filter { it.type == NodeType.TRANSFORM }
                .associate { it.id to templates.transformContract(workspaceId, it.template) }
        // The Context-key writers, first writer wins — §12.10's own rule, widened to the
        // value-mode TRANSFORM keys (a transform's `context_key` obeys every calculator rule).
        val keyToWriter =
            pipeline.nodes
                .flatMap { node -> writtenKeys(node).map { it to node.id } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, ids) -> ids.first() }
        val objectKeys =
            keyToWriter
                .filter { (key, writerId) ->
                    pipeline.node(writerId)?.type == NodeType.TRANSFORM &&
                        contracts[writerId]?.output is TransformContractView.Output.Obj
                }.keys
        val writers = keyToWriter.values
        val ancestors = Ancestry.of(pipeline, writers)
        val staged = StagedTables.of(pipeline)
        val deploymentKeys = orgContext.keys + ContextKeys.PLATFORM

        val ruleContext =
            RuleContext(pipeline, contracts, deploymentKeys, keyToWriter, objectKeys, ancestors, staged, kinds)
        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.TRANSFORM) {
                checkTransformNode(index, node, contracts[node.id], ruleContext, into)
            } else {
                checkForeignFields(index, node, into)
            }
        }
        checkObjectKeyBindings(templates, workspaceId, ruleContext, into)
    }

    /**
     * Everything the §12.13 rules read about the rest of the pipeline, derived once in
     * [check] — one value threaded through the rule functions instead of seven parameters
     * (the same derivation §12.10 builds for the calculators, widened to the TRANSFORM keys).
     */
    private class RuleContext(
        val pipeline: Pipeline,
        val contracts: Map<String, TransformContractView?>,
        val deploymentKeys: Set<String>,
        val keyToWriter: Map<String, String>,
        val objectKeys: Set<String>,
        val ancestors: Ancestry,
        val staged: StagedTables,
        val kinds: (String) -> co.datapipelines.calculators.CalculatorKind?,
    )

    /** Every Context key [node] writes — the calculator shapes plus a value-mode TRANSFORM's `context_key`. */
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

    // ---------------------------------------------------------------- the node

    private fun checkTransformNode(
        index: Int,
        node: Node,
        contract: TransformContractView?,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        if (node.source.isNotBlank()) {
            into.add(
                Validation.TRANSFORM_SOURCE_FORBIDDEN,
                "nodes[$index].source",
                "TRANSFORM node '${node.id.truncateForError()}' declares a source; a TRANSFORM is tempdb-only — " +
                    "it reads what upstream nodes staged plus the Context, and never touches a datasource.",
                mapOf("node" to node.id.truncateForError()),
            )
        }
        if (contract != null) {
            checkInputs(index, node, contract, ruleContext, into)
            checkOutput(index, node, contract, ruleContext, into)
            if (node.strict == true && !contract.rejects) {
                into.add(
                    Validation.TRANSFORM_STRICT_WITHOUT_REJECTS,
                    "nodes[$index].strict",
                    "TRANSFORM node '${node.id.truncateForError()}' is strict, but the pinned contract declares no " +
                        "rejects — there is no rejects table to fail on.",
                    mapOf("node" to node.id.truncateForError()),
                )
            }
        }
    }

    /** `strict` and `output.rejects` are TRANSFORM-only fields — refused everywhere else. */
    private fun checkForeignFields(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        if (node.strict != null) {
            into.add(
                Validation.TRANSFORM_STRICT_WITHOUT_REJECTS,
                "nodes[$index].strict",
                "${node.type.wire} node '${node.id.truncateForError()}' declares strict, which only a TRANSFORM node carries.",
                mapOf("node" to node.id.truncateForError()),
            )
        }
        val rejects = (node.output as? NodeOutput.Tempdb)?.rejects
        if (rejects != null) {
            into.add(
                Validation.TRANSFORM_REJECTS_UNDECLARED,
                "nodes[$index].output.rejects",
                "${node.type.wire} node '${node.id.truncateForError()}' declares output.rejects, which only a " +
                    "TRANSFORM node carries.",
                mapOf("node" to node.id.truncateForError()),
            )
        }
    }

    // ---------------------------------------------------------------- inputs

    private fun checkInputs(
        index: Int,
        node: Node,
        contract: TransformContractView,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        val supplied = node.inputs.orEmpty()
        val declared = contract.inputs.keys
        val missing = declared - supplied.keys
        val extra = supplied.keys - declared
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            into.add(
                Validation.TRANSFORM_INPUT_CONTRACT,
                "nodes[$index].inputs",
                "TRANSFORM node '${node.id.truncateForError()}' inputs must equal the contract's " +
                    "(${declared.sorted().joinToString()}); missing ${missing.sorted()}, extra ${extra.sorted()}.",
                mapOf(
                    "node" to node.id.truncateForError(),
                    "missing" to missing.sorted(),
                    "extra" to extra.sorted(),
                ),
            )
        }
        supplied.forEach { (name, value) ->
            val input = contract.inputs[name] ?: return@forEach
            val path = "nodes[$index].inputs.${name.truncateForError()}"
            val reference = value.takeIf { it.isTextual }?.asText()
            when {
                reference == null -> {
                    into.add(
                        Validation.TRANSFORM_INPUT_CONTRACT,
                        path,
                        "TRANSFORM node '${node.id.truncateForError()}' input '$name' must be a string: " +
                            "a staged table name, or \"\$key\" naming a Context key.",
                        mapOf("node" to node.id.truncateForError(), "input" to name),
                    )
                }

                reference.startsWith("$") && reference.length > 1 -> {
                    checkValueInput(path, node, name, input, reference.substring(1), ruleContext, into)
                }

                else -> {
                    checkTableInput(path, node, name, input, reference, ruleContext.staged, into)
                }
            }
        }
    }

    private fun checkValueInput(
        path: String,
        node: Node,
        name: String,
        input: TransformContractView.Input,
        key: String,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        if (input !is TransformContractView.Input.Value) {
            into.add(
                Validation.TRANSFORM_INPUT_CONTRACT,
                path,
                "TRANSFORM node '${node.id.truncateForError()}' binds '\$$key' to input '$name', " +
                    "which the contract declares a TABLE input — a table input names a staged table, not a Context key.",
                mapOf("node" to node.id.truncateForError(), "input" to name),
            )
            return
        }
        // An object-valued key (R4) binds ONLY here — a TRANSFORM value input — and fits no
        // declared LogicalType, so the type check does not apply to it.
        if (key in ruleContext.objectKeys) {
            checkOrdering(path, node, key, ruleContext.keyToWriter, ruleContext.ancestors, into)
            return
        }
        checkValueInputType(path, node, name, input, key, ruleContext, into)
    }

    /**
     * The value input's type resolution (§3.1): the Context tier the key lives in — platform
     * type, org key as STRING, declared parameter — else the writer's resolved output, with
     * §12.10's unknown and ordering refusals on the way.
     */
    private fun checkValueInputType(
        path: String,
        node: Node,
        name: String,
        input: TransformContractView.Input.Value,
        key: String,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        val declaredType =
            when {
                key in ContextKeys.PLATFORM_TYPES -> ContextKeys.PLATFORM_TYPES.getValue(key)
                key in ruleContext.deploymentKeys -> LogicalType.STRING
                else -> ruleContext.pipeline.parameters[key]?.type
            }
        if (declaredType == null) {
            val writer = ruleContext.keyToWriter[key]
            when {
                writer == null -> {
                    into.add(
                        Validation.TRANSFORM_INPUT_UNKNOWN,
                        path,
                        "TRANSFORM node '${node.id.truncateForError()}' input '$name' references '\$$key', which names " +
                            "no Context key: it is not an org or platform key, not a declared parameter, and no node writes it.",
                        mapOf("node" to node.id.truncateForError(), "input" to name, "reference" to key.truncateForError()),
                    )
                    return
                }

                writer != node.id && !ruleContext.ancestors.reaches(node.id, writer) -> {
                    unordered(path, node, key, writer, into)
                    return
                }
            }
        }
        val referenceType =
            declaredType
                ?: ruleContext.keyToWriter[key]
                    ?.let { writerId -> ruleContext.pipeline.node(writerId) }
                    ?.let { writer -> writerOutputType(writer, key, ruleContext) }
                ?: return
        if (referenceType != input.type) {
            into.add(
                Validation.TRANSFORM_INPUT_CONTRACT,
                path,
                "TRANSFORM node '${node.id.truncateForError()}' input '$name' takes a ${input.type.wire} value; " +
                    "'\$$key' resolves to ${referenceType.wire}.",
                mapOf(
                    "node" to node.id.truncateForError(),
                    "input" to name,
                    "declared_type" to input.type.wire,
                    "reference" to key.truncateForError(),
                ),
            )
        }
    }

    /**
     * The type one of [writer]'s keys resolves to — a calculator kind's output (§12.10's own
     * derivation), or a value-mode TRANSFORM's declared contract output. Null when neither
     * pins one: the type check skips rather than guesses (the §12.10 convention).
     */
    private fun writerOutputType(
        writer: Node,
        key: String,
        ruleContext: RuleContext,
    ): LogicalType? =
        when (writer.type) {
            NodeType.CALCULATOR -> {
                writer.kind
                    ?.let(ruleContext.kinds)
                    ?.let { kind -> calculatorOutputEntries(writer, kind).firstOrNull { it.first == key }?.second }
            }

            NodeType.TRANSFORM -> {
                (ruleContext.contracts[writer.id]?.output as? TransformContractView.Output.Value)?.type
            }

            else -> {
                null
            }
        }

    private fun checkTableInput(
        path: String,
        node: Node,
        name: String,
        input: TransformContractView.Input,
        table: String,
        staged: StagedTables,
        into: FailureCollector,
    ) {
        if (input !is TransformContractView.Input.Table) {
            into.add(
                Validation.TRANSFORM_INPUT_CONTRACT,
                path,
                "TRANSFORM node '${node.id.truncateForError()}' binds table '$table' to input '$name', " +
                    "which the contract declares a VALUE input — a value input names a Context key as \"\$key\".",
                mapOf("node" to node.id.truncateForError(), "input" to name),
            )
            return
        }
        if (table !in staged.visibleFrom(node.id)) {
            into.add(
                Validation.TRANSFORM_INPUT_UNKNOWN,
                path,
                "TRANSFORM node '${node.id.truncateForError()}' input '$name' names tempdb table '$table', " +
                    "which no node this one depends on stages.",
                mapOf("node" to node.id.truncateForError(), "input" to name, "table" to table.truncateForError()),
            )
        }
    }

    private fun checkOrdering(
        path: String,
        node: Node,
        key: String,
        keyToWriter: Map<String, String>,
        ancestors: Ancestry,
        into: FailureCollector,
    ) {
        val writer = keyToWriter[key] ?: return
        if (writer != node.id && !ancestors.reaches(node.id, writer)) {
            unordered(path, node, key, writer, into)
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

    // ---------------------------------------------------------------- output

    private fun checkOutput(
        index: Int,
        node: Node,
        contract: TransformContractView,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        when (contract.mode) {
            TransformContractView.Mode.ROW, TransformContractView.Mode.TABLE -> checkTableModeOutput(index, node, contract, into)
            TransformContractView.Mode.VALUE -> checkValueModeOutput(index, node, ruleContext, into)
        }
    }

    private fun checkTableModeOutput(
        index: Int,
        node: Node,
        contract: TransformContractView,
        into: FailureCollector,
    ) {
        if (node.contextKey != null) {
            into.add(
                Validation.TRANSFORM_OUTPUT_SHAPE,
                "nodes[$index].context_key",
                "TRANSFORM node '${node.id.truncateForError()}' pins a ${contract.mode.name.lowercase()}-mode " +
                    "contract and declares context_key — only a value-mode TRANSFORM writes a Context key.",
                mapOf("node" to node.id.truncateForError()),
            )
        }
        when (val output = node.output) {
            is NodeOutput.Tempdb -> {
                checkTempdbTableOutput(index, node, contract, output, into)
            }

            NodeOutput.Caller -> {
                if (contract.rejects) {
                    into.add(
                        Validation.TRANSFORM_REJECTS_ON_CALLER,
                        "nodes[$index].output",
                        "TRANSFORM node '${node.id.truncateForError()}' pins a rejects-declaring contract with " +
                            "output.target 'caller' — the caller result has no rejects table, and rejects are " +
                            "never silently dropped (R5).",
                        mapOf("node" to node.id.truncateForError()),
                    )
                }
            }

            is NodeOutput.Datasource, null -> {
                into.add(
                    Validation.TRANSFORM_OUTPUT_SHAPE,
                    "nodes[$index].output",
                    "TRANSFORM node '${node.id.truncateForError()}' pins a ${contract.mode.name.lowercase()}-mode " +
                        "contract, whose output is { target: tempdb, table, rejects? } or { target: caller } — " +
                        "a TRANSFORM never writes a datasource.",
                    mapOf("node" to node.id.truncateForError()),
                )
            }
        }
    }

    /**
     * The tempdb target of a row/table-mode output: the table is required, and the rejects
     * pair is declared together — a rejects-declaring contract must name the rejects table,
     * a rejects table without the contract is refused (R5's other half).
     */
    private fun checkTempdbTableOutput(
        index: Int,
        node: Node,
        contract: TransformContractView,
        output: NodeOutput.Tempdb,
        into: FailureCollector,
    ) {
        if (output.table.isBlank()) {
            into.add(
                Validation.OUTPUT_TABLE_MISSING,
                "nodes[$index].output.table",
                "A tempdb output requires 'table' — it is the name downstream nodes query.",
                mapOf("node" to node.id.truncateForError()),
            )
        }
        when {
            contract.rejects && output.rejects.isNullOrBlank() -> {
                into.add(
                    Validation.TRANSFORM_REJECTS_MISSING,
                    "nodes[$index].output.rejects",
                    "TRANSFORM node '${node.id.truncateForError()}' pins a rejects-declaring contract, so its " +
                        "output must name the rejects table — rejects are never silently dropped.",
                    mapOf("node" to node.id.truncateForError()),
                )
            }

            !contract.rejects && output.rejects != null -> {
                into.add(
                    Validation.TRANSFORM_REJECTS_UNDECLARED,
                    "nodes[$index].output.rejects",
                    "TRANSFORM node '${node.id.truncateForError()}' names a rejects table, but the pinned " +
                        "contract declares no rejects.",
                    mapOf("node" to node.id.truncateForError()),
                )
            }
        }
    }

    private fun checkValueModeOutput(
        index: Int,
        node: Node,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        if (node.output != null) {
            into.add(
                Validation.TRANSFORM_OUTPUT_SHAPE,
                "nodes[$index].output",
                "TRANSFORM node '${node.id.truncateForError()}' pins a value-mode contract and declares an output " +
                    "block — a value-mode TRANSFORM writes its Context key only (R3: no JSON result bodies).",
                mapOf("node" to node.id.truncateForError()),
            )
        }
        val key = node.contextKey
        if (key.isNullOrBlank()) {
            into.add(
                Validation.TRANSFORM_OUTPUT_SHAPE,
                "nodes[$index].context_key",
                "TRANSFORM node '${node.id.truncateForError()}' pins a value-mode contract, so it must name the " +
                    "Context key it writes with context_key.",
                mapOf("node" to node.id.truncateForError()),
            )
            return
        }
        if (!ContextKeys.NAME.matches(key)) {
            into.add(
                Validation.CALCULATOR_OUTPUT_NAME_INVALID,
                "nodes[$index].context_key",
                "context key '${key.truncateForError()}' must match ${ContextKeys.NAME.pattern} (§6.1) — " +
                    "it is bound in SQL as :$key.",
                mapOf("node" to node.id.truncateForError(), "context_key" to key.truncateForError()),
            )
            return
        }
        if (ruleContext.pipeline.parameters.containsKey(key)) {
            into.add(
                Validation.CALCULATOR_OUTPUT_COLLISION,
                "nodes[$index].context_key",
                "context key '${key.truncateForError()}' is already written by a declared parameter of the same " +
                    "name — one writer per Context key.",
                mapOf("node" to node.id.truncateForError(), "context_key" to key.truncateForError(), "collides_with" to "parameter"),
            )
            return
        }
        val other = ruleContext.keyToWriter[key]
        if (other != null && other != node.id && ruleContext.pipeline.node(other)?.type == NodeType.TRANSFORM) {
            // Reported here only when the OTHER writer is a TRANSFORM too — a collision with a
            // CALCULATOR is §12.10's verdict on the calculator node (its writer map includes
            // transform keys), and one edit must not buy an author two errors.
            into.add(
                Validation.CALCULATOR_OUTPUT_COLLISION,
                "nodes[$index].context_key",
                "context key '${key.truncateForError()}' is already written by node '${other.truncateForError()}' — " +
                    "one writer per Context key.",
                mapOf("node" to node.id.truncateForError(), "context_key" to key.truncateForError(), "collides_with" to "node"),
            )
        }
    }

    // ---------------------------------------------------------------- R4: object keys have one reader

    /**
     * R4: an object-valued Context key bound anywhere but a TRANSFORM `inputs` entry. The
     * three offending shapes: a SQL node's `:key` (found the way §12.10's ordering rule finds
     * them), a calculator's `inputs` `$key`, and a PIPELINE node's `parameters` `${ref}`.
     */
    private fun checkObjectKeyBindings(
        templates: TemplateDryRenderer,
        workspaceId: UUID,
        ruleContext: RuleContext,
        into: FailureCollector,
    ) {
        if (ruleContext.objectKeys.isEmpty()) return
        ruleContext.pipeline.nodes.forEachIndexed { index, node ->
            when (node.type) {
                NodeType.DQL, NodeType.DML, NodeType.DDL -> {
                    if (node.template.id.isBlank()) return@forEachIndexed
                    templates
                        .boundParameters(workspaceId, node.template)
                        .filter { it in ruleContext.objectKeys }
                        .forEach { key ->
                            objectKeyBound(
                                "nodes[$index].template",
                                node,
                                key,
                                ruleContext.keyToWriter,
                                "bound as :$key in its SQL — an object value has no SQL bind channel (R4)",
                                into,
                            )
                        }
                }

                NodeType.CALCULATOR -> {
                    node.inputs.orEmpty().forEach { (input, value) ->
                        val key = referenceIn(value) ?: return@forEach
                        if (key in ruleContext.objectKeys) {
                            objectKeyBound(
                                "nodes[$index].inputs.${input.truncateForError()}",
                                node,
                                key,
                                ruleContext.keyToWriter,
                                "a calculator input is a scalar channel — an object has no canonical reading (R4)",
                                into,
                            )
                        }
                    }
                }

                NodeType.PIPELINE -> {
                    node.parameters.orEmpty().forEach { (parameter, value) ->
                        val key = pipelineReferenceIn(value) ?: return@forEach
                        if (key in ruleContext.objectKeys) {
                            objectKeyBound(
                                "nodes[$index].parameters.${parameter.truncateForError()}",
                                node,
                                key,
                                ruleContext.keyToWriter,
                                "a composition parameter is a scalar channel (R4)",
                                into,
                            )
                        }
                    }
                }

                else -> {
                    // a TRANSFORM value input binds no table and declares nothing here
                }
            }
        }
    }

    private fun objectKeyBound(
        path: String,
        node: Node,
        key: String,
        keyToWriter: Map<String, String>,
        why: String,
        into: FailureCollector,
    ) = into.add(
        Validation.TRANSFORM_OBJECT_KEY_BOUND,
        path,
        "'${key.truncateForError()}' is an object-valued Context key written by node " +
            "'${keyToWriter[key]?.truncateForError()}'; $why. An object key's one reader is another TRANSFORM's inputs.",
        mapOf(
            "node" to node.id.truncateForError(),
            "context_key" to key.truncateForError(),
            "written_by" to keyToWriter[key]?.truncateForError(),
        ),
    )

    private fun referenceIn(value: JsonNode): String? =
        value
            .takeIf { it.isTextual }
            ?.asText()
            ?.takeIf { it.startsWith("$") && it.length > 1 }
            ?.substring(1)

    private fun pipelineReferenceIn(value: JsonNode): String? =
        value
            .takeIf { it.isTextual }
            ?.asText()
            ?.let { PARAMETER_REFERENCE.matchEntire(it) }
            ?.groupValues
            ?.get(1)

    private val PARAMETER_REFERENCE = Regex("\\$\\{([a-z_][a-z0-9_]*)}")
}

/**
 * What every node stages, and who can see it — built once per pipeline for §12.13's
 * `transform_input_unknown`.
 *
 * The validator has no staged-table registry (§12.1's [StructuralRules.checkOutputTables]
 * checks identifiers and uniqueness, not reachability), so the answer is derived here: a
 * node's own `output.table` (and `output.rejects`) unioned over its transitive `depends_on`
 * closure. The closure is folded along an ITERATIVE topological walk (§12.2's crash-safety
 * rule is normative — a deep chain must not exhaust the JVM stack); nodes a cycle strands
 * get the empty set, because §12.2 already owns the cycle verdict and a second report helps
 * nobody. Dangling ids are tolerated the same way.
 */
internal class StagedTables private constructor(
    private val visible: Map<String, Set<String>>,
) {
    /** Every tempdb table an ancestor of [nodeId] stages (output tables and rejects tables). */
    fun visibleFrom(nodeId: String): Set<String> = visible[nodeId].orEmpty()

    companion object {
        fun of(pipeline: Pipeline): StagedTables {
            val byId = pipeline.nodes.associateBy { it.id }
            val dependents = HashMap<String, MutableList<String>>()
            val indegree = HashMap<String, Int>()
            pipeline.nodes.forEach { node ->
                indegree.putIfAbsent(node.id, 0)
                node.dependsOn.forEach { dependency ->
                    if (dependency in byId) {
                        dependents.getOrPut(dependency) { mutableListOf() } += node.id
                        indegree.merge(node.id, 1, Int::plus)
                    }
                }
            }
            val queue = ArrayDeque(pipeline.nodes.filter { indegree[it.id] == 0 }.map { it.id })
            val visible = HashMap<String, Set<String>>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val node = byId.getValue(id)
                visible[id] =
                    node.dependsOn
                        .flatMap { dependency -> visible[dependency].orEmpty() + ownStaged(byId[dependency]) }
                        .toSet()
                dependents[id].orEmpty().forEach { dependent ->
                    if (indegree.merge(dependent, -1, Int::plus) == 0) queue.addLast(dependent)
                }
            }
            return StagedTables(visible)
        }

        private fun ownStaged(node: Node?): Set<String> =
            when (val output = node?.output) {
                is NodeOutput.Tempdb -> {
                    setOfNotNull(output.table.takeUnless { it.isBlank() }, output.rejects?.takeUnless { it.isBlank() })
                }

                else -> {
                    emptySet()
                }
            }
    }
}
