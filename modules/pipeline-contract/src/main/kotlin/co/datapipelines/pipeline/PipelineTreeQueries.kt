package co.datapipelines.pipeline

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The three SQL statements behind [PipelineRepository.listFolder] — ONE level of the pipeline
 * tree (067; template-hierarchy-design §8, §9.2).
 *
 * They live together, and away from [PipelineRepository], for one reason each way. Together:
 * the folders, the leaves and the total share [TREE_WHERE], and a level whose folders, rows
 * and count disagreed would be a screen that lies about itself — the 034 E3 discipline, made
 * structural. Away: [PipelineRepository]'s KDoc argues it must stay the single owner of every
 * `pipeline_versions` statement because those carry write invariants between them; this is
 * three read-only queries over `pipelines` that carry none, so the argument does not reach
 * them. The repository still owns the only public entry point.
 *
 * A folder is a name prefix (§3.1), so the split between folder and leaf is a single
 * expression over the remainder of a name below `prefix/`: a remainder that still contains
 * `/` belongs to a sub-folder, one that does not is a leaf. `a/b` and `a/b/c` coexisting
 * therefore yield BOTH a leaf `b` and a folder `b` at the same level — exactly what §4.3
 * describes, and what `PipelineRepositoryIntegrationTest` pins.
 *
 * [prefix] `null` (or empty) is the tree's **root** level: the first segment of every
 * multi-segment name. Flat legacy names have no first-segment-plus-remainder, so they are
 * absent from the folders and present as root leaves — nothing is renamed or reorganised.
 *
 * The folder counts are over LIVE pipelines beneath each folder, so a folder with nothing
 * beneath it does not come back at all: an empty folder is unrepresentable, not merely
 * unrendered (§9.1). Leaves are `name`-ordered so paging is stable — deliberately NOT
 * [PipelineRepository.findAll]'s `created_at DESC`, which is a listing's order and would make
 * a tree jump around as pipelines are added.
 *
 * `q` is deliberately absent: browsing and searching are different presentations (§9.2), and
 * search is a flat list of full paths served by [PipelineService.page], not a pruned tree.
 *
 * **Index use.** `workspace_id = :workspaceId` is the leading column of
 * `uq_pipelines_workspace_name (workspace_id, name)`, so every query below is a bounded index
 * range scan over one workspace rather than a full table scan — the point of the prefix form
 * over a `LIKE '%…%'` search.
 */
internal class PipelineTreeQueries(
    private val jdbc: NamedParameterJdbcTemplate,
    /** [PipelineRepository]'s own `pipelines` projection — handed over, never re-declared. */
    private val selectColumns: String,
    private val mapper: RowMapper<PipelineRecord>,
) {
    fun level(
        workspaceId: UUID,
        prefix: String?,
        offset: Int,
        limit: Int,
        folderLimit: Int,
    ): PipelineFolderLevel {
        val page = maxOf(0, offset)
        val leafLimit = limit.coerceIn(1, PipelineFolderLevel.MAX_PAGE_LIMIT)
        val folderCap = folderLimit.coerceIn(1, PipelineFolderLevel.MAX_PAGE_LIMIT)
        val params = params(workspaceId, prefix)
        val folderProbe = folders(params, prefix, folderCap)
        val leafProbe = leaves(params, page, leafLimit)
        return PipelineFolderLevel(
            folders = folderProbe.take(folderCap),
            foldersTruncated = folderProbe.size > folderCap,
            pipelines = leafProbe.take(leafLimit),
            total = total(params),
            hasMore = leafProbe.size > leafLimit,
        )
    }

    /** A `GROUP BY` over the FIRST path segment below the prefix — one probe row past the cap. */
    private fun folders(
        params: Map<String, Any?>,
        prefix: String?,
        folderCap: Int,
    ): List<PipelineFolder> =
        jdbc.query(
            """
            SELECT split_part(substring(name FROM CAST(:cutFrom AS INT)), '/', 1) AS segment,
                   COUNT(*) AS pipeline_count
              FROM pipelines
            $TREE_WHERE
              AND position('/' IN substring(name FROM CAST(:cutFrom AS INT))) > 0
             GROUP BY 1
             ORDER BY 1
             LIMIT :limit
            """.trimIndent(),
            params + mapOf("limit" to folderCap + 1),
        ) { rs, _ ->
            val segment = rs.getString("segment")
            PipelineFolder(
                path = if (prefix.isNullOrEmpty()) segment else "$prefix/$segment",
                segment = segment,
                pipelineCount = rs.getInt("pipeline_count"),
            )
        }

    /** The level's own rows — a remainder with no `/` — one probe row past the page. */
    private fun leaves(
        params: Map<String, Any?>,
        page: Int,
        leafLimit: Int,
    ): List<PipelineRecord> =
        jdbc.query(
            """
            $selectColumns
            $TREE_WHERE
              AND position('/' IN substring(name FROM CAST(:cutFrom AS INT))) = 0
             ORDER BY name
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            params + mapOf("limit" to leafLimit + 1, "offset" to page),
            mapper,
        )

    /** The same predicate without paging, so a level and its pager cannot drift apart. */
    private fun total(params: Map<String, Any?>): Int =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM pipelines
                $TREE_WHERE
                  AND position('/' IN substring(name FROM CAST(:cutFrom AS INT))) = 0
                """.trimIndent(),
                params,
                Int::class.java,
            ),
        )

    /**
     * The bind values every query above shares.
     *
     * `namePattern` is the prefix scope — `nyc/mobility/%` for a folder, `%` for the root
     * (where every name is in scope by definition). The prefix's own LIKE metacharacters are
     * escaped, so a folder literally named `100%_off` scopes to itself instead of to
     * everything. `cutFrom` is the 1-based offset at which a name's remainder *below* the
     * prefix begins: `prefix.length + 2` skips the prefix and its `/`, and `1` at the root
     * means the whole name.
     */
    private fun params(
        workspaceId: UUID,
        prefix: String?,
    ): Map<String, Any?> =
        mapOf(
            "workspaceId" to workspaceId,
            "namePattern" to if (prefix.isNullOrEmpty()) "%" else "${escapeLike(prefix)}/%",
            "cutFrom" to if (prefix.isNullOrEmpty()) 1 else prefix.length + 2,
        )

    private companion object {
        /**
         * The live-in-this-workspace predicate of ONE tree level, shared by all three queries.
         *
         * A DISCARDED entity is out (the §3.2 derivation — 101 replaced the `is_deleted`
         * column), the same rule the flat listing follows: a discarded pipeline is not
         * browsable, and a folder derived from one would be a folder with nothing in it.
         * `namePattern` is CAST in the SQL for the reason every optional bind in this module is
         * — a bare parameter gives Postgres no type to infer and the statement will not prepare.
         */
        val TREE_WHERE =
            """
            WHERE EXISTS (SELECT 1 FROM pipeline_versions lv
                           WHERE lv.pipeline_id = pipelines.id
                             AND lv.status IN ('DRAFT','RELEASED'))
              AND workspace_id = :workspaceId
              AND name LIKE CAST(:namePattern AS TEXT) ESCAPE '\'
            """.trimIndent()

        /**
         * Escapes a value that becomes part of a LIKE pattern, so its own metacharacters match
         * literally. The prefix is bound as a parameter either way — this is about a prefix
         * containing `%` or `_` scoping to itself rather than to everything.
         */
        fun escapeLike(term: String): String =
            term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
    }
}
