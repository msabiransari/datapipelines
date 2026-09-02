package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.TemplatePin
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The template used-by service (040) — the reverse arrow from a template version to the
 * pipelines pinning it. Every used-by surface (the `templates_used_by` MCP tool, the template
 * screen's per-version in-use count, the delete guard, the pipeline read's upgrade signal)
 * reads THIS service; none of them re-runs a scan of its own.
 *
 * The service owns the two QUESTIONS and keeps them apart (040 D1 — they are not one query):
 *
 *  - **Who is using `t@v` right now?** [usedBy] scans each pipeline's working version (the
 *    draft when one exists, else the latest released — versioning §7's rule), so a draft that
 *    just adopted the pin is counted. It drives the used-by listing, the "who do I notify"
 *    answer and the upgrade signal.
 *  - **Is it safe to remove `t`?** [referencedAnywhere] scans every pipeline version ever
 *    stored — pipeline versions are immutable and executable, so a pin in a historical version
 *    is a real reference. It drives the delete guard (D4's refusal-to-delete-while-referenced;
 *    this round adds retirement protection, never hard deletion).
 *
 * Conflating the two is how a used-by listing under-reports drafts or a delete guard
 * under-reports history; [PipelineRepository]'s scan KDocs carry the SQL half of the argument.
 */
class TemplateUsageService(
    private val templates: TemplateRepository,
    private val pipelines: PipelineRepository,
) {
    /** The used-by answer for one template version: one [TemplatePin] per pinning NODE. */
    data class UsedBy(
        val templateId: String,
        val version: Int,
        val references: List<TemplatePin>,
        /** Distinct pipelines among [references] — two nodes of one pipeline are one user. */
        val pipelineCount: Int,
    )

    /**
     * Question 1 — who pins `id@version` in their working version right now?
     *
     * @throws DatapipelinesException `template.not_found` when the template id is unknown in
     *   the workspace (a soft-deleted template is NOT unknown here: its versions still resolve
     *   for existing pins, which is exactly the retirement case the question serves), and the
     *   same code with a `version` detail when the id exists but that version does not.
     */
    fun usedBy(
        workspaceId: UUID,
        id: String,
        version: Int,
    ): UsedBy {
        if (!templates.existsId(workspaceId, id)) throw templateNotFound(id)
        if (templates.findVersionStatus(workspaceId, id, version) == null) throw templateVersionNotFound(id, version)
        val references = pipelines.findWorkingVersionTemplatePins(workspaceId, id, version)
        return UsedBy(
            templateId = id,
            version = version,
            references = references,
            pipelineCount = references.map(TemplatePin::pipelineId).distinct().size,
        )
    }

    /**
     * The template screen's per-version in-use counts (040 D6) — distinct pipelines pinning
     * each version in their working version. An unknown or soft-deleted template answers an
     * empty map: the version list renders zeros, which is truthful for a deleted template
     * (nothing may newly reference it) and cheap for a miss.
     */
    fun inUseCounts(
        workspaceId: UUID,
        id: String,
    ): Map<Int, Int> = pipelines.countWorkingTemplatePinsByPinnedVersion(workspaceId, id)

    /**
     * Question 2 — every pin of ANY version of `id`, from ANY pipeline version ever stored.
     * The delete guard's evidence (D4): non-empty means the refusal's `template.in_use`
     * answer, and each [TemplatePin] names the pipeline, node and pipeline version to go and
     * change.
     */
    fun referencedAnywhere(
        workspaceId: UUID,
        id: String,
    ): List<TemplatePin> = pipelines.findAnyVersionTemplatePins(workspaceId, id)

    /**
     * One entry of the pipeline read's upgrade signal (040 D5): node [node] pins
     * `templateId@pinned` while `templateId@latestReleased` exists. Surfaced, never applied —
     * moving a pin is a pipeline edit and stays the author's decision.
     */
    data class UpgradeAvailable(
        val node: String,
        val templateId: String,
        val pinned: Int,
        val latestReleased: Int,
    )

    /**
     * The upgrade signal for one pipeline body: for each node whose pinned template has a
     * NEWER RELEASED version, say which node and which versions (D5). A pin of a template
     * DRAFT version (`pinned` above the latest released) is deliberately NOT an upgrade — the
     * author is already ahead of release, which is information, not a signal.
     *
     * Reads the body as JSON rather than through the pipeline deserializer: the callers (the
     * pipeline reads) hold the stored body string, and the walk only needs `nodes[].id` and
     * `nodes[].template` — the deserializer's full contract would make this read refuse
     * bodies a looser historical writer might have stored, which a signal must never do.
     * Soft-deleted templates answer no latest version, so their pins carry no signal (there is
     * nothing to upgrade to).
     */
    fun upgradeAvailable(
        workspaceId: UUID,
        bodyJson: String,
    ): List<UpgradeAvailable> {
        val tree = MAPPER.readTree(bodyJson)
        val nodes = tree.get("nodes")?.takeIf { it.isArray } ?: return emptyList()
        val latestByTemplate =
            templates.findCurrentVersions(
                workspaceId,
                nodes
                    .mapNotNull { node ->
                        node
                            .get("template")
                            ?.get("id")
                            ?.takeIf { id -> id.isTextual }
                            ?.asText()
                    }.toSet(),
            )
        if (latestByTemplate.isEmpty()) return emptyList()
        return nodes
            .mapNotNull { node ->
                val ref = node.get("template") ?: return@mapNotNull null
                val id = ref.get("id")?.takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
                val pinned = ref.get("version")?.takeIf { it.isInt }?.asInt() ?: return@mapNotNull null
                val latest = latestByTemplate[id] ?: return@mapNotNull null
                val nodeId = node.get("id")?.takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
                if (pinned < latest) UpgradeAvailable(nodeId, id, pinned, latest) else null
            }
    }

    private fun templateNotFound(id: String): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "Template '$id' does not exist.",
            details = mapOf("template_id" to id),
        )

    private fun templateVersionNotFound(
        id: String,
        version: Int,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Template.NOT_FOUND,
            message = "Template '$id' has no version $version.",
            details = mapOf("template_id" to id, "version" to version),
        )

    private companion object {
        val MAPPER = TemplateJson.objectMapper()
    }
}
