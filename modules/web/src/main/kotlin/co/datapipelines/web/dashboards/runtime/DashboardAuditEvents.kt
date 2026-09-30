package co.datapipelines.web.dashboards.runtime

/**
 * The dashboard runtime's audit event names (enums.md §15) — wire values in `audit_log.event`, held to the doc by
 * `DashboardAuditEventsSpecDriftTest`. A typo here would write rows no query finds, and nothing else would fail.
 */
object DashboardAuditEvents {
    /** One per refresh, awaited, written at its end (Dashboards §5.7). */
    const val REFRESH = "dashboard.refresh"

    val ALL: Set<String> = setOf(REFRESH)
}
