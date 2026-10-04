package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.currentPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The templates CATALOG page (#398, ui-screens.md §4.6 in §4.3's shape): `GET /templates` is
 * one flat, server-paged list of full paths — every template the caller may read when `q` is
 * empty, or the matches of `q` — with the dialect and type filters, and (an author's) the
 * create modal. Each row links into the template workspace; the folder TREE is the sidebar's
 * (`data-nav-branch="templates"`, §3.4). The old two-pane explorer body is gone with #398:
 * a template is read, rendered and version-managed in ONE place, its workspace.
 */
@Controller
class TemplateUiController(
    private val browse: TemplateBrowseModel,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the page renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/templates")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun list(
        model: Model,
        request: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        TemplateFilters.fill(model, dialect, type)
        // §9.5: the create form's name check is rendered from the SERVER's own grammar —
        // never a second regex typed beside it. The server validates every write regardless
        // and its rejection is the one that counts.
        model.addAttribute("namePattern", TemplateNameGrammar.pattern)
        model.addAttribute("nameMaxLength", TemplateNameGrammar.maxLength)
        model.addAttribute("nameHint", TemplateNameGrammar.DESCRIPTION)
        model.addAttribute("types", TemplateType.WIRE_VALUES)
        // 7d: the create modal's transform half — which types carry the three blocks, and the
        // design record's example they start from (the create runs its suite, so it must pass).
        model.addAttribute("transformTypes", TemplateType.entries.filter { it.isTransform }.joinToString(",") { it.wire })
        model.addAttribute("skeleton", TransformSkeleton)
        model.addAttribute("q", q ?: "")
        val principal = currentPrincipal()
        browse.fillWrapper(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            q = q?.trim()?.takeIf { it.isNotEmpty() },
            dialect = TemplateFilters.dialect(dialect),
            type = TemplateFilters.type(type),
            offset = offset ?: 0,
            scope = TemplateListScope.CATALOG,
        )
        return "templates/list"
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
