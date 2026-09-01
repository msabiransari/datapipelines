package co.datapipelines.web.templates

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
    fun templateDraftService(templates: TemplateRepository): TemplateDraftService = TemplateDraftService(templates)

    @Bean
    fun templateReleaseService(
        templates: TemplateRepository,
        validator: TemplateValidator,
    ): TemplateReleaseService = TemplateReleaseService(templates, validator)
}
