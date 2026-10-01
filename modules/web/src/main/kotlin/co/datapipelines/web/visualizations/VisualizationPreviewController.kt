package co.datapipelines.web.visualizations

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.PreviewCase
import co.datapipelines.visualization.TestPreview
import co.datapipelines.visualization.TestSessionLinks
import co.datapipelines.visualization.VisualizationErrorCodes
import co.datapipelines.visualization.VisualizationTestCapabilities
import co.datapipelines.web.dashboards.runtime.RuntimeViews
import co.datapipelines.web.ui.UiProperties
import co.datapipelines.web.ui.site.ScriptSafeJson
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The visualization test preview (ui-screens.md §4.x; the implementation spec's §6.3 and §11.2) — the page an agent's
 * browser opens with the preview capability, and the first SESSION-LESS, capability-authenticated page in the product.
 *
 * ## Credential
 * A `PublicPaths` entry with no `@RequiredScope`: the `?session=` capability is the ONLY credential (the spec's §11.2
 * governs over §6.3's "session-authenticated" heading — recorded in the handback). No cookie is read, no principal
 * consulted, no CSRF token materialised (the page's layout carries none), so the response sets no cookie.
 * [VisualizationTestCapabilities.preview] decides; every refusal — absent, malformed, wrong, revoked by a submit,
 * expired, another visualization's, content moved on, a starter who lost `visualization.update` — renders the SAME
 * 404 page, byte for byte: the URL is an oracle for nothing.
 *
 * ## What it serves
 * The run's EXACT version, case by case: the fixtures as saved, evaluated the way the mechanical test evaluates them
 * (no pipeline, no datasource), handed to the SAME vendored runtime the dashboards use in its fixture mode — one
 * instance per case, the composite adapter, the one Plotly bundle the server chose. The data reaches the page as one
 * JSON block through [ScriptSafeJson] (never inline script over a value); case names and titles render through
 * `th:text`. The page never echoes the capability.
 */
@Controller
class VisualizationPreviewController(
    private val capabilities: VisualizationTestCapabilities,
    private val ui: UiProperties,
) {
    @GetMapping("/visualizations/{id}/preview")
    fun preview(
        @PathVariable id: String,
        @RequestParam(value = TestSessionLinks.PREVIEW_PARAMETER, required = false) capability: String?,
        @RequestParam(value = "theme", required = false) theme: String?,
        model: Model,
        response: HttpServletResponse,
    ): String {
        model.addAttribute("activeTheme", theme?.takeIf { it in THEMES } ?: ui.theme)
        val preview =
            try {
                val visualizationId = runCatching { UUID.fromString(id) }.getOrNull() ?: throw unavailable()
                capabilities.preview(visualizationId, capability)
            } catch (e: DatapipelinesException) {
                if (e.code != VisualizationErrorCodes.TEST_SESSION_NOT_FOUND) throw e
                response.status = HttpServletResponse.SC_NOT_FOUND
                model.addAttribute("available", false)
                return VIEW
            }
        val visualization = preview.visualization
        model.addAttribute("available", true)
        model.addAttribute("displayName", visualization.record.displayName)
        model.addAttribute("visualizationName", visualization.record.name)
        model.addAttribute("version", visualization.detail.version)
        model.addAttribute("expiresAt", preview.expiresAt.toString())
        model.addAttribute("cases", preview.cases.map { PreviewCaseView(it.name, it.assertions.map(::assertionLabel)) })
        model.addAttribute("bundle", RuntimeViews.rendererBundle(listOf(visualization)))
        model.addAttribute("previewJson", ScriptSafeJson.forScriptBlock(ArtifactJson.mapper.writeValueAsString(PreviewViews.page(preview))))
        return VIEW
    }

    /** What the template lists per case: its name and its assertions as readable labels (text, never markup). */
    data class PreviewCaseView(
        val name: String,
        val assertions: List<String>,
    )

    private fun assertionLabel(assertion: co.datapipelines.visualization.Assertion): String =
        listOfNotNull(assertion.kind.wire, assertion.equals?.let { "= $it" }, assertion.text?.let { "\"$it\"" }).joinToString(" ")

    private fun unavailable() = DatapipelinesException(VisualizationErrorCodes.TEST_SESSION_NOT_FOUND, "No such test session.", emptyMap())

    private companion object {
        const val VIEW = "visualizations/preview"

        /** The two themes a preview may be asked for; anything else is the deployment default (`datapipelines.ui.theme`). */
        val THEMES = setOf("light", "dark")
    }
}

/**
 * The preview page's JSON block — per case, a runtime configuration in the §8.1 shape with ONE occurrence and the
 * results a live refresh would deliver for it, so the runtime's fixture mode judges, mounts and renders the case
 * exactly as a board renders a visualization. Bindings are projected the way the refresh engine projects them
 * (`bindings.mapValues { column -> rows.map { it[column] } }`); an empty result is the empty state; an evaluator
 * refusal is the occurrence's error with the evaluator's own code.
 */
internal object PreviewViews {
    private val nodes = JsonNodeFactory.instance

    /** The one occurrence's name inside each case's configuration. */
    const val OCCURRENCE = "preview"

    private const val GRID_HEIGHT = 4
    private const val REFRESH_SECONDS = 60
    private const val PARAMETER_LOCK_SECONDS = 30
    private const val RENDER_SECONDS = 20

    fun page(preview: TestPreview): ObjectNode =
        nodes.objectNode().also { out ->
            val visualization = preview.visualization
            out.putObject("visualization").also {
                it.put("id", visualization.record.id.toString())
                it.put("name", visualization.record.name)
                it.put("version", visualization.detail.version)
            }
            out.put("expires_at", preview.expiresAt.toString())
            val cases = out.putArray("cases")
            preview.cases.forEachIndexed { index, case -> cases.add(case(preview, case, index)) }
        }

    private fun case(
        preview: TestPreview,
        case: PreviewCase,
        index: Int,
    ): ObjectNode =
        nodes.objectNode().also { out ->
            val mapper = ArtifactJson.mapper
            out.put("name", case.name)
            out.set<JsonNode>("inputs", mapper.valueToTree(case.fixtures))
            out.set<JsonNode>("assertions", mapper.valueToTree(case.assertions))
            out.set<JsonNode>("config", config(preview, index))
            out.putObject("results").set<JsonNode>(OCCURRENCE, result(preview, case))
        }

    private fun result(
        preview: TestPreview,
        case: PreviewCase,
    ): JsonNode {
        val refusal = case.refusal
        val rows = case.rows
        return when {
            refusal != null -> {
                nodes.objectNode().also {
                    it.putObject("error").put("code", refusal.code).put("message", refusal.message)
                }
            }

            rows.isNullOrEmpty() -> {
                nodes.objectNode().put("rows", 0)
            }

            else -> {
                val bindings =
                    preview.visualization.body.bindings
                        .mapValues { (_, column) -> rows.map { it[column] } }
                nodes.objectNode().also {
                    it.set<JsonNode>("bindings", ArtifactJson.mapper.valueToTree(bindings))
                    it.put("rows", rows.size)
                }
            }
        }
    }

    /** The §8.1 configuration of a one-occurrence board — the visualization exactly as the run pinned it. */
    private fun config(
        preview: TestPreview,
        index: Int,
    ): ObjectNode {
        val visualization = preview.visualization
        val body = visualization.body
        val mapper = ArtifactJson.mapper
        return nodes.objectNode().also { out ->
            out.put("configuration_id", "preview:${preview.runId}:$index")
            out.putObject("renderer").put("bundle", RuntimeViews.rendererBundle(listOf(visualization)))
            out.putObject("dashboard").also {
                it.put("id", visualization.record.id.toString())
                it.put("name", visualization.record.name)
                it.put("version", visualization.detail.version)
                it.put("status", visualization.detail.status.name)
            }
            out.putObject("layout").also { layout ->
                layout.put("columns", GRID_COLUMNS)
                layout
                    .putArray("grid")
                    .addObject()
                    .put("name", OCCURRENCE)
                    .put("x", 0)
                    .put("y", 0)
                    .put("w", GRID_COLUMNS)
                    .put("h", GRID_HEIGHT)
            }
            out.set<JsonNode>("parameter_set", nodes.nullNode())
            out.putArray("visualizations").addObject().also { v ->
                v.put("name", OCCURRENCE)
                v.putObject("artifact").also {
                    it.put("id", visualization.record.id.toString())
                    it.put("name", visualization.record.name)
                    it.put("version", visualization.detail.version)
                }
                v.putObject("renderer").put("kind", body.renderer.kind.wire).put("version", body.renderer.version)
                v.set<JsonNode>("config", body.config)
                v.set<JsonNode>("bindings", mapper.valueToTree(body.bindings))
                v.set<JsonNode>("presentation", body.presentation?.let { mapper.valueToTree(it) } ?: nodes.nullNode())
                v.put("timeout_seconds", REFRESH_SECONDS)
            }
            out.putArray("groups")
            out
                .putArray("actions")
                .addObject()
                .put("name", OCCURRENCE)
                .put("type", "refresh")
                .put("scope", "all")
                .put("initial", true)
                .putArray("targets")
            out.putArray("action_controls")
            out.putObject("parameter_scopes")
            out.set<JsonNode>("parameter_state", nodes.nullNode())
            out.putObject("timeouts").also {
                it.put("refresh_seconds", REFRESH_SECONDS)
                it.put("parameter_lock_seconds", PARAMETER_LOCK_SECONDS)
                it.put("render_seconds", RENDER_SECONDS)
            }
            out.putObject("budgets").put("max_bytes_per_source", 0).put("max_bytes_per_refresh", 0)
        }
    }

    private const val GRID_COLUMNS = co.datapipelines.visualization.DashboardLayout.GRID_COLUMNS
}
