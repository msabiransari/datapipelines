package co.datapipelines.web.schedules

/**
 * The schedule audit event names (enums.md §15, "Schedule audit events"; #9) — wire values written
 * into `audit_log.event` by [SchedulesController]. A person's acts only: what a schedule does when
 * it fires is its runs' trail, not an audit row. `ScheduleAuditEventsSpecDriftTest` holds these
 * equal to the documented table, the `MailAuditEvents` pattern.
 */
object ScheduleAuditEvents {
    const val CREATED = "schedule.created"
    const val UPDATED = "schedule.updated"
    const val PAUSED = "schedule.paused"
    const val RESUMED = "schedule.resumed"
    const val UNBLOCKED = "schedule.unblocked"
    const val DELETED = "schedule.deleted"
    const val RUN_REQUESTED = "schedule.run_requested"

    val ALL: Set<String> = setOf(CREATED, UPDATED, PAUSED, RESUMED, UNBLOCKED, DELETED, RUN_REQUESTED)
}
