package co.datapipelines.datasources.semantics

import java.time.Instant
import java.util.UUID

/*
 * The learned semantic layer's rows (learned-semantic-layer design §3, metadata-db §4.18) —
 * what an agent learned about a datasource that introspection could not tell it, kept as
 * structured rows keyed by the object they describe and served beside the live metadata.
 *
 * Nothing here duplicates what JDBC metadata already provides (D-S2): the kind list has no
 * type, nullable, key, comment or partition entry, and `SchemaFingerprint` is what ties a fact
 * to the shape of the table it was learned against.
 */

/** D-S1 — which object a fact is bound to: the data (visible wherever the datasource is granted) or one workspace's meaning. */
enum class LearnedFactScope {
    DATASOURCE,
    WORKSPACE,
}

/**
 * The closed kind list (design §4; the `chk_learned_facts_kind` CHECK; enums.md §19). Each kind
 * belongs to exactly one [scope] — the database states the same rule in
 * `chk_learned_facts_kind_scope`, and a spec-drift test holds the three lists to one truth.
 */
enum class LearnedFactKind(
    val wire: String,
    val scope: LearnedFactScope,
) {
    UNIT("unit", LearnedFactScope.DATASOURCE),
    TIME_ZONE("time_zone", LearnedFactScope.DATASOURCE),
    SAMPLING("sampling", LearnedFactScope.DATASOURCE),
    GRAIN("grain", LearnedFactScope.DATASOURCE),
    WINDOW("window", LearnedFactScope.DATASOURCE),
    ENUM_MEANING("enum_meaning", LearnedFactScope.DATASOURCE),
    JOIN("join", LearnedFactScope.DATASOURCE),
    CAVEAT("caveat", LearnedFactScope.DATASOURCE),
    FORMAT("format", LearnedFactScope.DATASOURCE),
    DEFINITION("definition", LearnedFactScope.WORKSPACE),
    EXCLUSION("exclusion", LearnedFactScope.WORKSPACE),
    PREFERENCE("preference", LearnedFactScope.WORKSPACE),
    ;

    companion object {
        /** The kind for a wire token, or null — the caller decides the refusal code. */
        fun fromWire(token: String): LearnedFactKind? = entries.firstOrNull { it.wire == token }

        /** The datasource-as-a-whole kinds `datasources_get` carries (design §7.2). */
        val DATASOURCE_WIDE: Set<LearnedFactKind> = setOf(WINDOW, SAMPLING)
    }
}

/**
 * Design §5 — how far a fact may be trusted, and the mechanical demotions. Ordered as the
 * design lists them; nothing compares ordinals, and [isLive] is the only derived question.
 */
enum class LearnedFactTrust(
    val wire: String,
) {
    ASSERTED("asserted"),
    OBSERVED("observed"),
    VERIFIED("verified"),
    NEEDS_REVIEW("needs_review"),
    STALE("stale"),
    RETIRED("retired"),
    ;

    /** True unless the fact is retired — the served set (§6: retired facts are listable, never served). */
    val isLive: Boolean get() = this != RETIRED

    companion object {
        fun fromWire(token: String): LearnedFactTrust = entries.first { it.wire == token }
    }
}

/**
 * One structural reference (§3.1): a table on the fact's datasource, optionally one of its
 * columns. [schema] is the namespace filter as the introspection tools accept it — a single
 * label or the dotted `catalog.schema` form — null when the connection's current one applies.
 *
 * Validated against live introspection at record time; a ref that does not resolve is refused,
 * so the store never starts stale. [tableKey] is the per-table identity the fingerprint and the
 * drift check are keyed on.
 */
data class FactRef(
    val schema: String?,
    val table: String,
    val column: String?,
) {
    /** `schema.table` or `table` — the key of one referenced table across the fact's refs. */
    val tableKey: String get() = schema?.let { "$it.$table" } ?: table

    /** True when this ref names [table] within [namespace] — a null side matches anything (a lenient read). */
    fun matches(
        table: String,
        namespace: List<String>?,
    ): Boolean {
        if (this.table != table) return false
        if (schema == null || namespace.isNullOrEmpty()) return true
        return schema == namespace.joinToString(".") || schema == namespace.last()
    }
}

/** One `learned_facts` row (metadata-db §4.18). */
data class LearnedFact(
    val id: UUID,
    val scope: LearnedFactScope,
    val workspaceId: UUID?,
    val datasourceName: String,
    val kind: LearnedFactKind,
    val fact: String,
    val refs: List<FactRef>,
    val evidenceSql: String?,
    val evidenceSummary: String?,
    val trust: LearnedFactTrust,
    val schemaFingerprint: String,
    val recordedBy: UUID,
    val recordedVia: String,
    val recordedIn: UUID,
    val sourcePipelineId: UUID?,
    val sourceVersion: Int?,
    val recordedAt: Instant,
    val verifiedBy: UUID?,
    val verifiedAt: Instant?,
    val supersedes: UUID?,
    val retiredAt: Instant?,
    val retiredReason: String?,
) {
    /** The refs that name [table] (within [namespace]) — which of this fact's columns, if any, a column listing should show it on. */
    fun refsOn(
        table: String,
        namespace: List<String>?,
    ): List<FactRef> = refs.filter { it.matches(table, namespace) }
}
