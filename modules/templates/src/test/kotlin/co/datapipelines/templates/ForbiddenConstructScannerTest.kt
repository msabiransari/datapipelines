package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

/**
 * [ForbiddenConstructScanner] evasion robustness (templates.md §4.2, §7).
 *
 * The scan is the *only* guard against `?eval` (no Freemarker config disables it), so it must
 * survive the evasions an attacker reaches for. Two of them defeated the previous
 * regex-over-stripped-source scanner outright and are the reason §4.2 now makes an AST-based
 * scan normative:
 *  - a `<#--` / `-->` pair hidden **inside string literals**, which made a comment-stripping
 *    regex delete a live `?eval`;
 *  - a leading `[#ftl]`, which switches the parser to square-bracket syntax.
 *
 * `SstiMatrixTest` carries the end-to-end arm for both — scanner flags it **and** the render
 * does not succeed. This class is the unit-level detail.
 */
class ForbiddenConstructScannerTest {
    private fun flagged(body: String): Boolean = ForbiddenConstructScanner.scan(body).isNotEmpty()

    private fun rejected(body: String): List<String> =
        TemplateValidator(LibraryResolver { _ -> InMemoryTemplateRegistry() })
            .validate(TemplateFixtures.draft(body = body), java.util.UUID.randomUUID())
            .codes

    @Test
    fun `plain forbidden builtins are flagged`() {
        flagged("\${x?eval}").shouldBeTrue()
        flagged("\${x?api}").shouldBeTrue()
        flagged("\${\"c\"?new()}").shouldBeTrue()
    }

    @Test
    fun `the interpret builtin is flagged alongside eval`() {
        flagged("\${payload?interpret}").shouldBeTrue()
        flagged("<@\"\${payload}\"?interpret />").shouldBeTrue()
    }

    @Test
    fun `both spellings of eval_json are flagged`() {
        // Freemarker accepts the camel-case alias and its canonical rendering *preserves* the
        // spelling the author wrote, so a scanner keyed on the snake_case name alone misses
        // `?evalJson` entirely — which is what the previous regex did.
        flagged("\${payload?eval_json}").shouldBeTrue()
        flagged("\${payload?evalJson}").shouldBeTrue()
        ForbiddenConstructScanner
            .scan("\${payload?evalJson}")
            .map { it.construct } shouldContain "?eval_json"
    }

