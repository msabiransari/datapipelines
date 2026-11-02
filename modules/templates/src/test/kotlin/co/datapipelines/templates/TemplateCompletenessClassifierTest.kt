package co.datapipelines.templates

import co.datapipelines.templates.TemplateCompletenessClassifier.Kind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The completeness classifier of the reference scanner (#778, slice D-A): an EXACT body's one render is every table
 * position it has; each construct that can change the rendered text makes it PARTIAL and is named by its kind. The
 * node-class names it matches beyond [FreemarkerAst]'s pinned set (`SwitchBlock`, `Case`, `Items`, `Sep`) are pinned
 * here by parsing a body that must produce them.
 */
class TemplateCompletenessClassifierTest {
    private fun classify(
        body: String,
        imports: Boolean = false,
    ) = TemplateCompletenessClassifier.classify(body, imports)

    @Test
    fun `a literal body is EXACT`() {
        val verdict = classify("SELECT id, fare FROM lake.sales.orders WHERE day = :day ORDER BY id")
        verdict.completeness shouldBe TemplateCompleteness.EXACT
        verdict.findings.shouldBeEmpty()
    }

    @Test
    fun `comments, assignments and uncalled macro definitions keep a body EXACT`() {
        val body =
            """
            <#-- ${'$'}{ignored} <#if x>FROM a.b</#if> -->
            <#assign limit = 10>
            <#macro unused>FROM ${'$'}{t}</#macro>
            SELECT * FROM a.b
            """.trimIndent()
        classify(body).completeness shouldBe TemplateCompleteness.EXACT
    }

    @Test
    fun `an if around the table is PARTIAL and names the directive`() {
        val verdict = classify("SELECT * FROM <#if region == 'eu'>a.x<#else>b.y</#if>")
        verdict.completeness shouldBe TemplateCompleteness.PARTIAL
        verdict.findings.map { it.kind } shouldContain Kind.IF
    }

    @Test
    fun `an imported library is PARTIAL even before the body calls it`() {
        val verdict = classify("SELECT * FROM a.b", imports = true)
        verdict.completeness shouldBe TemplateCompleteness.PARTIAL
        verdict.findings.map { it.kind } shouldContain Kind.IMPORT
    }

    @Test
    fun `a syntax error is PARTIAL, never EXACT`() {
        classify("SELECT * FROM <#if>").findings.map { it.kind } shouldContain Kind.SYNTAX_ERROR
    }

    data class Construct(
        val body: String,
        val kind: String,
    ) {
        override fun toString() = kind
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructs")
    fun `each output-changing construct makes the body PARTIAL with its kind`(construct: Construct) {
        val verdict = classify(construct.body)
        verdict.completeness shouldBe TemplateCompleteness.PARTIAL
        verdict.findings.map { it.kind } shouldContain construct.kind
    }

    /**
     * The falsification of the classifier (brief Gates): a body with an `<#if>` read with the classifier switched off
     * — every body EXACT — is the wrong answer this test refuses; with it on, PARTIAL.
     */
    @Test
    fun `with the classifier disabled an if body would read EXACT - the gate's red arm`() {
        val body = "SELECT * FROM <#if region == 'eu'>a.x<#else>b.y</#if>"
        val disabled = TemplateClassification(TemplateCompleteness.EXACT, emptyList())
        (disabled.completeness == classify(body).completeness) shouldBe false
    }

    companion object {
        @JvmStatic
        fun constructs(): List<Construct> =
            listOf(
                Construct("<#assign t = 'trips'>SELECT * FROM ns.${'$'}{t}", Kind.INTERPOLATION),
                Construct("SELECT * FROM <#if x>a.b<#elseif y>c.d<#else>e.f</#if>", Kind.IF),
                Construct("SELECT * FROM <#switch r><#case 'eu'>a.x<#break><#default>b.y</#switch>", Kind.SWITCH),
                Construct("SELECT * FROM a.x WHERE id IN (<#list ids as i>:p${'$'}{i?index}<#sep>,</#list>)", Kind.LIST),
                Construct("<#list ids><#items as i>${'$'}{i}</#items></#list>", Kind.LIST),
                Construct("<#macro m>FROM a.x</#macro>SELECT * <@m/>", Kind.MACRO_CALL),
                Construct("<#compress>SELECT * FROM a.x</#compress>", "${Kind.DIRECTIVE}:CompressedBlock"),
            )
    }
}
