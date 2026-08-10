package co.datapipelines.templates

import freemarker.cache.NullCacheStorage
import freemarker.core._CoreAPI
import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.Version
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A [Configuration] whose templates can actually be aborted by interrupting the render thread
 * (templates.md §4.3).
 *
 * ## The problem this exists to solve
 *
 * Freemarker has no render timeout of its own, so [TemplateEngine] runs each render on a worker
 * and interrupts it at the cap. **A plain `Thread.interrupt()` does not abort a Freemarker
 * render** — verified against the pinned 2.3.34 jar: a `<#list 1..2000000000>` whose body writes
 * nothing was still `RUNNABLE` four seconds after `Future.cancel(true)`, burning a core with no
 * way back. Abandoning the worker leaks one such thread per runaway.
 *
 * Freemarker's answer is `ThreadInterruptionSupportTemplatePostProcessor`, which rewrites a
 * parsed template to test the interrupt flag on every loop iteration. The class is
 * package-private, so the registration API is the published static
 * [_CoreAPI.addThreadInterruptedChecks]; with it the same runaway aborted **1 ms** after
 * `cancel(true)`, throwing Freemarker's internal
 * `TemplateProcessingThreadInterruptedException`. Both measurements are asserted by
 * `TemplateEngineTest`, so the guard cannot rot into a comment.
 *
 * ## Why it is applied here rather than at the call site
 *
 * `addThreadInterruptedChecks` **mutates** the AST, so it must run exactly once per parsed
 * template, before any thread renders it. Applying it to whatever
 * `Configuration.getTemplate(name)` returns cannot give that: the built-in cache hands the same
 * object to concurrent renders, and a second application would mutate a tree another thread is
 * walking.
 *
 * Overriding the one method every load funnels through — including the transitive
 * `<#import>` resolution Freemarker performs at render time, verified — gives the guarantee
 * instead:
 *  - Freemarker's own cache is disabled in `init` so `super.getTemplate` always returns a
 *    **fresh, unshared** tree;
 *  - that tree is post-processed and published to [postProcessed] before any caller sees it;
 *  - subsequent loads of the same key hit [postProcessed], which is this module's parsed-template
 *    cache (`datapipelines.templates.cache-size`, configuration.md §3.9) in place of the
 *    built-in one.
 *
 * Eviction is safe precisely because nothing is shared: an evicted key is re-parsed into a new
 * tree, and templates already handed to in-flight renders stay valid — a stored version is
 * immutable (templates.md §5.1), so two trees for one key can never disagree.
 */
internal class InterruptibleConfiguration(
    version: Version,
    private val cacheSize: Int,
) : Configuration(version) {
    /**
     * Post-processed templates by loader key. A [ConcurrentHashMap] with `computeIfAbsent`
     * gives the exactly-once publication the mutation requires while letting different keys
     * parse concurrently; the parse never re-enters this map, because Freemarker resolves
     * `<#import>` at render time, not at parse time.
     */
    private val postProcessed = ConcurrentHashMap<String, Template>()

    init {
        // Every load must reach the loader and produce an unshared tree — see the class KDoc.
        // This module's own `postProcessed` map is the parsed-template cache in its place.
        cacheStorage = NullCacheStorage()
    }

    /**
     * The single method every template load funnels through — the public entry points
     * (`getTemplate(name)` and friends) and Freemarker's own `<#import>` / `<#include>`
     * resolution all delegate here, so an imported library is made interruptible too.
     *
     * Returns null only when `ignoreMissing` is set and the loader has no such template; that
     * result is not cached, so a template created later is still found.
     */
    override fun getTemplate(
        name: String,
        locale: Locale?,
        customLookupCondition: Any?,
        encoding: String?,
        parseAsFTL: Boolean,
        ignoreMissing: Boolean,
    ): Template? {
        postProcessed[name]?.let { return it }
        val loaded = super.getTemplate(name, locale, customLookupCondition, encoding, parseAsFTL, ignoreMissing) ?: return null
        return postProcessed
            .computeIfAbsent(name) { _ ->
                _CoreAPI.addThreadInterruptedChecks(loaded)
                loaded
            }.also { evictIfOverCap() }
    }

    /**
     * Bounds the cache. A wholesale clear, not an LRU eviction: the map exists for correctness
     * (exactly-once post-processing), a re-parse after a clear is correct in every case, and an
     * ordering structure would need a lock on the hot path to buy nothing but a warmer cache.
     */
    private fun evictIfOverCap() {
        if (postProcessed.size > cacheSize) postProcessed.clear()
    }
}
