package co.datapipelines.lake.maintenance.remove

import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LeaseHandle
import co.datapipelines.lake.ReaderPins
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.time.Duration
import java.time.Instant

/** One object of the pass's listing: the inventory's row (D10). */
data class ListedObject(
    val key: String,
    val size: Long,
    val lastModified: Instant,
)

/**
 * The catalog reads a pass makes, each its own autocommit statement on DP's metadata database (never inside a
 * transaction: a transaction would freeze `now()` and hide a takeover):
 * - [protectedRevisions] — record §9's protected set, read afresh: the current pointer (`iceberg_tables`), the current
 *   revision row, every revision whose `protected_until` is null or ahead of `now()`, and every reader-pinned revision
 *   ([ReaderPins], record §9 draft 24);
 * - [modeIsNormal] and [leaseHeld] — the checks made before every batch and before the marker's deletion (D1, D5).
 */
class RemovalChecks(
    private val jdbc: JdbcTemplate,
    private val leases: LakeLeases,
    private val pins: ReaderPins,
    private val catalogName: String,
    private val leaseTtl: Duration,
) {
    init {
        require(leaseTtl > Duration.ZERO) { "the remover's lease ttl must be positive" }
    }

    fun protectedRevisions(target: RemovalTarget): Set<String> {
        requireNoTransaction()
        val pointer =
            jdbc.queryForList(
                "SELECT metadata_location FROM iceberg_tables WHERE catalog_name = ? AND table_namespace = ? AND table_name = ?" +
                    " AND metadata_location IS NOT NULL",
                String::class.java,
                catalogName,
                target.namespace,
                target.name,
            )
        val revisions =
            jdbc.queryForList(
                "SELECT metadata_location FROM lake_table_revisions WHERE namespace = ? AND table_name = ?" +
                    " AND (superseded_at IS NULL OR protected_until IS NULL OR protected_until > now())",
                String::class.java,
                target.namespace,
                target.name,
            )
        return (pointer + revisions + pins.pinnedLocations(target.namespace, target.name)).toSet()
    }

    /** `lake_control.mode = 'normal'`; any other mode, or no row, answers false. */
    fun modeIsNormal(): Boolean = currentMode() == NORMAL

    fun currentMode(): String? {
        requireNoTransaction()
        return jdbc.queryForList("SELECT mode FROM lake_control WHERE id = 1", String::class.java).singleOrNull()
    }

    /** The caller's lease is still this operation's, live and undeadlined (a heartbeat that renews it by [leaseTtl]). */
    fun leaseHeld(lease: LeaseHandle): Boolean = leases.heartbeat(lease, leaseTtl)

    private fun requireNoTransaction() =
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "the remover's catalog reads run on their own autocommit statements, never inside a transaction"
        }

    companion object {
        const val NORMAL = "normal"
    }
}

/** The pass's paged listing of every key under the table's prefix (`ListObjectsV2`, the delivery listing's precedent). */
internal class LocationListing(
    private val s3: S3Client,
) {
    fun list(
        bucket: String,
        prefix: String,
    ): List<ListedObject> {
        require(prefix.endsWith("/") && prefix.length > 1) { "a removal listing needs a table prefix ending in '/'" }
        val objects = ArrayList<ListedObject>()
        var token: String? = null
        do {
            val request =
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .apply { if (token != null) continuationToken(token) }
                    .build()
            val response = s3.listObjectsV2(request)
            response.contents().forEach { objects += ListedObject(it.key(), it.size() ?: 0L, it.lastModified()) }
            val next = if (response.isTruncated == true) response.nextContinuationToken() else null
            check(next == null || next != token) { "the store repeated a continuation token" }
            token = next
        } while (token != null)
        return objects
    }
}
