package co.datapipelines.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.env.MockEnvironment

/**
 * #804 — the acceptance case, through the STARTUP path: with the lake DISABLED, a DuckDB staging
 * admission value DuckDB itself refuses (`staging-memory-limit` below the engine's 1 MiB floor) is
 * refused at startup by the config validator's catalogued §7 path — [IllegalStateException], the
 * "Invalid configuration — startup refused" report naming the key. Before #804 the same lake-less
 * deployment met only `DuckDbStagingProperties`' bare `IllegalArgumentException` at bean
 * construction, and the cross-key reservation rule was not checked anywhere.
 *
 * The environment is a bare [MockEnvironment] with only the case's properties set: everything else
 * is absent, which the §7 report answers with its own required-key violations folded in — the
 * assertions below are `contains`, because the report names every offending key at once. The lake is
 * explicitly `false`: these two admission values are judged with no lake anywhere in the picture.
 */
class StagingAdmissionStartupTest {
    @Test
    fun `804 - a lake-less deployment with a staging memory limit below DuckDB's floor is refused at startup, catalogued`() {
        val environment = MockEnvironment()
        environment.setProperty("datapipelines.lake.enabled", "false")
        environment.setProperty("datapipelines.lake.admission.staging-memory-limit", "512KB")

        val error =
            runCatching { ConfigValidator(environment).validateOnStartup() }
                .exceptionOrNull()
                ?: error("startup validation passed; the floor violation was never named")

        // The §7 catalogued report — the validator's IllegalStateException, not a binder crash and
        // not a staging require-check: it names the key and the 1 MiB floor.
        check(error is IllegalStateException) { "expected the validator's IllegalStateException, got: $error" }
        error.message!! shouldStartWith "Invalid configuration — startup refused"
        error.message!! shouldContain "datapipelines.lake.admission.staging-memory-limit"
        error.message!! shouldContain "at least 1 MiB"
    }

    @Test
    fun `804 - a lake-less deployment whose scratch cannot hold one reservation is refused at startup, catalogued`() {
        val environment = MockEnvironment()
        environment.setProperty("datapipelines.lake.enabled", "false")
        // One default reservation is staging-memory-limit × staging-expansion-factor = 1GB × 3 = 3GB;
        // 2GB cannot hold it. All three are set explicitly: this is the operator-configured trio of a
        // lake-less deployment (a bare MockEnvironment carries no application.yml, so an unset limit
        // or factor would be named as malformed before the reservation rule can judge anything).
        environment.setProperty("datapipelines.lake.admission.staging-memory-limit", "1GB")
        environment.setProperty("datapipelines.lake.admission.staging-expansion-factor", "3")
        environment.setProperty("datapipelines.lake.admission.scratch-bytes", "2GB")

        val error =
            runCatching { ConfigValidator(environment).validateOnStartup() }
                .exceptionOrNull()
                ?: error("startup validation passed; the reservation violation was never named")

        check(error is IllegalStateException) { "expected the validator's IllegalStateException, got: $error" }
        error.message!! shouldStartWith "Invalid configuration — startup refused"
        error.message!! shouldContain "datapipelines.lake.admission.scratch-bytes"
        error.message!! shouldContain "one DuckDB staging reservation"
    }

    /**
     * #804 R2 — the order link's own wiring ([ConfigValidatorPrecedence]); `StagingAdmissionBootTest` proves its
     * effect at a real boot. With the lake ENABLED, `LakeAutoConfiguration` registers `LakeProperties` through Boot's
     * own `@EnableConfigurationProperties` path — the bean is found by its class name and linked too. Every
     * definition is made lazy so nothing is instantiated: the case reads the definitions, not the beans.
     */
    @Test
    fun `804 R2 - the staging factory and an enabled lake's properties both depend on the validator`() {
        ApplicationContextRunner()
            .withPropertyValues("datapipelines.lake.enabled=true")
            .withInitializer { context ->
                val registry = context as GenericApplicationContext
                registry.registerBeanDefinition(ConfigValidatorPrecedence.VALIDATOR, RootBeanDefinition(Any::class.java))
                registry.registerBeanDefinition(
                    ConfigValidatorPrecedence.STAGING_FACTORY,
                    RootBeanDefinition(Any::class.java).apply { setDependsOn("somethingElse") },
                )
                registry.addBeanFactoryPostProcessor { factory ->
                    factory.beanDefinitionNames.forEach { factory.getBeanDefinition(it).isLazyInit = true }
                }
                registry.addBeanFactoryPostProcessor(ConfigValidatorPrecedence())
            }.withUserConfiguration(Class.forName("co.datapipelines.lake.LakeAutoConfiguration"))
            .run { context ->
                check(context.startupFailure == null) { "context failed: ${context.startupFailure}" }
                val factory = context.beanFactory
                val lake = context.getBeanNamesForType(Class.forName(ConfigValidatorPrecedence.LAKE_PROPERTIES_CLASS), true, false)
                lake.toList() shouldHaveSize 1
                factory.getBeanDefinition(lake.single()).dependsOn.orEmpty().toList() shouldContain ConfigValidatorPrecedence.VALIDATOR
                // An existing dependsOn is kept, and the validator is added once.
                factory.getBeanDefinition(ConfigValidatorPrecedence.STAGING_FACTORY).dependsOn.orEmpty().toList() shouldBe
                    listOf("somethingElse", ConfigValidatorPrecedence.VALIDATOR)
            }
    }

    /** #804 R2 — a renamed or removed staging bean refuses startup by name; the link never silently disappears. */
    @Test
    fun `804 R2 - without a stagingFactory definition the order link refuses, naming it`() {
        val factory = DefaultListableBeanFactory()
        factory.registerBeanDefinition(ConfigValidatorPrecedence.VALIDATOR, RootBeanDefinition(Any::class.java))
        val failure = shouldThrow<IllegalStateException> { ConfigValidatorPrecedence().postProcessBeanFactory(factory) }
        failure.message.orEmpty() shouldContain "no 'stagingFactory' bean definition"
    }
}
