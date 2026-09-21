package co.datapipelines.pipeline

/**
 * One **virtual folder** of the pipeline tree (067; template-hierarchy-design §3.1, §9.2).
 *
 * A folder is a name prefix and nothing else: there is no table, no column, no id and no row
 * anywhere that corresponds to one. It is derived, per request, from the names of the live
 * pipelines beneath it — which is why [pipelineCount] is always ≥ 1 and an *empty* folder is
 * unrepresentable rather than merely unrendered. Folder CRUD does not exist and cannot: there
 * is nothing to create, rename, move or delete.
 *
 * [path] is the full prefix (`nyc/mobility`) — the value the next level's prefix query takes;
 * [segment] is its last element (`mobility`), which is what a tree labels.
 */
data class PipelineFolder(
    val path: String,
    val segment: String,
    val pipelineCount: Int,
)

/**
 * **One level** of the pipeline tree: the direct sub-folders of a prefix and the direct
 * pipeline leaves under it, with the leaves' truthful total.
 *
 * One level per request is the whole contract (template-hierarchy-design §9.2, decision 2):
 * expanding `nyc` returns `nyc`'s children and nothing else — never `nyc/mobility`'s rows,
 * never the whole list, at any size. The two presentations are separate: this is BROWSE;
 * a non-empty search is a flat list of full paths served by `PipelineService.page` (§9.3).
 *
 * [folders] is not paged — it is a `GROUP BY` over one path segment — so [foldersTruncated]
 * reports an overflow honestly instead of cutting silently. [pipelines] is paged with the
 * screen's own pager, and [total] is the same predicate without paging so a level and its
 * pager can never disagree (the 034 E3 discipline).
 */
data class PipelineFolderLevel(
    val folders: List<PipelineFolder>,
    val foldersTruncated: Boolean,
    val pipelines: List<PipelineRecord>,
    val total: Int,
    val hasMore: Boolean,
) {
    /**
     * This level with its leaves removed — the shape the ROOT level renders since 077.
     *
     * §4.1 requires a folder, so the root of the tree is a directory of folders and nothing
     * else. Unlike templates, pipelines carry no deploy gate for the narrowing (§14.2: a
     * pipeline name is validated at save only), so a pre-077 flat row can still exist; this
     * drops it from the ROOT LEVEL's rendering, and only there. It stays reachable by search,
     * by `pipelines_list`, and by its UUID URL. [total] and [hasMore] go with the leaves, so a
     * level and its pager cannot disagree.
     */
    fun withoutLeaves(): PipelineFolderLevel = copy(pipelines = emptyList(), total = 0, hasMore = false)

    companion object {
        /** Leaves returned for one level when the caller states no page size. */
        const val DEFAULT_PAGE_LIMIT: Int = 25

        /** The hard cap on one level's leaves, and on its sub-folders before [foldersTruncated] fires. */
        const val MAX_PAGE_LIMIT: Int = 200

        /**
         * One level computed IN MEMORY over [records] — the lensed principal's tree (178).
         *
         * `PipelineTreeQueries.level` derives the same level in SQL over the whole workspace;
         * a lensed principal sees a name SET decided per request, and a `GROUP BY` cannot take
         * a set the query does not know. So the lensed read fetches the workspace's index rows
         * (metadata only — the same rows `PromotionService.plan` already read), keeps the
         * admitted ones, and this function applies the SQL's exact rules to them: scope by
         * `prefix/`, a sub-folder per first segment of the remainder that still carries a `/`,
         * a leaf per remainder that does not, folders ordered by segment and capped with the
         * overflow reported, leaves ordered by name and paged with the truthful total. The
         * unit test pins each rule against the SQL's stated contract so the two presentations
         * cannot drift.
         */
        fun of(
            records: List<PipelineRecord>,
            prefix: String?,
            offset: Int = 0,
            limit: Int = DEFAULT_PAGE_LIMIT,
            folderLimit: Int = MAX_PAGE_LIMIT,
        ): PipelineFolderLevel {
            val page = maxOf(0, offset)
            val leafLimit = limit.coerceIn(1, MAX_PAGE_LIMIT)
            val folderCap = folderLimit.coerceIn(1, MAX_PAGE_LIMIT)
            val scope = if (prefix.isNullOrEmpty()) "" else "$prefix/"
            val inScope = records.filter { it.name.startsWith(scope) }.map { it to it.name.removePrefix(scope) }
            val folders =
                inScope
                    .filter { (_, remainder) -> '/' in remainder }
                    .groupBy { (_, remainder) -> remainder.substringBefore('/') }
                    .toSortedMap()
                    .map { (segment, rows) -> PipelineFolder(path = scope + segment, segment = segment, pipelineCount = rows.size) }
            val leaves = inScope.filter { (_, remainder) -> '/' !in remainder }.map { it.first }.sortedBy { it.name }
            return PipelineFolderLevel(
                folders = folders.take(folderCap),
                foldersTruncated = folders.size > folderCap,
                pipelines = leaves.drop(page).take(leafLimit),
                total = leaves.size,
                hasMore = leaves.size > page + leafLimit,
            )
        }
    }
}
