package co.datapipelines.executor

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The verdict of one readonly-backstop live read (044's fail-closed semantics,
 * datasources.md §5.7 layer 2). The three cases the 020 code conflated into one nullable —
 * every value below is a DIFFERENT operational answer, not a shade of "no signal".
 */
enum class ReadonlySignal {
    /** The live row is writable — proceed with the write. */
    WRITABLE,

    /** The live row is readonly — refuse with `pipeline.node.datasource_readonly`. */
    READONLY,

    /**
     * No live row — refuse with `pipeline.node.datasource_not_found`. A row-level soft-delete
     * (`is_deleted = TRUE` by manual SQL — the exact D10 channel) makes the live read null
     * while the cached entry and a warm pool would happily execute the write.
     */
    ABSENT,
}

/**
 * Workspaces design §6 layer 2a's read, shared by both raise sites — [NodeRunner]'s DML/DDL
 * source check and [JdbcWritebackRunner]'s output-target check — so the fail-closed decision
 * exists in exactly one place (020 F2/F3/F7).
 *
 * **The rule this object enforces: "I could not read the row" is never "there is no
 * restriction."** A security control must fail closed:
 *
 * - flag readonly → [ReadonlySignal.READONLY] (the caller refuses, naming the datasource)
 * - flag writable → [ReadonlySignal.WRITABLE]
 * - no live row → [ReadonlySignal.ABSENT] (the caller refuses as not-found)
 * - the read itself failed → throws, refusing the write with an error naming the **metadata**
 *   database — never the healthy TARGET datasource. 020's shape let a metadata-DB outage
 *   surface as `datasource_connection_failed` (write-back path: `writeback_failed`) blaming
 *   the target, sending the operator to the wrong database.
 *
 * The carried code is `pipeline.execution.aborted` — the catalog's "executor-internal failure
 * with no more specific code" — because the failure is the executor's own dependency on
 * metadata-DB availability, which this round also documents in datasources.md §5.7.
 */
object ReadonlyBackstop {
    /** Reads the live readonly signal for [name], translating a failed read into the refusal. */
    fun signal(
        registry: DatasourceRegistry,
        name: String,
    ): ReadonlySignal =
        try {
            // null: no live row — `is_readonly` is NOT NULL (V4), so absent is the only null.
            when (registry.isReadonlyLive(name)) {
                true -> ReadonlySignal.READONLY
                false -> ReadonlySignal.WRITABLE
                null -> ReadonlySignal.ABSENT
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw metadataUnreadable(name, e)
        }

    /**
     * The metadata-DB-down refusal: the TARGET datasource is healthy and unnamed as a cause —
     * it rides in `details` as context only. The original failure is the cause, so the
     * operator's trail leads to the metadata database.
     */
    private fun metadataUnreadable(
        name: String,
        cause: Exception,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.ABORTED,
            message =
                "The readonly backstop could not read the metadata database to verify datasource " +
                    "'$name' — refusing the write rather than guessing the flag (the datasource itself " +
                    "was not contacted and may be healthy).",
            details = mapOf("datasource" to name, "unavailable" to "metadata_db"),
            cause = cause,
        )
}
