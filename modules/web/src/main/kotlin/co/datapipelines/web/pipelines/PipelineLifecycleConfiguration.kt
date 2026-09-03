package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.templates.TemplateRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The pipeline aggregate's service and the version-lifecycle collaborators it composes
 * (versioning.md §5/§9, ARCH-AUDIT S5).
 *
 * All three classes live in `pipeline-contract` since 056; this is the assembly, and it lives in
 * `web` because `web` is the aggregation layer (module-structure §5.9) — `pipeline-contract`
 * ships no Spring configuration of its own, exactly as `DomainConfiguration` already declares its
 * repository.
 *
 * [PipelineService] is a **single bean shared by both surfaces**: the REST controllers, the UI
 * controllers and — through `mcp-server`'s autoconfiguration — the pipeline MCP tools all take
 * this one instance. That sharing is what makes S2's D1/D2/D6 duplication structurally impossible
 * to reintroduce rather than merely absent today.
 */
@Configuration
class PipelineLifecycleConfiguration {
    @Bean
    fun pipelineDraftService(
        pipelines: PipelineRepository,
        authoring: AuthoringGuard,
    ): PipelineDraftService = PipelineDraftService(pipelines, authoring)

    /**
     * The `templates` half of the release gate (versioning §6), as the port `pipeline-contract`
     * declares: `templates` depends on `pipeline-contract`, so the arrow cannot be reversed and
     * the aggregation layer supplies the one fact the gate needs.
     */
    @Bean
    fun templateVersionStatuses(templates: TemplateRepository): TemplateVersionStatuses =
        TemplateVersionStatuses { workspaceId, templateId, version ->
            templates.findVersionStatus(workspaceId, templateId, version)
        }

    @Bean
    fun pipelineReleaseService(
        pipelines: PipelineRepository,
        templates: TemplateVersionStatuses,
        validator: PipelineValidator,
        authoring: AuthoringGuard,
    ): PipelineReleaseService = PipelineReleaseService(pipelines, templates, validator, authoring)

    @Bean
    fun pipelineService(
        pipelines: PipelineRepository,
        validator: PipelineValidator,
        drafts: PipelineDraftService,
        releases: PipelineReleaseService,
        authoring: AuthoringGuard,
    ): PipelineService = PipelineService(pipelines, validator, drafts, releases, authoring)
}
