package co.datapipelines.web.dependencies

import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionService
import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.scheduler.ScheduleService
import co.datapipelines.templates.TemplateService
import co.datapipelines.visualization.DashboardService
import co.datapipelines.visualization.VisualizationService
import co.datapipelines.web.config.ReleaseDependencyProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

/**
 * **The §25.4 keys reach the tree walk through the wiring, not as constants** (#875 G4 §D.1). The real
 * [DependenciesConfiguration] is assembled by Spring from `datapipelines.release.dependencies.*` property values — the
 * three keys are #786 U1's, bound by [ReleaseDependencyProperties] — and its `dependencyTreeService` bean walks a stub
 * pipeline family: the configured `max-resolve-batch-size` is the batch the provider receives, and the walk's
 * `ResolveContext` carries a deadline of `metadata-timeout-seconds` with `statement-timeout-seconds` as the statement
 * bound. A bean that read a constant (the default 256, 30 s, 5 s) is red here.
 */
class DependenciesConfigConsumedTest {
    private val context =
        ApplicationContextRunner()
            .withUserConfiguration(DependenciesConfiguration::class.java, Collaborators::class.java)
            .withPropertyValues(
                "datapipelines.release.dependencies.max-resolve-batch-size=2",
                "datapipelines.release.dependencies.metadata-timeout-seconds=7",
                "datapipelines.release.dependencies.statement-timeout-seconds=3",
            )

    @Test
    fun `the configured batch size, deadline and statement limit are what the interactive walk runs under`() {
        context.run { context ->
            val pipelines = context.getBean(StubFamily::class.java)
            val children = (1..WIDTH).map { exact(FamilyKey.PIPELINE, "acme/child_$it", 1) }
            children.forEach { pipelines.put(it) }
            pipelines.put(exact(FamilyKey.PIPELINE, "acme/root", 1), Lifecycle.RELEASED, *children.toTypedArray())
            val remaining = ArrayList<Duration>()
            val statementBounds = ArrayList<Duration>()
            pipelines.beforeBatch = { _, resolve ->
                remaining += checkNotNull(resolve.deadline.remaining()) { "the tree walk's deadline is bounded" }
                statementBounds += checkNotNull(resolve.statementBound()) { "the tree walk's statements are bounded" }
            }

            val tree = context.getBean(DependencyTreeService::class.java).tree(member(), FamilyKey.PIPELINE, "name=acme/root&version=1")

            tree.nodes.size shouldBe WIDTH + 1
            pipelines.calls.map { it.size } shouldBe listOf(1) + List(WIDTH / 2) { 2 }
            // The system clock runs: the first read starts a little under the configured 7 s.
            remaining.first() shouldBeLessThanOrEqualTo Duration.ofSeconds(7)
            remaining.first() shouldBeGreaterThan Duration.ofSeconds(6)
            statementBounds.toSet() shouldBe setOf(Duration.ofSeconds(3))
        }
    }

    /** What `DependenciesConfiguration` is assembled with: the bound keys, a stub pipeline registry, inert services. */
    @Configuration
    @EnableConfigurationProperties(ReleaseDependencyProperties::class)
    @Suppress("unused", "TooManyFunctions") // read by the context runner; one bean per collaborator
    internal class Collaborators {
        @Bean
        fun releaseBounds(properties: ReleaseDependencyProperties): ReleaseBounds = properties.toBounds()

        @Bean
        fun pipelineFamily(): StubFamily = StubFamily(FamilyKey.PIPELINE)

        @Bean
        fun familyRegistry(pipelines: StubFamily): FamilyRegistry = FamilyRegistry(listOf(pipelines.descriptor()))

        @Bean
        fun metadataTransactionManager(): PlatformTransactionManager = DataSourceTransactionManager(savepointCapable())

        @Bean
        fun promoterLens(): PromoterLens = PromoterLens { LensedView.EVERYTHING }

        @Bean
        fun objectMapper(): ObjectMapper = jacksonObjectMapper()

        @Bean
        fun pipelineService(): PipelineService = mockk(relaxed = true)

        @Bean
        fun templateService(): TemplateService = mockk(relaxed = true)

        @Bean
        fun parameterSetService(): ParameterSetService = mockk(relaxed = true)

        @Bean
        fun visualizationService(): VisualizationService = mockk(relaxed = true)

        @Bean
        fun dashboardService(): DashboardService = mockk(relaxed = true)

        @Bean
        fun endpointPublishService(): EndpointPublishService = mockk(relaxed = true)

        @Bean
        fun endpointVersionService(): EndpointVersionService = mockk(relaxed = true)

        @Bean
        fun scheduleService(): ScheduleService = mockk(relaxed = true)
    }

    private companion object {
        const val WIDTH = 300

        /**
         * A metadata DataSource whose relaxed connections admit savepoints: the production tree reads speculatively inside
         * a savepoint (`ReleasePlanningWalk.speculativeReads`), which a driver without savepoints refuses.
         */
        fun savepointCapable(): DataSource =
            mockk(relaxed = true) {
                every { connection } answers { mockk<Connection>(relaxed = true) { every { metaData.supportsSavepoints() } returns true } }
            }
    }
}
