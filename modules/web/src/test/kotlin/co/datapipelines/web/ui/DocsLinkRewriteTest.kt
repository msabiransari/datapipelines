package co.datapipelines.web.ui

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * 033 §A — the docs-link rule, asserted over the REAL packaged spec set.
 *
 * Every relative link in every packaged doc resolves to a packaged slug (`/docs/{slug}`)
 * or is rewritten to the canonical GitHub URL. BOTH branches must stay non-vacuous (§A2):
 * a bug that sends everything to GitHub — or everything in-app — passes a one-branch
 * assertion silently. And no rewritten link is empty or relative (§A2), fragments survive
 * the GitHub rewrite (§A3), and every anchor — in-page or cross-doc — resolves to an id
 * the target's rendered HTML actually emits.
 */
class DocsLinkRewriteTest {
    private val catalog = DocsCatalog(javaClass.classLoader)
    private val docs = catalog.index().flatMap { it.docs }.map { catalog.render(it.slug)!! }

    @Test
    fun `every relative link resolves in-app or to GitHub - both branches exercised, nothing dead`() {
        val hrefs = docs.flatMap { doc -> HREF.findAll(doc.html).map { it.groupValues[1] } }
        hrefs.shouldNotBeEmpty()

        val inApp = hrefs.filter { it.startsWith("/docs/") }
        val github = hrefs.filter { it.startsWith(DocsCatalog.GITHUB_BLOB_BASE) }
        val external = hrefs.filter { it.startsWith("http://") || it.startsWith("https://") } - github.toSet()
        val inPage = hrefs.filter { it.startsWith("#") }

        // The branches, both non-vacuous (§A2). Counts from the 2026-08-31 measurement at
        // c06dfd9: 31 non-packaged relative links across the 19 packaged docs.
        inApp.size shouldBeGreaterThan 100
        github.size shouldBeGreaterThan 25
        external.shouldNotBeEmpty()
        inPage.shouldNotBeEmpty()

        // Nothing empty, nothing relative, no dead .md href anywhere (§A2).
        val unaccounted =
            hrefs.filterNot {
                it.startsWith("/docs/") || it.startsWith("http://") || it.startsWith("https://") ||
                    it.startsWith("#") || it.startsWith("mailto:")
            }
        unaccounted shouldBe emptyList()
        docs.forEach { it.html shouldNotContain "href=\"\"" }
    }

    @Test
    fun `every anchor resolves - in-page and cross-doc - and ids are unique per doc`() {
        val failures = docs.flatMap { doc -> anchorFailures(doc, docs.associateBy { it.entry.slug }) }
        failures shouldBe emptyList()
    }

    /** The dead-anchor and duplicate-id findings of ONE rendered doc. */
    private fun anchorFailures(
        doc: DocsCatalog.RenderedDoc,
        bySlug: Map<String, DocsCatalog.RenderedDoc>,
    ): List<String> {
        val failures = mutableListOf<String>()
        val ids = ID.findAll(doc.html).map { it.groupValues[1] }.toList()
        if (ids.size != ids.toSet().size) failures += "${doc.entry.slug}: duplicate heading ids emitted"

        HREF.findAll(doc.html).forEach { m ->
            val href = m.groupValues[1]
            when {
                href.startsWith("#") -> {
                    if (href.removePrefix("#") !in doc.anchors) {
                        failures += "${doc.entry.slug}: dead in-page anchor $href"
                    }
                }

                href.startsWith("/docs/") -> {
                    val slug = href.removePrefix("/docs/").substringBefore('#')
                    val target = bySlug[slug]
                    if (target == null) {
                        failures += "${doc.entry.slug}: /docs/$slug is not a packaged slug"
                    } else if ('#' in href && href.substringAfter('#') !in target.anchors) {
                        failures += "${doc.entry.slug}: dead cross-doc anchor $href"
                    }
                }
            }
        }
        return failures
    }

    @Test
    fun `the rewrite rule itself - packaged slug, GitHub fallback, fragments preserved`() {
        val packaged = setOf("docs/rest-api.md", "docs/auth.md")

        // Packaged target → in-app route, fragment carried (§A1 branch one).
        DocsCatalog.rewriteHref("rest-api.md#6-sse-stream", packaged) shouldBe "/docs/rest-api#6-sse-stream"
        // Non-packaged target → canonical GitHub URL, fragment carried (§A1 branch two, §A3).
        DocsCatalog.rewriteHref("SPEC-REVIEW-2026-08.md#217-type-systemmd", packaged) shouldBe
            "${DocsCatalog.GITHUB_BLOB_BASE}docs/SPEC-REVIEW-2026-08.md#217-type-systemmd"
        // Outside docs/ entirely → repo-root-relative GitHub URL.
        DocsCatalog.rewriteHref("../DEVELOPMENT.md", packaged) shouldBe "${DocsCatalog.GITHUB_BLOB_BASE}DEVELOPMENT.md"
        DocsCatalog.rewriteHref("../deploy/.env.example", packaged) shouldBe "${DocsCatalog.GITHUB_BLOB_BASE}deploy/.env.example"
        // Passthroughs: absolute URLs, in-page anchors, mailto.
        DocsCatalog.rewriteHref("https://example.com/x", packaged) shouldBe "https://example.com/x"
        DocsCatalog.rewriteHref("#local-anchor", packaged) shouldBe "#local-anchor"
        DocsCatalog.rewriteHref("mailto:a@b.c", packaged) shouldBe "mailto:a@b.c"
    }

    private companion object {
        val HREF = Regex("""href="([^"]*)"""")
        val ID = Regex("""id="([^"]+)"""")
    }
}
