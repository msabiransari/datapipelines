package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.ColumnInfo
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.SqlProbeExecutionException
import co.datapipelines.datasources.SqlProbeParameterException
import co.datapipelines.datasources.SqlProbeRefusalException
import co.datapipelines.datasources.SqlProbeTimeoutException
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * Design §7.1 — the ONE validated path that turns "an agent learned something" into a
 * `learned_facts` row. Everything below the principal lives here, in plain identifiers, so the
 * module keeps its `typesystem`-only dependency rule: the scope/grant/capability rules and the
 * audit row are the application service's (the LakeTableRegistryService split).
 *
 * In order, each a refusal before anything is written:
 *
 *  1. the kind is in the closed list AND belongs to the requested scope (`semantics.kind_invalid`);
 *  2. the fact text, the ref count and the summary length are inside their windows (`semantics.fact_invalid`);
 *  3. every ref resolves against LIVE introspection (`semantics.ref_unresolved`) — one column read
 *     per referenced table, whose result is also the table's [SchemaFingerprint] (§3.2), so the
 *     store never starts stale and the drift check has something to compare against;
 *  4. no identical live fact exists (`semantics.duplicate`, O-4);
 *  5. `evidence_sql`, when given, runs ONCE through the probe path — the same classifier, lease
 *     and timebox as `sql_probe`: a statement the classifier refuses is `semantics.evidence_refused`
 *     (nothing leased), a statement the database refuses or that times out is
 *     `semantics.evidence_failed` (the fact is NOT recorded — evidence that does not run is not
 *     evidence). Its first rows become `evidence_summary` when none was given.
 *
 * Trust follows the evidence (D-S4): `observed` with it, `asserted` without. A superseding
 * fact retires its predecessor with reason `superseded` in the same call (§5) — the caller
 * resolves the predecessor through the visibility predicate first, so this recorder never
 * retires what the caller could not see.
 */
