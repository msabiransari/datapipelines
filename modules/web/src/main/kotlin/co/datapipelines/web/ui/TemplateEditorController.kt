package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateService
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.core.JsonProcessingException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.ModelAndView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Controller
class TemplateEditorController(
    private val templates: TemplateRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val themeResolver: ThemeResolver,
    private val drafts: TemplateDraftService,
    /** 178 — the promoter lens on the two READ_RESOURCES reads (the page, the source partial); the writes are an author's. */
    private val reads: TemplateService,
    private val lens: PromoterLens,
) {
    /** The source column's one rule, shared with the transform face's routes (7d). */
    private val source = TemplateSourceModel(reads)

    @GetMapping("/templates/editor")
    // 143 (T315): the page floors at READ, the pipeline editor's 122 rule — the operation
    // the screen exists to perform for its LOWEST role is reading the source. What it
    // renders is read state (a draft body is read, never written, by a GET); every write
    // it can make — Edit, Preview, the lifecycle dialogs — is its own verb-guarded route,
    // and the markup hides those verbs by role (§4.3e). 096 §C's "authoring state" floor
    // stays on the writes below, where it belongs.
    @RequiredScope(Permission.TEMPLATE_READ)
    fun editor(
        @RequestParam name: String,
        @RequestParam(required = false) version: Int?,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        // §9.6: the name is a query parameter — it may contain `/`, which can never travel
        // in a URL path segment (the container refuses %2F below routing).
        val view = lens.viewFor(principal).templates
        val filled = source.fill(model, workspaceId, view, name, version, RoleModel.roles(principal).canAuthor)
        val draft = filled.draft
        model.addAttribute("versions", reads.listVersions(workspaceId, view, name))
        model.addAttribute("hasDraft", draft != null)
        model.addAttribute("draftVersion", draft?.version)
        model.addAttribute("draftHash", draft?.bodyHash)
        // 7e (transform-nodes design §8.2): the displayed version's `needs_review` marker —
        // computed on read by the projection's own query (a cited fact is retired).
        model.addAttribute("needsReview", filled.displayed?.needsReview == true)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model, principal)
        return "templates/editor"
    }

    /**
     * The source column alone — what the version `<select>` swaps (§5's idiom: the page
     * renders the shell AND the initial fragment; every later selection hits this endpoint
     * and swaps `#template-source` only).
     */
    @GetMapping("/partials/templates/editor/source")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun source(
        @RequestParam name: String,
        @RequestParam(required = false) version: Int?,
        model: Model,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val filled = source.fill(model, workspaceId, lens.viewFor(principal).templates, name, version, RoleModel.roles(principal).canAuthor)
        RoleModel.stamp(model, principal)
        // 7d: a transform template's column is the face — the same answer the face's own GET gives.
        return filled.view
    }

    /**
     * **Edit** on a version the editor is showing read-only (R5).
     *
     * The lifecycle rule 035/039 shipped is APPLIED here, never re-implemented: a draft
     * already exists ⇒ that draft IS the edit target and **no write is issued** — the UI does
     * not ask for a second one (`uq_template_versions_one_draft` refuses it anyway, and a
     * write would silently overwrite the author's in-progress draft with the selected
     * version's body). Otherwise the selected version is copied into a new draft through the
     * SAME [TemplateDraftService] the REST `PUT /api/v1/templates` uses — one component, two
     * surfaces — and the answer the server gives is followed, not second-guessed: a
     * byte-identical copy is that service's documented no-op, and the redirect below then
     * lands back on the working version, which is the honest outcome.
     *
     * The precondition is the CURRENT RELEASE's hash, because that is the row
     * `createDraft`'s guard reads — not the hash of the version being copied.
     */
    @PostMapping("/partials/templates/editor/edit")
    @RequiredScope(Permission.TEMPLATE_UPDATE)
    @ResponseBody
    fun edit(
        @RequestParam name: String,
        @RequestParam version: Int,
    ): Any {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        // A draft already exists ⇒ it IS the edit target and this path writes NOTHING.
        if (templates.findDraftDetail(workspaceId, name) == null) {
            copyIntoDraft(workspaceId, name, version, principal.userId)?.let { return it }
        }
        return openWorkingVersion(name)
    }

    /** Copies [version] into a new draft; returns the refusal to render, or null on success. */
    private fun copyIntoDraft(
        workspaceId: UUID,
        name: String,
        version: Int,
        actor: UUID,
    ): ModelAndView? {
        val selected =
            templates.findVersion(workspaceId, name, version)
                ?: return refusal("Version v$version of '$name' was not found.")
        val current =
            templates.findLatest(workspaceId, name)
                ?: return refusal("Template '$name' was not found.")
        val base =
            templates.findVersionDetail(workspaceId, name, current.version)
                ?: return refusal("Template '$name' has no current release to base a draft on.")
        return try {
            drafts.write(workspaceId, name, selected.asDraft(), base.bodyHash, actor, WriteSurface.SESSION)
            null
        } catch (e: DatapipelinesException) {
            refusal(e.message ?: "The draft could not be opened.")
        }
    }

    /** A stored version, verbatim, as the inbound draft shape the write path takes. */
    private fun Template.asDraft(): TemplateDraft =
        TemplateDraft(
            schemaVersion = schemaVersion,
            id = id,
            engine = engine,
            type = type,
            dialect = dialect,
            displayName = displayName,
            description = description,
            imports = imports,
            body = body,
            isLibrary = isLibrary,
            // 7d: a transform version's three blocks are content (inside `body_hash`); dropping
            // them made Edit on a released jsonata version a `contract_invalid` blocks_missing.
            contract = contract,
            invariants = invariants,
            tests = tests,
        )

    /**
     * Success: htmx navigates the whole page, because opening the draft changes the header
     * too (the pending-release badge, Release, Discard) — a fragment swap would leave the
     * page telling two different stories about which version is being edited.
     */
    private fun openWorkingVersion(name: String): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .header("HX-Redirect", "/templates/editor?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8))
            .body("")

    /**
     * A refusal is a 200 carrying the reason, not a 4xx: htmx does not swap 4xx bodies
     * (`responseHandling` defaults), so a 4xx here would drop the message on the floor.
     * Form-level feedback belongs in the form (the 022 review F9 rule).
     */
    private fun refusal(why: String): ModelAndView {
        log.info(EDIT_TAG, CorrelationId.current(), why)
        return ModelAndView("partials/inline-refusal", mapOf("message" to why))
    }

    @PostMapping("/partials/templates/render")
    @RequiredScope(Permission.TEMPLATE_RENDER)
    fun renderPreview(
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam("body") @Suppress("UNUSED_PARAMETER") body: String,
        @RequestParam("context") contextJson: String,
    ): ModelAndView {
        val workspaceId = currentPrincipal().requireWorkspace().id
        if (templates.lookupVersion(workspaceId, name, version) == null && !templates.existsId(workspaceId, name)) {
            return renderError("Template '$name' not found.")
        }
        val context =
            try {
                MAPPER.readTree(contextJson)
                @Suppress("UNCHECKED_CAST")
                MAPPER.convertValue(MAPPER.readTree(contextJson), Map::class.java)
                    as? Map<String, Any?> ?: emptyMap()
            } catch (e: JsonProcessingException) {
                return renderError("Invalid context JSON: ${e.message}")
            } catch (e: IllegalArgumentException) {
                return renderError("Invalid context JSON: ${e.message}")
            }
        return try {
            val rendered = templateEngines.engineFor(workspaceId).render(TemplateRef(name, version), context)
            // A blank render is a RESULT, not a failure: the fragment says "(empty output)".
            renderOutput(if (rendered.isBlank()) "" else rendered)
        } catch (e: co.datapipelines.templates.TemplateRenderException) {
            renderError("Render failed: ${e.message}")
        }
    }

    /** The preview pane's three states are one fragment; see `partials/template-render.html`. */
    private fun renderOutput(rendered: String): ModelAndView =
        ModelAndView("partials/template-render", mapOf("renderOutput" to rendered, "renderError" to null))

    private fun renderError(message: String): ModelAndView {
        log.info(TAG, CorrelationId.current(), message)
        return ModelAndView("partials/template-render", mapOf("renderOutput" to "", "renderError" to message))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(TemplateEditorController::class.java)
        private const val TAG = "Template preview failed — correlationId={}, detail={}"
        private const val EDIT_TAG = "Template edit refused — correlationId={}, detail={}"
        private val MAPPER = TemplateJson.objectMapper()
    }
}
