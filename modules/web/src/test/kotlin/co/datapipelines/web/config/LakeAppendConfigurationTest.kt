package co.datapipelines.web.config

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.PoolInvalidationPublisher
import co.datapipelines.lake.LakeAutoConfiguration
import co.datapipelines.lake.LakeLeases
import co.datapipelines.lake.append.LakeAppendAutoConfiguration
import co.datapipelines.lake.append.LakeAppendRuntime
import co.datapipelines.lake.definitions.LakeDefinitionsAutoConfiguration
import co.datapipelines.pipeline.LakeAppendPort
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager

/** Actual enabled/disabled bean graph: web adapters assemble the neutral lake runtime without a cycle or duplicate lease bean. */
class LakeAppendConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    JdbcTemplateAutoConfiguration::class.java,
                    LakeAutoConfiguration::class.java,
                    LakeDefinitionsAutoConfiguration::class.java,
                    LakeAppendAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(LakeAppendConfiguration::class.java, LakeSettlementSchedulingConfiguration::class.java)
            .withBean(PlatformTransactionManager::class.java, { mockk() })
            .withBean(AuditEventSink::class.java, { mockk() })
            .withBean(DatasourceRegistry::class.java, { mockk() })
            .withBean("contractDatasourceRegistry", co.datapipelines.pipeline.DatasourceRegistry::class.java, { mockk() })
            .withBean(PoolInvalidationPublisher::class.java, { mockk() })
            .withBean(WebSurfaceConfiguration.ExecutionCoroutineScope::class.java, { WebSurfaceConfiguration.ExecutionCoroutineScope() })
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/never",
                "spring.datasource.username=dp",
                "spring.datasource.password=unused-test-value",
                "datapipelines.duckdb.extension-directory=",
                "datapipelines.lake.storage.bucket=unused-test-bucket",
                "datapipelines.lake.storage.region=us-east-1",
                "datapipelines.lake.storage.access-key=unused-test-access",
                "datapipelines.lake.storage.secret-key=unused-test-secret",
            )

    @Test
    fun `disabled default creates no append port runtime or scheduled adapter`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(LakeAppendPort::class.java).size shouldBe 0
            context.getBeansOfType(LakeAppendRuntime::class.java).size shouldBe 0
            context.getBeansOfType(LakeSettlementScheduler::class.java).size shouldBe 0
        }
    }

    @Test
    fun `enabled binds the actual production graph with one public lease bean`() {
        runner.withPropertyValues("datapipelines.lake.enabled=true").run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(LakeAppendPort::class.java).size shouldBe 1
            context.getBeansOfType(LakeAppendRuntime::class.java).size shouldBe 1
            context.getBeansOfType(LakeSettlementScheduler::class.java).size shouldBe 1
            context.getBeansOfType(LakeLeases::class.java).size shouldBe 1
            context.getBean(LakeAppendPort::class.java).datasourceName shouldBe "lake"
        }
    }

    @Test
    fun `the production sweep carries the configured protection window`() {
        runner
            .withPropertyValues("datapipelines.lake.enabled=true", "datapipelines.lake.retention.protection-days=30")
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(LakeAppendRuntime::class.java).sweep.protectionDays shouldBe 30
            }
    }
}
