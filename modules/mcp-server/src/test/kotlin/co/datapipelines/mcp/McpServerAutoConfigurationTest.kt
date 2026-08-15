package co.datapipelines.mcp

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthErrorWriter
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.executor.ExecutionEventRepository
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.templates.TemplateEngine
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The wiring `McpAuthFilterTest` cannot see.
 *
 * That test drives the filter with a **pre-populated** `SecurityContext`, so it proves the filter's
 * logic and nothing about whether the running application ever populates one first. The
 * load-bearing facts live in the registration beans: the filter runs **after** the Spring Security
 * chain (order), it runs on `/mcp` **only** (url pattern), and the servlet is registered against a
 * server that has already installed its request handler. A wrong order here would make every MCP
 * request look unauthenticated; a wrong pattern would put an auth gate on unrelated endpoints.
 */
class McpServerAutoConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpServerAutoConfiguration::class.java))
            .withUserConfiguration(EngineStubs::class.java)

    @Test
    fun `the filter is registered on mcp only, after the security chain`() {
        runner.run { context ->
            @Suppress("UNCHECKED_CAST")
            val registration = context.getBean(FilterRegistrationBean::class.java) as FilterRegistrationBean<McpAuthFilter>

            assertAll(
                { registration.urlPatterns shouldContainExactly listOf(McpServerFactory.ENDPOINT) },
                { (registration.order > SecurityProperties.DEFAULT_FILTER_ORDER) shouldBe true },
                { registration.filter::class shouldBe McpAuthFilter::class },
            )
        }
    }

    @Test
    fun `the transport servlet is mapped at mcp and the server installed its handler`() {
        runner.run { context ->
            @Suppress("UNCHECKED_CAST")
            val servlet =
                context.getBean(ServletRegistrationBean::class.java) as ServletRegistrationBean<HttpServletStatelessServerTransport>

            assertAll(
                { servlet.urlMappings shouldContainExactly listOf(McpServerFactory.ENDPOINT) },
                { context.getBean(McpStatelessSyncServer::class.java).listTools().size shouldBe 18 },
                { context.getBean(McpToolDispatcher::class.java).toolNames().size shouldBe 18 },
            )
        }
    }

    @Test
    fun `the whole surface stays out of a context that has no executor`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpServerAutoConfiguration::class.java))
            .run { context ->
                context.getBeanProvider(McpToolDispatcher::class.java).ifAvailable shouldBe null
            }
    }

    /** The collaborator beans `app` supplies; mocked, since none of them is under test here. */
    @Configuration(proxyBeanMethods = false)
    internal class EngineStubs {
        @Bean fun pipelines(): PipelineRepository = mockk()

        @Bean fun templates(): TemplateRepository = mockk()

        @Bean fun datasources(): DatasourceRegistry = mockk()

        @Bean fun schemaIntrospector(): SchemaIntrospector = mockk()

        @Bean fun executions(): ExecutionRepository = mockk()

        @Bean fun events(): ExecutionEventRepository = mockk()

        @Bean fun executor(): PipelineExecutor = mockk()

        @Bean fun resultStore(): ResultStore = mockk()

        @Bean fun resultUrls(): ResultUrlFactory = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" }

        @Bean fun executorConfig(): ExecutorConfig = ExecutorConfig()

        @Bean fun pipelineValidator(): PipelineValidator = mockk()

        @Bean fun templateValidator(): TemplateValidator = mockk()

        @Bean fun templateEngine(): TemplateEngine = mockk()

        @Bean fun auditLogger(): AuditLogger = mockk(relaxed = true)

        @Bean fun authErrorWriter(): AuthErrorWriter = AuthErrorWriter(ObjectMapper())
    }
}
