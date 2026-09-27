package co.datapipelines.scheduler

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The by-target schedule read (#259): the reverse lookup the indexed `target_ref` column (B15)
 * exists to answer — which live schedules run a given pipeline.
 *
 * A separate class, beside [ScheduleRepository] rather than inside it, while the scheduler
 * follow-ups lane is in flight on that file; folding it in at that merge is a one-method move.
 * The query reads ONLY ids on the index and maps rows through [ScheduleRepository.findAny], so
 * the schedule row mapping stays in exactly one place. Not a transport surface: callers reach it
 * through [ScheduleService] (module-structure's one-scheduler-type rule); it is public only so a
 * test constructing the service can wire it.
 */
class ScheduleTargetReads(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * The live schedules of [workspaceId] whose `target_ref` names [targetRef], by name, lensed
     * for [viewer] exactly as [ScheduleService.list] lenses its page: a schedule outside the
     * lens answers as absent (auth.md §11A.1) — hidden, never admitted.
     */
    fun listByTarget(
        schedules: ScheduleRepository,
        executors: JobExecutors,
        workspaceId: UUID,
        targetRef: String,
        viewer: TargetViewer,
    ): List<Schedule> {
        val ids =
            jdbc.query(
                "SELECT id FROM schedules WHERE workspace_id = :ws AND target_ref = :ref AND deleted_at IS NULL ORDER BY name",
                mapOf("ws" to workspaceId, "ref" to targetRef),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        val rows = ids.mapNotNull { schedules.findAny(it) }
        if (!viewer.narrowed) return rows
        return rows.groupBy { it.executorId }.flatMap { (executorId, group) ->
            val executor = executors.find(executorId) ?: return@flatMap emptyList()
            executor.visibleTargets(viewer, workspaceId, group.map { it.targetRef }).let { refs ->
                group.filter { it.targetRef in refs }
            }
        }
    }
}
