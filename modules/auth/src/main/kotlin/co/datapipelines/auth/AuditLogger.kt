package co.datapipelines.auth

import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.persistence.Outcome
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Append-only writer for `audit_log` (metadata-db §4.3, auth.md §10). Rows are
 * INSERTed, never updated. `details_json` is redaction-bound (observability §3) —
 * callers must not put credentials here.
 *
 * Failure to write an audit row must not break the request path, but it must never
 * pass silently: it is logged as a structured WARN at a defined boundary (rules/02),
 * not swallowed.
 *
 * Implements [AuditEventSink] (052) so cross-module emitters — the MCP dispatcher's
 * `mcp.tool.*` events — depend on the sink contract, not on this JDBC writer; the
 * default argument values live on the interface now and are inherited here unchanged.
 *
 * ## Durable before [log] returns — batched, or joined to the caller's transaction (#266)
 * Audit rows are AUTHORIZATION INPUTS: an MCP key's read of its own execution is decided by its
 * `mcp.execution.launched` / `mcp.tool.called` rows, an endpoint key's by its serve row. So [log]
 * returns only once the row is committed — never from memory. HOW it is committed depends on the
 * caller, decided per call:
 * - **Inside the caller's transaction** (`TransactionSynchronizationManager.isActualTransactionActive`)
 *   the row is INSERTed on the caller's connection, exactly as before #266: it commits with the
 *   business write it describes and vanishes if that write rolls back (a key issuance that failed
 *   leaves no `api_key.created` row). A batched write there would commit an audit row for an action
 *   that never happened — and would hold a second pool connection while the caller holds its first.
 * - **Otherwise** the row goes through [writer] — a group commit shared by concurrent callers,
 *   partitioned by key (then user) so one credential's events commit in order. The writer falls back
 *   to this same INSERT on the caller's thread after `record-max-wait-ms`, and after shutdown.
 * - **No writer** (`datapipelines.persistence.enabled: false`, or a test slice): every row is the
 *   direct INSERT.
 */
class AuditLogger(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val writer: BatchingWriter<AuditRow>? = null,
) : AuditEventSink {
    private val log = org.slf4j.LoggerFactory.getLogger(AuditLogger::class.java)

    override fun log(
        event: String,
        userId: UUID?,
        keyId: String?,
        sourceIp: String?,
        userAgent: String?,
        details: Map<String, Any?>,
    ) {
        val row = AuditRow(event, userId, keyId, sourceIp, userAgent, objectMapper.writeValueAsString(details))
        if (writer == null || TransactionSynchronizationManager.isActualTransactionActive()) {
            insertDirect(row)
            return
        }
        val outcome = writer.record(row)
        if (outcome is Outcome.Failed) {
            // The writer has logged the cause with the row's ids; this is the line the audit trail
            // has always carried for a lost row.
            log.warn("audit_log write failed event={} user_id={} key_id={} kind={}", event, userId, keyId, outcome.kind)
        }
    }

    private fun insertDirect(row: AuditRow) {
        try {
            jdbc.update(AuditRowSink.INSERT, AuditRowSink.params(row))
        } catch (e: org.springframework.dao.DataAccessException) {
            log.warn(
                "audit_log write failed event={} user_id={} key_id={}",
                row.event,
                row.userId,
                row.keyId,
                e,
            )
        }
    }

    companion object {
        /**
         * D-R8's audit flag: the value `details.acting_via` carries when a SUPER ADMIN acted in
         * a workspace they hold no explicit membership in. Spelled once here because three
         * emitters write it — the workspace service, the scope interceptor and the MCP
         * dispatcher — and a trail you can only query by exact string is a trail whose spelling
         * has to be shared.
         */
        const val ACTING_VIA_SUPER_ADMIN = "super_admin"

        /** The `details` key [ACTING_VIA_SUPER_ADMIN] is written under. */
        const val ACTING_VIA = "acting_via"

        /**
         * `acting_via=super_admin` when [context] says the actor holds no explicit membership
         * here (D-R8), otherwise nothing. Merged into a details map by the caller, so an
         * ordinary action carries no key at all rather than a null one.
         */
        fun actingVia(context: WorkspaceContext?): Map<String, Any?> =
            if (context?.actingViaSuperAdmin == true) mapOf(ACTING_VIA to ACTING_VIA_SUPER_ADMIN) else emptyMap()
    }
}
