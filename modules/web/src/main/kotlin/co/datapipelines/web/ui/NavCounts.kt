package co.datapipelines.web.ui

import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 079 §A — the two counts the rail's Pipelines and Templates rows carry, with a TTL cache in
 * front of them.
 *
 * The rail renders on EVERY page and on every boosted swap. Two `COUNT(*)`s per click is not
 * ruinous, but the templates count joins `template_versions` to reach the current version, and
 * a badge nobody is reading has no business being the reason a nav click waits on the database.
 * 60 seconds is the TTL every other liveness cache in this codebase uses (`AuthCache`,
 * `DatasourceMetadataCache`), and it is the right order of magnitude here for the same reason:
 * a count that is a minute stale is indistinguishable from a fresh one to a reader, while a
 * count that is an hour stale is a bug.
 *
 * ## Why a new cache and not "the existing metadata cache"
 *
 * There is no general metadata cache in this tree. `DatasourceMetadataCache` is keyed by
 * datasource NAME and lives in `modules/datasources`; `AuthCache` is the nav's other cached
 * read (the workspace switcher's memberships) but lives in `modules/auth`, which by design
 * cannot see the `pipelines` or `templates` tables. So this copies `DatasourceMetadataCache`'s
 * discipline rather than its instance: `ConcurrentHashMap`, an injected [ticker] so the expiry
 * is testable without sleeping, lazy expiry on read (no sweeper thread), and **misses are
 * never cached** — a failed count must not pin a zero into the rail for a minute.
 *
 * ## What it deliberately does NOT do
 *
 * No invalidation hooks. Creating a pipeline through the editor or over MCP does not evict
 * this; the badge catches up within the TTL. Wiring eviction into every mutation path would
 * couple six services to a decoration, and the failure mode it would prevent ("the badge said
 * 8 for forty seconds after I made a ninth") is not one worth that coupling.
 */
class NavCounts(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val ttl: Duration = DEFAULT_TTL,
    private val ticker: () -> Long = System::nanoTime,
) {
    private val log = LoggerFactory.getLogger(NavCounts::class.java)

    /** Both counts for one workspace, and the nano-timestamp they were read at. */
    private data class Entry(
        val pipelines: Int,
        val templates: Int,
        val readAt: Long,
    )

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /**
     * The rail's badges for [workspaceId]. Never throws: a database that cannot answer must
     * not take the whole shell down with it, so a failed read logs and yields nulls, and the
     * template renders the row with no badge at all (`th:if` on the value) — the same
     * "degrade to the previous rendering" posture the vendored fonts take.
     */
    fun forWorkspace(workspaceId: UUID?): Counts {
        if (workspaceId == null) return Counts(null, null)
        val now = ticker()
        val cached = entries[workspaceId]
        if (cached != null && now - cached.readAt < ttl.inWholeNanoseconds) {
            return Counts(cached.pipelines, cached.templates)
        }
        return runCatching {
            val fresh =
                Entry(
                    pipelines = pipelines.countAll(workspaceId),
                    templates = templates.count(workspaceId),
                    readAt = now,
                )
            entries[workspaceId] = fresh
            Counts(fresh.pipelines, fresh.templates)
        }.getOrElse { failure ->
            log.warn("nav counts unavailable for workspace {}: {}", workspaceId, failure.toString())
            Counts(null, null)
        }
    }

    /** Drops one workspace's entry — for tests and for a future mutation hook, not called today. */
    fun invalidate(workspaceId: UUID) {
        entries.remove(workspaceId)
    }

    data class Counts(
        val pipelines: Int?,
        val templates: Int?,
    )

    companion object {
        val DEFAULT_TTL: Duration = 60.seconds
    }
}
