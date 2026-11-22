package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.4 (#1034, write-transactions §3.4) — the shape of a DML node: its
 * declared operation, an insert's key and input, and where its result rows go.
 *
 * Pure over the body — no registry, no template — so run admission can re-apply exactly these
 * verdicts to a stored version before any business write ([DmlShapeCheck]); that is how an
 * operation-less DML node saved before this rule existed is refused at its next run (record §7,
 * the owner's legacy-DML waiver).
 */
internal object DmlRules {
    /**
     * A physical column name the driver is asked to return: an SQL identifier, never quoted or
     * interpolated into SQL — it is passed to `prepareStatement(sql, columnNames)` and compared,
     * case-insensitively, with the label the driver hands back.
     */
    private val KEY_COLUMN = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")

    fun check(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val staged = lazy { StagedTables.of(pipeline) }
        pipeline.nodes.forEachIndexed { index, node ->
            if (node.type == NodeType.DML) {
                checkDml(index, node, pipeline, staged, into)
            } else {
                forbidDmlFields(index, node, into)
                forbidDiscard(index, node, into)
            }
        }
    }

    private fun checkDml(
        index: Int,
        node: Node,
        pipeline: Pipeline,
        staged: Lazy<StagedTables>,
        into: FailureCollector,
    ) {
        val id = node.id.truncateForError()
        checkOutput(index, node, into)
        val operation = node.operation
        if (operation.isNullOrBlank()) {
            into.add(
                Validation.DML_OPERATION_REQUIRED,
                "nodes[$index].operation",
                "DML node '$id' declares no 'operation'; declare one of ${DmlOperation.WIRE_VALUES}.",
                mapOf("node" to id, "allowed" to DmlOperation.WIRE_VALUES),
            )
            return
        }
        when (DmlOperation.fromWireOrNull(operation)) {
            DmlOperation.INSERT -> {
                checkKey(index, node, into)
                checkInput(index, node, pipeline, staged, into)
            }

            DmlOperation.UPDATE, DmlOperation.DELETE -> {
                forbidInsertField(index, node, "key", node.key != null, Validation.DML_KEY_INVALID, into)
                forbidInsertField(index, node, "input", node.input != null, Validation.DML_INPUT_INVALID, into)
            }

            null -> {
                into.add(
                    Validation.DML_OPERATION_INVALID,
                    "nodes[$index].operation",
                    "DML node '$id' operation '${operation.truncateForError()}' is not one of ${DmlOperation.WIRE_VALUES}.",
                    mapOf("node" to id, "value" to operation.truncateForError(), "allowed" to DmlOperation.WIRE_VALUES),
                )
            }
        }
    }

    /** A DML result goes to `tempdb`, `caller` or `discard` — explicitly (§4.4); never to a write-back. */
    private fun checkOutput(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        val id = node.id.truncateForError()
        when (val output = node.output) {
            null -> {
                into.add(
                    Validation.DML_OUTPUT_REQUIRED,
                    "nodes[$index].output",
                    "DML node '$id' declares no 'output'; name where its result rows go: " +
                        "'tempdb' (with 'table'), 'caller' or 'discard'.",
                    mapOf("node" to id, "allowed" to DML_TARGETS),
                )
            }

            is NodeOutput.Datasource -> {
                into.add(
                    Validation.DML_HAS_OUTPUT,
                    "nodes[$index].output.target",
                    "DML node '$id' targets a datasource; a DML result goes to 'tempdb', 'caller' or 'discard' " +
                        "(the node writes through its own statement, never a write-back).",
                    mapOf("node" to id, "target" to output.target.wire, "allowed" to DML_TARGETS),
                )
            }

            is NodeOutput.Tempdb -> {
                if (output.table.isBlank()) {
                    into.add(
                        Validation.OUTPUT_TABLE_MISSING,
                        "nodes[$index].output.table",
                        "A tempdb output requires 'table' — it is the name downstream nodes query.",
                        mapOf("node" to id),
                    )
                }
            }

            NodeOutput.Caller, NodeOutput.Discard -> {
                // No companion fields; §12.3's multiple_caller_nodes counts a DML caller like any other.
            }
        }
    }

    private fun checkKey(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        val id = node.id.truncateForError()
        val key = node.key
        val problem =
            when {
                key == null -> "declares no 'key'; an insert names its one physical key column and its type"
                key.column.isNullOrBlank() -> "declares no 'key.column'"
                !KEY_COLUMN.matches(key.column) -> "names key column '${key.column.truncateForError()}', which is not an SQL identifier"
                key.logicalType == null -> "declares key type '${key.type.truncateForError()}'; supported: ${SUPPORTED_KEY_TYPES}"
                else -> return
            }
        into.add(
            Validation.DML_KEY_INVALID,
            "nodes[$index].key",
            "DML insert node '$id' $problem.",
            mapOf("node" to id, "supported_types" to SUPPORTED_KEY_TYPES),
        )
    }

    private fun checkInput(
        index: Int,
        node: Node,
        pipeline: Pipeline,
        staged: Lazy<StagedTables>,
        into: FailureCollector,
    ) {
        val id = node.id.truncateForError()
        val input = node.input
        val parameters = input?.parameters
        val table = input?.table
        val problem =
            when {
                input == null -> {
                    "declares no 'input'; an insert reads either declared 'parameters' (one row) or a staged 'table'"
                }

                (parameters == null) == (table == null) -> {
                    "must declare exactly one of 'input.parameters' and 'input.table'"
                }

                parameters != null -> {
                    parameterProblem(parameters, pipeline)
                }

                table.isNullOrBlank() -> {
                    "declares a blank 'input.table'"
                }

                table !in staged.value.visibleFrom(node.id) -> {
                    "reads tempdb table '${table.truncateForError()}', which no node this one depends on stages"
                }

                else -> {
                    null
                }
            } ?: return
        into.add(
            Validation.DML_INPUT_INVALID,
            "nodes[$index].input",
            "DML insert node '$id' $problem.",
            mapOf("node" to id),
        )
    }

    /** The scalar input names declared parameters, each once (§4.4: request parameters keep their scalar contract). */
    private fun parameterProblem(
        parameters: List<String>,
        pipeline: Pipeline,
    ): String? {
        val duplicates =
            parameters
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val undeclared = parameters.filterNot { it in pipeline.parameters }
        return when {
            duplicates.isNotEmpty() -> "lists input parameter '${duplicates.first().truncateForError()}' more than once"
            undeclared.isNotEmpty() -> "lists '${undeclared.first().truncateForError()}', which is not a declared pipeline parameter"
            else -> null
        }
    }

    @Suppress("LongParameterList") // one refusal shape, five facts it names
    private fun forbidInsertField(
        index: Int,
        node: Node,
        field: String,
        present: Boolean,
        code: String,
        into: FailureCollector,
    ) {
        if (!present) return
        into.add(
            code,
            "nodes[$index].$field",
            "DML node '${node.id.truncateForError()}' is an ${node.operation} and declares '$field'; only an insert has one.",
            mapOf("node" to node.id.truncateForError(), "operation" to node.operation),
        )
    }

    private fun forbidDmlFields(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        val present =
            buildList {
                if (node.operation != null) add("operation")
                if (node.key != null) add("key")
                if (node.input != null) add("input")
            }
        if (present.isEmpty()) return
        into.add(
            Validation.DML_FIELD_FORBIDDEN,
            "nodes[$index]",
            "${node.type.wire} node '${node.id.truncateForError()}' declares ${present.joinToString()}; only DML nodes have them.",
            mapOf("node" to node.id.truncateForError(), "fields" to present),
        )
    }

    /**
     * `discard` is a DML result target only. A DDL node with any output already fails
     * `ddl_has_output` (§12.4), so it is not reported twice here.
     */
    private fun forbidDiscard(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        if (node.output != NodeOutput.Discard || node.type == NodeType.DDL) return
        into.add(
            Validation.OUTPUT_TARGET_INVALID,
            "nodes[$index].output.target",
            "Output target 'discard' is for DML nodes only; ${node.type.wire} node '${node.id.truncateForError()}' cannot discard.",
            mapOf("node" to node.id.truncateForError(), "value" to OutputTarget.DISCARD.wire),
        )
    }

    private val DML_TARGETS = listOf(OutputTarget.TEMPDB.wire, OutputTarget.CALLER.wire, OutputTarget.DISCARD.wire)
    private val SUPPORTED_KEY_TYPES = DmlKey.SUPPORTED_TYPES.map { it.wire }
}

/**
 * Run admission's re-application of [DmlRules] (write-transactions §4 row 1): the executor
 * calls this on the pinned body BEFORE any business write, so a stored version whose DML shape
 * predates the rule — an operation-less node above all — is refused with the same catalog code
 * save-time validation would give, instead of running under the old count-only contract.
 */
object DmlShapeCheck {
    fun failures(pipeline: Pipeline): List<ValidationFailure> = FailureCollector().also { DmlRules.check(pipeline, it) }.toResult().failures
}
