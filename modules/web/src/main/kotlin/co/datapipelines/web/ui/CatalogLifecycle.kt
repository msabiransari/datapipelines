package co.datapipelines.web.ui

import co.datapipelines.pipeline.LifecycleBucket
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * #556 — the catalogs' `lifecycle` query parameter: its ONE parse, the words every page shows
 * for a bucket, and which buckets a family's catalog offers.
 *
 * A family offers a bucket only when every object in it has a page to open: a discarded-only
 * pipeline has none (its workspace answers 404, the live-only `findById`), so the pipelines
 * catalog does not offer [LifecycleBucket.DISCARDED_ONLY] and its overview leaves that bar
 * unlinked. A family without an entry offers nothing yet (#579–#582 add theirs).
 *
 * Bound as a STRING by the callers, like [RequestedVersion]: an unknown or unoffered value is the
 * house 400 with the constant [BAD_LIFECYCLE], which never carries the input.
 */
object CatalogLifecycle {
    /** The refusal's one reason — a constant, so nothing of the input can ride it. */
    const val BAD_LIFECYCLE = "The lifecycle parameter is not a filter this catalog offers."

    /** The pipelines catalog's filters: every bucket whose objects all have a workspace page. */
    val PIPELINES: List<LifecycleBucket> = LifecycleBucket.entries.filter { it != LifecycleBucket.DISCARDED_ONLY }

    /**
     * #582 — the parameter-sets catalog's filters, the same five: a discarded-only set has no page
     * either (its pointer falls to NULL when its last release is discarded, so the workspace's
     * `findWorking` answers nothing and the route is the family's 404).
     */
    val PARAMETER_SETS: List<LifecycleBucket> = LifecycleBucket.entries.filter { it != LifecycleBucket.DISCARDED_ONLY }

    /** `null`/empty → null (no filter); an offered bucket's wire value → it; anything else → the 400. */
    fun parse(
        raw: String?,
        offered: List<LifecycleBucket>,
    ): LifecycleBucket? {
        if (raw.isNullOrEmpty()) return null
        return LifecycleBucket.ofWire(raw)?.takeIf { it in offered }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_LIFECYCLE)
    }

    /** The bucket's name on every surface — the overview's bars and counts, the catalog's control. */
    fun label(bucket: LifecycleBucket): String =
        when (bucket) {
            LifecycleBucket.DRAFT_ONLY -> "Draft only"
            LifecycleBucket.RELEASED_ONLY -> "Released only"
            LifecycleBucket.RELEASED_WITH_DRAFT -> "Released with draft changes"
            LifecycleBucket.DISCARDED_ONLY -> "Discarded only"
            LifecycleBucket.WITH_DRAFTS -> "With drafts"
            LifecycleBucket.WITH_RELEASES -> "With releases"
        }

    /** One control option: the query value and its words. */
    data class Option(
        val value: String,
        val label: String,
    )

    /** The control's options for [offered], in the inventory's order. */
    fun options(offered: List<LifecycleBucket>): List<Option> = offered.map { Option(it.wire, label(it)) }
}
