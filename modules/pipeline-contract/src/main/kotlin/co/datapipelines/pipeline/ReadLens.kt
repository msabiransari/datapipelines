package co.datapipelines.pipeline

/**
 * What a principal is allowed to SEE of a workspace's pipelines or templates — the promoter
 * lens of the roles design (§3.1, D5; 178), as a value every read takes.
 *
 * A lens is not a permission: the interceptor has already admitted the caller to the read
 * (auth.md §7.6 says `lens` in the promoter's cell, never ✗). It narrows WHAT the read returns.
 * Two shapes and nothing in between:
 *
 * - [Everything] — every other role. The read paths are byte-for-byte what they were before
 *   178: the same SQL, the same paging, no extra query.
 * - [Only] — a lensed principal. The admitted NAMES are decided per request by the surface that
 *   knows the principal (web's `PromotableViews`), and a read filters by name: a list keeps the
 *   admitted rows, a get of a row that is not admitted answers null — the same null a row in
 *   another workspace gets, so a hidden object is indistinguishable from an absent one (the
 *   404 rule, auth.md §11A).
 *
 * ## Why an explicit parameter and not a thread-local
 * `PipelineService` lives in this module, which knows no principal; and an MCP tool call runs on
 * the SDK's `boundedElastic` thread where Spring Security's thread-local is EMPTY (the 134
 * lesson, `DomainConfiguration`'s `contractDatasourceRegistry` KDoc). A lens injected by the
 * interceptor would silently be [Everything] on every MCP read. So the lens travels the way
 * `workspaceId` does: as an argument with no default, so that a missed caller is a compile error
 * rather than a leak. Templates share the type — this module is the one both aggregates see.
 */
sealed interface ReadLens {
    /** True when a row named [name] may be returned to this principal. */
    fun admits(name: String): Boolean

    /** True for [Everything] — the fast path that keeps the pre-178 SQL untouched. */
    val isEverything: Boolean get() = this === Everything

    /** No narrowing: the read returns what the workspace predicate alone returns. */
    data object Everything : ReadLens {
        override fun admits(name: String): Boolean = true
    }

    /**
     * Exactly [names] and nothing else. An EMPTY set is a legal, meaningful lens — it is what a
     * lensed principal holds while the higher environment cannot be read (fail closed, §3.1).
     */
    data class Only(
        val names: Set<String>,
    ) : ReadLens {
        override fun admits(name: String): Boolean = name in names

        override fun toString(): String = "ReadLens.Only(${names.size} names)"
    }

    companion object {
        /** A lens admitting nothing — the fail-closed view. */
        val NOTHING: ReadLens = Only(emptySet())
    }
}

/** The rows of this list the [lens] admits, each named by [name]; the list itself under [ReadLens.Everything]. */
fun <T> List<T>.through(
    lens: ReadLens,
    name: (T) -> String,
): List<T> = if (lens.isEverything) this else filter { lens.admits(name(it)) }
