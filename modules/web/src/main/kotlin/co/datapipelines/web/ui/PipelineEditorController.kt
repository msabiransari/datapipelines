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
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
) {
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
        val body =
            pipelines.findVersionBody(workspaceId, record.id, record.currentVersion)
                ?: throw NoSuchElementException("Pipeline $id version ${record.currentVersion} body not found")
        val fullTree = PipelineResponses.full(record, body)
        val pipelineJson = mapper.writeValueAsString(fullTree)

        model.addAttribute("pipelineJson", pipelineJson)
        model.addAttribute("activeTheme", themeResolver.resolve(request))
        return "pipelines/editor"
    }
}
