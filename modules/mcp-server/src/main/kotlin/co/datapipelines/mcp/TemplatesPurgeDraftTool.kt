package co.datapipelines.mcp

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.auth.Permission
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.DatapipelinesException
import io.modelcontextprotocol.spec.McpSchema

/**
 * `templates_purge_draft` (107 — the bounded D61/D62 self-service verb).
 * Permission: `template.version.manage`. Mutating.
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
 *  - NOTHING pins any version of it, ever — pipelines, parameter sets and (#320) visualizations,
 *    [TemplateUsage]'s every-version-ever scans (D4), not the working-version scan
 *    `templates_used_by` answers.
 *
 * The purge itself is [TemplateRepository.purgeDraft] with the draft's current `body_hash` as
 * the §4.2 precondition: a concurrent edit between the guard reads and the delete is a
 * `template.version.conflict`, never a silent delete of someone else's save.
 */
class TemplatesPurgeDraftTool(
    private val templates: TemplateRepository,
    private val usage: TemplateUsage,
    private val authoring: AuthoringGuard,
    /**
     * 178 — the caller's view. #300: the guard's SCANS run under the whole workspace (the web
     * guard's rule); the lens decides only which pinned names the refusal may ECHO.
     */
    private val lens: PromoterLens,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_purge_draft",
            description =
                "Hard-delete a template that has NEVER been released: the only version is a DRAFT, created by " +
                    "this key's user, and pinned by nothing — no pipeline version anywhere, draft or released, " +
                    "no parameter-set version either (#194), and no visualization version (#320) " +
                    "may reference any version of it (the refusal names the pinning pipelines, sets or visualizations " +
                    "your view admits; templates_used_by " +
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

    /**
     * The D4 guard, all three aggregates: every pipeline version ever, then every set version ever, then (#320) every
     * visualization version ever.
     * #300 (the 194d security pass, observation 11): BOTH scans run under the WHOLE workspace —
     * the web guard's rule, unlensed ([TemplateUsage.referencedAnywhere] under a narrowing view
     * dropped hidden sets, so a lensed caller could purge a template a hidden set still pins).
     * Only the ECHO narrows: the refusal names the pins the caller's view admits and flags that others exist
     * ("pins_hidden": true) — never a name, and never a count, of what the caller cannot see.
     */
    @Suppress("ThrowsCount") // one refusal per aggregate that pins templates — each names its own arm and lens
    private fun refuseIfPinned(
        workspaceId: java.util.UUID,
        id: String,
        caller: co.datapipelines.application.lens.LensedView,
    ) {
        val pinners = usage.pipelinesReferencedAnywhere(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, id)
        if (pinners.isNotEmpty()) {
            // The web service's `template.in_use` wire shape, verbatim: the refusal names the
            // pipelines to go and change — the admitted ones; hidden ones are a flag.
            val names = pinners.map { it.pipelineName }.distinct()
            val (admitted, hidden) = splitByAdmission(names, caller.pipelines)
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.IN_USE,
                message =
                    "Version of template '$id' is pinned by " + pinnedBy("live pipeline version(s)", names.size, admitted, hidden) +
                        "; discard or repoint them first.",
                details =
                    buildMap {
                        put("template_id", id)
                        put("pinned_by", admitted)
                        if (hidden > 0) put("pins_hidden", true)
                    },
            )
        }
        val setPinners = usage.referencedAnywhere(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, id)
        val visualizationPinners =
            usage.visualizationsReferencedAnywhere(workspaceId, co.datapipelines.application.lens.LensedView.EVERYTHING, id)
        if (setPinners.isNotEmpty()) {
            val setNames = setPinners.map { it.setName }.distinct()
            val (admitted, hidden) = splitByAdmission(setNames, caller.parameterSets)
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.IN_USE,
                message =
                    "Version of template '$id' is pinned by " + pinnedBy("parameter set version(s)", setNames.size, admitted, hidden) +
                        "; discard or repoint them first.",
                details =
                    buildMap {
                        put("template_id", id)
                        put("referencing_parameter_sets", admitted)
                        if (hidden > 0) put("pins_hidden", true)
                    },
            )
        }
        if (visualizationPinners.isNotEmpty()) {
            val names = visualizationPinners.map { it.name }.distinct()
            val (admitted, hidden) = splitByAdmission(names, caller.visualizations)
            throw DatapipelinesException(
                code = PipelineErrorCodes.Template.IN_USE,
                message =
                    "Version of template '$id' is pinned by " + pinnedBy("visualization version(s)", names.size, admitted, hidden) +
                        "; discard or repoint them first.",
                details =
                    buildMap {
                        put("template_id", id)
                        put("referencing_visualizations", admitted)
                        if (hidden > 0) put("pins_hidden", true)
                    },
            )
        }
    }

    /** Split pinned names into what [lens] admits (echoed) and how many it hides (flagged, never echoed). */
    private fun splitByAdmission(
        names: List<String>,
        lens: co.datapipelines.pipeline.ReadLens,
    ): Pair<List<String>, Int> = names.filter { lens.admits(it) } to names.count { !lens.admits(it) }

    /**
     * The web guard's sentence, byte-for-byte, for a caller whose view admits every pin; when a
     * pin is HIDDEN the sentence carries no cardinality at all — neither the total nor the hidden
     * count (the 300 security pass: a count of hidden objects is an existence oracle) — only the
     * admitted names and the fact that others exist outside the view.
     */
    private fun pinnedBy(
        kind: String,
        total: Int,
        admitted: List<String>,
        hidden: Int,
    ): String =
        when {
            hidden == 0 -> "$total $kind: " + admitted.joinToString(", ")
            admitted.isEmpty() -> "$kind outside your view"
            else -> "$kind: " + admitted.joinToString(", ") + " and others outside your view"
        }

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
            // Ownership, not permission — but `auth.role_required` is the ONE authorization
            // refusal (#215, owner ruling 2026-09-24): `details.reason` names the rule that fired,
            // `required`/`held` what the role was judged as.
            throw DatapipelinesException(
                code = PipelineErrorCodes.Auth.ROLE_REQUIRED,
                message = "Template '$id' was created by someone else; a key purges only its own user's drafts.",
                details =
                    mapOf(
                        "template_id" to id,
                        "reason" to "not_creator",
                        "required" to Permission.TEMPLATE_VERSION_MANAGE.wire,
                        "held" to ctx.principal.heldRole,
                    ),
            )
        }
        // #194 lane D — the guard covers parameter-set pins too (the record's §8.4). #300: the
        // scans read the WHOLE workspace; the caller's view narrows only the echo.
        refuseIfPinned(workspaceId, id, lens.viewFor(ctx.principal))

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
