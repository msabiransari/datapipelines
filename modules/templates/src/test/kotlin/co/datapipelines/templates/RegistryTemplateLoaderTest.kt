package co.datapipelines.templates

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * [RegistryTemplateLoader] is the containment boundary of templates.md §4.3: the **only** way a
 * body reaches the engine, and the only place a template name is interpreted.
 *
 * Everything here is a security assertion. The loader is what makes "there is no template name a
 * body could reference to escape the registry" true; if it ever resolved a name that is not an
 * exact `{id}@{version}` registry key, §4.2's whole argument for allowing `?eval`-free bodies to
 * be rendered at all would collapse.
 */
class RegistryTemplateLoaderTest {
    private val library = TemplateFixtures.version("lib.sql", isLibrary = true, body = "<#macro m>ok</#macro>")
    private val registry = InMemoryTemplateRegistry(listOf(library))
    private val loader = RegistryTemplateLoader(registry)

    @Test
    fun `resolves an exact id@version key`() {
        loader.findTemplateSource("lib.sql@1").shouldNotBeNull()
    }

    @Test
    fun `refuses every name that is not an exact id@version key`() {
        NON_KEYS.forEach { name ->
            withClue("loader must not resolve: '$name'") { loader.findTemplateSource(name).shouldBeNull() }
        }
    }

    @Test
    fun `a version that is not an integer never reaches the registry`() {
        // A non-numeric version is rejected by parsing, not by lookup — so a name like
        // "lib.sql@1 OR 1=1" cannot become a registry query at all.
        loader.findTemplateSource("lib.sql@1x").shouldBeNull()
        loader.findTemplateSource("lib.sql@-1").shouldBeNull()
    }

    @Test
    fun `synthesizes the import prologue ahead of the stored body`() {
        val main =
            TemplateFixtures.version(
                "main.sql",
                imports = listOf(TemplateImport("lib.sql", 1, "d")),
                body = "SELECT 1",
            )
        val source = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(library, main))).findTemplateSource("main.sql@1")
        val text = readerText(source)

        text shouldStartWith "<#import \"lib.sql@1\" as d>"
        text shouldContain "SELECT 1"
    }

    @Test
    fun `fails closed rather than synthesizing an unsafe prologue`() {
        val main =
            TemplateFixtures.version(
                "main.sql",
                imports = listOf(TemplateImport("lib.sql", 1, "d>\${\"PWNED\"}<#assign z=1")),
                body = "SELECT 1",
            )
        val unsafeLoader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(library, main)))

        val thrown = shouldThrow<IOException> { unsafeLoader.findTemplateSource("main.sql@1") }

        withClue("the refusal must not echo the attacker's alias back into logs") {
            (thrown.message ?: "") shouldNotContain "PWNED"
        }
    }

    private fun readerText(source: Any?): String = loader.getReader(source, "UTF-8").readText()

    private companion object {
        val NON_KEYS =
            listOf(
                "lib.sql", // unversioned
                "@1", // no id
                "lib.sql@", // no version
                "/etc/passwd",
                "../lib.sql@1",
                "file:///etc/passwd",
                "classpath:/lib.sql@1",
                "lib.sql@1@2",
                "lib.sql@999",
                "",
            )
    }
}
