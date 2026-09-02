package co.datapipelines.templates

import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * [TemplateDryRendererImpl] — the pipeline-contract §12.6 contract, implemented here.
 *
 * The two things the interface's KDoc makes load-bearing: the `template_not_found` /
 * `template_version_not_found` split, and that `dryRender` **never throws** — a broken template
 * is a returned outcome, not an escaped exception (§17.2).
 */
class TemplateDryRendererImplTest {
    private val registry =
        InMemoryTemplateRegistry(
            listOf(TemplateFixtures.version("fetch.sql", version = 1, dialect = Dialect.MYSQL, body = "SELECT \${id}")),
        )
    private val engine = TemplateEngine(registry, 10, 5_000, 1_000_000)
    private val workspaceId = java.util.UUID.randomUUID()
    private val engines =
        mockk<WorkspaceTemplateEngines> {
            every { registryFor(any()) } returns registry
            every { engineFor(any()) } returns engine
        }
    private val dryRenderer = TemplateDryRendererImpl(engines)

    @AfterEach
    fun tearDown() = engine.close()

    @Test
    fun `lookup returns Found with the template's dialect`() {
        dryRenderer.lookup(workspaceId, TemplateRef("fetch.sql", 1)) shouldBe TemplateLookup.Found(Dialect.MYSQL)
    }

    @Test
    fun `lookup distinguishes a missing version from a missing id`() {
        dryRenderer.lookup(workspaceId, TemplateRef("fetch.sql", 2)) shouldBe TemplateLookup.VersionNotFound
        dryRenderer.lookup(workspaceId, TemplateRef("absent.sql", 1)) shouldBe TemplateLookup.TemplateNotFound
    }

    @Test
    fun `dryRender succeeds when every referenced variable is supplied`() {
        dryRenderer.dryRender(workspaceId, TemplateRef("fetch.sql", 1), mapOf("id" to 7)) shouldBe DryRenderOutcome.Success
    }

    @Test
    fun `dryRender reports an undeclared variable as its own outcome`() {
        dryRenderer
            .dryRender(workspaceId, TemplateRef("fetch.sql", 1), emptyMap())
            .shouldBeInstanceOf<DryRenderOutcome.UndeclaredVariable>()
    }

    @Test
    fun `dryRender maps any other failure to RenderFailed without throwing`() {
        registry.put(TemplateFixtures.version("api.sql", body = "\${x?api}"))
        dryRenderer
            .dryRender(workspaceId, TemplateRef("api.sql", 1), mapOf("x" to "s"))
            .shouldBeInstanceOf<DryRenderOutcome.RenderFailed>()
    }

    @Test
    fun `interpolatedParameters reports a declared name the body interpolates`() {
        registry.put(TemplateFixtures.version("bind.sql", body = "SELECT \${customer_id} WHERE id = :customer_id"))

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("bind.sql", 1), setOf("customer_id")) shouldBe
            listOf("customer_id")
    }

    @Test
    fun `interpolatedParameters is empty when the body interpolates nothing declared`() {
        dryRenderer
            .interpolatedParameters(workspaceId, TemplateRef("fetch.sql", 1), setOf("customer_id"))
            .shouldBe(emptyList())
    }

    @Test
    fun `interpolatedParameters is empty when the reference resolves to no stored version`() {
        dryRenderer
            .interpolatedParameters(workspaceId, TemplateRef("absent.sql", 1), setOf("customer_id"))
            .shouldBe(emptyList())
    }

    @Test
    fun `dryRender does not throw even for a template the registry cannot resolve`() {
        dryRenderer
            .dryRender(workspaceId, TemplateRef("does_not_exist.sql", 9), emptyMap())
            .shouldBeInstanceOf<DryRenderOutcome.RenderFailed>()
    }
}
