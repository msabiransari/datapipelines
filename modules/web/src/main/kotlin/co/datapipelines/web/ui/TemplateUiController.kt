package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The templates screen (ui-screens.md §4.6, template-hierarchy-design §9.2).
 *
 * Renders the shell **and** the first fragment; every later refresh hits
 * [TemplatePartialController] and swaps `#template-list-wrapper` only. The model the
 * fragment needs is filled by the one [TemplateBrowseModel] both controllers share, so the
 * page's first render and an htmx refresh cannot disagree about what a level contains.
 */
@Controller
class TemplateUiController(
    private val browse: TemplateBrowseModel,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/templates")
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        model.addAttribute("scopes", scopes())
        TemplateFilters.fill(model, dialect, type)
        // §9.5: the create form's name check is rendered from the SERVER's own grammar —
        // never a second regex typed beside it. The server validates every write regardless
        // and its rejection is the one that counts.
        model.addAttribute("namePattern", TemplateNameGrammar.pattern)
        model.addAttribute("nameMaxLength", TemplateNameGrammar.maxLength)
        model.addAttribute("nameHint", TemplateNameGrammar.DESCRIPTION)
        model.addAttribute("types", TemplateType.WIRE_VALUES)
        val query = q?.trim()?.takeIf { it.isNotEmpty() }
        model.addAttribute("q", q ?: "")
        browse.fillWrapper(
            model,
            currentPrincipal().requireWorkspace().id,
            q = query,
            dialect = TemplateFilters.dialect(dialect),
            type = TemplateFilters.type(type),
            offset = offset ?: 0,
        )
        return "templates/list"
    }

    private fun scopes(): Set<String> {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
        return principal?.scopes?.map { it.name }?.toSet() ?: emptySet()
    }
}

/**
 * The templates screen's two list filters, bound the same way on every surface that renders
 * them (the page, the partial, the create-success refresh).
 *
 * `dialect` and `type` are both **exact** matches on the version row and both optional; an
 * unrecognised wire value binds to `null` — an unknown filter shows everything rather than
 * nothing, which is the behaviour the dialect filter has always had.
 */
internal object TemplateFilters {
    fun dialect(raw: String?): Dialect? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
            Dialect.entries.firstOrNull { it.wire.equals(d, ignoreCase = true) }
        }

    fun type(raw: String?): TemplateType? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
            TemplateType.entries.firstOrNull { it.wire.equals(t, ignoreCase = true) }
        }

    /** Echoes the selected filter values back so the controls re-render in the state they were used in. */
    fun fill(
        model: Model,
        dialect: String?,
        type: String?,
    ) {
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        model.addAttribute("selectedDialect", dialect ?: "")
        model.addAttribute("selectedType", type ?: "")
    }
}
