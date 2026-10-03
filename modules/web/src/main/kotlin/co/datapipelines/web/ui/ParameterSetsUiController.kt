package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.parameters.ParameterSetResponses
import co.datapipelines.web.ui.site.ScriptSafeJson
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The Parameter Sets screens (#374, ui-screens §4.23): the flat catalog page and the canonical
 * version-explicit workspace page, [DashboardUiController]'s and [PipelineWorkspaceController]'s shape
 * for the third artifact family — a `@Controller` of session-authenticated, scope-declared handlers whose
 * model work lives in [ParameterSetsBrowseModel] and [ParameterSetsWorkspaceModel].
 *
 * Both routes floor at [Permission.PARAMETER_SET_READ]: the page is a read surface for every admitted
 * reader, the promoter through the lens. Evaluate stays its own permission-gated call, role-hidden in the
 * markup (`canEvaluateParameterSets`) AND never issued by the client without the flag. A set the caller
 * cannot see (absent, foreign, lens-hidden, an explicit version not admitted) is the family's one 404.
 *
 * `?tab=history` (#376) paints the set's evaluation records through [ParameterSetEvaluationsBrowseModel] — the SAME
 * model its pager partial uses — and renders no live form: the state block says there is no body to mount, so the
 * History arm never issues an evaluate.
 *
 * The two JSON blocks go out through [ScriptSafeJson.forScriptBlock]: a set's `display_name`,
 * `description` and labels have no charset rule, and a `</script>` or `<!--` inside a value must not
 * close the block it rides in.
 */
@Controller
class ParameterSetsUiController(
    private val browse: ParameterSetsBrowseModel,
    private val workspace: ParameterSetsWorkspaceModel,
    /** #376 — the History tab's first page; its pager is [ParameterSetEvaluationsPartialController] over the same model. */
    private val history: ParameterSetEvaluationsBrowseModel,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the pages render the caller's view. */
    private val lens: PromoterLens,
) {
    // NOT a constructor parameter: Spring injects the app's servlet ObjectMapper into an ObjectMapper-typed
    // parameter even when it has a default, and that mapper lacks the contract modules.
    // Guarded by ObjectMapperDefaultParameterKonsistTest.
    private val mapper: ObjectMapper = ParameterSetJson.mapper

    /** The catalog page — a flat, server-paged list over the same read the sidebar's tree is built from. */
    @GetMapping("/parameter-sets")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        browse.fillCatalog(model, principal.requireWorkspace().id, lens.viewFor(principal), offset ?: 0)
        return LIST_VIEW
    }

    /** The canonical workspace page: one URL names one set, one viewed version and one tab. */
    @GetMapping("/parameter-sets/{id}")
    @RequiredScope(Permission.PARAMETER_SET_READ)
    fun workspace(
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) tab: String?,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        val requested = ParameterSetsWorkspaceModel.parseRequestedVersion(version)
        val roles = RoleModel.roles(principal)
        val resolved = workspace.resolve(workspaceId, view, id, requested)

        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        model.addAttribute("parameterSetId", id)
        model.addAttribute("parameterSetName", resolved.record.name)
        model.addAttribute("parameterSetDisplayName", resolved.selected?.body?.displayName ?: resolved.record.displayName)
        // The sidebar tree's current-leaf hook reads the FULL path, so the rail can reveal this set's folders.
        model.addAttribute("navCurrentPath", resolved.record.name)
        val activeTab = ParameterSetsWorkspaceModel.ParameterSetWorkspaceTab.fromWire(tab)
        val onHistory = activeTab == ParameterSetsWorkspaceModel.ParameterSetWorkspaceTab.HISTORY
        model.addAttribute("activeTab", activeTab.wire)
        if (onHistory) history.fillHistory(model, workspaceId, view, id, offset = 0)
        model.addAttribute("hasSelectedBody", resolved.hasSelectedBody)
        model.addAttribute("viewedVersion", resolved.viewedVersion)
        model.addAttribute("viewedIsDraft", resolved.viewedIsDraft)
        model.addAttribute("viewedIsCurrent", resolved.viewedIsCurrent)
        model.addAttribute("currentVersion", resolved.currentVisible)
        model.addAttribute("viewedLabel", resolved.viewedLabel)
        model.addAttribute("versions", resolved.versions)
        // Only a body that exists AND a role that may evaluate renders the live form; the promoter's page
        // renders the structure (graph + inspector) and no control.
        model.addAttribute("canEvaluate", roles.canEvaluateParameterSets && resolved.hasSelectedBody)

        resolved.selected?.let { selected ->
            val tree = ParameterSetResponses.full(resolved.record, selected.body, selected.detail, resolved.draft) as ObjectNode
            // The page's own copy of the tree: `current_version` names the current the CALLER MAY SEE (a hidden
            // pointer never reaches a promoter's script JSON), exactly as the pipeline workspace's block does.
            resolved.currentVisible?.let { tree.put("current_version", it) } ?: tree.putNull("current_version")
            model.addAttribute("parameterSetJson", ScriptSafeJson.forScriptBlock(mapper.writeValueAsString(tree)))
        }
        // The workspace state the client pins reads and submits from — ONE source for the displayed and the
        // submitted version. A malformed block is a refusal in the client, never a default.
        // On the History tab there is nothing to mount: no body block, no form — `hasBody` and `canEvaluate` say so, and the
        // client then renders no structure and issues no evaluate (#376).
        val mountsBody = resolved.hasSelectedBody && !onHistory
        val state =
            mapOf<String, Any?>(
                "parameterSetId" to resolved.record.id.toString(),
                "viewedVersion" to resolved.viewedVersion,
                "tab" to activeTab.wire,
                "hasBody" to mountsBody,
                "canEvaluate" to (roles.canEvaluateParameterSets && mountsBody),
                "versionRows" to
                    resolved.versions.map {
                        mapOf<String, Any?>(
                            "version" to it.version,
                            "status" to it.status.name,
                            "current" to it.isCurrent,
                            "viewed" to it.isViewed,
                        )
                    },
            )
        model.addAttribute("workspaceJson", ScriptSafeJson.forScriptBlock(mapper.writeValueAsString(state)))
        return WORKSPACE_VIEW
    }

    private companion object {
        const val LIST_VIEW = "parameter-sets/list"
        const val WORKSPACE_VIEW = "parameter-sets/workspace"
    }
}
