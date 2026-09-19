package co.datapipelines.web.ui.site

import co.datapipelines.web.ui.DocsCatalog

/**
 * `/llms.txt` and `/llms-full.txt` (173 §C.1) — the site as an agent reads it, in the
 * llmstxt.org shape: an H1, a blockquote summary, then sections of
 * `- [title](absolute url): description` lines; the full variant carries every packaged
 * doc's Markdown after the same header.
 *
 * A pure function of the two registries that already decide what is public — the page
 * registry through [SiteExplore.groups] (the same grouping `/explore` renders) and the
 * packaged docs catalog — deliberately separate from the controller, like [SitemapXml]:
 * the live route and the static export need the same bytes. Because both files derive
 * from the registries, they cannot drift from `/sitemap.xml`: `LlmsTextTest` asserts every
 * marketing `<loc>` appears in the index and every docs slug in both. Docs are linked at
 * their `.md` route, which is the text an agent can actually read.
 */
object LlmsText {
    /** The index: header, then the four `/explore` groups as link lists. */
    fun index(docs: DocsCatalog): String =
        buildString {
            header()
            SiteExplore.groups(docs).forEach { group ->
                append("## ").append(group.name).append('\n')
                append(group.blurb).append("\n\n")
                group.links.forEach { link ->
                    append("- [").append(link.title).append("](").append(SITE_ORIGIN).append(agentPath(link.path)).append(')')
                    if (link.description.isNotBlank()) append(": ").append(link.description)
                    append('\n')
                }
                append('\n')
            }
            // The directory itself is not in its own groups; it is in the sitemap, so it is here.
            append("## ").append(DIRECTORY_HEADING).append('\n')
            append("- [").append(SitePages.EXPLORE.title).append("](").append(SitePages.EXPLORE.canonical).append("): ")
            append(SitePages.EXPLORE.description).append('\n')
        }

    /** The full text: the same header, then every packaged doc's rewritten Markdown under `# <title>`. */
    fun full(docs: DocsCatalog): String =
        buildString {
            header()
            docs.index().forEach { group ->
                group.docs.forEach { entry ->
                    val markdown = checkNotNull(docs.markdown(entry.slug)) { "no markdown for packaged doc ${entry.slug}" }
                    append("\n---\n\n# ").append(entry.title).append('\n')
                    append(SOURCE_LINE_PREFIX).append(SITE_ORIGIN).append(agentPath("/docs/${entry.slug}")).append("\n\n")
                    append(withoutLeadingH1(markdown).trimEnd()).append('\n')
                }
            }
        }

    /** The llmstxt.org header: H1, blockquote summary, one line of orientation. */
    private fun StringBuilder.header() {
        append("# ").append(SITE_NAME).append("\n\n")
        append("> ").append(SITE_SUMMARY).append("\n\n")
        append(ORIENTATION).append("\n\n")
    }

    /**
     * The address an agent should fetch for a path: a doc's `.md` twin (raw Markdown), the
     * HTML page for everything else — the docs index has no Markdown form.
     */
    private fun agentPath(path: String): String =
        if (path.startsWith(DOCS_PREFIX)) path + MARKDOWN_SUFFIX else path

    /**
     * Every packaged doc opens with its own `# Title` line — the line [DocsCatalog] took the
     * title from — so the separator this file writes would repeat it. Drop that first H1
     * only; a doc that (some day) opens otherwise is concatenated verbatim.
     */
    private fun withoutLeadingH1(markdown: String): String {
        val lines = markdown.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        return if (first >= 0 && lines[first].startsWith("# ")) lines.drop(first + 1).joinToString("\n") else markdown
    }

    /** The site's name as the H1 — the same spelling the JSON-LD blocks use. */
    const val SITE_NAME: String = DocJsonLd.SITE_NAME

    private const val DOCS_PREFIX = "/docs/"
    private const val MARKDOWN_SUFFIX = ".md"
    private const val SOURCE_LINE_PREFIX = "Source: "
    private const val DIRECTORY_HEADING = "The directory"

    private const val ORIENTATION =
        "Every page below is server-rendered HTML; the documentation is also served as raw Markdown at its " +
            "`.md` address, and `/llms-full.txt` carries all of it in one file. The agent skill for this " +
            "server is at `/skill.md`; the MCP endpoint is `POST /mcp` with an API key."
}
