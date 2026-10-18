package co.datapipelines.pipeline

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/** One recently modified object: its identity, when it last changed, and whether an admitted draft exists. */
data class RecentRow(
    val id: UUID,
    val name: String,
    val modifiedAt: Instant,
    val hasDraft: Boolean,
)

/**
 * #556 — a family's most recently modified objects, the overview's "Recently updated" list (shell
 * design §8 "Continue work"): the family's modification data, not anyone's per-user history.
 *
 * No single column is authoritative: an in-place draft write moves only its version row's
 * `updated_at` for most families, a release only `released_at`, a discard only `discarded_at`, and
 * the parent's `updated_at` moves on create, release, restore and switch. So the time is the
 * GREATEST of the parent's `updated_at` and, over the ADMITTED versions, each version's last draft
 * write (`updated_at`, NULL until the first one, so its `created_at`), release and discard.
 *
 * A narrowed lens is [InventorySource]'s rule exactly: admitted names, RELEASED versions only,
 * and the time is then the latest `released_at` alone (a pre-stamp row: its `created_at`) —
 * neither a hidden draft's write nor the parent's own clock, which a draft operation can move,
 * may reorder or reveal anything for a reader who sees only releases. Only objects with an admitted DRAFT or RELEASED version are
 * listed: a discarded-only object has no workspace page to link. One statement, one snapshot;
 * identifiers come from [NavigationFamily] alone, the workspace, names and limit are bound.
 */
object RecentSource {
    /** The overview's list length (shell design §8: "up to five"). */
    const val DEFAULT_LIMIT = 5

    /** The newest [limit] admitted objects, newest first; an empty lens performs no database access. */
    fun recent(
        jdbc: NamedParameterJdbcTemplate,
        family: NavigationFamily,
        workspaceId: UUID,
        lens: ReadLens,
        limit: Int = DEFAULT_LIMIT,
    ): List<RecentRow> {
        val names = (lens as? ReadLens.Only)?.names
        if (names != null && names.isEmpty()) return emptyList()
        return jdbc.query(
            sql(family, narrowed = names != null),
            mapOf("workspace" to workspaceId, "names" to names, "limit" to limit),
        ) { rs, _ ->
            RecentRow(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                modifiedAt = rs.getTimestamp("modified_at").toInstant(),
                hasDraft = rs.getBoolean("has_draft"),
            )
        }
    }

    internal fun sql(
        family: NavigationFamily,
        narrowed: Boolean,
    ): String {
        val versions = family.table.removeSuffix("s") + "_versions"
        val admitted = if (narrowed) "AND s.name IN (:names) AND v.status = 'RELEASED'" else ""
        val modified =
            if (narrowed) {
                // A RELEASED row always carries its stamp in the product; a row from before the
                // stamp existed reads its creation instead of a NULL time.
                "coalesce(max(v.released_at), max(v.created_at))"
            } else {
                "GREATEST(s.updated_at, max(coalesce(v.updated_at, v.created_at)), max(v.released_at), max(v.discarded_at))"
            }
        return """
            SELECT s.id, s.name, $modified AS modified_at,
                   bool_or(v.status = 'DRAFT') AS has_draft
              FROM ${family.table} s
              JOIN $versions v ON v.${family.foreignKey} = s.id
             WHERE s.workspace_id = :workspace $admitted
             GROUP BY s.id, s.name, s.updated_at
            HAVING bool_or(v.status IN ('DRAFT', 'RELEASED'))
             ORDER BY modified_at DESC, s.name COLLATE "C"
             LIMIT :limit
            """.trimIndent()
    }
}
