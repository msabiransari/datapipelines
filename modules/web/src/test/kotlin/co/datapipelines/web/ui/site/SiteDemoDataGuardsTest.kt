package co.datapipelines.web.ui.site

import co.datapipelines.web.TestRepoFiles
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The demo-data page's four guards (116 §A.3) — the tools page's completeness rule applied
 * to the published sample-data manifests, so a repack that moves without the page fails the
 * build:
 *
 *  1. **Pin agreement** — each vendored manifest's `version` is exactly the version
 *     `deploy/env/defaults.env` pins for its family. A repack that bumps the pin and
 *     forgets to re-vendor the manifest fails here, and so does the reverse.
 *  2. **Completeness** — every `tables[].table` in the vendored manifests appears on the
 *     rendered page, and nothing else does. The rows are generated, and this is the proof
 *     they stayed so.
 *  3. **Derived row counts** — the number rendered for each table is that manifest row's
 *     count, formatted; and NEITHER the formatted nor the raw number appears as a literal
 *     in the template source. A typed row count would be wrong after the next repack; a
 *     rendered one cannot be.
 *  4. **Non-vacuity** — at least three families with at least three tables each are
 *     rendered, each carrying its verified-licence stamp. A parse that silently found
 *     nothing would otherwise pass every check above by comparing nothing.
 *
 * Both falsifiable arms were falsified at birth (116 §A.3): editing the vendored nyc
 * version turned arm 1 red; filtering a table out of the template's `th:each` turned arm 2
 * red — both reverted by the inverse edit.
 */
class SiteDemoDataGuardsTest {
    private val rendered: String by lazy { SitePageRenderer.render(SitePages.DEMO_DATA) }
    private val model: SiteDemoData by lazy { SiteDemoData(javaClass.classLoader) }

    @Test
    fun `each vendored manifest is the version the deploy pins`() {
        val defaults = TestRepoFiles.read(DEPLOY_DEFAULTS)
        PINS.forEach { (family, envKey) ->
            val pinned =
                Regex("""(?m)^\s*$envKey=(\S+)\s*$""")
                    .find(defaults)
                    ?.groupValues
                    ?.get(1)
                    ?: error("$envKey not found in $DEPLOY_DEFAULTS")
            val vendored = model.family(family).version
            withClue("family $family: defaults.env pins $envKey=$pinned, vendored manifest says $vendored") {
                vendored shouldBe pinned
            }
        }
    }

    @Test
    fun `every manifest table renders, and no other table does`() {
        val renderedNames = ROW.findAll(rendered).map { it.groupValues[1] }.toList()
        val manifestNames = model.families.flatMap { it.tables }.map { it.table }
        withClue("rendered table names (the code cells of the generated rows)") {
            renderedNames.sorted() shouldBe manifestNames.sorted()
        }
    }

    @Test
    fun `each rendered row count is the manifest's number, formatted`() {
        val renderedRows = ROW.findAll(rendered).map { it.groupValues[1] to it.groupValues[2] }.toMap()
        val source = TestRepoFiles.read(TEMPLATE)
        model.families.flatMap { it.tables }.forEach { t ->
            val renderedCount =
                withClue("table ${t.table}: no rendered row count") { renderedRows[t.table].shouldNotBeNull() }
            withClue("table ${t.table}: rendered '$renderedCount', manifest ${t.rowCountFormatted}") {
                renderedCount shouldBe t.rowCountFormatted
            }
            // Derived, never transcribed: the formatted count may not exist as a literal in
            // the template source. (Raw counts are only checked where formatting makes them
            // distinguishable — a 5-row table's raw count is any prose digit.)
            if (t.rowCountFormatted != t.rowCount.toString()) {
                withClue("table ${t.table}: its raw count is typed into the template source") {
                    source.contains(t.rowCount.toString()) shouldBe false
                }
            }
        }
        // Structural derivation: every count cell binds the model's formatted value — a
        // hand-typed cell has no binding to find. One cell per family table.
        BINDINGS
            .findAll(source)
            .map { it.groupValues[1] }
            .toList() shouldBe listOf("rowCountFormatted", "rowCountFormatted", "rowCountFormatted")
    }

    @Test
    fun `three families, at least three tables each, and each verified date on the page`() {
        model.families.size shouldBe 3
        model.families.forEach { f ->
            withClue("family ${f.key}: only ${f.tables.size} tables in its manifest") {
                f.tables.size shouldBeGreaterThanOrEqual MIN_TABLES_PER_FAMILY
            }
            val stamp = f.licenseVerified
            withClue("family ${f.key}: no licence stamp in its manifest") { stamp.shouldNotBeNull() }
            withClue("family ${f.key}: its verified stamp is not on the rendered page") {
                rendered shouldContain "Licence verified $stamp"
            }
        }
        withClue("the page renders fewer rows than three families of three tables") {
            ROW.findAll(rendered).count().shouldBeGreaterThanOrEqual(3 * MIN_TABLES_PER_FAMILY)
        }
    }

    private companion object {
        const val DEPLOY_DEFAULTS = "deploy/env/defaults.env"
        const val TEMPLATE = "modules/web/src/main/resources/templates/site/demo-data.html"
        const val MIN_TABLES_PER_FAMILY = 3

        /** defaults.env's variable per family key — nyc's has no infix (SAMPLE_VERSION). */
        val PINS: List<Pair<String, String>> =
            listOf(
                "nyc" to "SAMPLE_VERSION",
                "trade" to "SAMPLE_TRADE_VERSION",
                "lake" to "SAMPLE_LAKE_VERSION",
            )

        /**
         * One rendered table row: the generated `<code>` name cell, then the generated
         * `demo-rows` count cell — the two cells the manifest owns, whatever static prose
         * (grain, key columns) sits between them.
         */
        val ROW =
            Regex(
                """<td><code>([a-z0-9_]+)</code></td>(?:(?!</tr>).)*?<td class="demo-rows">([\d,]+)</td>""",
                RegexOption.DOT_MATCHES_ALL,
            )

        /** A count cell's binding in the source — the proof the cell renders, not states. */
        val BINDINGS = Regex("""<td class="demo-rows" th:text="\$\{t\.([a-zA-Z]+)\}">""")
    }
}
