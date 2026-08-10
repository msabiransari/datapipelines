package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import freemarker.cache.StringTemplateLoader
import freemarker.template.Configuration
import freemarker.template.Version
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.StringWriter

/**
 * The SSTI matrix of templates.md §12.3 — **two independent layers, tested independently.**
 *
 * Every advisory vector must be:
 *  1. rejected at **save** by the forbidden-construct scan ([TemplateValidator]), and
 *  2. blocked at **render** by the §4.3 configuration even if the save scan were bypassed —
 *     `?new` / `?api` / `Execute` / `ObjectConstructor` cannot reach a Java class under
 *     `ALLOWS_NOTHING_RESOLVER` + `isAPIBuiltinEnabled = false` + `SimpleObjectWrapper`.
 *
 * The render arm deliberately puts each payload straight into the registry (no validation), so
 * it proves the second layer holds on its own — which is the point of there being two.
 *
 * ## The positive control
 *
 * A matrix of payloads that all fail proves nothing unless the payloads are *live*: a typo in a
 * vector fails for the wrong reason and the suite still reads green. `a live payload succeeds
 * against an unsandboxed Freemarker` renders the class-instantiation vector through a stock
 * `Configuration` — whose defaults are `UNRESTRICTED_RESOLVER` and a Beans-backed wrapper — and
 * asserts it **succeeds** there. That is the control the rest of the matrix is measured against;
 * it uses `java.lang.String`, never `Execute`, so the control itself never runs a command.
 */
class SstiMatrixTest {
    private val validator = TemplateValidator(LibraryResolver(InMemoryTemplateRegistry()))
    private val engines = mutableListOf<TemplateEngine>()

    @AfterEach
    fun tearDown() = engines.forEach { it.close() }

    private fun engineFor(body: String): TemplateEngine {
        val registry = InMemoryTemplateRegistry(listOf(TemplateFixtures.version("evil.sql", body = body)))
        return TemplateEngine(registry, cacheSize = 10, renderTimeoutMs = 5_000, maxOutputChars = 1_000_000)
            .also { engines += it }
    }

    @Test
    fun `every advisory vector is rejected at save`() {
        SAVE_VECTORS.forEach { vector ->
            withClue("save must reject: $vector") {
                validator.validate(TemplateFixtures.draft(body = vector)).codes shouldContain
                    PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
            }
        }
    }

    @Test
    fun `a live payload succeeds against an unsandboxed Freemarker`() {
        // The control: stock Freemarker defaults (UNRESTRICTED_RESOLVER, Beans wrapper) instantiate
        // the class and render its value. Everything else in this class is only meaningful because
        // this assertion passes — it is what makes the vectors demonstrably live.
        val unsandboxed =
            Configuration(pinnedVersion()).apply {
                templateLoader = StringTemplateLoader().apply { putTemplate("v", CLASS_INSTANTIATION_VECTOR) }
            }
        val out = StringWriter()

        unsandboxed.getTemplate("v").process(emptyMap<String, Any>(), out)

        out.toString() shouldBe "PWNED"
    }

    @Test
    fun `the same live payload is blocked by the hardened configuration`() {
        val outcome = engineFor(CLASS_INSTANTIATION_VECTOR).execute(TemplateRef("evil.sql", 1), emptyMap())

        outcome.shouldBeInstanceOf<RenderOutcome.Failed>()
        withClue("the instantiated value must never reach the rendered SQL") {
            outcome.detail shouldNotContain "PWNED"
        }
    }

    @Test
    fun `class-reaching vectors are blocked at render even when the save scan is bypassed`() {
        RENDER_VECTORS.forEach { (body, context) ->
            val outcome = engineFor(body).execute(TemplateRef("evil.sql", 1), context)
            withClue("render must NOT succeed for: $body") {
                outcome.shouldBeInstanceOf<RenderOutcome.Failed>()
            }
        }
    }

    @Test
    fun `a bypassed eval cannot reach a Java class - its blast radius is the render context`() {
        // templates.md §4.2 claims exactly this: ?eval has no config switch, so the scan is the only
        // guard against it — but §4.3's resolver confines what a leaked ?eval could do to
        // expressions over the context. Both halves are asserted so the claim is not just prose.
        engineFor("\${\"1+1\"?eval}")
            .execute(TemplateRef("evil.sql", 1), emptyMap())
            .shouldBeInstanceOf<RenderOutcome.Success>()
            .sql shouldBe "2"

        engineFor("\${\"\\\"java.lang.String\\\"?new(\\\"PWNED\\\")\"?eval}")
            .execute(TemplateRef("evil.sql", 1), emptyMap())
            .shouldBeInstanceOf<RenderOutcome.Failed>()
    }

