package co.datapipelines.datasources.semantics

/**
 * The learned-semantics slice of the error-code catalog
 * ([pipeline-contract §13.15](../../../../../../../../docs/pipeline-contract.md)), mirrored here
 * for the same reason [co.datapipelines.datasources.DatasourceErrorCodes] is: this module may
 * depend on `typesystem` only (module-structure §5.4), and `PipelineErrorCodes.Semantics` lives
 * in `pipeline-contract`, a sibling layer. `SemanticsErrorCodesSpecDriftTest` reads §13.15 and
 * fails if the two ever disagree.
 */
object SemanticsErrorCodes {
    /** `kind` is not in the closed list, or is not a kind of the requested `scope`. */
    const val KIND_INVALID = "semantics.kind_invalid"

    /** `fact` outside 8–1000 characters, empty `refs`, or an over-long `evidence_summary`; `details.field` names which. */
    const val FACT_INVALID = "semantics.fact_invalid"

    /** A ref does not resolve against live introspection — the store never starts stale (§3.1). */
    const val REF_UNRESOLVED = "semantics.ref_unresolved"

    /** `evidence_sql` is not a single read-only SELECT/WITH, or names a parameter. Refused before any connection opens. */
    const val EVIDENCE_REFUSED = "semantics.evidence_refused"

    /** `evidence_sql` ran and the database refused it or it timed out; the fact is not recorded. */
    const val EVIDENCE_FAILED = "semantics.evidence_failed"

    /** O-4 — an identical live fact already exists; `details.existing_id` names it. */
    const val DUPLICATE = "semantics.duplicate"

    /** The fact addressed by id does not exist or is not visible to the caller's workspace. */
    const val NOT_FOUND = "semantics.not_found"
}
