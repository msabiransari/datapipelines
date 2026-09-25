package co.datapipelines.web.templates

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The version-lifecycle services of the template surface (versioning.md §5/§6).
 *
 * Lives here rather than in `web/config` because that package is owned by a parallel lane
 * this round; the beans are exactly what `DomainConfiguration` would declare for these
 * services — constructor wiring of existing beans, no new dependencies — and the placement
 * is a named crossing in the round's handback, to be normalized into `web/config` when the
 * lanes merge.
 */
@Configuration
class TemplateLifecycleConfiguration {
    @Bean
    fun templateDraftService(
        templates: TemplateRepository,
        authoring: AuthoringGuard,
        // 7e — the citation rows the write lands `implements` in (inherit or replace).
        citations: co.datapipelines.templates.TemplateImplementsRepository,
    ): TemplateDraftService = TemplateDraftService(templates, authoring, citations)

    @Bean
    fun templateReleaseService(
        templates: TemplateRepository,
        validator: TemplateValidator,
        authoring: AuthoringGuard,
        pipelines: PipelineRepository,
    ): TemplateReleaseService = TemplateReleaseService(templates, validator, authoring, pipelines)
}
