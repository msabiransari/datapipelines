package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.templates.TemplateRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The version-lifecycle services of the pipeline surface (versioning.md §5/§9).
 *
 * Lives here rather than in `web/config` because that package is owned by a parallel lane
 * this round; the beans are exactly what [DomainConfiguration][co.datapipelines.web.config.DomainConfiguration]
 * would declare for these services — constructor wiring of existing beans, no new
 * dependencies — and the placement is a named crossing in the round's handback, to be
 * normalized into `web/config` when the lanes merge.
 */
@Configuration
class PipelineLifecycleConfiguration {
    @Bean
    fun pipelineDraftService(pipelines: PipelineRepository): PipelineDraftService = PipelineDraftService(pipelines)

    @Bean
    fun pipelineReleaseService(
        pipelines: PipelineRepository,
        templates: TemplateRepository,
        validator: PipelineValidator,
    ): PipelineReleaseService = PipelineReleaseService(pipelines, templates, validator)
}
