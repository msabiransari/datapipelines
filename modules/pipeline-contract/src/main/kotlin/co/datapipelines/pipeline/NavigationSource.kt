package co.datapipelines.pipeline

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/** Closed storage descriptors; identifiers interpolated into SQL never come from a request. */
enum class NavigationFamily(
    val table: String,
    val foreignKey: String,
    val route: String,
) {
    PIPELINES("pipelines", "pipeline_id", "pipelines"),
    TEMPLATES("templates", "template_id", "templates"),
    DASHBOARDS("dashboards", "dashboard_id", "dashboards"),
    VISUALIZATIONS("visualizations", "visualization_id", "visualizations"),
    PARAMETER_SETS("parameter_sets", "parameter_set_id", "parameter-sets"),
}

/** Body-free row. Folder and artifact keys remain distinct even when their paths collide. */
data class NavigationRow(
    val path: String,
    val name: String,
    val id: UUID?,
    val version: Int?,
    val draftVersion: Int?,
) {
    val key: String get() = if (id == null) "folder:$path" else "artifact:$id"
    val orderKey: String get() = if (id == null) "0:$path" else "1:$path"
}

/** One bounded keyset page over the authorized level or authorized NAME matches. */
data class NavigationRequest(
    val workspaceId: UUID,
    val lens: ReadLens,
    val root: String,
    val parent: String,
    val query: String?,
    val after: String = "",
    val limit: Int = PAGE_SIZE,
) {
    companion object {
        const val PAGE_SIZE = 200
    }
}

/**
 * Common SQL projection used by repository-owned family adapters. Filters workspace, membership and
 * released-only lenses BEFORE deriving folders. Browse pages folders and leaves together; search
 * pages only matches. No body is selected or decoded, and no per-node lookup is necessary.
 */
object NavigationSource {
    fun page(
        jdbc: NamedParameterJdbcTemplate,
        family: NavigationFamily,
        request: NavigationRequest,
    ): List<NavigationRow> {
        val names = (request.lens as? ReadLens.Only)?.names
        if (names != null && names.isEmpty()) return emptyList()
        val prefix = (if (request.query == null) request.parent else request.root).let { if (it.isEmpty()) "" else "$it/" }
        val parameters =
            mapOf(
                "workspace" to request.workspaceId,
                "prefix" to (literal(prefix) + "%"),
                "cut" to (prefix.length + 1),
                "pattern" to ("%" + literal(request.query.orEmpty()) + "%"),
                "after" to request.after,
                "limit" to request.limit.coerceIn(1, NavigationRequest.PAGE_SIZE + 1),
                "names" to names,
            )
        return jdbc.query(sql(family, names != null, request.query != null), parameters) { rs, _ ->
            NavigationRow(
                path = rs.getString("path"),
                name = rs.getString("name"),
                id = rs.getObject("id", UUID::class.java),
                version = rs.getObject("version") as? Int,
                draftVersion = rs.getObject("draft_version") as? Int,
            )
        }
    }

    private fun sql(
        family: NavigationFamily,
        narrowed: Boolean,
        search: Boolean,
    ): String {
        val table = family.table
        val versions = if (family == NavigationFamily.PARAMETER_SETS) "parameter_set_versions" else table.removeSuffix("s") + "_versions"
        val fk = family.foreignKey
        val admitted = if (narrowed) "AND s.name IN (:names) AND v.status = 'RELEASED'" else ""
        val listed =
            if (narrowed) {
                "s.current_version"
            } else {
                "COALESCE(s.current_version, (SELECT MAX(d.version) FROM $versions d WHERE d.$fk = s.id AND d.status = 'DRAFT'))"
            }
        val draft = if (narrowed) "NULL::integer" else "(SELECT MAX(d.version) FROM $versions d WHERE d.$fk = s.id AND d.status = 'DRAFT')"
        val projection =
            if (search) {
                """
                SELECT s.name AS path, s.display_name AS name, s.id, v.version, $draft AS draft_version, '1:' || s.name AS ordering
                  FROM admitted s
                  JOIN $versions v ON v.$fk = s.id AND v.version = s.listed_version
                 WHERE s.name ILIKE :pattern ESCAPE '\' OR s.display_name ILIKE :pattern ESCAPE '\'
                """.trimIndent()
            } else {
                """
                SELECT substring(s.name FROM 1 FOR CAST(:cut AS integer) - 1) || split_part(substring(s.name FROM CAST(:cut AS integer)), '/', 1) AS path,
                       split_part(substring(s.name FROM CAST(:cut AS integer)), '/', 1) AS name,
                       NULL::uuid AS id, NULL::integer AS version, NULL::integer AS draft_version,
                       '0:' || substring(s.name FROM 1 FOR CAST(:cut AS integer) - 1) || split_part(substring(s.name FROM CAST(:cut AS integer)), '/', 1) AS ordering
                  FROM admitted s WHERE position('/' IN substring(s.name FROM CAST(:cut AS integer))) > 0
                 GROUP BY 1, 2, 6
                UNION ALL
                SELECT s.name AS path, s.display_name AS name, s.id, v.version, $draft AS draft_version, '1:' || s.name AS ordering
                  FROM admitted s JOIN $versions v ON v.$fk = s.id AND v.version = s.listed_version
                 WHERE position('/' IN substring(s.name FROM CAST(:cut AS integer))) = 0
                """.trimIndent()
            }
        return """
            WITH admitted AS (
                SELECT s.*, $listed AS listed_version FROM $table s
                JOIN $versions v ON v.$fk = s.id AND v.version = $listed
                WHERE s.workspace_id = :workspace AND s.name LIKE :prefix ESCAPE '\'
                  AND v.status IN ('DRAFT', 'RELEASED') $admitted
            ), projected AS ($projection)
            SELECT path, name, id, version, draft_version FROM projected
             WHERE ordering COLLATE "C" > CAST(:after AS text) COLLATE "C"
             ORDER BY ordering COLLATE "C" LIMIT :limit
            """.trimIndent()
    }

    /** LIKE metacharacters are data, for root, parent and search alike. */
    private fun literal(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
