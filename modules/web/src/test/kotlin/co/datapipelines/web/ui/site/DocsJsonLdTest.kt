package co.datapipelines.web.ui.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The public doc pages' structured data (173 §C.4), PARSED off the render the way
 * [SiteJsonLdTest] parses the homepage: every packaged doc carries a `TechArticle` whose
 * headline is the doc's title and whose url is its canonical, and a `BreadcrumbList` of
 * Docs → the page. Read off the render because Thymeleaf sits between [DocJsonLd] and the
 * wire — a `th:utext` slot that stopped being one would escape the JSON into text.
 */
class DocsJsonLdTest {
    private val entries = SitePageRenderer.docs.index().flatMap { it.docs }

    @Test
    fun `every public doc page publishes a valid TechArticle and a BreadcrumbList`() {
        entries.size shouldBeGreaterThanOrEqual MIN_DOCS
        val bad =
            entries.flatMap { entry ->
                val blocks = blocks(SitePageRenderer.renderDoc(entry.slug))
                val canonical = "$SITE_ORIGIN/docs/${entry.slug}"
                val article = blocks.firstOrNull { it["@type"]?.asText() == "TechArticle" }
                val crumbs = blocks.firstOrNull { it["@type"]?.asText() == "BreadcrumbList" }?.get("itemListElement")
                listOfNotNull(
                    if (article == null) "${entry.slug}: no TechArticle" else null,
                    if (article?.get("headline")?.asText() != entry.title) "${entry.slug}: headline is not the title" else null,
                    if (article?.get("url")?.asText() != canonical) "${entry.slug}: url is not the canonical" else null,
                    if (article?.get("description")?.asText() != entry.description) "${entry.slug}: description drifted" else null,
                    if (crumbs == null || crumbs.size() != 2) "${entry.slug}: breadcrumbs are not Docs → page" else null,
                    if (crumbs?.get(0)?.get("item")?.asText() != "$SITE_ORIGIN/docs") "${entry.slug}: first crumb is not /docs" else null,
                    if (crumbs?.get(1)?.get("item")?.asText() != canonical) "${entry.slug}: last crumb is not the page" else null,
                )
            }
        withClue(bad.joinToString("\n")) { bad.shouldBeEmpty() }
    }

    @Test
    fun `a title with a quote or a closing tag cannot break the block`() {
        val rendered = DocJsonLd.render("""Auth "quoted" </script>""", "d", "$SITE_ORIGIN/docs/x")
        rendered.size shouldBe 2
        rendered.forEach { block ->
            block.contains("</") shouldBe false
            MAPPER.readTree(block)["@context"].asText() shouldBe "https://schema.org"
        }
        MAPPER.readTree(rendered[0])["headline"].asText() shouldBe """Auth "quoted" </script>"""
    }

    private fun blocks(html: String): List<JsonNode> = LD_JSON.findAll(html).map { MAPPER.readTree(it.groupValues[1]) }.toList()

    private companion object {
        const val MIN_DOCS = 15
        val MAPPER = ObjectMapper()
        val LD_JSON = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    }
}
