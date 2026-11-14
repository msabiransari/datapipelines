package co.datapipelines.web.config

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.delivery.LakeStagingStorage
import co.datapipelines.lake.maintenance.MaintenanceScratch
import co.datapipelines.lake.maintenance.compaction.CompactionScratchPort
import co.datapipelines.staging.StagingFactory
import co.datapipelines.staging.StagingScratchExhaustedException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

/**
 * #969 D5: the maintenance worker's scratch and the compaction part's scratch database, both over the staging factory
 * the deliveries use. With these two beans the lake's auto-configuration replaces `MaintenanceScratch.NONE` and
 * registers the `compaction` part.
 */
@Configuration
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeCompactionConfiguration {
    /**
     * D5 step 1: a run's reservation IS a staging preparation, debited from the budget deliveries reserve from, in the
     * delivery path's shape (an exhausted budget answers null). The run row does not exist yet when the worker
     * reserves (957 D3 step 3), so the preparation's id is its own.
     */
    @Bean
    fun lakeMaintenanceScratch(stagingFactory: StagingFactory): MaintenanceScratch =
        MaintenanceScratch { bytes ->
            try {
                stagingFactory.prepareDelivery(UUID.randomUUID(), bytes)
            } catch (_: StagingScratchExhaustedException) {
                null
            }
        }

    /** D5 step 5: the part's port, a trusted scratch database per run, configured through the lake's storage setup. */
    @Bean
    fun lakeCompactionScratchPort(storage: LakeStagingStorage): CompactionScratchPort = TrustedCompactionScratch(storage, Clock.systemUTC())
}
