package co.datapipelines.web.pipelines

import co.datapipelines.application.IdempotencyMetrics
import co.datapipelines.web.metrics.WebMetrics

/**
 * `web`'s [IdempotencyMetrics]: the two counters `WebMetrics` already publishes, behind the port
 * `modules/application` declares.
 *
 * The port exists because the reservation moved out of `web` in 056 but the meter registry did
 * not — `application` may not import a web type, and a service that took `WebMetrics` directly
 * would be the layering violation `ArchitectureGuardTest` fails the build on. The counter names,
 * and therefore every dashboard over them, are unchanged.
 */
class WebIdempotencyMetrics(
    private val metrics: WebMetrics,
) : IdempotencyMetrics {
    override fun reservationHit() = metrics.idempotencyHit()

    override fun reservationConflict() = metrics.idempotencyConflict()
}
