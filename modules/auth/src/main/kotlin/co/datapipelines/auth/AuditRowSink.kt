package co.datapipelines.auth

import co.datapipelines.persistence.BatchSink
import co.datapipelines.persistence.FailureKinds
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** One `audit_log` row as [AuditLogger] hands it to its writer — `details` already serialized. */
data class AuditRow(
    val event: String,
    val userId: UUID?,
    val keyId: String?,
    val sourceIp: String?,
    val userAgent: String?,
    val detailsJson: String,
)

/**
 * The audit log's side of its batching writer (#266; auth.md §10).
 *
 * [write] is ONE JDBC batch in ONE transaction: all of it commits or none of it does, and the store
 * pays one commit for N rows. The transaction is explicit because PgJDBC does not give an autocommit
 * batch one — it commits every ~256 statements (measured, `ExecutionRepositoriesIntegrationTest`) —
 * and all-or-nothing is what makes the writer's retry-as-singles exact: `audit_log` has no natural
 * key to deduplicate a re-sent row by, so a half-applied batch followed by singles would duplicate
 * rows. [writeOne] — the direct path, on the caller's thread — is the INSERT [AuditLogger] always ran.
 *
 * `timestamp` keeps its column default, `NOW()`: the database's clock, which `McpToolLearnings`
 * compares with `pipeline_versions.updated_at` and must stay free of cross-clock skew. In a batch it
 * is the batch transaction's start — later than the call by at most the queue wait, and equal for
 * the rows of one batch (callers who were concurrent; `id` still orders them).
 */
class AuditRowSink(
    private val jdbc: NamedParameterJdbcTemplate,
) : BatchSink<AuditRow> {
    private val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.jdbcTemplate.dataSource)))

    override fun write(items: List<AuditRow>) {
        transactions.executeWithoutResult { jdbc.batchUpdate(INSERT, items.map(::params).toTypedArray()) }
    }

    override fun writeOne(item: AuditRow) {
        jdbc.update(INSERT, params(item))
    }

    /**
     * One key's (or, keyless, one user's) events share a writer and commit in order — a later
     * `revoked` never before an earlier `created`.
     */
    override fun partitionKey(item: AuditRow): Any = item.keyId ?: item.userId ?: ANONYMOUS

    override fun sizeOf(item: AuditRow): Int = item.detailsJson.length + (item.userAgent?.length ?: 0) + ROW_OVERHEAD_BYTES

    /** Ids only — the row's `details` are redaction-bound and never logged (observability §9.2). */
    override fun describe(item: AuditRow): String = "event=${item.event} user_id=${item.userId} key_id=${item.keyId}"

    /** A row the database refuses (a user id with no user row, a malformed address) is `poison`; anything else is an outage. */
    override fun classify(failure: Throwable): String =
        if (failure is DataIntegrityViolationException) FailureKinds.POISON else FailureKinds.WRITE_FAILED

    companion object {
        /** The statement [AuditLogger] has always run — the batch sends it N times, the direct path once. */
        const val INSERT =
            "INSERT INTO audit_log (event, user_id, key_id, source_ip, user_agent, details_json) " +
                "VALUES (:event, :user_id, :key_id, CAST(:source_ip AS INET), :user_agent, CAST(:details AS JSONB))"

        private const val ANONYMOUS = "anonymous"

        /** The fixed columns, roughly — the bound is a memory bound, not an accounting. */
        private const val ROW_OVERHEAD_BYTES = 128

        fun params(row: AuditRow): MapSqlParameterSource =
            MapSqlParameterSource()
                .addValue("event", row.event)
                .addValue("user_id", row.userId)
                .addValue("key_id", row.keyId)
                .addValue("source_ip", row.sourceIp)
                .addValue("user_agent", row.userAgent)
                .addValue("details", row.detailsJson)
    }
}
