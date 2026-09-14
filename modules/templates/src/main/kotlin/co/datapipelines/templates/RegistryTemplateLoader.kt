package co.datapipelines.templates

import freemarker.cache.TemplateLoader
import java.io.IOException
import java.io.Reader
import java.io.StringReader

/**
 * The Freemarker [TemplateLoader] of templates.md §4.3 — the **only** way a body reaches the
 * engine.
 *
 * It resolves nothing but `"{id}@{version}"` keys against the [TemplateRegistry]. There is no
 * `FileTemplateLoader` and no `ClassTemplateLoader` anywhere in the configuration
 * ([FreemarkerConfigFactory]), so there is no template name a body could reference to escape
 * the registry — which is exactly why templates.md §4.2 forbids a literal `<#import>` /
 * `<#include>` in a body: the only imports are the ones this loader synthesizes.
 *
 * ## Synthesized import prologue
 *
 * The stored [TemplateVersion.body] never contains an import directive (D12). This loader
 * prepends a synthesized `<#import "{id}@{version}" as {alias}>` line per [TemplateImport],
 * so when Freemarker parses the prologue it asks *this same loader* for each library key,
 * resolving the closure transitively. The prologue depends only on the version's `imports`,
 * which the row's `body_hash` covers, so the effective source is a function of that hash.
 *
 * ## Content identity, not key identity (132)
 *
 * A `"{id}@{version}"` key is **not** immutable: the sole DRAFT is overwritten in place by
 * `templates_update` (117) and deleted by `templates_purge_draft`. Every [Source] this loader
 * hands out therefore carries the row's [Source.identity] (`body_hash`) and its
 * [Source.lastModified], and [InterruptibleConfiguration] keys its parsed-template cache on
 * `name#identity` rather than on the name — so a re-read draft with a new hash is a cache
 * miss and a fresh parse, while a RELEASED version keeps hitting. [getLastModified] reports
 * the real stamp for the same reason: a constant told Freemarker's own staleness check the
 * source could never change.
 *
 * ## The pinned source
 *
 * [InterruptibleConfiguration] has to know a name's identity BEFORE it decides whether to
 * parse, and the parse — `Configuration.getTemplate` — asks this loader for the source again
 * on the same thread. Two registry reads per miss is one too many for a draft (a row read
 * each), and worse, a draft overwritten BETWEEN the two reads would be cached under the
 * first read's hash with the second read's tree. [withPinned] closes both: the source the
 * configuration resolved is the source the parse gets, verbatim, on that thread only.
 *
 * ## The prologue fails closed
 *
 * The prologue is source built by interpolating two author-controlled strings, and the
 * save-time body scan never sees it. Save-time validation rejects an unsafe `imports` entry
 * ([LibraryResolver]); this loader **independently** refuses to synthesize one, so a row that
 * reached the database by any other path (a pre-guard row, a direct SQL write, a future code
 * path that skips the validator) cannot smuggle FTL into the engine either. Same two-layer
 * discipline §4.2/§4.3 apply to every other construct.
 */
