package co.datapipelines.pipeline

/**
 * Where a DQL node's ResultSet goes — the **flat** sealed interface of
 * [dag-executor §4](../../../../../../../docs/dag-executor.md), which pipeline-contract
 * §17.1 aligns to (SPEC-REVIEW 2.1.10: "dag-executor's flat sealed interface wins").
 *
 * Flat, not a nested `Target` enum plus a bag of nullable fields, because the variants have
 * genuinely different shapes: `caller` takes no fields, `tempdb` takes a table, `datasource`
 * takes three. Modelling them flat makes the illegal combinations unrepresentable and lets
 * the executor's `when` be exhaustive without an `else` branch — so a future `kafka` /`s3`
 * target (§18) is a compile error at every dispatch site rather than a silent fallthrough.
 *
 * ## Absence
 *
 * `null` output means the wire payload carried no `output` block **and** the node is not a
 * DQL node. A DQL node with no `output` block resolves to [Caller] at deserialization time
 * (D1, §4.7) — never later, so nothing downstream re-derives a default.
 */
sealed interface NodeOutput {
    /** The wire discriminator this variant serializes as. */
    val target: OutputTarget

    /**
     * Stage the ResultSet into the per-execution tempdb as [table].
     *
     * A node whose data downstream nodes consume must declare this explicitly (D1) — there
     * is no implicit staging.
     */
    data class Tempdb(
        val table: String,
    ) : NodeOutput {
        override val target: OutputTarget get() = OutputTarget.TEMPDB
    }

    /**
     * Return the ResultSet as the pipeline's result — the **caller node** (§9).
     *
     * At most one node per pipeline may resolve to this; zero is legal (a pure write-back
     * pipeline returns stats only and emits no `data_ready` event).
     */
    data object Caller : NodeOutput {
        override val target: OutputTarget get() = OutputTarget.CALLER
    }

    /**
     * Stream the ResultSet to [table] in the registered datasource [datasource], applying
     * [mode].
     *
     * The target table must already exist — created by a preceding DDL node in the same
     * pipeline, or pre-existing (§4.7).
     */
    data class Datasource(
        val datasource: String,
        val table: String,
        val mode: WriteMode,
    ) : NodeOutput {
        override val target: OutputTarget get() = OutputTarget.DATASOURCE
    }
}

/**
 * A node's execution target: a registered datasource, or the per-execution tempdb
 * (pipeline-contract §4.8, dag-executor §4).
 *
 * The wire form is a plain string, and [Node.source] keeps that string so the JSON model
 * stays a faithful mirror of the document. This type is the *resolved* view the executor
 * consumes; [from] is the whole conversion.
 */
sealed interface NodeSource {
    /** The wire string this source serializes as. */
    val wire: String

    /** A datasource registered in this environment; resolved per-environment (§11.2). */
    data class Datasource(
        val name: String,
    ) : NodeSource {
        override val wire: String get() = name
    }

    /** The reserved literal `"tempdb"` — the in-memory staging database for this execution. */
    data object Tempdb : NodeSource {
        override val wire: String get() = TEMPDB_LITERAL
    }

    companion object {
        /**
         * The reserved source/target literal (§4.8). A datasource may not be registered
         * under this name, and no node id or output table may use it (§12.1).
         */
        const val TEMPDB_LITERAL = "tempdb"

        /** Resolves a wire `source` string. Any value other than `tempdb` is a datasource name. */
        fun from(wire: String): NodeSource = if (wire == TEMPDB_LITERAL) Tempdb else Datasource(wire)
    }
}
