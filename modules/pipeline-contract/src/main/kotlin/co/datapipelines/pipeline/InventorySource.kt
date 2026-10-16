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
            ) { rs, _ ->
                Inventory(
                    draftOnly = rs.getLong("draft_only"),
                    releasedOnly = rs.getLong("released_only"),
                    releasedWithDraft = rs.getLong("released_with_draft"),
                    discardedOnly = rs.getLong("discarded_only"),
                )
            }.single()
    }

    internal fun sql(
        family: NavigationFamily,
        narrowed: Boolean,
    ): String {
        val versions = family.table.removeSuffix("s") + "_versions"
        val admitted = if (narrowed) "AND s.name IN (:names) AND v.status = 'RELEASED'" else ""
        return """
            WITH admitted AS (
                SELECT bool_or(v.status = 'DRAFT') AS d,
                       bool_or(v.status = 'RELEASED') AS r,
                       bool_or(v.status = 'DISCARDED') AS x
                  FROM ${family.table} s
                  JOIN $versions v ON v.${family.foreignKey} = s.id
                 WHERE s.workspace_id = :workspace $admitted
                 GROUP BY s.id
            )
            SELECT count(*) FILTER (WHERE d AND NOT r) AS draft_only,
                   count(*) FILTER (WHERE r AND NOT d) AS released_only,
                   count(*) FILTER (WHERE d AND r) AS released_with_draft,
                   count(*) FILTER (WHERE x AND NOT d AND NOT r) AS discarded_only
              FROM admitted
            """.trimIndent()
    }
}
