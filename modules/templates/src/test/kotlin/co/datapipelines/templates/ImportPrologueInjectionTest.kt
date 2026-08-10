package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The `imports` array is the **second untrusted input** to the render engine, and the one the
 * body scan cannot see.
 *
 * templates.md §6.3 has the engine *synthesize* `<#import "{id}@{version}" as {alias}>` from the
 * array. Both interpolated pieces come from the author's JSON payload, so an `alias` (or an `id`)
 * carrying FTL metacharacters injects source that [ForbiddenConstructScanner] never scanned —
 * exactly the bypass §4.2 forbids literal `<#import>` in a body to prevent. Two independent
 * layers must therefore hold here too, and are tested independently:
 *
 *  1. **Save** — a malformed alias/id is `template.validation.dangerous_construct`.
 *  2. **Render** — [RegistryTemplateLoader] fails closed on any entry it would have to
 *     interpolate unsafely, even for a row that somehow reached the database.
 */
class ImportPrologueInjectionTest {
    private val library = TemplateFixtures.version("lib.sql", isLibrary = true, body = "<#macro m>ok</#macro>")
    private val engines = mutableListOf<TemplateEngine>()

    @AfterEach
    fun tearDown() = engines.forEach { it.close() }

    private fun validator(): TemplateValidator = TemplateValidator(LibraryResolver(InMemoryTemplateRegistry(listOf(library))))

    private fun engineWith(vararg versions: TemplateVersion): TemplateEngine =
        TemplateEngine(InMemoryTemplateRegistry(versions.toList() + library), 10, 5_000, 1_000_000)
            .also { engines += it }

    @Test
    fun `an alias carrying FTL metacharacters is rejected at save`() {
        INJECTING_ALIASES.forEach { alias ->
            withClue("save must reject alias: $alias") {
                validator()
                    .validate(TemplateFixtures.draft(imports = listOf(TemplateImport("lib.sql", 1, alias))))
                    .codes shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
            }
        }
    }

    @Test
    fun `an import id outside the identifier rule is rejected at save`() {
        validator()
            .validate(TemplateFixtures.draft(imports = listOf(TemplateImport("lib.sql\" as x><#assign a=1>", 1, "d"))))
            .codes shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `a non-positive import version is rejected at save`() {
        // §6.3 names `version` a positive integer alongside the alias/id identifier rules — it is
        // interpolated into the same synthesized directive, so 0 and negatives are refused there
        // too rather than being left to fail as a registry miss.
        listOf(0, -1, Int.MIN_VALUE).forEach { version ->
            withClue("version must be rejected: $version") {
                validator()
                    .validate(TemplateFixtures.draft(imports = listOf(TemplateImport("lib.sql", version, "d"))))
                    .codes shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
            }
        }
    }

    @Test
    fun `a long alias is accepted - the spec sets no length bound`() {
        // §6.3's rule is `[a-zA-Z_][a-zA-Z0-9_]*`, unbounded. The character class carries the
        // security property; adding a length cap would reject aliases the contract permits.
        validator()
            .validate(TemplateFixtures.draft(imports = listOf(TemplateImport("lib.sql", 1, "a".repeat(200)))))
            .isValid
            .shouldBeTrue()
    }

    @Test
    fun `the save refusal never echoes the offending id or alias`() {
        // templates.md §6.3 (frozen): "the refusal message never echoes the offending value back
        // into logs" (TPL-API-3). The attacker controls both strings; reflecting them puts
        // attacker-authored FTL into every log sink and error response that carries the failure.
        // RegistryTemplateLoader's refusal already obeyed this — the two are now aligned.
        val hostileId = "lib.sql\" as x><#assign pwned=1>"
        val hostileAlias = "d>\${\"PWNED\"}<#assign z=1"

        val result = validator().validate(TemplateFixtures.draft(imports = listOf(TemplateImport(hostileId, 1, hostileAlias))))

        // Scanned across EVERY failure, not just the dangerous_construct one (NEW-3). Checking a
        // single failure missed that the traversal also emitted an `import_not_found` for the same
        // entry whose message DID echo the attacker's id — the rule was satisfied on one code path
        // and broken on another. `walk` now skips entries that already failed the safety check.
        withClue("no failure anywhere may carry the attacker's strings: ${result.failures}") {
            listOf("PWNED", "#assign", "as x>").forEach { fragment ->
                result.failures.forEach { failure ->
                    failure.message shouldNotContain fragment
                    failure.details.values
                        .joinToString()
                        .shouldNotContain(fragment)
                }
            }
        }
        withClue("and the refused entry is reported once, not once per check") {
            result.codes shouldContainExactly listOf(PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT)
        }
        withClue("but the author still needs to know WHICH entry was refused") {
            result.failures
                .single()
                .details["index"] shouldBe 0
        }
    }

    @Test
    fun `a plain alias still passes`() {
        validator()
            .validate(TemplateFixtures.draft(imports = listOf(TemplateImport("lib.sql", 1, "dates_1"))))
            .isValid
            .shouldBeTrue()
    }

    @Test
    fun `the loader fails closed on an injecting alias that somehow reached storage`() {
        // Simulates a row written before this guard existed, or by a path that skipped validation:
        // the render must still refuse, because the prologue is source the scan never saw.
        val main =
            TemplateFixtures.version(
                "main.sql",
                imports = listOf(TemplateImport("lib.sql", 1, "d>\${\"PWNED\"}<#assign z=1")),
                body = "SELECT 1",
            )
        val outcome = engineWith(main).execute(TemplateRef("main.sql", 1), emptyMap())

        outcome.shouldBeInstanceOf<RenderOutcome.Failed>()
        withClue("the injected interpolation must never reach the rendered SQL") {
            outcome.detail shouldNotContain "PWNED"
        }
    }

    private companion object {
        val INJECTING_ALIASES =
            listOf(
                "d>\${\"PWNED\"}<#assign z=1",
                "d><#include \"/etc/passwd\">\n<#assign z=1",
                "d\n",
                "d>\${\"x\"?eval}<#--",
                "",
            )
    }
}
