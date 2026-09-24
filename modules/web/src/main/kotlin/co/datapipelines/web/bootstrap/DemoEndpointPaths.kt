package co.datapipelines.web.bootstrap

import co.datapipelines.application.endpoints.EndpointPath

/**
 * The ONE statement of #224's demo-path mapping — a seeded pipeline's name as the published
 * endpoint path the seeder publishes and the site's demo-data page renders:
 * `nyc/mobility/taxi_vs_rideshare` → `/demo/nyc/mobility/taxi-vs-rideshare`.
 *
 * The name's first segment becomes the R-EP5 version segment (`nyc`), the rest become the
 * served path with underscores folded to hyphens (the grammar's alphabet allows both; URLs read
 * better hyphenated), and the category is always `demo` — never the reserved `v<n>` or `api`.
 * Both consumers derive from this object, so the endpoint a curl names can never drift from the
 * endpoint that answers it.
 */
object DemoEndpointPaths {
    /** The R-EP5 category every demo endpoint serves under. */
    const val CATEGORY = "demo"

    /** The stored form: `/demo/<version>/<path…>`, exactly what `EndpointPublishService` stores. */
    fun pathFor(pipelineName: String): String {
        val segments = pipelineName.split('/')
        require(segments.size >= 2) {
            "The demo pipeline name '$pipelineName' does not map to an endpoint path: at least " +
                "<version>/<name> segments are required under the demo category."
        }
        val path =
            "/$CATEGORY/" + segments.first() + "/" +
                segments.drop(1).joinToString("/") { segment -> segment.replace('_', '-') }
        EndpointPath.parse(path).getOrElse {
            throw IllegalArgumentException("The demo pipeline name '$pipelineName' maps to an illegal endpoint path: ${it.message}")
        }
        return path
    }
}
