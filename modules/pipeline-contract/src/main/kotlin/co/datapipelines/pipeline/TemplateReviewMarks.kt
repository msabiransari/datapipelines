package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * One RETIRED learned fact a template version cites as implementing (transform-nodes design
 * §2.3/§8.2) — the reason the version reads `needs_review`. A superseded fact is a retired one
 * with [retiredReason] `superseded`; [supersededBy] is its successor, the visible row whose
 * `supersedes` names [factId] (there is no `superseded_by` column — this is the reverse read),
 * null when the fact was retired without one.
 *
 * Declared here, not in `templates`, because the pipeline release ([PipelineReleaseService]) and
 * its dialog read it through [TemplateReviewMarks] and the module arrow runs `templates →
 * pipeline-contract`; `templates` reuses the type on its read projection so there is one shape.
 * The wire keys are pinned on every use-site target (the `Template` DTO rule).
 */
data class RetiredFactCitation(
    @field:JsonProperty("fact_id") @get:JsonProperty("fact_id") @param:JsonProperty("fact_id")
    val factId: String,
    @field:JsonProperty("retired_reason") @get:JsonProperty("retired_reason") @param:JsonProperty("retired_reason")
    val retiredReason: String?,
    @field:JsonProperty("superseded_by") @get:JsonProperty("superseded_by") @param:JsonProperty("superseded_by")
    val supersededBy: String?,
) {
    /** `<id> — superseded by <id>`, or `<id> (retired: <reason>)` — the phrase the warning and the dialog row share. */
    fun describe(): String =
        when {
            supersededBy != null -> "$factId — superseded by $supersededBy"
            retiredReason.isNullOrBlank() -> "$factId (retired)"
            else -> "$factId (retired: $retiredReason)"
        }
}

/**
 * "Which of these pinned template versions cite a retired fact?" — the one fact the pipeline
 * release needs about the semantic link (transform-nodes design §8.2: pinning a `needs_review`
 * version at release is a WARNING, never a refusal).
 *
 * A port for the reason [TemplateVersionStatuses] is one: `templates` depends on
 * `pipeline-contract`, never the reverse, so the aggregation layer (`web`) supplies the
 * implementation over the templates module's citation read — the SAME read every template
 * projection's `needs_review` comes from, so the release warning and the explorer's marker
 * cannot disagree. The answer holds only the pins that DO cite a retired fact; a pin absent
 * from the map reads clean.
 */
fun interface TemplateReviewMarks {
    fun retiredCitations(
        workspaceId: UUID,
        pins: Collection<TemplateRef>,
    ): Map<TemplateRef, List<RetiredFactCitation>>

    companion object {
        /** No citation store wired — every pin reads clean (constructions that predate 7e). */
        val NONE = TemplateReviewMarks { _, _ -> emptyMap() }
    }
}

/**
 * One entry of the release response's `warnings` array (transform-nodes design §8.2,
 * pipeline-contract §14): a fact about what was released that the releaser should see, never a
 * refusal. [template]/[version] name the pin the warning is about.
 */
data class ReleaseWarning(
    val code: String,
    val message: String,
    val template: String,
    val version: Int,
) {
    companion object {
        /** The §13.13 `pipeline.release.template_needs_review` warning for [pin] and its [citations]. */
        fun templateNeedsReview(
            pin: TemplateRef,
            citations: List<RetiredFactCitation>,
        ): ReleaseWarning =
            ReleaseWarning(
                code = PipelineErrorCodes.Versioning.RELEASE_TEMPLATE_NEEDS_REVIEW,
                message =
                    "Template '${pin.id}' v${pin.version} cites a retired fact: " +
                        citations.joinToString("; ") { it.describe() } +
                        ". Released anyway — re-cite the successor (or drop the citation) with templates_update.",
                template = pin.id,
                version = pin.version,
            )
    }
}
