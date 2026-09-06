package co.datapipelines.mcp

import co.datapipelines.auth.Scope
import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineResolver
import co.datapipelines.pipeline.PipelineValidationException
import co.datapipelines.pipeline.PipelineValidator
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.LibraryResolver
import co.datapipelines.templates.TemplateNameGrammar
import co.datapipelines.templates.TemplateRegistry
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.TemplateVersion
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * §4.1's folder requirement (077) **as an agent meets it**: over `pipelines_create` and
 * `templates_create`, with the REAL validators.
 *
 * The two sibling suites (`PipelineAuthoringToolsTest`, `TemplateToolsTest`) inject
 * `mockk<PipelineValidator>()` / `mockk<TemplateValidator>()`, which is right for what they
 * assert — the tool's own wiring — and useless for this: a mocked validator cannot refuse
 * anything, so a suite built on one would go green whatever the grammar said. So this file
 * constructs the production validators and lets them answer.
 *
 * What is asserted is the `details.reason` an agent reads, not the message text. An agent that
 * sent `scratch` has to learn to send `test/scratch`; an agent that sent `_helper` must NOT be
 * told that, because `test/_helper` is illegal too. That distinction is the whole reason 077
 * added a field instead of a new error code.
 */
class McpFolderRequiredTest {
    private val ctx = McpFixtures.ctx(Scope.AUTHOR)

    // A real pipeline validator over empty collaborators: nothing but the NAME rule can fire
    // for the payload below, and the name rule needs no datasource, template or child.
    private val pipelineValidator =
        PipelineValidator(
            datasources = DatasourceRegistry.EMPTY,
            templates = EmptyDryRenderer,
            pipelines = PipelineResolver { _, _, _ -> null },
            maxCompositionDepth = 5,
        )

    // A real template validator whose registry is empty — the draft below imports nothing.
    private val templateValidator = TemplateValidator(LibraryResolver { EmptyTemplateRegistry })

    @Test
    fun `pipelines_create refuses a folderless name with reason folder_required`() {
        val thrown =
            shouldThrow<PipelineValidationException> {
                PipelinesCreateTool(
                    McpFixtures.pipelineService(mockk<PipelineRepository>(), pipelineValidator, AuthoringGuard(true)),
                ).call(McpArguments(pipelineArgs("scratch")), ctx)
            }

        val failure = thrown.result.failures.single { it.code == PipelineErrorCodes.Validation.NAME_INVALID }
        failure.details["value"] shouldBe "scratch"
        failure.details["reason"] shouldBe PipelineNameGrammar.REASON_FOLDER_REQUIRED
    }

    @Test
    fun `pipelines_create says grammar, not folder_required, when a folder would not fix it`() {
        val thrown =
            shouldThrow<PipelineValidationException> {
                PipelinesCreateTool(
                    McpFixtures.pipelineService(mockk<PipelineRepository>(), pipelineValidator, AuthoringGuard(true)),
                ).call(McpArguments(pipelineArgs("_helper")), ctx)
            }

        thrown.result
            .failures
            .single { it.code == PipelineErrorCodes.Validation.NAME_INVALID }
            .details["reason"] shouldBe PipelineNameGrammar.REASON_GRAMMAR
    }

    @Test
    fun `templates_create refuses a folderless id with reason folder_required`() {
        val thrown =
            shouldThrow<TemplateValidationException> {
                TemplatesCreateTool(mockk<TemplateRepository>(), AuthoringGuard(true), templateValidator)
                    .call(McpArguments(templateArgs("scratch")), ctx)
            }

        val failure = thrown.result.failures.single { it.code == PipelineErrorCodes.Template.ID_INVALID }
        failure.details["id"] shouldBe "scratch"
        failure.details["reason"] shouldBe TemplateNameGrammar.REASON_FOLDER_REQUIRED
    }

    @Test
    fun `templates_create says grammar, not folder_required, when a folder would not fix it`() {
        val thrown =
            shouldThrow<TemplateValidationException> {
                TemplatesCreateTool(mockk<TemplateRepository>(), AuthoringGuard(true), templateValidator)
                    .call(McpArguments(templateArgs("_helper")), ctx)
            }

        thrown.result
            .failures
            .single { it.code == PipelineErrorCodes.Template.ID_INVALID }
            .details["reason"] shouldBe TemplateNameGrammar.REASON_GRAMMAR
    }

    @Test
    fun `both tools advertise the folder-bearing pattern, read from the grammar objects themselves`() {
        // The schema an agent reads BEFORE it sends anything. `templates_create` carried no
        // `pattern` at all until 077 — it advertised the pre-043 flat rule in prose (audit
        // T129) — so this asserts the value, not merely that a key exists.
        val pipelineSchema =
            PipelinesCreateTool(McpFixtures.pipelineService(mockk<PipelineRepository>(), pipelineValidator, AuthoringGuard(true)))
                .definition
                .inputSchema()
                .toString()
        val templateSchema =
            TemplatesCreateTool(mockk<TemplateRepository>(), AuthoringGuard(true), templateValidator)
                .definition
                .inputSchema()
                .toString()

        pipelineSchema.contains(PipelineNameGrammar.pattern) shouldBe true
        templateSchema.contains(TemplateNameGrammar.pattern) shouldBe true
        // …and it is the folder-bearing pattern: the repetition has a lower bound of one now.
        PipelineNameGrammar.matches("scratch") shouldBe false
        TemplateNameGrammar.matches("scratch") shouldBe false
    }

    private fun pipelineArgs(name: String): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to "Scratch",
            "description" to "A folderless pipeline.",
            "parameters" to emptyMap<String, Any?>(),
            "nodes" to
                listOf(
                    mapOf(
                        "id" to "only",
                        "type" to "DQL",
                        "source" to "tempdb",
                        "template" to mapOf("id" to "test/scratch.sql", "version" to 1),
                        "output" to mapOf("target" to "caller"),
                        "depends_on" to emptyList<String>(),
                    ),
                ),
        )

    private fun templateArgs(id: String): Map<String, Any?> =
        mapOf(
            "id" to id,
            "dialect" to "POSTGRES",
            "display_name" to "Scratch",
            "description" to "A folderless template.",
            "imports" to emptyList<Any?>(),
            "body" to "SELECT 1",
        )

    /** Nothing resolves and nothing renders — the name rule is the only one under test. */
    private object EmptyDryRenderer : TemplateDryRenderer {
        override fun lookup(
            workspaceId: UUID,
            ref: TemplateRef,
        ): TemplateLookup = TemplateLookup.Found(Dialect.H2)

        override fun dryRender(
            workspaceId: UUID,
            ref: TemplateRef,
            context: Map<String, Any?>,
        ): co.datapipelines.pipeline.DryRenderOutcome = co.datapipelines.pipeline.DryRenderOutcome.Success

        override fun interpolatedParameters(
            workspaceId: UUID,
            ref: TemplateRef,
            declared: Set<String>,
        ): List<String> = emptyList()

        override fun boundParameters(
            workspaceId: UUID,
            ref: TemplateRef,
        ): List<String> = emptyList()
    }

    private object EmptyTemplateRegistry : TemplateRegistry {
        override fun lookup(
            id: String,
            version: Int,
        ): TemplateVersion? = null

        override fun existsId(id: String): Boolean = false
    }
}
