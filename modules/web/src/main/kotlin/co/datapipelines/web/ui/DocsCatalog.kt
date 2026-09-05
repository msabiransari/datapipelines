package co.datapipelines.web.ui

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.nio.file.Paths

/**
 * The in-product spec set (033): renders the packaged classpath:docs markdown set to HTML,
 * once, memoized — the docs are immutable inside a jar, so there is nothing to re-read per request.
 *
 * Packaging (which files land on the classpath, and the excluded set) lives in
 * `modules/web/build.gradle.kts` (`processResources`); the grouping below is the owner-approved
 * Operations manual / Contracts / Reference split. A doc that reaches the classpath without a
 * group fails fast here — grouping is a deliberate decision, not a default.
 *
 * Link rewriting (033 §A — the docs-link rule and the exclusion policy only work together):
 * every relative link resolves to a packaged slug (`/docs/{slug}`) or is rewritten to the
 * canonical GitHub URL for that path. Nothing ships as a dead relative href — the excluded
 * docs (`SPEC-REVIEW-2026-08.md`, the docs/superpowers tree, …) and everything outside `docs`
 * (`../DEVELOPMENT.md`, `../LICENSE`, …) are public in the AGPL repo, so the GitHub rewrite
 * is always reachable. Fragments pass through untouched on both branches: doc sources carry
 * GitHub-style anchors already, and the rendered heading ids use the SAME slug algorithm
 * `scripts/docs-audit.sh` implements ([githubSlug], duplicate-suffixed per document).
 * `DocsRenderingTest` asserts BOTH branches stay non-vacuous.
 */
