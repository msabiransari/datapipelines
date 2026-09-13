package co.datapipelines.mcp

import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * 125 §B — `semantics_record` refuses a fact whose text names a table its `refs` do not.
 *
 * The learned-fact store checks every REF against live introspection (`semantics.ref_unresolved`),
 * but nothing checked the other direction until the audited acceptance runs: an agent recorded a
 * fact whose `fact` text named a table that does not exist (a typo) while its `refs` were right,
 * and the store kept the lie — the next session reads the text, not the refs. This is the other
 * direction: every identifier-shaped token of the `fact` (and a caller-given `evidence_summary`)
 * is compared against the datasource's catalog listing, and a token that names a listed table
 * without being one of the `refs` — a catalog table spelled exactly but missing from `refs`, or a
 * near-miss within an edit distance of [MAX_DISTANCE] of one — refuses the record as
 * `semantics.ref_mismatch`. A token the `refs` DO carry is text/refs agreement; whether that ref
 * then resolves is the recorder's `semantics.ref_unresolved`, not this check.
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
 */
internal object FactRefMismatchCheck {
    private val TOKEN = Regex("[a-z0-9_]{4,}")

    /** 123's `MAX_SUGGESTION_DISTANCE`, restated: the largest edit distance a near-miss may carry. */
    private const val MAX_DISTANCE = 2

    /**
     * Throws the catalogued refusal on the first offending token of [texts]; returns normally when
     * every table the texts name is in [refs] (or the token is prose). [catalogTables] is the
     * datasource's listing as `datasources_get_tables` reports it; an empty listing skips the
     * check entirely.
     */
    fun check(
        datasource: String,
        texts: List<String>,
        refs: List<FactRef>,
        catalogTables: List<String>,
    ) {
        if (catalogTables.isEmpty()) return
        val refTables = refs.mapTo(mutableSetOf()) { it.table.lowercase() }
        val catalogLower = catalogTables.map { it.lowercase() }
        val hit =
            texts.firstNotNullOfOrNull { text ->
                TOKEN
                    .findAll(text.lowercase())
                    .map { it.value }
                    .firstNotNullOfOrNull { token -> offense(datasource, token, refTables, catalogTables, catalogLower) }
            }
        hit?.let { throw it }
    }

    /**
     * The refusal one [token] earns, or null when it is prose (no listed table within
     * [MAX_DISTANCE]), text/refs agreement (a wrong REF is the recorder's `ref_unresolved`,
     * not this check), or a listed table the refs carry.
     */
    private fun offense(
        datasource: String,
        token: String,
        refTables: Set<String>,
        catalogTables: List<String>,
        catalogLower: List<String>,
    ): DatapipelinesException? {
        val exactIndex = catalogLower.indexOf(token)
        val nearest = catalogTables.minByOrNull { levenshtein(token, it.lowercase()) }
        val nearestDistance = nearest?.let { levenshtein(token, it.lowercase()) } ?: Int.MAX_VALUE
        return when {
            // A listed table, spelled as the catalog spells it: refused only when no ref carries it.
            exactIndex >= 0 && token !in refTables -> {
                mismatch(datasource, token, suggestion = null, listedAs = catalogTables[exactIndex])
            }

            // Prose: an exact match the refs carry, a plain word (no underscore), or nothing close.
            exactIndex >= 0 || '_' !in token || nearestDistance > MAX_DISTANCE -> {
                null
            }

            // A token the refs carry is text/refs agreement — a wrong REF is the recorder's job.
            token in refTables -> {
                null
            }

            else -> {
                mismatch(
                    datasource,
                    token,
                    suggestion = requireNotNull(nearest) { "within MAX_DISTANCE implies a nearest table" },
                    listedAs = null,
                )
            }
        }
    }

    private fun mismatch(
        datasource: String,
        token: String,
        suggestion: String?,
        listedAs: String?,
    ): DatapipelinesException {
        val message =
            if (suggestion != null) {
                "The fact names '$token', which is not a table here; did you mean '$suggestion'? " +
                    "Name the tables the fact is about in refs and spell them as the catalog does."
            } else {
                "The fact names '$token', which is a table on datasource '$datasource' but is not one of this fact's refs. " +
                    "Name the tables the fact is about in refs and spell them as the catalog does."
            }
        return DatapipelinesException(
            code = PipelineErrorCodes.Semantics.REF_MISMATCH,
            message = message,
            details =
                buildMap {
                    put("datasource", datasource)
                    put("token", token)
                    suggestion?.let { put("suggestion", it) }
                    listedAs?.let { put("table", it) }
                },
        )
    }

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
