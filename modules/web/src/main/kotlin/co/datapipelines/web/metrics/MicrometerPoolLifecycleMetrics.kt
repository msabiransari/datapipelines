package co.datapipelines.web.metrics

import co.datapipelines.datasources.pooling.PoolLifecycleMetrics
import io.micrometer.core.instrument.MeterRegistry

/**
 * The Micrometer half of [PoolLifecycleMetrics] (observability.md §4.1) — the pool-retirement
 * lifecycle datasources.md §5.2 describes, counted.
 *
 * It lives in `web` rather than in `datasources` for the reason module-structure §4.2 gives:
 * `datasources` is a plain library and does not depend on Micrometer, so the instrument is wired
 * where the registry is, exactly like `PoolInvalidationPublisher`'s Redis half.
 *
 * `datasource_name` is a bounded-cardinality tag (a deployment has tens of datasources), which
 * is what observability §4.3 permits and what the catalogued
 * `datapipelines.datasource.pool.*` family already uses.
 */
class MicrometerPoolLifecycleMetrics(
    private val registry: MeterRegistry,
) : PoolLifecycleMetrics {
    override fun poolRetired(datasourceName: String) {
        registry.counter(POOL_RETIRED, "datasource_name", datasourceName).increment()
    }

    /**
     * The ceiling fired. The count of connections it took down is deliberately NOT a tag — it is
     * unbounded — and travels in the WARN [co.datapipelines.datasources.pooling.ConnectionPoolManager]
     * logs beside this increment.
     */
    override fun poolHardClosed(
        datasourceName: String,
        activeConnections: Int,
    ) {
        registry.counter(POOL_HARD_CLOSED, "datasource_name", datasourceName).increment()
    }

    companion object {
        const val POOL_RETIRED = "datapipelines.datasource.pool.retired"
        const val POOL_HARD_CLOSED = "datapipelines.datasource.pool.hard_closed"
    }
}
