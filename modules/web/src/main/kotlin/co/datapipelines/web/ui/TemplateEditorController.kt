package co.datapipelines.web.ui

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.core.JsonProcessingException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class TemplateEditorController(
    private val templates: TemplateRepository,
    private val templateEngines: WorkspaceTemplateEngines,
    private val themeResolver: ThemeResolver,
) {
    @GetMapping("/templates/editor")
    fun editor(
        @RequestParam name: String,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        // versioning §6/§7: the editor shows the DRAFT when one exists — the working copy a
        // human reviews — with its pending-release affordance; the list keeps showing the
        // released content until lock.
        // §9.6: the name is a query parameter — it may contain `/`, which can never travel
        // in a URL path segment (the container refuses %2F below routing).
        val draft = templates.findDraftDetail(workspaceId, name)
        val template = draft?.let { templates.findVersion(workspaceId, name, it.version) } ?: templates.findLatest(workspaceId, name)
        model.addAttribute("template", template)
        model.addAttribute("versions", templates.listVersions(workspaceId, name))
        model.addAttribute("hasDraft", draft != null)
        model.addAttribute("draftVersion", draft?.version)
        model.addAttribute("draftHash", draft?.bodyHash)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "templates/editor"
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
