package co.datapipelines.web.ui

import co.datapipelines.pipeline.LifecycleBucket
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletResponse
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * [CatalogUrl] — #591: the URL a catalog swap writes to the address bar is built only from the
 * handler's bound, validated values, form-encoded, on the catalog's own path. The controller
 * tests of each family pin that the header is written (and that a refused `lifecycle` is the 400
 * before it is); this class pins the URL itself.
 */
class CatalogUrlTest {
    @Test
    fun `a search with reserved characters is form-encoded beside the bucket's wire value`() {
        CatalogUrl.of(CatalogUrl.PIPELINES, "a b&c", LifecycleBucket.DRAFT_ONLY, 0) shouldBe
            "/pipelines?q=a+b%26c&lifecycle=draft"
    }

    @Test
    fun `an unfiltered first page stands at the bare path`() {
        CatalogUrl.of(CatalogUrl.DASHBOARDS, null, null, null) shouldBe "/dashboards"
        CatalogUrl.of(CatalogUrl.DASHBOARDS, "   ", null, 0) shouldBe "/dashboards"
        CatalogUrl.of(CatalogUrl.DASHBOARDS, "", null, -25) shouldBe "/dashboards"
    }

    @Test
    fun `q is trimmed and the offset rides only past the first page`() {
        CatalogUrl.of(CatalogUrl.PARAMETER_SETS, "  geo ", LifecycleBucket.WITH_DRAFTS, 25) shouldBe
            "/parameter-sets?q=geo&lifecycle=with-drafts&offset=25"
    }

    @Test
    fun `filters sit between q and lifecycle, an absent one left out`() {
        CatalogUrl.of(
            CatalogUrl.TEMPLATES,
            "rev",
            LifecycleBucket.RELEASED_WITH_DRAFT,
            0,
            listOf("dialect" to "postgres", "type" to null),
        ) shouldBe "/templates?q=rev&dialect=postgres&lifecycle=released-with-draft"
    }

    /**
     * The header is same-origin and path-relative whatever the search holds: no value can end
     * the query, open a fragment, or turn the URL into a scheme-relative or absolute one, and
     * the servlet's own parse of the URL gives the search back exactly.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "//evil.example/x",
            "https://evil.example/",
            "a#frag",
            "x&lifecycle=released",
            "100% + 1 = ?",
            "naïve 東京",
            "\r\nSet-Cookie: x=1",
        ],
    )
    fun `any search stays inside the query and round-trips through the servlet's parse`(q: String) {
        val url = CatalogUrl.of(CatalogUrl.VISUALIZATIONS, q, LifecycleBucket.DRAFT_ONLY, 0)

        url shouldStartWith "/visualizations?q="
        url shouldNotContain "#"
        url shouldNotContain "\r"
        url shouldNotContain "\n"
        URI(url).host shouldBe null
        // The servlet container's query parse: split on & and =, then form-decode each side.
        val params =
            URI(url).rawQuery.split("&").associate { pair ->
                val (name, value) = pair.split("=", limit = 2)
                name to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
        params shouldBe mapOf("q" to q.trim(), "lifecycle" to "draft")
    }

    @Test
    fun `replace writes the URL to htmx's replace header`() {
        val response = MockHttpServletResponse()

        CatalogUrl.replace(response, CatalogUrl.PIPELINES, "nyc", LifecycleBucket.WITH_RELEASES, 50)

        response.getHeader("HX-Replace-Url") shouldBe "/pipelines?q=nyc&lifecycle=with-releases&offset=50"
    }

    @Test
    fun `every catalog path is a same-origin absolute path`() {
        listOf(
            CatalogUrl.PIPELINES,
            CatalogUrl.TEMPLATES,
            CatalogUrl.DASHBOARDS,
            CatalogUrl.VISUALIZATIONS,
            CatalogUrl.PARAMETER_SETS,
        ).forEach { path ->
            path shouldStartWith "/"
            path shouldNotContain "//"
            path shouldNotContain "?"
        }
    }
}
