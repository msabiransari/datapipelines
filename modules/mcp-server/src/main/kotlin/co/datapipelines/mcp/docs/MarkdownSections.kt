package co.datapipelines.mcp.docs

/**
 * Section cutting and slugging for the rendered documents (record §3: "sections cut at `##`
 * headings and slugged by GitHub rules — the audit's own").
 *
 * The slug rule is `scripts/docs-audit.sh`'s `gh_slug`, spelled identically here so a section
 * id an agent passes to `docs_get` is the anchor the same text carries in the audited export:
 * lower-case, backticks stripped, word characters, `_`, `-` and spaces kept, spaces folded to
 * `-`, everything else dropped. Duplicate ids in one document take GitHub's `-1`/`-2` suffixes.
 */
object MarkdownSections {
    /** One `##`-delimited run of a document, with its audit-spelled id. */
    data class Section(
        val id: String,
        val title: String,
        val markdown: String,
    )

    /** Word characters, `_`, `-` and space — the audit's keep-set, split to stay simple. */
    private fun isSeparator(ch: Char): Boolean = ch == '_' || ch == '-' || ch == ' '

    private fun isSlugChar(ch: Char): Boolean = ch.isLetterOrDigit() || isSeparator(ch)

    /** The audit's own GitHub slug spelling. */
    fun slug(heading: String): String {
        val h = heading.trim().lowercase().replace("`", "")
        val kept =
            buildString {
                for (ch in h) {
                    if (isSlugChar(ch)) append(ch)
                }
            }
        return kept.replace(' ', '-')
    }

    /**
     * Cuts [markdown] at its `##` headings, in order. The text before the first `##` belongs to
     * no section — it is the document's lede, served whole with the document and dropped by the
     * section form, the same way the H1 and the lede read only once.
     */
    fun cut(markdown: String): List<Section> {
        val lines = markdown.lines()
        if (lines.none { it.startsWith("## ") }) {
            return emptyList()
        }
        val sections = mutableListOf<Section>()
        var title: String? = null
        val body = StringBuilder()
        for (line in lines) {
            if (line.startsWith("## ")) {
                title?.let { sections.add(Section(id = slug(it), title = it, markdown = body.toString().trim())) }
                title = line.removePrefix("## ").trim()
                body.clear()
            } else if (title != null) {
                body.append(line).append('\n')
            }
        }
        title?.let { sections.add(Section(id = slug(it), title = it, markdown = body.toString().trim())) }
        // GitHub duplicate suffixes, in document order (the audit spells the same rule).
        val seen = mutableMapOf<String, Int>()
        return sections.map { section ->
            val n = seen.getOrDefault(section.id, 0)
            seen[section.id] = n + 1
            if (n == 0) section else section.copy(id = "${section.id}-$n")
        }
    }

    /**
     * Splits one section's markdown into budget-sized parts at the next heading inside it
     * (`###` and deeper, never a nested `##`, which would be the section boundary itself); a
     * section with no inner headings is split at a line boundary as a last resort. The first
     * part keeps the section's own id; continuation parts answer to `<id>-<n>` — the id a
     * response's `next` field names and `docs_get`'s `section` accepts (record §4).
     */
    fun splitToBudget(
        section: Section,
        budgetChars: Int,
    ): List<Section> {
        if (section.markdown.length <= budgetChars) {
            return listOf(section)
        }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (line in section.markdown.lines()) {
            val atInnerHeading = line.startsWith("### ")
            val currentHasBody = current.isNotEmpty()
            val currentWellFilled = current.length >= budgetChars / MINIMUM_FILL
            if (atInnerHeading && currentHasBody && currentWellFilled) {
                parts.add(current.toString().trim())
                current.clear()
            }
            current.append(line).append('\n')
            if (current.length >= budgetChars) {
                parts.add(current.toString().trim())
                current.clear()
            }
        }
        if (current.isNotBlank()) {
            parts.add(current.toString().trim())
        }
        return parts.mapIndexed { index, markdown ->
            if (index == 0) section.copy(markdown = markdown) else section.copy(id = "${section.id}-${index + 1}", markdown = markdown)
        }
    }

    /** A part is required to be at least this fraction of the budget before an inner heading may close it. */
    private const val MINIMUM_FILL = 2
}
