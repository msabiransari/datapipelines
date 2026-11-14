package co.datapipelines.config

/**
 * §7 / §3.29 (#957 D9; the P6 lane cut's L3; owner ruling 2026-11-13) — the scheduler's thread count against the lake's
 * maintenance, in its own file like [SchedulerNotificationRules].
 *
 * A lake maintenance run holds the scheduler thread that took its task for its whole length. With the managed lake and
 * the scheduler both enabled, `datapipelines.scheduler.threads` must therefore be at least
 * `datapipelines.lake.admission.max-concurrent-maintenance` + [THREADS_BEYOND_MAINTENANCE]: one thread per admitted run,
 * one for the maintenance planner, and one always free for the dispatcher, the reconciler and the notifier. The refusal
 * names both keys. A malformed maintenance value is named by `LakeRules.checkLakeAdmission` and not again here.
 */
internal object SchedulerThreadsRules {
    const val KEY_ENABLED = "datapipelines.scheduler.enabled"
    const val KEY_THREADS = "datapipelines.scheduler.threads"
    val KEYS: List<String> = listOf(KEY_ENABLED, KEY_THREADS)

    /** The planner's thread and the one always free, beyond one thread per admitted maintenance run. */
    const val THREADS_BEYOND_MAINTENANCE = 2

    /** `SchedulerProperties.threads`'s default, which an unset key boots with. */
    private const val DEFAULT_THREADS = 3

    /** `LakeProperties.Admission.maxConcurrentMaintenance`'s default, which an unset key boots with. */
    private const val DEFAULT_MAINTENANCE = 1

    fun checkMaintenanceThreads(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!isTrue(snapshot.lake[LakeRules.ENABLED], default = false)) return
        if (!isTrue(snapshot.scheduler[KEY_ENABLED], default = true)) return
        val rawMaintenance = snapshot.lake[LakeRules.MAX_CONCURRENT_MAINTENANCE]?.trim()
        val maintenance =
            if (rawMaintenance.isNullOrEmpty()) {
                DEFAULT_MAINTENANCE.toLong()
            } else {
                rawMaintenance.toLongOrNull()?.takeIf { it > 0 } ?: return
            }
        val raw = snapshot.scheduler[KEY_THREADS]?.trim()
        val threads = if (raw.isNullOrEmpty()) DEFAULT_THREADS.toLong() else raw.toLongOrNull()
        if (threads == null || threads < 1) {
            violations += "$KEY_THREADS must be a positive whole number (§3.29)."
            return
        }
        val required = maintenance + THREADS_BEYOND_MAINTENANCE
        if (threads < required) {
            violations +=
                "$KEY_THREADS ($threads) must be at least ${LakeRules.MAX_CONCURRENT_MAINTENANCE} ($maintenance) + " +
                "$THREADS_BEYOND_MAINTENANCE = $required with the managed lake and the scheduler enabled (§7): one thread " +
                "per admitted maintenance run, one for the maintenance planner, one always free for the dispatcher, " +
                "the reconciler and the notifier."
        }
    }

    private fun isTrue(
        raw: String?,
        default: Boolean,
    ): Boolean {
        val value = raw?.trim()
        return if (value.isNullOrEmpty()) default else value.equals("true", ignoreCase = true)
    }
}
