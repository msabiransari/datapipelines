package co.datapipelines.templates

import freemarker.core.HTMLOutputFormat
import freemarker.core.TemplateClassResolver
import freemarker.template.Configuration
import freemarker.template.SimpleObjectWrapper
import freemarker.template.TemplateExceptionHandler
import freemarker.template.Version
import java.util.TimeZone

/**
 * Builds the hardened Freemarker [Configuration]s of templates.md §4.3 — two of them since 046
 * (template-hierarchy-design §6), selected by a version's `type` at render time.
 *
 * Templates are authored by authenticated users but are still *untrusted input* to the render
 * engine (this is the SSTI module), so every knob below is set for the hostile case. The
 * requirements are fixed by the spec; only the accessor spellings were subject to the §4.3
 * implementation gate, which this file closes by compiling against the pinned artifact.
 *
 * The security settings are the **independent second layer** behind
 * [ForbiddenConstructScanner]: even if the save-time scan were bypassed, `?new` / `?api` /
 * `Execute` / `ObjectConstructor` cannot reach a Java class here, because class resolution is
 * off entirely and the object wrapper exposes no Java members. Both configurations below
 * carry this layer identically — the `html` one adds output escaping on top of it, never
 * instead of it.
 *
 * Every one of these settings is asserted directly by `FreemarkerConfigFactoryTest`, for
 * [create], [createHtml] and [parseOnly]. Three of them (`isAPIBuiltinEnabled`, the wrapper,
 * the exception handler) currently equal the library default, so without that test they could
 * be deleted with the suite still green — and a future default change would then open them
 * silently.
 */
object FreemarkerConfigFactory {
    /**
     * The Freemarker version this module is written against (templates.md §4.1/§4.3: the
     * `incompatibleImprovements` version is kept equal to the pinned catalog version).
     *
     * A **literal**, not `Configuration.getVersion()`, so a BOM-driven jar bump cannot silently
     * shift engine semantics under templates that were validated under the old ones. The
     * artifact is BOM-managed, so nothing in this repository states the version otherwise;
     * `FreemarkerConfigFactoryTest` asserts this constant still equals the artifact on the
     * classpath, which is what turns a bump into a red build and a deliberate decision.
     */
    val PINNED_VERSION: Version = Version(2, 3, 34)

    /**
     * The `sql` render configuration — exactly the one configuration this factory built before
     * 046: no output escaping, type-aware interpolation per templates.md §4.4.
     *
     * @param loader the registry-backed loader — the only way a body reaches the engine (§4.3).
     * @param cacheSize parsed-template cache entries (`datapipelines.templates.cache-size`,
     *   configuration.md §3.9).
     */
    fun create(
        loader: RegistryTemplateLoader,
        cacheSize: Int,
    ): Configuration = renderConfiguration(loader, cacheSize)

    /**
     * The `html` render configuration (046, template-hierarchy-design §6): identical hardening
     * to [create], **plus** `HTMLOutputFormat` with auto-escaping on — so `${user_value}` is
     * HTML-escaped by default and markup requires an explicit `?no_esc`.
     *
     * `ENABLE_IF_SUPPORTED_AUTO_ESCAPING_POLICY`, not FORCE — measured against the pinned
     * 2.3.34 jar: under FORCE, `?no_esc` is a **syntax error** ("can't be used when the
     * auto_escaping_policy was set to force"), and §6 requires the explicit opt-out to exist.
     * Under ENABLE_IF_SUPPORTED the policy keys off the output format's own capability, and
     * `HTMLOutputFormat` supports auto-escaping — so escaping is on for this configuration,
     * with the opt-out available. (The `sql` configuration's `UndefinedOutputFormat` supports
     * nothing, which is the second half of why it stays raw.)
     *
     * Nothing else differs: the loader, the watchdog-facing [InterruptibleConfiguration] base
     * and the save-time [ForbiddenConstructScanner] are shared with `sql`, because imports are
     * type-agnostic (§6) and the forbidden-construct contract is engine-level, not type-level.
     */
    fun createHtml(
        loader: RegistryTemplateLoader,
        cacheSize: Int,
    ): Configuration =
        renderConfiguration(loader, cacheSize).apply {
            outputFormat = HTMLOutputFormat.INSTANCE
            autoEscapingPolicy = Configuration.ENABLE_IF_SUPPORTED_AUTO_ESCAPING_POLICY
        }

