package co.datapipelines.mcp

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateUsageService
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema

/**
 * `templates_purge_draft` (107 — the bounded D61/D62 self-service verb). Scope: `author`.
 * Mutating.
 *
 * The agent-reachable fraction of the 101 lifecycle: hard-delete a template that has NEVER been
 * released. `web`'s `TemplateReleaseService` is unreachable from this module (mcp-server is a
 * thin adapter web depends on, never the reverse), so this tool composes [TemplateRepository] +
 * [TemplateUsageService] directly and ports the MINIMAL sole-draft path of
 * `TemplateReleaseService.purgeEntity` — no more:
 *
 *  - the ONLY version is a DRAFT (a template holding any RELEASED or DISCARDED version is
 *    refused: humans release, and humans discard releases — D4/D57 stay with the UI);
 *  - the draft was created by THIS key's user (templates carry a creator USER id, no key-id
 *    column — "this key created it" degrades to "this key's user");
 *  - NOTHING pins any version of it, ever — [TemplateUsageService.referencedAnywhere], the
 *    delete guard's every-version-ever scan (D4), not the working-version scan
 *    `templates_used_by` answers.
 *
 * The purge itself is [TemplateRepository.purgeDraft] with the draft's current `body_hash` as
 * the §4.2 precondition: a concurrent edit between the guard reads and the delete is a
 * `template.version.conflict`, never a silent delete of someone else's save.
 */
class TemplatesPurgeDraftTool(
    private val templates: TemplateRepository,
    private val usage: TemplateUsageService,
    private val authoring: AuthoringGuard,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_purge_draft",
            description =
                "Hard-delete a template that has NEVER been released: the only version is a DRAFT, created by " +
                    "this key's user, and pinned by nothing — no pipeline version anywhere, draft or released, " +
                    "may reference any version of it (the refusal names the pinning pipelines; templates_used_by " +
                    "answers the working-version scan if you need to inspect them). The sole-draft purge takes " +
                    "the entity row with it. A template holding any RELEASED or discarded version, another " +
                    "user's draft, or a pinned draft is refused — humans release and humans discard releases; " +
                    "an agent's own draft that should not exist is what this verb removes. Mutating.",
            schema =
                """
                {
                  "type": "object",
                  "required": ["id"],
                  "additionalProperties": false,
                  "properties": {
                    "id": {"type": "string", "description": "Template id."}
                  }
                }
                """.trimIndent(),
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        // versioning §5.5: purging authored content is authoring — a promotion receiver refuses it.
        authoring.requireTemplateAuthoring()
        val workspaceId = ctx.principal.requireWorkspace().id
        val id = args.requiredString("id")
        if (!templates.existsId(workspaceId, id)) throw McpNotFound.template(id)

        val versions = templates.listVersions(workspaceId, id)
        if (versions.size != 1 || versions[0].status != PipelineVersionStatus.DRAFT) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_LAST_RELEASE,
                message =
                    "Template '$id' cannot be purged: an entity purge requires the only version to be " +
                        "a DRAFT (this one has ${versions.size}).",
                details = mapOf("template_id" to id, "version_count" to versions.size),
            )
        }
        if (versions[0].createdBy != ctx.principal.userId) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Auth.SCOPE_INSUFFICIENT,
                message = "Template '$id' was created by someone else; a key purges only its own user's drafts.",
                details = mapOf("template_id" to id, "reason" to "not_creator"),
            )
        }
        val pinners = usage.referencedAnywhere(workspaceId, id)
        if (pinners.isNotEmpty()) {
            // The web service's `template.in_use` wire shape, verbatim: the refusal names the
            // pipelines to go and change.
            val names = pinners.map { it.pipelineName }.distinct()
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.IN_USE,
                message =
                    "Version of template '$id' is pinned by ${names.size} live pipeline version(s): " +
                        names.joinToString(", ") + "; discard or repoint them first.",
                details = mapOf("template_id" to id, "pinned_by" to names),
            )
        }

        val draft = templates.findDraftDetail(workspaceId, id) ?: throw McpNotFound.template(id)
        if (!templates.purgeDraft(workspaceId, id, draft.bodyHash, authoring.developmentPosture)) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.VERSION_CONFLICT,
                message = "Template was modified by someone else after you loaded it.",
                details = mapOf("template_id" to id),
            )
        }
        return mapOf("id" to id, "purged" to true)
    }
}
