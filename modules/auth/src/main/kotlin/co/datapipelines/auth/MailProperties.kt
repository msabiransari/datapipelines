package co.datapipelines.auth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `datapipelines.mail.*` (configuration.md §3.27, auth.md §5A.8) — the SMTP server this
 * deployment sends its two notices through: the welcome / password-reset mail to a local
 * user (the one-time password, the login URL) and the new-user notice to sys-ops.
 *
 * ## Enabled is DERIVED, never a flag
 * Mail is on exactly when [host] and [from] are both set ([enabled]). There is deliberately
 * no `enabled` key: a boolean that can disagree with the fields it summarises is the
 * YAML-boolean trap the house has already logged once, and "configured" is the only honest
 * definition of "on" for an outbound adapter — a server nobody named cannot be sent through.
 * The half-configured shapes (a host without a from, a from without a host, an `ops-to`
 * without a host) are refused at boot by `ConfigValidator` §7, naming the field.
 *
 * ## The password never leaves this object as text
 * [toString] redacts it (pinned by `MailPropertiesTest`): a `@ConfigurationProperties` bean is
 * exactly the kind of object that ends up in a debug log line or a "print the config" dump.
 *
 * Every SMTP server is "traditional host + port + username + password" here — Postmark's SMTP
 * endpoint is one such server, and its message-stream header ([messageStream], the optional
 * `X-PM-Message-Stream`) is the only vendor-shaped knob: set, it rides every message; absent,
 * nothing is added.
 */
@ConfigurationProperties(prefix = "datapipelines.mail")
data class MailProperties(
    /** SMTP host. Blank = not configured (mail off). */
    val host: String? = null,
    /** SMTP port; 587 is the submission port every STARTTLS-capable server listens on. */
    val port: Int = DEFAULT_PORT,
    /** SMTP username; blank = the server takes no credentials (a relay on a private network). */
    val username: String? = null,
    /** SMTP password. Never logged, never in [toString]. */
    val password: String? = null,
    /**
     * STARTTLS on the submission connection — required, not opportunistic: `true` refuses a
     * server that does not upgrade rather than silently falling back to plaintext. The
     * `hardened` posture refuses `false` at boot (§7).
     */
    val starttls: Boolean = true,
    /**
     * The sender: a bare address or `Display Name <address>` — both notices are sent from it,
     * and it is half of what makes mail [enabled].
     */
    val from: String? = null,
    /** `Reply-To` on every message; blank = [from] ([effectiveReplyTo]). */
    val replyTo: String? = null,
    /**
     * The sys-ops sink for the new-user notice — one address or a comma list
     * ([opsRecipients]). Blank = no new-user notices, even with mail on.
     */
    val opsTo: String? = null,
    /** Postmark's `X-PM-Message-Stream`; blank = the header is not added. */
    val messageStream: String? = null,
) {
    /** Mail is on exactly when [host] and [from] are set — the KDoc's whole point. */
    val enabled: Boolean
        get() = !host.isNullOrBlank() && !from.isNullOrBlank()

    /** [replyTo], or [from] when it is blank. Null only when mail is off. */
    fun effectiveReplyTo(): String? = present(replyTo) ?: present(from)

    /** [opsTo] as a trimmed, non-empty list — the comma form of one env variable. */
    fun opsRecipients(): List<String> =
        opsTo
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** The bare address inside [from] — `Name <addr>` → `addr`; a bare address is itself. */
    fun fromAddress(): String? = present(from)?.let { raw -> ANGLE_ADDRESS.find(raw)?.groupValues?.get(1) ?: raw }

    private fun present(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    override fun toString(): String =
        "MailProperties(host=$host, port=$port, username=$username, password=<redacted>, starttls=$starttls, " +
            "from=$from, replyTo=$replyTo, opsTo=$opsTo, messageStream=$messageStream, enabled=$enabled)"

    companion object {
        /** The SMTP submission port (RFC 6409). */
        const val DEFAULT_PORT = 587

        private val ANGLE_ADDRESS = Regex("<([^<>]+)>\\s*$")
    }
}