    /**
     * A configuration for **parse-only** save validation (templates.md §7.1).
     *
     * `Template(name, source, cfg)` parses a string directly and never consults the loader, so
     * none is set. The §4.3 security settings are still applied so a body parses under exactly
     * the regime it will render under — nothing behaves one way at save and another at render.
     * That equality is the premise of the AST scan: the tree validated at save is the tree the
     * engine will execute.
     */
    fun parseOnly(): Configuration = Configuration(PINNED_VERSION).apply { harden() }

    /** The shared render-configuration skeleton: one loader, one hardening, one format set. */
    private fun renderConfiguration(
        loader: RegistryTemplateLoader,
        cacheSize: Int,
    ): Configuration =
        InterruptibleConfiguration(PINNED_VERSION, cacheSize).apply {
            // 1. Templates come only from the registry, keyed "id@version". No file/classpath loader.
            templateLoader = loader
            localizedLookup = false

            harden()

            // Type-aware interpolation (§4.4): plain numbers at their declared scale (no locale
            // grouping, no scientific notation, no dropped trailing zeros — see
            // PlainNumberFormatFactory for why no built-in format satisfies §4.4), lowercase
            // booleans, ISO dates in UTC. Date/time/binary values are additionally pre-formatted
            // to canonical strings by RenderContextNormalizer, so these are the belt behind that
            // brace for any java.util.Date that reaches the wrapper directly.
            // booleanFormat = "c" (not the literal "true,false", which is the sentinel default
            // that deliberately errors on ${bool}); "c" renders lowercase true/false.
            customNumberFormats = mapOf(PlainNumberFormatFactory.NAME to PlainNumberFormatFactory)
            numberFormat = "@${PlainNumberFormatFactory.NAME}"
            booleanFormat = "c"
            timeZone = TimeZone.getTimeZone("UTC")
            dateFormat = "iso"
            timeFormat = "iso"
            dateTimeFormat = "iso"
        }

    /** The §4.3 security settings, applied identically to the render and the parse config. */
    private fun Configuration.harden() {
        // No class resolution at all — kills ?new, ObjectConstructor, Execute, JythonRuntime.
        // ALLOWS_NOTHING_RESOLVER, not SAFER_RESOLVER (§4.3: SAFER is "not restrictive enough").
        newBuiltinClassResolver = TemplateClassResolver.ALLOWS_NOTHING_RESOLVER

        // No ?api. Default is already false; set explicitly so a future default cannot open it.
        isAPIBuiltinEnabled = false

        // Context holds only canonical scalars/collections (§4.4), so the wrapper never needs to
        // expose Java members. SimpleObjectWrapper, not a Beans wrapper.
        objectWrapper = SimpleObjectWrapper(PINNED_VERSION)

        // Render failures propagate as errors — never partially into the SQL string.
        templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        logTemplateExceptions = false

        // Pin the tag/interpolation syntax (§4.3). Note what this does and does not buy: with
        // ANGLE_BRACKET pinned, a stray `[#include "y"]` parses as inert TEXT — but a leading
        // `[#ftl]` header still overrides the setting and switches the parser (verified against
        // the pinned 2.3.34 jar). The refusal that actually closes that door is
        // ForbiddenConstructScanner's outright rejection of `[#` / `[=` (§4.2); this pin narrows
        // the surface, and the AST scan catches what gets through either way.
        tagSyntax = Configuration.ANGLE_BRACKET_TAG_SYNTAX
        interpolationSyntax = Configuration.LEGACY_INTERPOLATION_SYNTAX
    }
}
