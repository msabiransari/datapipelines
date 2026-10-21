package co.datapipelines.pipeline

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/** Distinct objects classified using only the versions admitted to the caller. */
data class Inventory(
    val draftOnly: Long,
    val releasedOnly: Long,
    val releasedWithDraft: Long,
    val discardedOnly: Long,
) {
    val objects: Long get() = draftOnly + releasedOnly + releasedWithDraft + discardedOnly
    val withDrafts: Long get() = draftOnly + releasedWithDraft
    val withReleases: Long get() = releasedOnly + releasedWithDraft
}

/**
 * #556 — the lifecycle classes an object can be filtered by, each the ONE definition of its
 * bucket: [predicate] reads the three per-object flags (`d` an admitted DRAFT, `r` an admitted
 * RELEASED, `x` an admitted DISCARDED version) that [InventorySource] computes, so a catalog
 * filtered by a bucket and the inventory's count of it cannot disagree (shell design §8: "the
 * graph should never disagree with its adjacent totals"). The first four are the inventory's
 * exclusive buckets A–D; [WITH_DRAFTS] (A + C) and [WITH_RELEASES] (B + C) are its two overlapping
 * summary counts. [wire] is the `lifecycle` query value; a request value is parsed into this enum
 * before any SQL, so no request text reaches a statement.
 */
