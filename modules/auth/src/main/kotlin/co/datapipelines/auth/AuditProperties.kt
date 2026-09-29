package co.datapipelines.auth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.audit.*` keys ([Configuration §3.12](../../../../../../../docs/configuration.md)).
 *
 * Defaults here MUST equal configuration.md §3.12 — that document is the single authority, and
 * a binding class that quietly disagrees with it is a second authority.
 * `AuthPropertiesSpecDriftTest` fails the build on any divergence.
 *
 * ## The bounds refuse at BIND, which is at startup
 * The retention job deletes audit rows, and the audit trail is the one record of who did what —
 * so a value that would erase it (a typo'd `1`, a `0` meant as "off") must stop the deployment
 * before the first tick, not be honoured by it. The `require`s below run while Spring binds the
 * key, so an out-of-range value fails the context with the key named. There is no "off": a
 * deployment that wants the trail longer sets the ceiling.
 */
@ConfigurationProperties(prefix = "datapipelines.audit")
data class AuditProperties(
    /**
     * `retention-days` — how long an `audit_log` row lives before the hourly retention job
     * ([AuditLogRetention], metadata-db §8.2) deletes it. An `Int`: the job passes it to
     * Postgres's `make_interval(days => …)`, whose parameter is an `integer`.
     */
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    init {
        require(retentionDays >= MIN_RETENTION_DAYS) {
            "datapipelines.audit.retention-days must be >= $MIN_RETENTION_DAYS (was $retentionDays): " +
                "the retention job deletes audit rows older than this, and a smaller value would erase the trail"
        }
        require(retentionDays <= MAX_RETENTION_DAYS) {
            "datapipelines.audit.retention-days must be <= $MAX_RETENTION_DAYS (was $retentionDays)"
        }
    }

    companion object {
        /** configuration.md §3.12's default: one year. */
        const val DEFAULT_RETENTION_DAYS = 365

        /**
         * The floor: thirty days — a month of trail survives any configuration, long enough for an
         * incident noticed at the next monthly review to still have its record.
         */
        const val MIN_RETENTION_DAYS = 30

        /** The ceiling: ten years — past it, "keep it" is the honest intent, and a number is a guess. */
        const val MAX_RETENTION_DAYS = 3650
    }
}
