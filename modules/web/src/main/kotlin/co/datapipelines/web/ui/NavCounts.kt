package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.ReadLens
import org.slf4j.LoggerFactory
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 079 §A, #554 — the admitted-resource count the resource browser's header carries for each of
 * the five browsable families, with a TTL cache in front of the reads.
 *
 * Until #554 the rail's Pipelines and Templates rows carried two of these as badges; the shell
 * record (§3) moved counts into the browser header — "resource counts belong in the resource
 * browser, not repeated beside every primary item" — and every family with a browser now has
 * one. Each count is the family's CATALOG size: the same live predicate its catalog page
 * totals (`countAll`/`count`), so the header can never say "9" beside a catalog of eight.
 *
 * The shell renders on EVERY page and on every boosted swap. Five `COUNT(*)`s per click is not
 * ruinous, but the templates count joins `template_versions` to reach the current version, and
 * a header count nobody is reading has no business being the reason a click waits on the
 * database. 60 seconds is the TTL every other liveness cache in this codebase uses
 * (`AuthCache`, `DatasourceMetadataCache`): a count that is a minute stale is
 * indistinguishable from a fresh one to a reader, while one that is an hour stale is a bug.
 *
 * ## Why a new cache and not "the existing metadata cache"
 *
 * There is no general metadata cache in this tree. `DatasourceMetadataCache` is keyed by
 * datasource NAME and lives in `modules/datasources`; `AuthCache` lives in `modules/auth`, which
 * by design cannot see the family tables. So this copies `DatasourceMetadataCache`'s discipline
 * rather than its instance: `ConcurrentHashMap`, an injected [ticker] so the expiry is testable
 * without sleeping, lazy expiry on read (no sweeper thread), and **misses are never cached** — a
 * failed count must not pin a zero into the header for a minute.
 *
 * ## What it deliberately does NOT do
 *
 * No invalidation hooks. Creating a pipeline through the editor or over MCP does not evict
 * this; the count catches up within the TTL. Wiring eviction into every mutation path would
 * couple six services to a decoration.
 *
 * @param sources one catalog-size read per family, keyed by the closed [NavigationFamily] set;
 *   a family without a source has no count (the header omits it).
 */
class NavCounts(
    private val sources: Map<NavigationFamily, (UUID) -> Int>,
    private val ttl: Duration = DEFAULT_TTL,
    private val ticker: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(NavCounts::class.java)

    /** Every family's live count for one workspace, and the nano-timestamp they were read at. */
    private data class Entry(
        val counts: Map<NavigationFamily, Int>,
        val readAt: Long,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /**
     * The browser header's counts for [workspaceId]. Never throws: a database that cannot answer
     * must not take the whole shell down with it, so a failed read logs and yields nulls, and the
     * template renders the header with no count at all (`th:if` on the value) — the record's
     * "unknown/unavailable counts are omitted, never replaced by zero".
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
        // read, no cache for those arms: the view already paid for the answer, and a
        // per-principal set must not be pinned into a per-workspace entry.
        val lenses = NavigationFamily.entries.associateWith { view.arm(it) }
        val narrowed = lenses.mapNotNull { (family, lens) -> (lens as? ReadLens.Only)?.let { family to it.names.size } }.toMap()
        if (narrowed.size == NavigationFamily.entries.size) return Counts.of(narrowed)
        // A failed live read leaves only the narrowed arms: the rest are unknown, never zero.
        return Counts.of((live(workspaceId) ?: emptyMap()) + narrowed)
    }

    /** The cached (or freshly read) live counts, or null when the read failed (never cached). */
    private fun live(workspaceId: UUID): Map<NavigationFamily, Int>? {
        val now = ticker()
        val cached = entries[workspaceId]
        if (cached != null && now - cached.readAt < ttl.inWholeNanoseconds) return cached.counts
        return runCatching {
            val fresh = Entry(sources.mapValuesTo(EnumMap(NavigationFamily::class.java)) { (_, read) -> read(workspaceId) }, now)
            entries[workspaceId] = fresh
            fresh.counts
        }.getOrElse { failure ->
            log.warn("nav counts unavailable for workspace {}: {}", workspaceId, failure.toString())
            null
        }
    }

    /** Drops one workspace's entry — for tests and for a future mutation hook, not called today. */
    fun invalidate(workspaceId: UUID) {
        entries.remove(workspaceId)
    }

    /**
     * One count per family, null where unknown. The template reads the properties by name
     * (`navCounts.pipelines` …); [of] serves code that holds a [NavigationFamily].
     */
    data class Counts(
        val pipelines: Int?,
        val templates: Int?,
        val dashboards: Int? = null,
        val visualizations: Int? = null,
        val parameterSets: Int? = null,
    ) {
        fun of(family: NavigationFamily): Int? =
            when (family) {
                NavigationFamily.PIPELINES -> pipelines
                NavigationFamily.TEMPLATES -> templates
                NavigationFamily.DASHBOARDS -> dashboards
                NavigationFamily.VISUALIZATIONS -> visualizations
                NavigationFamily.PARAMETER_SETS -> parameterSets
            }

        companion object {
            val NONE = Counts(null, null)

            fun of(counts: Map<NavigationFamily, Int>): Counts =
                Counts(
                    pipelines = counts[NavigationFamily.PIPELINES],
                    templates = counts[NavigationFamily.TEMPLATES],
                    dashboards = counts[NavigationFamily.DASHBOARDS],
                    visualizations = counts[NavigationFamily.VISUALIZATIONS],
                    parameterSets = counts[NavigationFamily.PARAMETER_SETS],
                )
        }
    }

    companion object {
        val DEFAULT_TTL: Duration = 60.seconds

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
