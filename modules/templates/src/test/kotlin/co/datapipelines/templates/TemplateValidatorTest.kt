package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

/**
 * [TemplateValidator] against every templates.md §7 check, including the negative cases the
 * spec names explicitly (§12.3): duplicate alias, import of a non-library, depth 11, a two-node
 * cycle.
 */
class TemplateValidatorTest {
    private val workspaceId = java.util.UUID.randomUUID()

    private fun validator(vararg registered: TemplateVersion): TemplateValidator {
        val registry = InMemoryTemplateRegistry(registered.toList())
        return TemplateValidator(LibraryResolver { _ -> registry })
    }

    @Test
    fun `a well-formed template passes`() {
        val result = validator().validate(TemplateFixtures.draft(body = "SELECT * FROM orders WHERE id = \${order_id}"), workspaceId)
        result.isValid.shouldBeTrue()
    }

    @Test
    fun `id_invalid rejects an id outside the identifier rule`() {
        validator().validate(TemplateFixtures.draft(id = "Fetch Orders"), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.ID_INVALID
    }

    @Test
    fun `an auto-generated (null) id is not an id_invalid`() {
        validator().validate(TemplateFixtures.draft(id = null), workspaceId).codes shouldNotContain
            PipelineErrorCodes.Template.ID_INVALID
    }

    @Test
    fun `engine_unsupported rejects an engine v1 does not support`() {
        // templates.md §3.2/§7: never stored, because a stored "pebble" template would be rendered
        // as Freemarker — either failing far downstream or, worse, parsing and emitting SQL the
        // author never wrote.
        listOf("pebble", "handlebars", "none", "thymeleaf-sql", "FREEMARKER", "").forEach { engine ->
            withClue("engine must be rejected: '$engine'") {
                validator().validate(TemplateFixtures.draft(engine = engine), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.ENGINE_UNSUPPORTED
            }
        }
    }

    @Test
    fun `the default engine passes and is the only one that does`() {
        validator().validate(TemplateFixtures.draft(engine = Template.FREEMARKER_ENGINE), workspaceId).isValid.shouldBeTrue()
    }

    @Test
    fun `schema_version_unsupported rejects a schema_version v1 does not support`() {
        listOf(0, 2, -1, 99).forEach { version ->
            withClue("schema_version must be rejected: $version") {
                validator().validate(TemplateFixtures.draft(schemaVersion = version), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED
            }
        }
    }

    @Test
    fun `the supported schema_version passes`() {
        validator()
            .validate(TemplateFixtures.draft(schemaVersion = Template.SUPPORTED_SCHEMA_VERSION), workspaceId)
            .isValid
            .shouldBeTrue()
    }

    @Test
    fun `an unsupported engine and schema_version are reported together, not one per round-trip`() {
        val result = validator().validate(TemplateFixtures.draft(engine = "pebble", schemaVersion = 2), workspaceId)

        result.codes shouldContainExactlyInAnyOrder
            listOf(
                PipelineErrorCodes.Template.ENGINE_UNSUPPORTED,
                PipelineErrorCodes.Template.SCHEMA_VERSION_UNSUPPORTED,
            )
    }

    @Test
    fun `the rejected engine value is bounded and sanitized before it is echoed`() {
        val hostile = "pebble\n2026-08-09 INFO forged" + "x".repeat(5_000)

        val failure =
            validator()
                .validate(TemplateFixtures.draft(engine = hostile), workspaceId)
                .failures
                .single { it.code == PipelineErrorCodes.Template.ENGINE_UNSUPPORTED }

        failure.message shouldNotContain "\n"
        (failure.details["engine"] as String).length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1
    }

    @Test
    fun `syntax_error rejects an unparseable body`() {
        validator().validate(TemplateFixtures.draft(body = "<#if x>never closed"), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.SYNTAX_ERROR
    }

    @Test
    fun `dangerous_construct rejects an eval builtin`() {
        validator().validate(TemplateFixtures.draft(body = "\${expr?eval}"), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `dangerous_construct rejects a literal import directive in a body (D12)`() {
        validator().validate(TemplateFixtures.draft(body = "<#import \"x.sql@1\" as x>\nSELECT 1"), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `duplicate_alias rejects two imports sharing an alias`() {
        val a = TemplateFixtures.version("liba.sql", isLibrary = true, body = "<#macro m></#macro>")
        val b = TemplateFixtures.version("libb.sql", isLibrary = true, body = "<#macro m></#macro>")
        val draft =
            TemplateFixtures.draft(
                imports =
                    listOf(
                        TemplateImport("liba.sql", 1, "shared"),
                        TemplateImport("libb.sql", 1, "shared"),
                    ),
            )
        validator(a, b).validate(draft, workspaceId).codes shouldContain PipelineErrorCodes.Template.DUPLICATE_ALIAS
    }

    @Test
    fun `import_not_found rejects an import missing from the registry`() {
        val draft = TemplateFixtures.draft(imports = listOf(TemplateImport("absent.sql", 7, "x")))
        validator().validate(draft, workspaceId).codes shouldContain PipelineErrorCodes.Template.IMPORT_NOT_FOUND
    }

    @Test
    fun `import_not_library rejects importing a non-library template`() {
        val notLib = TemplateFixtures.version("regular.sql", isLibrary = false)
        val draft = TemplateFixtures.draft(imports = listOf(TemplateImport("regular.sql", 1, "r")))
        validator(notLib).validate(draft, workspaceId).codes shouldContain PipelineErrorCodes.Template.IMPORT_NOT_LIBRARY
    }

    @Test
    fun `import_cycle rejects a two-node cycle`() {
        val a = TemplateFixtures.version("liba.sql", isLibrary = true, imports = listOf(TemplateImport("libb.sql", 1, "b")))
        val b = TemplateFixtures.version("libb.sql", isLibrary = true, imports = listOf(TemplateImport("liba.sql", 1, "a")))
        val draft = TemplateFixtures.draft(imports = listOf(TemplateImport("liba.sql", 1, "a")))
        validator(a, b).validate(draft, workspaceId).codes shouldContain PipelineErrorCodes.Template.IMPORT_CYCLE
    }

    /**
     * `lib1 → lib2 → … → lib{depth}`, where the last library imports nothing — a terminating
     * chain of exactly [depth] levels below the draft.
     */
    private fun chain(depth: Int): Array<TemplateVersion> =
        (1..depth)
            .map { n ->
                TemplateFixtures.version(
                    "lib$n.sql",
                    isLibrary = true,
                    imports = if (n == depth) emptyList() else listOf(TemplateImport("lib${n + 1}.sql", 1, "n")),
                )
            }.toTypedArray()

    private val chainRoot = TemplateFixtures.draft(imports = listOf(TemplateImport("lib1.sql", 1, "n")))

    @Test
    fun `a terminating chain exactly at the depth cap validates clean`() {
        // The positive case that was missing (TPL-API-1 / TPL-TEST-4): the walk guarded on entry
        // with no empty-list short-circuit, so the deepest library's *empty* imports array was
        // still "walked" at depth 11 and a perfectly legal depth-10 closure was rejected. The cap
        // is 10 (§6.4); the effective cap was 9.
        withClue("§6.4 caps transitive depth at ${LibraryResolver.MAX_IMPORT_DEPTH}, so exactly that must pass") {
            validator(*chain(LibraryResolver.MAX_IMPORT_DEPTH)).validate(chainRoot, workspaceId).isValid.shouldBeTrue()
        }
    }

    @Test
    fun `import_depth_exceeded rejects a chain one deeper than the cap`() {
        // Every library in the chain is registered, including the eleventh — otherwise this would
        // pass on `import_not_found` and prove nothing about the depth rule (TPL-API-1).
        val result = validator(*chain(LibraryResolver.MAX_IMPORT_DEPTH + 1)).validate(chainRoot, workspaceId)

        result.codes shouldContain PipelineErrorCodes.Template.IMPORT_DEPTH_EXCEEDED
        withClue("the chain must fail for the depth, not because a library was missing") {
            result.codes shouldNotContain PipelineErrorCodes.Template.IMPORT_NOT_FOUND
        }
    }

    @Test
    fun `a wide but shallow import DAG still validates`() {
        // The memoized walk must not turn "already seen" into "already rejected": ten libraries
        // sharing one dependency is a legal diamond, not a cycle.
        val leaf = TemplateFixtures.version("leaf.sql", isLibrary = true, body = "<#macro m></#macro>")
        val mids =
            (1..10).map { n ->
                TemplateFixtures.version("mid$n.sql", isLibrary = true, imports = listOf(TemplateImport("leaf.sql", 1, "l")))
            }
        val draft = TemplateFixtures.draft(imports = mids.mapIndexed { i, m -> TemplateImport(m.id, 1, "a$i") })

        validator(leaf, *mids.toTypedArray()).validate(draft, workspaceId).isValid.shouldBeTrue()
    }

    @Test
    fun `an exponentially fanning import graph validates in bounded time`() {
        // TPL-SEC-5 / §12.3 adversarial-input suite. lib_k imports lib_{k-1} under ten distinct
        // aliases: an unmemoized walk visits 10^9 nodes (~134s measured); the (library, depth)
        // memo bounds it at distinct-libraries × depth.
        val libs =
            (1..LibraryResolver.MAX_IMPORT_DEPTH).map { k ->
                TemplateFixtures.version(
                    "fan$k.sql",
                    isLibrary = true,
                    imports = if (k == 1) emptyList() else (1..10).map { a -> TemplateImport("fan${k - 1}.sql", 1, "a$a") },
                )
            }
        val draft = TemplateFixtures.draft(imports = listOf(TemplateImport("fan${LibraryResolver.MAX_IMPORT_DEPTH}.sql", 1, "top")))

        lateinit var result: TemplateValidationResult
        val elapsed = measureTimeMillis { result = validator(*libs.toTypedArray()).validate(draft, workspaceId) }

        // Asserting the VERDICT is what makes this test able to fail (HIGH-2). The graph is legal —
        // fan1 imports nothing and the deepest reach is exactly the §6.4 cap — so deleting the memo
        // does not merely slow the walk down: it trips MAX_EXPANSIONS and turns a valid template
        // into a rejected one. Timing alone would have passed on the backstop that masks the bug.
        withClue("the fan graph is legal: ${result.codes}") { result.isValid.shouldBeTrue() }
        withClue("wide-fan-out import validation took ${elapsed}ms") { (elapsed < ADVERSARIAL_BUDGET_MS).shouldBeTrue() }
    }

    @Test
    fun `a library reached at two depths is re-walked at the deeper one`() {
        // HIGH-3: the memo key's DEPTH component was unguarded — dropping `#\$depth` from
        // `expanded.add(...)` kept every other test green, because nothing reached one library at
        // two different depths. That is a real false NEGATIVE, not a slow path: a key-only memo
        // marks X "done" at depth 1 and then skips the depth-9 reach, so a closure two levels over
        // the §6.4 cap validates clean and gets stored.
        //
        //   draft ─→ X                                  (X at depth 1)
        //   draft ─→ P1 → … → P8 → X                    (X at depth 9)
        //                            X → Y1 → Y2        (Y2 at depth 11 > cap 10)
        val chainToX =
            (1..8).map { n ->
                TemplateFixtures.version(
                    "p$n.sql",
                    isLibrary = true,
                    imports = listOf(if (n == 8) TemplateImport("x.sql", 1, "x") else TemplateImport("p${n + 1}.sql", 1, "n")),
                )
            }
        val x = TemplateFixtures.version("x.sql", isLibrary = true, imports = listOf(TemplateImport("y1.sql", 1, "y")))
        val y1 = TemplateFixtures.version("y1.sql", isLibrary = true, imports = listOf(TemplateImport("y2.sql", 1, "y")))
        val y2 = TemplateFixtures.version("y2.sql", isLibrary = true)
        val draft =
            TemplateFixtures.draft(
                imports = listOf(TemplateImport("x.sql", 1, "shallow"), TemplateImport("p1.sql", 1, "deep")),
            )

        val result = validator(*chainToX.toTypedArray(), x, y1, y2).validate(draft, workspaceId)

        withClue("X is legal at depth 1 but over the cap at depth 9 — the deeper reach must be walked") {
            result.codes shouldContain PipelineErrorCodes.Template.IMPORT_DEPTH_EXCEEDED
        }
    }

    @Test
    fun `a body over the length cap is rejected without being parsed`() {
        // TPL-SEC-3: the cap exists to keep an adversarial body away from the parser, so it is
        // enforced before parsing — and must be fast, since it runs on the request thread.
        val registry = InMemoryTemplateRegistry()
        val validator = TemplateValidator(LibraryResolver { _ -> registry }, maxBodyChars = 1_000)
        val overCap = "SELECT 1 ".repeat(200)

        val elapsed =
            measureTimeMillis {
                validator.validate(TemplateFixtures.draft(body = overCap), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.SYNTAX_ERROR
            }

        withClue("over-cap rejection took ${elapsed}ms") { (elapsed < ADVERSARIAL_BUDGET_MS).shouldBeTrue() }
    }

    @Test
    fun `a body at the cap is accepted and validates in bounded time`() {
        // The other half of the cap: the largest legal body must still validate quickly, or the
        // cap is merely relocating the denial of service to just under the limit.
        val line = "SELECT \${a} FROM t WHERE b='\${c}'\n"
        val body = buildString { while (length < TemplateValidator.DEFAULT_MAX_BODY_CHARS - line.length) append(line) }

        val elapsed = measureTimeMillis { validator().validate(TemplateFixtures.draft(body = body), workspaceId).isValid.shouldBeTrue() }

        withClue("${body.length}-char body validated in ${elapsed}ms") { (elapsed < ADVERSARIAL_BUDGET_MS).shouldBeTrue() }
    }

    @Test
    fun `a body over the bracket-nesting pre-scan is rejected by the pre-scan itself`() {
        // The two bounds are pinned separately (MEDIUM-1): this one must fail for the PRE-SCAN's
        // reason, so its message is asserted rather than just the code. Previously one test stood
        // for both bounds and they masked each other — deleting either left it green.
        val body = "\${" + "(".repeat(4_000) + "1" + ")".repeat(4_000) + "}"

        val failure =
            validator()
                .validate(TemplateFixtures.draft(body = body), workspaceId)
                .failures
                .single { it.code == PipelineErrorCodes.Template.SYNTAX_ERROR }

        failure.message shouldContainIgnoringCase "nesting depth"
        failure.message shouldContain TemplateBodyParser.MAX_NESTING_DEPTH.toString()
    }

    @Test
    fun `a bracket-free body that overflows the parser is a syntax_error, never an escaped Error`() {
        // TPL-SEC-6's actual path (MEDIUM-1). The pre-scan counts BRACKETS, so a bracket-free
        // `\${a!a!a…}` sails past it and reaches the parser, which overflows on it — verified
        // against the pinned jar. This is the body that exercises the `catch (StackOverflowError)`;
        // the 4000-paren case never reaches the parser at all.
        val body = "\${a" + "!a".repeat(2_000) + "}"

        val failure =
            validator()
                .validate(TemplateFixtures.draft(body = body), workspaceId)
                .failures
                .single { it.code == PipelineErrorCodes.Template.SYNTAX_ERROR }

        withClue("must be the parser's verdict, not the pre-scan's") {
            failure.message shouldContainIgnoringCase "nests too deeply"
        }
    }

    @Test
    fun `a bracket-free adversarial body at the cap fails fast instead of burning the save thread`() {
        // The §12.3 "a body at max-body-chars completes within a bounded time" case that neither
        // the length cap nor the bracket pre-scan covers: `\${a+a+a…}` at exactly the cap took
        // 37.3 SECONDS of uninterruptible save-thread CPU on the default stack before overflowing.
        // Parsing on a bounded stack (TemplateBodyParser.PARSE_STACK_BYTES) is what bounds it.
        val body = "\${a" + "+a".repeat(131_070) + "}"

        lateinit var codes: List<String>
        val elapsed = measureTimeMillis { codes = validator().validate(TemplateFixtures.draft(body = body), workspaceId).codes }

        codes shouldContain PipelineErrorCodes.Template.SYNTAX_ERROR
        withClue("parsing this ${body.length}-char body took ${elapsed}ms") {
            (elapsed < ADVERSARIAL_BUDGET_MS).shouldBeTrue()
        }
    }

    @Test
    fun `a legitimate body just under the nesting pre-scan still parses`() {
        // The other side of the bounded stack: it must sit ABOVE what the pre-scan admits, or the
        // two bounds contradict and a body the pre-scan allows dies in the parser instead.
        val depth = TemplateBodyParser.MAX_NESTING_DEPTH - 1
        val body = "\${" + "(".repeat(depth) + "1" + ")".repeat(depth) + "}"

        validator().validate(TemplateFixtures.draft(body = body), workspaceId).isValid.shouldBeTrue()
    }

    @Test
    fun `deep directive nesting is a catalog verdict at both ends, never an escaped Error`() {
        // The bracket pre-scan counts brackets, so directive nesting reaches the parser and the
        // bounded parse stack decides it. Both ends are pinned, because a one-sided assertion is
        // how a bound drifts unnoticed — and the old version of this test asserted
        // `codes.all { … }`, which is vacuously true of an empty list and could not fail at all.
        //
        // The ceiling is environment-sensitive (frame sizes differ between a cold interpreted run
        // and a JIT-warmed one — measured 800-deep parsing standalone but not inside the Gradle
        // test JVM), so the "legitimate" figure here is deliberately conservative rather than at
        // the measured edge. 200 matches the bracket pre-scan's own limit and is already far past
        // anything a SQL template author writes; asserting nearer the edge would buy nothing and
        // flake on whichever machine has the smaller frames.
        val legitimate = "<#if a>".repeat(200) + "x" + "</#if>".repeat(200)
        val adversarial = "<#if a>".repeat(3_000) + "x" + "</#if>".repeat(3_000)

        withClue("realistic (indeed generous) directive nesting must still validate") {
            validator().validate(TemplateFixtures.draft(body = legitimate), workspaceId).isValid.shouldBeTrue()
        }
        withClue("and the overflowing one is a syntax_error, not a StackOverflowError as a 500") {
            validator().validate(TemplateFixtures.draft(body = adversarial), workspaceId).codes shouldContain
                PipelineErrorCodes.Template.SYNTAX_ERROR
        }
    }

    @Test
    fun `is_library_without_macros rejects a library with no macro`() {
        validator().validate(TemplateFixtures.draft(isLibrary = true, body = "SELECT 1"), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.IS_LIBRARY_WITHOUT_MACROS
    }

    @Test
    fun `is_library_without_macros rejects output outside macro definitions`() {
        val body = "<#macro m>x</#macro>\nSELECT 1 -- output that would leak into importers"
        validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.IS_LIBRARY_WITHOUT_MACROS
    }

    @Test
    fun `a macro-only library body passes the is_library check`() {
        val body = "<#-- lib -->\n<#macro date_range column start end>\n  \${column}\n</#macro>"
        validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).isValid.shouldBeTrue()
    }

    @Test
    fun `a function-only library body passes the is_library check`() {
        // templates.md §6.2: "everything at the top level is <#macro> / <#function> / comments".
        // §7's summary row reads "at least one <#macro>"; this pins the §6.2 reading — see the
        // note in LibraryBodyCheck. Flagged to the orchestrator for a §7 wording amendment.
        val body = "<#function to_cents amount><#return amount * 100></#function>"
        validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).isValid.shouldBeTrue()
    }

    @Test
    fun `a library may carry blank lines and comments between definitions`() {
        val body = "<#macro a>x</#macro>\n\n<#-- gap -->\n<#function b n><#return n></#function>\n"
        validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).isValid.shouldBeTrue()
    }

    @Test
    fun `a leading ftl header is rejected for a library too`() {
        // Flipped at v1.6 (TPL-SEC-10). v1.4/v1.5 allowed the header as "inert"; it is not — it
        // evaluates `attributes={…}` at parse time on the save thread, and it produces no AST node
        // so the §4.2 scan cannot see what it carries. §6.2 now disallows it for libraries and
        // templates alike, and the refusal is source-level so it lands before the parse.
        val body = "<#ftl encoding='UTF-8'>\n<#macro a>x</#macro>"

        validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `a library with a top-level assignment or interpolation is rejected`() {
        // The regex check this replaced stripped only definition *blocks*, so a top-level
        // directive that produced no text could slip through. On the AST every top-level element
        // is inspected: anything that is not a definition, a comment or blank text runs on import.
        listOf(
            "<#macro m>x</#macro><#assign leaked = 1>",
            "<#macro m>x</#macro>\${leaked}",
            "<#macro m>x</#macro><@m/>",
            "<#macro m>x</#macro><#if flag>y</#if>",
        ).forEach { body ->
            withClue("must not be a valid library body: $body") {
                validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).codes shouldContain
                    PipelineErrorCodes.Template.IS_LIBRARY_WITHOUT_MACROS
            }
        }
    }

    @Test
    fun `top-level output is rejected on the AST whatever it is dressed up as`() {
        // Reworded to what it actually proves (MEDIUM-2). It does NOT distinguish the AST check
        // from the old comment-stripping one: the body is rejected because a top-level `<#assign>`
        // is disallowed outright, and the old implementation rejected it too. The reviewer could
        // not construct a leak the old check missed — every mechanism for hiding top-level output
        // is itself a disallowed top-level element — so the honest claim is the one made here:
        // on the AST, every top-level element is inspected, so no dressing changes the verdict.
        val body = "<#macro m>x</#macro><#assign a=\"<#--\">SELECT 'leaked'<#assign b=\"-->\">"

        validator().validate(TemplateFixtures.draft(isLibrary = true, body = body), workspaceId).codes shouldContain
            PipelineErrorCodes.Template.IS_LIBRARY_WITHOUT_MACROS
    }

    @Test
    fun `validation is exhaustive - all failures collected at once`() {
        // A bad id AND a forbidden construct: an author sees both, not one per round-trip.
        val draft = TemplateFixtures.draft(id = "BAD ID", body = "\${x?api}")
        validator().validate(draft, workspaceId).codes shouldContainExactlyInAnyOrder
            listOf(PipelineErrorCodes.Template.ID_INVALID, PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT)
    }

    private companion object {
        /**
         * Wall-clock budget for the §12.3 adversarial-input cases. Generous by two orders of
         * magnitude against the measured cost (a 256K body parses and scans in well under
         * 200 ms), and still far below the seconds-to-minutes the unbounded versions took — so it
         * catches a regression to quadratic or exponential without being flaky on a loaded box.
         */
        const val ADVERSARIAL_BUDGET_MS = 5_000L
    }
}
