package co.datapipelines.web.ui.site

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * The structured data of one public doc page (173 §C.4): a `TechArticle` (the doc's title,
 * description and canonical address) and a `BreadcrumbList` (Docs → the page), written the
 * way [FaqJsonLd] writes the FAQ block — from Kotlin, through Jackson, into a `th:utext`
 * slot — so a title with a quote or an angle bracket cannot break the JSON at serve time.
 * `DocsJsonLdTest` parses the render back the way `SiteJsonLdTest` parses the homepage.
 */
object DocJsonLd {
    private val mapper: ObjectMapper = JsonMapper.builder().build()

    /** Two `application/ld+json` bodies, article first, ready for two script tags. */
    fun render(
        title: String,
        description: String,
        canonical: String,
    ): List<String> {
        val article =
            linkedMapOf(
                "@context" to SCHEMA_ORG,
                "@type" to "TechArticle",
                "headline" to title,
                "description" to description,
                "url" to canonical,
                "mainEntityOfPage" to canonical,
                "publisher" to linkedMapOf("@type" to "Organization", "name" to SITE_NAME, "url" to "$SITE_ORIGIN/"),
            )
        val breadcrumbs =
            linkedMapOf(
                "@context" to SCHEMA_ORG,
                "@type" to "BreadcrumbList",
                "itemListElement" to
                    listOf(
                        crumb(1, "Documentation", "$SITE_ORIGIN/docs"),
                        crumb(2, title, canonical),
                    ),
            )
        return listOf(article, breadcrumbs).map { escapeForScript(mapper.writeValueAsString(it)) }
    }

    private fun crumb(
        position: Int,
        name: String,
        item: String,
    ): Map<String, Any> = linkedMapOf("@type" to "ListItem", "position" to position, "name" to name, "item" to item)

    /** The same one rule [FaqJsonLd] applies: a closing-tag sequence cannot appear inside a script body. */
    private fun escapeForScript(json: String): String = json.replace("</", "<\\/")

    private const val SCHEMA_ORG = "https://schema.org"

    /** The publisher name — the site's own, as the homepage's SoftwareApplication block spells it. */
    const val SITE_NAME = "datapipelines.co"
}
