package co.datapipelines.mcp

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.application.templates.TemplateUsage
import co.datapipelines.auth.Permission
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.templates.TemplateDraftService
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
 *  - NOTHING pins any version of it, ever — pipelines, parameter sets, (#320) visualizations and (#877 §D, ruling
 *    A.0 option 1) importing templates, [TemplateUsage]'s every-version-ever scans (D4), not the working-version scan
 *    `templates_used_by` answers.
 *
 * The purge itself is [TemplateRepository.purgeDraft] with the draft's current `body_hash` as
 * the §4.2 precondition: a concurrent edit between the guard reads and the delete is a
 * `template.version.conflict`, never a silent delete of someone else's save.
 *
 * #841 (G0, #744): the existence, lifecycle, creator and pinner checks and the delete run in ONE metadata transaction
 * that acquires the workspace writer guard first ([TemplateDraftService.guarded], the template writers' shared seam), so
 * a pin committed by a concurrent writer is either seen by the scans (`template.in_use`) or waits for the purge to
 * commit — it can never land between the scan and the delete.
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
    /** #841 — the template writers' guarded transaction ([TemplateDraftService.guarded]). */
    private val drafts: TemplateDraftService,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "templates_purge_draft",
            description =
                "Hard-delete a template that has NEVER been released: the only version is a DRAFT, created by " +
                    "this key's user, and pinned by nothing — no pipeline version anywhere, draft or released, " +
                    "no parameter-set version either (#194), no visualization version (#320), and no template version " +
                    "that imports it (#877) may reference any version of it (the refusal names the pinning pipelines, " +
                    "sets, visualizations or importing templates " +
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
     * The D4 guard, all four aggregates: every pipeline version ever, then every set version ever, then (#320) every
     * visualization version ever, then (#877 §D) every template version ever that imports it — a DRAFT importer
     * included, which this guard let purge its library out from under it before (a guard correction, ruling A.0).
     * #300 (the 194d security pass, observation 11): BOTH scans run under the WHOLE workspace —
     * the web guard's rule, unlensed ([TemplateUsage.referencedAnywhere] under a narrowing view
     * dropped hidden sets, so a lensed caller could purge a template a hidden set still pins).
     * Only the ECHO narrows: the refusal names the pins the caller's view admits and flags that others exist
     * ("pins_hidden": true) — never a name, and never a count, of what the caller cannot see.
     */
    private fun refuseIfPinned(
        workspaceId: java.util.UUID,
        id: String,
        caller: co.datapipelines.application.lens.LensedView,
    ) {
        val everything = co.datapipelines.application.lens.LensedView.EVERYTHING
        // The web service's `template.in_use` wire shape, verbatim: the refusal names the
        // pipelines to go and change — the admitted ones; hidden ones are a flag.
        refuseArm(
            id,
            usage.pipelinesReferencedAnywhere(workspaceId, everything, id).map { it.pipelineName },
            caller.pipelines,
            "live pipeline version(s)",
            "pinned_by",
        )
        refuseArm(
            id,
            usage.referencedAnywhere(workspaceId, everything, id).map { it.setName },
            caller.parameterSets,
            "parameter set version(s)",
            "referencing_parameter_sets",
        )
        refuseArm(
            id,
            usage.visualizationsReferencedAnywhere(workspaceId, everything, id).map { it.name },
            caller.visualizations,
            "visualization version(s)",
            "referencing_visualizations",
        )
        refuseArm(
            id,
            usage.importedBy(workspaceId, id, null, co.datapipelines.pipeline.inverse.InverseScope.ANY).map { it.importerName },
            caller.templates,
            "template version(s)",
            "referencing_templates",
        )
    }

    /**
     * One aggregate's `template.in_use` refusal, when [pinners] names anything: the [kind] sentence over the distinct
     * names, the admitted ones under [detailsKey] (always present when the arm refuses), `pins_hidden` when [lens] hides one.
     */
    private fun refuseArm(
        id: String,
        pinners: List<String>,
        lens: co.datapipelines.pipeline.ReadLens,
        kind: String,
        detailsKey: String,
    ) {
        val names = pinners.distinct()
        if (names.isEmpty()) return
        val (admitted, hidden) = splitByAdmission(names, lens)
        throw DatapipelinesException(
            code = PipelineErrorCodes.Template.IN_USE,
            message =
                "Version of template '$id' is pinned by " + pinnedBy(kind, names.size, admitted, hidden) +
                    "; discard or repoint them first.",
            details =
                buildMap {
                    put("template_id", id)
                    put(detailsKey, admitted)
                    if (hidden > 0) put("pins_hidden", true)
                },
        )
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
        return drafts.guarded(workspaceId) { purgeGuarded(workspaceId, id, ctx) }
    }

    /** [call]'s checks and delete, under the workspace writer guard. */
    @Suppress("ThrowsCount") // the purge's own refusals, moved here unchanged from [call] (an override detekt exempts) by #841
    private fun purgeGuarded(
        workspaceId: java.util.UUID,
        id: String,
        ctx: McpToolContext,
    ): Any {
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
