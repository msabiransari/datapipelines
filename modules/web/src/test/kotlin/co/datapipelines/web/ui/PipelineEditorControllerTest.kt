package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.ui.ExtendedModelMap
import java.time.Instant
import java.util.UUID

class PipelineEditorControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = PipelineEditorController(repository, themeResolver)

    private val pipelineId = UUID.randomUUID()
    private val record =
        PipelineRecord(
            id = pipelineId,
            name = "sample_pipeline",
            displayName = "Sample Pipeline",
            description = "A sample pipeline for testing",
            ownerId = UUID.randomUUID(),
            currentVersion = 1,
            isDeleted = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
        )

    private val bodyJson =
        """
        {
          "schema_version": 1,
          "name": "sample_pipeline",
          "display_name": "Sample Pipeline",
          "description": "A sample pipeline for testing",
          "settings": {"tempdb": {"engine": "H2"}},
          "parameters": {},
          "nodes": [
            {
              "id": "extract_users",
              "type": "DQL",
              "source": "prod_db",
              "template": {"id": "select_all", "version": 1},
              "depends_on": []
            },
            {
              "id": "transform_users",
              "type": "DQL",
              "source": "tempdb",
              "template": {"id": "transform", "version": 1},
              "depends_on": ["extract_users"]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `editor returns editor view with pipeline json and theme`() {
        every { repository.findById(pipelineId) } returns record
        every { repository.findVersionBody(pipelineId, 1) } returns bodyJson
        every { themeResolver.resolve(any()) } returns "saas"

        val model = ExtendedModelMap()
        val request = mockk<HttpServletRequest>()
        val viewName = controller.editor(pipelineId, model, request)

        viewName shouldBe "pipelines/editor"
        model["activeTheme"] shouldBe "saas"
        val json = model["pipelineJson"] as String
        json shouldContain "sample_pipeline"
        json shouldContain "extract_users"
        json shouldContain "transform_users"
        json shouldContain pipelineId.toString()
    }

    @Test
    fun `editor includes server-assigned fields in pipeline json`() {
        every { repository.findById(pipelineId) } returns record
        every { repository.findVersionBody(pipelineId, 1) } returns bodyJson
        every { themeResolver.resolve(any()) } returns "dark"

        val model = ExtendedModelMap()
        val request = mockk<HttpServletRequest>()
        controller.editor(pipelineId, model, request)

        val json = model["pipelineJson"] as String
        json shouldContain "\"id\""
        json shouldContain "\"version\""
        json shouldContain "\"owner\""
        json shouldContain "\"created_at\""
        json shouldContain "\"updated_at\""
    }
}
