package co.datapipelines.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
 */
class AuditLogger(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
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
        try {
            jdbc.update(
                """
                INSERT INTO audit_log (event, user_id, key_id, source_ip, user_agent, details_json)
                VALUES (:event, :user_id, :key_id, CAST(:source_ip AS INET), :user_agent, CAST(:details AS JSONB))
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("event", event)
                    .addValue("user_id", userId)
                    .addValue("key_id", keyId)
                    .addValue("source_ip", sourceIp)
                    .addValue("user_agent", userAgent)
                    .addValue("details", objectMapper.writeValueAsString(details)),
            )
        } catch (e: org.springframework.dao.DataAccessException) {
            log.warn(
                "audit_log write failed event={} user_id={} key_id={}",
                event,
                userId,
                keyId,
                e,
            )
        }
    }
}
