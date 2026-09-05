package co.datapipelines.web.bootstrap

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.web.TestRepoFiles
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.pipelines.PipelineImportService
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
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

    /** A file mixing a path name and a legacy flat one — the transparency fixture. */
    private val pathShapedExamples =
        """
        {
          "templates": [
            {"id": "nyc/mobility/revenue.sql", "dialect": "POSTGRES", "display_name": "Revenue", "description": "d", "body": "SELECT 1"}
          ],
          "pipelines": [
            {"schema_version": 1, "name": "nyc/mobility/revenue_by_borough", "display_name": "Revenue by borough", "nodes": []},
            {"schema_version": 1, "name": "legacy_flat_name", "display_name": "Legacy", "nodes": []}
          ]
        }
        """.trimIndent()

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

    /**
     * 067 §E — the SHIPPED demo content, read from the repo, not from a fixture.
     *
     * The two `content/examples.json` files are the files the artifact build COPIES, so this
     * is the build input that says "a fresh workspace seeds pipelines under folder roots".
     * It asserts the names the artifact will carry, the grammar the server will enforce on
     * them, and the one PIPELINE child reference that had to move with its child.
     */
    @Test
    fun `the shipped example files name every pipeline under a folder root`() {
        val families =
            mapOf(
                "scripts/sample-data/content/examples.json" to "nyc",
                "scripts/sample-data-trade/content/examples.json" to "trade",
            )

        val allNames =
            families.flatMap { (path, root) ->
                val names = mapper.readTree(TestRepoFiles.read(path)).get("pipelines").map { it.get("name").asText() }
                // Non-vacuity: an empty file would satisfy every assertion below by having
                // nothing to check.
                names.shouldNotBeEmpty()
                names.forEach { name ->
                    withClue(name) {
                        PipelineNameGrammar.matches(name) shouldBe true
                        name.substringBefore('/') shouldBe root
                        // A folder, not a flat name that happens to start with the root.
                        name.contains('/') shouldBe true
                    }
                }
                names
            }

        allNames shouldContainExactlyInAnyOrder
            listOf(
                "nyc/mobility/revenue_by_borough",
                "nyc/mobility/rainy_vs_dry_ridership",
                "nyc/mobility/borough_od_matrix",
                "nyc/mobility/airport_access_by_borough",
                "nyc/mobility/weather_sensitivity_by_borough",
                "nyc/mobility/mobility_briefing",
                "trade/balance_by_partner",
                "trade/reconciliation",
                "trade/fx/imports_in_partner_currency",
            )
    }

    @Test
    fun `the PIPELINE child reference moved with its child`() {
        // The composition the demo ships: `mobility_briefing` invokes `borough_od_matrix`.
        // A rename that moved the child and not the reference would seed a workspace whose
        // briefing pipeline fails §12.9 `pipeline_not_found` at save — i.e. a login that 500s.
        val doc = mapper.readTree(TestRepoFiles.read("scripts/sample-data/content/examples.json"))
        val briefing = doc.get("pipelines").single { it.get("name").asText() == "nyc/mobility/mobility_briefing" }

        val refs = briefing.get("nodes").mapNotNull { it.get("pipeline")?.get("name")?.asText() }

        refs shouldBe listOf("nyc/mobility/borough_od_matrix")
    }

    @Test
    fun `seeding hands the import service path-shaped names verbatim - it neither rewrites nor namespaces them`() {
        // The other half of §E: an ALREADY-SEEDED workspace, holding the old flat names, is
        // left alone. Two facts make that true and neither is a guess — (1) provisioning runs
        // the seeder exactly once per new workspace and never again
        // (`PersonalWorkspaceSeedingTest.seeding does not re-run for a workspace a previous
        // login already provisioned`), and (2) the seeder is TRANSPARENT to names: it forwards
        // whatever the file says, so it has no code path that could rename anything. This
        // pins (2).
        val pipelineBodies = mutableListOf<String>()
        every { templates.import(any(), workspaceId, userId) } returns emptyList()
        every { pipelines.import(capture(pipelineBodies), workspaceId, userId) } returns mockk()

        seeder(file(pathShapedExamples)).seed(workspaceId, userId)

        pipelineBodies.map { mapper.readTree(it).get("name").asText() } shouldBe
            listOf("nyc/mobility/revenue_by_borough", "legacy_flat_name")
    }

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

    // ---------------------------------------------------------------- F7: declared-but-empty

    @Test
    fun `declared-but-empty arrays are valid input, not a malformed file`() {
        // Both arrays are optional (class KDoc), so an explicitly empty one is a deployment
        // saying "seed nothing of this kind" — never the "declares neither" refusal, which
        // exists for a file that mentions neither array at all.
        seeder(file("""{"templates":[],"pipelines":[]}""")).seed(workspaceId, userId)

        verify(exactly = 0) { templates.import(any(), any(), any()) }
        verify(exactly = 0) { pipelines.import(any(), any(), any()) }
    }

    @Test
    fun `an empty array beside a populated one seeds the populated one`() {
        every { pipelines.import(any(), any(), any()) } returns mockk()

        seeder(
            file("""{"templates":[],"pipelines":[{"schema_version":1,"name":"only_one","display_name":"One","nodes":[]}]}"""),
        ).seed(workspaceId, userId)

        verify(exactly = 0) { templates.import(any(), any(), any()) }
        verify(exactly = 1) { pipelines.import(any(), workspaceId, userId) }
    }

    // ---------------------------------------------------------------- A: the failure is legible

    @Test
    fun `a failing fixture is logged structured with its id and error code before the failure travels`() {
        // The shape T63 reported: seeding fails, the login 500s, and nothing in the logs says
        // which fixture or why. The refusal stands; only its legibility is under test.
        every { templates.import(any(), any(), any()) } returns emptyList()
        every { pipelines.import(any(), any(), any()) } throws
            ApiException(
                PipelineErrorCodes.Import.MISSING_DATASOURCE,
                "Imported pipeline 'revenue_by_borough' has unmet dependencies in this environment.",
                mapOf("missing_datasources" to listOf("nyc-open-data")),
            )

        val lines = capturingLogs { shouldThrow<ApiException> { seeder(file(examples)).seed(workspaceId, userId) } }

        val failure = lines.single { it.contains("event=workspace.examples_seed_failed") }
        failure.shouldContain("workspace_id=$workspaceId")
        failure.shouldContain("user_id=$userId")
        failure.shouldContain("fixture_kind=pipeline")
        failure.shouldContain("fixture=revenue_by_borough")
        failure.shouldContain("error_code=${PipelineErrorCodes.Import.MISSING_DATASOURCE}")
        // The success line must NOT be emitted for a seeding that failed.
        lines.none { it.contains("event=workspace.examples_seeded") } shouldBe true
    }

    @Test
    fun `a failing template import names the templates envelope it was importing`() {
        every { templates.import(any(), any(), any()) } throws
            ApiException(PipelineErrorCodes.Template.SYNTAX_ERROR, "broken", emptyMap())

        val lines = capturingLogs { shouldThrow<ApiException> { seeder(file(examples)).seed(workspaceId, userId) } }

        val failure = lines.single { it.contains("event=workspace.examples_seed_failed") }
        failure.shouldContain("fixture_kind=templates")
        failure.shouldContain("fixture=nyc_revenue.sql")
        failure.shouldContain("error_code=${PipelineErrorCodes.Template.SYNTAX_ERROR}")
    }

    // ---------------------------------------------------------------- F10: family list semantics

    @Test
    fun `two families seed ALL templates before ANY pipeline, in file order`() {
        val fileA =
            tempDir
                .resolve("examples-nyc.json")
                .also {
                    it.writeText(
                        """{"templates":[{"id":"nyc_t.sql","dialect":"H2","display_name":"N","description":"d","body":"SELECT 1"}]""" +
                            ""","pipelines":[{"schema_version":1,"name":"nyc_p","display_name":"N","nodes":[]}]}""",
                    )
                }.toString()
        val fileB =
            tempDir
                .resolve("examples-trade.json")
                .also {
                    it.writeText(
                        """{"templates":[{"id":"trade_t.sql","dialect":"DUCKDB","display_name":"T",""" +
                            """"description":"d","body":"SELECT 2"}],""" +
                            """"pipelines":[{"schema_version":1,"name":"trade_p","display_name":"T","nodes":[]}]}""",
                    )
                }.toString()

        val templateBodies = mutableListOf<String>()
        val pipelineBodies = mutableListOf<String>()
        every { templates.import(capture(templateBodies), workspaceId, userId) } returns emptyList()
        every { pipelines.import(capture(pipelineBodies), workspaceId, userId) } returns mockk()

        seeder("$fileA, $fileB").seed(workspaceId, userId)

        templateBodies shouldHaveSize 2
        mapper
            .readTree(templateBodies[0])
            .get("templates")[0]
            .get("id")
            .asText() shouldBe "nyc_t.sql"
        mapper
            .readTree(templateBodies[1])
            .get("templates")[0]
            .get("id")
            .asText() shouldBe "trade_t.sql"

        pipelineBodies shouldHaveSize 2
        pipelineBodies.map { mapper.readTree(it).get("name").asText() } shouldBe listOf("nyc_p", "trade_p")

        // Both template imports precede BOTH pipeline imports.
        verify(ordering = io.mockk.Ordering.ORDERED) {
            templates.import(any(), workspaceId, userId)
            templates.import(any(), workspaceId, userId)
            pipelines.import(any(), workspaceId, userId)
            pipelines.import(any(), workspaceId, userId)
        }
    }

    @Test
    fun `empty entries in the family list are dropped`() {
        every { templates.import(any(), any(), any()) } returns emptyList()

        seeder(", ${file(examples)} ,").seed(workspaceId, userId)

        verify(exactly = 1) { templates.import(any(), workspaceId, userId) }
        verify(exactly = 2) { pipelines.import(any(), workspaceId, userId) }
    }

    private fun capturingLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(ExampleContentSeeder::class.java) as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