class LearnedFactRecorder(
    private val repository: LearnedFactRepository,
    private val introspector: SchemaIntrospector,
    private val probe: SqlProbe,
) {
    /** What a caller asks to record — the tool's arguments plus the provenance only it knows. */
    data class Request(
        val scope: LearnedFactScope,
        val kind: String,
        val fact: String,
        val refs: List<FactRef>,
        val evidenceSql: String?,
        val evidenceSummary: String?,
        val sourcePipelineId: UUID?,
        val sourceVersion: Int?,
        /** The predecessor this fact replaces, ALREADY resolved through the reader's visibility. */
        val supersedes: LearnedFact?,
        val recordedBy: UUID,
        val recordedVia: String,
        /** The active workspace at record time: provenance for every fact, the binding for a WORKSPACE one. */
        val recordedIn: UUID,
    )

    /** Records one fact against the ALREADY visibility-gated [datasource]. */
    fun record(
        datasource: Datasource,
        request: Request,
    ): LearnedFact {
        val kind = kindOf(request)
        validateShape(request)
        val fingerprint = resolveRefs(datasource, request.refs)
        val workspaceId = if (request.scope == LearnedFactScope.WORKSPACE) request.recordedIn else null
        repository.findDuplicate(request.scope, workspaceId, datasource.name, kind, request.refs, request.fact)?.let { existing ->
            throw DatapipelinesException(
                code = SemanticsErrorCodes.DUPLICATE,
                message = "An identical live fact is already recorded (${existing.id}).",
                details = mapOf("existing_id" to existing.id.toString(), "kind" to kind.wire),
            )
        }
        val summary = request.evidenceSql?.let { runEvidence(datasource, it, request.evidenceSummary) }
        val stored =
            repository.insert(
                LearnedFactRepository.NewFact(
                    scope = request.scope,
                    workspaceId = workspaceId,
                    datasourceName = datasource.name,
                    kind = kind,
                    fact = request.fact.trim(),
                    refs = request.refs,
                    evidenceSql = request.evidenceSql,
                    evidenceSummary = summary,
                    trust = if (request.evidenceSql != null) LearnedFactTrust.OBSERVED else LearnedFactTrust.ASSERTED,
                    schemaFingerprint = fingerprint,
                    recordedBy = request.recordedBy,
                    recordedVia = request.recordedVia,
                    recordedIn = request.recordedIn,
                    sourcePipelineId = request.sourcePipelineId,
                    sourceVersion = request.sourceVersion,
                    supersedes = request.supersedes?.id,
                ),
            )
        request.supersedes?.let { repository.retire(it.id, SUPERSEDED_REASON) }
        return stored
    }

    private fun kindOf(request: Request): LearnedFactKind {
        val kind = LearnedFactKind.fromWire(request.kind)
        if (kind == null || kind.scope != request.scope) {
            throw DatapipelinesException(
                code = SemanticsErrorCodes.KIND_INVALID,
                message =
                    if (kind == null) {
                        "Unknown kind '${request.kind}'. The kinds are: ${LearnedFactKind.entries.joinToString { it.wire }}."
                    } else {
                        "Kind '${kind.wire}' is a ${kind.scope.name} fact, not a ${request.scope.name} one."
                    },
                details = mapOf("kind" to request.kind, "scope" to request.scope.name),
            )
        }
        return kind
    }

    private fun validateShape(request: Request) {
        val fact = request.fact.trim()
        when {
            fact.length !in FACT_MIN_LENGTH..FACT_MAX_LENGTH -> {
                refuseShape("fact", "fact must be $FACT_MIN_LENGTH–$FACT_MAX_LENGTH characters.")
            }

            request.refs.isEmpty() -> {
                refuseShape("refs", "At least one ref {table, column?} is required.")
            }

            (request.evidenceSummary?.length ?: 0) > SUMMARY_MAX_LENGTH -> {
                refuseShape("evidence_summary", "evidence_summary must be at most $SUMMARY_MAX_LENGTH characters.")
            }

            request.refs.any { it.table.isBlank() } -> {
                refuseShape("refs", "A ref's table must not be blank.")
            }
        }
    }

    private fun refuseShape(
        field: String,
        message: String,
    ): Nothing = throw DatapipelinesException(SemanticsErrorCodes.FACT_INVALID, message, mapOf("field" to field))

    /** §3.1 + §3.2 in one pass: one column read per referenced table validates its refs and yields its digest. */
    private fun resolveRefs(
        datasource: Datasource,
        refs: List<FactRef>,
    ): String {
        val perTable =
            refs.groupBy { it.tableKey }.mapValues { (_, tableRefs) ->
                val first = tableRefs.first()
                val columns: List<ColumnInfo> = introspector.columns(datasource, first.table, first.schema)
                if (columns.isEmpty()) refuseRef(first, "Table '${first.tableKey}' does not exist on datasource '${datasource.name}'.")
                val names = columns.map { it.column.name }.toSet()
                tableRefs.firstOrNull { it.column != null && it.column !in names }?.let { missing ->
                    refuseRef(
                        missing,
                        "Column '${missing.column}' is not a column of '${missing.tableKey}' on datasource '${datasource.name}'.",
                    )
                }
                SchemaFingerprint.of(columns)
            }
        return SchemaFingerprint.combine(perTable)
    }

    private fun refuseRef(
        ref: FactRef,
        message: String,
    ): Nothing =
        throw DatapipelinesException(
            SemanticsErrorCodes.REF_UNRESOLVED,
            message,
            mapOf("ref" to mapOf("schema" to ref.schema, "table" to ref.table, "column" to ref.column)),
        )

    /**
     * The evidence run. Parameters are deliberately NOT accepted — evidence is a statement that
     * shows the fact by itself, and a `:name` in it is refused by the probe's own binder.
     */
    @Suppress("SwallowedException")
    private fun runEvidence(
        datasource: Datasource,
        sql: String,
        givenSummary: String?,
    ): String =
        try {
            val result = probe.probe(datasource, sql, limit = EVIDENCE_ROW_CAP, timeoutSeconds = SqlProbe.DEFAULT_TIMEOUT_SECONDS)
            givenSummary?.trim()?.takeIf { it.isNotEmpty() } ?: summarize(result.rows.rows)
        } catch (e: SqlProbeRefusalException) {
            throw DatapipelinesException(
                SemanticsErrorCodes.EVIDENCE_REFUSED,
                "evidence_sql was refused: ${e.message}",
                mapOf("reason" to "not_read_only"),
                e,
            )
        } catch (e: SqlProbeParameterException) {
            throw DatapipelinesException(
                SemanticsErrorCodes.EVIDENCE_REFUSED,
                "evidence_sql names a parameter ('${e.parameter}'); evidence binds nothing — inline the value.",
                mapOf("reason" to "parameter", "parameter" to e.parameter),
                e,
            )
        } catch (e: SqlProbeTimeoutException) {
            throw DatapipelinesException(
                SemanticsErrorCodes.EVIDENCE_FAILED,
                "evidence_sql exceeded the probe timeout; the fact was not recorded.",
                mapOf("reason" to "timeout", "wall_ms" to e.wallMs),
                e,
            )
        } catch (e: SqlProbeExecutionException) {
            throw DatapipelinesException(
                SemanticsErrorCodes.EVIDENCE_FAILED,
                "The database refused evidence_sql: ${e.driverMessage}",
                mapOf("reason" to "execution_failed"),
                e,
            )
        }

    /** The first rows as `col=value` pairs, one row per ` | `, cut to the column's window. */
    private fun summarize(rows: List<Map<String, Any?>>): String {
        if (rows.isEmpty()) return "(no rows)"
        val text = rows.joinToString(" | ") { row -> row.entries.joinToString(", ") { (k, v) -> "$k=${v ?: "null"}" } }
        return if (text.length <= SUMMARY_MAX_LENGTH) text else text.take(SUMMARY_MAX_LENGTH - 1) + "…"
    }

    companion object {
        const val FACT_MIN_LENGTH = 8
        const val FACT_MAX_LENGTH = 1000
        const val SUMMARY_MAX_LENGTH = 300

        /** Evidence is read for its summary, not exported: five rows say what the probe showed. */
        const val EVIDENCE_ROW_CAP = 5

        /** The `retired_reason` a superseding fact stamps on its predecessor (§5). */
        const val SUPERSEDED_REASON = "superseded"
    }
}
