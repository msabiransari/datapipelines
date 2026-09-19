package co.datapipelines.web.endpoints

import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.auth.ScopeInterceptor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * R-EP5's one rule, spelled three times by three modules — held to one corpus here.
 *
 * The reserved-category rule has three expressions that must never drift:
 *
 * - `EndpointPath.RESERVED_CATEGORY` (application) — the publish-time refusal and the
 *   serve-time drop;
 * - the regex inside [PublishedEndpointController.CATCH_ALL] (web) — the routing-table
 *   expression that keeps a reserved category from reaching the handler at all;
 * - `ScopeInterceptor.isPublishedEndpointPath` (auth) — the endpoint-key confinement, which
 *   cannot import the application's object (`auth` sits below it).
 *
 * This test lives in `web` because that is the one module that sees all three. A fourth
 * spelling — or a drift between these — fails here, not in production routing.
 */
class EndpointCategoryRuleTest {
    @Test
    fun `the mapping regex, the grammar rule and the auth rule agree on one corpus`() {
        val mapping = Regex("^" + EndpointPath.CATEGORY_URL_PATTERN + "$")

        assertAll(
            CORPUS.map { (category, legal) ->
                {
                    withClue(category) {
                        val grammarSays = !EndpointPath.RESERVED_CATEGORY.matches(category)
                        val mappingSays = mapping.matches(category)
                        val authSays = ScopeInterceptor.isPublishedEndpointPath("/api/$category/v1/x")
                        grammarSays shouldBe legal
                        mappingSays shouldBe legal
                        authSays shouldBe legal
                    }
                }
            },
        )
    }

    @Test
    fun `the auth rule is a first-segment test, never a prefix`() {
        assertAll(
            // Not under /api at all.
            { ScopeInterceptor.isPublishedEndpointPath("/dashboard") shouldBe false },
            { ScopeInterceptor.isPublishedEndpointPath("/partials/api-keys") shouldBe false },
            // '/api' alone or empty category: nothing is published there.
            { ScopeInterceptor.isPublishedEndpointPath("/api") shouldBe false },
            { ScopeInterceptor.isPublishedEndpointPath("/api/") shouldBe false },
            // The product's own namespace is not the published surface, however deep.
            { ScopeInterceptor.isPublishedEndpointPath("/api/v1/endpoints") shouldBe false },
            { ScopeInterceptor.isPublishedEndpointPath("/api/v2/revenue") shouldBe false },
            // A published path of any legal depth is.
            { ScopeInterceptor.isPublishedEndpointPath("/api/nyc/v1/revenue/Manhattan") shouldBe true },
            { ScopeInterceptor.isPublishedEndpointPath("/api/demo/v1/top-company-by-borough") shouldBe true },
        )
    }

    private companion object {
        /** Category → may serve published endpoints. The refusal cases and the near-misses both. */
        val CORPUS =
            listOf(
                "v1" to false,
                "v2" to false,
                "v10" to false,
                "api" to false,
                "trade" to true,
                "v" to true,
                "v1a" to true,
                "2025" to true,
                "nyc" to true,
                "apis" to true,
                "api-x" to true,
                "demo" to true,
            )
    }
}
