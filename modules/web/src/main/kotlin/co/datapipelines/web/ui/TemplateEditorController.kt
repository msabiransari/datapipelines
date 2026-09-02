package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateDraftService
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateVersionDetail
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Controller
class TemplateEditorController(
    private val templates: TemplateRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val themeResolver: ThemeResolver,
    private val drafts: TemplateDraftService,
) {
    @GetMapping("/templates/editor")
    fun editor(
        @RequestParam name: String,
        @RequestParam(required = false) version: Int?,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        // §9.6: the name is a query parameter — it may contain `/`, which can never travel
        // in a URL path segment (the container refuses %2F below routing).
        val draft = fillSource(model, workspaceId, name, version)
        model.addAttribute("versions", templates.listVersions(workspaceId, name))
        model.addAttribute("hasDraft", draft != null)
        model.addAttribute("draftVersion", draft?.version)
        model.addAttribute("draftHash", draft?.bodyHash)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "templates/editor"
    }

    /**
     * The source column alone — what the version `<select>` swaps (§5's idiom: the page
     * renders the shell AND the initial fragment; every later selection hits this endpoint
     * and swaps `#template-source` only).
     */
    @GetMapping("/partials/templates/editor/source")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun source(
        @RequestParam name: String,
        @RequestParam(required = false) version: Int?,
        model: Model,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        fillSource(model, workspaceId, name, version)
        return "partials/template-source"
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
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    @ResponseBody
    fun edit(
        @RequestParam name: String,
        @RequestParam version: Int,
    ): ResponseEntity<String> {
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
    ): ResponseEntity<String>? {
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
            drafts.write(workspaceId, name, selected.asDraft(), base.bodyHash, actor)
            null
        } catch (e: DatapipelinesException) {
            refusal(e.message ?: "The draft could not be opened.")
        }
    }

    /**
     * The source column's model, shared by the page and its partial.
     *
     * 039 O14: the default is the WORKING version — the draft when one exists, else the
     * current release. Selecting a DIFFERENT entry is what changed in R5: that version is
     * shown READ-ONLY, with its badge and, when RELEASED, who released it and when. The
     * editable textarea only ever carries the working version, so no selection can make a
     * RELEASED row the write target.
     *
     * Returns the draft detail it had to read anyway, so the page's header affordances
     * (the pending-release badge, Release, Discard) cost no second query.
     */
    private fun fillSource(
        model: Model,
        workspaceId: UUID,
        name: String,
        requested: Int?,
    ): TemplateVersionDetail? {
        val draft = templates.findDraftDetail(workspaceId, name)
        val latest = templates.findLatest(workspaceId, name)
        val workingVersion = draft?.version ?: latest?.version
        val selectedVersion = requested ?: workingVersion
        // A `version` naming no stored row (a hand-typed URL) falls back to the current
        // release rather than painting an EMPTY editable textarea, which would be a lie
        // about what the author is looking at.
        val displayed =
            when {
                selectedVersion == null -> null
                latest != null && selectedVersion == latest.version -> latest
                else -> templates.findVersion(workspaceId, name, selectedVersion)
            } ?: latest
        val readOnly = displayed != null && workingVersion != null && displayed.version != workingVersion
        // `readOnly` proves `displayed` non-null; Kotlin's data-flow carries that here.
        val detail = if (readOnly) templates.findVersionDetail(workspaceId, name, displayed.version) else null
        model.addAttribute("template", displayed)
        model.addAttribute("templateName", name)
        model.addAttribute("selectedVersion", displayed?.version ?: selectedVersion)
        model.addAttribute("workingVersion", workingVersion)
        model.addAttribute("readOnly", readOnly)
        model.addAttribute("selectedStatus", (detail?.status ?: displayed?.status)?.name)
        model.addAttribute("releasedAt", detail?.releasedAt)
        model.addAttribute("releasedBy", detail?.releasedBy?.toString())
        return draft
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
    private fun refusal(why: String): ResponseEntity<String> {
        log.info(EDIT_TAG, CorrelationId.current(), why)
        return ResponseEntity.ok(
            """<div class="ds-surface" style="border:1px solid var(--accent-danger);border-radius:var(--radius-base);""" +
                """padding:var(--gap-sm);color:var(--text-primary);font-size:var(--text-sm)">""" +
                escaped(why) +
                "</div>",
        )
    }

    @PostMapping("/partials/templates/render")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    @ResponseBody
    fun renderPreview(
        @RequestParam name: String,
        @RequestParam version: Int,
        @RequestParam("body") @Suppress("UNUSED_PARAMETER") body: String,
        @RequestParam("context") contextJson: String,
    ): String {
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
            if (rendered.isBlank()) {
                RENDER_EMPTY_HTML
            } else {
                RENDER_OUTPUT_PREFIX + escaped(rendered) + RENDER_OUTPUT_SUFFIX
            }
        } catch (e: co.datapipelines.templates.TemplateRenderException) {
            renderError("Render failed: ${e.message}")
        }
    }

    private fun renderError(message: String): String {
        log.info(TAG, CorrelationId.current(), message)
        return RENDER_ERROR_PREFIX + escaped(message) + RENDER_ERROR_SUFFIX
    }

    private fun escaped(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private companion object {
        private val log = LoggerFactory.getLogger(TemplateEditorController::class.java)
        private const val TAG = "Template preview failed — correlationId={}, detail={}"
        private const val EDIT_TAG = "Template edit refused — correlationId={}, detail={}"
        private val MAPPER = TemplateJson.objectMapper()

        const val RENDER_EMPTY_HTML =
            """<div class="ds-card" style="padding:var(--gap-md)">""" +
                """<pre style="margin:0;color:var(--text-secondary);font-style:italic">(empty output)</pre></div>"""
        const val RENDER_OUTPUT_PREFIX =
            """<div class="ds-card" style="padding:var(--gap-md)">""" +
                """<pre style="margin:0;white-space:pre-wrap;word-break:break-word;""" +
                """font-family:var(--font-mono);font-size:var(--text-sm);color:var(--text-primary)">"""
        const val RENDER_OUTPUT_SUFFIX = "</pre></div>"
        const val RENDER_ERROR_PREFIX =
            """<div class="ds-card" style="padding:var(--gap-md);border-color:var(--accent-danger);""" +
                """background:var(--accent-danger-bg)">""" +
                """<p style="margin:0;color:var(--accent-danger);font-size:var(--text-sm)">"""
        const val RENDER_ERROR_SUFFIX = "</p></div>"
    }
}
