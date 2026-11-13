package co.datapipelines.web.config

import co.datapipelines.application.lake.LakeReferenceIndexRepository
import co.datapipelines.application.lake.LakeReferenceScanService
import co.datapipelines.application.lake.ScannedVersion
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionOperations

/**
 * The lake reference index's boot backfill (#778, slice D-C), a sibling of [AuthoringStartupCheck]: every RELEASED
 * pipeline and parameter-set version with no scan row, or one written by an older
 * [LakeReferenceScanService.SCANNER_VERSION], is scanned once, each in its own metadata transaction. Released content is
 * immutable, so a second boot finds nothing to do (idempotent); a bumped scanner version re-indexes everything once.
 *
 * A version whose scan throws is logged by its identity (family, entity id, version — never its SQL) and left for the
 * next boot; it does not stop the boot or the remaining versions. A page with no successful scan ends the pass, so a
 * version that fails every time cannot spin it. Logs counts only.
 */
class LakeReferenceBackfill(
    private val index: LakeReferenceIndexRepository,
    private val scanner: LakeReferenceScanService,
    private val transactions: TransactionOperations,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    private val log = LoggerFactory.getLogger(LakeReferenceBackfill::class.java)

    /** What one pass did — the [backfill] result, also what the boot log line reports. */
    data class Outcome(
        val scanned: Int,
        val failed: Int,
    )

    @PostConstruct
    fun onBoot() {
        val outcome = backfill()
        log.info(
            "event=lake.reference_index.backfill scanned={} failed={} scanner_version={}",
            outcome.scanned,
            outcome.failed,
            LakeReferenceScanService.SCANNER_VERSION,
        )
    }

    /** One pass over the unscanned RELEASED versions. */
    fun backfill(): Outcome {
        var scanned = 0
        val failed = LinkedHashSet<ScannedVersion>()
        do {
            val page =
                index
                    .unscannedReleased(LakeReferenceScanService.SCANNER_VERSION, pageSize + failed.size)
                    .filterNot { it in failed }
                    .take(pageSize)
            val succeeded = page.count { version -> scanOne(version, failed) }
            scanned += succeeded
        } while (succeeded > 0)
        return Outcome(scanned, failed.size)
    }

    /** Scans [version] in its own transaction; a failure is logged by identity and remembered in [failed]. */
    private fun scanOne(
        version: ScannedVersion,
        failed: MutableSet<ScannedVersion>,
    ): Boolean =
        runCatching { transactions.executeWithoutResult { scanner.scan(version) } }
            .onFailure { e ->
                failed += version
                log.warn(
                    "event=lake.reference_index.backfill_failed family={} entity_id={} version={} error_type={}",
                    version.family.wire,
                    version.entityId,
                    version.version,
                    e.javaClass.simpleName,
                )
            }.isSuccess

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
    }
}
