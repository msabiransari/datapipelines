package co.datapipelines.auth

import co.datapipelines.persistence.BatchingWriter
import co.datapipelines.persistence.FailureShape
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
 * ## Durable before [log] returns — direct, joined to the caller's transaction, or batched (#266)
 * Audit rows are AUTHORIZATION INPUTS: an MCP key's read of its own execution is decided by its
 * `mcp.execution.launched` / `mcp.tool.called` rows, an endpoint key's by its serve row. So [log]
 * returns only once the row is committed — never from memory.
 *
 * **By default there is no [writer]** (#266b): `datapipelines.persistence.audit.enabled` ships
 * false, because measured, audit rows never formed batches and the writer only added to their tail
 * (configuration §3.32). Every row is then the pre-#266 INSERT — on the caller's connection inside
 * its transaction, on a pool connection otherwise. With the switch on, HOW a row is committed is
 * decided per call:
 * - **Inside the caller's transaction** (`TransactionSynchronizationManager.isActualTransactionActive`)
 *   the row is INSERTed on the caller's connection, exactly as before #266: it commits with the
 *   business write it describes and vanishes if that write rolls back (a key issuance that failed
 *   leaves no `api_key.created` row). A batched write there would commit an audit row for an action
 *   that never happened — and would hold a second pool connection while the caller holds its first.
 * - **Otherwise** the row goes through [writer] — a group commit shared by concurrent callers,
 *   partitioned by key (then user) so one credential's events commit in order. The writer falls back
 *   to this same INSERT on the caller's thread after `record-max-wait-ms`, and after shutdown.
 * - **No writer** (the default — `audit.enabled: false` — or `datapipelines.persistence.enabled:
 *   false`, or a test slice): every row is the direct INSERT.
 *
 * ## Which failures reach the caller — the same on every path (#266b)
 * A STORE failure is the row's, never the request's: a `DataAccessException` (the INSERT refused, the
 * database unreachable) and, on the batched path, a `TransactionException` (the batch's own
 * transaction could not begin or commit) are WARNed with the row's ids and the failure's class and
 * SQLState, counted, and swallowed — [log] returns and the request proceeds without the row.
 * Anything else propagates out of [log] and fails the request, exactly as the pre-#266 INSERT's
 * `catch (DataAccessException)` let it: a runtime exception from the driver or the template, a
 * serialization failure of `details`, an `Error`. On the batched path the writer rethrows it on this
 * thread ([AuditRowSink.propagates]).
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
        val inTransaction = TransactionSynchronizationManager.isActualTransactionActive()
        // ONE decision, logged and then acted on: the line reports the path the row really takes.
        val batching = writer.takeUnless { inTransaction }
        // Which path a row takes, per event name — the #266 A.1 probe reads this; an operator can too.
        log.debug(
            "event=audit.write audit_event={} path={}",
            event,
            when {
                batching != null -> PATH_BATCHED
                inTransaction -> PATH_TRANSACTIONAL
                else -> PATH_DIRECT
            },
        )
        if (batching == null) {
            insertDirect(row)
            return
        }
        val outcome = batching.record(row)
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
            // The class and the SQLState, never the exception: Postgres quotes the refused row in its
            // message (a JSONB refusal's CONTEXT carries `details`, which are redaction-bound) — #266b.
            log.warn(
                "audit_log write failed event={} user_id={} key_id={} cause={} sql_state={}",
                row.event,
                row.userId,
                row.keyId,
                FailureShape.cause(e),
                FailureShape.sqlState(e),
            )
        }
    }

    companion object {
        /** The `path=` values of the DEBUG `event=audit.write` line. */
        const val PATH_TRANSACTIONAL = "transactional"
        const val PATH_BATCHED = "batched"
        const val PATH_DIRECT = "direct"

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
