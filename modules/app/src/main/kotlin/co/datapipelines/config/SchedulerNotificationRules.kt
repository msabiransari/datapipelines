package co.datapipelines.config

/**
 * §7 / §3.29 (#442b) — scheduler notification mail's startup rule, in its own file like
 * [MailRules] (the validator's companion is the registry, not the home, of the §3.x families).
 *
 * Scheduler mail is off unless `datapipelines.scheduler.notifications.enabled=true` (owner ruling
 * 2026-10-03). Turning it on is a half-configured shape unless the shared transport is configured
 * (`datapipelines.mail.host` AND `.from`, §3.27) and `datapipelines.auth.base-url` is set — every
 * notice links to the UI, and the link is built from base-url, never from a request's host (#207).
 * Both missing halves are named in one violation each.
 */
internal object SchedulerNotificationRules {
    const val KEY_ENABLED = "datapipelines.scheduler.notifications.enabled"
    private const val KEY_HOST = "datapipelines.mail.host"
    private const val KEY_FROM = "datapipelines.mail.from"
    private const val KEY_BASE_URL = "datapipelines.auth.base-url"

    fun checkSchedulerNotifications(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val mail = snapshot.mail
        if (mail.schedulerNotificationsEnabled?.trim()?.equals("true", ignoreCase = true) != true) return
        if (!mail.enabled) {
            violations +=
                "$KEY_ENABLED is true but mail is not configured (§3.29): scheduler mail rides the shared " +
                "transport — set $KEY_HOST and $KEY_FROM (§3.27), or set $KEY_ENABLED=false."
        }
        if (mail.authBaseUrl.isNullOrBlank()) {
            violations +=
                "$KEY_ENABLED is true but $KEY_BASE_URL is not set (§3.29): every schedule notice links to " +
                "the UI, and the link is built from base-url, never from a request's host (#207)."
        }
    }
}
