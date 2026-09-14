package co.datapipelines.templates

/**
 * Lookup of stored template versions by `{id, version}` (templates.md §12.1).
 *
 * The engine and the validator read the registry rather than the repository directly so that
 * caching and the "does this id exist at all?" question live in one place. A RELEASED or
 * DISCARDED version is immutable (templates.md §5.1), so an implementation is free to cache
 * such a [TemplateVersion] forever once seen; the sole DRAFT is overwritten in place by an
 * update (117) and deleted by a purge, so a [lookup] of a draft must reflect the row as it is
 * NOW — the contract [RepositoryTemplateRegistry] keeps by re-reading drafts (132).
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
