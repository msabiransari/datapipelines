package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseSql
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The schedule → pipeline inverse arm's native bounded query (#877 §B) over `schedules`, read by SQL here — beside
 * [SchedulePipelineConsumers], for the same reason: the scheduler core names nothing of the pipeline contract
 * (`SchedulerBoundaryTest`), and the executor's payload vocabulary arrives as a [ScheduleTargetShape].
 *
 * One page of the live (not deleted) schedules of the workspace whose `target_ref` is the shape's prefix + the target
 * key, each ONE occurrence at the shape's source path, in the inverse total order (schedule id, then path), `limit + 1`
 * rows. A schedule is unversioned (version 0, no lifecycle) and every scope reads the same rows — live until soft-deleted,
 * paused and blocked ones too, as [SchedulePipelineConsumers] counts them.
 *
 * The stored version is the payload's version field when the payload is at the shape's exact schema or later and the
 * field is a positive JSON integer inside Int's range ([InverseSql.jsonInt]); NULL otherwise — a schema-1 payload follows
 * the pipeline's current pointer and stores no version, so an exact target never matches it. Every value, the field name
 * and the path included, is a bind.
 */
class ScheduleTargetQueries(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findTargetOccurrences(
        request: InverseRequest,
        shape: ScheduleTargetShape,
    ): List<InverseRow> {
        val number = InverseSql.jsonInt("s.payload_json -> CAST(:versionField AS text)")
        val stored = "(CASE WHEN s.payload_schema_version >= :exactFromSchema AND $number >= 1 THEN $number END)"
        val exact = if (request.targetVersion == null) "" else "AND $stored = :targetVersion"
        val occurrences =
            """
            SELECT s.id::text AS dependent_id, s.name AS dependent_name, 0 AS dependent_version,
                   NULL::text AS dependent_status, CAST(:sourcePath AS text) AS source_path,
                   $stored AS stored_version, NULL::text AS element_id
              FROM schedules s
             WHERE s.workspace_id = :workspaceId AND s.target_ref = :targetRef AND s.deleted_at IS NULL
               $exact
            """.trimIndent()
        val binds =
            mapOf(
                "workspaceId" to request.workspaceId,
                "targetRef" to shape.targetRefPrefix + request.targetKey,
                "versionField" to shape.versionField,
                "sourcePath" to shape.sourcePath,
                "exactFromSchema" to shape.exactFromSchema,
                "targetVersion" to request.targetVersion,
            )
        return InverseSql.page(jdbc, occurrences, binds, request)
    }
}
