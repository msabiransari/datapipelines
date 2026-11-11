package co.datapipelines.web.config

import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.ReleaseDependencyFamilies
import co.datapipelines.application.dependencies.StatementCeiling
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.schedule.ScheduleDependencyFamily
import co.datapipelines.dependencies.FamilyDescriptor
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.FamilyRegistry
import co.datapipelines.scheduler.JobExecutor
import co.datapipelines.web.release.ReleaseConfiguration
import co.datapipelines.web.release.ReleaseWiringCollaborators
import co.datapipelines.web.schedules.PipelineJobExecutor
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * #874 G3 §C.4 (mode A) — `DependencyGraphNonArtifactConfiguration` joins the registry `ReleaseConfiguration` assembles
 * from every descriptor bean, without an edit there: with the API family's bean (#786) present the registry holds EVERY
 * production family key (`FamilyKey.PRODUCTION`, eight at this base — the count is that map's, never a literal), each
 * once; the datasource and schedule descriptors read under the ONE PostgreSQL ceiling and through repositories over its
 * own template; the schedule family's extractor is the pipeline executor bean.
 */
class DependencyGraphNonArtifactConfigurationWiringTest {
    private val context =
        ApplicationContextRunner().withUserConfiguration(
            ReleaseConfiguration::class.java,
            ReleaseWiringCollaborators::class.java,
            EndpointFamily::class.java,
            Executors::class.java,
            DependencyGraphNonArtifactConfiguration::class.java,
        )

    @Test
    fun `the registry holds every production family exactly once - the two non-artifact families included`() {
        context.run { context ->
            val keys = context.getBeansOfType(FamilyDescriptor::class.java).values.map { it.key }
            keys.size shouldBe FamilyKey.PRODUCTION.size
            keys.toSet() shouldBe FamilyKey.PRODUCTION.keys
            context.getBean(FamilyRegistry::class.java).keys shouldBe FamilyKey.PRODUCTION.keys
            context.getBean(FamilyRegistry::class.java)[FamilyKey.DATASOURCE]?.capabilities shouldBe DatasourceDependencyFamily.CAPABILITIES
            context.getBean(FamilyRegistry::class.java)[FamilyKey.SCHEDULE]?.capabilities shouldBe ScheduleDependencyFamily.CAPABILITIES
        }
    }

    @Test
    fun `both non-artifact descriptors read under the one ceiling and through its own template`() {
        context.run { context ->
            context.getBeansOfType(StatementCeiling::class.java).size shouldBe 1
            val ceiling = context.getBean(PostgresStatementCeiling::class.java)
            listOf(FamilyKey.DATASOURCE, FamilyKey.SCHEDULE).forEach { key ->
                val descriptor = context.getBean(FamilyRegistry::class.java)[key].shouldBeInstanceOf<FamilyDescriptor<*>>()
                withClue(key) {
                    ReleaseDependencyFamilies.ceilingOf(descriptor) shouldBeSameInstanceAs ceiling
                    val templates = templatesReachable(descriptor.provider, ceiling)
                    templates.shouldNotBeEmpty()
                    templates.forEach { it shouldBeSameInstanceAs ceiling.jdbc }
                }
            }
        }
    }

    @Test
    fun `the schedule family reads targets through the pipeline executor bean`() {
        context.run { context ->
            val descriptor = context.getBean(FamilyRegistry::class.java)[FamilyKey.SCHEDULE].shouldBeInstanceOf<FamilyDescriptor<*>>()
            val extractors = ScheduleDependencyFamily.extractorsOf(descriptor)
            extractors.keys.toList() shouldContainExactly listOf(PipelineJobExecutor.EXECUTOR_ID)
            extractors[PipelineJobExecutor.EXECUTOR_ID] shouldBeSameInstanceAs context.getBean(JobExecutor::class.java)
        }
    }

    @Test
    fun `two extractors for one executor id stop the boot - the deferred read is still fail-fast`() {
        ApplicationContextRunner()
            .withUserConfiguration(
                ReleaseConfiguration::class.java,
                ReleaseWiringCollaborators::class.java,
                EndpointFamily::class.java,
                Executors::class.java,
                SecondPipelineExecutor::class.java,
                DependencyGraphNonArtifactConfiguration::class.java,
            ).run { context ->
                val failure = context.startupFailure.shouldBeInstanceOf<Throwable>()
                generateSequence(failure) { it.cause }.last().message shouldContain "one executor-target extractor per executor id"
            }
    }

    /** Every JDBC template reachable from [root] through this codebase's own objects, never descending into [ceiling]. */
    private fun templatesReachable(
        root: Any,
        ceiling: Any,
    ): List<Any> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<Any>()

        fun visit(
            value: Any?,
            depth: Int,
        ) {
            if (value == null || value === ceiling || !seen.add(value)) return
            if (value is JdbcOperations || value is NamedParameterJdbcOperations) {
                found += value
                return
            }
            if (depth > MAX_DEPTH || value is Enum<*> || !value.javaClass.name.startsWith(OWN)) return
            generateSequence<Class<*>>(value.javaClass) { it.superclass }
                .takeWhile { it.name.startsWith(OWN) }
                .flatMap { it.declaredFields.asSequence() }
                .filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive }
                .forEach { field ->
                    field.isAccessible = true
                    visit(field.get(value), depth + 1)
                }
        }
        visit(root, 0)
        return found
    }

    /** The API family's production bean method, as `ReleaseStatementCeilingWiringTest` declares it. */
    @Configuration
    @Suppress("unused") // read by the context runner
    class EndpointFamily {
        @Bean
        fun endpointDependencyFamily(ceiling: PostgresStatementCeiling): FamilyDescriptor<*> =
            EndpointsConfiguration().endpointDependencyFamily(ceiling)
    }

    /**
     * The pipeline executor bean (its behaviour is `PipelineJobExecutorTest`'s); its type and id matter here, and so does
     * the dependency production's has: through the pipeline service it reaches the family registry, so an eager executor
     * list in the schedule descriptor is a circular reference this context refuses to start with.
     */
    @Configuration
    @Suppress("unused") // read by the context runner
    class Executors {
        @Bean
        fun pipelineJobExecutor(
            @Suppress("UNUSED_PARAMETER") registry: FamilyRegistry,
        ): JobExecutor = mockk<PipelineJobExecutor>(relaxed = true) { every { executorId } returns PipelineJobExecutor.EXECUTOR_ID }
    }

    /** A second executor bean claiming the pipeline executor's id. */
    @Configuration
    @Suppress("unused") // read by the context runner
    class SecondPipelineExecutor {
        @Bean
        fun secondPipelineJobExecutor(): JobExecutor =
            mockk<PipelineJobExecutor>(relaxed = true) { every { executorId } returns PipelineJobExecutor.EXECUTOR_ID }
    }

    private companion object {
        const val OWN = "co.datapipelines."

        /** provider → repository → template is two hops; the walk allows a little more. */
        const val MAX_DEPTH = 6
    }
}
