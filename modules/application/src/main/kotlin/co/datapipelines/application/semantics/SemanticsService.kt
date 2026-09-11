package co.datapipelines.application.semantics

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Capability
import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.RoleRequiredException
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.datasources.semantics.LearnedFact
import co.datapipelines.datasources.semantics.LearnedFactRecorder
import co.datapipelines.datasources.semantics.LearnedFactRepository
import co.datapipelines.datasources.semantics.LearnedFactScope
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

/**
 * Design §7.1 — the three verbs above the principal: `semantics_record`, `semantics_list`,
 * `semantics_retire`. The ONE validated path the MCP tools (and any later REST twin) call, the
 * `LakeTableRegistryService` split: the datasource arrives ALREADY visibility-gated — which for a
 * datasource IS the grant (D-R7), so a DATASOURCE-scope record on an ungranted datasource is the
 * not-found the gate answered before this ran — and the `author` floor is the §7.6 matrix's,
 * enforced by the dispatcher (the one place). What lives here is what the surfaces must share:
 *
 *  - the provenance the recorder cannot know (who, through what door, in which workspace);
 *  - the D-S9 predicate on the OUTBOUND links: a `supersedes` or `source_pipeline_id` the
 *    caller's workspace cannot read is not-found (`semantics.not_found` /
 *    `pipeline.execution.not_found`), never a 403 that confirms it exists;
 *  - the retire rule (§7.1): only facts the workspace may see, and a DATASOURCE fact recorded
 *    from ANOTHER workspace needs `ws_admin` — you do not silently retire what someone else
 *    established;
 *  - the audit rows the §9 acceptance counts: `semantics.recorded` per fact, `semantics.retired`
 *    per retirement — kind, scope, datasource, refs, trust, via; never the fact text or the SQL.
 */
