package co.datapipelines.visualization

import co.datapipelines.pipeline.TemplateRef
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * The visualization release gate's evidence half (D56, the spec's §11.4): the candidate's latest run is for its
 * exact content (`tests_stale`), GREEN (`tests_red`) and the server's mechanical test passes now
 * (`mechanical_failed`). The evidence tables (V42's `visualization_test_runs`) and the mechanical test are the
 * tests lane's (L4), which REPLACES [NOT_INSTALLED] with the real gate. It runs INSIDE the release's one
 * transaction, before the flip, so an intervening edit cannot release on stale evidence.
 *
 * [NOT_INSTALLED] is the default and it REFUSES: a deployment without L4 cannot release a visualization by
 * accident — `visualization.release.tests_missing` with `details.reason = gate_not_installed`. The at-least-one-case
 * rule is not the gate's: [VisualizationService.release] checks it first, gate or no gate.
 */
fun interface ReleaseEvidence {
    fun verdict(
        workspaceId: UUID,
        candidate: ReleaseCandidate,
    ): EvidenceVerdict

    companion object {
        /** No evidence gate installed — every release refused `tests_missing` / `gate_not_installed` (L4 replaces it). */
        val NOT_INSTALLED: ReleaseEvidence =
            ReleaseEvidence { _, candidate ->
                EvidenceVerdict.Refused(
                    VisualizationErrorCodes.RELEASE_TESTS_MISSING,
                    "Visualization '${candidate.name.safeEcho()}' has no release evidence: the test-session gate is not installed on " +
                        "this deployment yet, so no visualization can be released.",
                    mapOf("reason" to "gate_not_installed", "visualization" to candidate.name.safeEcho(), "version" to candidate.version),
                )
            }
    }
}

/** What the gate judges: exactly the DRAFT the release would flip. */
data class ReleaseCandidate(
    val visualizationId: UUID,
    val name: String,
    val version: Int,
    val bodyHash: String,
    val body: VisualizationBody,
)

/** The gate's answer. */
sealed interface EvidenceVerdict {
    data object Pass : EvidenceVerdict

    /** A refusal with one of `visualization.release.tests_*` / `mechanical_failed`, naming the case or path. */
    data class Refused(
        val code: String,
        val message: String,
        val details: Map<String, Any?>,
    ) : EvidenceVerdict
}

/**
 * The template half of a transfer (the spec's §12): a visualization's export carries its pinned transform
 * templates' `imports` closure at the envelope root, and its import lands them FIRST. The template export and import
 * services live in `web` (`TemplateImportService`), which L1c's routes wire here — the parameter-set transfer's
 * composition, lifted behind a port so the transfer SERVICE lives in this module.
 */
interface TemplateBundle {
    /** The pinned versions and their transitive `imports`, deduplicated, as template export nodes. */
    fun export(
        workspaceId: UUID,
        pins: List<TemplateRef>,
    ): List<JsonNode>

    /** Imports [templates] (idempotent for versions already present) on behalf of [actor]. */
    fun import(
        workspaceId: UUID,
        templates: List<JsonNode>,
        actor: UUID,
    )
}
