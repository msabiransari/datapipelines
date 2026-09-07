package co.datapipelines.templates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The 042 B2 scan, pinned against the same jar the production walk uses: which declared
 * parameter names a body interpolates inside `${}`, with scope shadowing honoured.
 */
class InterpolatedParameterScannerTest {
    private fun scan(
        body: String,
        declared: Set<String>,
    ): List<String> = InterpolatedParameterScanner.scan(body, declared)

    @Test
    fun `a bare interpolation of a declared name is reported`() {
        scan("SELECT * FROM t WHERE id = \${customer_id}", setOf("customer_id")) shouldBe listOf("customer_id")
    }

    @Test
    fun `an interpolation with a builtin is reported`() {
        scan("SELECT \${start_date?string(\"yyyy-MM-dd\")}", setOf("start_date")) shouldBe listOf("start_date")
    }

    @Test
    fun `a compound expression reports every declared name it references`() {
        scan(
            "SELECT \${min_total + max_total}",
            setOf("min_total", "max_total", "unused"),
        ) shouldBe listOf("min_total", "max_total")
    }

    @Test
    fun `a directive test is not an interpolation and is not reported`() {
        scan("<#if customer_id gt 0>WHERE id = :customer_id</#if>", setOf("customer_id")) shouldBe emptyList()
    }

    @Test
    fun `the bind form is not an interpolation and is not reported`() {
        scan("SELECT * FROM t WHERE id = :customer_id", setOf("customer_id")) shouldBe emptyList()
    }

    @Test
    fun `a dotted access cannot reach a flat declared parameter and is not reported`() {
        scan("SELECT \${user.customer_id}", setOf("customer_id")) shouldBe emptyList()
    }

    @Test
    fun `a macro parameter shadows the declared name inside the macro body`() {
        scan(
            "<#macro m customer_id>SELECT \${customer_id}</#macro>",
            setOf("customer_id"),
        ) shouldBe emptyList()
    }

    @Test
    fun `a function parameter shadows the declared name inside the function body`() {
        scan(
            "<#function f customer_id>SELECT \${customer_id}</#function>",
            setOf("customer_id"),
        ) shouldBe emptyList()
    }

    @Test
    fun `a macro parameter with a default also shadows`() {
        scan(
            "<#macro m customer_id=3>SELECT \${customer_id}</#macro>",
            setOf("customer_id"),
        ) shouldBe emptyList()
    }

    @Test
    fun `outside the macro the declared name is reported again`() {
        scan(
            "<#macro m customer_id>SELECT \${customer_id}</#macro>SELECT \${customer_id}",
            setOf("customer_id"),
        ) shouldBe listOf("customer_id")
    }

    @Test
    fun `a loop variable shadows the declared name inside the list body`() {
        scan(
            "<#list rows as customer_id>\${customer_id}</#list>",
            setOf("customer_id"),
        ) shouldBe emptyList()
    }

    @Test
    fun `an interpolation after an assign of the same name is refused - the safe direction`() {
        // Deliberate: the assigned value could itself have been copied from the parameter, so
        // exempting the sibling interpolation would admit an indirection (scanner KDoc).
        scan(
            "<#if c><#assign customer_id = 5></#if>SELECT \${customer_id}",
            setOf("customer_id"),
        ) shouldBe listOf("customer_id")
    }

    @Test
    fun `an interpolation after a local of the same name is refused - the safe direction`() {
        scan(
            "<#macro m><#local customer_id = 5></#macro>SELECT \${customer_id}",
            setOf("customer_id"),
        ) shouldBe listOf("customer_id")
    }

    @Test
    fun `an escaped-looking interpolation is still a live interpolation and is reported`() {
        // Pinned against 2.3.34 (FreemarkerAstDriftTest): `\${x}` renders `\42` — the backslash
        // is literal text and the interpolation executes, so there is no spelling that hides a
        // live interpolation from the tree.
        scan("SELECT \\\${customer_id}", setOf("customer_id")) shouldBe listOf("customer_id")
    }

    @Test
    fun `a string literal equal to a declared name is reported - the accepted false positive`() {
        // The parser prints string literals inside the expression, so `${"customer_id"}` looks
        // like a use. Over-refusal is the safe direction for the injection guard.
        scan("SELECT \${\"customer_id\"}", setOf("customer_id")) shouldBe listOf("customer_id")
    }

    @Test
    fun `undeclared names are never reported`() {
        scan("SELECT \${undeclared} FROM t", setOf("customer_id")) shouldBe emptyList()
    }

    @Test
    fun `an empty declaration set scans nothing`() {
        scan("SELECT \${customer_id}", emptySet()) shouldBe emptyList()
    }

    @Test
    fun `a body that does not parse reports nothing - the syntax check owns that failure`() {
        scan("SELECT \${ FROM ", setOf("customer_id")) shouldBe emptyList()
    }

    @Test
    fun `reports are in first-use order`() {
        scan("SELECT \${b}, \${a}, \${b}", setOf("a", "b")) shouldBe listOf("b", "a")
    }

    // ---- 078 A1: the guarded set (CALCULATOR output keys) — refused in conditionals too ----

    private fun scanGuarded(
        body: String,
        guarded: Set<String>,
    ): List<String> = InterpolatedParameterScanner.scan(body, emptySet(), guarded)

    @Test
    fun `a bare interpolation of a guarded name is reported`() {
        scanGuarded("SELECT * FROM t WHERE q = \${run_fiscal_quarter}", setOf("run_fiscal_quarter")) shouldBe
            listOf("run_fiscal_quarter")
    }

    @Test
    fun `an interpolation with the default operator of a guarded name is reported`() {
        // `${x!}` is the shape that PASSED save before A1: the dry render tolerates the missing
        // variable behind the default, so only the scan can refuse it.
        scanGuarded("SELECT \${run_fiscal_quarter!}", setOf("run_fiscal_quarter")) shouldBe listOf("run_fiscal_quarter")
    }

    @Test
    fun `an if test on a guarded name is reported`() {
        scanGuarded("<#if run_fiscal_quarter??>SELECT 1</#if>", setOf("run_fiscal_quarter")) shouldBe
            listOf("run_fiscal_quarter")
    }

    @Test
    fun `an elseif test on a guarded name is reported`() {
        scanGuarded("<#if a>SELECT 1<#elseif run_fiscal_quarter??>SELECT 2</#if>", setOf("run_fiscal_quarter")) shouldBe
            listOf("run_fiscal_quarter")
    }

    @Test
    fun `an if test on a declared-only name stays legal - 042 B1 is not re-litigated`() {
        InterpolatedParameterScanner.scan("<#if customer_id??>SELECT 1</#if>", setOf("customer_id"), emptySet()) shouldBe
            emptyList()
    }

    @Test
    fun `a macro parameter shadows a guarded name inside the macro body`() {
        scanGuarded(
            "<#macro m run_fiscal_quarter><#if run_fiscal_quarter??>\${run_fiscal_quarter}</#if></#macro>",
            setOf("run_fiscal_quarter"),
        ) shouldBe emptyList()
    }

    @Test
    fun `a guarded name in an unparseable condition reports nothing - the syntax check owns that failure`() {
        scanGuarded("<#if run_fiscal_quarter>SELECT ${'$'}{</#if>", setOf("run_fiscal_quarter")) shouldBe emptyList()
    }
}
