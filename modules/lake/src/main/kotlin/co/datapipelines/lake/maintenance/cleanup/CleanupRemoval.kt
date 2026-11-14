package co.datapipelines.lake.maintenance.cleanup

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.ReaderPins
import co.datapipelines.lake.maintenance.MaintenanceRun
import co.datapipelines.lake.maintenance.remove.CatalogClock
import co.datapipelines.lake.maintenance.remove.LakeRemover
import co.datapipelines.lake.maintenance.remove.ReachabilityWalker
import co.datapipelines.lake.maintenance.remove.RemovalChecks
import co.datapipelines.lake.maintenance.remove.RemovalTables
import co.datapipelines.lake.maintenance.remove.RunStates
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.services.s3.S3Client
import java.time.Duration

/**
 * What both parts reach beyond the metadata rows: 958's [remover] and the reads it is built from (the table's identity,
 * the mode, the reader pins), and the catalog's [clock] the parts read "now" from (the floor, the keep plan, the listing
 * time, due-ness). The remover keeps its own clock (958 D7); production gives both `CatalogClock.of` the same database.
 */
class CleanupRemoval(
    val remover: LakeRemover,
    val tables: RemovalTables,
    val checks: RemovalChecks,
    val pins: ReaderPins,
    val clock: CatalogClock,
) {
    companion object {
        /**
         * The production construction (brief 962; the remover's rules are 958's): the lease it re-checks is the run's
         * ([MaintenanceRun.TTL]), the grace the deployment's `retention.orphan-grace-hours`, the catalog name the lake's.
         * [removerClock] and [s3] are replaceable only so a test can shift the catalog's clock or the store's ages.
         */
        @Suppress("LongParameterList") // the remover's collaborators, each a production bean
        fun of(
            properties: LakeProperties,
            jdbc: JdbcTemplate,
            catalogs: LakeCatalogs,
            leases: LakeLeases,
            s3: S3Client,
            runStates: RunStates,
            clock: CatalogClock = CatalogClock.of(jdbc),
            removerClock: CatalogClock = clock,
        ): CleanupRemoval {
            val pins = ReaderPins(jdbc)
            val tables = RemovalTables(jdbc, properties.storage)
            val checks = RemovalChecks(jdbc, leases, pins, properties.name, MaintenanceRun.TTL)
            val remover =
                LakeRemover(
                    tables = tables,
                    checks = checks,
                    walker = ReachabilityWalker(catalogs.metadataFileIo()),
                    s3 = s3,
                    clock = removerClock,
                    runStates = runStates,
                    orphanGrace = Duration.ofHours(properties.retention.orphanGraceHours.toLong()),
                )
            return CleanupRemoval(remover, tables, checks, pins, clock)
        }
    }
}
