package co.datapipelines.visualization

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `datapipelines.dashboards.*` — one row per key (configuration.md §3.34, the one definition — D8): the YAML
 * key relative to the prefix, its default and its inclusive bounds. [DashboardRuntimeConfig] and
 * [DashboardRuntimeProperties] take their defaults from here and [DashboardRuntimeConfig] enforces the bounds,
 * so no literal lives in two places; `DashboardRuntimeConfigKeysSpecDriftTest` holds this table, the doc,
 * `application.yml` and `ConfigValidator`'s rule file together.
 *
 * Every default is the owner-confirmed number of the implementation spec's §17 (2026-09-28). The bounds are the
 * lane's: wide enough that a deployment can tune, tight enough that a typo (`max-bytes-per-source: 0`, a
 * refresh deadline in milliseconds) is refused at boot naming the key instead of starving or unbounding the
 * runtime. The admission counters are JVM-LOCAL like `ExecutionSlots` (the record's 050/R2 shape) — a
 * cluster-wide count is a later decision (configuration.md §3.34 says so).
 */
enum class DashboardRuntimeKey(
    /** The key below `datapipelines.dashboards.` — dotted, because the YAML groups them (admission / results / timeouts). */
    val key: String,
    val default: Long,
    val min: Long,
    val max: Long,
) {
    MAX_CONCURRENT_REFRESHES_PER_WORKSPACE("admission.max-concurrent-refreshes-per-workspace", default = 4, min = 1, max = 100),
    MAX_EXECUTIONS_PER_REFRESH("admission.max-executions-per-refresh", default = 16, min = 1, max = 200),
    MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE(
        "admission.max-concurrent-dashboard-executions-per-instance",
        default = 40,
        min = 1,
        max = 1_000,
    ),
    MAX_WAIT_SECONDS("admission.max-wait-seconds", default = 10, min = 0, max = 120),
    MAX_BYTES_PER_SOURCE("results.max-bytes-per-source", default = 4_194_304, min = 1_024, max = 268_435_456),
    MAX_BYTES_PER_REFRESH("results.max-bytes-per-refresh", default = 33_554_432, min = 1_024, max = 1_073_741_824),
    DEFAULT_REFRESH_SECONDS("timeouts.default-refresh-seconds", default = 600, min = 1, max = 3_600),
    MAX_REFRESH_SECONDS("timeouts.max-refresh-seconds", default = 900, min = 1, max = 7_200),
    PARAMETER_LOCK_SECONDS("timeouts.parameter-lock-seconds", default = 30, min = 1, max = 600),
    RENDER_SECONDS("timeouts.render-seconds", default = 20, min = 1, max = 600),
    ;

    /** The full YAML path. */
    val path: String get() = "$PREFIX.$key"

    /** Refuses [value] outside this key's bounds, naming the key (the `VisualizationKey.check` shape). */
    fun check(value: Long) {
        require(value in min..max) { "$path must be $min..$max, was $value" }
    }

    companion object {
        const val PREFIX: String = "datapipelines.dashboards"
    }
}

/**
 * The dashboard runtime's numbers as its collaborators take them — plain, validated at construction.
 * [DashboardRuntimeProperties] is its Spring binding twin.
 *
 * Beyond each key's own bounds, three RELATIONS are refused (each names both keys): the default refresh deadline
 * may not exceed the cap; a source's byte cap may not exceed the refresh's; and the instance-wide dashboard
 * execution cap may not be below one refresh's execution ceiling (else a maximal refresh could never be admitted
 * — a permanent 429).
 */
data class DashboardRuntimeConfig(
    val maxConcurrentRefreshesPerWorkspace: Int = DashboardRuntimeKey.MAX_CONCURRENT_REFRESHES_PER_WORKSPACE.default.toInt(),
    val maxExecutionsPerRefresh: Int = DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH.default.toInt(),
    val maxConcurrentDashboardExecutionsPerInstance: Int =
        DashboardRuntimeKey.MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE.default.toInt(),
    val maxWaitSeconds: Int = DashboardRuntimeKey.MAX_WAIT_SECONDS.default.toInt(),
    val maxBytesPerSource: Long = DashboardRuntimeKey.MAX_BYTES_PER_SOURCE.default,
    val maxBytesPerRefresh: Long = DashboardRuntimeKey.MAX_BYTES_PER_REFRESH.default,
    val defaultRefreshSeconds: Int = DashboardRuntimeKey.DEFAULT_REFRESH_SECONDS.default.toInt(),
    val maxRefreshSeconds: Int = DashboardRuntimeKey.MAX_REFRESH_SECONDS.default.toInt(),
    val parameterLockSeconds: Int = DashboardRuntimeKey.PARAMETER_LOCK_SECONDS.default.toInt(),
    val renderSeconds: Int = DashboardRuntimeKey.RENDER_SECONDS.default.toInt(),
) {
    init {
        valuesByKey().forEach { (key, value) -> key.check(value) }
        require(defaultRefreshSeconds <= maxRefreshSeconds) {
            "${DashboardRuntimeKey.DEFAULT_REFRESH_SECONDS.path} ($defaultRefreshSeconds) must not exceed " +
                "${DashboardRuntimeKey.MAX_REFRESH_SECONDS.path} ($maxRefreshSeconds)"
        }
        require(maxBytesPerSource <= maxBytesPerRefresh) {
            "${DashboardRuntimeKey.MAX_BYTES_PER_SOURCE.path} ($maxBytesPerSource) must not exceed " +
                "${DashboardRuntimeKey.MAX_BYTES_PER_REFRESH.path} ($maxBytesPerRefresh)"
        }
        require(maxExecutionsPerRefresh <= maxConcurrentDashboardExecutionsPerInstance) {
            "${DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH.path} ($maxExecutionsPerRefresh) must not exceed " +
                "${DashboardRuntimeKey.MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE.path} " +
                "($maxConcurrentDashboardExecutionsPerInstance)"
        }
    }

    /** Every key with its value — the bounds check's and the drift test's one enumeration. */
    fun valuesByKey(): Map<DashboardRuntimeKey, Long> =
        linkedMapOf(
            DashboardRuntimeKey.MAX_CONCURRENT_REFRESHES_PER_WORKSPACE to maxConcurrentRefreshesPerWorkspace.toLong(),
            DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH to maxExecutionsPerRefresh.toLong(),
            DashboardRuntimeKey.MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE to maxConcurrentDashboardExecutionsPerInstance.toLong(),
            DashboardRuntimeKey.MAX_WAIT_SECONDS to maxWaitSeconds.toLong(),
            DashboardRuntimeKey.MAX_BYTES_PER_SOURCE to maxBytesPerSource,
            DashboardRuntimeKey.MAX_BYTES_PER_REFRESH to maxBytesPerRefresh,
            DashboardRuntimeKey.DEFAULT_REFRESH_SECONDS to defaultRefreshSeconds.toLong(),
            DashboardRuntimeKey.MAX_REFRESH_SECONDS to maxRefreshSeconds.toLong(),
            DashboardRuntimeKey.PARAMETER_LOCK_SECONDS to parameterLockSeconds.toLong(),
            DashboardRuntimeKey.RENDER_SECONDS to renderSeconds.toLong(),
        )
}

/**
 * The Spring binding of `datapipelines.dashboards.*` — the YAML groups the keys (`admission`, `results`,
 * `timeouts`), so the binding does too; [toConfig] flattens them into [DashboardRuntimeConfig], which enforces
 * every bound and relation.
 */
@ConfigurationProperties(prefix = DashboardRuntimeKey.PREFIX)
data class DashboardRuntimeProperties(
    val admission: Admission = Admission(),
    val results: Results = Results(),
    val timeouts: Timeouts = Timeouts(),
) {
    data class Admission(
        val maxConcurrentRefreshesPerWorkspace: Int = DashboardRuntimeKey.MAX_CONCURRENT_REFRESHES_PER_WORKSPACE.default.toInt(),
        val maxExecutionsPerRefresh: Int = DashboardRuntimeKey.MAX_EXECUTIONS_PER_REFRESH.default.toInt(),
        val maxConcurrentDashboardExecutionsPerInstance: Int =
            DashboardRuntimeKey.MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE.default.toInt(),
        val maxWaitSeconds: Int = DashboardRuntimeKey.MAX_WAIT_SECONDS.default.toInt(),
    )

    data class Results(
        val maxBytesPerSource: Long = DashboardRuntimeKey.MAX_BYTES_PER_SOURCE.default,
        val maxBytesPerRefresh: Long = DashboardRuntimeKey.MAX_BYTES_PER_REFRESH.default,
    )

    data class Timeouts(
        val defaultRefreshSeconds: Int = DashboardRuntimeKey.DEFAULT_REFRESH_SECONDS.default.toInt(),
        val maxRefreshSeconds: Int = DashboardRuntimeKey.MAX_REFRESH_SECONDS.default.toInt(),
        val parameterLockSeconds: Int = DashboardRuntimeKey.PARAMETER_LOCK_SECONDS.default.toInt(),
        val renderSeconds: Int = DashboardRuntimeKey.RENDER_SECONDS.default.toInt(),
    )

    /** The domain config — constructing it enforces every bound and relation, naming the key. */
    fun toConfig(): DashboardRuntimeConfig =
        DashboardRuntimeConfig(
            maxConcurrentRefreshesPerWorkspace = admission.maxConcurrentRefreshesPerWorkspace,
            maxExecutionsPerRefresh = admission.maxExecutionsPerRefresh,
            maxConcurrentDashboardExecutionsPerInstance = admission.maxConcurrentDashboardExecutionsPerInstance,
            maxWaitSeconds = admission.maxWaitSeconds,
            maxBytesPerSource = results.maxBytesPerSource,
            maxBytesPerRefresh = results.maxBytesPerRefresh,
            defaultRefreshSeconds = timeouts.defaultRefreshSeconds,
            maxRefreshSeconds = timeouts.maxRefreshSeconds,
            parameterLockSeconds = timeouts.parameterLockSeconds,
            renderSeconds = timeouts.renderSeconds,
        )
}
