package co.datapipelines.lake.definitions

/**
 * The managed lake definitions' audit event names (enums.md §15) — wire values in `audit_log.event`, held to the doc
 * by `LakeAuditEventsSpecDriftTest`. A typo here would write rows no query finds, and nothing else would fail.
 * Details carry the table id and name, the version, the operation id and a code: never a body or a credential.
 */
object LakeAuditEvents {
    /** A definition version went DRAFT → RELEASED (the release request, before its apply). */
    const val RELEASED = "lake.definition.released"

    /** This environment's table now holds a released definition (the applied stamp's transaction). */
    const val APPLIED = "lake.definition.applied"

    /** An apply attempt reached a definite negative verdict: drift, a reviewed or breaking change, an Iceberg refusal. */
    const val APPLY_FAILED = "lake.definition.apply_failed"

    /** Every lake.definition.* event this module emits — exactly what §15 documents. */
    val ALL: Set<String> = setOf(RELEASED, APPLIED, APPLY_FAILED)
}