class SemanticsService(
    private val repository: LearnedFactRepository,
    private val recorder: LearnedFactRecorder,
    private val pipelines: PipelineRepository,
    private val audit: AuditEventSink,
) {
    /** A record request as the surface binds it — everything but the provenance. */
    data class RecordCommand(
        val scope: LearnedFactScope,
        val kind: String,
        val fact: String,
        val refs: List<FactRef>,
        val evidenceSql: String?,
        val evidenceSummary: String?,
        val sourcePipelineId: UUID?,
        val sourceVersion: Int?,
        val supersedes: UUID?,
    )

    /** A listing filter — every field optional; `table` matches any ref on that table. */
    data class ListQuery(
        val table: String? = null,
        val scope: LearnedFactScope? = null,
        val includeRetired: Boolean = false,
        val since: Instant? = null,
    )

    /** Records one fact on the gated [datasource] as [principal], through [via]; returns the stored row's wire form. */
    fun record(
        principal: AuthenticatedPrincipal,
        datasource: Datasource,
        command: RecordCommand,
        via: WriteSurface,
    ): Map<String, Any?> {
        val workspaceId = principal.requireWorkspace().id
        val predecessor = command.supersedes?.let { visibleOrNotFound(it, workspaceId, field = "supersedes", datasource = datasource) }
        command.sourcePipelineId?.let { id ->
            pipelines.findById(workspaceId, id) ?: throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.NOT_FOUND,
                message = "Pipeline $id does not exist.",
                details = mapOf("pipeline_id" to id.toString()),
            )
        }
        val stored =
            recorder.record(
                datasource,
                LearnedFactRecorder.Request(
                    scope = command.scope,
                    kind = command.kind,
                    fact = command.fact,
                    refs = command.refs,
                    evidenceSql = command.evidenceSql,
                    evidenceSummary = command.evidenceSummary,
                    sourcePipelineId = command.sourcePipelineId,
                    sourceVersion = command.sourceVersion,
                    supersedes = predecessor,
                    recordedBy = principal.userId,
                    recordedVia = via.wire,
                    recordedIn = workspaceId,
                ),
            )
        audit.log(
            event = SemanticsAuditEvents.RECORDED,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                buildMap {
                    put("fact_id", stored.id.toString())
                    put("kind", stored.kind.wire)
                    put("scope", stored.scope.name)
                    put("datasource", stored.datasourceName)
                    put("refs", stored.refs.map { it.tableKey + (it.column?.let { c -> ".$c" } ?: "") })
                    put("trust", stored.trust.wire)
                    put("via", via.wire)
                    put("evidence", stored.evidenceSql != null)
                    stored.supersedes?.let { put("supersedes", it.toString()) }
                    stored.sourcePipelineId?.let { put("source_pipeline_id", it.toString()) }
                },
        )
        return FactWire.full(stored, workspaceId, sourcePipelineFor(stored, workspaceId))
    }

    /** The facts on [datasource] the caller's workspace may see, oldest first, as stored (no drift recompute — no columns in hand). */
    fun list(
        principal: AuthenticatedPrincipal,
        datasource: Datasource,
        query: ListQuery,
    ): List<Map<String, Any?>> {
        val workspaceId = principal.requireWorkspace().id
        return repository
            .findVisibleByDatasource(datasource.name, workspaceId, includeRetired = query.includeRetired, since = query.since)
            .filter { query.scope == null || it.scope == query.scope }
            .filter { query.table == null || it.refs.any { ref -> ref.table == query.table } }
            .map { FactWire.full(it, workspaceId, sourcePipelineFor(it, workspaceId)) }
    }

    /** Retires the fact under [id] with [reason]; the visibility and the cross-workspace rule of §7.1. */
    fun retire(
        principal: AuthenticatedPrincipal,
        id: UUID,
        reason: String,
    ): Map<String, Any?> {
        val workspaceId = principal.requireWorkspace().id
        val fact = visibleOrNotFound(id, workspaceId, field = "id", datasource = null)
        if (fact.scope == LearnedFactScope.DATASOURCE && fact.recordedIn != workspaceId && !principal.isWorkspaceAdmin) {
            throw RoleRequiredException(
                Capability.WS_ADMIN,
                (principal.workspace?.flags ?: MembershipFlags.VIEWER).held(),
                principal.workspace?.name,
            )
        }
        repository.retire(fact.id, reason.trim())
        audit.log(
            event = SemanticsAuditEvents.RETIRED,
            userId = principal.userId,
            keyId = principal.keyId,
            details =
                mapOf(
                    "fact_id" to fact.id.toString(),
                    "kind" to fact.kind.wire,
                    "scope" to fact.scope.name,
                    "datasource" to fact.datasourceName,
                    "reason" to reason.trim(),
                    "recorded_in_this_workspace" to (fact.recordedIn == workspaceId),
                ),
        )
        val retired = requireNotNull(repository.findVisible(fact.id, workspaceId)) { "the fact just retired vanished" }
        return FactWire.full(retired, workspaceId, sourcePipelineFor(retired, workspaceId))
    }

    /**
     * D-R5 for facts: a fact the workspace cannot see, or one on a different datasource than the
     * record targets, is `semantics.not_found` — the same answer an id that exists nowhere gets.
     */
    private fun visibleOrNotFound(
        id: UUID,
        workspaceId: UUID,
        field: String,
        datasource: Datasource?,
    ): LearnedFact {
        val fact = repository.findVisible(id, workspaceId)
        if (fact == null || (datasource != null && fact.datasourceName != datasource.name)) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Semantics.NOT_FOUND,
                message = "Fact $id does not exist.",
                details = mapOf("field" to field, "fact_id" to id.toString()),
            )
        }
        return fact
    }

    private fun sourcePipelineFor(
        fact: LearnedFact,
        workspaceId: UUID,
    ): Map<String, Any?>? =
        fact.sourcePipelineId
            ?.let { pipelines.findById(workspaceId, it) }
            ?.let { mapOf("id" to it.id.toString(), "name" to it.name) }
}
