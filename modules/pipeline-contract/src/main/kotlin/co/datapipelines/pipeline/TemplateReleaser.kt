package co.datapipelines.pipeline

import java.util.UUID

/**
 * "Release this DRAFT template version for this actor" — the one WRITE the pipeline
 * aggregate asks of `templates`, and the release cascade (versioning §5.3 precondition 2,
 * 142: releasing a pipeline may release the draft templates it pins, with consent) is the
 * only thing that asks.
 *
 * Declared here as a port for the same reason [TemplateVersionStatuses] is: the module
 * arrow runs `templates → pipeline-contract`, never the reverse, so the aggregation layer
 * (`web`) supplies the implementation over the template's OWN release path
 * (`TemplateReleaseService`) — one implementation of "release a template", so the direct
 * verb and the cascaded one cannot drift on validation, hash guard or pointer rule.
 *
 * The contract the cascade relies on: the implementation releases exactly [version] (the
 * PINNED version — never a newer draft), participates in the caller's metadata transaction
 * (a plain JDBC write on the metadata datasource does; nothing may open its own), and
 * throws the template's own catalogued refusal (`template.version.not_draft`,
 * `template.version.conflict`, a validation code) rather than mapping it — the pipeline's
 * transaction rolls back on any throw, which is what makes the cascade atomic.
 */
fun interface TemplateReleaser {
    /** Releases `templateId@version`; returns the reference it released. */
    fun release(
        workspaceId: UUID,
        templateId: String,
        version: Int,
        actor: UUID,
    ): TemplateRef

    companion object {
        /**
         * No releaser wired — the default for constructions that predate 142 (the MCP
         * fixtures, the lifecycle unit tests). A cascade asked of it is a wiring defect, not a
         * refusal a caller can act on, so it fails loudly instead of answering
         * `template_not_released` and hiding the gap.
         */
        val NONE =
            TemplateReleaser { _, templateId, version, _ ->
                error("No TemplateReleaser is wired; cannot cascade the release of '$templateId' v$version.")
            }
    }
}
