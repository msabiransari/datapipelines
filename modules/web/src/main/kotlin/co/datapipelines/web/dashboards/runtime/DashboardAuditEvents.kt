package co.datapipelines.web.dashboards.runtime

import co.datapipelines.web.pipelines.LifecycleVerbs

/**
 * The dashboard family's audit event names (enums.md §15) — wire values in `audit_log.event`, held to the doc by
 * `DashboardAuditEventsSpecDriftTest`. A typo here would write rows no query finds, and nothing else would fail.
 * The runtime's own event is [REFRESH]; the transfer pair moved here from `DashboardTransferController` and the
 * five human verbs plus the release are [LifecycleVerbs.FamilyAuditEvents]'s (#332), so ONE object holds the
 * family's whole vocabulary.
 */
object DashboardAuditEvents {
    private val lifecycle = LifecycleVerbs.DASHBOARD_EVENTS

    /** One per refresh, awaited, written at its end (Dashboards §5.7). */
    const val REFRESH = "dashboard.refresh"

    /** enums.md §15 — the transfer's audit events (the pipelines' lifecycle shape; #10 L1c). */
    const val EXPORTED = "dashboard.exported"
    const val IMPORTED = "dashboard.imported"

    /** The five human verbs' events — the drift guards' non-vacuity floor. */
    val FIVE: Set<String> =
        setOf(
            lifecycle.versionDiscarded,
            lifecycle.versionRestored,
            lifecycle.versionPurged,
            lifecycle.entityPurged,
            lifecycle.currentSwitched,
        )

    /** Every dashboard.* event the surfaces can emit — exactly what §15 documents. */
    val ALL: Set<String> = FIVE + lifecycle.versionReleased + setOf(REFRESH, EXPORTED, IMPORTED)
}
