package co.datapipelines.web.ui

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * How many times each VERSION of a pipeline has run (106, the acting column's Versions tab).
 *
 * "v3 · draft · 2 runs" is the fact that decides whether a draft is safe to discard, and it is
 * the one number the version list could not previously show. A per-row `COUNT(*)` would be one
 * query per version; this is one `GROUP BY` for the whole list.
 *
 * The query lives web-side, beside [PipelineNames], for the same reason that one does:
 * `pipeline_executions` belongs to `dag` and `pipeline_versions` to `pipeline-contract`, and
 * neither module may reach into the other. The join is a rendering concern and it stays here.
 *
 * Scoped by pipeline id only — an execution inherits its pipeline's workspace
 * (`ExecutionRepository`'s §5.3 note), so the caller having resolved the pipeline in the
 * active workspace is what scopes this.
 */
class PipelineRunStats(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** version → run count, for every version that has ever run. Versions with none are absent. */
    fun runsByVersion(pipelineId: UUID): Map<Int, Int> =
        jdbc
            .query(
                "SELECT pipeline_version, COUNT(*) AS runs FROM pipeline_executions" +
                    " WHERE pipeline_id = :id GROUP BY pipeline_version",
                mapOf("id" to pipelineId),
            ) { rs, _ -> rs.getInt("pipeline_version") to rs.getInt("runs") }
            .toMap()

    /** Total executions of the pipeline — the Runs tab's count, cheaper than listing them. */
    fun totalRuns(pipelineId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pipeline_executions WHERE pipeline_id = :id",
            mapOf("id" to pipelineId),
            Int::class.java,
        ) ?: 0
}
