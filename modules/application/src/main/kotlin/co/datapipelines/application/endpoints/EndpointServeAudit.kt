package co.datapipelines.application.endpoints

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * "Did THIS endpoint key start THIS execution?" (design §5.2) — the question that lets an endpoint
 * key read the cursor of its own result and nobody else's.
 *
 * ## Why the audit row and not the execution row
 *
 * `pipeline_executions.triggered_by` is a **user** id — the key's owner. Two endpoint keys owned
 * by the same person are indistinguishable through it, so a key bound to `/lending` could read
 * the results of a key bound to `/payroll` merely by sharing an owner. The serve audit row is the
 * only place the KEY id and the EXECUTION id appear together, which is exactly the pairing this
 * question needs, and it is written on the serve path for its own reasons (R4: every serve is
 * audited with the key id, endpoint id, execution id and outcome).
 *
 * ## Read-only, and deliberately narrow
 *
 * One boolean, one event name, one key, one execution. The audit log is append-only evidence; the
 * moment it is queried for anything broader it stops being evidence and becomes a data model.
 */
class EndpointServeAudit(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** True when [keyId] served an execution with id [executionId] through a published endpoint. */
    fun servedByKey(
        executionId: UUID,
        keyId: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM audit_log
                 WHERE event = :event
                   AND key_id = :keyId
                   AND details_json ->> 'execution_id' = :executionId
            )
            """.trimIndent(),
            mapOf("event" to SERVE_EVENT, "keyId" to keyId, "executionId" to executionId.toString()),
            Boolean::class.java,
        ) ?: false

    companion object {
        /** The event the serve path writes (enums.md §15); one spelling, shared with the writer. */
        const val SERVE_EVENT = "endpoint.served"
    }
}
