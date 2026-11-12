package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.web.pipelines.LifecycleVerbs
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The parameter set's release dialog (#876 §F.3): the family's first human release shell, opened from the workspace's
 * Versions arm into `#ps-dialog`. The GET renders the SHARED release dialog (`partials/release-dialog :: dialog`) over
 * the plan [ReleasePreviews] builds; the POST binds the plan it posted back and makes the SAME service call the REST
 * release makes ([ParameterSetService.release] with a plan, never the bare cascade alias). Both routes ride the existing
 * `PARAMETER_SET_RELEASE` row (author, workspace admin, super admin — never a viewer or a promoter), session-only like
 * every lifecycle verb (an API key is refused `auth.session.required` before anything is read), and the POST takes the
 * CSRF header path every htmx POST takes. A success answers `HX-Redirect` onto the set's workspace with the layout's
 * `released` flash; a refusal rides the `UiExceptionHandler` Shape C path with its real status, and the coordinator
 * writes every audit row inside its transaction — this handler writes none.
 */
@Controller
@RequestMapping("/partials/parameter-sets/{id}/lifecycle")
class ParameterSetLifecycleDialogController(
    private val sets: ParameterSetService,
    private val dialogs: ParameterSetLifecycleDialogModel,
    private val previews: ReleasePreviews,
) : ShellPage {
    @GetMapping("/release")
    @RequiredScope(Permission.PARAMETER_SET_RELEASE)
    fun releaseDialog(
        model: Model,
        @PathVariable id: UUID,
    ): String {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        val dlg = dialogs.release(workspaceId, id)
        model.addAttribute("dlg", dlg)
        if (dlg.refusal == null) {
            model.addAttribute("rd", previews.dialog(ReleaseFamily.PARAMETER_SET, workspaceId, dlg.name, dlg.version, principal.userId))
        }
        model.addAttribute("releaseAction", "/partials/parameter-sets/$id/lifecycle/release")
        model.addAttribute("releaseVerb", "parameter-set-release")
        RoleModel.stamp(model)
        return VIEW
    }

    @PostMapping("/release")
    @RequiredScope(Permission.PARAMETER_SET_RELEASE)
    fun release(
        @PathVariable id: UUID,
        @RequestParam bodyHash: String,
        @RequestParam(name = "tree_hash", required = false) treeHash: String?,
        @RequestParam(name = "include_dependencies", required = false) includeDependencies: List<String>?,
    ): ResponseEntity<String> {
        val principal = LifecycleVerbs.requireSession()
        val workspaceId = principal.requireWorkspace().id
        // The plan its GET rendered, into the SAME plan object the REST route parses: a stale tree_hash, an unticked
        // draft or an unavailable datasource is refused by the planner under the guard, before any flip.
        val plan = ReleaseDialogPost.plan(ReleaseFamily.PARAMETER_SET, treeHash, includeDependencies)
        val released = sets.release(workspaceId, id, bodyHash, principal.userId, releasePinnedTemplates = false, plan = plan)
        val url = "/parameter-sets/$id?version=${released.version.detail.version}&ok=released"
        return ResponseEntity.ok().header("HX-Redirect", url).body("")
    }

    private companion object {
        const val VIEW = "partials/release-dialog :: dialog"
    }
}
