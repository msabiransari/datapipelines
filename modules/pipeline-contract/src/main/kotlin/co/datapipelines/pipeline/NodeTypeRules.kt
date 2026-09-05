package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.4 — the rules tying a node's `type` to its `output` block.
 *
 * Three §12.4 codes are **not** here, and cannot be: `type_invalid`,
 * `output_target_invalid` and `output_mode_invalid` are verdicts on wire values that the
 * typed model has no way to hold. [PipelineDeserializer]'s pre-scan raises them, with the
 * same codes, before binding — see its KDoc.
 */
internal object NodeTypeRules {
    fun check(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        pipeline.nodes.forEachIndexed { index, node ->
            when (node.type) {
                NodeType.DQL -> checkDqlOutput(index, node, into)

                NodeType.DML -> forbidOutput(index, node, Validation.DML_HAS_OUTPUT, into)

                NodeType.DDL -> forbidOutput(index, node, Validation.DDL_HAS_OUTPUT, into)

                // A PIPELINE node's output legality is §12.9's job (CompositionRules): whether
                // the block may exist at all depends on the pinned child's caller node. The
                // block's companion-field shape is the standard §4.7 one, so CompositionRules
                // reuses [checkOutputCompanions] below.
                NodeType.PIPELINE -> Unit

                // §12.10 (CalculatorRules) owns the whole shape of a CALCULATOR node — `output`
                // is refused there together with `source` and `template`, in ONE failure that
                // names all three. Reporting `output` twice, once from each rule group, would
                // hand an author two errors for one edit.
                NodeType.CALCULATOR -> Unit
            }
        }
    }

    /**
     * The §4.7 companion-field checks, exposed for [CompositionRules]: a PIPELINE node's
     * `output` block — when §12.9 permits one — has the same required-companion-field rules as
     * a DQL node's.
     */
    internal fun checkOutputCompanions(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) = checkDqlOutput(index, node, into)

    /**
     * A DML/DDL node must carry no `output` block: its side effect *is* the output (§4.4,
     * §4.5). §9.2 states the same check from the caller-node angle
     * (`non_dql_caller_target`) and says explicitly it is the same check — so the §12.4 code
     * is what gets emitted, and the alias never does.
     */
    private fun forbidOutput(
        index: Int,
        node: Node,
        code: String,
        into: FailureCollector,
    ) {
        val output = node.output ?: return
        into.add(
            code,
            "nodes[$index].output",
            "${node.type.wire} node '${node.id.truncateForError()}' declares an output block " +
                "(target '${output.target.wire}'); only DQL nodes have outputs.",
            mapOf("node" to node.id.truncateForError(), "target" to output.target.wire),
        )
    }

    /**
     * The required-companion-field rules of §12.4.
     *
     * A blank value and an absent key are the same failure here: [NodeOutputModule] binds an
     * absent `table` to `""` precisely so this check — not a Jackson exception — is what the
     * author sees, and an author who wrote `"table": ""` has the same broken pipeline.
     */
    private fun checkDqlOutput(
        index: Int,
        node: Node,
        into: FailureCollector,
    ) {
        when (val output = node.output) {
            is NodeOutput.Tempdb -> {
                if (output.table.isBlank()) {
                    into.add(
                        Validation.OUTPUT_TABLE_MISSING,
                        "nodes[$index].output.table",
                        "A tempdb output requires 'table' — it is the name downstream nodes query.",
                        mapOf("node" to node.id.truncateForError()),
                    )
                }
            }

            is NodeOutput.Datasource -> {
                checkDatasourceOutput(index, node, output, into)
            }

            NodeOutput.Caller, null -> {
                // A caller target has no companion fields; a null output on a DQL node is
                // unreachable (D1 resolves it to Caller at deserialization).
            }
        }
    }

    private fun checkDatasourceOutput(
        index: Int,
        node: Node,
        output: NodeOutput.Datasource,
        into: FailureCollector,
    ) {
        val missing =
            buildList {
                if (output.datasource.isBlank()) add("datasource")
                if (output.table.isBlank()) add("table")
            }
        if (missing.isEmpty()) return
        into.add(
            Validation.OUTPUT_DATASOURCE_MISSING,
            "nodes[$index].output",
            "A datasource output requires 'datasource' and 'table'; missing: ${missing.joinToString()}.",
            mapOf("node" to node.id.truncateForError(), "missing" to missing),
        )
    }
}
