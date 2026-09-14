package co.datapipelines.templates

import java.util.Collections
import java.util.UUID

/**
 * A [TemplateRegistry] backed by [TemplateRepository], with an in-memory cache of resolved
 * versions, **bound to exactly one workspace** at construction.
 *
 * ## What is cached, and why that is a theorem rather than a policy (132)
 *
 * Only a **non-DRAFT** positive lookup enters the cache. A RELEASED or DISCARDED version is
 * immutable (templates.md §5.1): no repository statement writes the content fields of a
 * non-DRAFT row, discard/restore move status only, and the entity purge (101) is refused
 * unless the sole version is a DRAFT — so an entry, once admitted, can never disagree with
 * the database, whatever happens to the template afterwards. A DRAFT is the one mutable key:
 * `templates_update` / `PUT /templates` overwrite it in place (117) and `templates_purge_draft`
 * deletes it, both without changing `id@version`. Before 132 the LRU cached it like any other
 * row and every render after the first served the first body (the 2026-09-14 acceptance
 * run); now a draft is re-read on every lookup — one indexed row read, paid only while the
 * version is a draft and edited, and cached from the read that first sees it RELEASED.
 * Invalidation-on-write was the alternative; it would have to enumerate every write path
 * (overwrite, purge, entity purge, the promotion paths) and reach every other instance's
 * caches over a bus, and be wrong the moment one path was missed. This needs no bus: another
 * instance simply reads the row too.
 *
 * Only *positive* lookups are cached — a null could later become a real version (the row is
 * created), so caching absence would hide a just-saved template from the next render.
 *
 * ## Workspace binding (T24)
 *
 * Template names are unique only *per workspace* (metadata-db §4.8), so a registry keyed by
 * `"$id@$version"` alone collides across workspaces — the collision no repository grep sees.
 * The workspace is therefore part of the cache identity structurally: instances are vended
 * per workspace by [WorkspaceTemplateEngines], and the key inside one instance stays
 * `"$id@$version"` because the workspace can never vary within it.
 *
 * The cache is a bounded LRU sized by `datapipelines.templates.cache-size` (configuration.md
 * §3.9) — the same budget that sizes Freemarker's parsed-template cache, one layer up.
 */
class RepositoryTemplateRegistry(
    private val repository: TemplateRepository,
    cacheSize: Int,
    private val workspaceId: UUID,
) : TemplateRegistry {
    private val cache: MutableMap<String, TemplateVersion> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, TemplateVersion>(INITIAL_CAPACITY, LOAD_FACTOR, ACCESS_ORDER) {
                override fun removeEldestEntry(eldest: Map.Entry<String, TemplateVersion>): Boolean = size > cacheSize
            },
        )

    override fun lookup(
        id: String,
        version: Int,
    ): TemplateVersion? {
        val key = "$id@$version"
        cache[key]?.let { return it }
        val resolved = repository.lookupVersion(workspaceId, id, version) ?: return null
        if (!resolved.isDraft) cache[key] = resolved
        return resolved
    }

    override fun existsId(id: String): Boolean = repository.existsId(workspaceId, id)

    private companion object {
        /** `LinkedHashMap` in access-order (LRU) mode — the eldest entry is the least-recently used. */
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
        const val ACCESS_ORDER = true
    }
}
