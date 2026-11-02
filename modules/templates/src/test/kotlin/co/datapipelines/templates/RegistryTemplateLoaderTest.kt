package co.datapipelines.templates

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

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
    private val library = TemplateFixtures.version("test/lib.sql", isLibrary = true, body = "<#macro m>ok</#macro>")
    private val registry = InMemoryTemplateRegistry(listOf(library))
    private val loader = RegistryTemplateLoader(registry)

    @Test
    fun `resolves an exact id@version key`() {
        loader.withDeclaredClosure("test/lib.sql@1") {
            loader.findTemplateSource("test/lib.sql@1").shouldNotBeNull()
        }
    }

    @Test
    fun `refuses every name that is not an exact id@version key`() {
        // Shape refusal happens before closure logic (#787): parseKey returns null first, so
        // these stay null with no scope in the thread at all — no wrap needed, and the
        // absence of one is part of the assertion.
        NON_KEYS.forEach { name ->
            withClue("loader must not resolve: '$name'") { loader.findTemplateSource(name).shouldBeNull() }
        }
    }

    @Test
    fun `a well-formed key with no declared closure in scope is refused`() {
        val thrown = shouldThrow<IOException> { loader.findTemplateSource("test/lib.sql@1") }

        thrown.message shouldBe UndeclaredTemplateLoadException.MESSAGE
    }

    @Test
    fun `a key outside the declared closure is refused and its name is not echoed`() {
        loader.withDeclaredClosure("test/lib.sql@1") {
            val thrown = shouldThrow<IOException> { loader.findTemplateSource("test/other.sql@1") }

            thrown.message shouldBe UndeclaredTemplateLoadException.MESSAGE
            (thrown.message ?: "") shouldNotContain "other.sql"
        }
    }

    @Test
    fun `a declared import resolves once its importer does, and the closure grows along the imports only`() {
        val main =
            TemplateFixtures.version(
                "test/main.sql",
                imports = listOf(TemplateImport("test/lib.sql", 1, "d")),
                body = "SELECT 1",
            )
        val scopedLoader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(library, main)))

        scopedLoader.withDeclaredClosure("test/main.sql@1") {
            // Before main resolves, its declared edge to the library is not open yet.
            shouldThrow<IOException> { scopedLoader.findTemplateSource("test/lib.sql@1") }
            scopedLoader.findTemplateSource("test/main.sql@1").shouldNotBeNull()
            // main's row declares test/lib.sql@1; resolving it opened that edge.
            scopedLoader.findTemplateSource("test/lib.sql@1").shouldNotBeNull()
            // A well-formed key the closure never declared refuses even with edges open.
            shouldThrow<IOException> { scopedLoader.findTemplateSource("test/other.sql@1") }
        }
    }

    @Test
    fun `a version that is not an integer never reaches the registry`() {
        // A non-numeric version is rejected by parsing, not by lookup — so a name like
        // "test/lib.sql@1 OR 1=1" cannot become a registry query at all.
        loader.findTemplateSource("test/lib.sql@1x").shouldBeNull()
        loader.findTemplateSource("test/lib.sql@-1").shouldBeNull()
    }

    @Test
    fun `synthesizes the import prologue ahead of the stored body`() {
        val main =
            TemplateFixtures.version(
                "test/main.sql",
                imports = listOf(TemplateImport("test/lib.sql", 1, "d")),
                body = "SELECT 1",
            )
        val source =
            RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(library, main)))
                .let { l -> l.withDeclaredClosure("test/main.sql@1") { l.findTemplateSource("test/main.sql@1") } }
        val text = readerText(source)

        // §4.4: the prologue emits root-based names so Freemarker's relative resolution can
        // never fire for a hierarchical importer.
        text shouldStartWith "<#import \"/test/lib.sql@1\" as d>"
        text shouldContain "SELECT 1"
    }

    @Test
    fun `fails closed rather than synthesizing an unsafe prologue`() {
        val main =
            TemplateFixtures.version(
                "test/main.sql",
                imports = listOf(TemplateImport("test/lib.sql", 1, "d>\${\"PWNED\"}<#assign z=1")),
                body = "SELECT 1",
            )
        val unsafeLoader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(library, main)))

        val thrown =
            unsafeLoader.withDeclaredClosure("test/main.sql@1") {
                shouldThrow<IOException> { unsafeLoader.findTemplateSource("test/main.sql@1") }
            }

        withClue("the refusal must not echo the attacker's alias back into logs") {
            (thrown.message ?: "") shouldNotContain "PWNED"
        }
    }

    @Test
    fun `lastModified is the version's write stamp, not a constant - a draft overwrite moves it`() {
        // Freemarker's own staleness check compares this value; a constant told any cache the
        // source could never change, which the in-place draft overwrite (117) made untrue (132).
        val written = Instant.parse("2026-09-14T14:27:06Z")
        val overwritten = Instant.parse("2026-09-14T14:34:40Z")
        val registry = InMemoryTemplateRegistry()
        val draftLoader = RegistryTemplateLoader(registry)

        registry.put(TemplateFixtures.version("test/wip.sql", body = "SELECT 1", updatedAt = written))
        val first = draftLoader.withDeclaredClosure("test/wip.sql@1") { draftLoader.getLastModified(draftLoader.findTemplateSource("test/wip.sql@1")) }
        registry.put(TemplateFixtures.version("test/wip.sql", body = "SELECT 2", updatedAt = overwritten))
        val second = draftLoader.withDeclaredClosure("test/wip.sql@1") { draftLoader.getLastModified(draftLoader.findTemplateSource("test/wip.sql@1")) }

        first shouldBe written.toEpochMilli()
        second shouldBe overwritten.toEpochMilli()
    }

    @Test
    fun `lastModified falls back to created_at on a row that was never a draft`() {
        val source = loader.withDeclaredClosure("test/lib.sql@1") { loader.findTemplateSource("test/lib.sql@1") }

        loader.getLastModified(source) shouldBe library.createdAt.toEpochMilli()
    }

    private fun readerText(source: Any?): String = loader.getReader(source, "UTF-8").readText()

    private companion object {
        // #787: "test/lib.sql@999" moved out of this list — it is a well-formed key, so since
        // the declared-closure gate it now throws instead of resolving null; its refusal is
        // asserted in `a key outside the declared closure is refused and its name is not echoed`.
        val NON_KEYS =
            listOf(
                "test/lib.sql", // unversioned
                "@1", // no id
                "test/lib.sql@", // no version
                "/etc/passwd",
                "../lib.sql@1",
                "file:///etc/passwd",
                "classpath:/lib.sql@1",
                "test/lib.sql@1@2",
                "",
            )
    }
}
