package co.datapipelines.typesystem

/**
 * Observes final resource release, which may finish after a nonblocking close returned.
 * This neutral lifecycle contract lets admission retain capacity while a quarantined JDBC lease drains.
 */
interface ReleaseNotifications {
    /** Invokes each registration once after final release, or immediately if released. Callbacks must not throw. */
    fun afterRelease(callback: () -> Unit)
}
