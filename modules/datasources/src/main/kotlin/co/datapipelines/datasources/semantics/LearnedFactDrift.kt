package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.ColumnInfo

/**
 * Design §6 — the read-time drift check: detect, mark, never remap.
 *
 * Every introspection response that carries facts recomputes each fact's fingerprint from the
 * columns it just read (zero extra I/O) and compares it with what was stored at record time.
 * The verdict is a DEMOTION or nothing — a fact never climbs back by itself: a rename and a
 * "drop + unrelated add" are indistinguishable to a machine, so a `stale` fact stays stale
 * until a person or an agent records the superseding fact with evidence (D-S6).
 */
object LearnedFactDrift {
    /** What the check found: the trust the fact should now carry and the message that says why. */
    data class Verdict(
        val trust: LearnedFactTrust,
        val drift: String?,
    ) {
        /** True when the verdict is a change from [current] — the row is updated only then. */
        fun demotes(current: LearnedFactTrust): Boolean = trust != current
    }

    /**
     * The check against one table's freshly read [columns]. Refs on other tables are not this
     * listing's business (a join fact is checked table by table, each from its own listing).
     *
     * A ref naming a column that no longer exists is `stale`; refs that all resolve over a
     * changed column set are `needs_review`. Both are one-way: a fact already `stale` is never
     * softened to `needs_review` by a later read that happens to resolve, and a `retired` fact
     * is never touched.
     */
    fun againstColumns(
        fact: LearnedFact,
        table: String,
        namespace: List<String>?,
        columns: List<ColumnInfo>,
    ): Verdict {
        val refs = fact.refsOn(table, namespace)
        if (refs.isEmpty() || fact.trust == LearnedFactTrust.RETIRED) return unchanged(fact)
        val names = columns.map { it.column.name }.toSet()
        val missing = refs.mapNotNull { it.column }.firstOrNull { it !in names }
        if (missing != null) return demote(fact, LearnedFactTrust.STALE, "column $missing no longer exists")
        val recorded = refs.firstNotNullOfOrNull { SchemaFingerprint.segment(fact.schemaFingerprint, it.tableKey) }
        if (recorded != null && recorded != SchemaFingerprint.of(columns)) {
            return demote(fact, LearnedFactTrust.NEEDS_REVIEW, "table columns changed since this was recorded")
        }
        return unchanged(fact)
    }

    /**
     * The check a TABLE listing can make without reading columns: a ref whose table is no
     * longer listed is `stale`. Nothing else can be decided here — no columns, no fingerprint.
     */
    fun againstTables(
        fact: LearnedFact,
        listedTables: Set<String>,
    ): Verdict {
        if (fact.trust == LearnedFactTrust.RETIRED) return unchanged(fact)
        val missing = fact.refs.map { it.table }.firstOrNull { it !in listedTables }
        return if (missing != null) demote(fact, LearnedFactTrust.STALE, "table $missing no longer exists") else unchanged(fact)
    }

    private fun unchanged(fact: LearnedFact): Verdict = Verdict(fact.trust, storedDrift(fact))

    /** One-way: never soften an already-stale fact, never re-mark what is already marked. */
    private fun demote(
        fact: LearnedFact,
        to: LearnedFactTrust,
        message: String,
    ): Verdict = if (fact.trust == LearnedFactTrust.STALE) unchanged(fact) else Verdict(to, message)

    /** The message a previously demoted fact keeps carrying when the read that demoted it is not this one. */
    private fun storedDrift(fact: LearnedFact): String? =
        when (fact.trust) {
            LearnedFactTrust.STALE -> "a referenced column or table no longer exists"
            LearnedFactTrust.NEEDS_REVIEW -> "table columns changed since this was recorded"
            else -> null
        }
}
