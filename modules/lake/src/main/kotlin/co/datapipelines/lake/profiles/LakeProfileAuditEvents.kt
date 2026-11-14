package co.datapipelines.lake.profiles

/**
 * The lake profiles' and pauses' audit event names (enums.md §15) — wire values in `audit_log.event`, held to the doc
 * by `LakeProfileAuditEventsSpecDriftTest`. Each is written by the service, in the change's own transaction, once per
 * change. Details carry ids, names, key paths and instants: never a credential.
 */
object LakeProfileAuditEvents {
    /** A profile was created (its id and name; a new profile is never the default). */
    const val CREATED = "lake.profile.created"

    /** A profile's values changed (the changed key paths), or the default mark moved to it. */
    const val UPDATED = "lake.profile.updated"

    /** A managed table was moved to another profile (the previous and the new profile). */
    const val ASSIGNED = "lake.profile.assigned"

    /** One of a table's two pause keys was set or cleared (the key, the previous and the new instant). */
    const val PAUSE_CHANGED = "lake.table.pause_changed"

    /** Every event this package emits — exactly what §15 documents. */
    val ALL: Set<String> = setOf(CREATED, UPDATED, ASSIGNED, PAUSE_CHANGED)
}
