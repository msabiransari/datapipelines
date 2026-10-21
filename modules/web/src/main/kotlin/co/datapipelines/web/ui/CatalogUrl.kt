package co.datapipelines.web.ui

import co.datapipelines.pipeline.LifecycleBucket
import jakarta.servlet.http.HttpServletResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * #591 — the address a catalog's list stands at, answered by every catalog swap in
 * `HX-Replace-Url` so the address bar always describes the list on screen: a copied link shares
 * it, a reload renders it (each page route binds the same parameters), and Back leaves the
 * catalog instead of keeping the filter it arrived with.
 *
 * ONE place computes it for the five catalogs that offer `lifecycle` — Pipelines, Templates,
 * Dashboards, Visualizations, Parameter Sets — and their partial controllers call [replace] from
 * the list handler, so every request into the swap root carries it: the search box, the lifecycle
 * select (and the templates' dialect and type), the pager and the empty state's Clear buttons.
 *
 * A REPLACE, not a push (ui-screens §5, "The URL describes the list"): the search fires per
 * keystroke after its debounce, and a push would make each keystroke a history entry Back steps
 * through one by one. The catalog pages carry `hx-history="false"` beside it, so Back to a
 * catalog fetches the page at this URL rather than restoring a snapshot whose controls kept
 * their first-rendered values.
 *
 * The URL is built ONLY from values the handler already bound and validated — `q` trimmed, the
 * parsed bucket's wire value, the offset, the templates' parsed dialect and type — never from the
 * raw query string or a client-supplied URL. Each value is form-encoded, the inverse of the
 * servlet's query parse, so a `+` or `&` in a search comes back as itself. The path is one of
 * this object's constants, so the header is same-origin and path-relative by construction. An
 * empty value is left out: an unfiltered catalog on its first page stands at its bare path.
 */
object CatalogUrl {
    /** htmx 2's response header: replace the current history entry's URL after the swap. */
    const val HEADER = "HX-Replace-Url"

    const val PIPELINES = "/pipelines"
    const val TEMPLATES = "/templates"
    const val DASHBOARDS = "/dashboards"
    const val VISUALIZATIONS = "/visualizations"
    const val PARAMETER_SETS = "/parameter-sets"

    /**
     * The catalog URL for [path]: `q`, then each of [filters] (name to an already-parsed wire
     * value, `null` for none), then `lifecycle`, then `offset` when past the first page.
     */
    fun of(
        path: String,
        q: String?,
        lifecycle: LifecycleBucket?,
        offset: Int?,
        filters: List<Pair<String, String?>> = emptyList(),
    ): String {
        val params =
            buildList {
                add("q" to q?.trim())
                addAll(filters)
                add("lifecycle" to lifecycle?.wire)
                add("offset" to offset?.takeIf { it > 0 }?.toString())
            }.filter { !it.second.isNullOrEmpty() }
        if (params.isEmpty()) return path
        return path + "?" + params.joinToString("&") { (name, value) -> "$name=${encode(value.orEmpty())}" }
    }

    /** Answers [of] in [HEADER] on [response]. */
    fun replace(
        response: HttpServletResponse,
        path: String,
        q: String?,
        lifecycle: LifecycleBucket?,
        offset: Int?,
        filters: List<Pair<String, String?>> = emptyList(),
    ) = response.setHeader(HEADER, of(path, q, lifecycle, offset, filters))

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
