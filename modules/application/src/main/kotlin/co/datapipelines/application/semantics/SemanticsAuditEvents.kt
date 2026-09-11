package co.datapipelines.application.semantics

/**
 * The learned-semantics audit event names ([Enums §15](../../../../../../../../docs/enums.md),
 * the learned-semantic-layer design record §9) — the wire strings written to `audit_log.event`.
 * `SemanticsAuditEventsSpecDriftTest` fails if this list and enums.md §15 ever disagree, the
 * `DatasourceAuditEvents` guard applied to this domain.
 *
 * These rows are what the owner's §9 acceptance COUNTS: "facts recorded per session" is a
 * query over `semantics.recorded`, so every record emits one, with the kind, scope, datasource
 * and refs in `details` — never the fact text or the evidence SQL (the redaction rule).
 */
object SemanticsAuditEvents {
    /** One learned fact was recorded (`semantics_record`). */
    const val RECORDED = "semantics.recorded"

    /** One learned fact was retired (`semantics_retire`), with its reason. */
    const val RETIRED = "semantics.retired"

    /** Every registered name — the drift-test surface. */
    val ALL = listOf(RECORDED, RETIRED)
}
