package co.datapipelines.config

import co.datapipelines.web.endpoints.ConversionLocks
import co.datapipelines.web.endpoints.StartupConversion
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The app module's own bean wiring (015, module-structure.md §8.4): `app` is the
 * composition root, and its one collaborator is declared here explicitly like every
 * other module's beans.
 */
@Configuration
class AppConfiguration {
    @Bean
    fun configValidator(environment: Environment): ConfigValidator = ConfigValidator(environment)

    /**
     * #786 §E.1 — the upgrade's in-place conversion families, run after Flyway and before serving or dispatch; #895 —
     * in ONE transaction on the metadata transaction manager, under the runner's G0 acquisitions ([ConversionLocks]).
     * [steps] arrive in their beans' declared order (the APIs, then the schedules).
     */
    @Bean
    fun conversionStartup(
        steps: List<StartupConversion>,
        transactionManager: PlatformTransactionManager,
        locks: ConversionLocks,
    ): ConversionStartup = ConversionStartup(steps, TransactionTemplate(transactionManager), locks)

    companion object {
        /**
         * #804 R2 — [ConfigValidatorPrecedence]. Static (`@JvmStatic` on the companion) so Spring can run it before
         * [AppConfiguration] itself is instantiated, as a bean factory post-processor must be.
         */
        @JvmStatic
        @Bean
        fun configValidatorPrecedence(): BeanFactoryPostProcessor = ConfigValidatorPrecedence()
    }
}

/**
 * #804 R2 — the §7 report runs BEFORE the beans that consume the keys it judges, by an explicit link rather than by
 * registration order: every consumer bean definition gains `dependsOn` [VALIDATOR], so creating it creates the
 * validator first, and a bad value refuses startup with the catalogued report instead of the consumer's own
 * `IllegalArgumentException` or bind failure. Registration order is not enough: many web beans inject a
 * `StagingFactory`, and any of them created ahead of [VALIDATOR] would pull the staging bean in with it.
 *
 * The consumers of the §3.36 DuckDB staging admission keys (`LakeRules.STAGING_ADMISSION_KEYS`):
 * - [STAGING_FACTORY], `DomainConfiguration.stagingFactory`, which binds them on every deployment, lake or not. It
 *   is always defined, so its absence refuses startup here rather than silently dropping the link;
 * - the bean of class [LAKE_PROPERTIES_CLASS], bound by `LakeAutoConfiguration` only while the lake is enabled —
 *   linked when defined. Named as a string because `modules/lake` is not on this module's compile classpath.
 */
internal class ConfigValidatorPrecedence : BeanFactoryPostProcessor {
    // SpreadOperator: `setDependsOn` is a Java varargs setter with no array overload; the copy is of a handful of
    // bean names, once, at startup (the same reasoning as `DatapipelinesApplication.main`).
    @Suppress("SpreadOperator")
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        check(beanFactory.containsBeanDefinition(VALIDATOR)) { "#804: no '$VALIDATOR' bean definition to order the key consumers after." }
        check(beanFactory.containsBeanDefinition(STAGING_FACTORY)) {
            "#804: no '$STAGING_FACTORY' bean definition — the staging admission consumer was renamed or removed; " +
                "update ConfigValidatorPrecedence so the §7 report still runs before it."
        }
        val lakeProperties =
            beanFactory.beanDefinitionNames.filter { beanFactory.getBeanDefinition(it).beanClassName == LAKE_PROPERTIES_CLASS }
        (listOf(STAGING_FACTORY) + lakeProperties).forEach { name ->
            val definition = beanFactory.getBeanDefinition(name)
            val existing = definition.dependsOn.orEmpty()
            if (VALIDATOR !in existing) definition.setDependsOn(*existing, VALIDATOR)
        }
    }

    companion object {
        /** [AppConfiguration.configValidator]'s bean name. */
        const val VALIDATOR = "configValidator"

        /** `DomainConfiguration.stagingFactory`'s bean name (modules/web). */
        const val STAGING_FACTORY = "stagingFactory"

        /** The lake's `@ConfigurationProperties` class (modules/lake), bound only while the lake is enabled. */
        const val LAKE_PROPERTIES_CLASS = "co.datapipelines.lake.LakeProperties"
    }
}
