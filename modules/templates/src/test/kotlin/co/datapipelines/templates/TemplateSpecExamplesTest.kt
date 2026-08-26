package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateRef
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The spec's own worked examples, executed (templates.md Appendix A, §6.2, §6.3).
 *
 * §12.3 asks for "round-trip tests: every sample template in `docs/examples/` must parse,
 * validate, and resolve its import closure". **That directory does not exist in this repo** —
 * verified this session — so the samples used here are the ones the spec itself writes down,
 * quoted rather than paraphrased. They are the better fixture anyway: if the implementation and
 * Appendix A ever disagree about what `monthly_revenue.sql` renders to, this fails.
 *
 * The chain asserted end to end is exactly the spec's: library validates → importer validates →
 * import closure resolves → render produces Appendix A's SQL, `WHERE r.total >= 1000.00` and all
 * — the `?c` defect this test originally surfaced was fixed in the doc at v1.3, so the example is
 * now asserted verbatim apart from the presentational whitespace noted on [EXPECTED_SQL].
 */
class TemplateSpecExamplesTest {
    private val workspaceId = java.util.UUID.randomUUID()
    private val engines = mutableListOf<TemplateEngine>()

    @AfterEach
    fun tearDown() = engines.forEach { it.close() }

    private fun engine(registry: TemplateRegistry): TemplateEngine =
        TemplateEngine(registry, cacheSize = 50, renderTimeoutMs = 5_000, maxOutputChars = 1_000_000).also { engines += it }

    @Test
    fun `the Appendix A library validates as a library`() {
        val registry = InMemoryTemplateRegistry()
        val validator = TemplateValidator(LibraryResolver { _ -> registry })

        validator
            .validate(TemplateFixtures.draft(id = "lib_aggregate.sql", isLibrary = true, body = LIB_AGGREGATE), workspaceId)
            .isValid
            .shouldBeTrue()
    }

    @Test
    fun `the Appendix A template validates, resolves its closure, and renders the documented SQL`() {
        val registry =
            InMemoryTemplateRegistry(
                listOf(TemplateFixtures.version("lib_aggregate.sql", isLibrary = true, body = LIB_AGGREGATE)),
            )
        val draft =
            TemplateFixtures.draft(
                id = "monthly_revenue.sql",
                imports = listOf(TemplateImport("lib_aggregate.sql", 1, "agg")),
                body = MONTHLY_REVENUE,
            )

        TemplateValidator(LibraryResolver { _ -> registry }).validate(draft, workspaceId).isValid.shouldBeTrue()

        registry.put(
            TemplateFixtures.version(
                "monthly_revenue.sql",
                version = 3,
                imports = draft.imports,
                body = draft.body,
            ),
        )
        val sql =
            engine(registry).render(TemplateRef("monthly_revenue.sql", 3), mapOf("min_total" to BigDecimal("1000.00")))

        sql shouldBe EXPECTED_SQL
    }

    @Test
    fun `a BIGDECIMAL renders at its declared scale, as section 4-4 requires`() {
        // §4.4's normative table: BIGDECIMAL(p,s) renders as a "plain decimal string with declared
        // scale" — the guarantee Appendix A now relies on directly, having dropped `?c` at v1.3.
        // Kept as its own test so the property is pinned independently of the worked example.
        val registry = InMemoryTemplateRegistry(listOf(TemplateFixtures.version("scale.sql", body = "\${min_total}")))

        engine(registry).render(TemplateRef("scale.sql", 1), mapOf("min_total" to BigDecimal("1000.00"))) shouldBe "1000.00"
    }

    @Test
    fun `two libraries may share a macro name because aliases namespace them`() {
        // templates.md §6.4: "Two libraries may share macro names as long as their aliases differ —
        // that is what namespacing is for." Asserted at render, where a collision would actually bite.
        val pg = TemplateFixtures.version("lib_pg.sql", isLibrary = true, body = "<#macro quote v>\"\${v}\"</#macro>")
        val my = TemplateFixtures.version("lib_my.sql", isLibrary = true, body = "<#macro quote v>`\${v}`</#macro>")
        val main =
            TemplateFixtures.version(
                "both.sql",
                imports = listOf(TemplateImport("lib_pg.sql", 1, "pg"), TemplateImport("lib_my.sql", 1, "my")),
                body = "<@pg.quote v=\"a\"/> <@my.quote v=\"a\"/>",
            )
        val registry = InMemoryTemplateRegistry(listOf(pg, my, main))

        engine(registry).render(TemplateRef("both.sql", 1), emptyMap()) shouldBe "\"a\" `a`"
    }

