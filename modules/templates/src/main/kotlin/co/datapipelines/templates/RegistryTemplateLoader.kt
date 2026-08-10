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
 * resolving the closure transitively. The prologue depends only on the immutable version, so
 * the effective source is itself immutable and safe for Freemarker to cache.
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
     * One resolved source: the [key] Freemarker asked for and the effective FTL
     * ([prologue][synthesizePrologue] + body) it will parse.
     */
    private data class Source(
        val key: String,
        val effectiveSource: String,
    )

    override fun findTemplateSource(name: String): Any? {
        val key = parseKey(name) ?: return null
        val stored = registry.lookup(key.first, key.second) ?: return null
        return Source(name, synthesizePrologue(stored.imports) + stored.body)
    }

    /**
     * Splits `"{id}@{version}"` into its two halves, **checking the shape of both** — null for
     * anything that is not a well-formed registry key, so it never becomes a registry query.
     *
     * The `id` half is checked against the §7 `id` rule for the same fail-closed reason as the
     * version half (§6.3): a key reaching this loader by some route other than the validated
     * `imports` array is refused on its shape, rather than on the registry happening to have no
     * such row.
     */
    private fun parseKey(name: String): Pair<String, Int>? {
        val at = name.lastIndexOf('@')
        if (at <= 0 || at == name.length - 1) return null
        val id = name.substring(0, at).takeIf { TEMPLATE_ID.matches(it) } ?: return null
        val version = name.substring(at + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
        return id to version
    }

    override fun getReader(
        templateSource: Any?,
        encoding: String?,
    ): Reader = StringReader((templateSource as Source).effectiveSource)

    /**
     * A constant timestamp: every version is immutable (templates.md §5.1), so its source
     * never changes and Freemarker's cache never needs to reload it. A new version is a new
     * key, not a modification of this one.
     */
    override fun getLastModified(templateSource: Any?): Long = IMMUTABLE_TIMESTAMP

    override fun closeTemplateSource(templateSource: Any?) = Unit

    private companion object {
        const val IMMUTABLE_TIMESTAMP = 0L

        /**
         * @throws IOException if any entry is not a plain `{id, version, alias}` triple. Freemarker
         *   surfaces a loader `IOException` as a render failure, which is the fail-closed outcome:
         *   the render is refused rather than proceeding with injected source.
         */
        fun synthesizePrologue(imports: List<TemplateImport>): String =
            imports.joinToString(separator = "") {
                if (!it.isSafeToSynthesize()) {
                    throw IOException("Refusing to synthesize an import prologue for an unsafe imports entry.")
                }
                "<#import \"${it.key}\" as ${it.alias}>\n"
            }
    }
}
