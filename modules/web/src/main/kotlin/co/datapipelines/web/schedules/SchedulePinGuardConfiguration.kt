package co.datapipelines.web.schedules

import co.datapipelines.application.dependencies.SchedulePipelineConsumers
import co.datapipelines.pipeline.ScheduleConsumers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The schedule side of the exact-pin guards (#786; record §14, §15.1). `web` assembles it because `web` owns the
 * pipeline executor's `target_ref` vocabulary ([PipelineJobExecutor.TARGET_PREFIX]) and the scheduler core may know
 * no pipeline type.
 */
@Configuration
class SchedulePinGuardConfiguration {
    /**
     * B.4 — the pipeline aggregate's answer to "which schedules run this release?" (A39): a pipeline version discard or
     * purge refuses while a live schedule — paused and blocked included — runs it.
     */
    @Bean
    fun scheduleConsumers(jdbc: NamedParameterJdbcTemplate): ScheduleConsumers =
        SchedulePipelineConsumers(jdbc, PipelineJobExecutor.TARGET_PREFIX)
}