enum class LifecycleBucket(
    val wire: String,
    val predicate: String,
) {
    DRAFT_ONLY("draft", "d AND NOT r"),
    RELEASED_ONLY("released", "r AND NOT d"),
    RELEASED_WITH_DRAFT("released-with-draft", "d AND r"),
    DISCARDED_ONLY("discarded", "x AND NOT d AND NOT r"),
    WITH_DRAFTS("with-drafts", "d"),
    WITH_RELEASES("with-releases", "r"),
    ;

    /** How many objects of [inventory] this bucket holds — the number its filtered catalog lists. */
    fun countIn(inventory: Inventory): Long =
        when (this) {
            DRAFT_ONLY -> inventory.draftOnly
            RELEASED_ONLY -> inventory.releasedOnly
            RELEASED_WITH_DRAFT -> inventory.releasedWithDraft
            DISCARDED_ONLY -> inventory.discardedOnly
            WITH_DRAFTS -> inventory.withDrafts
            WITH_RELEASES -> inventory.withReleases
        }

    companion object {
        /** The bucket a `lifecycle` value names, or null for any other text. */
        fun ofWire(raw: String): LifecycleBucket? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * One SQL statement supplies all buckets from one PostgreSQL read snapshot, without pagination.
 *
 * A: admitted DRAFT, no RELEASED. B: admitted RELEASED, no DRAFT. C: both. D: admitted versions
 * all DISCARDED. Unlike the live catalog, the unrestricted inventory includes discarded-only
 * objects (#553 / shell design §8); these currently have no catalog drill-down target. A narrowed
 * lens still admits RELEASED versions only, so A, C and D are zero, never inferred from hidden rows.
 * Identifiers come exclusively from [NavigationFamily]; workspace and names are bound parameters.
 */
object InventorySource {
    /** Aggregates the complete admitted set; an empty lens performs no database access. */
    fun aggregate(
        jdbc: NamedParameterJdbcTemplate,
        family: NavigationFamily,
        workspaceId: UUID,
        lens: ReadLens,
    ): Inventory {
        val names = (lens as? ReadLens.Only)?.names
        if (names != null && names.isEmpty()) return Inventory(0, 0, 0, 0)
        return jdbc
            .query(
                sql(family, narrowed = names != null),
                mapOf("workspace" to workspaceId, "names" to names),
            ) { rs, _ -> lifecycle(rs) }
            .single()
    }

    /**
     * #556 — the catalog half of a lifecycle filter: a boolean SQL condition on ONE row of the
     * family's table (aliased [alias]) that holds exactly when the object falls in [bucket],
     * computed from the same three flags, over the same admitted versions (a [narrowed] lens:
     * RELEASED rows only), as [aggregate]. It REPLACES a catalog's liveness predicate rather than
     * being ANDed to it: the live catalog excludes discarded-only objects, which the
     * [LifecycleBucket.DISCARDED_ONLY] bucket is made of. The name half of a narrowed lens is the
     * caller's, exactly as the catalog already applies it; nothing here is bound from a request.
     */
    fun membership(
        family: NavigationFamily,
        alias: String,
        bucket: LifecycleBucket,
        narrowed: Boolean,
    ): String {
        val released = if (narrowed) " AND v.$RELEASED_VERSION" else ""
        return "EXISTS (SELECT 1 FROM (SELECT $FLAGS FROM ${versionsTable(family)} v" +
            " WHERE v.${family.foreignKey} = $alias.id$released) a WHERE ${bucket.predicate})"
    }

    /**
     * The three per-object flags every bucket predicate reads — their ONE definition (#592): the
     * inventory's [admitted] CTE and the catalog's [membership] both select exactly this list over
     * versions aliased `v`, so a filtered catalog and its count cannot read different flags.
     */
    private const val FLAGS =
        "bool_or(v.status = 'DRAFT') AS d, bool_or(v.status = 'RELEASED') AS r, bool_or(v.status = 'DISCARDED') AS x"

    /** The narrowed lens's version rule: a lensed caller is admitted RELEASED versions only. */
    private const val RELEASED_VERSION = "status = 'RELEASED'"

    private fun versionsTable(family: NavigationFamily): String = family.table.removeSuffix("s") + "_versions"

    internal fun sql(
        family: NavigationFamily,
        narrowed: Boolean,
    ): String = "${admitted(family, narrowed)}\nSELECT $LIFECYCLE_COUNTS\n  FROM admitted"

    /**
     * #598a — the `admitted` CTE every inventory statement starts from: one row per object of the
     * workspace, its three flags computed over the versions admitted to the caller (a [narrowed]
     * lens: the bound names' RELEASED rows only). [perObject] adds further per-object aggregates
     * over the SAME admitted rows ([ChartInventorySource]'s release count), so the lifecycle and
     * every chart beside it read one definition of an object and of what is admitted to it.
     */
    internal fun admitted(
        family: NavigationFamily,
        narrowed: Boolean,
        perObject: String = "",
    ): String {
        val versions = versionsTable(family)
        val admitted = if (narrowed) "AND s.name IN (:names) AND v.$RELEASED_VERSION" else ""
        return """
            WITH admitted AS (
                SELECT $FLAGS$perObject
                  FROM ${family.table} s
                  JOIN $versions v ON v.${family.foreignKey} = s.id
                 WHERE s.workspace_id = :workspace $admitted
                 GROUP BY s.id
            )
            """.trimIndent()
    }

    /** #598a — the four exclusive buckets A–D as `admitted`'s count columns, each its bucket's own predicate. */
    internal val LIFECYCLE_COUNTS =
        listOf(
            LifecycleBucket.DRAFT_ONLY to "draft_only",
            LifecycleBucket.RELEASED_ONLY to "released_only",
            LifecycleBucket.RELEASED_WITH_DRAFT to "released_with_draft",
            LifecycleBucket.DISCARDED_ONLY to "discarded_only",
        ).joinToString(",\n       ") { (bucket, column) -> "count(*) FILTER (WHERE ${bucket.predicate}) AS $column" }

    /** Reads the four lifecycle columns [LIFECYCLE_COUNTS] names from the current row. */
    internal fun lifecycle(rs: java.sql.ResultSet): Inventory =
        Inventory(
            draftOnly = rs.getLong("draft_only"),
            releasedOnly = rs.getLong("released_only"),
            releasedWithDraft = rs.getLong("released_with_draft"),
            discardedOnly = rs.getLong("discarded_only"),
        )
}
