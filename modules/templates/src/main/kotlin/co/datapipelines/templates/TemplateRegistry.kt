package co.datapipelines.templates

/**
 * Lookup of stored template versions by `{id, version}` (templates.md §12.1).
 *
 * The engine and the validator read the registry rather than the repository directly so that
 * caching and the "does this id exist at all?" question live in one place. Versions are
 * immutable (templates.md §5.1), so an implementation is free to cache a [TemplateVersion]
 * forever once seen — a new version is a new key, never a mutation of an old one.
 */
interface TemplateRegistry {
    /**
     * The stored version, or null when this exact `{id, version}` is not present.
     *
     * Null does not distinguish "no such id" from "id exists but not this version" — that
     * split is [existsId]'s job, and [TemplateDryRendererImpl] combines the two to produce the
     * §12.6 `template_not_found` / `template_version_not_found` distinction.
     */
    fun lookup(
        id: String,
        version: Int,
    ): TemplateVersion?

    /** True when at least one (non-deleted) version of [id] exists. */
    fun existsId(id: String): Boolean
}
