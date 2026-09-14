package co.datapipelines.mcp

import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * 125 §B, kinded by 129 §B — the text/refs agreement check over a recorded fact's wording.
 *
 * The learned-fact store checks every REF against live introspection (`semantics.ref_unresolved`),
 * but nothing checked the other direction until the audited acceptance runs: an agent recorded a
 * fact whose `fact` text named a table that does not exist (a typo) while its `refs` were right,
 * and the store kept the lie — the next session reads the text, not the refs. This is the other
 * direction: every identifier-shaped token of the `fact` (and a caller-given `evidence_summary`)
 * is compared against the datasource's catalog listing, and a token that NEAR-MISSES a listed
 * table — within an edit distance of [MAX_DISTANCE], without being one — refuses the record as
 * `semantics.ref_mismatch`, naming the nearest table. A token that case-fold IS a listed table
 * but that no ref carries is not a lie to refuse: the caller ADDS it to the stored refs, and
 * [check] returns it (in the catalog's spelling) so the tool can. A token the `refs` DO carry is
 * text/refs agreement; whether that ref then resolves is the recorder's
 * `semantics.ref_unresolved`, not this check.
 *
 * Prose is deliberately not parsed: a token is a CANDIDATE only when it is `[a-z0-9_]{4,}` AND
 * contains an underscore or case-fold-matches a listed table, and a candidate that matches nothing
 * within [MAX_DISTANCE] is prose (`row_count`, `as_of`), not a reference. The nearest-name rule is
 * the same one 123 built for `datasource.table_not_found` — case-folded equality first, then the
 * smallest case-folded Levenshtein distance capped at [MAX_DISTANCE]; the datasources module's own
 * copy is `internal`, so the rule is restated here rather than shared across the module boundary.
 *
 * The check runs only when the datasource HAS a catalog listing: a lake with an empty registry
 * (or a listing read that fails — the recorder's own ref check surfaces the unreachable
 * datasource) skips it.
 *
 * 129 owner ruling: the exact-missing arm used to refuse, and the refusal taxed ordinary prose —
 * every table in a workspace of common nouns (trips, zones, stations) tripped it. On an exact
 * match, ADD the ref and accept; refuse only the near-miss the check was built for.
 */
internal object FactRefMismatchCheck {
    private val TOKEN = Regex("[a-z0-9_]{4,}")

    /** 123's `MAX_SUGGESTION_DISTANCE`, restated: the largest edit distance a near-miss may carry. */
    private const val MAX_DISTANCE = 2

    /**
     * Returns the listed tables the texts name exactly but [refs] do not carry — the refs the
     * caller adds before storing, in the catalog's spelling, deduplicated in first-seen order —
     * and throws the catalogued refusal on the first NEAR-MISS token of [texts]. [catalogTables]
     * is the datasource's listing as `datasources_get_tables` reports it; an empty listing skips
     * the check entirely.
     */
    fun check(
        datasource: String,
        texts: List<String>,
        refs: List<FactRef>,
        catalogTables: List<String>,
    ): List<String> {
        if (catalogTables.isEmpty()) return emptyList()
        val refTables = refs.mapTo(mutableSetOf()) { it.table.lowercase() }
        val catalogLower = catalogTables.map { it.lowercase() }
        val missing = linkedSetOf<String>()
        for (text in texts) {
            for (token in TOKEN.findAll(text.lowercase()).map { it.value }) {
                val exactIndex = catalogLower.indexOf(token)
                when {
                    // A listed table, spelled as the catalog spells it: the caller adds the ref,
                    // unless a ref already carries it (agreement).
                    exactIndex >= 0 && token !in refTables -> missing += catalogTables[exactIndex]

                    // Prose: an exact match the refs carry, a plain word (no underscore), or
                    // nothing close.
                    exactIndex >= 0 || '_' !in token -> Unit

                    // A token the refs carry is text/refs agreement — a wrong REF is the
                    // recorder's job (`ref_unresolved`), not a near-miss to refuse.
                    token in refTables -> Unit

                    else -> {
                        val nearest =
                            catalogTables.minByOrNull { levenshtein(token, it.lowercase()) }
                        val nearestDistance = nearest?.let { levenshtein(token, it.lowercase()) } ?: Int.MAX_VALUE
                        if (nearestDistance <= MAX_DISTANCE) {
                            throw mismatch(
                                datasource,
                                token,
                                requireNotNull(nearest) { "within MAX_DISTANCE implies a nearest table" },
                            )
                        }
                    }
                }
            }
        }
        return missing.toList()
    }

    private fun mismatch(
        datasource: String,
        token: String,
        suggestion: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code = PipelineErrorCodes.Semantics.REF_MISMATCH,
            message =
                "The fact names '$token', which is not a table here; did you mean '$suggestion'? " +
                    "Name the tables the fact is about in refs and spell them as the catalog does.",
            details =
                mapOf(
                    "datasource" to datasource,
                    "token" to token,
                    "suggestion" to suggestion,
                ),
        )

    /** Classic two-row Levenshtein, the datasources module's own copy made module-local. */
    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
