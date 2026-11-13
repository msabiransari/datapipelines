package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.pipeline.release.PipelineDependencySource
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleaseNodeStatus
import co.datapipelines.pipeline.release.ReleasePin
import co.datapipelines.pipeline.release.RestoreBlocker
import co.datapipelines.pipeline.release.RestoreDependencyGuard
import co.datapipelines.pipeline.release.RestoreReach
import co.datapipelines.pipeline.release.RestoreRechecks
import java.util.UUID

/**
 * A pipeline RESTORE's recheck of the restored body's outbound pins (#517 round three, #719; release-dependencies record
 * §12.1 "Restore DISCARDED", RULED 2026-10-25). A restore lands the version RELEASED without a human's consent, and a
 * DISCARDED version protects nothing, so every template version and child pipeline version the body pins may have been
 * discarded or purged since. Runs inside the restore's guarded transaction, after the lifecycle checks.
 *
 * - A missing or DISCARDED pin refuses with the save rules' own validation code (400, the D7 precedent):
 *   `template_not_found` / `template_version_not_found` (a DISCARDED template version adds `reason: discarded`) and
 *   `pipeline_not_found` / `pipeline_version_not_found` / `pipeline_reference_not_released`.
 * - A present-but-DRAFT pin refuses `pipeline.release.dependency_not_released` (409, the import-landing precedent) in the
 *   shared blockers shape. No save rule lets a once-released body pin a draft, so this branch is defensive.
 *
 * The 400 answers before the 409. Only dependency failures block: a rule that tightened since the body was released is
 * not a dangling pin, so the validator is never consulted. Metadata reads only: no render, no check gate, no customer
 * connection while the guard is held. Template statuses come from the uncached port (the registry cache can be stale);
 * a template is told apart from its version by the ENTITY row ([TemplateIds]) — no version number stands in for it, since
 * a preserved-number import or a draft purge leaves a template without version 1 (#733).
 */
