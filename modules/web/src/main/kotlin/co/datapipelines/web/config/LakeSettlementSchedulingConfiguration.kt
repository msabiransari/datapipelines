package co.datapipelines.web.config

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.append.LakeAppendRuntime
import co.datapipelines.lake.settle.LakeSettlementSweep
import co.datapipelines.persistence.FailureShape
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled

/** The normal-mode delivery recovery adapter; each database statement has its own bounded template. */
@Configuration
@ConditionalOnProperty(prefix = LakeProperties.PREFIX, name = ["enabled"], havingValue = "true")
class LakeSettlementSchedulingConfiguration {
    @Bean
    fun lakeSettlementScheduler(runtime: LakeAppendRuntime): LakeSettlementScheduler = LakeSettlementScheduler(runtime.sweep)
}

/** Runs on the application's existing scheduler, with a finite rotating page and a ten-second work budget. */
class LakeSettlementScheduler(
    private val sweep: LakeSettlementSweep,
) {
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS)
    @Suppress("TooGenericExceptionCaught") // A timed-out observation defers recovery; never expose storage/library exception text.
    fun sweep() {
        try {
            sweep.runOnce()
        } catch (failure: Exception) {
            log.warn("event=lake.settle.sweep_deferred cause={} sql_state={}", FailureShape.cause(failure), FailureShape.sqlState(failure))
        }
    }

    private companion object {
        const val SWEEP_INTERVAL_MILLIS = 60_000L
        val log = LoggerFactory.getLogger(LakeSettlementScheduler::class.java)
    }
}
