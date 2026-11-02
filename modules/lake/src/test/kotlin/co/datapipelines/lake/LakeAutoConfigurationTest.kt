package co.datapipelines.lake

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.s3.S3Client

/**
 * The module is inert unless `datapipelines.lake.enabled=true` (module-structure §5.21): no bean of it exists by
 * default, and enabling it declares the five beans — none of which touches the network or the database while
 * the context starts (the boot check runs from `web`'s `LakeCatalogStartupCheck`, after Flyway). Spring Boot
 * finds the configuration through the module's `AutoConfiguration.imports`.
 */
class LakeAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(DataSourceAutoConfiguration::class.java, JdbcTemplateAutoConfiguration::class.java, LakeAutoConfiguration::class.java),
            ).withPropertyValues(
                // Never connected to while the context starts: Hikari opens lazily, nothing here queries it.
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/never",
                "spring.datasource.username=dp",
                "spring.datasource.password=catalog-password",
            )

    @Test
    fun `disabled by default - no bean of the module exists`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            LAKE_BEANS.forEach { type -> context.getBeanNamesForType(type).toList() shouldBe emptyList() }
        }
    }

    @Test
    fun `enabled - the leases, catalogs, S3 client and boot check exist, bound to the lake's keys`() {
        runner
            .withPropertyValues(
                "datapipelines.lake.enabled=true",
                "datapipelines.lake.storage.bucket=dp-lake",
                "datapipelines.lake.storage.region=eu-west-1",
                "datapipelines.lake.storage.secret-key=never-printed-secret",
                "datapipelines.lake.storage.access-key=never-printed-access",
            ).run { context ->
                context.startupFailure shouldBe null
                LAKE_BEANS.forEach { type -> context.getBeanNamesForType(type).size shouldBe 1 }
                val properties = context.getBean(LakeProperties::class.java)
                properties.storage.root shouldBe "s3://dp-lake/lake"
                properties.toString() shouldNotContain "never-printed-secret"
                properties.toString() shouldNotContain "never-printed-access"
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
        listed shouldContain LakeAutoConfiguration::class.java.name
    }

    private companion object {
        val LAKE_BEANS =
            listOf(LakeProperties::class.java, LakeLeases::class.java, LakeCatalogs::class.java, S3Client::class.java, LakeBootCheck::class.java)
    }
}
