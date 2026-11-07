package co.datapipelines.lake

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

/** P6's pin oracle. Both liveness conditions use the database clock, including for a dead instance. */
class ReaderPins(
    jdbc: JdbcTemplate,
) {
    private val jdbc = JdbcTemplate(requireNotNull(jdbc.dataSource)).apply { queryTimeout = LakeLeases.READER_STATEMENT_TIMEOUT_SECONDS }

    /** Metadata revisions held by live reader generations of this table, across every instance. */
    fun pinnedLocations(
        namespace: String,
        table: String,
    ): Set<String> {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "reader pin queries require an independent catalog clock" }
        return jdbc
            .query(
                "SELECT pinned_metadata_location FROM lake_leases WHERE kind='reader'" +
                    " AND split_part(name,'@',1)=? AND expires_at>now() AND deadline_at>now()",
                { rs, _ -> rs.getString(1) },
                "$namespace.$table",
            ).toSet()
    }
}

/** A catalog revision and the timestamp of its current Iceberg snapshot, read by P6 from metadata. */
data class SnapshotRevision(
    val metadataLocation: String,
    val protectedUntil: Instant?,
    val snapshotTimestamp: Instant,
)

/** P6 consumes the same protected revision set for expiry and its orphan walker. */
data class SnapshotProtection(
    val expireOlderThan: Instant,
    val protectedRevisions: Set<SnapshotRevision>,
)

/** The expiry floor never passes a protected or pinned revision's current snapshot. No deletion is performed here. */
object SnapshotExpiryFloor {
    fun compute(
        now: Instant,
        window: Duration,
        revisions: List<SnapshotRevision>,
        pinnedLocations: Set<String>,
    ): SnapshotProtection {
        require(window > Duration.ZERO) { "protection window must be positive" }
        val protected =
            revisions
                .filter { it.protectedUntil == null || it.protectedUntil > now || it.metadataLocation in pinnedLocations }
                .toSet()
        val floor = protected.fold(now.minus(window)) { floor, revision -> minOf(floor, revision.snapshotTimestamp) }
        return SnapshotProtection(floor, protected)
    }
}
