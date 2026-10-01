package co.datapipelines.web.parameters

import co.datapipelines.web.pipelines.LifecycleVerbs

/**
 * The parameter-set family's audit event names (enums.md §15) — wire values in `audit_log.event`, held to the
 * doc by `ParameterSetAuditEventsSpecDriftTest`. A typo here would write rows no query finds, and nothing else
 * would fail. The five human verbs and the release are [LifecycleVerbs.FamilyAuditEvents]'s; the family has no
 * transfer events (its import/export surface predates the transfer audit and emits none — the pipelines mould).
 */
object ParameterSetAuditEvents {
    private val lifecycle = LifecycleVerbs.PARAMETER_SET_EVENTS

    /** The five human verbs' events — the drift guards' non-vacuity floor. */
    val FIVE: Set<String> =
        setOf(
            lifecycle.versionDiscarded,
            lifecycle.versionRestored,
            lifecycle.versionPurged,
            lifecycle.entityPurged,
            lifecycle.currentSwitched,
        )

    /** Every parameter_set.* event the surfaces can emit — exactly what §15 documents. */
    val ALL: Set<String> = FIVE + lifecycle.versionReleased
}
