package co.datapipelines.pipeline

import java.util.UUID

/**
 * Whether a template ID is registered in a workspace — the entity question a restore asks to tell
 * `template_not_found` from `template_version_not_found` (#733).
 *
 * [TemplateVersionStatuses] answers null for an unknown template and for an unknown version of a
 * known one alike, and no version number stands in for the entity: a preserved-number import lands a
 * template at the version it carries (versioning §9.2 — gaps are expected), and purging a
 * never-released draft v1 beneath an imported release leaves the entity without version 1 (§3.3,
 * §5.4). The answer is the entity row, as the template save rule already reads it.
 *
 * Declared here as a port for the reason [TemplateVersionStatuses] is: `templates` depends on
 * `pipeline-contract`, never the reverse, so the aggregation layer supplies the implementation (over
 * `TemplateRepository.existsId`) and this interface carries no `companion of(...)`.
 *
 * Workspace-scoped: another workspace's template of the same ID is not registered HERE. A template
 * whose versions are all DISCARDED is registered, so a missing version of it is a missing version,
 * not a missing template.
 */
fun interface TemplateIds {
    /** True when a template [templateId] exists in [workspaceId], whatever the status of its versions. */
    fun isRegistered(
        workspaceId: UUID,
        templateId: String,
    ): Boolean
}
