package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.ReadLens
import org.slf4j.LoggerFactory
import java.util.EnumMap
import java.util.UUID

/**
 * 079 §A, #554 — the admitted-resource count the resource browser's header carries for each of
 * the five browsable families, read on every shell render that shows them.
 *
 * Until #554 the rail's Pipelines and Templates rows carried two of these as badges; the shell
 * record (§3) moved counts into the browser header — "resource counts belong in the resource
 * browser, not repeated beside every primary item" — and every family with a browser now has
 * one. Each count is the family's CATALOG size: the same live predicate its catalog page
 * totals (`countAll`/`count`), so the header can never say "9" beside a catalog of eight.
 *
 * ## Why no cache (#574)
 *
 * Until #574 a 60-second TTL entry per workspace sat in front of the reads, with no eviction:
 * in the header, directly above a tree that reads with `no-store`, a create over MCP or the
 * editor left "Pipelines 0" beside the new row for up to a minute. Evicting on every write
 * would couple the five families' services to a decoration; instead the header and the tree
 * are two reads of the same tables in the same navigation, so they cannot disagree past one.
 * The five reads are one `COUNT(*)` each over workspace-scoped rows (the templates one joins
 * `template_versions`); #574 measured them at about 1 ms of database time together on a seeded
 * workspace, less than the tree's own root query. They run only when a render reads a value
 * (see [Counts]), never on a JSON request.
 *
 * A failed read is not remembered either: the next render reads again.
 *
 * @param sources one catalog-size read per family, keyed by the closed [NavigationFamily] set;
 *   a family without a source has no count (the header omits it).
 */
class NavCounts(
    private val sources: Map<NavigationFamily, (UUID) -> Int>,
) {
    private val log = LoggerFactory.getLogger(NavCounts::class.java)

    /**
     * The browser header's counts for [workspaceId], read when first asked for ([Counts]). Never
     * throws: a database that cannot answer must not take the whole shell down with it, so a
     * failed read logs and yields nulls, and the template renders the header with no count at all
     * (`th:if` on the value) — the record's "unknown/unavailable counts are omitted, never
     * replaced by zero".
     */
    fun forWorkspace(
        workspaceId: UUID?,
        view: LensedView = LensedView.EVERYTHING,
    ): Counts {
        if (workspaceId == null) return Counts.NONE
        // A lensed principal whose higher environment could not be read admits nothing BECAUSE
        // nothing is known (fail closed, roles design §3.1): that is an unknown count, not zero.
        if (view.unavailable != null) return Counts.NONE
        // 178: a lensed principal's count is the SIZE of the admitted set — the same names every
        // lensed list shows, so the header can never say "3 pipelines" beside an empty tree. No
        // read for those arms: the view already paid for the answer.
        val lenses = NavigationFamily.entries.associateWith { view.arm(it) }
        val narrowed = lenses.mapNotNull { (family, lens) -> (lens as? ReadLens.Only)?.let { family to it.names.size } }.toMap()
        if (narrowed.size == NavigationFamily.entries.size) return Counts.of(narrowed)
        // A failed live read leaves only the narrowed arms: the rest are unknown, never zero. The read
        // waits for the first value the template asks for (#574: a JSON request asks for none).
        return Counts.deferred { (live(workspaceId) ?: emptyMap()) + narrowed }
    }

    /** The live counts, read now, or null when the read failed. */
    private fun live(workspaceId: UUID): Map<NavigationFamily, Int>? =
        runCatching {
            sources.mapValuesTo(EnumMap(NavigationFamily::class.java)) { (_, read) -> read(workspaceId) }
        }.getOrElse { failure ->
            log.warn("nav counts unavailable for workspace {}: {}", workspaceId, failure.toString())
            null
        }

    /**
     * One count per family, null where unknown. The template reads the properties by name
     * (`navCounts.pipelines` …); [of] serves code that holds a [NavigationFamily].
     *
     * #574: the values may be DEFERRED — [forWorkspace] hands back counts whose live read runs on
     * the first property read and is kept for this instance, so one render makes one read and a
     * request that renders nothing makes none. The shell advice that builds this attribute runs
     * for every shell page (`ShellPage`, #652), so a JSON API call pays no counts at all; the
     * deferral still bounds a shell render to the counts its template actually shows. Equality
     * is by value (it forces the read).
     */
    class Counts private constructor(
        private val values: Lazy<Map<NavigationFamily, Int>>,
    ) {
        constructor(
            pipelines: Int?,
            templates: Int?,
            dashboards: Int? = null,
            visualizations: Int? = null,
            parameterSets: Int? = null,
        ) : this(
            lazyOf(
                listOfNotNull(
                    pipelines?.let { NavigationFamily.PIPELINES to it },
                    templates?.let { NavigationFamily.TEMPLATES to it },
                    dashboards?.let { NavigationFamily.DASHBOARDS to it },
                    visualizations?.let { NavigationFamily.VISUALIZATIONS to it },
                    parameterSets?.let { NavigationFamily.PARAMETER_SETS to it },
                ).toMap(),
            ),
        )

        val pipelines: Int? get() = of(NavigationFamily.PIPELINES)
        val templates: Int? get() = of(NavigationFamily.TEMPLATES)
        val dashboards: Int? get() = of(NavigationFamily.DASHBOARDS)
        val visualizations: Int? get() = of(NavigationFamily.VISUALIZATIONS)
        val parameterSets: Int? get() = of(NavigationFamily.PARAMETER_SETS)

        fun of(family: NavigationFamily): Int? = values.value[family]

        override fun equals(other: Any?): Boolean = other is Counts && values.value == other.values.value

        override fun hashCode(): Int = values.value.hashCode()

        override fun toString(): String =
            "Counts(pipelines=$pipelines, templates=$templates, dashboards=$dashboards, " +
                "visualizations=$visualizations, parameterSets=$parameterSets)"

        companion object {
            val NONE = Counts(null, null)

            fun of(counts: Map<NavigationFamily, Int>): Counts = Counts(lazyOf(counts.toMap()))

            /** Counts whose [read] runs once, on the first property read (#574). */
            internal fun deferred(read: () -> Map<NavigationFamily, Int>): Counts = Counts(lazy(read))
        }
    }

    companion object {
        /** The lens arm that governs [family] — one place, so a sixth family is a compile error here. */
        private fun LensedView.arm(family: NavigationFamily): ReadLens =
            when (family) {
                NavigationFamily.PIPELINES -> pipelines
                NavigationFamily.TEMPLATES -> templates
                NavigationFamily.DASHBOARDS -> dashboards
                NavigationFamily.VISUALIZATIONS -> visualizations
                NavigationFamily.PARAMETER_SETS -> parameterSets
            }
    }
}
