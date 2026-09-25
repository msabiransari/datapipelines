package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.through
import co.datapipelines.typesystem.Dialect
import java.util.UUID

/**
 * The template aggregate's **read use-case service** — the `TemplateService` the ARCH-AUDIT
 * S5/R6 exemplar anticipated (`PipelineService`'s KDoc names it as slice B), created by 178
 * because the promoter lens needs ONE place every template read passes through.
 *
 * It lives in `modules/templates` because that module owns the aggregate (module-structure
 * §5.10) — and because `web` depends on `mcp-server`, not the reverse, so a service in `web`
 * could never serve the MCP read tools that are half of the read surface.
 *
 * ## The lens (roles design §3.1, 178)
 * Every read takes a [ReadLens] and NO default — the same rule `workspaceId` follows
 * ("a missed caller is a compile error"). [ReadLens.Everything] delegates to the repository
 * untouched: the same SQL, the same paging, no extra query, so every non-promoter surface
 * behaves exactly as it did. [ReadLens.Only] narrows by template id (a template's id IS its
 * name, templates.md §3.1): lists keep the admitted rows, and a read of an id the lens does
 * not admit answers null — indistinguishable from an id that does not exist, which is the
 * 404 rule (auth.md §11A) applied to a hidden object.
 *
 * ## Lensed lists and levels are taken in memory
 * The admitted set is decided per request, so the SQL pager and the tree `GROUP BY` cannot
 * take it. Under a narrowing lens the service reads the workspace's current-version rows
 * once (the same population `LIST_WHERE`/`TREE_WHERE` select, paged through the repository
 * at its maximum page size), keeps the admitted ones, and derives page, level, folders and
 * totals from that one list by the repository's own rules — so every count a lensed screen
 * shows comes from the same set. The set is small by construction: what is promotable, never
 * the workspace.
 *
 * ## What it deliberately does NOT do
 * Writes, the version lifecycle and the engine's `lookupVersion` stay on the repository and
 * the lifecycle services: those paths are the author's, refused for a promoter at the
 * interceptor, and a render resolves imports by pinned version rather than by what the
 * caller may list. Reads return null; the surface owns its 404.
 */
