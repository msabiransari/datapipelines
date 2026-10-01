package co.datapipelines.web.ui

import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.PipelineResponses
import co.datapipelines.web.ui.site.ScriptSafeJson
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The canonical pipeline workspace READ page (workspace spec §3.1, #348): `GET
 * /pipelines/{id}?version=N&tab=…`. One URL names one pipeline, one viewed version and one
 * tab; the version selector and every entry link land here, and the old
 * `/pipelines/{id}/editor` route is a compatibility redirect into it
 * ([PipelineEditorController]).
 *
 * The floor is [Permission.PIPELINE_READ] — the page is a read surface for every admitted
 * reader, the promoter through its lens; Execute and the lifecycle verbs stay their own
 * permission-gated calls, role-hidden in the markup (RoleVisibilityRenderTest). The tab set is
 * closed; #349 composes the remaining five tabs onto this route, and until then every tab
 * resolves to the Flow-shaped page this slice keeps — with the tab state and the model fields
 * the composition needs already carried here.
 *
 * The two JSON blobs go out through [ScriptSafeJson.forScriptBlock] exactly as the editor's
 * did (185): the free text a pipeline carries has no charset rule, and a `</script>` inside a
 * value must not close the block it rides in.
 */
@Controller
class PipelineWorkspaceController(
    private val workspace: PipelineWorkspaceModel,
    private val themeResolver: ThemeResolver,
    /** 178 — the promoter lens: the page resolves and renders the caller's view. */
    private val lens: PromoterLens,
) {
    // NOT a constructor parameter: Spring injects the app's servlet ObjectMapper into an
    // ObjectMapper-typed parameter even when it has a default, and that mapper lacks the
    // contract modules (see PipelineEditorController, 032). Guarded by
    // ObjectMapperDefaultParameterKonsistTest.
    private val mapper: ObjectMapper = PipelineJson.objectMapper()

    @GetMapping("/pipelines/{id}")
    @RequiredScope(Permission.PIPELINE_READ)
    fun workspace(
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) tab: String?,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val principal = currentPrincipal()
        val workspaceId = principal.requireWorkspace().id
        val view = lens.viewFor(principal)
        val requested = PipelineWorkspaceModel.parseRequestedVersion(version)
        // One roles read: the tab admission and the Execute affordance answer from the same
        // stamp the template renders by (the server decides, the markup renders its answer).
        val roles = RoleModel.roles(principal)
        val resolved = workspace.resolve(workspaceId, view, id, requested)

        model.addAttribute("activeTheme", themeResolver.resolve(request))
        RoleModel.stamp(model)
        model.addAttribute("pipelineId", id)
        model.addAttribute("pipelineName", resolved.record.displayName)
        stampVersionState(model, resolved, roles)
        model.addAttribute("versions", resolved.versions)
        model.addAttribute("canReadExecutions", roles.canReadExecutions)
        model.addAttribute("activeTab", PipelineWorkspaceTab.fromWire(tab, roles.canReadExecutions).wire)

        // A body selected: the graph's data block. The version field names the VIEWED row —
        // `PipelineResponses.full` falls back to the current pointer when the detail row is
        // absent, which would silently misname a narrow-read version (spec: fields describing
        // the viewed version come from that same row/body).
        if (resolved.hasSelectedBody) {
            val tree = PipelineResponses.full(resolved.record, resolved.selected.bodyJson!!, resolved.selected.detail, resolved.draft)
            resolved.viewedVersion?.let { (tree as ObjectNode).put("version", it) }
            model.addAttribute("pipelineJson", ScriptSafeJson.forScriptBlock(mapper.writeValueAsString(tree)))
        }
        // The workspace state the client pins reads and runs from — ONE source for the
        // displayed and submitted version (spec §3.4). A malformed block is a refusal, not a
        // default: workspace.js records PEWorkspaceInvalid and the execute path stops with a
        // visible error and zero requests.
        model.addAttribute(
            "workspaceJson",
            ScriptSafeJson.forScriptBlock(
                mapper.writeValueAsString(
                    buildMap<String, Any?> {
                        put("pipelineId", id.toString())
                        put("viewedVersion", resolved.viewedVersion)
                        put("hasBody", resolved.hasSelectedBody)
                        put("canExecute", roles.canExecute && resolved.hasSelectedBody)
                    },
                ),
            ),
        )
        return VIEW
    }

    /** The version-state attributes the header, the phone band and the selector read. */
    private fun stampVersionState(
        model: Model,
        resolved: PipelineWorkspaceModel.Resolved,
        roles: RoleModel.Roles,
    ) {
        model.addAttribute("hasSelectedBody", resolved.hasSelectedBody)
        model.addAttribute("viewedVersion", resolved.viewedVersion)
        model.addAttribute("viewedStatusLabel", resolved.viewedStatus?.name?.lowercase())
        model.addAttribute("viewedIsDraft", resolved.viewedIsDraft)
        model.addAttribute("viewedIsCurrent", resolved.viewedIsCurrent)
        model.addAttribute("currentVersion", resolved.currentVisible)
        // The one string the header chip and the phone band print: vN · status · current,
        // every clause only when the caller's view carries it.
        model.addAttribute(
            "viewedLabel",
            resolved.viewedVersion?.let { v ->
                buildString {
                    append('v').append(v)
                    resolved.viewedStatus?.let { append(" · ").append(it.name.lowercase()) }
                    if (resolved.viewedIsCurrent) append(" · current")
                }
            } ?: NO_VERSION_SELECTED,
        )
        // The draft affordances (Release / Purge draft) keep the editor's model names and
        // their own role gates; the draft pointer is lens-filtered by the service.
        model.addAttribute("hasDraft", resolved.draft != null)
        model.addAttribute("draftVersion", resolved.draft?.version)
        // Execute renders only for a caller who may run AND a body that was selected — the
        // choose-a-version state offers nothing to run (spec §3.1: no Execute until a body).
        model.addAttribute("canExecute", roles.canExecute && resolved.hasSelectedBody)
    }

    /** The tab set the page's query contract admits (workspace spec §3.1). */
    enum class PipelineWorkspaceTab(
        val wire: String,
    ) {
        FLOW("flow"),
        OVERVIEW("overview"),
        PARAMETERS("parameters"),
        RUNS("runs"),
        USAGE("usage"),
        VERSIONS("versions"),
        ;

        companion object {
            /**
             * Unknown or missing resolves to Flow; requesting Runs without the execution-read
             * permission ALSO resolves to Flow — before any runs read is attempted (the page
             * never fetches what the role cannot read).
             */
            fun fromWire(
                raw: String?,
                canReadExecutions: Boolean,
            ): PipelineWorkspaceTab =
                entries
                    .firstOrNull { it.wire == raw }
                    ?.takeIf { it != RUNS || canReadExecutions }
                    ?: FLOW
        }
    }

    private companion object {
        /** Compatibility anchor: the template file name the render tests and 349 consume. */
        const val VIEW = "pipelines/editor"

        /** The choose-a-version state's chip word — no version, no invented metadata. */
        const val NO_VERSION_SELECTED = "no version selected"
    }
}
