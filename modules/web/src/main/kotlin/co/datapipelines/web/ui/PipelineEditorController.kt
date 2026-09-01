package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.currentPrincipal
import co.datapipelines.web.pipelines.PipelineResponses
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.util.UUID

@Controller
class PipelineEditorController(
    private val pipelines: PipelineRepository,
    private val themeResolver: ThemeResolver,
) {
    // NOT a constructor parameter: Spring injects the app's servlet ObjectMapper into an
    // ObjectMapper-typed parameter even when it has a default, and that mapper lacks the
    // contract modules (see PipelineNodeSqlPartialController, 032). Guarded by
    // ObjectMapperDefaultParameterKonsistTest.
    private val mapper: ObjectMapper = PipelineJson.objectMapper()

    @GetMapping("/pipelines/{id}/editor")
    fun editor(
        @PathVariable id: UUID,
        model: Model,
        request: HttpServletRequest,
    ): String {
        val workspaceId = currentPrincipal().requireWorkspace().id
        val record =
            pipelines.findById(workspaceId, id)
                ?: throw NoSuchElementException("Pipeline $id not found")
        // versioning §3.5/§7: the editor shows the DRAFT when one exists (that is the
        // working copy a human reviews), with its pending-release affordance; the list
        // keeps showing the released name until lock. The default body of the REST GET
        // stays the released version — this is the editor's load, not the API's.
        val draft = pipelines.findDraftDetail(workspaceId, record.id)
        val shownVersion = draft?.version ?: record.currentVersion
        val body =
            pipelines.findVersionBody(workspaceId, record.id, shownVersion)
                ?: throw NoSuchElementException("Pipeline $id version $shownVersion body not found")
        val versionDetail = draft ?: pipelines.findCurrentVersionDetail(workspaceId, record.id)
        val fullTree = PipelineResponses.full(record, body, versionDetail, draft)
        val pipelineJson = mapper.writeValueAsString(fullTree)

        model.addAttribute("pipelineJson", pipelineJson)
        model.addAttribute("pipelineId", id)
        model.addAttribute("hasDraft", draft != null)
        model.addAttribute("draftVersion", draft?.version)
        model.addAttribute("draftHash", draft?.bodyHash)
        model.addAttribute("releasedVersion", record.currentVersion)
        model.addAttribute(
            "lifecycleJson",
            mapper.writeValueAsString(
                buildMap<String, Any?> {
                    put("hasDraft", draft != null)
                    put("draftVersion", draft?.version)
                    put("draftHash", draft?.bodyHash)
                    put("releasedVersion", record.currentVersion)
                },
            ),
        )
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "pipelines/editor"
    }
}
