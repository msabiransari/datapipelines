package co.datapipelines.web.release

import co.datapipelines.auth.AuditRow
import co.datapipelines.auth.AuditRowSink
import co.datapipelines.pipeline.release.ReleaseAuditRow
import co.datapipelines.pipeline.release.ReleaseAuditSink
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * The strict release audit (record §11, §13 `:306`; brief A.5): the audit log's own INSERT ([AuditRowSink.INSERT], the
 * row shape every `audit_log` reader knows), run on the CALLER's connection inside the release transaction — and,
 * unlike `AuditLogger`, never caught. A refused or unreachable insert throws, the coordinator's transaction rolls every
 * flip back with it, and the release is refused: unavailable audit storage refuses the release. No batching writer, no
 * async path, no body content (the coordinator's details are ids, names, versions and words).
 *
 * Outside a transaction it refuses to write at all: a row committed on its own could describe a release that then
 * rolled back.
 */
class StrictReleaseAuditSink(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ReleaseAuditSink {
    override fun record(row: ReleaseAuditRow) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "A strict release audit row is written only inside the release transaction."
        }
        val auditRow = AuditRow(row.event, row.userId, row.keyId, null, null, objectMapper.writeValueAsString(row.details))
        jdbc.update(AuditRowSink.INSERT, AuditRowSink.params(auditRow))
    }
}
