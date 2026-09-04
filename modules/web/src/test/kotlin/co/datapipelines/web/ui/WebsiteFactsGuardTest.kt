package co.datapipelines.web.ui

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.mcp.McpExecutionRunner
import co.datapipelines.mcp.McpServerAutoConfiguration
import co.datapipelines.mcp.McpToolCatalog
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * 033/C5 — the marketing tool count is derived, never transcribed: FOUR-WAY agreement
 * between the rendered homepage, mcp-server.md §6.1, [McpToolCatalog], and the production
 * tool bean (the REAL `@Bean` method's output — asserting a fixture against the catalog
 * would leave the pre-033 drift hole one level over).
 *
 * Falsified four ways before landing (recorded in the 033 handback): editing the
 * marketing copy, the spec's §6.1 list, the catalog, or the bean each turns this red.
 */
class WebsiteFactsGuardTest {
    @Test
    fun `the rendered site count, the spec, the catalog and the production bean agree`() {
        val renderedCount = renderedSiteCount()
        val specCount = specListedCount()
        val catalogCount = McpToolCatalog.NAMES.size
        val beanCount = productionToolCount()

        assertAll(
            // Non-vacuity first: a parse that silently found nothing must not "agree" at 0.
            { specCount shouldBeGreaterThan 0 },
            { renderedCount shouldBe specCount },
            { catalogCount shouldBe specCount },
            { beanCount shouldBe specCount },
        )
    }

    /** The count a visitor reads off `/`, extracted from the rendered page. */
    private fun renderedSiteCount(): Int {
        val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/"
                        suffix = ".html"
                        characterEncoding = "UTF-8"
                    },
                )
            }
        val exchange =
            JakartaServletWebApplication
                .buildApplication(MockServletContext())
                .buildExchange(MockHttpServletRequest(), MockHttpServletResponse())
        val html =
            engine.process(
                "site/index",
                WebContext(exchange).apply { setVariable("toolCount", McpToolCatalog.NAMES.size) },
            )
        return SITE_COUNT
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("the marketing page states no tool count — the guard would pass vacuously")
    }

    /** §6.1's list, parsed with the same regex McpToolSurfaceSpecDriftTest uses. */
    private fun specListedCount(): Int {
        val spec =
            PathMatchingResourcePatternResolver(javaClass.classLoader)
                .getResource("classpath:docs/mcp-server.md")
                .inputStream
                .readBytes()
                .decodeToString()
        val section =
            spec.substring(
                spec.indexOf("### 6.1 Tool naming convention"),
                spec.indexOf("### 6.2 Tool definitions"),
            )
        return Regex("^- `([a-z_]+)`$", RegexOption.MULTILINE).findAll(section).count()
    }

    /** The REAL mcpTools bean method's output size — the list the server actually ships. */
    private fun productionToolCount(): Int {
        val executionRunner = mockk<ObjectProvider<McpExecutionRunner>>()
        every { executionRunner.getIfAvailable() } returns null
        val launcher = mockk<ObjectProvider<co.datapipelines.application.ExecutionLauncher>>()
        every { launcher.getIfAvailable() } returns null
        return McpServerAutoConfiguration()
            .mcpTools(
                pipelines = mockk<PipelineRepository>(),
                pipelineService = mockk<co.datapipelines.pipeline.PipelineService>(),
                templates = mockk<TemplateRepository>(),
                datasources = mockk<DatasourceRegistry>(),
                introspector = mockk<SchemaIntrospector>(),
                executions = mockk<ExecutionRepository>(),
                executor = mockk<PipelineExecutor>(),
                resultStore = mockk<ResultStore>(),
                resultUrls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" },
                executorConfig = ExecutorConfig(),
                templateValidator = mockk<TemplateValidator>(),
                templateEngines = mockk<WorkspaceTemplateEngines>(),
                environment = StandardEnvironment(),
                executionRunner = executionRunner,
                launcher = launcher,
                datasourceCreateService = mockk<co.datapipelines.application.datasources.DatasourceCreateService>(),
            ).size
    }

    private companion object {
        val SITE_COUNT = Regex("""<span>(\d+)</span> tools cover the full lifecycle""")
    }
}
