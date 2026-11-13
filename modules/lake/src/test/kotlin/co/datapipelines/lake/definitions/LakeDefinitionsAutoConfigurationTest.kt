package co.datapipelines.lake.definitions

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.LakeAutoConfiguration
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The definitions' beans follow the lake's switch: none exists by default, and `datapipelines.lake.enabled=true`
 * declares exactly the two repositories, the applier and the service, wired to the context's one transaction manager
 * and audit sink, without touching the database while the context starts. Spring Boot finds the configuration through
 * the module's `AutoConfiguration.imports`.
 */
class LakeDefinitionsAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                    JdbcTemplateAutoConfiguration::class.java,
                    LakeAutoConfiguration::class.java,
                    LakeDefinitionsAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(AuditSinkConfiguration::class.java)
            .withPropertyValues(
                // Never connected to while the context starts: Hikari opens lazily, nothing here queries it.
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/never",
                "spring.datasource.username=dp",
                "spring.datasource.password=catalog-password",
            )

    @Test
    fun `disabled by default - no definitions bean exists`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            BEANS.forEach { type -> context.getBeanNamesForType(type).toList() shouldBe emptyList() }
        }
    }

    @Test
    fun `enabled - the repositories, applier and service exist once each`() {
        runner
            .withPropertyValues(
                "datapipelines.lake.enabled=true",
                "datapipelines.lake.storage.bucket=dp-lake",
                "datapipelines.lake.storage.region=eu-west-1",
                "datapipelines.lake.storage.secret-key=never-printed-secret",
                "datapipelines.lake.storage.access-key=never-printed-access",
            ).run { context ->
                context.startupFailure shouldBe null
                BEANS.forEach { type -> context.getBeanNamesForType(type).size shouldBe 1 }
            }
    }

    @Test
    fun `Spring Boot discovers the configuration through AutoConfiguration_imports`() {
        val resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        val listed =
            javaClass.classLoader
                .getResources(resource)
                .toList()
                .flatMap { it.readText().lines() }
                .map { it.trim() }
        listed shouldContain LakeDefinitionsAutoConfiguration::class.java.name
    }

    /** The app's `AuditLogger` stands in as a recording sink; the context only needs one to exist. */
    @Configuration(proxyBeanMethods = false)
    class AuditSinkConfiguration {
        @Bean
        fun auditEventSink(): AuditEventSink = RecordingAuditSink()
    }

    private companion object {
        val BEANS =
            listOf(
                LakeDefinitionRepository::class.java,
                LakeApplyRepository::class.java,
                LakeDefinitionApplier::class.java,
                LakeDefinitionService::class.java,
            )
    }
}
