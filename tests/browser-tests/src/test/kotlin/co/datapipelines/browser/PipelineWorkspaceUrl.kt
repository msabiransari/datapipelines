package co.datapipelines.browser

import java.util.regex.Pattern

/**
 * The pipeline workspace's CANONICAL document URL (#348): `/pipelines/{uuid}`, with or without a
 * `version`/`tab` query. The old `/pipelines/{id}/editor` URL is a compatibility redirect into it,
 * so a suite that follows an app link — the catalog's Open, a search row, a datasource fact — waits
 * for THIS shape, never for `/editor`: the redirect's 302 is a hop, the page it lands on is the proof.
 */
object PipelineWorkspaceUrl {
    /** The whole document URL, scheme and host included — `page.url()` and a navigation entry's name. */
    val DOCUMENT: Regex = Regex("^https?://[^/]+/pipelines/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\\?.*)?$")

    /** The same shape for Playwright's `waitForURL` and `route` overloads that take a [Pattern]. */
    val PATTERN: Pattern = DOCUMENT.toPattern()

    /** True when [url] is the canonical workspace document — a response filter's one-liner. */
    fun matches(url: String): Boolean = DOCUMENT.matches(url)
}