internal class PipelineRestorePins(
    private val pipelines: PipelineRepository,
    private val templateStatuses: TemplateVersionStatuses,
    private val templateIds: TemplateIds,
    private val maxBlockers: Int,
    private val deserializer: PipelineDeserializer,
    /** #873 §D.3 — the closure BELOW each live pin, walked through the shared port ([RestoreDependencyGuard]). */
    private val transitive: RestoreDependencyGuard = RestoreDependencyGuard.DIRECT_ONLY,
) {
    /** Refuses the restore of [record]'s [version], stored as [body], when a pin dangles (400) or is a draft (409). */
    fun recheck(
        workspaceId: UUID,
        record: PipelineRecord,
        version: Int,
        body: String,
    ) {
        val pipeline = deserializer.readOrThrow(body)
        val nodeIds = pipeline.nodes.withIndex().associate { (index, node) -> "nodes[$index].pipeline" to node.id }
        val failures = FailureCollector()
        val drafts = mutableListOf<RestoreBlocker>()
        val pins = PipelineDependencySource.pinsOf(workspaceId, pipeline)
        for (pin in pins) {
            val status =
                if (pin.target.family == ReleaseFamily.PIPELINE) {
                    childStatus(workspaceId, pin, nodeIds.getValue(pin.path), failures)
                } else {
                    templateStatus(workspaceId, pin, failures)
                }
            if (status == PipelineVersionStatus.DRAFT) {
                drafts += RestoreBlocker(pin.target, pin.targetName, PipelineDependencySource.statusOf(status), pin.path)
            }
        }
        val root = PipelineDependencySource.identity(workspaceId, record.name, version)
        // §D.3: the direct pins are judged above in their native codes; the walk adds what lies BELOW a live pin.
        val below = transitive.recheck(root, pins).filter { it.transitive }
        below.filter { it.dangling }.forEach { reach -> danglingBelow(reach, failures) }
        val result = failures.toResult()
        if (result.failures.isNotEmpty()) throw PipelineValidationException(result)
        RestoreRechecks.notReleased(root, record.name, below, maxBlockers, extra = drafts)?.let { throw it }
    }

    /**
     * A missing or DISCARDED node below a live pin (§D.3): the save rules' own code for its family — a template's
     * `template_version_not_found`, a child pipeline's `pipeline_version_not_found` (missing) or
     * `pipeline_reference_not_released` (DISCARDED) — at the full path from this body, with `reason: discarded` for a
     * DISCARDED version and the intermediate nodes as `via`.
     */
    private fun danglingBelow(
        reach: RestoreReach,
        into: FailureCollector,
    ) {
        val name = reach.identity.entityId.truncateForError()
        val version = reach.identity.version
        val discarded = if (reach.status == ReleaseNodeStatus.DISCARDED) mapOf("reason" to "discarded") else emptyMap()
        val via = mapOf("path" to reach.path, "via" to reach.viaDetails())
        when {
            reach.identity.family == ReleaseFamily.TEMPLATE -> {
                into.add(
                    Validation.TEMPLATE_VERSION_NOT_FOUND,
                    reach.directPath,
                    "Template '$name' version $version, reached through '${reach.path}', is ${reach.status.wireWord()}; a restore " +
                        "lands RELEASED and everything below it must be live.",
                    mapOf("template" to name, "version" to version) + discarded + via,
                )
            }

            reach.status == ReleaseNodeStatus.DISCARDED -> {
                into.add(
                    Validation.PIPELINE_REFERENCE_NOT_RELEASED,
                    reach.directPath,
                    "Pipeline '$name' version $version, reached through '${reach.path}', is DISCARDED; a restore lands " +
                        "RELEASED and everything below it must be live.",
                    mapOf("pipeline" to name, "pipeline_version" to version, "status" to reach.status.name) + discarded + via,
                )
            }

            else -> {
                into.add(
                    Validation.PIPELINE_VERSION_NOT_FOUND,
                    reach.directPath,
                    "Pipeline '$name' has no version $version, reached through '${reach.path}'.",
                    mapOf("pipeline" to name, "version" to version) + via,
                )
            }
        }
    }

    private fun ReleaseNodeStatus.wireWord(): String = if (this == ReleaseNodeStatus.DISCARDED) "discarded" else "missing"

    /** The pinned template version's status, or null after reporting it missing or DISCARDED. */
    private fun templateStatus(
        workspaceId: UUID,
        pin: ReleasePin,
        into: FailureCollector,
    ): PipelineVersionStatus? {
        val template = pin.target.entityId
        val version = pin.target.version
        val status = templateStatuses.statusOf(workspaceId, template, version)
        when {
            status == PipelineVersionStatus.DISCARDED -> {
                into.add(
                    Validation.TEMPLATE_VERSION_NOT_FOUND,
                    pin.path,
                    "Template '${template.truncateForError()}' version $version is discarded; a restore lands RELEASED " +
                        "and may pin only a live version.",
                    mapOf("template" to template.truncateForError(), "version" to version, "reason" to "discarded"),
                )
            }

            status == null && templateIds.isRegistered(workspaceId, template) -> {
                into.add(
                    Validation.TEMPLATE_VERSION_NOT_FOUND,
                    pin.path,
                    "Template '${template.truncateForError()}' has no version $version.",
                    mapOf("template" to template.truncateForError(), "version" to version),
                )
            }

            status == null -> {
                into.add(
                    Validation.TEMPLATE_NOT_FOUND,
                    pin.path,
                    "Template '${template.truncateForError()}' is not in the template registry.",
                    mapOf("template" to template.truncateForError()),
                )
            }
        }
        return status.takeUnless { it == PipelineVersionStatus.DISCARDED }
    }

    /**
     * The pinned child version's status, or null after reporting it missing or DISCARDED. A child is told apart from its
     * version by the entity row, which survives a purged version.
     */
    private fun childStatus(
        workspaceId: UUID,
        pin: ReleasePin,
        nodeId: String,
        into: FailureCollector,
    ): PipelineVersionStatus? {
        val name = pin.target.entityId
        val version = pin.target.version
        val child = pipelines.findByNameAnyStatus(workspaceId, name)
        val detail = child?.let { pipelines.findVersionDetail(workspaceId, it.id, version) }
        when {
            child == null -> {
                into.add(
                    Validation.PIPELINE_NOT_FOUND,
                    pin.path,
                    "Pipeline '${name.truncateForError()}' is not in the pipeline registry.",
                    mapOf("pipeline" to name.truncateForError()),
                )
            }

            detail == null -> {
                into.add(
                    Validation.PIPELINE_VERSION_NOT_FOUND,
                    pin.path,
                    "Pipeline '${name.truncateForError()}' has no version $version.",
                    mapOf("pipeline" to name.truncateForError(), "version" to version),
                )
            }

            detail.status == PipelineVersionStatus.DISCARDED -> {
                into.add(
                    Validation.PIPELINE_REFERENCE_NOT_RELEASED,
                    "${pin.path}.version",
                    "PIPELINE node '${nodeId.truncateForError()}' pins '${name.truncateForError()}' version $version, " +
                        "which is DISCARDED; a restore lands RELEASED and its child pins must name a live version.",
                    mapOf(
                        "node" to nodeId.truncateForError(),
                        "pipeline" to name.truncateForError(),
                        "pipeline_version" to version,
                        "status" to detail.status.name,
                    ),
                )
            }
        }
        return detail?.status?.takeUnless { it == PipelineVersionStatus.DISCARDED }
    }
}