    @Test
    fun `two versions of one library on the same path are a legal DAG, not a cycle`() {
        // §6.4 pins imports to an exact {id, version}: lib@1 and lib@2 are different keys, so a
        // closure containing both is not a cycle. The cycle check keys on {id, version} for exactly
        // this reason, and this test is what stops it being "simplified" to an id-only check.
        val v1 = TemplateFixtures.version("lib.sql", version = 1, isLibrary = true, body = "<#macro m>1</#macro>")
        val v2 =
            TemplateFixtures.version(
                "lib.sql",
                version = 2,
                isLibrary = true,
                imports = listOf(TemplateImport("lib.sql", 1, "older")),
                body = "<#macro m><@older.m/>2</#macro>",
            )
        val registry = InMemoryTemplateRegistry(listOf(v1, v2))
        val draft = TemplateFixtures.draft(imports = listOf(TemplateImport("lib.sql", 2, "newer")))

        TemplateValidator(LibraryResolver { _ -> registry }).validate(draft, workspaceId).isValid.shouldBeTrue()
    }

    private companion object {
        /** templates.md Appendix A, `lib_aggregate.sql` v1 — quoted, not paraphrased. */
        val LIB_AGGREGATE =
            """
            <#macro sum_by group_by_column value_column table>
              SELECT
                ${'$'}{group_by_column} AS group_key,
                SUM(${'$'}{value_column}) AS total
              FROM ${'$'}{table}
              GROUP BY ${'$'}{group_by_column}
            </#macro>

            <#macro count_by group_by_column table>
              SELECT
                ${'$'}{group_by_column} AS group_key,
                COUNT(*) AS record_count
              FROM ${'$'}{table}
              GROUP BY ${'$'}{group_by_column}
            </#macro>
            """.trimIndent()

        /** templates.md Appendix A, `monthly_revenue.sql` v3 — no `<#import>` in the body (D12). */
        val MONTHLY_REVENUE =
            """
            WITH revenue AS (
            <@agg.sum_by
                 group_by_column="customer_id"
                 value_column="total_amount"
                 table="stg_orders" />
            )
            SELECT
              c.customer_name,
              r.total AS revenue,
              r.total / 100.0 AS revenue_display
            FROM revenue r
            JOIN stg_customers c ON r.group_key = c.customer_id
            WHERE r.total >= ${'$'}{min_total}
            ORDER BY r.total DESC
            """.trimIndent()

        /**
         * Appendix A's "Rendered SQL" — now matching the documented value exactly, `1000.00`
         * included.
         *
         * v1.3 corrected the worked example's body from `${'$'}{min_total?c}` to `${'$'}{min_total}` after
         * this test established that `?c` bypasses `numberFormat` for the `CFormat` and drops the
         * declared scale §4.4 promises. The spec and the engine now agree, so the assertion no
         * longer carries an exemption for the number.
         *
         * One departure from the block as printed remains, and it is presentational: a macro call
         * reproduces its body verbatim while the spec's block is prose-formatted, so the assertion
         * is on the whitespace the quoted macro actually emits.
         */
        val EXPECTED_SQL =
            """
            WITH revenue AS (
              SELECT
                customer_id AS group_key,
                SUM(total_amount) AS total
              FROM stg_orders
              GROUP BY customer_id
            )
            SELECT
              c.customer_name,
              r.total AS revenue,
              r.total / 100.0 AS revenue_display
            FROM revenue r
            JOIN stg_customers c ON r.group_key = c.customer_id
            WHERE r.total >= 1000.00
            ORDER BY r.total DESC
            """.trimIndent()
    }
}
