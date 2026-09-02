package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.UUID

/**
 * The §4.1 naming grammar (template-hierarchy-design.md), exercised through **all three** of
 * its call sites — the premise of §4.6 is that the grammar is re-checked at save, at render
 * and at prologue synthesis, so a test that covers only the validator proves nothing about the
 * two render-time doors.
 *
 * The negative list pins every §4.2 rule plus the two §4.6 narrowings (a leading `_` and a
 * 65-char segment were legal under the pre-043 flat rule and are rejected now).
 */
class TemplateNameGrammarTest {
    private val workspaceId: UUID = UUID.randomUUID()

    @Test
    fun `the grammar accepts legal paths`() {
        LEGAL.forEach { name ->
            withClue("must accept: '$name'") { isValidTemplateName(name) shouldBe true }
        }
    }

    @Test
    fun `the grammar rejects every illegal shape`() {
        ILLEGAL.forEach { name ->
            withClue("must reject: '$name'") { isValidTemplateName(name) shouldBe false }
        }
    }

    // ---- Call site 1: TemplateValidator (save) ----

    @Test
    fun `save - every illegal name is id_invalid, every legal one is not`() {
        val validator = TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })
        LEGAL.forEach { name ->
            withClue("save must accept: '$name'") {
                validator
                    .validate(TemplateFixtures.draft(id = name), workspaceId)
                    .codes
                    .contains(PipelineErrorCodes.Template.ID_INVALID) shouldBe false
            }
        }
        ILLEGAL.forEach { name ->
            withClue("save must reject: '$name'") {
                validator.validate(TemplateFixtures.draft(id = name), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.ID_INVALID
            }
        }
    }

    // ---- Call site 2: RegistryTemplateLoader.parseKey (render) ----

    @Test
    fun `render - a hierarchical key resolves, with or without the root slash`() {
        val report = TemplateFixtures.version("acme/finance/report", body = "SELECT 1")
        val loader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(report)))

        // The engine asks for the bare key at the top level; the §4.4 prologue asks for the
        // root-based form for every import. Both must resolve the same stored version.
        loader.findTemplateSource("acme/finance/report@1").shouldNotBeNull()
        loader.findTemplateSource("/acme/finance/report@1").shouldNotBeNull()
    }

    @Test
    fun `render - every illegal name fails closed at the loader`() {
        val report = TemplateFixtures.version("acme/finance/report", body = "SELECT 1")
        val loader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(report)))

        ILLEGAL.forEach { name ->
            withClue("loader must not resolve: '$name@1'") { loader.findTemplateSource("$name@1").shouldBeNull() }
        }
        // Exactly one leading slash is stripped; a doubled one is NOT peeled into legality.
        loader.findTemplateSource("//acme/finance/report@1").shouldBeNull()
    }

    // ---- Call site 3: TemplateImport.isSafeToSynthesize (prologue synthesis) ----

    @Test
    fun `prologue - a legal hierarchical import synthesizes a root-based name`() {
        val main =
            TemplateFixtures.version(
                "acme/finance/report",
                imports = listOf(TemplateImport("lib/dates", 1, "d")),
                body = "SELECT 1",
            )
        val lib = TemplateFixtures.version("lib/dates", isLibrary = true, body = "<#macro m>ok</#macro>")
        val loader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(main, lib)))

        val text = loader.getReader(loader.findTemplateSource("acme/finance/report@1"), "UTF-8").readText()
        text shouldContain "<#import \"/lib/dates@1\" as d>"
    }

    @Test
    fun `prologue - an illegal import id is refused, never synthesized`() {
        ILLEGAL.forEach { name ->
            val main =
                TemplateFixtures.version(
                    "main.sql",
                    imports = listOf(TemplateImport(name, 1, "d")),
                    body = "SELECT 1",
                )
            val loader = RegistryTemplateLoader(InMemoryTemplateRegistry(listOf(main)))
            withClue("prologue must refuse import id: '$name'") {
                shouldThrow<IOException> { loader.findTemplateSource("main.sql@1") }
            }
        }
    }

    // ---- §4.4 gate, pinned against the resolved Freemarker 2.3.34 ----

    @Test
    fun `a root-based import name reaches the loader without relative resolution`() {
        // Measured against the pinned Freemarker 2.3.34 (legacy template-name format):
        // `<#import "/lib/dates@1">` inside `acme/finance/report@1` is normalized by
        // toAbsoluteName to `lib/dates@1` — the leading slash is consumed BY FREEMARKER, and
        // crucially the name is NOT resolved against the importer's directory (which would
        // have produced `acme/finance/lib/dates@1`). RegistryTemplateLoader.parseKey stripping
        // one leading `/` covers the verbatim-arrival case other name formats produce; both
        // forms resolve the same registry key.
        val requested = mutableListOf<String>()
        val recording =
            object : freemarker.cache.TemplateLoader {
                override fun findTemplateSource(name: String): Any? {
                    requested += name
                    return when (name) {
                        "acme/finance/report@1" -> "<#import \"/lib/dates@1\" as d><@d.m/>"
                        "lib/dates@1" -> "<#macro m>OK</#macro>"
                        else -> null
                    }
                }

                override fun getLastModified(templateSource: Any?): Long = 0L

                override fun getReader(
                    templateSource: Any?,
                    encoding: String?,
                ): java.io.Reader = StringReader(templateSource as String)

                override fun closeTemplateSource(templateSource: Any?) = Unit
            }
        val configuration =
            freemarker.template.Configuration(FreemarkerConfigFactory.PINNED_VERSION).apply {
                templateLoader = recording
                localizedLookup = false
            }

        val out = StringWriter()
        configuration.getTemplate("acme/finance/report@1").process(emptyMap<String, Any?>(), out)

        out.toString() shouldBe "OK"
        requested shouldContain "lib/dates@1"
        requested.filter { it.startsWith("acme/finance/lib") } shouldBe emptyList()
    }

    @Test
    fun `a hierarchical importer resolves its import at the tree root, never relative to itself`() {
        // THE §4.4 test: `acme/finance/report` importing `lib/dates` must resolve `lib/dates` —
        // if relative resolution fired, the loader would be asked for `acme/finance/lib/dates`
        // and the render would fail with template-not-found.
        val lib = TemplateFixtures.version("lib/dates", isLibrary = true, body = "<#macro m>FROM orders</#macro>")
        val report =
            TemplateFixtures.version(
                "acme/finance/report",
                imports = listOf(TemplateImport("lib/dates", 1, "d")),
                body = "SELECT * <@d.m/>",
            )
        val engine =
            TemplateEngine(
                InMemoryTemplateRegistry(listOf(lib, report)),
                cacheSize = 16,
                renderTimeoutMs = 5_000,
                maxOutputChars = 10_000,
            )

        engine.use {
            it.render(TemplateRef("acme/finance/report", 1), emptyMap()) shouldBe "SELECT * FROM orders"
        }
    }

    private companion object {
        val LEGAL =
            listOf(
                "fetch_orders.sql", // today's flat name shape — valid at the tree root (§4.2)
                "acme/finance/monthly_revenue",
                "a/b/c/d/e/f/g/h/i/j", // exactly 10 segments
                "a".repeat(64), // a maximal segment
                "x/" + "y".repeat(64),
                (listOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d4")).joinToString("/"), // 196 chars, 4 segments
            )

        val ILLEGAL =
            listOf(
                "../lib", // traversal segment
                "acme/../report", // traversal mid-path
                "acme/./report", // dot segment
                "acme//report", // empty segment
                "/acme/report", // leading slash
                "acme/report/", // trailing slash
                "acme\\report", // backslash is not a separator
                "acme/report@2", // '@' is the loader-key separator
                "Acme/report", // uppercase
                "acme/Report",
                "a/b/c/d/e/f/g/h/i/j/k", // 11 segments
                "a".repeat(65), // a 65-char segment — legal under the pre-043 flat rule (§4.6)
                "x/" + "y".repeat(65),
                "a".repeat(201), // over the 200-char total cap
                (listOf("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(9))).joinToString("/"), // 201 chars
                "_helper", // leading underscore — legal under the pre-043 flat rule (§4.6)
                "-legacy", // leading dash
                ".tmp", // leading dot
                "", // empty
            )
    }
}
