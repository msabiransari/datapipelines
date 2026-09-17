package co.datapipelines.executor

import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The catalogued refusals [NodeRunner] raises before or at CONNECT (§13.4): a datasource the
 * workspace cannot see, one that is readonly now, and a readonly write-back target. Their
 * messages are the same ones the write-back shell raises, so an author reads one sentence
 * whichever side of the source query the refusal lands on.
 *
 * Extracted from the runner for its size ceiling; nothing here touches runner state.
 */
internal object NodeRefusals {
    fun datasourceNotFound(name: String) =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.DATASOURCE_NOT_FOUND,
            message = "Datasource '$name' is not registered in this environment.",
            details = mapOf("datasource" to name),
        )

    /**
     * §13.4 sibling of `datasource_not_found`: the datasource resolved at write-time, but its
     * live entry is readonly now — the stored version predates the flag, or the flag flipped
     * between save and run (D10). Same HTTP class and shape as its sibling.
     */
    fun datasourceReadonly(
        name: String,
        type: NodeType,
    ) = DatapipelinesException(
        code = PipelineErrorCodes.Node.DATASOURCE_READONLY,
        message =
            "Datasource '$name' is readonly — its ${type.wire} use is forbidden " +
                "(the flag was set after this pipeline version was saved, or the version predates it).",
        details = mapOf("datasource" to name, "node_type" to type.wire),
    )

    /**
     * The write-back shape of [datasourceReadonly] (§13.4, 020 F9): raised at the CONNECT-phase
     * pre-check of a DQL node's `output.target: "datasource"`, with the same message the
     * write-back shell's own authoritative check raises — the author gets the identical error
     * either side of the source query.
     */
    fun writebackTargetReadonly(target: NodeOutput.Datasource) =
        DatapipelinesException(
            code = PipelineErrorCodes.Node.DATASOURCE_READONLY,
            message =
                "Write-back target datasource '${target.datasource}' is readonly — writing '${target.table}' to it is forbidden " +
                    "(the flag was set after this pipeline version was saved, or the version predates it).",
            details = mapOf("datasource" to target.datasource, "table" to target.table),
        )
}
