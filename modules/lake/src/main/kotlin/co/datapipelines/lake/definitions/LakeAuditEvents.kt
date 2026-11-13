package co.datapipelines.lake.definitions

/**
 * The managed lake definitions' audit event names (enums.md §15) — wire values in `audit_log.event`, held to the doc
 * by `LakeAuditEventsSpecDriftTest`. A typo here would write rows no query finds, and nothing else would fail.
 * Details carry the table id and name, the version, the operation id, the surface, a code and a boolean: never a body,
 * a location or a credential.
 */
object LakeAuditEvents {
    /** A definition version went DRAFT → RELEASED (the release request, before its apply). */
    const val RELEASED = "lake.definition.released"

    /** This environment's table now holds a released definition (the applied stamp's transaction). */
    const val APPLIED = "lake.definition.applied"

    /** An apply attempt reached a definite negative verdict: drift, a reviewed or breaking change, an Iceberg refusal. */
    const val APPLY_FAILED = "lake.definition.apply_failed"

    /** A RELEASED definition version went → DISCARDED (the discard's transaction, after the pointer's recompute). */
    const val DISCARDED = "lake.definition.discarded"

    /** A table's DRAFT was deleted, with the never-released table's row when no version was left (its transaction). */
    const val DRAFT_PURGED = "lake.definition.draft_purged"

    /** Every lake.definition.* event this module emits — exactly what §15 documents. */
    val ALL: Set<String> = setOf(RELEASED, APPLIED, APPLY_FAILED, DISCARDED, DRAFT_PURGED)
}