    @Test
    fun `a payload hidden between string-literal comment markers does not evade`() {
        // THE bypass (TPL-SEC-1). A comment-stripping regex deletes everything between the two
        // literals and reports a clean body, while Freemarker parses and executes the ?eval.
        // On the AST there is nothing to strip: the literals are literals and the ?eval is a node.
        val body = "<#assign a=\"<#--\">\${\"1+1\"?eval}<#assign b=\"-->\">"

        withClue("the string literals must not be able to hide the ?eval between them") {
            flagged(body).shouldBeTrue()
        }
        rejected(body) shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `a leading square-bracket ftl header does not evade`() {
        // TPL-SEC-2: `[#ftl]` switches the parser to square-bracket syntax, so `[#include …]` is
        // a live directive. Note the tagSyntax pin does NOT prevent this (verified against the
        // pinned jar) — the outright refusal of `[#` / `[=` is what closes it, and the AST scan
        // catches the Include node besides.
        listOf(
            "[#ftl][#include \"/etc/passwd\"]",
            "[#ftl]\n[#import \"x@1\" as x]",
            "[#ftl][=\"1+1\"?eval]",
        ).forEach { body ->
            withClue("square-bracket syntax must be refused: $body") {
                flagged(body).shouldBeTrue()
                rejected(body) shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
            }
        }
    }

    @Test
    fun `a leading ftl header is refused BEFORE the parse, which is what stops the CPU burn`() {
        // TPL-SEC-10, and the one hole the AST move opened. `<#ftl attributes={…}>` evaluates its
        // expressions at PARSE time on the save thread, so the attack IS the parse: measured
        // against the pinned jar, `{"k":(1..N)?seq_index_of(-1)}` costs 75ms / 216ms / 1634ms at
        // N = 2e6 / 2e7 / 2e8 — linear in a literal the attacker picks, from a ~65-byte body that
        // `max-body-chars` cannot bound.
        //
        // The wall-clock bound is the real assertion here: a refusal that happened AFTER the parse
        // would still be a "rejected" verdict and would still pass a code-only check, while
        // burning the CPU it was supposed to save.
        val dos = "<#ftl attributes={\"k\":(1..200000000)?seq_index_of(-1)}>OK"

        val elapsed =
            measureTimeMillis {
                withClue("must be refused, and by the source-level scan") {
                    ForbiddenConstructScanner.scanSource(dos).map { it.construct } shouldContain
                        ForbiddenConstructScanner.FTL_HEADER_CONSTRUCT
                    rejected(dos) shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
                }
            }

        withClue("refusing this body took ${elapsed}ms — it must not reach the parser at all") {
            (elapsed < PARSE_TIME_EVAL_BUDGET_MS).shouldBeTrue()
        }
    }

    @Test
    @Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
    fun `a forbidden builtin hidden in an ftl header is refused`() {
        // The gate hole itself: this body PARSES CLEAN to a bare TextBlock (verified against the
        // pinned jar) — the parser consumes the header, so the `?eval` carries no AST node and
        // scanAst has nothing to find. Only the source-level refusal catches it.
        val body = "<#ftl attributes={\"k\":\"1+1\"?eval}>OK"

        withClue("the AST genuinely cannot see it — that is why this must be a source check") {
            ForbiddenConstructScanner
                .scanAst(
                    (TemplateBodyParser.parse(body) as BodyParse.Parsed).template.rootTreeNode,
                ).shouldBeEmpty()
        }
        rejected(body) shouldContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `every spelling of the leading header is refused, including after whitespace`() {
        listOf(
            "<#ftl>",
            "<#ftl encoding='UTF-8'>SELECT 1",
            "\n\n   <#ftl encoding='UTF-8'>SELECT 1",
            "<#FTL>SELECT 1",
            "[#ftl]SELECT 1",
        ).forEach { body ->
            withClue("must be refused: $body") { flagged(body).shouldBeTrue() }
        }
    }

    @Test
    fun `a non-leading ftl tag stays a syntax_error, not a dangerous_construct`() {
        // Freemarker only accepts the header as the leading token, so `<#ftl>` anywhere else is
        // already a parse error. The refusal is anchored at `^\s*` rather than matching `<#ftl`
        // anywhere, so this keeps its accurate code instead of being relabelled.
        val codes = rejected("SELECT 1 <#ftl>")

        codes shouldContain PipelineErrorCodes.Template.SYNTAX_ERROR
        codes shouldNotContain PipelineErrorCodes.Template.DANGEROUS_CONSTRUCT
    }

    @Test
    fun `square-bracket syntax is refused even without an ftl header`() {
        flagged("[#include \"y\"]").shouldBeTrue()
        flagged("[=x]").shouldBeTrue()
    }

    @Test
    fun `a longer builtin that merely starts with a forbidden name is not flagged`() {
        // The token boundary matters in both directions: over-matching would reject legitimate
        // authoring, under-matching would let `?eval` through as part of a longer token.
        flagged("\${x?new_thing}").shouldBeFalse()
        flagged("\${x?evaluated}").shouldBeFalse()
    }

    @Test
    fun `whitespace between the question mark and the builtin does not evade`() {
        flagged("\${x?   eval}").shouldBeTrue()
    }

    @Test
    fun `a comment splitting the token does not evade`() {
        // Freemarker strips comments between tokens, so this parses as x?eval — and the AST
        // therefore *contains* `${x?eval}`, with no stripping step of our own to be lied to.
        flagged("\${x?<#-- nothing -->eval}").shouldBeTrue()
    }

    @Test
    fun `a case- or unicode-spelled builtin is rejected by the parser, not the scan`() {
        // Freemarker built-in names are case-sensitive ASCII, so `?EVAL` and `?ｅｖａｌ` do not
        // parse at all (verified against the pinned 2.3.34 jar). The body is still refused — by
        // syntax_error rather than dangerous_construct — which is what matters: it is never
        // stored. Asserted through the validator, because the scan alone has no tree to walk.
        listOf("\${x?EVAL}", "\${x?ｅｖａｌ}", "\${x?Api}").forEach { body ->
            withClue("must be rejected at save: $body") {
                rejected(body) shouldContain PipelineErrorCodes.Template.SYNTAX_ERROR
            }
        }
    }

    @Test
    fun `import and include directives are flagged in both tag syntaxes`() {
        flagged("<#import \"x@1\" as y>").shouldBeTrue()
        flagged("<#include \"y\">").shouldBeTrue()
        flagged("[#import \"x@1\" as y]").shouldBeTrue()
    }

    @Test
    fun `the utility package reference is flagged`() {
        flagged("\${\"freemarker.template.utility.Execute\"?new()}").shouldBeTrue()
        flagged("<#assign j = \"freemarker.template.utility.JythonRuntime\">").shouldBeTrue()
    }

    @Test
    fun `a construct hidden inside a comment is inert and not flagged`() {
        withClue("A construct inside a comment never executes, so comment nodes are skipped") {
            flagged("<#-- \${x?eval} -->\nSELECT 1").shouldBeFalse()
        }
    }

    @Test
    fun `literal SQL text is not scanned for builtins`() {
        // A TextBlock is emitted verbatim and never re-parsed, so the words cannot be a
        // construct. Precision the AST buys that a scan over raw source could not.
        flagged("SELECT 'x?eval' FROM t -- freemarker.template.utility").shouldBeFalse()
    }

    @Test
    fun `a string literal inside an expression is still over-rejected, deliberately`() {
        // The safe direction: the element owning the literal prints it, so it is flagged. An
        // author resolves this by rephrasing; the alternative is a scanner that reasons about
        // which literals can reach an evaluator, which is exactly how bypasses are born.
        flagged("<#assign a = \"x?eval\">").shouldBeTrue()
    }

    @Test
    fun `no AST position in this swept set hides a forbidden builtin from the scan`() {
        // The completeness question the AST move raises: `getDescription()` prints an element's
        // own expressions, so a construct is only found if the position that carries it belongs
        // to an element that prints it. Every place Freemarker 2.3.34 lets an expression appear is
        // swept here — macro parameter defaults, macro-call arguments, `#switch`/`#case`,
        // `#elseif`, namespace assignment, `#setting`, `#escape`, `#stop`, `#visit`, `#recurse`,
        // `#nested`, `#list`'s `#else` branch, `#attempt`, `#compress`, and a captured `#assign`
        // block. Verified against the pinned jar; a jar change that stops printing one of these
        // fails here rather than opening a hole.
        listOf(
            "<#macro m x=\"1+1\"?eval>y</#macro>",
            "<#function f a=x?eval><#return 1></#function>",
            "<@n.mac arg=\"1+1\"?eval/>",
            "<@n.mac arg=q?eval; loopVar>body</@n.mac>",
            "<#switch x?eval><#case 1>a<#break></#switch>",
            "<#switch v><#case x?eval>a<#break></#switch>",
            "<#if a><#elseif b?eval>y</#if>",
            "<#assign a = 1 in ns?eval>",
            "<#global q = x?eval>",
            "<#setting number_format=x?eval>",
            "<#escape x as x?eval>t</#escape>",
            "<#stop x?eval>",
            "<#visit x?eval>",
            "<#recurse x?eval>",
            "<#macro m><#nested x?eval /></#macro>",
            "<#list s as i><#else>\${x?eval}</#list>",
            "<#attempt>\${x?eval}<#recover>y</#attempt>",
            "<#compress>\${x?eval}</#compress>",
            "<#assign a>\${x?eval}</#assign>",
            "<#outputformat \"HTML\">\${x?eval}</#outputformat>",
            // The `<#ftl …>` header is in this list but is NOT an AST position: it produces no
            // node at all. It is caught by the source-level refusal in scanSource, and it is
            // listed here so the sweep reads as "no position hides a built-in", AST or not.
            "<#ftl attributes={\"k\":\"1+1\"?eval}>OK",
        ).forEach { body ->
            withClue("a forbidden builtin must not hide here: $body") { flagged(body).shouldBeTrue() }
        }
    }

    @Test
    fun `a noparse block is inert output and is not flagged`() {
        // `<#noparse>` emits its content verbatim; Freemarker parses it into a TextBlock and never
        // executes it, so this is a correct non-flag rather than a gap in the sweep above.
        flagged("<#noparse>\${x?eval}</#noparse>").shouldBeFalse()
    }

    @Test
    fun `allowed builtins and directives are not flagged`() {
        flagged("\${total?c}").shouldBeFalse()
        flagged("<#if x><#list items as i>\${i?upper_case}</#list></#if>").shouldBeFalse()
        flagged("SELECT \${amount} FROM t WHERE d = '\${start_date}'").shouldBeFalse()
    }

    private companion object {
        /**
         * Wall-clock budget for refusing a parse-time-eval header. The payload costs ~1.6s of
         * uninterruptible CPU *inside the parser*, so a refusal that reaches the parse blows this
         * by an order of magnitude while a source-level refusal is sub-millisecond. Wide enough
         * not to flake on a loaded box, narrow enough that only the correct ordering passes.
         */
        const val PARSE_TIME_EVAL_BUDGET_MS = 400L
    }
}
