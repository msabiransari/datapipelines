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
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

@Controller
class TemplateEditorController(
    private val templates: TemplateRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val drafts: TemplateDraftService,
    /** 178 — the promoter lens on the source-column read (the writes are an author's). */
    private val reads: TemplateService,
    private val lens: PromoterLens,
) {
    /** The source column's one rule, shared with the transform face's routes (7d). */
    private val source = TemplateSourceModel(reads)

    /**
     * #398 — the compatibility redirect: `GET /templates/editor?name=&version=&tab=` is a 302
     * onto the canonical workspace `/templates/{name}`, preserving an explicit valid version
     * and a supported tab (the pipelines twin's rule, `PipelineEditorController`). Validated,
     * not forwarded blind: the same parse the canonical page runs, and only a tab of the
     * closed set survives. A malformed version is the same house 400 the canonical route
     * answers; the name is the query's (§9.6 — it may contain `/`), re-encoded into the one
     * place a path may carry it, the workspace's capture-everything variable.
     *
     * The route stays under `template.read` (its Surfaces cell names it as the redirect), and
     * the page floor is unchanged: the workspace itself is a read surface.
     */
    @GetMapping("/templates/editor")
    @RequiredScope(Permission.TEMPLATE_READ)
    fun editor(
        @RequestParam name: String,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) tab: String?,
    ): RedirectView {
        val parsedVersion = PipelineWorkspaceModel.parseRequestedVersion(version)
        val supportedTab = TemplateWorkspaceController.TemplateWorkspaceTab.entries.firstOrNull { it.wire == tab }
        val builder = canonicalBuilder(name)
        parsedVersion?.let { builder.queryParam("version", it) }
        supportedTab?.let { builder.queryParam("tab", it.wire) }
        return RedirectView(builder.build().encode().toUriString())
    }

    /**
     * The canonical workspace URL builder: the name's SEGMENTS as path segments, so
     * `demo/top_carrier.sql` builds `/templates/demo/top_carrier.sql` (a `pathSegment` per
     * grammar segment — the grammar allows no `/` inside one, so the split is lossless — and
     * each segment percent-encodes anything that is not a path character). The workspace's
     * capture-everything variable is the one place a path may carry the name.
     */
    @Suppress("SpreadOperator") // pathSegment has no List overload; the split is bounded by the grammar
    private fun canonicalBuilder(name: String): UriComponentsBuilder =
        UriComponentsBuilder
            .fromPath("/templates")
            .pathSegment(*name.split('/').toTypedArray())

    /**
     * The source column alone — what the workspace's Source tab and the version selector
     * swap (§5's idiom: a stable target, `#template-source`, one fragment for the first
     * paint and every later selection).
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
        var draftVersion: Int? = null
        // A draft already exists ⇒ it IS the edit target and this path writes NOTHING.
        if (templates.findDraftDetail(workspaceId, name) == null) {
            copyIntoDraft(workspaceId, name, version, principal.userId)?.let { return it }
        }
        draftVersion = templates.findDraftDetail(workspaceId, name)?.version
        return openWorkingVersion(name, draftVersion)
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
     * too (the pending-release badge, Release, Purge draft) — a fragment swap would leave the
     * page telling two different stories about which version is being edited. The landing is
     * the CANONICAL workspace (#398) with the draft's own version made explicit: the
     * workspace's default view is the current release, so a version-less redirect would land
     * the author on the read-only release they were merely reading instead of the draft they
     * just opened. The Source tab is where the draft's editable body lives.
     */
    private fun openWorkingVersion(
        name: String,
        draftVersion: Int?,
    ): ResponseEntity<String> {
        val builder = canonicalBuilder(name)
        draftVersion?.let { builder.queryParam("version", it) }
        builder.queryParam("tab", TemplateWorkspaceController.TemplateWorkspaceTab.SOURCE.wire)
        return ResponseEntity
            .ok()
            .header("HX-Redirect", builder.build().toUriString())
            .body("")
    }

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
            } catch (_: JsonProcessingException) {
                // #448 — the parser's own message quotes the token it could not read; the
                // refusal is fixed syntax guidance instead (#381's convention).
                return renderError("Invalid context JSON: expected valid JSON syntax.")
            } catch (_: IllegalArgumentException) {
                // #448 — the real mapper's `convertValue` wrapper: a valid JSON value that is
                // not an object. Its coercion text quotes the value; fixed prose instead.
                return renderError("Invalid context JSON: expected a JSON object.")
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
