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
import co.datapipelines.web.TestRepoFiles
import co.datapipelines.web.ui.site.SitePages
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
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

    /**
     * 076 — the demo pipeline names the site QUOTES are derived, never transcribed: every
     * name mentioned by the homepage, the federated-query page, the dp-lake page and the
     * engine pages' `demo` facts must be one of the pipelines the shipped demo content seeds
     * (`scripts/sample-data/content/examples.json`,
     * `scripts/sample-data-trade/content/examples.json` and — 089 — the lake family's
     * `scripts/sample-data/content/examples-lake.json`, what `./app.sh --start --demo nyc`,
     * `--demo trade` and `--demo lake` load). The 067 folder-named rename left the site
     * quoting the old flat names; this guard is what makes that the last time.
     *
     * Extraction, same regex-over-source style as [renderedSiteCount]:
     * - Demo names: the `"name"` of each `"pipelines"` entry in the JSON files, anchored on
     *   the preceding `"schema_version"` so nothing else named "name" is collected.
     * - Site mentions: every lowercase-underscore token in the three template SOURCES (read
     *   from disk via [TestRepoFiles], never the build output) whose LAST `/`-separated
     *   segment equals a demo leaf name. A full path (`nyc/mobility/revenue_by_borough`)
     *   matches on its leaf; a flat regression (`revenue_by_borough`) matches too — and then
     *   fails the membership assertion, which is the point. The corollary: site prose must
     *   quote demo pipelines by their full path, because a bare leaf segment is treated as a
     *   pipeline mention.
     * - Engine facts: [SitePages.ENGINES]' non-null `demo` fields, read off the registry
     *   itself rather than re-parsed from source.
     */
    @Test
    fun `every demo pipeline name the site quotes is a pipeline the demo content ships`() {
        val demoNames = demoPipelineNames()
        val leafNames = demoNames.map { it.substringAfterLast('/') }.toSet()

        val indexMentions = quotedPipelineMentions(SITE_INDEX, leafNames)
        val federatedMentions = quotedPipelineMentions(SITE_FEDERATED_QUERY, leafNames)
        val dpLakeMentions = quotedPipelineMentions(SITE_DP_LAKE, leafNames)
        val engineDemos = SitePages.ENGINES.mapNotNull { it.demo }

        assertAll(
            // Non-vacuity first: an extractor that silently found nothing must not "agree".
            { demoNames.size shouldBeGreaterThan 3 },
            { indexMentions.shouldNotBeEmpty() },
            { federatedMentions.shouldNotBeEmpty() },
            { dpLakeMentions.shouldNotBeEmpty() },
            { engineDemos.shouldNotBeEmpty() },
            {
                (indexMentions + federatedMentions + dpLakeMentions + engineDemos).forEach { name ->
                    demoNames shouldContain name
                }
            },
        )
    }

    /** The pipeline names of all three demo families, parsed from the content files themselves. */
    private fun demoPipelineNames(): Set<String> =
        listOf(DEMO_NYC, DEMO_TRADE, DEMO_LAKE)
            .flatMap { path ->
                PIPELINE_NAME
                    .findAll(TestRepoFiles.read(path))
                    .map { it.groupValues[1] }
                    .toList()
            }.toSet()

    /** Tokens in [relativePath] whose last segment is a demo leaf — the page's pipeline mentions. */
    private fun quotedPipelineMentions(
        relativePath: String,
        leafNames: Set<String>,
    ): Set<String> =
        NAME_TOKEN
            .findAll(TestRepoFiles.read(relativePath))
            .map { it.value }
            .filter { it.substringAfterLast('/') in leafNames }
            .toSet()

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
                endpointPublishService = mockk<co.datapipelines.application.endpoints.EndpointPublishService>(),
                lakeTableRegistryService = mockk<co.datapipelines.application.datasources.LakeTableRegistryService>(),
                cancellationService = mockk<co.datapipelines.executor.ExecutionCancellationService>(),
                mcpCallAudit = mockk<co.datapipelines.application.mcp.McpCallAudit>(),
                auditSink = mockk<co.datapipelines.auth.AuditEventSink>(),
            ).size
    }

    private companion object {
        val SITE_COUNT = Regex("""<span>(\d+)</span> tools cover the full lifecycle""")

        const val SITE_INDEX = "modules/web/src/main/resources/templates/site/index.html"
        const val SITE_FEDERATED_QUERY = "modules/web/src/main/resources/templates/site/federated-query.html"
        const val SITE_DP_LAKE = "modules/web/src/main/resources/templates/site/dp-lake.html"
        const val DEMO_NYC = "scripts/sample-data/content/examples.json"
        const val DEMO_TRADE = "scripts/sample-data-trade/content/examples.json"
        const val DEMO_LAKE = "scripts/sample-data/content/examples-lake.json"

        /** A `"pipelines"` entry's name — anchored on the schema version that precedes it. */
        val PIPELINE_NAME = Regex(""""schema_version":\s*\d+,\s*"name":\s*"([^"]+)"""")

        /** Lowercase-underscore words, `/`-joined or bare — the shape every pipeline name has. */
        val NAME_TOKEN = Regex("""[a-z0-9_]+(?:/[a-z0-9_]+)*""")
    }
}
