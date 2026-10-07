package co.datapipelines.pipeline

/**
 * What a restore produced (versioning §3.1): the bumped entity record — whose
 * `current_version` IS the pointer after — and the pointer as the restore statement's own
 * snapshot held it BEFORE the move. The pair every `pipeline.version.restored` audit row
 * carries (enums.md §15, #379); both values come from the statement itself, never a re-read
 * after the fact.
 */
data class PipelineRestoreResult(
    val record: PipelineRecord,
    val currentVersionBefore: Int?,
)
