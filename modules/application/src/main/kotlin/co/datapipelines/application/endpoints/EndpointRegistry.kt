package co.datapipelines.application.endpoints

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * The per-instance cache of the published-endpoint registry (design §4.1), and the only thing the
 * catch-all handler consults to resolve a request path.
 *
 * ## Why a cache at all
 *
 * Every request under `/api/x/` has to answer "which endpoint is this?" before it can do anything
 * else — including refusing an unauthenticated caller. Reading the table per request would put a
 * metadata-DB round trip in front of every 404, which is the cheapest thing a hostile caller can
 * ask for. The registry is small (tens of rows) and changes rarely (a human publishes), so it is
 * held whole.
 *
 * ## Why invalidation goes through Redis and not a TTL
 *
 * A TTL would make "I published it and it still 404s" a normal experience for a window nobody can
 * predict, and would differ per instance. Instead a write publishes on the 050 channel and every
 * OTHER instance drops its snapshot; the writing instance drops its own synchronously. This is the
 * same mechanism, and deliberately the same shape, as datasource pool invalidation — one pattern
 * for "a row changed, other JVMs are holding it".
 *
 * If the publish fails (Redis down), the local instance is still correct and the peers serve a
 * stale registry until their next reload; that is logged and is the documented degradation, the
 * same trade the pool publisher makes. It is never a reason to fail the write that already
 * committed.
 *
 * ## The snapshot is immutable, and that is the concurrency story
 *
 * [snapshot] holds a fully-built [EndpointMatcher] in an [AtomicReference]. A reload builds a new
 * one and swaps it; readers hold whichever they got. There is no lock on the read path, no
 * partially-populated matcher is ever visible, and a reload racing a request means the request
 * sees either the whole old registry or the whole new one — never a mix.
 */
class EndpointRegistry(
    private val repository: PublishedEndpointRepository,
    private val invalidation: EndpointInvalidationPublisher = EndpointInvalidationPublisher.NONE,
) {
    private val log = LoggerFactory.getLogger(EndpointRegistry::class.java)
    private val snapshot = AtomicReference<EndpointMatcher?>(null)

    /** The matcher for the current registry, loading it on first use and after an invalidation. */
    fun matcher(): EndpointMatcher = snapshot.get() ?: reload()

    /**
     * Drops the local snapshot AND tells every other instance to drop theirs.
     *
     * Called after a publish/unpublish/enable write has COMMITTED — never before, or a peer could
     * reload the pre-write state and cache it again.
     */
    fun invalidate() {
        snapshot.set(null)
        invalidation.publish()
    }

    /** Drops the local snapshot only — what the Redis subscriber calls when a PEER wrote. */
    fun invalidateLocally() {
        snapshot.set(null)
    }

    /** Rebuilds the snapshot from the table. Visible for the wiring's warm-up and for tests. */
    fun reload(): EndpointMatcher {
        val endpoints = repository.findAllEnabled()
        val built = EndpointMatcher(endpoints)
        if (built.size != endpoints.size) {
            // EndpointMatcher drops a row whose stored pattern Spring's parser rejects. That
            // cannot happen through this application (§4.1 is strictly narrower than what
            // PathPatternParser accepts), so it means a row was written around the grammar —
            // worth an ERROR naming the count, and NOT worth taking every other endpoint down.
            log.error(
                "event=endpoint.registry_pattern_unparseable loaded={} matchable={} " +
                    "reason=\"rows whose stored path_pattern the matcher could not compile are not served\"",
                endpoints.size,
                built.size,
            )
        }
        snapshot.set(built)
        return built
    }
}

/**
 * "The published-endpoint registry changed" — fanned out to every other instance (§4.1).
 *
 * A `fun interface` in this module with a no-op default, so the registry has no Redis dependency
 * and its tests need no broker; the real publisher is wired in `web`, which owns the
 * infrastructure. Exactly the shape `PoolInvalidationPublisher` has, for exactly the same reason.
 */
fun interface EndpointInvalidationPublisher {
    /** Fan out "the endpoint registry is stale" to every OTHER instance. */
    fun publish()

    companion object {
        val NONE = EndpointInvalidationPublisher { }
    }
}
