package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/**
 * The canonical template workspace READ page (#398, workspace spec §3.1 read onto a family
 * without execution): `GET /templates/{*name}?version=N&tab=…`. One URL names one template,
 * one viewed version and one tab; the sidebar tree's leaves, the catalog's rows and the
 * version selector all land here, and the old `/templates/editor` route is a compatibility
 * redirect into it ([TemplateEditorController]).
 *
 * The name travels IN the path on purpose, and only on this page: `PathPatternParser` (the
 * base's routing, [TemplateWorkspaceRouteTest]) captures a dotted, multi-segment name whole
 * and gives the literal `/templates` and `/templates/editor` routes priority over the
 * capture. Everywhere else the name stays a query parameter (§9.6 — the container refuses an
 * encoded `%2F` in a path segment, and the grammar-legal name needs no escaping here).
 *
 * The floor is [Permission.TEMPLATE_READ] — the page is a read surface for every admitted
 * reader, the promoter through its lens, whose hidden templates answer the family's 404;
 * Render, Run suite and the lifecycle verbs stay their own permission-gated routes,
 * role-hidden in the markup (RoleVisibilityRenderTest). The tab set is closed; unknown and
 * missing resolve to the default, and a tab a role cannot read resolves to it too.
 */
@Controller
class TemplateWorkspaceController(
    private val workspace: TemplateWorkspaceModel,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the page resolves and renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/templates/{*name}")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun workspace(
        @PathVariable name: String,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) tab: String?,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        // The version parameter's one parse — the pipelines twin's own function, reused so
        // there is no second copy of the 400 rule to drift: optional, and when supplied a
        // positive integer, or the house 400 (never a silent clamp to another version).
        val requested = PipelineWorkspaceModel.parseRequestedVersion(version)
        val roles = RoleModel.roles(principal)
        // The capture carries the leading slash; the grammar's names do not.
        val templateName = name.removePrefix("/")
        // A name that fails the grammar cannot name a stored template: the family's 404,
        // before any read — a climb segment or a bare slash never becomes a folder path.
        if (!co.datapipelines.templates.TemplateNameGrammar.matches(templateName)) {
            throw workspace.notFound(templateName)
        }
        val resolved = workspace.resolve(workspaceId, view, templateName, requested)

        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model, principal)
        model.addAttribute("templateName", templateName)
        model.addAttribute("templateWorkspace", resolved)
        // The Used by fragment reads the flat attribute names the detail pane used to fill —
        // stamped from the one resolved facts object, so the tab and the refusal evidence
        // (the same scan `template.in_use` reads) cannot drift.
        model.addAttribute("usedBy", resolved.usedBy.pipelines)
        model.addAttribute("usedByCount", resolved.usedBy.pipelineCount)
        model.addAttribute("usedBySets", resolved.usedBy.sets)
        model.addAttribute("usedByVisualizations", resolved.usedBy.visualizations)
        model.addAttribute("usedBySummary", resolved.usedBy.summary)
        // #398 — the sidebar tree's current-leaf hook: the FULL path (the name is the id),
        // so the rail can mark this template's leaf and reveal its folders.
        model.addAttribute("navCurrentPath", templateName)
        model.addAttribute("canAuthor", roles.canAuthor)
        stampVersionState(model, resolved, roles)

        // The tab set the page's query contract admits (the pipelines twin's rule: the
        // server decides, the markup renders its answer). Render is the Render Context +
        // preview tab — its POST is MUTATE (`template.render`), it exists to feed Preview,
        // and a reader is not offered a control the server would refuse (143's rule); a
        // transform has no Render tab at all (its preview would feed a Freemarker render a
        // transform does not have — the old rail's own rule), so the page resolves render→
        // source for one before any render state is read.
        val hasRenderTab = roles.canAuthor && (resolved.viewedTemplate?.type?.isTransform != true)
        val activeTab = TemplateWorkspaceTab.fromWire(tab, hasRenderTab)
        model.addAttribute("activeTab", activeTab.wire)

        // The Source tab's column and the transform face read the SAME attribute names the
        // old editor page painted (and the /partials/templates/editor/source swap still
        // paints), so the first paint and every fragment render cannot disagree about
        // what a version shows.
        if (resolved.hasSelectedBody) fillSourceColumn(model, resolved, roles.canAuthor)
        return VIEW
    }

    /**
     * The version-state attributes the header, the phone band and the selector read — the
     * pipelines workspace's stamp, over the template's facts.
     */
    private fun stampVersionState(
        model: Model,
        resolved: TemplateWorkspaceModel.Resolved,
        roles: RoleModel.Roles,
    ) {
        model.addAttribute("hasSelectedBody", resolved.hasSelectedBody)
        model.addAttribute("viewedVersion", resolved.viewedVersion)
        model.addAttribute("viewedStatusLabel", resolved.viewedStatus?.name?.lowercase())
        model.addAttribute("viewedIsDraft", resolved.viewedIsDraft)
        model.addAttribute("viewedIsCurrent", resolved.viewedIsCurrent)
        model.addAttribute("currentVersion", resolved.currentVisible)
        model.addAttribute("viewedLabel", resolved.viewedLabel)
        // R5: the editable surfaces exist only on the working DRAFT, for an author. A
        // selected RELEASED version is read-only with the Edit verb; a selected older draft
        // (history holds at most one, but the rule is the version's, not the pointer's) is
        // read-only with Edit too.
        val editable = roles.canAuthor && resolved.viewedIsWorkingDraft
        model.addAttribute("viewedEditable", editable)
        // The draft affordances keep the editor's model names and their own role gates; the
        // draft pointer is lens-filtered by the service.
        model.addAttribute("hasDraft", resolved.draft != null)
        model.addAttribute("draftVersion", resolved.draft?.version)
        model.addAttribute("draftHash", resolved.draft?.bodyHash)
        // The header's verbs (102 §B.1's one-destructive rule, the detail pane's own
        // formulas): the entity purge in the {D} shape, else Discard of the resolved
        // release, else Purge draft — at most one of the three, all canAuthor.
        val versionCount = resolved.versions.size
        val canDelete = versionCount == 1 && resolved.draft != null
        val canDiscard = !canDelete && resolved.currentVisible != null
        model.addAttribute("canDelete", canDelete)
        model.addAttribute("canDiscardCurrent", canDiscard)
        model.addAttribute("canPurgeDraftInHeader", resolved.draft != null && !canDelete && !canDiscard)
        model.addAttribute("releasableVersion", resolved.draft?.version)
        model.addAttribute("currentReleaseVersion", resolved.currentVisible)
    }

    /**
     * The Source tab's column: the same fill `TemplateSourceModel.fill` paints for the swap
     * endpoint, from the workspace's own resolved version. `readOnly` is R5's conjunction —
     * the version rule (the viewed version must BE the working draft) AND the author
     * capability — computed once here, so the page, the fragment and the R5 test cannot
     * drift.
     */
    private fun fillSourceColumn(
        model: Model,
        resolved: TemplateWorkspaceModel.Resolved,
        canAuthor: Boolean,
    ) {
        val displayed = requireNotNull(resolved.viewedTemplate)
        val readOnly = !resolved.viewedIsWorkingDraft || !canAuthor
        val detail = resolved.selected.detail
        model.addAttribute("template", displayed)
        model.addAttribute("selectedVersion", displayed.version)
        model.addAttribute("selectedStatus", (detail?.status ?: displayed.status).name)
        model.addAttribute("releasedAt", detail?.releasedAt)
        model.addAttribute("releasedBy", detail?.releasedBy?.toString())
        model.addAttribute("readOnly", readOnly)
        // Overview's References reading (a derived scan, never a declared contract).
        model.addAttribute("interpolations", TemplateWorkspaceModel.interpolations(displayed.body))
        // 7d: a transform template's column is the face — the same fill the face's own GET gives.
        if (displayed.type.isTransform) TransformFace.fill(model, displayed, readOnly)
        model.addAttribute("isTransform", displayed.type.isTransform)
    }

    /** The tab set the page's query contract admits (workspace spec §3.1, read onto templates). */
    enum class TemplateWorkspaceTab(
        val wire: String,
    ) {
        SOURCE("source"),
        OVERVIEW("overview"),
        RENDER("render"),
        RUNS("runs"),
        USED_BY("used-by"),
        VERSIONS("versions"),
        ;

        companion object {
            /**
             * Unknown or missing resolves to Source; requesting Render without the author
             * capability ALSO resolves to Source — before any render state is read (the page
             * never renders what the role cannot use).
             */
            fun fromWire(
                raw: String?,
                canAuthor: Boolean,
            ): TemplateWorkspaceTab =
                entries
                    .firstOrNull { it.wire == raw }
                    ?.takeIf { it != RENDER || canAuthor }
                    ?: SOURCE
        }
    }

    companion object {
        /** Compatibility anchor: the template file name the render tests consume. */
        const val VIEW = "templates/workspace"
    }
}
