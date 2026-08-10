package co.datapipelines.templates

import freemarker.core.TemplateClassResolver
import freemarker.template.Configuration
import freemarker.template.SimpleObjectWrapper
import freemarker.template.TemplateExceptionHandler
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * Asserts the produced [Configuration] directly — templates.md §4.3, setting by setting.
 *
 * ## Why this test has to exist
 *
 * Three of the §4.3 settings currently equal the Freemarker default (`isAPIBuiltinEnabled =
 * false`, `SimpleObjectWrapper`, `RETHROW_HANDLER`). Without an assertion on the produced object
 * they can be **deleted with every other test still green** — and the day a Freemarker default
 * changes, the deletion becomes an open door nobody notices. §4.3 says so explicitly: "set it
 * explicitly so a future default change cannot silently open it". This is what makes that
 * sentence enforceable.
 *
 * Both entry points are covered, because a body must parse under exactly the regime it renders
 * under — the premise the §4.2 AST scan depends on.
 */
class FreemarkerConfigFactoryTest {
    private val render: Configuration = FreemarkerConfigFactory.create(RegistryTemplateLoader(InMemoryTemplateRegistry()), 50)
    private val parseOnly: Configuration = FreemarkerConfigFactory.parseOnly()

    private fun bothConfigs(): List<Pair<String, Configuration>> = listOf("create()" to render, "parseOnly()" to parseOnly)

    @Test
    fun `class resolution is disabled entirely, not merely made safer`() {
        // §4.3 is explicit that SAFER_RESOLVER is "not restrictive enough" for untrusted
        // templates. ALLOWS_NOTHING is what kills ?new / ObjectConstructor / Execute / Jython.
        bothConfigs().forEach { (name, config) ->
            withClue(name) {
                config.newBuiltinClassResolver shouldBeSameInstanceAs TemplateClassResolver.ALLOWS_NOTHING_RESOLVER
            }
        }
    }

    @Test
    fun `the api builtin is off`() {
        bothConfigs().forEach { (name, config) -> withClue(name) { config.isAPIBuiltinEnabled.shouldBeFalse() } }
    }

    @Test
    fun `the object wrapper exposes no Java members`() {
        bothConfigs().forEach { (name, config) ->
            withClue(name) { config.objectWrapper.shouldBeInstanceOf<SimpleObjectWrapper>() }
        }
    }

    @Test
    fun `render failures rethrow rather than leaking partial output into the SQL`() {
        bothConfigs().forEach { (name, config) ->
            withClue(name) {
                config.templateExceptionHandler shouldBeSameInstanceAs TemplateExceptionHandler.RETHROW_HANDLER
                config.logTemplateExceptions.shouldBeFalse()
            }
        }
    }

    @Test
    fun `the tag and interpolation syntaxes are pinned`() {
        // Note what the pin does and does not buy — a leading `[#ftl]` still overrides it, which
        // is why ForbiddenConstructScanner refuses `[#` / `[=` outright (SstiMatrixTest).
        bothConfigs().forEach { (name, config) ->
            withClue(name) {
                config.tagSyntax shouldBe Configuration.ANGLE_BRACKET_TAG_SYNTAX
                config.interpolationSyntax shouldBe Configuration.LEGACY_INTERPOLATION_SYNTAX
            }
        }
    }

    @Test
    fun `the render config loads templates only from the registry`() {
        render.templateLoader.shouldBeInstanceOf<RegistryTemplateLoader>()
    }

    @Test
    fun `the parse config has no template loader at all`() {
        // `Template(name, source, cfg)` never consults one, and not setting one means a parse
        // cannot reach a file, a classpath resource, or the registry by accident.
        withClue("parseOnly() must not be able to load anything") { parseOnly.templateLoader shouldBe null }
    }

    @Test
    fun `incompatibleImprovements equals the artifact on the classpath`() {
        // §4.1/§4.3: kept equal to the pinned version. Asserted against a literal constant rather
        // than derived from `Configuration.getVersion()`, so a BOM-driven jar bump turns into a
        // red build and a deliberate decision instead of a silent change of engine semantics.
        val artifact = Configuration.getVersion()

        withClue("artifact on the classpath is $artifact, PINNED_VERSION is ${FreemarkerConfigFactory.PINNED_VERSION}") {
            listOf(artifact.major, artifact.minor, artifact.micro) shouldBe
                listOf(
                    FreemarkerConfigFactory.PINNED_VERSION.major,
                    FreemarkerConfigFactory.PINNED_VERSION.minor,
                    FreemarkerConfigFactory.PINNED_VERSION.micro,
                )
        }
        bothConfigs().forEach { (name, config) ->
            withClue(name) { config.incompatibleImprovements shouldBe FreemarkerConfigFactory.PINNED_VERSION }
        }
    }

    @Test
    fun `the render config carries the section 4-4 formatting`() {
        render.numberFormat shouldBe "@${PlainNumberFormatFactory.NAME}"
        render.booleanFormat shouldBe "c"
        render.timeZone.id shouldBe "UTC"
        render.dateTimeFormat shouldBe "iso"
    }
}
