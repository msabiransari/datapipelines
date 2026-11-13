package co.datapipelines.web.ui.site

/**
 * The claim-citation grammar (#674): what a `<!-- claim: … -->` comment may cite and how to
 * read it, split out of [SiteClaimCitationTest] so the parser has one home and the sweep keeps
 * reading like the audit it is.
 *
 * Grammar, derived from the citations the templates actually carry (inventoried at the 722 base, 9d63e2d88):
 *  - a citation is `docs/<file>.md` optionally followed by an anchor and a same-document chain;
 *  - an anchor is `§` + a section number (`3`, `6.2.14`, `9.7A`, `8C.1`), `§` + `Appendix X`,
 *    `§` + `Part <word>` — and the SAME three shapes WITHOUT the `§` glyph (`Appendix B`,
 *    `Part one`), which 36+ citations spell that way;
 *  - a range `A–B` (U+2013 or `-`, second end optionally `§`-prefixed) cites BOTH ends;
 *  - a citation without any anchor is a file-only citation (the residue of the pre-674 rule).
 *
 * After an anchor, further anchors separated by commas, semicolons, `and`, `or` or `plus`
 * (with or without `§`) belong to the SAME document; ranges retain both ends. A parenthetical
 * or em-dash opens prose and ends the chain. An anchor for a DIFFERENT document needs its
 * own path. A bare `§n` before any path is prose. Only claim comments are read, never visible
 * page text. The prose probe uses the LAST anchor, including in chains and ranges, because
 * the prose sits nearest that anchor; existence is checked for EVERY anchor.
 */
internal object ClaimCitations {
    /** One citation: the doc path, its anchors (empty = file-only), and the prose that follows it. */
    data class Citation(
        val doc: String,
        val anchors: List<String>,
        val prose: String,
    ) {
        val anchored: Boolean get() = anchors.isNotEmpty()
    }

    /** Split one claim comment's text into its citations, in order. */
    fun parse(comment: String): List<Citation> {
        val citations = mutableListOf<Citation>()
        PATH.findAll(comment).forEach { pathMatch ->
            val proseEnd = PATH.find(comment, pathMatch.range.last + 1)?.range?.first ?: comment.length
            val tail = comment.substring(pathMatch.range.last + 1, proseEnd)
            val anchors = mutableListOf<String>()
            var anchorMatch = ANCHOR.matchAt(tail, 0)
            var proseStart = 0
            while (anchorMatch != null) {
                anchors += anchorMatch.groupValues[2]
                anchorMatch.groupValues[4].takeIf { it.isNotEmpty() }?.let { anchors += it }
                proseStart = anchorMatch.range.last + 1
                val separator = CHAIN_SEPARATOR.matchAt(tail, proseStart) ?: break
                anchorMatch = ANCHOR.matchAt(tail, separator.range.last + 1)
            }
            citations +=
                Citation(
                    doc = pathMatch.value,
                    anchors = anchors,
                    prose = prose(tail.substring(proseStart)),
                )
        }
        return citations
    }

    /**
     * The cited section's text: its heading line through the line before the next heading of
     * the same or a higher level. `null` when no heading carries the anchor — the caller's
     * loud direction.
     */
    fun sectionText(
        docLines: List<String>,
        anchor: String,
    ): String? {
        val heading = Regex("""^(#{2,4}) ${Regex.escape(anchor)}(?=[.:](?!\d)|\s|$)""")
        val start = docLines.indexOfFirst { heading.containsMatchIn(it) }
        if (start < 0) return null
        val level = docLines[start].takeWhile { it == '#' }.length
        val end = docLines.drop(start + 1).indexOfFirst { it.matches(Regex("""^#{1,$level} \S.*""")) }
        return docLines.drop(start).take(if (end < 0) docLines.size else end + 1).joinToString("\n")
    }

    /** Probe tokens: the prose's words of five characters or more, minus [STOP_TOKENS]. */
    fun probeTokens(prose: String): List<String> =
        WORD
            .findAll(prose.lowercase())
            .map { it.value }
            .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_TOKENS }
            .toList()

    /** Does the (lower-cased) section text contain the token as a whole word? */
    fun sectionMentions(
        lowerCasedSection: String,
        token: String,
    ): Boolean = Regex("""(?<![a-z0-9])${Regex.escape(token)}(?![a-z0-9])""").containsMatchIn(lowerCasedSection)

    /**
     * The stop-list, named: function words that survive the five-character floor yet say
     * nothing about what a section contains. Content words — "refusal", "scheduler",
     * "warehouse" — must never land here.
     */
    val STOP_TOKENS: Set<String> =
        setOf(
            "about",
            "after",
            "again",
            "because",
            "been",
            "before",
            "being",
            "between",
            "could",
            "does",
            "doing",
            "during",
            "each",
            "every",
            "from",
            "further",
            "have",
            "having",
            "it's",
            "itself",
            "just",
            "more",
            "most",
            "other",
            "should",
            "some",
            "such",
            "than",
            "that",
            "their",
            "them",
            "then",
            "there",
            "these",
            "they",
            "this",
            "those",
            "through",
            "under",
            "until",
            "very",
            "were",
            "what",
            "when",
            "where",
            "which",
            "while",
            "will",
            "with",
            "would",
            "your",
            "yours",
        )

    private fun prose(raw: String): String =
        raw
            .replace("-->", " ")
            .trim()
            .trimStart(',', ';', '—', '–', '-', '§')
            .trim()
            .trimEnd(',', ';')
            .trim()

    private val WORD = Regex("""[a-z0-9']+""")
    private const val MIN_TOKEN_LENGTH = 5

    /**
     * A path inside a claim comment. The character class is the pre-674 one, unchanged: a
     * wider class would read `docs/x.md` out of prose it does not belong to.
     */
    private val PATH = Regex("""docs/[A-Za-z0-9._/-]+\.md""")

    private val CHAIN_SEPARATOR =
        Regex("""\s*(?:[,;]\s*(?:(?:and|or|plus)\b\s*)?|(?:and|or|plus)\b\s*)""")

    /**
     * The anchor immediately after a path: group 2 the first end, group 4 the range's upper
     * end. The `§` is optional so the `Appendix B` / `Part one` spellings parse; the range
     * separator accepts U+2013 and the ASCII hyphen.
     */
    private val ANCHOR =
        Regex(
            """\s*(§)?\s*((?:[0-9]+[A-Z]?(?:\.[0-9]+[A-Z]?)*|Appendix [A-Z]|Part [a-z]+))""" +
                """(?:\s*[–-]\s*(§)?([0-9]+[A-Z]?(?:\.[0-9]+[A-Z]?)*))?""",
        )
}
