package co.datapipelines.application.endpoints

import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import org.springframework.web.util.pattern.PatternParseException

/**
 * Matches a request path against the published-endpoint registry (design §4.1).
 *
 * ## Spring's parser as a LIBRARY, never as request mapping
 *
 * The paths users publish never reach Spring's `RequestMappingHandlerMapping`: the whole
 * `/api/x/{path}` subtree is served by ONE catch-all handler (ruling R-EP1), and this class parses
 * and matches the patterns itself with [PathPatternParser]. That is what keeps publishing a
 * database write instead of a mutation of the servlet container's routing table — no
 * re-registration, no restart, no per-instance divergence, and no way for a published path to
 * shadow a product route.
 *
 * ## Longest-literal precedence is NOT implemented, on purpose
 *
 * `PathPattern` implements `Comparable` and Spring's own mapping uses it to prefer `/a/b` over
 * `/a/{x}`. This matcher does not sort, does not rank and has no tie-break — because §4.1
 * refuses to publish an ambiguous pattern in the first place ([EndpointPath.overlaps], enforced
 * by the repository with `endpoint.path_conflict`). At request time **at most one** pattern can
 * therefore match.
 *
 * That invariant is load-bearing enough to be asserted rather than asserted-in-prose:
 * `EndpointMatcherTest` publishes a conflict-free set and fuzzes request paths against it,
 * failing if any path ever matches two patterns. If the ambiguity check were ever weakened, this
 * matcher would start silently picking one of two meanings for a URL — the fuzz is what makes
 * that go red instead.
 */
class EndpointMatcher(
    endpoints: Collection<PublishedEndpoint>,
) {
    /**
     * The compiled patterns, paired with their rows.
     *
     * A pattern that Spring's parser rejects is dropped rather than thrown on: the grammar
     * (§4.1) is strictly narrower than what `PathPatternParser` accepts, so a parse failure here
     * means the row was written by something that bypassed [EndpointPath], and refusing the whole
     * registry would take every OTHER endpoint down with it. The drop is logged by the caller
     * that built this matcher.
     */
    private val compiled: List<Pair<PathPattern, PublishedEndpoint>> =
        endpoints.mapNotNull { endpoint ->
            runCatching { PARSER.parse(endpoint.pathPattern) }
                .getOrElse { if (it is PatternParseException) null else throw it }
                ?.let { it to endpoint }
        }

    /** How many patterns this matcher holds — the non-vacuity handle a fuzz test needs. */
    val size: Int get() = compiled.size

    /**
     * The endpoint this request path resolves to, with its extracted path variables — or null
     * when nothing matches (§5.6's `404`).
     *
     * [path] is the part AFTER `/api/x`, with its leading `/` (`/nyc/revenue/Manhattan`).
     */
    fun match(path: String): Match? {
        val parsed = PathContainer.parsePath(path)
        val hit = compiled.firstOrNull { (pattern, _) -> pattern.matches(parsed) } ?: return null
        val variables =
            hit.first
                .matchAndExtract(parsed)
                ?.uriVariables
                .orEmpty()
        return Match(hit.second, variables)
    }

    /**
     * Every endpoint whose pattern matches [path]. Only ever more than one if the §4.1 ambiguity
     * refusal has been bypassed — which is exactly what the fuzz test uses this for.
     */
    fun matchAll(path: String): List<PublishedEndpoint> {
        val parsed = PathContainer.parsePath(path)
        return compiled.filter { (pattern, _) -> pattern.matches(parsed) }.map { it.second }
    }

    /** A resolved request: the row, and the path variables the pattern bound. */
    data class Match(
        val endpoint: PublishedEndpoint,
        val pathVariables: Map<String, String>,
    )

    private companion object {
        /**
         * One parser for every pattern. `PathPatternParser` is thread-safe and its instances are
         * meant to be shared; the parsed [PathPattern]s are immutable.
         */
        val PARSER = PathPatternParser()
    }
}
