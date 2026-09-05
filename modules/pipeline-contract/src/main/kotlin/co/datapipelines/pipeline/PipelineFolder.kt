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
    companion object {
        /** Leaves returned for one level when the caller states no page size. */
        const val DEFAULT_PAGE_LIMIT: Int = 25

        /** The hard cap on one level's leaves, and on its sub-folders before [foldersTruncated] fires. */
        const val MAX_PAGE_LIMIT: Int = 200
    }
}