class DocsCatalog(
    classLoader: ClassLoader,
) {
    /**
     * One index row: slug, display title (first H1), group name, and the meta description
     * the public doc page publishes (073 §C).
     */
    data class DocEntry(
        val slug: String,
        val title: String,
        val group: String,
        val description: String,
    )

    /** A named section of the docs index, in display order. */
    data class DocGroup(
        val name: String,
        val docs: List<DocEntry>,
    )

    /** A rendered doc: its index entry, the memoized HTML body, and the anchor ids it emits. */
    data class RenderedDoc(
        val entry: DocEntry,
        val html: String,
        val anchors: Set<String>,
    )

    private val rendered: Map<String, RenderedDoc>
    private val groups: List<DocGroup>

    init {
        val resources =
            PathMatchingResourcePatternResolver(classLoader)
                .getResources("classpath*:docs/*.md")
                .filter { it.filename != null }
                .distinctBy { it.filename }
                .sortedBy { it.filename!!.lowercase() }
        require(resources.isNotEmpty()) {
            "docs-in-app: no packaged docs on the classpath — processResources packaging is broken (modules/web/build.gradle.kts)"
        }

        val packagedPaths = resources.mapTo(mutableSetOf()) { "docs/${it.filename}" }
        val bySlug =
            resources.associate { res ->
                val filename = res.filename!!
                val slug = filename.removeSuffix(".md").lowercase()
                val markdown = res.inputStream.readBytes().decodeToString()
                slug to renderDoc(slug, markdown, packagedPaths)
            }

        val unknown = bySlug.keys - GROUPING.values.flatten().toSet()
        require(unknown.isEmpty()) {
            "docs-in-app: packaged doc(s) $unknown have no index group — grouping is a deliberate edit to GROUPING"
        }
        rendered = bySlug
        groups =
            GROUPING.map { (name, slugs) ->
                DocGroup(name, slugs.map { bySlug.getValue(it).entry })
            }
    }

    /** The grouped docs index for `GET /docs`. */
    fun index(): List<DocGroup> = groups

    /** The memoized render for `GET /docs/{slug}`, or null for an unknown slug. */
    fun render(slug: String): RenderedDoc? = rendered[slug]

    private fun renderDoc(
        slug: String,
        markdown: String,
        packagedPaths: Set<String>,
    ): RenderedDoc {
        val document = PARSER.parse(markdown)
        // §A rewrite, before rendering: the parsed tree carries the original hrefs.
        document.accept(
            object : AbstractVisitor() {
                override fun visit(link: Link) {
                    link.destination = rewriteHref(link.destination, packagedPaths)
                }
            },
        )

        val seen = mutableMapOf<String, Int>()
        val anchors = mutableSetOf<String>()
        val renderer =
            HtmlRenderer
                .builder()
                .extensions(EXTENSIONS)
                .attributeProviderFactory { HeadingIdProvider(seen, anchors) }
                .build()
        val html = renderer.render(document)

        var title = slug
        document.accept(
            object : AbstractVisitor() {
                override fun visit(heading: Heading) {
                    if (title == slug && heading.level == 1) title = textOf(heading).ifBlank { slug }
                }
            },
        )
        return RenderedDoc(DocEntry(slug, title, groupOf(slug), metaDescription(document)), html, anchors)
    }

    /**
     * The doc's meta description (073 §C): its first REAL paragraph, flattened to one line
     * and trimmed to the length a search result displays.
     *
     * "First paragraph" naively is the wrong answer here — every spec opens with the
     * `**Status:** … **Owner:** … **Last updated:** …` metadata block, which describes the
     * document's bookkeeping and not its subject. So the scan skips that block by its own
     * marker and skips one-liners (a pointer sentence, a table caption), and takes the first
     * paragraph long enough to be prose. A doc with no such paragraph gets an empty
     * description and the page omits the tag — an absent description is a worse result
     * snippet; a wrong one is a wrong page in the index.
     */
    private fun metaDescription(document: Node): String {
        val paragraphs = mutableListOf<String>()
        document.accept(
            object : AbstractVisitor() {
                override fun visit(paragraph: Paragraph) {
                    paragraphs += paragraphText(paragraph).replace(WHITESPACE, " ").trim()
                }
            },
        )
        val prose =
            paragraphs.firstOrNull { it.length >= MIN_DESCRIPTION_CHARS && !it.startsWith(STATUS_BLOCK_MARKER) }
                ?: return ""
        return truncateToDisplay(prose)
    }

    /**
     * A paragraph's visible text, with line breaks turned back into spaces.
     *
     * Deliberately NOT [textOf], which the heading-slug provider uses: that one ignores
     * `SoftLineBreak`, so a paragraph wrapped mid-sentence in the source comes out with the
     * words on either side of the newline glued together ("how edits becomedrafts", which is
     * what versioning.md's first paragraph produced). Widening [textOf] instead would change
     * the heading ids it feeds, and those are pinned to GitHub's algorithm and to
     * `scripts/docs-audit.sh` — a slug change would 404 every anchor the audit calls live.
     */
    private fun paragraphText(paragraph: Node): String =
        buildString {
            paragraph.accept(
                object : AbstractVisitor() {
                    override fun visit(text: Text) = append(text.literal).let { }

                    override fun visit(code: Code) = append(code.literal).let { }

                    override fun visit(softLineBreak: SoftLineBreak) = append(' ').let { }

                    override fun visit(hardLineBreak: HardLineBreak) = append(' ').let { }
                },
            )
        }

    private fun groupOf(slug: String): String =
        GROUPING.entries.firstOrNull { slug in it.value }?.key
            ?: error("docs-in-app: $slug has no group (checked at init)")

    /**
     * Emits GitHub-compatible heading ids ([githubSlug] + per-document duplicate suffixing,
     * mirroring scripts/docs-audit.sh exactly — the audit and this renderer must agree on
     * what `](file.md#anchor)` resolves to, or an anchor the audit calls live 404s in-product).
     */
    private class HeadingIdProvider(
        private val seen: MutableMap<String, Int>,
        private val emitted: MutableSet<String>,
    ) : AttributeProvider {
        override fun setAttributes(
            node: Node,
            tagName: String,
            attributes: MutableMap<String, String>,
        ) {
            if (node !is Heading) return
            val base = githubSlug(textOf(node))
            if (base.isEmpty()) return
            val id = uniqueHeadingId(base, seen)
            emitted += id
            attributes["id"] = id
        }
    }

    companion object {
        /**
         * The canonical public location of the repo (033 §A1): public doc access remains
         * GitHub (owner Decision 2), and the repo is AGPL-public, so every non-packaged
         * relative link target is reachable here. A constant, not config: the docs are
         * version-locked to THIS repository's content.
         */
        const val GITHUB_BLOB_BASE = "https://github.com/msabiransari/datapipelines/blob/main/"

        /** Owner-approved grouping (033 reviewer's answers). Slugs are lowercase filenames minus `.md`. */
        private val GROUPING: Map<String, List<String>> =
            linkedMapOf(
                "Operations manual" to
                    listOf("deployment", "configuration", "auth", "datasources", "key-providers", "observability", "mcp-server"),
                "Contracts" to
                    listOf(
                        "pipeline-contract",
                        "templates",
                        "versioning",
                        "rest-api",
                        "type-system",
                        "enums",
                        "staging",
                        "dag-executor",
                        // 072: the calculator catalog is a contract — a `kind` is written into
                        // versioned, exported, promoted bodies, so it reads beside the pipeline
                        // contract rather than under Reference.
                        "calculators",
                    ),
                "Reference" to
                    listOf("metadata-db", "module-structure", "pipeline-editor", "ui-screens", "roadmap", "readme"),
            )

        private val EXTENSIONS = listOf(TablesExtension.create(), StrikethroughExtension.create())
        private val PARSER: Parser = Parser.builder().extensions(EXTENSIONS).build()

        /** Google truncates a description around 155–160 characters; 155 is the safe display budget. */
        const val DESCRIPTION_MAX_CHARS = 155

        /** Below this a "paragraph" is a pointer line or a caption, not the doc's subject. */
        private const val MIN_DESCRIPTION_CHARS = 60

        /** Every spec opens with this bookkeeping block; it describes the file, not the subject. */
        private const val STATUS_BLOCK_MARKER = "Status:"

        private val WHITESPACE = Regex("""\s+""")

        /**
         * Cut to [DESCRIPTION_MAX_CHARS] at a word boundary — mid-word truncation reads as a
         * broken page in the search result. Text that already fits is returned untouched, so
         * a short first paragraph keeps its full stop instead of gaining an ellipsis.
         */
        internal fun truncateToDisplay(text: String): String {
            if (text.length <= DESCRIPTION_MAX_CHARS) return text
            val head = text.take(DESCRIPTION_MAX_CHARS - 1)
            val cut = head.lastIndexOf(' ')
            return (if (cut > 0) head.take(cut) else head).trimEnd(',', ';', ':', '.', ' ') + "\u2026"
        }

        /** The non-alphanumerics GitHub keeps in heading slugs: underscore, dash, space. */
        private const val SLUG_KEPT_CHARS = "_- "

        /**
         * The GitHub heading-slug algorithm as scripts/docs-audit.sh implements it
         * (033 §A3): lowercase, backticks stripped, alphanumerics plus `_ - <space>` kept,
         * everything else dropped, spaces become dashes. DocsSlugAlgorithmTest pins the two
         * implementations against the same inputs.
         */
        fun githubSlug(heading: String): String {
            val h = heading.trim().replace("`", "").lowercase()
            val out = StringBuilder()
            for (ch in h) {
                if (ch.isLetterOrDigit() || ch in SLUG_KEPT_CHARS) out.append(ch)
            }
            return out.toString().replace(" ", "-")
        }

        /**
         * docs-audit.sh's duplicate-heading rule: the first occurrence of a slug keeps the
         * base, later ones get `-1`, `-2`, … — recorded in [seen] exactly like the audit's
         * per-file pass. Internal for DocsSlugAlgorithmTest (no packaged doc currently HAS a
         * duplicate heading, so this path is only reachable here).
         */
        internal fun uniqueHeadingId(
            base: String,
            seen: MutableMap<String, Int>,
        ): String {
            val n = seen.getOrDefault(base, 0)
            seen[base] = n + 1
            return if (n == 0) base else "$base-$n"
        }

        /**
         * 033 §A1, the two-branch rule: a relative link resolves to a packaged slug, or it
         * is rewritten to the canonical GitHub URL for that path. Absolute URLs and pure
         * in-page anchors pass through; fragments are preserved on both branches (§A3).
         */
        fun rewriteHref(
            href: String,
            packagedPaths: Set<String>,
        ): String {
            if (href.isBlank() || href.startsWith("#")) return href
            if (isAbsolute(href)) return href

            val path = href.substringBefore('#')
            val fragment = if ('#' in href) "#${href.substringAfter('#')}" else ""
            if (path.isBlank()) return href
            val resolved =
                Paths
                    .get("docs")
                    .resolve(path)
                    .normalize()
                    .joinToString("/") { it.toString() }

            return if (resolved in packagedPaths) {
                "/docs/${resolved.removePrefix("docs/").removeSuffix(".md").lowercase()}$fragment"
            } else {
                "$GITHUB_BLOB_BASE$resolved$fragment"
            }
        }

        /** Absolute links (web, mail) pass the rewrite untouched. */
        private fun isAbsolute(href: String): Boolean {
            val lower = href.lowercase()
            return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")
        }

        /** The visible text of a node (heading titles, slug sources). */
        private fun textOf(node: Node): String =
            buildString {
                node.accept(
                    object : AbstractVisitor() {
                        override fun visit(text: Text) {
                            append(text.literal)
                        }

                        override fun visit(code: Code) {
                            append(code.literal)
                        }
                    },
                )
            }
    }
}
