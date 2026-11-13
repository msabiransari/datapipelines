package co.datapipelines.lake.definitions

/**
 * A managed table's lifecycle (enums.md §46, metadata-db §4.49's CHECK). A namespace takes the same values except
 * [PURGED]. P3 writes [ACTIVE] only; the transitions are P8's and the purge flow's.
 */
enum class LakeTableStatus(
    val wire: String,
) {
    ACTIVE("active"),
    DEPRECATED("deprecated"),
    ARCHIVED("archived"),
    PURGED("purged"),
    ;

    companion object {
        fun fromWire(wire: String): LakeTableStatus = entries.firstOrNull { it.wire == wire } ?: error("unknown lake table status '$wire'")
    }
}

/**
 * This environment's apply state for a managed table (enums.md §47, lake.md §3, metadata-db §4.49): NULL in the
 * database before the first release, then [PENDING] → [APPLYING] → [APPLIED] | [FAILED].
 */
enum class LakeApplyState(
    val wire: String,
) {
    /** A released version waits to be applied (or a retryable attempt gave up: a busy lease, a lost race). */
    PENDING("pending"),

    /** An attempt owns the row (`apply_operation_id`); only that attempt, or `verifyApplying`, moves it on. */
    APPLYING("applying"),

    /** The Iceberg table holds the current definition (`applied_version_here = current_definition_version`). */
    APPLIED("applied"),

    /** The apply refused (a reviewed or breaking change, drift, an invalid body); `apply_error_json` says why. */
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(wire: String): LakeApplyState = entries.firstOrNull { it.wire == wire } ?: error("unknown lake apply state '$wire'")
    }
}

/** The supported-change policy's classes (enums.md §48, lake.md §4): only [COMPATIBLE] is applied automatically. */
enum class LakeChangeClass(
    val wire: String,
) {
    COMPATIBLE("compatible"),
    REVIEWED("reviewed"),
    BREAKING("breaking"),
}
