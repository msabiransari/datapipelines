package co.datapipelines.web.ui

import co.datapipelines.application.datasources.LakeTable
import co.datapipelines.pipeline.PipelineNameGrammar
import org.springframework.ui.Model
import java.security.MessageDigest

/**
 * The LAKE datasource detail's tree model (round 089 §A; the 058/067 explorer pattern of
 * [TemplateBrowseModel], narrowed to read-only).
 *
 * The tree is the datasource's REGISTERED lake tables ([LakeTable] rows from the dp-lake
 * catalog, metadata-db §4.15), one level per request: expanding a folder issues ONE more
 * request for ONE more level, and nothing ships the whole registry to the browser or assembles
 * a tree in JS. The levels are derived in Kotlin from the datasource's registry listing — a
 * bounded, per-datasource set read from our own metadata DB, not a customer database's
 * namespace — so the derivation needs no SQL prefix query of its own.
 *
 * Read-only by construction: there is no selection, no detail pane and no CRUD anywhere in
 * this tree (R10 — registration stays REST/MCP-only). A leaf renders its `format` and
 * `partition_column` badges and nothing actionable.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero
 * DI stereotypes in production code (015 / module-structure §8.4), and `ArchitectureGuardTest`
 * enforces it.
 */
class LakeTableBrowseModel {
    /**
     * Fills [model] for one **tree level** — [prefix] `null`/empty is the root — and returns
     * the view name to render. The prefix is the `/`-joined namespace path (segments carry no
     * `/` or `.` by the registry's grammar, so the join is unambiguous).
     */
    fun fillLevel(
        model: Model,
        tables: List<LakeTable>,
        prefix: String?,
        offset: Int,
    ): String {
        val page = maxOf(0, offset)
        val segments =
            prefix
                ?.split('/')
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        // A prefix that is not a legal namespace cannot name a real folder (the TemplateBrowseModel
        // rule): render an ordinary empty level, never an error, and never a wildcard match.
        if (segments.any { !PipelineNameGrammar.matchesSegment(it) || it.contains('.') }) {
            return emptyLevel(model, prefix ?: "")
        }
        val folders =
            tables
                .filter { it.namespace.size > segments.size && it.namespace.subList(0, segments.size) == segments }
                .groupingBy { it.namespace[segments.size] }
                .eachCount()
                .toSortedMap()
        val leaves = tables.filter { it.namespace == segments }.sortedBy { it.name }
        model.addAttribute("prefix", segments.joinToString("/"))
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute(
            "folders",
            folders.entries.take(FOLDER_LIMIT).map { (segment, count) ->
                LakeTableFolderView(
                    path = (segments + segment).joinToString("/"),
                    segment = segment,
                    tableCount = count,
                )
            },
        )
        model.addAttribute("foldersTruncated", folders.size > FOLDER_LIMIT)
        model.addAttribute("tables", leaves.drop(page).take(PAGE_SIZE))
        model.addAttribute("offset", page)
        model.addAttribute("hasMore", leaves.size > page + PAGE_SIZE)
        model.addAttribute("total", leaves.size)
        return LEVEL_VIEW
    }

    /** A level that cannot exist: rendered as an ordinary empty level, never as an error. */
    private fun emptyLevel(
        model: Model,
        prefix: String,
    ): String {
        model.addAttribute("prefix", prefix)
        model.addAttribute("levelId", levelId(prefix))
        model.addAttribute("folders", emptyList<LakeTableFolderView>())
        model.addAttribute("foldersTruncated", false)
        model.addAttribute("tables", emptyList<LakeTable>())
        model.addAttribute("offset", 0)
        model.addAttribute("hasMore", false)
        model.addAttribute("total", 0)
        return LEVEL_VIEW
    }

    companion object {
        /** A level's own leaves before the level pages — the templates screen's page size. */
        const val PAGE_SIZE = 25

        /** Sub-folders returned for one level before the level reports an overflow rather than hiding it. */
        const val FOLDER_LIMIT = 200

        /** The root level's container id — the detail page's stable swap root. */
        const val ROOT_LEVEL_ID = "lake-table-tree"

        const val LEVEL_VIEW = "partials/lake-table-tree-level"

        /** Hex characters of a nested level's id digest — the TemplateBrowseModel rule. */
        private const val LEVEL_ID_HEX_LENGTH = 16

        /**
         * The DOM id of the container holding one tree level — a digest, for
         * [TemplateBrowseModel.levelId]'s reason: the placeholder the folder renders and the
         * root of the fragment that replaces it are derived in ONE place and cannot disagree.
         */
        fun levelId(prefix: String?): String {
            if (prefix.isNullOrEmpty()) return ROOT_LEVEL_ID
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix.toByteArray(Charsets.UTF_8))
            return "lake-level-" + digest.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}

/**
 * One virtual namespace folder, as the tree fragment needs it — the [TemplateFolderView]
 * precedent: the id of the container that will hold the folder's level is derived HERE, once.
 */
data class LakeTableFolderView(
    val path: String,
    val segment: String,
    val tableCount: Int,
) {
    val levelId: String get() = LakeTableBrowseModel.levelId(path)
}
