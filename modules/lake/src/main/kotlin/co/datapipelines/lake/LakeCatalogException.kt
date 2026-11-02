package co.datapipelines.lake

import co.datapipelines.persistence.FailureShape
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * A catalog write the managed lake refused or could not settle — pipeline-contract §13.25. The two refusals come
 * from the fence trigger's SQLSTATEs; [OutcomeUnknown] is not a refusal: the statement may have committed (§8a,
 * E1 #774 case A) and only `settle` (P4) can tell.
 *
 * Messages are static and name no token, row or path. The cause chain is kept for the operator's log, where
 * [FailureShape] reads only its class and SQLSTATE.
 */
sealed class LakeCatalogException(
    code: String,
    message: String,
    cause: Throwable,
) : DatapipelinesException(code = code, message = message, cause = cause) {
    /** `lake.catalog.fenced` (SQLSTATE `DP001`): no live lease of this operation on the written name. */
    class Fenced(
        cause: Throwable,
    ) : LakeCatalogException(
            PipelineErrorCodes.Lake.CATALOG_FENCED,
            "the lake catalog refused a write without a live lease of this operation",
            cause,
        )

    /** `lake.catalog.restoring` (SQLSTATE `DP002`): the catalog is in `restoring` mode. */
    class Restoring(
        cause: Throwable,
    ) : LakeCatalogException(
            PipelineErrorCodes.Lake.CATALOG_RESTORING,
            "the lake catalog is restoring; no catalog write is admitted",
            cause,
        )

    /**
     * `lake.catalog.outcome_unknown`: the catalog connection failed (SQLSTATE class `08`) while a write was in
     * flight. Never retried here — a replay can report a committed write as failed and let the library delete its
     * files (E1 F3). The session that saw it refuses further work.
     */
    class OutcomeUnknown(
        cause: Throwable,
    ) : LakeCatalogException(
            PipelineErrorCodes.Lake.CATALOG_OUTCOME_UNKNOWN,
            "the lake catalog write's outcome is unknown; settle it before any retry",
            cause,
        )

    companion object {
        /** SQLSTATE the fence raises without a live lease (V53 `dp_fence`). */
        const val SQLSTATE_FENCED = "DP001"

        /** SQLSTATE the fence raises in `restoring` mode (V53 `dp_fence`). */
        const val SQLSTATE_RESTORING = "DP002"

        /** SQLSTATE class "connection exception" — the outcome of an in-flight statement is unknown. */
        private const val CONNECTION_CLASS = "08"

        /**
         * The catalogued form of [failure], or null when it is none of the three (the caller rethrows it as is).
         * Reads the first SQLSTATE in the cause chain: Iceberg wraps the driver's `SQLException` in its own
         * unchecked types (`UncheckedSQLException`, `CommitFailedException`), so the top rarely carries it.
         */
        fun classify(failure: Throwable): LakeCatalogException? {
            if (failure is LakeCatalogException) return failure
            val state = FailureShape.sqlState(failure)
            return when {
                state == SQLSTATE_FENCED -> Fenced(failure)
                state == SQLSTATE_RESTORING -> Restoring(failure)
                state.startsWith(CONNECTION_CLASS) -> OutcomeUnknown(failure)
                else -> null
            }
        }
    }
}
