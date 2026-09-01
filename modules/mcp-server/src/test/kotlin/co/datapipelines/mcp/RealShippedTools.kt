package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.executor.ExecutorConfig
import co.datapipelines.executor.PipelineExecutor
import co.datapipelines.executor.ResultStore
import co.datapipelines.executor.ResultUrlFactory
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider

/**
 * The REAL `mcpTools` `@Bean` method's output, with mocked collaborators (033/C2).
 *
 * Every pre-033 test fixture hand-rebuilt the tool list next to the autoconfiguration and
 * claimed to match it — a claim, not a constraint: a 19th tool added to the bean left each
 * fixture's copy (and every guard asserting against it) silently stale. Tests that need the
 * shipped tool list call THIS, so the bean method itself is the only list under test.
 */
fun realShippedTools(): List<McpTool> {
    val executionRunner = mockk<ObjectProvider<McpExecutionRunner>>()
    every { executionRunner.getIfAvailable() } returns null
    return McpServerAutoConfiguration().mcpTools(
        pipelines = mockk<PipelineRepository>(),
        templates = mockk<TemplateRepository>(),
        datasources = mockk<DatasourceRegistry>(),
        introspector = mockk<SchemaIntrospector>(),
        executions = mockk<ExecutionRepository>(),
        executor = mockk<PipelineExecutor>(),
        resultStore = mockk<ResultStore>(),
        resultUrls = ResultUrlFactory { "https://dp.test/api/v1/executions/$it/result" },
        executorConfig = ExecutorConfig(),
        pipelineValidator = mockk<PipelineValidator>(),
        templateValidator = mockk<TemplateValidator>(),
        templateEngines = mockk<WorkspaceTemplateEngines>(),
        executionRunner = executionRunner,
    )
}
