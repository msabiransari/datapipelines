package co.datapipelines.templates

import java.io.Closeable
import java.util.UUID

/**
 * Vends the per-workspace [TemplateEngine] + [TemplateRegistry] pair (design §5, T24).
 *
 * Since 046 each vendored engine internally carries the **pair of Freemarker
 * configurations** (template-hierarchy-design §6 — `sql` and auto-escaping `html`, over one
 * shared loader) and picks per render by the resolved version's `type`; the workspace-bound
 * identity below is what makes BOTH caches sound, so the pair lives inside the engine rather
 * than beside it.
 *
 * Template names are unique only per workspace (metadata-db §4.8), so the render path's two
 * cache tiers — this registry's resolved-version LRU and Freemarker's parsed-template cache
 * (keyed by the loader's `"{id}@{version}"` name, see [InterruptibleConfiguration]) — are
 * only sound when the workspace is part of their identity. It is, structurally: each
 * workspace gets its own registry and its own engine (whose Freemarker `Configuration` is
 * per-instance), so no key anywhere needs the workspace appended and no cross-workspace
 * cache hit is representable.
 *
 * This is also what lets `dag` keep calling `TemplateEngine.render(ref, context, budget)`
 * unchanged: the execution path's assemblers (in `web`) pick the workspace's engine at
 * launch time and hand *it* to the executor — the workspace travels in the instance, not in
 * a signature `dag` would have to change.
 *
 * The map is a bounded LRU ([MAX_ENGINES]); eviction closes the engine (its render pool is
 * a thread resource, not garbage). A code constant, not a config key: configuration.md §3.9
 * defines the templates keys and names none for this (the D8 discipline
 * [TemplateEngine.MAX_CONCURRENT_RENDERS] already follows). Far above any real deployment's
 * active-workspace count; past it, the least-recently-used workspace pays one engine rebuild.
 */
class WorkspaceTemplateEngines(
    private val repository: TemplateRepository,
    private val cacheSize: Int,
    private val renderTimeoutMs: Long,
    private val maxOutputChars: Long,
) : Closeable {
    /**
     * The per-workspace pair. The engine's loader reads through THIS registry, so all of a
     * workspace's renders and validations share one resolved-version cache.
     */
    inner class Bound internal constructor(
        val registry: RepositoryTemplateRegistry,
        val engine: TemplateEngine,
    )

    // One lock covers lookup, admission and eviction: a plain getOrPut on a synchronized
    // map is not atomic, and two threads building two engines for one workspace would
    // orphan a render pool (a thread leak, not garbage).
    private val lock = Any()
    private val bindings = LinkedHashMap<UUID, Bound>(INITIAL_CAPACITY, LOAD_FACTOR, ACCESS_ORDER)

    /** The workspace's engine, built (with its bound registry) on first use. */
    fun engineFor(workspaceId: UUID): TemplateEngine = bindingFor(workspaceId).engine

    /** The workspace's registry — the same instance its engine renders through. */
    fun registryFor(workspaceId: UUID): TemplateRegistry = bindingFor(workspaceId).registry

    private fun bindingFor(workspaceId: UUID): Bound =
        synchronized(lock) {
            bindings
                .getOrPut(workspaceId) {
                    val registry = RepositoryTemplateRegistry(repository, cacheSize, workspaceId)
                    Bound(
                        registry = registry,
                        engine =
                            TemplateEngine(
                                registry = registry,
                                cacheSize = cacheSize,
                                renderTimeoutMs = renderTimeoutMs,
                                maxOutputChars = maxOutputChars,
                            ),
                    )
                }.also { evictOverflow() }
        }

    /** LRU eviction past [MAX_ENGINES] — callers hold [lock]. */
    private fun evictOverflow() {
        while (bindings.size > MAX_ENGINES) {
            val eldest = bindings.entries.first()
            bindings.remove(eldest.key)
            eldest.value.engine.close()
        }
    }

    /** Live bindings — the bound this factory promises, observable in tests. */
    internal fun size(): Int = synchronized(lock) { bindings.size }

    override fun close() {
        synchronized(lock) {
            bindings.values.forEach { it.engine.close() }
            bindings.clear()
        }
    }

    companion object {
        /**
         * Per-workspace engine ceiling — LRU beyond it, with `close()` on eviction. Public
         * (like [TemplateEngine.MAX_CONCURRENT_RENDERS]) so the bound is observable in tests.
         */
        const val MAX_ENGINES = 64
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val ACCESS_ORDER = true
    }
}
