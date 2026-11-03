package co.datapipelines.lake.definitions

import java.util.UUID

/** A test seam: runs right after an apply's commit, before its stamp (the crash-between case, lake.md §3). */
fun interface LakeApplyHooks {
    fun afterCommit(
        tableId: UUID,
        operationId: UUID,
    )

    companion object {
        val NONE = LakeApplyHooks { _, _ -> }
    }
}
