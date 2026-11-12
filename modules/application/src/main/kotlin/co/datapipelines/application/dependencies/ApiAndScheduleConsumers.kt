package co.datapipelines.application.dependencies

import co.datapipelines.pipeline.EndpointVersionConsumers
import co.datapipelines.pipeline.EndpointVersionPin
import co.datapipelines.pipeline.ScheduleConsumers
import co.datapipelines.pipeline.SchedulePin
import co.datapipelines.pipeline.inverse.InverseRow
import co.datapipelines.pipeline.inverse.InverseScope
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The pipeline aggregate's answer to "which API versions pin this release?" (#786 B.4; record §14, A39): the
 * [EndpointVersionConsumers] port as an adapter over the pipeline family's API arm (#877 §C — `findIncoming` paged to
 * exhaustion), composed in `application` as [DashboardPipelineConsumers] is. Live = DRAFT or RELEASED, the noncurrent
 * versions and a disabled entity's included; DISCARDED protects nothing — on BOTH questions (an API version is never
 * restored, so the arm's ANY is its LIVE). Path, then version order.
 */
class EndpointVersionPipelineConsumers(
    private val incoming: IncomingRows,
) : EndpointVersionConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<EndpointVersionPin> =
        pins(incoming.rows(workspaceId, InverseArm.PIPELINE_FROM_API_ENDPOINT, pipelineName, version, InverseScope.LIVE))

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<EndpointVersionPin> =
        pins(incoming.rows(workspaceId, InverseArm.PIPELINE_FROM_API_ENDPOINT, pipelineName, null, InverseScope.LIVE))

    private fun pins(rows: List<InverseRow>): List<EndpointVersionPin> =
        rows
            .map { row ->
                EndpointVersionPin(
                    path = row.dependentName,
                    version = row.dependentVersion,
                    status = checkNotNull(row.status) { "an API version has a status" },
                    pipelineVersion = checkNotNull(row.storedVersion) { "an API version stores its pipeline version" },
                )
            }.sortedWith(compareBy<EndpointVersionPin> { it.path }.thenBy { it.version })
}

/**
 * The pipeline aggregate's answer to "which schedules run this release?" (#786 B.4; record §14, A39): the
 * [ScheduleConsumers] port as an adapter over the pipeline family's schedule arm (#877 §C — `findIncoming` paged to
 * exhaustion; the arm's query is [ScheduleTargetQueries], SQL in `application` so it takes no scheduler type).
 *
 * Every live (not deleted) schedule counts: paused and blocked ones too, since a resume or an unblock would run the
 * version again. A schema-2 payload (#786 D.1) protects exactly the version its `version` field pins. A schema-1 payload
 * names no version — it followed the pipeline's CURRENT, and the upgrade's conversion (D.5) rewrites every live one
 * before serving — so one that is still there (planted, or a deployment mid-upgrade; its edge says `followsCurrent`)
 * protects the version CURRENT names now, and nothing while CURRENT is NULL; the entity scope counts every schedule
 * regardless, because the entity purge removes the name it targets. Both questions read the entity-wide arm and filter
 * here: an exact-version inverse never matches a schema-1 row, which stores no version.
 */
class SchedulePipelineConsumers(
    private val incoming: IncomingRows,
    private val jdbc: NamedParameterJdbcTemplate,
) : ScheduleConsumers {
    override fun liveVersionPins(
        workspaceId: UUID,
        pipelineName: String,
        version: Int,
    ): List<SchedulePin> = resolved(workspaceId, pipelineName).filter { it.pipelineVersion == version }

    override fun anyVersionPins(
        workspaceId: UUID,
        pipelineName: String,
    ): List<SchedulePin> = resolved(workspaceId, pipelineName)

    /** Each live schedule targeting [pipelineName] with the version it runs, by schedule name. */
    private fun resolved(
        workspaceId: UUID,
        pipelineName: String,
    ): List<SchedulePin> {
        val rows = incoming.rows(workspaceId, InverseArm.PIPELINE_FROM_SCHEDULE, pipelineName, null, InverseScope.LIVE)
        val current by lazy { currentVersion(workspaceId, pipelineName) }
        return rows
            .map { row -> SchedulePin(row.dependentName, if (row.followsCurrent) current else row.storedVersion) }
            .sortedBy { it.name }
    }

    /** The pipeline's CURRENT pointer, null when it has none (or no such pipeline) — read only for a schema-1 row. */
    private fun currentVersion(
        workspaceId: UUID,
        pipelineName: String,
    ): Int? =
        jdbc
            .query(
                "SELECT current_version FROM pipelines WHERE workspace_id = :ws AND name = :name",
                mapOf("ws" to workspaceId, "name" to pipelineName),
            ) { rs, _ -> rs.getInt("current_version").takeUnless { rs.wasNull() } }
            .firstOrNull()

    companion object {
        /** A plain positive decimal integer: no sign, no leading zero, no fraction or exponent. */
        private val POSITIVE_DECIMAL = Regex("[1-9][0-9]*")

        /**
         * The stored schema-2 `version` number's text as the exact pin it names, or null when it names none: the
         * executor's grammar (a positive JSON integer that fits an Int). [String.toIntOrNull] answers null past Int's
         * range, so an overlong value never throws and never wraps.
         */
        fun exactPin(text: String?): Int? = text?.takeIf(POSITIVE_DECIMAL::matches)?.toIntOrNull()
    }
}
