package co.datapipelines.application.mcp

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

/**
 * "What has THIS API key already learned?" (139) — the audit-log read behind the MCP
 * entry-point checks `pipeline.validation.table_not_learned` and
 * `pipeline.execution.template_unrendered`.
 *
 * ## Why the audit rows
 *
 * The checks the owner ratified are for the AGENT loop the skill describes: a key must not
 * save a pipeline naming a table it never read the columns of, nor run a draft whose
 * template it edited after its last render. Both questions are about ONE credential's own
 * recent behaviour, and the dispatcher already writes exactly that behaviour —
 * `mcp.tool.called` rows carrying the tool, the target and (since 139) the table /
 * template identifier — keyed by [McpCallAudit]'s `key_id`. No new table, no second source
 * of truth: the audit log is the learning record, and this class is its only reader beside
 * [McpCallAudit] and `EndpointServeAudit` (the narrow-reader discipline: one question per
 * reader, never a general query surface).
 *
 * ## Rows written before 139 simply do not count
 *
 * The pre-139 detail maps carry no `table` and no `template` key, so their
 * `details_json ->> 'table'` is NULL and they match neither query — an old key that read a
 * table last week under the old dispatcher has not learned it, and a fresh key learns
 * fresh. That is the ratified reading, not an accident of the join.
 *
 * Success rows only: a refused `datasources_get_columns` (`datasource.table_not_found`)
 * taught the key nothing about the table's columns, and a failed `templates_render`
 * produced no SQL to run.
 */
class McpToolLearnings(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * The tables of [datasource] whose columns [keyId] has successfully read with
     * `datasources_get_columns` — the learned set check A intersects the template body's
     * table references against. Null/blank key (no credential on the principal) learns
     * nothing: fail-closed is the only safe reading of "you must have read it".
     */
    fun columnsRead(
        keyId: String?,
        datasource: String,
    ): Set<String> {
        if (keyId.isNullOrBlank()) return emptySet()
        return jdbc
            .queryForList(
                """
                SELECT DISTINCT details_json ->> 'table' AS table_name
                  FROM audit_log
                 WHERE event = :event
                   AND key_id = :keyId
                   AND details_json ->> 'tool' = :tool
                   AND details_json ->> 'outcome' = 'success'
                   AND details_json ->> 'target' = :datasource
                   AND details_json ->> 'table' IS NOT NULL
                """.trimIndent(),
                mapOf("event" to McpCallAudit.CALL_EVENT, "keyId" to keyId, "tool" to TOOL_COLUMNS, "datasource" to datasource),
                String::class.java,
            ).toSet()
    }

    /**
     * When [keyId] last successfully rendered template [templateId], or null when this key
     * never did. Check B compares this against the draft's `updated_at` — both stamps come
     * from the same metadata database's clock (`audit_log.timestamp` and
     * `pipeline_versions.updated_at` are both `NOW()` at write), so the comparison carries
     * no cross-clock skew.
     */
    fun lastRenderAt(
        keyId: String?,
        templateId: String,
    ): Instant? {
        if (keyId.isNullOrBlank()) return null
        return jdbc.queryForObject(
            """
            SELECT max("timestamp")
              FROM audit_log
             WHERE event = :event
               AND key_id = :keyId
               AND details_json ->> 'tool' = :tool
               AND details_json ->> 'template' = :templateId
               AND details_json ->> 'outcome' = 'success'
            """.trimIndent(),
            mapOf("event" to McpCallAudit.CALL_EVENT, "keyId" to keyId, "tool" to TOOL_RENDER, "templateId" to templateId),
            Instant::class.java,
        )
    }

    companion object {
        private const val TOOL_COLUMNS = "datasources_get_columns"
        private const val TOOL_RENDER = "templates_render"
    }
}
