package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.ExclusiveDraftTemplates
import co.datapipelines.pipeline.PipelineDraftService
import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.ReleaseCheckGate
import co.datapipelines.pipeline.TemplateReleaser
import co.datapipelines.pipeline.TemplateReviewMarks
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.templates.TemplateRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

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

    /**
     * The release cascade's write (versioning §5.3 precondition 2, 142), as the port
     * `pipeline-contract` declares — the same module-arrow arrangement as
     * [templateVersionStatuses], now for the one WRITE the pipeline aggregate asks of
     * `templates`. It rides [TemplateReleaseService.releasePinned], i.e. the template's own
     * release path: one implementation of "release a template", so the direct verb
     * (`POST /api/v1/templates/release`, the template dialog) and the cascaded one cannot
     * drift. Wrapped here rather than moved down to `application`: that module is for
     * cross-aggregate use cases and does not depend on `templates` today, and the service
     * is a single-aggregate lifecycle service exactly like `PipelineReleaseService` — the
     * port is the seam, not a relocation.
     */
    @Bean
    fun templateReleaser(releases: co.datapipelines.web.templates.TemplateReleaseService): TemplateReleaser =
        TemplateReleaser { workspaceId, templateId, version, actor ->
            val released = releases.releasePinned(workspaceId, templateId, version, actor)
            co.datapipelines.pipeline.TemplateRef(released.detail.templateId, released.detail.version)
        }

    /**
     * 7e — "which pinned template versions cite a retired fact?" (transform-nodes design §8.2),
     * as the port `pipeline-contract` declares — the [templateVersionStatuses] arrangement. It
     * rides the templates module's citation read, whose SQL is the SAME expression every template
     * projection's `needs_review` comes from, so the release warning, the dialog row and the
     * explorer's marker cannot disagree.
     */
    @Bean
    fun templateReviewMarks(citations: co.datapipelines.templates.TemplateImplementsRepository): TemplateReviewMarks =
        TemplateReviewMarks { workspaceId, pins -> citations.retiredCitations(workspaceId, pins) }

    @Bean
    @Suppress("LongParameterList") // one collaborator per release precondition and port
    fun pipelineReleaseService(
        pipelines: PipelineRepository,
        templates: TemplateVersionStatuses,
        validator: PipelineValidator,
        authoring: AuthoringGuard,
        metadataTransactionManager: PlatformTransactionManager,
        checkGate: ReleaseCheckGate,
        templateReleaser: TemplateReleaser,
        reviewMarks: TemplateReviewMarks,
    ): PipelineReleaseService =
        PipelineReleaseService(
            pipelines,
            templates,
            validator,
            authoring,
            checkGate = checkGate,
            templateReleaser = templateReleaser,
            transactions = TransactionTemplate(metadataTransactionManager),
            reviewMarks = reviewMarks,
        )

    /**
     * The entity purge's exclusive-draft-templates offer (versioning §3.5, 101), as the port
     * `pipeline-contract` declares — the same module-arrow arrangement as
     * [templateVersionStatuses] above.
     */
    @Bean
    fun exclusiveDraftTemplates(templates: TemplateRepository): ExclusiveDraftTemplates =
        object : ExclusiveDraftTemplates {
            override fun exclusiveIds(
                workspaceId: java.util.UUID,
                pipelineId: java.util.UUID,
            ) = templates.exclusiveDraftTemplateIds(workspaceId, pipelineId)

            override fun purge(
                workspaceId: java.util.UUID,
                templateId: String,
            ) {
                // The offered set was verified draft-only and exclusively pinned at offer
                // time, and the sole pinner (the purged pipeline) is already gone; the
                // entity delete cascades its one version row.
                if (!templates.deleteTemplateRow(workspaceId, templateId)) {
                    throw co.datapipelines.typesystem.DatapipelinesException(
                        code = co.datapipelines.pipeline.PipelineErrorCodes.Template.NOT_FOUND,
                        message = "Template '$templateId' no longer exists; the exclusive-template purge raced.",
                        details = mapOf("template_id" to templateId),
                    )
                }
            }
        }

    @Bean
    fun pipelineService(
        pipelines: PipelineRepository,
        validator: PipelineValidator,
        drafts: PipelineDraftService,
        releases: PipelineReleaseService,
        authoring: AuthoringGuard,
        draftTemplates: ExclusiveDraftTemplates,
    ): PipelineService = PipelineService(pipelines, validator, drafts, releases, authoring, draftTemplates)
}
