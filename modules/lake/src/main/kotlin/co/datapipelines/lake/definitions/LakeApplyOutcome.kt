package co.datapipelines.lake.definitions

import co.datapipelines.pipeline.PipelineErrorCodes

/**
 * What one apply (or verify) attempt did to a managed table (lake.md §3). Each outcome names the state the row is
 * left in; none is thrown, because a release's landing stands whatever its apply reaches (dp-lake design record §7).
 */
sealed interface LakeApplyOutcome {
    /** The catalogued code a surface reports for this outcome; null for success. */
    val code: String?

    /** The table holds [version]; [committed] = this attempt made an Iceberg commit (false: S already equalled intent). Row `applied`. */
    data class Applied(
        val version: Int,
        val committed: Boolean,
    ) : LakeApplyOutcome {
        override val code: String? = null
    }

    /** A definite negative verdict, recorded: row `failed` with [error] in `apply_error_json`. No commit was made. */
    data class Failed(
        val version: Int,
        override val code: String,
        val error: Map<String, Any?>,
    ) : LakeApplyOutcome

    /** A lease could not be taken within the wait ([reason]: `namespace`, `table` or `deadline`). Nothing changed. */
    data class Busy(
        val reason: String,
    ) : LakeApplyOutcome {
        override val code: String = PipelineErrorCodes.Lake.DEFINITION_TABLE_BUSY
    }

    /**
     * Row `pending`: the attempt stopped before any commit ([code] names why: the fence, restoring mode, a lost lease),
     * or a verify found that the interrupted attempt committed nothing ([code] null).
     */
    data class Pending(
        override val code: String?,
    ) : LakeApplyOutcome

    /**
     * A commit's outcome is unknown ([code] `lake.catalog.outcome_unknown`). The row stays `applying` for
     * [LakeDefinitionApplier.verifyApplying].
     */
    data class Unknown(
        override val code: String,
    ) : LakeApplyOutcome

    /** Nothing to do: the version is not the table's current one, or the row is not in a state this call moves. */
    data class NotApplicable(
        val state: LakeApplyState?,
        val currentVersion: Int?,
    ) : LakeApplyOutcome {
        override val code: String? = null
    }

    /** The attempt's stamp changed no row: a newer attempt owns the table. Nothing was recorded by this one. */
    data object Superseded : LakeApplyOutcome {
        override val code: String? = null
    }
}
