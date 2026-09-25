package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.currentPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView

/**
 * The templates screen's htmx fragments (template-hierarchy-design §9.2, ui-screens.md §4.6).
 *
 * Every handler here is under `/partials`, which the [co.datapipelines.auth.ScopeInterceptor]
 * governs as **default-deny**: a partial carrying no [RequiredScope] is refused, so a new
 * fragment endpoint joins the same authorization posture as the one it sits beside rather
 * than quietly opening a hole (§9.1).
 *
 * ## One route, two fragment shapes — chosen by `prefix`
 *
 * - **`prefix` absent** → the wrapper: the whole `#template-list-wrapper`, which is the search
 *   result list when `q` is non-empty and the tree's root level otherwise. This is the target
 *   of the filter controls and of the search pager — the existing SPA contract, unchanged.
 * - **`prefix` present** (empty string = the root) → that ONE tree level: its direct
 *   sub-folders and its direct template children, and nothing else. This is what a folder's
 *   lazy expansion fetches, so expanding `acme/finance` never returns `acme/hr`'s rows and
 *   never returns the whole list (§9.1: the tree is backed by server-side prefix queries).
 *
 * `q` is ignored while `prefix` is present: browse and search are different presentations
 * (§9.2) and a folder expansion is unambiguously a browse.
 */