    @Test
    fun `interpret really does turn a parameter value into source - which is why the save scan is normative`() {
        // Not a hypothetical: with the save-time scan bypassed, ?interpret compiles the *context
        // value* and runs it, and no §4.3 setting prevents that (Freemarker has no switch for
        // ?eval / ?interpret — templates.md §4.2). This asserts the exposure the scanner closes, so
        // that deleting the scanner check on the belief that "the configuration covers it" fails
        // here rather than in production.
        val outcome =
            engineFor("<@\"\${payload}\"?interpret />")
                .execute(TemplateRef("evil.sql", 1), mapOf("payload" to "INJECTED"))

        outcome.shouldBeInstanceOf<RenderOutcome.Success>().sql shouldBe "INJECTED"

        // …and the save-time scan is what stops such a body ever being stored.
        validator.validate(TemplateFixtures.draft(body = "<@\"\${payload}\"?interpret />")).codes shouldContain
            PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `a literal include or import in a stored body cannot escape the registry`() {
        // The loader resolves nothing but "{id}@{version}" keys, so even a scan-bypassing body
        // cannot name a file, a classpath resource, or a URL (templates.md §4.3, §6.3).
        LOADER_ESCAPE_VECTORS.forEach { body ->
            withClue("loader must not resolve: $body") {
                // Any non-Success is containment; which flavour (not-found vs failed) is §8.2's
                // classification concern, asserted in TemplateEngineTest.
                val outcome = engineFor(body).execute(TemplateRef("evil.sql", 1), emptyMap())
                (outcome is RenderOutcome.Success) shouldBe false
            }
        }
    }

    @Test
    fun `a payload hidden from a comment-stripping scanner is rejected AND does not render`() {
        // THE regression test for TPL-SEC-1, the CRITICAL. The old scanner stripped comments with
        // a regex before matching, so `<#--` and `-->` placed inside *string literals* deleted the
        // payload from the scan while Freemarker executed it — the entire save-time SSTI gate was
        // bypassable for every construct §4.2 forbids.
        //
        // Both halves are asserted, and both must hold for the fix to mean anything:
        //   1. the save-time scan flags it (the gate §4.2 makes normative), and
        //   2. the render does not succeed (so the test cannot pass on a scanner that merely
        //      reports something while the payload still runs).
        HIDDEN_PAYLOAD_VECTORS.forEach { (body, rendersTo) ->
            withClue("save must reject the hidden payload: $body") {
                validator.validate(TemplateFixtures.draft(body = body)).codes shouldContain
                    PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
            }
            withClue("and the payload must be live — it renders to '$rendersTo' when unguarded: $body") {
                // The positive control for THIS arm: if the vector were a typo it would fail the
                // scan for the wrong reason and this suite would read green while the bypass was
                // never reproduced. Rendering it through the engine proves the payload executes.
                engineFor(body)
                    .execute(TemplateRef("evil.sql", 1), emptyMap())
                    .shouldBeInstanceOf<RenderOutcome.Success>()
                    .sql shouldContain rendersTo
            }
        }
    }

    @Test
    fun `the square-bracket tag-syntax switch is rejected AND cannot resolve a template`() {
        // TPL-SEC-2. `[#ftl]` switches the parser to square-bracket syntax — and does so even with
        // tagSyntax pinned to ANGLE_BRACKET (verified against the pinned jar), which is why §4.2
        // refuses `[#` / `[=` outright rather than relying on the pin.
        SQUARE_BRACKET_VECTORS.forEach { body ->
            withClue("save must reject: $body") {
                validator.validate(TemplateFixtures.draft(body = body)).codes shouldContain
                    PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
            }
        }
        SQUARE_BRACKET_DIRECTIVE_VECTORS.forEach { body ->
            withClue("and with the save scan bypassed, the loader must still contain it: $body") {
                (engineFor(body).execute(TemplateRef("evil.sql", 1), emptyMap()) is RenderOutcome.Success) shouldBe false
            }
        }
    }

    @Test
    fun `the camelCase alias of eval_json does not evade the save scan`() {
        // `?evalJson` is Freemarker's camel-case spelling of the forbidden `?eval_json`, and the
        // canonical AST rendering preserves whichever the author wrote — so a check keyed on the
        // snake_case name alone (as the old regex was) misses it.
        validator.validate(TemplateFixtures.draft(body = "\${payload?evalJson}")).codes shouldContain
            PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `a context value containing FTL is data, never re-evaluated as source`() {
        // The classic second-order SSTI: the body is clean, the *parameter value* carries the
        // payload. Freemarker never re-parses an interpolated value, and RenderContextNormalizer
        // hands it over as a plain string — so it must appear verbatim in the SQL.
        val payload = "\${\"freemarker.template.utility.Execute\"?new()(\"id\")}"
        val engine = engineFor("SELECT '\${note}'")

        engine
            .execute(TemplateRef("evil.sql", 1), mapOf("note" to payload))
            .shouldBeInstanceOf<RenderOutcome.Success>()
            .sql shouldBe "SELECT '$payload'"
    }

    private companion object {
        /** The pinned artifact's own version — never a hardcoded constant that could drift from it. */
        fun pinnedVersion(): Version = Configuration.getVersion().let { Version(it.major, it.minor, it.micro) }

        /**
         * The published `ObjectConstructor` gadget — the advisory vector itself, aimed at a harmless
         * target so the control never runs a command.
         *
         * `?new` requires its class to implement `TemplateModel`, which is why an SSTI payload goes
         * through `ObjectConstructor` (a `TemplateMethodModelEx`) to reach *arbitrary* classes. It is
         * verified live against stock Freemarker below; `java.lang.String` merely stands in for
         * whatever an attacker would really construct.
         */
        const val CLASS_INSTANTIATION_VECTOR =
            "\${\"freemarker.template.utility.ObjectConstructor\"?new()(\"java.lang.String\",\"PWNED\")}"

        val SAVE_VECTORS =
            listOf(
                "\${\"freemarker.template.utility.Execute\"?new()(\"id\")}",
                "\${\"freemarker.template.utility.ObjectConstructor\"?new()}",
                "\${\"freemarker.template.utility.JythonRuntime\"?new()}",
                "<#assign ex = \"freemarker.template.utility.Execute\"?new()>",
                "\${obj?api}",
                "\${obj?api.getClass().getProtectionDomain()}",
                "\${\"1+1\"?eval}",
                "\${payload?eval_json}",
                // ?interpret compiles a string into a template and runs it — the one construct that
                // can turn a *parameter value* into executable source.
                "\${payload?interpret}",
                "<@\"\${payload}\"?interpret />",
                "<#import \"x.sql@1\" as x>\nSELECT 1",
                "<#include \"y\">",
                "[#include \"y\"]",
                "SELECT 1 <#-- harmless --><#include \"/etc/passwd\">",
                "\${payload?evalJson}",
            )

        /**
         * Payloads a comment-stripping scanner deletes and Freemarker still executes, each with
         * the output that proves it ran. The `<#--` / `-->` live inside **string literals**, so
         * they are not comments at all — the regex scanner only thought they were.
         */
        val HIDDEN_PAYLOAD_VECTORS: List<Pair<String, String>> =
            listOf(
                "<#assign a=\"<#--\">\${\"1+1\"?eval}<#assign b=\"-->\">" to "2",
                "<#assign o=\"<#--\">\${\"\\\"X\\\"\"?eval}<#assign c=\"-->\">" to "X",
                "<#assign o=\"<#--\">SELECT <@\"INJECTED\"?interpret /><#assign c=\"-->\">" to "INJECTED",
            )

        /**
         * The `[#ftl]` tag-syntax switch and its bare square-bracket siblings — every one refused
         * at save by §4.2's outright rejection of `[#` / `[=`.
         */
        val SQUARE_BRACKET_VECTORS =
            listOf(
                "[#ftl][#include \"/etc/passwd\"]",
                "[#ftl]\n[#import \"lib_date_filters.sql@1\" as d]",
                "[#ftl][=\"1+1\"?eval]",
                "[#include \"y\"]",
            )

        /**
         * The subset that becomes a **live directive** once `[#ftl]` has switched the parser, so
         * the loader's containment can be asserted on it. (`[=…]` stays inert text under the
         * pinned `LEGACY_INTERPOLATION_SYNTAX`, which is why it is not in this list — the save
         * refusal is its whole guard, and that is the point of refusing the spelling.)
         */
        val SQUARE_BRACKET_DIRECTIVE_VECTORS =
            listOf(
                "[#ftl][#include \"/etc/passwd\"]",
                "[#ftl]\n[#import \"lib_date_filters.sql@1\" as d]",
            )

        /** Payloads that reach for a Java class — the four §12.3 hardening targets. */
        val RENDER_VECTORS: List<Pair<String, Map<String, Any?>>> =
            listOf(
                "\${\"freemarker.template.utility.Execute\"?new()(\"id\")}" to emptyMap(),
                "\${\"freemarker.template.utility.ObjectConstructor\"?new()}" to emptyMap(),
                "\${\"java.lang.String\"?new(\"x\")}" to emptyMap(),
                // ?api on a real context value: undefined would be an UndefinedVariable, so bind it
                // to isolate the api-builtin guard itself.
                "\${x?api}" to mapOf("x" to "s"),
                // Member access on a wrapped value: SimpleObjectWrapper exposes no Java members,
                // so the classic `.class.protectionDomain.classLoader` walk dies at the first hop.
                "\${x.class}" to mapOf("x" to "s"),
                "\${x.getClass().getName()}" to mapOf("x" to "s"),
            )

        val LOADER_ESCAPE_VECTORS =
            listOf(
                "<#include \"/etc/passwd\">",
                "<#import \"file:///etc/passwd\" as x>",
                "<#include \"../../../../etc/passwd\">",
                "<#import \"lib_date_filters.sql\" as d>", // unversioned: not a registry key
            )
    }
}
