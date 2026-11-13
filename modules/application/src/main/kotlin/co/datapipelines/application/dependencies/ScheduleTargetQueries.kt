package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.inverse.InverseRequest
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseSql
import org.springframework.jdbc.core.RowMapper
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
 * field's text is the executor's exact-pin grammar ([SchedulePipelineConsumers.exactPin], read in SQL); NULL otherwise.
 * A schema-1 payload follows the pipeline's current pointer and stores no version, so an exact target never matches it;
 * its row carries `followsCurrent` so the port adapter can resolve CURRENT as [SchedulePipelineConsumers] always has.
 * Every value, the field name and the path included, is a bind.
 */
class ScheduleTargetQueries(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findTargetOccurrences(
        request: InverseRequest,
        shape: ScheduleTargetShape,
    ): List<InverseRow> {
        val stored = exactPin("s.payload_json -> CAST(:versionField AS text)")
        val exact = if (request.targetVersion == null) "" else "AND $stored = :targetVersion"
        val occurrences =
            """
            SELECT s.id::text AS dependent_id, s.name AS dependent_name, 0 AS dependent_version,
                   NULL::text AS dependent_status, CAST(:sourcePath AS text) AS source_path,
                   $stored AS stored_version, NULL::text AS element_id,
                   s.payload_schema_version < :exactFromSchema AS follows_current
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
        return InverseSql.page(jdbc, occurrences, binds, request, FOLLOWS_CURRENT)
    }

    private companion object {
        /**
         * The executor's exact-pin grammar in SQL — the one [SchedulePipelineConsumers.exactPin] and the outgoing
         * extractor (`PipelineGraphTarget`) apply: a JSON number whose text is a plain positive decimal (no sign, leading
         * zero, fraction or exponent) inside Int's range, at the shape's exact schema or later; NULL otherwise. Nested
         * CASEs order the checks, so the cast only ever sees ten digits or fewer.
         */
        fun exactPin(field: String): String {
            val text = "($field #>> '{}')"
            return "(CASE WHEN s.payload_schema_version >= :exactFromSchema AND jsonb_typeof($field) = 'number' THEN " +
                "CASE WHEN $text ~ '^[1-9][0-9]{0,9}$' THEN " +
                "CASE WHEN CAST($text AS bigint) <= $INT_MAX THEN CAST($text AS int) END END END)"
        }

        const val INT_MAX = Int.MAX_VALUE

        /** The shared row plus the schema-1 flag: a payload before the exact schema follows the pipeline's CURRENT. */
        val FOLLOWS_CURRENT =
            RowMapper { rs, index ->
                checkNotNull(InverseSql.ROW_MAPPER.mapRow(rs, index)).copy(followsCurrent = rs.getBoolean("follows_current"))
            }
    }
}
