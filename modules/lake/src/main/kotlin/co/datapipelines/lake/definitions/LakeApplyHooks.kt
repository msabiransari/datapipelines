package co.datapipelines.lake.definitions

import co.datapipelines.lake.LeaseHandle
import java.util.UUID

/** A test seam: runs right after an apply's commit, before its stamp (the crash-between case, lake.md §3). */
fun interface LakeApplyHooks {
    fun afterCommit(
        tableId: UUID,
        operationId: UUID,
    )

    /**
     * A test seam: the last step of closing one of an apply's catalog sessions, the one under [lease] (#822). Iceberg's
     * `JdbcCatalog.close()` suppresses its own close failures, so a failing close can be raised only here.
     */
    fun afterSessionClose(lease: LeaseHandle) = Unit

    companion object {
        val NONE = LakeApplyHooks { _, _ -> }
    }
}
