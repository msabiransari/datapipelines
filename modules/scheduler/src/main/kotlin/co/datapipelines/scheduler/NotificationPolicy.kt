package co.datapipelines.scheduler

import java.net.URI
import java.util.Locale

/**
 * The deployment's recipient-domain allowlist (configuration.md §3.29,
 * `datapipelines.scheduler.notifications.recipient-domains`). An entry is a domain — `example.com`
 * admits exactly that domain — or a subdomain wildcard — `*.example.com` admits every domain ending
 * in `.example.com` and NOT `example.com` itself (list both to admit both). Compared lower-cased.
 */
class RecipientDomains(
    entries: List<String>,
) {
    private val entries: List<String> = entries.map { it.trim().lowercase(Locale.ROOT) }

    val empty: Boolean get() = entries.isEmpty()

    /** True when [address]'s domain matches an entry. An empty list admits nothing. */
    fun admits(address: String): Boolean {
        val domain = address.substringAfterLast('@', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (domain.isEmpty()) return false
        return entries.any { entry ->
            if (entry.startsWith(WILDCARD)) domain.endsWith(entry.substring(1)) else domain == entry
        }
    }

    companion object {
        private const val WILDCARD = "*."
        private const val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        private val ENTRY = Regex("""^(?:\*\.)?$LABEL(?:\.$LABEL)*$""")

        /** The entry grammar the binding enforces; case is not part of it. */
        fun valid(entry: String): Boolean = ENTRY.matches(entry.trim().lowercase(Locale.ROOT))
    }
}

/** Why scheduler mail is off on this deployment — the `reason` of the computed delivery object (rest-api §20). */
enum class DeliveryOffReason(
    val wire: String,
) {
    /** `datapipelines.scheduler.notifications.enabled` is false (the default, owner ruling 2026-10-03). */
    DISABLED("disabled"),

    /** The shared SMTP transport is not configured. Startup refuses `enabled=true` without it; kept as the defensive arm. */
    MAIL_UNCONFIGURED("mail_unconfigured"),

    /** The `development` posture with an empty recipient-domain allowlist: nothing sends (owner ruling 2026-10-03). */
    NO_ALLOWLIST("no_allowlist"),
}

/** The deployment's notification delivery state: on, or off for one [DeliveryOffReason]. Never "delivered". */
data class NotificationDeliveryState(
    val off: DeliveryOffReason?,
) {
    val on: Boolean get() = off == null

    /** The wire object: `{"state":"on"}` or `{"state":"off","reason":"…"}`. */
    fun toWire(): Map<String, String> = off?.let { mapOf("state" to "off", "reason" to it.wire) } ?: mapOf("state" to "on")
}

/**
 * What the composition root says about the deployment around the scheduler (web supplies it — the
 * scheduler reads no auth, mail or posture type): whether the shared SMTP transport is configured
 * (`datapipelines.mail.host` and `from`), the configured base URL every notice links to (#207, never
 * a request's host) and whether the posture is `development`, where an empty allowlist means nothing
 * sends. An unknown posture reads as development: the stricter of the two. Absent (a context with no
 * composition root), [UNKNOWN] — mail off.
 */
data class NotificationDeployment(
    val mailConfigured: Boolean,
    val baseUrl: String?,
    val developmentPosture: Boolean,
) {
    /** The base URL's host — the right-hand side of every delivery's stable `Message-ID`. */
    val messageIdHost: String
        get() = baseUrl?.let { runCatching { URI(it).host }.getOrNull() }?.takeIf { it.isNotBlank() } ?: FALLBACK_HOST

    companion object {
        /** Only reachable with mail off (startup requires base-url to enable it); `.invalid` cannot collide (RFC 2606). */
        private const val FALLBACK_HOST = "datapipelines.invalid"

        /** No composition root said anything: no transport, no link, the strict posture. */
        val UNKNOWN = NotificationDeployment(mailConfigured = false, baseUrl = null, developmentPosture = true)
    }
}

/**
 * The owner's safety ruling (2026-10-03) as ONE decision, read at save and again at send (record
 * §6.1): scheduler mail is off unless enabled; it needs the shared transport; under the
 * `development` posture an empty allowlist means nothing sends; under `hardened` an empty allowlist
 * means no domain restriction (the ruling restricts only `development` — orchestrator choice 1,
 * #442); a non-empty allowlist admits only its domains, at either posture.
 */
class NotificationPolicy(
    private val notifications: SchedulerProperties.Notifications,
    val deployment: NotificationDeployment,
) {
    private val domains = notifications.allowedDomains

    /** The computed delivery state (replaces 442a's constant `off/disabled`). */
    val state: NotificationDeliveryState =
        NotificationDeliveryState(
            when {
                !notifications.enabled -> DeliveryOffReason.DISABLED
                !deployment.mailConfigured -> DeliveryOffReason.MAIL_UNCONFIGURED
                deployment.developmentPosture && domains.empty -> DeliveryOffReason.NO_ALLOWLIST
                else -> null
            },
        )

    /** The allowlist's verdict on [address] alone — empty admits everything (the `hardened` reading; [state] covers `development`). */
    fun admits(address: String): Boolean = domains.empty || domains.admits(address)

    /** True when the allowlist is non-empty and [address] is outside it — the save-time refusal. */
    fun refusesAtSave(address: String): Boolean = !domains.empty && !domains.admits(address)
}
