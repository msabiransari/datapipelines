package co.datapipelines.pipeline

import java.util.UUID

/**
 * Whether a pipeline NAME is registered in a workspace — the entity question §12.9 asks to tell
 * `pipeline_not_found` from `pipeline_version_not_found` (#723).
 *
 * [PipelineResolver.resolve] answers null for an unknown name and for an unknown version of a
 * known one alike, and no version number stands in for the entity: a preserved-number import
 * lands an entity at the version it carries (versioning §9.2 — gaps are expected), and purging a
 * never-released draft v1 beneath an imported release leaves the entity without version 1 (§3.3,
 * §5.4). The answer is the entity row, as the restore recheck already reads it
 * ([PipelineRestorePins], #517).
 *
 * Workspace-scoped like the resolver (design 2026-08-16-workspaces §3): another workspace's
 * pipeline of the same name is not registered HERE. A DISCARDED entity is registered — names are
 * unique forever (D59) — so a missing version of it is a missing version, not a missing pipeline.
 */
fun interface PipelineNames {
    /** True when an entity named [name] exists in [workspaceId], whatever the status of its versions. */
    fun isRegistered(
        workspaceId: UUID,
        name: String,
    ): Boolean

    companion object {
        /** The registry's answer: the workspace-scoped entity row, DISCARDED entities included. */
        fun of(repository: PipelineRepository): PipelineNames =
            PipelineNames { workspaceId, name -> repository.findByNameAnyStatus(workspaceId, name) != null }
    }
}
