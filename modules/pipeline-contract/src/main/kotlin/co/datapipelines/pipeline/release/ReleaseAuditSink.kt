package co.datapipelines.pipeline.release

import java.util.UUID

/**
 * The strict release audit (record §11, §13): one `*.version.released` row per actual flip, written in the release's own
 * transaction, in release order. Unlike the general audit sink it never swallows: a row that cannot be written throws,
 * and the release rolls back with it — unavailable audit storage refuses the release. No async writer, no body content.
 */
fun interface ReleaseAuditSink {
    fun record(row: ReleaseAuditRow)
}

/** One audit row — the `audit_log` columns the release fills; [details] are ids, names, versions and words only. */
data class ReleaseAuditRow(
    val event: String,
    val userId: UUID?,
    val keyId: String?,
    val details: Map<String, Any?>,
)