class RegistryTemplateLoader(
    private val registry: TemplateRegistry,
) : TemplateLoader {
    /**
     * One resolved source: the [key] Freemarker asked for, the effective FTL
     * ([prologue][synthesizePrologue] + body) it will parse, and the row's content [identity]
     * (`body_hash`, blank when the row carries none) with its [lastModified] stamp.
     */
    internal data class Source(
        val key: String,
        val parsedKey: Pair<String, Int>,
        val effectiveSource: String,
        val identity: String,
        val lastModified: Long,
    )

    private val pinned = ThreadLocal<Source?>()

    override fun findTemplateSource(name: String): Any? = source(name)

    /**
     * The typed resolution behind [findTemplateSource]: the [pinned] source when it names this
     * key (see the class KDoc), else one registry lookup.
     */
    internal fun source(name: String): Source? {
        val key = parseKey(name) ?: return null
        pinned.get()?.takeIf { it.parsedKey == key }?.let { return it }
        val stored = registry.lookup(key.first, key.second) ?: return null
        return sourceOf(name, key, stored)
    }

    /**
     * The [Source] for a version the caller has ALREADY resolved under [name] — so
     * [TemplateEngine], which looks the version up to pick a configuration, can pin it and
     * spare the configuration its own lookup. Null when [name] is not a well-formed key for
     * [stored] (a mismatch is a programming error upstream; refusing is the fail-closed shape).
     */
    internal fun sourceOf(
        name: String,
        stored: TemplateVersion,
    ): Source? {
        val key = parseKey(name)?.takeIf { it.first == stored.id && it.second == stored.version } ?: return null
        return sourceOf(name, key, stored)
    }

    private fun sourceOf(
        name: String,
        key: Pair<String, Int>,
        stored: TemplateVersion,
    ): Source =
        Source(
            key = name,
            parsedKey = key,
            effectiveSource = synthesizePrologue(stored.imports) + stored.body,
            identity = stored.bodyHash,
            lastModified = stored.lastModified.toEpochMilli(),
        )

    /**
     * Runs [block] with [source] as THE answer for its key on this thread — so a parse the
     * caller starts after resolving [source] is guaranteed to parse exactly that content, and
     * costs no second registry read. Nests (the engine pins around a load, the configuration
     * pins the same source around the parse); the previous pin is restored on exit,
     * exceptional or not.
     */
    internal fun <T> withPinned(
        source: Source,
        block: () -> T,
    ): T {
        val previous = pinned.get()
        pinned.set(source)
        try {
            return block()
        } finally {
            if (previous == null) pinned.remove() else pinned.set(previous)
        }
    }

    /**
     * Splits `"{id}@{version}"` into its two halves, **checking the shape of both** — null for
     * anything that is not a well-formed registry key, so it never becomes a registry query.
     *
     * One leading `/` is stripped first: the §4.4 prologue emits root-based names
     * (`/{name}@{version}`) so a hierarchical importer can never pull Freemarker's relative
     * resolution into play. Exactly one slash — after the strip the name must satisfy the §4.1
     * grammar, which rejects a leading `/`, so `//{name}@{version}` fails closed rather than
     * being peeled repeatedly. (Measured on the pinned 2.3.34 jar: the legacy name format
     * consumes the prologue's leading `/` itself, so the bare form arrives here; the strip
     * covers the verbatim arrival other name formats produce. Both forms resolve one key.)
     *
     * The `id` half is checked against the §4.1 grammar for the same fail-closed reason as the
     * version half (§6.3): a key reaching this loader by some route other than the validated
     * `imports` array is refused on its shape, rather than on the registry happening to have no
     * such row.
     */
    private fun parseKey(name: String): Pair<String, Int>? {
        val stripped = name.removePrefix("/")
        val at = stripped.lastIndexOf('@')
        if (at <= 0 || at == stripped.length - 1) return null
        val id = stripped.substring(0, at).takeIf { isValidTemplateName(it) } ?: return null
        val version = stripped.substring(at + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
        return id to version
    }

    override fun getReader(
        templateSource: Any?,
        encoding: String?,
    ): Reader = StringReader((templateSource as Source).effectiveSource)

    /**
     * The row's write stamp (a draft's last overwrite, else its creation), so a Freemarker-side
     * cache comparing it would reload an overwritten draft. Until 132 this was a constant on
     * the premise that every version is immutable — the sole DRAFT is not (templates.md §5.1).
     */
    override fun getLastModified(templateSource: Any?): Long = (templateSource as Source).lastModified

    override fun closeTemplateSource(templateSource: Any?) = Unit

    private companion object {
        /**
         * @throws IOException if any entry is not a plain `{id, version, alias}` triple. Freemarker
         *   surfaces a loader `IOException` as a render failure, which is the fail-closed outcome:
         *   the render is refused rather than proceeding with injected source.
         *
         * The emitted name is **root-based** (`/{name}@{version}`, template-hierarchy-design
         * §4.4): with `/` legal in names, a bare `lib/dates@1` imported from
         * `acme/finance/report` would resolve against the importer's directory and silently
         * become `acme/finance/lib/dates@1`. The leading `/` pins every reference to the tree
         * root, and [parseKey] strips exactly that one slash on the way back in.
         */
        fun synthesizePrologue(imports: List<TemplateImport>): String =
            imports.joinToString(separator = "") {
                if (!it.isSafeToSynthesize()) {
                    throw IOException("Refusing to synthesize an import prologue for an unsafe imports entry.")
                }
                "<#import \"/${it.key}\" as ${it.alias}>\n"
            }
    }
}
