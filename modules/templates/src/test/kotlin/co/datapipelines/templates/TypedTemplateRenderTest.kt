package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The `html` acceptance bar of 046 (template-hierarchy-design §2/§6/§12.4) — the ENTIRE bar
 * for this round: schema (TypedTemplatesMigrationTest), the second engine configuration, and
 * a render proving escaping. No serving endpoint, no CSP work, no dashboard surface.
 *
 * The escape probe is the design's own: `${"<script>"}` becomes `&lt;script&gt;` through the
 * `html` configuration and does not through `sql`. Both halves matter — a regression that
 * escapes SQL would corrupt every executed query as surely as one that fails to escape HTML.
 */
class TypedTemplateRenderTest {
    private val engines = mutableListOf<TemplateEngine>()

    @AfterEach
    fun tearDown() = engines.forEach { it.close() }

    private fun engine(vararg versions: TemplateVersion): TemplateEngine =
        TemplateEngine(InMemoryTemplateRegistry(versions.toList()), 50, 5_000, 1_000_000).also { engines += it }

    @Test
    fun `html escapes interpolations by default and sql does not`() {
        val html =
            engine(
                TemplateFixtures.version(
                    "report.html",
                    type = TemplateType.HTML,
                    dialect = null,
                    body = "<p title=\${title}>\${user_value}</p>",
                ),
            )
        val sql =
            engine(
                TemplateFixtures.version(
                    "report.sql",
                    body = "SELECT '<script>' AS marker WHERE title = \${title}",
                ),
            )

        assertSoftly {
            html.render(TemplateRef("report.html", 1), mapOf("user_value" to "<script>", "title" to "a&b")) shouldBe
                "<p title=a&amp;b>&lt;script&gt;</p>"

            sql.render(
                TemplateRef("report.sql", 1),
                mapOf("title" to "a&b"),
            ) shouldBe "SELECT '<script>' AS marker WHERE title = a&b"
        }
    }

    @Test
    fun `markup requires an explicit no_esc in html`() {
        val e =
            engine(
                TemplateFixtures.version(
                    "row.html",
                    type = TemplateType.HTML,
                    dialect = null,
                    body = "<td>\${cell}</td><td>\${cell?no_esc}</td>",
                ),
            )
        e.render(TemplateRef("row.html", 1), mapOf("cell" to "<b>bold</b>")) shouldBe
            "<td>&lt;b&gt;bold&lt;/b&gt;</td><td><b>bold</b></td>"
    }

    @Test
    fun `the dispatch is by the version's type - one engine, both configurations`() {
        // Same body, same key shape, two templates differing only in type: one engine routes
        // each through its own configuration. This is the §6 selection rule — and the guard
        // against an html body ever reaching the escaping-free configuration by omission.
        val both =
            engine(
                TemplateFixtures.version("plain.sql", body = "\${value}"),
                TemplateFixtures.version("plain.html", type = TemplateType.HTML, dialect = null, body = "\${value}"),
            )
        assertSoftly {
            both.render(TemplateRef("plain.sql", 1), mapOf("value" to "<i>x</i>")) shouldBe "<i>x</i>"
            both.render(TemplateRef("plain.html", 1), mapOf("value" to "<i>x</i>")) shouldBe "&lt;i&gt;x&lt;/i&gt;"
        }
    }

    @Test
    fun `imports stay type-agnostic - an html template calls a library macro`() {
        // §6: the loader and the synthesized prologue are shared; only the output format
        // differs. An html template may import any library it is authorised to see.
        val lib =
            TemplateFixtures.version(
                "lib_fmt.sql",
                isLibrary = true,
                body = "<#macro badge text><span>\${text}</span></#macro>",
            )
        val page =
            TemplateFixtures.version(
                "page.html",
                type = TemplateType.HTML,
                dialect = null,
                imports = listOf(TemplateImport("lib_fmt.sql", 1, "fmt")),
                body = "<@fmt.badge text=user_label/>",
            )
        engine(lib, page).render(TemplateRef("page.html", 1), mapOf("user_label" to "<script>")) shouldBe
            "<span>&lt;script&gt;</span>"
    }
}