@Controller
class TemplatePartialController(
    private val templates: TemplateRepository,
    private val browse: TemplateBrowseModel,
    private val validator: TemplateValidator,
    private val authoring: AuthoringGuard,
    /** 178 — the promoter lens: every fragment here renders the caller's view. */
    private val lens: PromoterLens,
) {
    @GetMapping("/partials/templates")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) prefix: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        val dialectFilter = TemplateFilters.dialect(dialect)
        val typeFilter = TemplateFilters.type(type)
        TemplateFilters.fill(model, dialect, type)
        model.addAttribute("q", q ?: "")
        RoleModel.stamp(model)
        return if (prefix != null) {
            browse.fillLevel(model, workspaceId, view, prefix, dialectFilter, typeFilter, offset ?: 0)
        } else {
            browse.fillWrapper(
                model,
                workspaceId,
                view,
                q = q?.trim()?.takeIf { it.isNotEmpty() },
                dialect = dialectFilter,
                type = typeFilter,
                offset = offset ?: 0,
            )
        }
    }

    /**
     * The SELECTED template, for the explorer's right pane (058, reshaped by 106) — the
     * header (path eyebrow, leaf name, Open in editor and 101's verbs), the READING column
     * (Overview with the source excerpt, and Used by) and the ACTING column
     * (Versions · Source · Runs).
     *
     * A selection swaps this fragment into `#template-detail` with `innerHTML` and touches
     * nothing else: the tree pane's DOM is never re-rendered by a selection.
     *
     * The route keeps its historical spelling (`/versions`) — it is what every leaf, every
     * search row and the explorer script already point at, and renaming it would be a
     * migration for no reader's benefit.
     *
     * §9.6: the name is a query parameter. It may contain `/`, and an encoded `%2F` in a URL
     * **path segment** is refused 400 by the container below routing — which is why the two
     * fragments below take `name` as a parameter where the pipelines twin can use `{id}`.
     */
    @GetMapping("/partials/templates/versions")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun versions(
        model: Model,
        @RequestParam name: String,
    ): String {
        RoleModel.stamp(model)
        val principal = currentPrincipal()
        return browse.fillDetail(model, principal.requireWorkspace().id, lens.viewFor(principal), name)
    }

    /**
     * The acting column's **Runs** tab — recent executions of the pipelines pinning this
     * template, loaded on the tab's first click.
     *
     * Visibility is the execution-history screen's, unchanged: an admin sees the workspace's
     * runs, everyone else sees their own.
     */
    @GetMapping("/partials/templates/runs")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun runs(
        model: Model,
        @RequestParam name: String,
    ): String {
        val principal = currentPrincipal()
        return browse.fillRuns(
            model,
            principal.requireWorkspace().id,
            lens.viewFor(principal),
            name,
            principal.userId,
            principal.holds(Permission.EXECUTION_READ_ALL),
        )
    }

    /**
     * The create modal's action (§9.3) — form-encoded fields, the §5 idiom, bound into a
     * [TemplateDraft] and put through the **same** [TemplateValidator] and repository call
     * the REST `POST /api/v1/templates` uses. One component, two surfaces: a refusal the API
     * would give is a refusal here.
     *
     * `type` is a create-time input and appears on no other form (§5.3 makes it immutable);
     * `dialect` is conditional on it — required for `sql`, absent for `html` and the transform
     * types, with the database's `chk_type_dialect` as the backstop. There is no rename
     * affordance because §4.5 offers no rename: `name` is a create-time input, full stop.
     *
     * 7d: a transform type (`jsonata` / `javascript`) also submits its three blocks — the modal
     * prefills the design record's example — and they bind through the same binder the
     * transform face's Save uses (7b's deserializer, strict), so a typo in a block is the
     * `contract_invalid` the REST create would give. The create then runs the suite (7b's gate).
     */
    @Suppress("LongParameterList", "LongMethod") // one form field per parameter; the two type families share one refusal path
    @PostMapping("/partials/templates")
    @RequiredScope(Permission.TEMPLATE_CREATE)
    fun create(
        model: Model,
        @RequestParam name: String,
        @RequestParam type: String,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) displayName: String?,
        @RequestParam(required = false) description: String?,
        @RequestParam body: String,
        @RequestParam(required = false) contract: String? = null,
        @RequestParam(required = false) invariants: String? = null,
        @RequestParam(required = false) tests: String? = null,
    ): Any {
        val principal = principal() ?: error("No authenticated principal")
        val workspaceId = principal.requireWorkspace().id
        val templateType = TemplateFilters.type(type) ?: return refused("Unknown template type '$type'.")
        val trimmedName = name.trim()
        return try {
            // §5.5: creation is authoring — a promotion receiver refuses it, on this surface
            // exactly as on the REST one.
            authoring.requireTemplateAuthoring()
            if (templates.existsId(workspaceId, trimmedName)) {
                return refused("A template named '$trimmedName' already exists.")
            }
            val shownName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: trimmedName
            val draft =
                if (templateType.isTransform) {
                    val identity =
                        TransformFace.Identity(
                            trimmedName,
                            templateType.wire,
                            Template.NONE_ENGINE,
                            shownName,
                            description?.trim().orEmpty(),
                        )
                    val panes = TransformPanes(body, contract.orEmpty(), invariants.orEmpty(), tests.orEmpty())
                    when (val bound = TransformFace.bind(identity, panes)) {
                        is TransformFace.Bound.Refused -> return refused(bound.refusals.joinToString(" ") { it.line })
                        is TransformFace.Bound.Draft -> bound.draft
                    }
                } else {
                    TemplateDraft(
                        id = trimmedName,
                        type = templateType,
                        // §5.1's chk_type_dialect: a dialect belongs to `sql` only. The form hides
                        // the control for `html`; dropping any value it might still carry is what
                        // makes the server, not the form, the authority on that rule.
                        dialect = if (templateType == TemplateType.SQL) TemplateFilters.dialect(dialect) else null,
                        displayName = shownName,
                        description = description?.trim().orEmpty(),
                        body = body,
                    )
                }
            validator.validateOrThrow(draft, workspaceId)
            // D55: the editor's create lands version 1 DRAFT — the same rule the API follows.
            templates.create(workspaceId, draft, principal.userId, CreateLifecycle.DRAFT, WriteSurface.SESSION)
            // Shape A (§5.1): the success node lands in #template-create-result — its arrival
            // is what closes the modal — and the refreshed list rides along out-of-band. No
            // HX-Redirect: a navigation would discard the toast.
            TemplateFilters.fill(model, dialect = null, type = null)
            model.addAttribute("q", "")
            RoleModel.stamp(model)
            // An author's re-render after a create (MUTATE row): the view is theirs — Everything.
            browse.fillWrapper(model, workspaceId, LensedView.EVERYTHING, q = null, dialect = null, type = null, offset = 0)
            model.addAttribute("createdName", trimmedName)
            model.addAttribute("oob", true)
            "partials/template-created"
        } catch (e: TemplateValidationException) {
            // The server's rejection is the one that counts (§9.5). The grammar hint rides
            // along only here, where a name-shape refusal is the likely cause.
            if (templateType.isTransform) {
                // 7d: a transform's refusals name their block and 7b's code — the suite ran,
                // and "which case, which pane" is the whole answer. The grammar hint only when
                // the NAME is what failed.
                val nameFailed = e.result.failures.any { it.code == PipelineErrorCodes.Template.ID_INVALID }
                refused(
                    e.result.failures.joinToString(" ") { TransformFace.refusalOf(it).line } +
                        if (nameFailed) " " + TemplateNameGrammar.DESCRIPTION else "",
                )
            } else {
                refused(e.result.failures.joinToString(" ") { it.message } + " " + TemplateNameGrammar.DESCRIPTION)
            }
        } catch (e: DatapipelinesException) {
            refused(e.message ?: "The template was rejected.")
        }
    }

    /**
     * The refusal the modal renders inline — never an error page for an expected 4xx, and
     * never a toast: form-level feedback belongs in the form (the 022 review F9 rule the
     * datasource register modal established).
     */
    private fun refused(why: String): ModelAndView =
        ModelAndView("partials/inline-refusal", mapOf("message" to why), HttpStatus.BAD_REQUEST)

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
}
