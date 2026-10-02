package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The pipelines screen (ui-screens.md §4.3) — since #350 the **catalog**: a flat, server-paged
 * list of full paths (every pipeline the caller may read, or the matches of `q`), each row a
 * link to the canonical workspace. The folder tree moved into the global sidebar
 * (`/partials/pipelines?scope=nav`), so this page no longer carries a tree or a detail pane
 * (owner ruling 2026-10-02; spec §3.2 "#350 removes the redundant tree panel").
 *
 * The page's first render goes through the same [PipelineBrowseModel] the htmx partial does,
 * so the screen and the fragment that replaces its list cannot disagree about what it contains.
 * `?q=` keeps working as the deep link every "find this pipeline" link uses.
 */
@Controller
class PipelineUiController(
    private val browse: PipelineBrowseModel,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the page renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/pipelines")
    @RequiredScope(Permission.PIPELINE_READ)
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        RoleModel.stamp(model)
        model.addAttribute("q", q ?: "")
        val principal = currentPrincipal()
        browse.fillWrapper(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            q?.trim()?.takeIf { it.isNotEmpty() },
            maxOf(0, offset ?: 0),
            PipelineListScope.CATALOG,
        )
        return "pipelines/list"
    }
}
