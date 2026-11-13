package co.datapipelines.pipeline.release

/**
 * The release-dependency bounds (release-dependencies record §1.2 and §4.3, ruled §17 row 4) as the plain value the
 * family-neutral core reads. `web` binds them from `datapipelines.release.dependencies.*` (configuration.md §3.35)
 * and hands this value down — the core never sees the Spring class, so a directly constructed graph walk in a unit
 * test states its bounds explicitly.
 *
 * Every bound is enforced WHILE the closure is enumerated (an explicit stack, memoised, never recursion), so a graph
 * over a bound is refused before the walk materialises it, and a truncated closure can never authorise a release.
 */
data class ReleaseBounds(
    /** Longest dependency path from the root, in edges. Refused `<family>.release.dependency_limit` with `limit: depth`. */
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    /** Distinct versions in one closure, the root included. `limit: versions`. */
    val maxVersions: Int = DEFAULT_MAX_VERSIONS,
    /** Pin occurrences (edges, every incoming field path counted) in one closure. `limit: edges`. */
    val maxEdges: Int = DEFAULT_MAX_EDGES,
    /** Blockers reported in one refusal's `details.blockers`; the rest set `blockers_truncated`. */
    val maxBlockers: Int = DEFAULT_MAX_BLOCKERS,
    /** Bytes of one plan request or of the plan's canonical serialisation. `limit: wire_bytes`. */
    val maxWireBytes: Int = DEFAULT_MAX_WIRE_BYTES,
    /** Seconds the workspace mutation guard waits for its lock before `<family>.release.busy`. */
    val lockWaitSeconds: Int = DEFAULT_LOCK_WAIT_SECONDS,
) {
    init {
        require(maxDepth >= 1) { "maxDepth must be at least 1, was $maxDepth" }
        require(maxVersions >= 1) { "maxVersions must be at least 1, was $maxVersions" }
        require(maxEdges >= 1) { "maxEdges must be at least 1, was $maxEdges" }
        require(maxBlockers >= 1) { "maxBlockers must be at least 1, was $maxBlockers" }
        require(maxWireBytes >= 1) { "maxWireBytes must be at least 1, was $maxWireBytes" }
        require(lockWaitSeconds >= 1) { "lockWaitSeconds must be at least 1, was $lockWaitSeconds" }
    }

    companion object {
        /** The ratified defaults (record §1.2, §17 row 4) — configuration.md §3.35's Default column. */
        const val DEFAULT_MAX_DEPTH = 32
        const val DEFAULT_MAX_VERSIONS = 1024
        const val DEFAULT_MAX_EDGES = 4096
        const val DEFAULT_MAX_BLOCKERS = 100
        const val DEFAULT_MAX_WIRE_BYTES = 1_048_576
        const val DEFAULT_LOCK_WAIT_SECONDS = 5
    }
}
