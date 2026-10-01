package co.datapipelines.web.visualizations

import co.datapipelines.web.pipelines.LifecycleVerbs

/**
 * The visualization family's audit event names (enums.md §15) — wire values in `audit_log.event`, held to the
 * doc by `VisualizationAuditEventsSpecDriftTest`. A typo here would write rows no query finds, and nothing else
 * would fail. The five human verbs and the release are [LifecycleVerbs.FamilyAuditEvents]'s (#332); the transfer
 * pair moved here from [VisualizationTransferController] so ONE object holds the family's whole vocabulary.
 */
object VisualizationAuditEvents {
    private val lifecycle = LifecycleVerbs.VISUALIZATION_EVENTS

    /** enums.md §15 — the transfer's audit events (the pipelines' lifecycle shape; #10 L1c). */
    const val EXPORTED = "visualization.exported"
    const val IMPORTED = "visualization.imported"

    /** The five human verbs' events — the drift guards' non-vacuity floor. */
    val FIVE: Set<String> =
        setOf(
            lifecycle.versionDiscarded,
            lifecycle.versionRestored,
            lifecycle.versionPurged,
            lifecycle.entityPurged,
            lifecycle.currentSwitched,
        )

    /** Every visualization.* event the surfaces can emit — exactly what §15 documents. */
    val ALL: Set<String> = FIVE + lifecycle.versionReleased + setOf(EXPORTED, IMPORTED)
}
