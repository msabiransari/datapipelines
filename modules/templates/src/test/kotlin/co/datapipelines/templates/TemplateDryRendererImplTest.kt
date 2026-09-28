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
            listOf(TemplateFixtures.version("test/fetch.sql", version = 1, dialect = Dialect.MYSQL, body = "SELECT \${id}")),
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
        dryRenderer.lookup(workspaceId, TemplateRef("test/fetch.sql", 1)) shouldBe TemplateLookup.Found(Dialect.MYSQL)
    }

    @Test
    fun `lookup distinguishes a missing version from a missing id`() {
        dryRenderer.lookup(workspaceId, TemplateRef("test/fetch.sql", 2)) shouldBe TemplateLookup.VersionNotFound
        dryRenderer.lookup(workspaceId, TemplateRef("test/absent.sql", 1)) shouldBe TemplateLookup.TemplateNotFound
    }

    @Test
    fun `dryRender succeeds when every referenced variable is supplied`() {
        dryRenderer.dryRender(workspaceId, TemplateRef("test/fetch.sql", 1), mapOf("id" to 7)) shouldBe DryRenderOutcome.Success
    }

    @Test
    fun `dryRender reports an undeclared variable as its own outcome`() {
        dryRenderer
            .dryRender(workspaceId, TemplateRef("test/fetch.sql", 1), emptyMap())
            .shouldBeInstanceOf<DryRenderOutcome.UndeclaredVariable>()
    }

    @Test
    fun `dryRender maps any other failure to RenderFailed without throwing`() {
        registry.put(TemplateFixtures.version("test/api.sql", body = "\${x?api}"))
        dryRenderer
            .dryRender(workspaceId, TemplateRef("test/api.sql", 1), mapOf("x" to "s"))
            .shouldBeInstanceOf<DryRenderOutcome.RenderFailed>()
    }

    @Test
    fun `interpolatedParameters reports a declared name the body interpolates`() {
        registry.put(TemplateFixtures.version("test/bind.sql", body = "SELECT \${customer_id} WHERE id = :customer_id"))

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/bind.sql", 1), setOf("customer_id")) shouldBe
            listOf("customer_id")
    }

    @Test
    fun `interpolatedParameters reports a declared name reached through a FreeMarker special variable`() {
        // The 194c security pass: `${.vars.x}` and `${.data_model.x}` resolve to the data model exactly
        // as `${x}` does, so a caller's VALUE would land in SQL text past the guard. The `.`-prefixed
        // rule below must not exempt the special variables.
        registry.put(TemplateFixtures.version("test/vars.sql", body = "SELECT 1 WHERE a = '\${.vars.customer_id}'"))
        registry.put(TemplateFixtures.version("test/model.sql", body = "SELECT 1 WHERE a = '\${.data_model.customer_id}'"))

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/vars.sql", 1), setOf("customer_id")) shouldBe
            listOf("customer_id")
        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/model.sql", 1), setOf("customer_id")) shouldBe
            listOf("customer_id")
    }

    @Test
    fun `interpolatedParameters ignores a member of another value that merely shares the name`() {
        // `x.customer_id` cannot resolve to the flat parameter `customer_id` — the rule the fix above keeps.
        registry.put(TemplateFixtures.version("test/member.sql", body = "SELECT 1 WHERE a = '\${x.customer_id}'"))

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/member.sql", 1), setOf("customer_id")) shouldBe emptyList()
    }

    @Test
    fun `interpolatedParameters reports a value assigned from a declared parameter - the direct indirection (#285)`() {
        // RED FIRST: `<#assign x = region>${x}` writes the caller's value into the SQL text
        // through `x`, and the scan reported nothing.
        registry.put(TemplateFixtures.version("test/indirect.sql", body = "SELECT 1 WHERE a = '<#assign x = region>\${x}'"))

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/indirect.sql", 1), setOf("region")) shouldBe
            listOf("region")
    }

    @Test
    fun `interpolatedParameters reports the transitive indirection through two assignments (#285)`() {
        registry.put(
            TemplateFixtures.version("test/chain.sql", body = "SELECT '<#assign x = region><#assign y = x>\${y}'"),
        )

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/chain.sql", 1), setOf("region")) shouldBe
            listOf("region")
    }

    @Test
    fun `interpolatedParameters stays clean when an assignment carries a literal`() {
        // The taint follows REFERENCES, not assignments as such: `1` carries nothing of the caller.
        registry.put(TemplateFixtures.version("test/literal.sql", body = "SELECT 1 WHERE a = '<#assign x = 1>\${x}'"))

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/literal.sql", 1), setOf("customer_id")) shouldBe
            emptyList()
    }

    @Test
    fun `interpolatedParameters stays clean when a macro parameter shadows a tainted name`() {
        // Shadowing clears as it does for direct references: inside `m`, `region` is the LOCAL.
        registry.put(
            TemplateFixtures.version(
                "test/shadowed.sql",
                body = "SELECT '<#assign t = region><#macro m region>\${region}\${t}</#macro>'",
            ),
        )

        dryRenderer.interpolatedParameters(workspaceId, TemplateRef("test/shadowed.sql", 1), setOf("region")) shouldBe
            listOf("region") // only ${t} — read AFTER the macro definition, outside the shadow — is the parameter's path
    }

    @Test
    fun `interpolatedParameters is empty when the body interpolates nothing declared`() {
        dryRenderer
            .interpolatedParameters(workspaceId, TemplateRef("test/fetch.sql", 1), setOf("customer_id"))
            .shouldBe(emptyList())
    }

    @Test
    fun `interpolatedParameters is empty when the reference resolves to no stored version`() {
        dryRenderer
            .interpolatedParameters(workspaceId, TemplateRef("test/absent.sql", 1), setOf("customer_id"))
            .shouldBe(emptyList())
    }

    @Test
    fun `dryRender does not throw even for a template the registry cannot resolve`() {
        dryRenderer
            .dryRender(workspaceId, TemplateRef("test/does_not_exist.sql", 9), emptyMap())
            .shouldBeInstanceOf<DryRenderOutcome.RenderFailed>()
    }
}
