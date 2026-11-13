package co.datapipelines.web.config

import co.datapipelines.application.dependencies.IncomingArmCatalog
import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.schedule.ExecutorTargetExtractor
import co.datapipelines.application.dependencies.schedule.ScheduleDependencyFamily
import co.datapipelines.datasources.DatasourceGraphFacts
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.scheduler.JobExecutor
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The dependency graph's two NON-ARTIFACT families (#874 G3; record §3.2, §4): the datasource and schedule descriptors,
 * one owner per file — `ReleaseConfiguration` assembles the registry from every `FamilyDescriptor` bean (mode A, stamped
 * 2026-11-11), so these two join it with no edit there, and its duplicate-key refusal still holds.
 *
 * Both read under the ONE `PostgresStatementCeiling` bean and through repositories built over ITS template
 * (`ceiling.jdbc`), never the shared repository beans: only that template's per-statement hook bounds each statement
 * (#786 R3, record §25.4). The schedule family's target extractors are the [JobExecutor] beans that implement
 * [ExecutorTargetExtractor] (the pipeline executor); an executor without one yields an unreadable target, never a
 * schedule without an edge.
 *
 * The executors are looked up LAZILY: the pipeline executor depends on the pipeline service, which reaches the family
 * registry this descriptor joins (through the release service and the restore guard), so taking the executors as a
 * constructor list is a circular bean reference and the application does not start. [scheduleTargetExtractorCheck]
 * resolves them once every singleton exists — a duplicate executor id still stops the boot.
 */
@Configuration
class DependencyGraphNonArtifactConfiguration {
    /** Record §4 row `datasource`: an unversioned leaf by name; a graph read never tests connectivity. */
    @Bean
    fun datasourceDependencyFamily(ceiling: PostgresStatementCeiling): FamilyDescriptor<DatasourceGraphFacts> =
        DatasourceDependencyFamily.descriptor(
            DatasourceRepository(ceiling.jdbc),
            ceiling,
            incoming = IncomingArmCatalog.datasource(PipelineRepository(ceiling.jdbc), ParameterSetRepository(ceiling.jdbc), ceiling),
        )

    /** Record §4 row `schedule`: a revisioned workspace object with exactly one `EXECUTOR_TARGET` edge. */
    @Bean
    fun scheduleDependencyFamily(
        ceiling: PostgresStatementCeiling,
        executors: ObjectProvider<JobExecutor>,
    ): FamilyDescriptor<*> =
        ScheduleDependencyFamily.descriptor(
            ScheduleRepository(ceiling.jdbc, SchedulerAutoConfiguration.JSON),
            { executors.orderedStream().toList().filterIsInstance<ExecutorTargetExtractor>() },
            ceiling,
        )

    /** Resolves the schedule family's extractors after every singleton exists: fail-fast, without the bean cycle. */
    @Bean
    fun scheduleTargetExtractorCheck(registry: FamilyRegistry): SmartInitializingSingleton =
        SmartInitializingSingleton {
            ScheduleDependencyFamily.extractorsOf(checkNotNull(registry[FamilyKey.SCHEDULE]) { "the schedule family is registered" })
        }
}
