package co.datapipelines.web.navigation

/** Tree-only normalization, applied after the shared search-needle admission rule. */
internal object TreeSearchTerm {
    // Fixed ECMAScript WhiteSpace + LineTerminator alphabet; keep identical to state.mjs.
    private val whitespace = Regex("""[\u0009-\u000D\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+""")

    /** Collapse runs, strip the resulting edge spaces, and make an empty term a browse. */
    fun collapse(term: String): String? = term.replace(whitespace, " ").trim(' ').ifEmpty { null }
}
