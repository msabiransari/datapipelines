package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #713 — the h2-run section wrapper (`DocsCatalog.wrapDocParts`, rendered by
 * `DocsCatalog.DocPartRenderer` as `<section class="doc-part">`), which carries the
 * skip-render rule in `docs.css` so long documents stop laying out their off-screen bulk.
 *
 * The wrapper is a pure tree restructure, and these assertions hold it to that: the
 * heading ids it leaves in place are the anchor contract (`scripts/docs-audit.sh`,
 * `DocsLinkRewriteTest`, llms.txt, external deep links), so the id set must be byte-identical
 * to what the flat renderer emitted before it; each part opens exactly at an h2; the
 * pre-heading preamble (title, status block, first prose paragraph — the meta description's
 * home) stays outside any part; parts never nest; and the long documents that motivated the
 * change are actually wrapped (non-vacuity).
 */
class DocsPartWrappingTest {
    private val catalog = DocsCatalog(javaClass.classLoader)
    private val docs = catalog.index().flatMap { it.docs }.map { catalog.render(it.slug)!! }

    @Test
    fun `every doc body is wrapped into doc-part sections and the long documents most of all`() {
        val total = docs.sumOf { doc -> SECTION.findAll(doc.html).count() }
        withClue("the wrapper produced no sections — the renderer change is inert") {
            total.shouldBeGreaterThan(0)
        }
        val wrapped = docs.filter { doc -> SECTION.findAll(doc.html).count() >= LONG_DOC_PARTS }
        withClue("the long documents (auth, ui-screens, rest-api …) must be among the wrapped") {
            wrapped.map { it.entry.slug }.shouldNotBeEmpty()
        }
    }

    @Test
    fun `every part opens exactly at an h2 and the preamble stays unwrapped`() {
        docs.forEach { doc ->
            val html = doc.html
            SECTION.findAll(html).forEach { match ->
                val window = html.substring(match.range.first, html.length.coerceAtMost(match.range.first + 120))
                withClue("${doc.entry.slug}: a doc-part opens at ${match.range} not immediately before an h2") {
                    PART_BEFORE_H2.containsMatchIn(window).shouldBe(true)
                }
            }
            SECTION.findAll(html).count() shouldBe H2.findAll(html).count()
            val firstSection = html.indexOf(SECTION_OPEN)
            val firstH1 = html.indexOf("<h1")
            withClue("${doc.entry.slug}: the preamble (title and first paragraph) must precede the first part") {
                (firstH1 in 0 until firstSection).shouldBe(true)
            }
        }
    }

    @Test
    fun `heading ids are byte-identical to the flat renderer's anchor contract`() {
        docs.forEach { doc ->
            val idsInHtml = H_TAG_ID.findAll(doc.html).map { it.groupValues[1] }.toList()
            withClue("${doc.entry.slug}: rendered heading ids must equal the catalog's anchor set — a divergence 404s live anchors") {
                idsInHtml.toSet() shouldBe doc.anchors
                idsInHtml.size shouldBe doc.anchors.size
            }
        }
    }

    @Test
    fun `parts never nest and every part closes`() {
        docs.forEach { doc ->
            val html = doc.html
            html shouldContain "</section>"
            val depth =
                TAG.findAll(html).fold(0) { acc, m ->
                    val next = if (m.value == "</section>") acc - 1 else acc + 1
                    withClue("${doc.entry.slug}: section tags nest or close unmatched at ${m.range}") {
                        (next in 0..1).shouldBe(true)
                    }
                    next
                }
            withClue("${doc.entry.slug}: unclosed doc-part") {
                depth.shouldBe(0)
            }
        }
    }

    private companion object {
        val SECTION = Regex("""<section class="doc-part">""")
        const val SECTION_OPEN = """<section class="doc-part">"""
        val PART_BEFORE_H2 = Regex("""<section class="doc-part">\s*<h2 """)

        /** Any heading tag's id — the anchor contract. */
        val H_TAG_ID = Regex("""<h[1-6] id="([^"]+)"""")

        val H2 = Regex("""<h2 """)

        /** Section opens and closes, in document order — the nesting scan. */
        val TAG = Regex("""<section\b|</section>""")

        /** A document with at least this many h2-runs counts as "long" for non-vacuity. */
        const val LONG_DOC_PARTS = 10
    }
}
