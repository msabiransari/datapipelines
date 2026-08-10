package co.datapipelines.staging

/**
 * The staging domain's error codes, transcribed from the single system-wide catalog in
 * [pipeline-contract §13.5](../../../../../../../docs/pipeline-contract.md).
 *
 * pipeline-contract.md is the sole authority for concrete error codes (README house rules).
 * `staging` cannot depend on `pipeline-contract` (module-structure.md §4.2 — `staging` →
 * `typesystem` only), so the codes are re-declared here rather than imported. The literals
 * MUST match §13.5 exactly; `StagingErrorCodesSpecDriftTest` reads the document and fails if
 * this object and the spec table ever disagree in either direction — the same discipline
 * `PipelineErrorCodes` uses one layer up.
 *
 * Codes are `pipeline.staging.{failure}`, lowercase, additive — never reused, never renamed.
 */
object StagingErrorCodes {
    /** A source value exceeds the staged column's capacity (staging.md §4.3). HTTP 500. */
    const val VALUE_OVERFLOW = "pipeline.staging.value_overflow"

    /** Source DECIMAL precision exceeds H2's ceiling (staging.md §5.2). Raised by `H2EgressMapper`. */
    const val PRECISION_OVERFLOW = "pipeline.staging.precision_overflow"

    /** The requested engine is not available on the classpath (staging.md §3.1). */
    const val ENGINE_UNAVAILABLE = "pipeline.staging.engine_unavailable"

    /** The staging instance could not be created (staging.md §3.1). */
    const val CREATION_FAILED = "pipeline.staging.creation_failed"

    /** The table-drop sweep or the connection close failed (staging.md §3.4); logged, never rethrown. */
    const val CLEANUP_FAILED = "pipeline.staging.cleanup_failed"

    /** The measured staged footprint exceeds the effective memory budget (staging.md §8.2). */
    const val MEMORY_LIMIT_EXCEEDED = "pipeline.staging.memory_limit_exceeded"

    /** A source column label fails validation or duplicates another in the same result set (§4.5). */
    const val INVALID_COLUMN_NAME = "pipeline.staging.invalid_column_name"

    /** `CREATE TABLE` targets a name already staged in this execution (staging.md §4.5). */
    const val TABLE_ALREADY_EXISTS = "pipeline.staging.table_already_exists"
}
