package co.datapipelines.web.ui

import co.datapipelines.application.dependencies.ReleasePlanningWalk
import co.datapipelines.pipeline.release.CheckOverride
import co.datapipelines.pipeline.release.ClosureNode
import co.datapipelines.pipeline.release.ReleaseBounds
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeIdentity
import co.datapipelines.pipeline.release.ReleasePlan
import co.datapipelines.pipeline.release.ReleasePlanRequest
import co.datapipelines.pipeline.release.ReleasePrerequisite
import co.datapipelines.pipeline.release.ReleaseRefusals
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import java.util.UUID

/**
 * What a release dialog shows (#876 §F.2 Producer; record §8, §12): the session [plan] of the root's working draft and
 * the datasource [prerequisites] its closure needs — one outcome per distinct name, `readonly` a boolean only when the
 * datasource is available. Nothing else feeds [ReleaseDialogModel]: the interactive dependency tree is never read here,
 * and a datasource is never a plan node (it reaches the dialog only through [prerequisites]).
 */
data class ReleasePreview(
    val plan: ReleasePlan,
    val prerequisites: List<ReleasePrerequisite>,
)

/**
 * The dialog GET's planning read (#876 §F.2, path (1)): [ReleasePlanningWalk.planned] — the closure and its prerequisites
 * in ONE read-only planning transaction, resolved through G3's scoped provider, so an absent datasource and one granted
 * only to another workspace are the same outcome — then [ReleasePlan.session] over both. The first production caller of
 * `ReleasePlan.session`. Reads only: the POST re-walks, re-compares the `tree_hash` and re-checks every prerequisite
 * under the guard, which stays the authority.
 */
class ReleasePreviews(
    private val planning: ReleasePlanningWalk,
    private val bounds: ReleaseBounds,
) {
    /**
     * The preview of [family]'s draft [name] at [version] in [workspaceId], planned for [actor]. A root that no longer
     * resolves (purged or released since the page was read) is the family's `plan_conflict`, as the POST would answer;
     * the walk's own refusals (a cycle, a bound, the metadata deadline) propagate as the family's codes.
     */
    fun preview(
        family: ReleaseFamily,
        workspaceId: UUID,
        name: String,
        version: Int,
        actor: UUID,
    ): ReleasePreview {
        val root = ReleaseNodeIdentity(workspaceId, family, name, version)
        val planned = planning.planned(root, actor) ?: throw ReleaseRefusals.planConflict(family)
        return ReleasePreview(ReleasePlan.session(planned.closure, bounds.maxWireBytes, planned.prerequisites), planned.prerequisites)
    }

    /**
     * The dialog model of [preview] for the same root, or — when the planner refuses to plan at all (the root left
     * draft, a cycle, a bound, the metadata deadline) — a model carrying that refusal's message and no form. [note]
     * adds a family's remark to a node's row.
     */
    @Suppress("LongParameterList") // the root's identity, its planner and the family's one remark
    fun dialog(
        family: ReleaseFamily,
        workspaceId: UUID,
        name: String,
        version: Int,
        actor: UUID,
        note: (ClosureNode) -> String? = { null },
    ): ReleaseDialogModel =
        try {
            ReleaseDialogModel.of(preview(family, workspaceId, name, version, actor), note)
        } catch (refused: DatapipelinesException) {
            ReleaseDialogModel.refused(family, refused.message ?: "The dependencies could not be planned.")
        }
}

/**
 * The dialog POST's form → plan binding (#876 §F.2): the posted `tree_hash`, the repeated `include_dependencies` node ids
 * and the optional per-node check overrides become the SAME [ReleasePlanRequest] the REST route parses, handed to the
 * SAME service call. A POST without a `tree_hash` is refused `plan_required` (428) on purpose — the dialog always posts
 * the one its GET rendered, and the bare cascade alias is closed (record §25.4 D15). The bounds and the selection rules
 * stay the coordinator's.
 *
 * The overrides travel as two repeated fields in the same order, [OVERRIDE_NODE] (a plan row's `node_id`) and
 * [OVERRIDE_REASON] (its reason), paired by index: different counts are `plan_invalid` with the locator
 * `check_overrides`; a pair whose reason is blank after trimming is "no override" and dropped; no pair left leaves
 * `checkOverrides` null, exactly as a POST without the fields. The binding never copies the root's own reason
 * (`overrideChecksReason`, the pipeline root's existing field) to a node, nor a node's reason to the root; which node
 * may carry one — a node of the plan, released by it, given once, within the bound — is the coordinator's to refuse.
 * The fields are read raw from the form ([MultiValueMap]), never through a `List` conversion that would split one
 * reason at its commas.
 */
object ReleaseDialogPost {
    /** The hidden field naming a plan row's `node_id`, one per override reason. */
    const val OVERRIDE_NODE = "check_override_node"

    /** The text field carrying that row's audited override reason. */
    const val OVERRIDE_REASON = "check_override_reason"

    fun plan(
        family: ReleaseFamily,
        treeHash: String?,
        includeDependencies: List<String>?,
        form: MultiValueMap<String, String> = LinkedMultiValueMap(),
    ): ReleasePlanRequest {
        if (treeHash.isNullOrBlank()) throw ReleaseRefusals.planRequired(family)
        return ReleasePlanRequest(
            treeHash = treeHash,
            includeDependencies = includeDependencies.orEmpty(),
            checkOverrides = checkOverrides(family, form[OVERRIDE_NODE], form[OVERRIDE_REASON]),
        )
    }

    /** The paired override fields as plan entries, or null when no pair carries a reason. */
    fun checkOverrides(
        family: ReleaseFamily,
        nodes: List<String>?,
        reasons: List<String>?,
    ): List<CheckOverride>? {
        val nodeIds = nodes.orEmpty()
        val texts = reasons.orEmpty()
        if (nodeIds.size != texts.size) {
            throw ReleaseRefusals.planInvalid(family, "check_overrides", "every override names one node and one reason")
        }
        return nodeIds
            .zip(texts)
            .filter { (_, reason) -> reason.isNotBlank() }
            .map { (nodeId, reason) -> CheckOverride(nodeId, reason) }
            .ifEmpty { null }
    }
}
