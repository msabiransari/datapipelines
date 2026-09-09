package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.datasources.CredentialKind
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.datasources.DatasourcePoolForm
import co.datapipelines.web.datasources.DatasourceWorkspaceRules
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The datasources screen's htmx partials (ui-screens.md §4.5/§5): the workspace-scoped
 * list fragment, the connection-test TOAST fragment, and the REGISTER action of the §4.5
 * modal — which applies the SAME [DatasourceWorkspaceRules] the REST §9.1 endpoint applies,
 * then crosses the SAME `registry.save` boundary (validation, encryption, pool eviction).
 * Two write paths, one rule set — that is the point of the extracted component.
 */
@Controller
class DatasourcePartialController(
    private val browse: DatasourceBrowseModel,
    private val datasources: DatasourceRegistry,
    private val rules: DatasourceWorkspaceRules,
    /**
     * The SAME any-version reverse scan the REST delete's `409 datasource.in_use` reports
     * (061/T79). The delete dialog asks it FIRST and renders its rows, so "where is this used"
     * is answered before anything is at stake — and by the one authority, not a second query
     * that could disagree with the refusal the user would hit a moment later.
     */
    private val references: DatasourceReferences,
) {
    @GetMapping("/partials/datasources")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun list(
        model: Model,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) dialect: String?,
        @RequestParam(required = false) offset: Int?,
    ): String {
        browse.fillList(model, principal(), q, dialect, offset)
        return DatasourceBrowseModel.LIST_VIEW
    }

    /**
     * The connection probe, delivered as a TOAST (ui-screens.md §4.5/§5.1 Notifications):
     * the button appends the rendered fragment to the layout's #toast stack
     * (`hx-swap="beforeend"`), so the table is never re-rendered mid-interaction —
     * the old row-swap contract (one row out, two rows in, "Back to list" fetching
     * the whole list INTO a row) is what exploded the table layout.
     *
     * §5.3: an invisible datasource behaves as not-found — a danger toast naming the
     * probe, the same refusal the REST §9.6 probe expresses as 404.
     */
    @PostMapping("/partials/datasources/{name}/test")
    @RequiredScope(ScopeMatrix.RestOperation.TEST_DATASOURCE)
    fun test(
        model: Model,
        @PathVariable name: String,
    ): String {
        val workspaceId = principal()?.workspace?.id
        val visible = workspaceId != null && datasources.getVisible(name, workspaceId) != null
        val result = if (visible) datasources.testConnection(name) else null
        when {
            result == null -> {
                model.addAttribute("variant", "danger")
                model.addAttribute("title", "Datasource not found")
                model.addAttribute("message", "$name is not visible in the active workspace.")
            }

            result.connected -> {
                model.addAttribute("variant", "success")
                model.addAttribute("title", "Connection succeeded")
                model.addAttribute("message", "$name — Server version: ${result.serverVersion ?: "unknown"}")
            }

            else -> {
                model.addAttribute("variant", "danger")
                model.addAttribute("title", "Connection failed")
                model.addAttribute("message", "$name — ${result.error ?: "unknown error"}")
            }
        }
        return "partials/toast"
    }

    /**
     * The register modal's action. Form-encoded fields (the §5 idiom), bound here into a
     * [Datasource] and gated by the shared rules — `global` (admin-only; the checkbox is
     * visible-disabled for members) and `readonly` ride along as booleans. The active
     * workspace is the default binding; a member with the gate off gets the refusal HTML.
     */
    @Suppress("LongParameterList") // the register form's fields, one parameter each (the §5 form-encoding idiom)
    @PostMapping("/partials/datasources")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun register(
        model: Model,
        @RequestParam name: String,
        @RequestParam dialect: String,
        @RequestParam jdbcUrl: String,
        @RequestParam(required = false, defaultValue = "password") credentialKind: String,
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) password: String?,
        @RequestParam(required = false) displayName: String?,
        @RequestParam(required = false) description: String?,
        @RequestParam(required = false, defaultValue = "false") global: Boolean,
        @RequestParam(required = false, defaultValue = "false") readonly: Boolean,
        @RequestParam params: Map<String, String>,
    ): Any {
        val principal = principal() ?: error("No authenticated principal")
        return try {
            val resolvedDialect =
                Dialect.entries.firstOrNull { it.wire.equals(dialect.trim(), ignoreCase = true) }
                    ?: return refused("Unknown dialect '$dialect'.")
            // §3.4: the form states the KIND; `none` (a file database, an IAM role) carries
            // neither field, so both inputs are optional here and the registry's validator —
            // not an HTML `required` attribute — is what enforces the per-kind rules.
            val kind =
                CredentialKind.fromWireOrNull(credentialKind.trim().lowercase())
                    ?: return refused("Unknown credential kind '$credentialKind'.")
            val workspaceId = rules.resolveCreateBinding(principal, global, null)
            val datasource =
                Datasource(
                    name = name.trim(),
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim(),
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    dialect = resolvedDialect,
                    jdbcUrl = jdbcUrl.trim(),
                    username = username?.trim()?.takeIf { it.isNotEmpty() },
                    credentialKind = kind,
                    secret = password?.takeIf { it.isNotEmpty() },
                    isReadonly = readonly,
                    workspaceId = workspaceId,
                    // 094 §A: only the pool fields the operator CHANGED. A field left at the
                    // prefilled default is not persisted, so a later change to a product default
                    // still reaches datasources created through this form.
                    properties = DatasourceProperties(hikari = DatasourcePoolForm.toHikari(params, resolvedDialect)),
                )
            if (datasources.exists(datasource.name)) {
                return refused("A datasource named '${datasource.name}' already exists.")
            }
            datasources.save(datasource, principal.userId)
            // No HX-Redirect: a full-page navigation would discard the toast. The success
            // node lands in #register-result (its arrival closes the modal, 022/F9); the
            // refreshed list and the toast ride along out-of-band (Shape A, §5.1).
            browse.fillList(model, principal(), null, null, null)
            model.addAttribute("registeredName", datasource.name)
            model.addAttribute("oob", true)
            "partials/datasource-registered"
        } catch (e: co.datapipelines.typesystem.DatapipelinesException) {
            refused(e.message ?: "The connection details were rejected.")
        }
    }

    // ------------------------------------------------------------------ §4.5 pool fields (094)

    /**
     * The create dialog's "Connection pool" fields for one dialect (datasources.md §5).
     *
     * A fetch rather than eight static inputs because the prefilled value is the EFFECTIVE
     * default for the CHOSEN dialect, and the dialect is chosen in the same form: the select
     * swaps this fragment on change. Every shipped dialect happens to resolve to the same
     * numbers today — no adapter overrides a pool setting — but the resolution is the adapter's
     * to make, and a form that hard-coded today's answer would be wrong on the first dialect
     * that disagrees, silently.
     */
    @GetMapping("/partials/datasources/pool-fields")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun poolFields(
        model: Model,
        @RequestParam(required = false) dialect: String?,
    ): String {
        val resolved =
            dialect?.trim()?.takeIf { it.isNotEmpty() }?.let { wire ->
                Dialect.entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) }
            } ?: Dialect.entries.first()
        model.addAttribute("poolFields", DatasourcePoolForm.fields(resolved))
        // The create dialog has no datasource yet, so the readonly mirror reads from the form's
        // own checkbox rather than from a row — rendered by the caller, not here.
        model.addAttribute("poolReadonly", false)
        // The template by NAME: the fragment's `idPrefix` parameter is unbound on a
        // whole-template render and defaults to the create dialog's `ds-`, which is the only
        // prefix this endpoint ever needs — the edit dialog does not re-fetch (its dialect is
        // fixed). The swap target itself is the page's own wrapper, not this root.
        return "partials/datasource-pool-fields"
    }

    // ------------------------------------------------------------------ §4.5 edit (094)

    /** The edit dialog, prefilled from the row — including its effective pool settings. */
    @GetMapping("/partials/datasources/{name}/edit")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun editForm(
        model: Model,
        @PathVariable name: String,
    ): Any {
        val datasource = visible(name) ?: return notFoundDialog(name)
        model.addAttribute("datasource", datasource)
        model.addAttribute("dialects", Dialect.entries.map { it.wire })
        model.addAttribute("credentialKinds", CredentialKind.entries.map { it.wire })
        model.addAttribute("poolFields", DatasourcePoolForm.fields(datasource))
        model.addAttribute("poolReadonly", datasource.isReadonly)
        model.addAttribute("isAdmin", principal()?.isAdmin == true)
        return "partials/datasource-edit"
    }

    /**
     * The edit dialog's action — the UI twin of `PUT /api/v1/datasources/{name}` (§9.4), through
     * the SAME [DatasourceWorkspaceRules] gates and the SAME `registry.save` boundary.
     *
     * `password` blank means KEEP the stored credential (§9.4): the form never renders one back,
     * so a blank field is "unchanged", never "clear it".
     *
     * `pool.*` fields are collected from the whole parameter map rather than declared one by one:
     * the catalog is [co.datapipelines.datasources.pooling.PoolSettings]'s, and a controller
     * signature that re-listed the eight keys would be a second place to add the ninth.
     */
    @Suppress("LongParameterList") // the edit form's fields, one parameter each (the §5 form idiom)
    @PostMapping("/partials/datasources/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun update(
        model: Model,
        @PathVariable name: String,
        @RequestParam jdbcUrl: String,
        @RequestParam(required = false, defaultValue = "password") credentialKind: String,
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) password: String?,
        @RequestParam(required = false) displayName: String?,
        @RequestParam(required = false) description: String?,
        @RequestParam(required = false, defaultValue = "false") global: Boolean,
        @RequestParam(required = false, defaultValue = "false") globalPresent: Boolean,
        @RequestParam(required = false, defaultValue = "false") readonly: Boolean,
        @RequestParam params: Map<String, String>,
    ): Any {
        val principal = principal() ?: error("No authenticated principal")
        val existing = visible(name) ?: return refused("Datasource '$name' is not visible in the active workspace.")
        return try {
            val kind =
                CredentialKind.fromWireOrNull(credentialKind.trim().lowercase())
                    ?: return refused("Unknown credential kind '$credentialKind'.")
            rules.requireGlobalMutationAllowed(principal, existing, name)
            rules.requireMemberDatasourcesGate(principal)
            // An unchecked HTML checkbox posts NOTHING, which is indistinguishable from "the
            // field was not on the form at all" — so an admin could never un-global a datasource
            // through a bare checkbox. `globalPresent` is the companion hidden field the admin
            // form renders (and a member form does not): present ⇒ the checkbox's value is a
            // deliberate write, absent ⇒ keep the stored binding. A member forging it is refused
            // by the same admin-only rule REST applies.
            val globalRequested = if (globalPresent) global else null
            rules.requireGlobalFlagWriteAllowed(principal, globalRequested)
            // The dialect is immutable on this surface: changing it would repoint a live
            // datasource at a different driver under the same name, and every pipeline that
            // references it by name would silently follow. REST does not allow it either.
            val updated =
                existing.copy(
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: name,
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    jdbcUrl = jdbcUrl.trim(),
                    username = username?.trim()?.takeIf { it.isNotEmpty() },
                    credentialKind = kind,
                    secret = password?.takeIf { it.isNotEmpty() },
                    isReadonly = readonly,
                    workspaceId = rules.resolveUpdateBinding(principal, existing, globalRequested, null),
                    properties =
                        existing.properties.copy(
                            hikari = DatasourcePoolForm.toHikari(params, existing.dialect, existing.properties.hikari),
                        ),
                )
            datasources.save(updated, principal.userId)
            browse.fillList(model, principal(), null, null, null)
            model.addAttribute("savedName", name)
            model.addAttribute("savedVerb", "updated")
            model.addAttribute("oob", true)
            "partials/datasource-saved"
        } catch (e: co.datapipelines.typesystem.DatapipelinesException) {
            refused(e.message ?: "The connection details were rejected.")
        }
    }

    // ------------------------------------------------------------------ §4.5 delete (094 §B)

    /**
     * The delete dialog. It asks the USAGE question first and, when the answer is "yes", offers
     * nothing else — the confirm button does not exist on that branch, so the refusal cannot be
     * clicked past. The same rows the REST 409's `details` carries (061/T79), rendered.
     */
    @GetMapping("/partials/datasources/{name}/delete")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun deleteDialog(
        model: Model,
        @PathVariable name: String,
    ): Any {
        val principal = principal() ?: error("No authenticated principal")
        val datasource = visible(name) ?: return notFoundDialog(name)
        model.addAttribute("datasource", datasource)
        // D8 first: a member looking at a global datasource is told so instead of being shown a
        // confirm button whose POST would refuse. Same rule as update (admin for global).
        val forbidden =
            when {
                datasource.workspaceId == null && !principal.isAdmin -> {
                    "Deleting the global datasource '$name' requires admin."
                }

                else -> {
                    null
                }
            }
        model.addAttribute("forbidden", forbidden)
        val usages = if (forbidden == null) references.referencesTo(name) else emptyList()
        model.addAttribute("usages", usages)
        model.addAttribute("usedByPipelines", usages.map { it.pipelineName }.distinct())
        return "partials/datasource-delete"
    }

    /**
     * The confirmed delete — the UI twin of `DELETE /api/v1/datasources/{name}` (§9.5), through
     * the same D8 gates and the same `registry.delete`, whose in-use guard runs again here.
     *
     * The guard is re-run rather than trusted from the dialog on purpose: a pipeline can start
     * referencing this datasource between the dialog opening and the button being pressed, and
     * the authority for "is it still unused" is the delete itself, never the screen.
     */
    @PostMapping("/partials/datasources/{name}/delete")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_WORKSPACE_DATASOURCES)
    fun delete(
        model: Model,
        @PathVariable name: String,
    ): Any {
        val principal = principal() ?: error("No authenticated principal")
        val existing = visible(name) ?: return refused("Datasource '$name' is not visible in the active workspace.")
        return try {
            rules.requireGlobalMutationAllowed(principal, existing, name)
            rules.requireMemberDatasourcesGate(principal)
            val result = datasources.delete(name)
            if (!result.deleted) {
                return refused(
                    "'$name' is still used by ${result.referencingPipelines.size} pipeline(s) across " +
                        "${result.references.size} node(s). Remove or repoint them first.",
                )
            }
            browse.fillList(model, principal(), null, null, null)
            model.addAttribute("savedName", name)
            model.addAttribute("savedVerb", "deleted")
            model.addAttribute("oob", true)
            "partials/datasource-saved"
        } catch (e: co.datapipelines.typesystem.DatapipelinesException) {
            refused(e.message ?: "The datasource could not be deleted.")
        }
    }

    /** The active workspace's view of [name], or null — §5.3 visibility, never a post-filter. */
    private fun visible(name: String): Datasource? = principal()?.workspace?.id?.let { datasources.getVisible(name, it) }

    /** A dialog body saying the datasource is not there — a 404 inside a modal, not an error page. */
    private fun notFoundDialog(name: String): ResponseEntity<String> = refused("Datasource '$name' is not visible in the active workspace.")

    /** The refusal the modal renders inline — never an error page for an expected 4xx. */
    private fun refused(why: String): ResponseEntity<String> =
        ResponseEntity.badRequest().body(
            """<div class="ds-surface" style="border:1px solid var(--accent-danger);border-radius:var(--radius-base);""" +
                """padding:var(--gap-sm);color:var(--text-primary);font-size:var(--text-sm);max-width:480px">""" +
                why.replace("&", "&amp;").replace("<", "&lt;") +
                "</div>",
        )

    private fun principal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
}
