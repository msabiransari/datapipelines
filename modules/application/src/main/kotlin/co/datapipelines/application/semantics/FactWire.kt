package co.datapipelines.application.semantics

import co.datapipelines.datasources.semantics.LearnedFact
import co.datapipelines.datasources.semantics.LearnedFactDrift
import co.datapipelines.templates.ImplementingVersion
import java.util.UUID

/**
 * The learned-fact wire projections — one home, shared verbatim by the MCP tools and the REST
 * twins (the `SchemaWire` discipline). Hand-built snake_case maps, omitted-when-null for the
 * optional fields, so a missing `drift` key means "no drift" and a missing `source_pipeline`
 * means "not yours to see" (D-S9) — never a `null` that asserts something.
 */
object FactWire {
    /**
     * Design §7.2 — the fact as it rides inside an introspection response:
     * `{id, scope, kind, fact, trust, drift?, evidence_summary?, recorded_via, recorded_at,
     * from_this_workspace, source_pipeline?, conflict?}`.
     */
    fun summary(
        fact: LearnedFact,
        readerWorkspaceId: UUID,
        verdict: LearnedFactDrift.Verdict,
        conflict: Boolean,
        sourcePipeline: Map<String, Any?>?,
        implementedBy: List<ImplementingVersion>? = null,
    ): Map<String, Any?> =
        buildMap {
            put("id", fact.id.toString())
            put("scope", fact.scope.name)
            put("kind", fact.kind.wire)
            put("fact", fact.fact)
            put("trust", verdict.trust.wire)
            verdict.drift?.let { put("drift", it) }
            fact.evidenceSummary?.let { put("evidence_summary", it) }
            put("recorded_via", fact.recordedVia)
            put("recorded_at", fact.recordedAt.toString())
            put("from_this_workspace", fact.recordedIn == readerWorkspaceId)
            sourcePipeline?.let { put("source_pipeline", it) }
            if (conflict) put("conflict", true)
            implementedBy?.let { put("implemented_by", implementedByWire(it)) }
        }

    /**
     * 7e (transform-nodes design §8.3) — the `implemented_by` value: the template versions the
     * reader's template lens admits that cite this fact, `[{template_id, version}]`. Present
     * (possibly `[]`) on a WORKSPACE fact — "no transform implements this rule yet" is an answer —
     * and ABSENT on a DATASOURCE fact, which a transform can never cite (§2.3): the caller passes
     * null there, so the key's absence means "not applicable", never "none".
     */
    fun implementedByWire(versions: List<ImplementingVersion>): List<Map<String, Any?>> =
        versions.map { mapOf("template_id" to it.templateId, "version" to it.version) }

    /**
     * `semantics_record`'s result and `semantics_list`'s rows: the summary plus everything an
     * agent needs to re-verify or supersede — the refs, the evidence SQL, the predecessor, the
     * retirement stamp. `recorded_by` is the user id (a key's writes are its owner's).
     */
    fun full(
        fact: LearnedFact,
        readerWorkspaceId: UUID,
        sourcePipeline: Map<String, Any?>?,
        implementedBy: List<ImplementingVersion>? = null,
    ): Map<String, Any?> =
        buildMap {
            putAll(
                summary(
                    fact,
                    readerWorkspaceId,
                    LearnedFactDrift.Verdict(fact.trust, LearnedFactDrift.storedDrift(fact)),
                    conflict = false,
                    sourcePipeline,
                    implementedBy,
                ),
            )
            put("datasource", fact.datasourceName)
            put("refs", fact.refs.map { ref -> mapOf("schema" to ref.schema, "table" to ref.table, "column" to ref.column) })
            fact.evidenceSql?.let { put("evidence_sql", it) }
            put("recorded_by", fact.recordedBy.toString())
            fact.sourceVersion?.let { put("source_version", it) }
            fact.supersedes?.let { put("supersedes", it.toString()) }
            fact.verifiedAt?.let { put("verified_at", it.toString()) }
            fact.retiredAt?.let { put("retired_at", it.toString()) }
            fact.retiredReason?.let { put("retired_reason", it) }
        }
}
