package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The pipelines screen (ui-screens.md §4.3) — since 067 the **pipelines explorer**: the folder
 * tree on the left, the selected pipeline on the right.
 *
 * The page's first render goes through the same [PipelineBrowseModel] the htmx partial does,
 * so the screen and the fragment that replaces its list cannot disagree about which
 * presentation is showing or what it contains.
 */
@Controller
class PipelineUiController(
    private val browse: PipelineBrowseModel,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/pipelines")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        model.addAttribute("scopes", scopes())
        model.addAttribute("q", q ?: "")
        browse.fillWrapper(
            model,
            currentPrincipal().requireWorkspace().id,
            q?.trim()?.takeIf { it.isNotEmpty() },
            maxOf(0, offset ?: 0),
        )
        return "pipelines/list"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }
}
