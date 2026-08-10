package co.datapipelines.templates

import freemarker.core.Macro
import freemarker.core._CoreAPI
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import freemarker.template.Template as FreemarkerTemplate

/**
 * Pins every assumption [FreemarkerAst] makes about the pinned Freemarker jar.
 *
 * The §4.2 AST scan is a security control built on `freemarker.core.TemplateElement`, which
 * Freemarker annotates `@Deprecated` — *"internal FreeMarker API with no backward compatibility
 * guarantees"*. That is an accepted, documented dependency (there is no supported alternative,
 * and the regex it replaced was bypassable), and this test is the other half of the bargain: a
 * jar bump that renames a node, changes what `getDescription()` prints, or stops escaping angle
 * brackets in string literals fails the build **here**, loudly, instead of silently disarming
 * the scan.
 *
 * Everything below is re-derived from the jar on every run. Nothing is asserted from memory.
 */
@Suppress("DEPRECATION") // freemarker.core.TemplateElement — see FreemarkerAst
class FreemarkerAstDriftTest {
    private fun parse(body: String): FreemarkerTemplate = (TemplateBodyParser.parse(body) as BodyParse.Parsed).template

    private fun rootTypeOf(body: String): String = FreemarkerAst.typeOf(parse(body).rootTreeNode)

    @Test
    fun `each node class name the scan matches on still names that node`() {
        // The scan identifies nodes by fully-qualified class name (several are package-private,
        // so `is Include` will not compile). If a name drifts, the corresponding check silently
        // matches nothing — which for INCLUDE and LIBRARY_LOAD would reopen the §4.2 hole.
        mapOf(
            FreemarkerAst.INCLUDE to "<#include \"y\">",
            FreemarkerAst.LIBRARY_LOAD to "<#import \"x@1\" as y>",
            FreemarkerAst.COMMENT to "<#-- c -->",
            FreemarkerAst.TEXT_BLOCK to "SELECT 1",
            FreemarkerAst.MACRO to "<#macro m>x</#macro>",
            FreemarkerAst.MIXED_CONTENT to "SELECT \${a} FROM t",
        ).forEach { (expected, body) ->
            withClue("$body must still parse to $expected") { rootTypeOf(body) shouldBe expected }
        }
    }

    @Test
    fun `getDescription prints the element's own expressions and not its children`() {
        // This is the property the whole scan rests on: an expression-level construct is visible
        // on the element that owns it. `TemplateObject.getParameterValue` — the expression-level
        // walk — is package-private in 2.3.x, so there is no other way to see `?eval`.
        val root = parse("<#if x?eval>CHILD-TEXT</#if>").rootTreeNode

        FreemarkerAst.ownText(root) shouldContain "?eval"
        withClue("children must be excluded, or every ancestor would re-report its subtree") {
            FreemarkerAst.ownText(root) shouldNotContain "CHILD-TEXT"
        }
    }

    @Test
    fun `a string literal cannot re-inject tag syntax into the scanned text`() {
        // Freemarker's canonical rendering escapes `<` and `>` inside a string literal (`\l`,
        // `\g`). That is precisely why the string-literal comment-marker bypass cannot come back:
        // the scanned text of `<#assign a="<#--">` contains no `<#--` at all.
        val assignment = FreemarkerAst.topLevelOf(parse("<#assign a=\"<#--\">\${x}<#assign b=\"-->\">").rootTreeNode).first()

        FreemarkerAst.ownText(assignment) shouldNotContain "<#--"
        FreemarkerAst.ownText(assignment) shouldContain "assign"
    }

    @Test
    fun `an ftl header produces no node - the blind spot that forces a source-level refusal`() {
        // Pins the jar behaviour the §4.2 v1.6 rule rests on: the parser CONSUMES the header, so
        // the tree looks identical with and without it. Nothing an AST walk can do will ever see
        // a header, which is why ForbiddenConstructScanner refuses it in scanSource instead.
        rootTypeOf("<#ftl encoding='UTF-8'>\n<#macro m>x</#macro>") shouldBe FreemarkerAst.MACRO
        rootTypeOf("<#ftl attributes={\"k\":\"1+1\"?eval}>OK") shouldBe FreemarkerAst.TEXT_BLOCK
    }

    @Test
    fun `macro and function are one node type, separated by isFunction`() {
        // LibraryBodyCheck accepts both; the distinction is available if the contract ever
        // narrows to macros only (see the §7-vs-§6.2 note there).
        (parse("<#macro m>x</#macro>").rootTreeNode as Macro).isFunction shouldBe false
        (parse("<#function f a><#return a></#function>").rootTreeNode as Macro).isFunction shouldBe true
    }

    @Test
    fun `child traversal still works through the typed accessor`() {
        val root = parse("SELECT \${a} FROM t").rootTreeNode

        FreemarkerAst.childrenOf(root).size shouldBe root.childCount
        _CoreAPI.getChildElement(root, 0).shouldNotBeNull()
    }

    @Test
    fun `the thread-interruption registration API is still present`() {
        // InterruptibleConfiguration depends on this static existing; without it a render
        // timeout cannot abort a running render at all (verified: plain interrupt does nothing).
        val template = parse("<#list 1..3 as i>\${i}</#list>")

        _CoreAPI.addThreadInterruptedChecks(template)

        withClue("post-processing must leave a usable tree") { template.rootTreeNode.shouldNotBeNull() }
    }
}
