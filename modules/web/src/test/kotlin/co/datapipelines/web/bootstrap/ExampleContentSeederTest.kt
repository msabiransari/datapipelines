package co.datapipelines.web.bootstrap

import co.datapipelines.web.pipelines.PipelineImportService
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

/**
 * The D9 seeder (sample-data design §6.1): what it hands the import services, when it refuses at
 * startup, and what it does when there is nothing configured.
 *
 * The import services are mocked here — the *content* of an import is their own suites' subject;
 * what this suite pins is that the seeder reuses them at all, with the right bodies, in the right
 * order, and that a failure travels.
 */
class ExampleContentSeederTest {
    @TempDir
    lateinit var tempDir: Path

    private val pipelines = mockk<PipelineImportService>(relaxed = true)
    private val templates = mockk<TemplateImportService>(relaxed = true)
    private val mapper = ObjectMapper()

    private val workspaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun file(json: String): String = tempDir.resolve("examples.json").also { it.writeText(json) }.toString()

    private fun seeder(examplesFile: String?) = ExampleContentSeeder(BootstrapProperties(examplesFile = examplesFile), pipelines, templates)

    private val examples =
        """
        {
          "templates": [
            {"id": "nyc_revenue.sql", "dialect": "POSTGRES", "display_name": "Revenue", "description": "d", "body": "SELECT 1"}
          ],
          "pipelines": [
            {"schema_version": 1, "name": "revenue_by_borough", "display_name": "Revenue by borough", "nodes": []},
            {"schema_version": 1, "name": "rainy_day_demand", "display_name": "Rainy-day demand", "nodes": []}
          ]
        }
        """.trimIndent()

    @Test
    fun `templates are imported as one import body, then each pipeline, into the new workspace`() {
        val templateBodies = mutableListOf<String>()
        val pipelineBodies = mutableListOf<String>()
        every { templates.import(capture(templateBodies), workspaceId, userId) } returns emptyList()
        every { pipelines.import(capture(pipelineBodies), workspaceId, userId) } returns mockk()

        seeder(file(examples)).seed(workspaceId, userId)

        // One templates call carrying the §8.8 `{"templates":[...]}` envelope...
        templateBodies shouldHaveSize 1
        mapper.readTree(templateBodies.single()).get("templates").size() shouldBe 1
        mapper
            .readTree(templateBodies.single())
            .get("templates")[0]
            .get("id")
            .asText() shouldBe "nyc_revenue.sql"

        // ...and one §5.8 call per pipeline, each body the bare pipeline JSON.
        pipelineBodies shouldHaveSize 2
        pipelineBodies.map { mapper.readTree(it).get("name").asText() } shouldBe listOf("revenue_by_borough", "rainy_day_demand")

        // Templates first: a pipeline node references a template version, and §12 validation
        // resolves it at save time — the reverse order would fail on a valid fixture.
        verify(ordering = io.mockk.Ordering.ORDERED) {
            templates.import(any(), workspaceId, userId)
            pipelines.import(any(), workspaceId, userId)
        }
    }

    @Test
    fun `no examples file configured is a no-op, not a failure`() {
        seeder(null).seed(workspaceId, userId)
        seeder("   ").seed(workspaceId, userId)

        verify(exactly = 0) { templates.import(any(), any(), any()) }
        verify(exactly = 0) { pipelines.import(any(), any(), any()) }
    }

    @Test
    fun `either array alone is enough`() {
        every { templates.import(any(), any(), any()) } returns emptyList()
        seeder(file("""{"templates":[{"id":"a.sql","dialect":"H2","display_name":"A","description":"d","body":"SELECT 1"}]}""")).seed(
            workspaceId,
            userId,
        )
        verify(exactly = 1) { templates.import(any(), workspaceId, userId) }
        verify(exactly = 0) { pipelines.import(any(), any(), any()) }
    }

    @Test
    fun `a broken examples file is refused while the context is still building, not at first login`() {
        // Every one of these is thrown by the CONSTRUCTOR — i.e. at bean creation — so a mounted
        // file with a typo fails startup rather than the first user's login.
        val missing = shouldThrow<ExampleContentFileException> { seeder(tempDir.resolve("absent.json").toString()) }
        missing.message!!.shouldContain("could not be read")

        shouldThrow<ExampleContentFileException> { seeder(file("{oops")) }.message!!.shouldContain("not valid JSON")
        shouldThrow<ExampleContentFileException> { seeder(file("[]")) }.message!!.shouldContain("must be a JSON object")
        shouldThrow<ExampleContentFileException> { seeder(file("""{"templates":{}}""")) }.message!!.shouldContain("must be an array")
        shouldThrow<ExampleContentFileException> { seeder(file("""{"other":1}""")) }.message!!.shouldContain("declares neither")
    }

    @Test
    fun `a failing import is not swallowed - provisioning must fail loudly`() {
        every { templates.import(any(), any(), any()) } throws IllegalStateException("template.validation.body_invalid")

        val error = shouldThrow<IllegalStateException> { seeder(file(examples)).seed(workspaceId, userId) }

        error.message shouldBe "template.validation.body_invalid"
        verify(exactly = 0) { pipelines.import(any(), any(), any()) }
    }
}
