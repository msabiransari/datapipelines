package co.datapipelines.application.mcp

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * "Did THIS API key make the MCP call that started THIS execution?" (107) — the question that
 * lets `executions_cancel` enforce its same-credential rule.
 *
 * ## Why the audit row and not the execution row
 *
 * `pipeline_executions.triggered_by` is a **user** id — the key's owner — and there is no key-id
 * column on the table (the exact gap [co.datapipelines.application.endpoints.EndpointServeAudit]
 * KDoc documents for `endpoint.served`). Two keys owned by one person are indistinguishable
 * through it. The dispatcher's `mcp.tool.called` row is the only place the KEY id and the call's
 * CORRELATION id appear together, and the same correlation id lands on the execution row the
 * call started — so key id + correlation id is the pairing, and this is its only reader.
 *
 * ## Read-only, and deliberately narrow
 *
 * One boolean, one event name, one key, one correlation id — the [EndpointServeAudit] discipline:
 * the audit log is append-only evidence; the moment it is queried for anything broader it stops
 * being evidence and becomes a data model.
 */
class McpCallAudit(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** True when [keyId] made an `mcp.tool.called` call carrying [correlationId]. */
    fun calledByKey(
        keyId: String,
        correlationId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM audit_log
                 WHERE event = :event
                   AND key_id = :keyId
                   AND details_json ->> 'correlation_id' = :correlationId
            )
            """.trimIndent(),
            mapOf("event" to CALL_EVENT, "keyId" to keyId, "correlationId" to correlationId.toString()),
            Boolean::class.java,
        ) ?: false

    companion object {
        /** The dispatcher's per-call event; one spelling, shared with the writer. */
        const val CALL_EVENT = "mcp.tool.called"
    }
}