@Suppress("TooManyFunctions") // one façade over the aggregate's whole read surface, as PipelineService is
open class TemplateService(
    private val templates: TemplateRepository,
) {
    /** The current-version projection of a live, admitted template, or null. */
    open fun findLatest(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
    ): Template? = if (lens.admits(id)) templates.findLatest(workspaceId, id) else null

    /**
     * The working version (draft, else current release) of an admitted template, or null.
     * Under a narrowing lens the working version IS the release: a promoter never sees a
     * draft, not as an object and not as the pending edits of a visible one (178).
     */
    open fun findWorking(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
    ): Template? =
        when {
            !lens.admits(id) -> null
            lens.isEverything -> templates.findWorking(workspaceId, id)
            else -> templates.findLatest(workspaceId, id)
        }

    /** One stored version of an admitted template, or null — a DRAFT version is null under a narrowing lens. */
    open fun findVersion(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
        version: Int,
    ): Template? = if (lens.admits(id)) templates.findVersion(workspaceId, id, version)?.takeIf { released(lens, it.status) } else null

    /**
     * The engine's version record for an admitted template — [TemplateRepository.lookupVersion]'s
     * "deleted or not, a pinned version still resolves" read, gated by the lens only. The MCP
     * template resource reads a version through this so a retired template's pinned version
     * keeps serving exactly as before 178.
     */
    open fun lookupVersion(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
        version: Int,
    ): TemplateVersion? =
        if (lens.admits(id)) templates.lookupVersion(workspaceId, id, version)?.takeIf { released(lens, it.status) } else null

    /** True when any version of [id] exists AND the lens admits it — the not-found split's first half. */
    open fun existsId(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
    ): Boolean = lens.admits(id) && templates.existsId(workspaceId, id)

    /**
     * The `GET /templates` page: the repository's SQL page, or the admitted rows paged in memory.
     * [implements] (7e, §8.3) keeps the templates whose listed version cites that fact.
     */
    open fun list(
        workspaceId: UUID,
        lens: ReadLens,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        q: String? = null,
        offset: Int = 0,
        limit: Int = TemplateRepository.DEFAULT_PAGE_LIMIT,
        implements: UUID? = null,
    ): List<Template> {
        if (lens.isEverything) return templates.list(workspaceId, dialect, type, q, offset, limit, implements)
        return admitted(workspaceId, lens, dialect, type, q, implements)
            .drop(maxOf(0, offset))
            .take(limit.coerceIn(1, TemplateRepository.MAX_PAGE_LIMIT))
    }

    /** The truthful total of [list] under the same filters and the same lens. */
    open fun count(
        workspaceId: UUID,
        lens: ReadLens,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        q: String? = null,
        implements: UUID? = null,
    ): Int {
        if (lens.isEverything) return templates.count(workspaceId, dialect, type, q, implements)
        return admitted(workspaceId, lens, dialect, type, q, implements).size
    }

    /** One tree level's direct sub-folders, counted over the admitted subtree. */
    open fun listChildFolders(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String? = null,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        limit: Int = TemplateRepository.MAX_PAGE_LIMIT,
    ): List<TemplateFolder> {
        if (lens.isEverything) return templates.listChildFolders(workspaceId, prefix, dialect, type, limit)
        val scope = scope(prefix)
        return admitted(workspaceId, lens, dialect, type, q = null)
            .filter { it.id.startsWith(scope) }
            .map { it.id.removePrefix(scope) }
            .filter { remainder -> '/' in remainder }
            .groupBy { remainder -> remainder.substringBefore('/') }
            .toSortedMap()
            .map { (segment, rows) -> TemplateFolder(path = scope + segment, segment = segment, templateCount = rows.size) }
            .take(limit.coerceIn(1, TemplateRepository.MAX_PAGE_LIMIT + 1))
    }

    /** One tree level's direct template leaves, at their current version, admitted ones only. */
    open fun listChildTemplates(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String? = null,
        dialect: Dialect? = null,
        type: TemplateType? = null,
        offset: Int = 0,
        limit: Int = TemplateRepository.DEFAULT_PAGE_LIMIT,
    ): List<Template> {
        if (lens.isEverything) return templates.listChildTemplates(workspaceId, prefix, dialect, type, offset, limit)
        return leaves(workspaceId, lens, prefix, dialect, type)
            .drop(maxOf(0, offset))
            .take(limit.coerceIn(1, TemplateRepository.MAX_PAGE_LIMIT + 1))
    }

    /** The truthful total of one level's leaves under the same lens. */
    open fun countChildTemplates(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String? = null,
        dialect: Dialect? = null,
        type: TemplateType? = null,
    ): Int {
        if (lens.isEverything) return templates.countChildTemplates(workspaceId, prefix, dialect, type)
        return leaves(workspaceId, lens, prefix, dialect, type).size
    }

    /** §9 — version metadata of an admitted template, newest first; empty otherwise. */
    open fun listVersions(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
    ): List<TemplateVersionSummary> =
        if (lens.admits(id)) templates.listVersions(workspaceId, id).filter { released(lens, it.status) } else emptyList()

    /** One version's lifecycle detail of an admitted template, or null. */
    open fun findVersionDetail(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
        version: Int,
    ): TemplateVersionDetail? =
        if (lens.admits(id)) templates.findVersionDetail(workspaceId, id, version)?.takeIf { released(lens, it.status) } else null

    /** The draft pointer of an admitted template, or null — always null under a narrowing lens. */
    open fun findDraftDetail(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
    ): TemplateVersionDetail? = if (lens.isEverything && lens.admits(id)) templates.findDraftDetail(workspaceId, id) else null

    /** The DRAFT detail of each of [ids] that has one — the list badge; empty under a narrowing lens. */
    open fun findDrafts(
        workspaceId: UUID,
        lens: ReadLens,
        ids: Collection<String>,
    ): Map<String, TemplateVersionDetail> = if (lens.isEverything) templates.findDrafts(workspaceId, ids) else emptyMap()

    /** The lifecycle status of one version of an admitted template, or null. */
    open fun findVersionStatus(
        workspaceId: UUID,
        lens: ReadLens,
        id: String,
        version: Int,
    ): PipelineVersionStatus? =
        if (lens.admits(id)) templates.findVersionStatus(workspaceId, id, version)?.takeIf { released(lens, it) } else null

    /**
     * The admitted current-version rows under the list filters, name-ordered — the one read
     * every lensed list, count and level derives from. Paged through the repository at its
     * maximum page size so the population is exactly `LIST_WHERE`'s (live, current-or-draft
     * version, the `dialect`/`type`/`q` clauses), never a second predicate.
     */
    private fun admitted(
        workspaceId: UUID,
        lens: ReadLens,
        dialect: Dialect?,
        type: TemplateType?,
        q: String?,
        implements: UUID? = null,
    ): List<Template> {
        val all = mutableListOf<Template>()
        var offset = 0
        while (true) {
            val page = templates.list(workspaceId, dialect, type, q, offset, TemplateRepository.MAX_PAGE_LIMIT, implements)
            all += page
            if (page.size < TemplateRepository.MAX_PAGE_LIMIT) break
            offset += TemplateRepository.MAX_PAGE_LIMIT
        }
        return all.through(lens) { it.id }
    }

    /** The admitted leaves of one level: in scope, and with no `/` left in the remainder. */
    private fun leaves(
        workspaceId: UUID,
        lens: ReadLens,
        prefix: String?,
        dialect: Dialect?,
        type: TemplateType?,
    ): List<Template> {
        val scope = scope(prefix)
        return admitted(workspaceId, lens, dialect, type, q = null)
            .filter { it.id.startsWith(scope) && '/' !in it.id.removePrefix(scope) }
    }

    /** Under [ReadLens.Everything] every status; under a narrowing lens RELEASED only (a draft is never a promoter's). */
    private fun released(
        lens: ReadLens,
        status: PipelineVersionStatus,
    ): Boolean = lens.isEverything || status == PipelineVersionStatus.RELEASED

    /** `prefix/` for a folder, the empty string for the root — the repository's `namePattern` scope. */
    private fun scope(prefix: String?): String = if (prefix.isNullOrEmpty()) "" else "$prefix/"
}
