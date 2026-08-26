package co.datapipelines.templates

import java.util.Collections
import java.util.UUID

/**
 * A [TemplateRegistry] backed by [TemplateRepository], with an in-memory cache of resolved
 * versions, **bound to exactly one workspace** at construction.
 *
 * Caching is sound because a version is immutable (templates.md §5.1): once `id@version` has
 * been read it can be held forever, and a new version is a different key rather than a mutation
 * of this one. Only *positive* lookups are cached — a null could later become a real version
 * (the row is created), so caching absence would hide a just-saved template from the next
 * render.
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
        cache[key] = resolved
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
