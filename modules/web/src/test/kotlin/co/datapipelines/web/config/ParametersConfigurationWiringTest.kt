package co.datapipelines.web.config

import co.datapipelines.parameters.ParameterEvaluator
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetValidator
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.parameters.ParametersProperties
import co.datapipelines.parameters.SelectorPool
import co.datapipelines.parameters.SelectorRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The parameter engine's surface graph (record §2.4, lane D's A.1): `ParametersConfiguration`
 * builds the runner, the evaluator and the ONE `SelectorPool` from one `ParametersConfig`
 * (C27 — three constructions from one config is what keeps the statement-level and
 * evaluate-level limits from disagreeing), every frozen collaborator resolves, and the
 * `parameters.selectors.abandoned` gauge scrapes a REAL pool over a REAL registry (the
 * 194c pass, observation 4: the scrape is also the dead-thread prune's only caller — a
 * strict mock would make a missing call unobservable, so the pool here is real).
 */
class ParametersConfigurationWiringTest {
    private val context =
        ApplicationContextRunner()
            .withUserConfiguration(ParametersConfiguration::class.java, Collaborators::class.java)

    @Test
    fun `the whole graph builds - service, validator, runner, evaluator and the one pool`() {
        context.run { it ->
            it.getBean(ParameterSetService::class.java) shouldNotBe null
            it.getBean(ParameterSetValidator::class.java) shouldNotBe null
            it.getBean(SelectorRunner::class.java) shouldNotBe null
            it.getBean(ParameterEvaluator::class.java) shouldNotBe null
            it.getBean(SelectorPool::class.java) shouldNotBe null
            // One pool: the runner's, the evaluator's and the gauge's are the same bean.
            val pool = it.getBean(SelectorPool::class.java)
            it.getBean(ParameterEvaluator::class.java) shouldBe it.getBean(ParameterEvaluator::class.java)
            pool.size shouldBe it.getBean(ParametersConfig::class.java).maxConcurrentSelectorQueries
        }
    }

    @Test
    fun `the bound properties reach the domain config, bounds enforced at construction`() {
        context.run { it ->
            val config = it.getBean(ParametersConfig::class.java)
            config.maxParametersPerSet shouldBe 64
            config.maxConcurrentSelectorQueries shouldBe 4
            config.maxWaitingSelectorQueries shouldBe 64
        }
    }

    @Test
    fun `a property override binds through ParametersProperties into the shared config and the pool's size`() {
        val properties =
            mapOf(
                "datapipelines.parameters.max-concurrent-selector-queries" to "2",
                "datapipelines.parameters.max-waiting-selector-queries" to "8",
            )
        val source = MapConfigurationPropertySource(properties)
        val bound =
            Binder(source).bind("datapipelines.parameters", Bindable.of(ParametersProperties::class.java)).get().toConfig()
        bound.maxConcurrentSelectorQueries shouldBe 2
        SelectorPool(bound).size shouldBe 2
        SelectorPool(bound).queue shouldBe 10
    }

    /** The gauge over a REAL pool + REAL registry (MISTAKES: a strict mock hides the missing call). */
    @Test
    fun `the gauge reads the pool's abandoned adder and the scrape prunes dead worker threads`() {
        val pool = SelectorPool(ParametersConfig())
        val configuration = ParametersConfiguration()
        val meters = SimpleMeterRegistry()
        configuration.parametersSelectorsAbandonedGauge(pool, meters)

        pool.abandoned.increment()
        val gauge = meters.get("parameters.selectors.abandoned").gauge()
        gauge.value() shouldBe 1.0

        // Observation 4 (194c security pass): the scrape is the prune's caller. Plant a DEAD
        // worker Thread in the pool's abandoned set the way a real abandonment leaves it, read
        // the gauge (Micrometer's scrape), and the dead entry must be gone.
        val field = SelectorPool::class.java.getDeclaredField("abandonedThreads")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val threads = field.get(pool) as MutableSet<Thread>
        val dead =
            Thread({}, "selector-dead-test").apply {
                start()
                join()
            }
        threads.add(dead)
        gauge.value() shouldBe 1.0
        threads.none { it === dead } shouldBe true
    }

    /** The frozen collaborators the graph needs, as beans a test context can resolve. */
    @Configuration
    @Suppress("unused")
    class Collaborators {
        private fun dataSource(): javax.sql.DataSource = io.mockk.mockk(relaxed = true)

        @Bean
        fun jdbcTemplate(): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(dataSource())

        @Bean
        fun templateEngines(): co.datapipelines.templates.WorkspaceTemplateEngines =
            co.datapipelines.templates.WorkspaceTemplateEngines(
                co.datapipelines.templates.TemplateRepository(NamedParameterJdbcTemplate(dataSource())),
                cacheSize = 64,
                renderTimeoutMs = 5_000,
                maxOutputChars = 1_000_000,
            )

        @Bean
        fun templateRepository(): co.datapipelines.templates.TemplateRepository =
            co.datapipelines.templates.TemplateRepository(NamedParameterJdbcTemplate(dataSource()))

        @Bean
        fun templateDryRenderer(
            engines: co.datapipelines.templates.WorkspaceTemplateEngines,
        ): co.datapipelines.pipeline.TemplateDryRenderer = co.datapipelines.templates.TemplateDryRendererImpl(engines)

        @Bean
        fun datasources(): co.datapipelines.datasources.DatasourceRegistry = io.mockk.mockk(relaxed = true)

        @Bean
        fun contractDatasources(): co.datapipelines.pipeline.DatasourceRegistry = io.mockk.mockk(relaxed = true)

        @Bean
        fun authoringGuard(): co.datapipelines.pipeline.AuthoringGuard = co.datapipelines.pipeline.AuthoringGuard(true)

        @Bean
        fun orgContext(): co.datapipelines.pipeline.OrgContext = co.datapipelines.pipeline.OrgContext.DEFAULTS

        @Bean
        fun transactionManager(): org.springframework.transaction.PlatformTransactionManager = io.mockk.mockk(relaxed = true)

        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }
}
