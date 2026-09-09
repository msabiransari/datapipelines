package co.datapipelines.application.mcp

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * "Did THIS API key make the MCP call that started THIS execution?" (107) — the question that
 * lets `executions_cancel` enforce its same-credential rule.
 *
 * ## Why the audit rows and not the execution row
 *
 * `pipeline_executions.triggered_by` is a **user** id — the key's owner — and there is no key-id
 * column on the table (the exact gap [co.datapipelines.application.endpoints.EndpointServeAudit]
 * KDoc documents for `endpoint.served`). Two keys owned by one person are indistinguishable
 * through it. The audit log is the only place the KEY id and the call's CORRELATION id appear
 * together, and the same correlation id lands on the execution row the call started — so key id
 * + correlation id is the pairing, and this is its only reader.
 *
 * ## Two event names, one timing gap
 *
 * The dispatcher's `mcp.tool.called` row exists only when a call ENDS — and `pipelines_execute`
 * blocks until the execution it started is already terminal, so for an IN-FLIGHT execution that
 * row does not exist yet and the join would refuse the very key that started it (measured live,
 * 107). `pipelines_execute` therefore emits [LAUNCH_EVENT] before the blocking run begins; both
 * event names authorize, since a completed call's row may still precede a still-RUNNING
 * execution (a server restart between the two).
 *
 * ## Read-only, and deliberately narrow
 *
 * One boolean, two event names, one key, one correlation id — the [EndpointServeAudit]
 * discipline: the audit log is append-only evidence; the moment it is queried for anything
 * broader it stops being evidence and becomes a data model.
 */
class McpCallAudit(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** True when [keyId] made an MCP call carrying [correlationId], at launch or at completion. */
    fun calledByKey(
        keyId: String,
        correlationId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM audit_log
                 WHERE event IN (:events)
                   AND key_id = :keyId
                   AND details_json ->> 'correlation_id' = :correlationId
            )
            """.trimIndent(),
            mapOf("events" to EVENTS, "keyId" to keyId, "correlationId" to correlationId.toString()),
            Boolean::class.java,
        ) ?: false

    companion object {
        /** The dispatcher's per-call event; one spelling, shared with the writer. */
        const val CALL_EVENT = "mcp.tool.called"

        /** The launch-time event `pipelines_execute` emits before blocking — see the class KDoc. */
        const val LAUNCH_EVENT = "mcp.execution.launched"

        /** The events that authorize the same-credential rule. */
        val EVENTS: List<String> = listOf(CALL_EVENT, LAUNCH_EVENT)
    }
}
