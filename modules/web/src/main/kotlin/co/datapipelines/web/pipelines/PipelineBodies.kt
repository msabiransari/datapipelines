package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/**
 * Bounded access to the pipeline table for the listing and reference-scan paths.
 *
 * ## The scan this class exists to bound (carry-forward #6)
 * [PipelineRepository.findAll] has **no SQL `LIMIT`** and returns every live pipeline; the
 * `datasource` filter of `GET /pipelines` (rest-api §5.7) and the `datasource.in_use` check
 * (rest-api §9.5) additionally have to look *inside* each body, so a naive implementation
 * deserializes the whole table on every request. `mcp-server` hit the same wall and applied the
 * same in-module mitigation — a single scan per request, memoized, cut into fixed pages in memory
 * — because the root fix changes `pipeline-contract`.
 *
 * **Cross-module follow-up (not this module's to make):** push `limit`/`offset` *and* the
 * datasource predicate down into `PipelineRepository`, so neither the row set nor the body
 * deserialization is unbounded. That changes `pipeline-contract`; `McpResourceCatalog`'s KDoc
 * records the same residual against the same repository.
 *
 * Until then the guarantees here are: **one** `findAll()` per [Scan], bodies parsed **lazily** and
 * at most once each, and a hard [MAX_SCANNED_PIPELINES] ceiling that truncates rather than letting
 * a large deployment turn one listing into an unbounded heap allocation. Truncation is reported,
 * never silent ([Scan.truncated]).
 */
class PipelineBodies(
    private val repository: PipelineRepository,
) {
    /** Opens a scan. One instance per request — never share one across requests. */
    fun scan(ownerId: UUID? = null): Scan = Scan(repository, ownerId)

    /**
     * Live pipeline **names** referencing [datasourceName], for `datasource.in_use`.
     *
     * A name, not an id: `DeleteResult.referencingPipelines` is what the error envelope shows a
     * human, and a UUID tells them nothing about which pipeline to edit.
     */
    fun pipelinesReferencing(datasourceName: String): List<String> =
        scan().let { s -> s.records.filter { s.usesDatasource(it, datasourceName) }.map { it.name } }

    /**
     * One request's view of the pipeline table.
     *
     * `records` is fetched once, lazily; `body(record)` parses a body at most once. Both are plain
     * `by lazy`, matching `McpResourceCatalog.RequestScan`, so a caller that filters and then pages
     * pays for exactly one scan.
     */
    class Scan(
        private val repository: PipelineRepository,
        private val ownerId: UUID?,
    ) {
        private val bodies = mutableMapOf<UUID, JsonNode?>()

        /** True when the table held more live pipelines than [MAX_SCANNED_PIPELINES]. */
        var truncated: Boolean = false
            private set

        /** Every live pipeline (optionally one owner's), newest first, capped at [MAX_SCANNED_PIPELINES]. */
        val records: List<PipelineRecord> by lazy {
            // The repository's own order (created_at DESC) is kept: it is deterministic, it is
            // what the listing promises, and re-sorting by UUID would scramble it (gate C, F12b).
            val all = repository.findAll(ownerId)
            truncated = all.size > MAX_SCANNED_PIPELINES
            all.take(MAX_SCANNED_PIPELINES)
        }

        /** The latest body of [record] as a tree, or null when it cannot be read. */
        fun body(record: PipelineRecord): JsonNode? =
            bodies.getOrPut(record.id) {
                repository.findVersionBody(record.id, record.currentVersion)?.let {
                    runCatching { MAPPER.readTree(it) }.getOrNull()
                }
            }

        /**
         * Whether [record] references [datasourceName] as a node `source` or an
         * `output.datasource` (rest-api §5.7's `datasource` filter, §9.5's in-use check).
         *
         * Read off the JSON tree rather than the bound model: an unbound body must still be
         * scannable, and binding every candidate is the cost this class exists to avoid.
         */
        fun usesDatasource(
            record: PipelineRecord,
            datasourceName: String,
        ): Boolean {
            val nodes = body(record)?.path("nodes") ?: return false
            if (!nodes.isArray) return false
            return nodes.any { node ->
                node.path("source").asText(null) == datasourceName ||
                    node.path("output").path("datasource").asText(null) == datasourceName
            }
        }

        /** Case-insensitive match of [query] against name, display name and description (§5.7 `q`). */
        fun matchesQuery(
            record: PipelineRecord,
            query: String,
        ): Boolean {
            val needle = query.lowercase()
            return record.name.lowercase().contains(needle) ||
                record.displayName.lowercase().contains(needle) ||
                record.description.lowercase().contains(needle)
        }
    }

    companion object {
        /**
         * The ceiling on one scan. Fixed, never client-controllable — a client-tunable scan bound
         * is not a bound. Sized well above any plausible v1 deployment so truncation signals "the
         * push-down follow-up is now required", not "your listing is broken".
         */
        const val MAX_SCANNED_PIPELINES: Int = 5000

        private val MAPPER = PipelineJson.objectMapper()
    }
}
