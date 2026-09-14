package co.datapipelines.config

import co.datapipelines.web.config.DeploymentEnv

/**
 * The §3.27 mail rules (137), in their own file for the reason [PostureRules] has one: the
 * validator's companion is at the size the build's complexity guard allows, and these rules
 * belong together — they are the whole of what "mail is enabled exactly when it is configured"
 * means in enforcement (auth.md §5A.8).
 *
 * Pure functions of a [MailSnapshot] inside the [ConfigSnapshot]. `ConfigValidatorCheckCountTest`
 * counts `check*` declarations across every rule file, so a rule here is in the number the
 * boot line quotes.
 *
 * The snapshot carries the SMTP password as PRESENCE only — it is a credential, and a §7
 * violation is a logged line.
 */
internal object MailRules {
    private const val KEY_HOST = "datapipelines.mail.host"
    private const val KEY_FROM = "datapipelines.mail.from"
    private const val KEY_OPS_TO = "datapipelines.mail.ops-to"
    private const val KEY_PORT = "datapipelines.mail.port"
    private const val KEY_STARTTLS = "datapipelines.mail.starttls"
    private const val KEY_USERNAME = "datapipelines.mail.username"
    private const val KEY_PASSWORD = "datapipelines.mail.password"
    private const val KEY_BASE_URL = "datapipelines.auth.base-url"

    private const val MAX_PORT = 65_535

    /**
     * §7 / §3.27 — the shape rules. Mail is on exactly when `host` and `from` are both set
     * (there is no flag), so every half-configured shape is a refusal naming the missing half:
     *
     *  - `host` without `from`, or `from` without `host` — one field names the other;
     *  - `ops-to` without `host` — a sink nobody can reach is a misconfiguration, not a
     *    preference;
     *  - mail on without `datapipelines.auth.base-url` — the welcome mail carries the login
     *    URL, and the request's origin is not an acceptable source for it (auth.md §5.2);
     *  - `port` a positive integer in range, `from` an address (a bare `@` check — the
     *    transport validates the rest at send time, where a malformed sender fails visibly
     *    rather than at boot with a parser's opinion).
     */
    fun checkMailShape(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val mail = snapshot.mail
        val host = mail.host?.trim().orEmpty()
        val from = mail.from?.trim().orEmpty()
        if (host.isNotEmpty() && from.isEmpty()) {
            violations +=
                "$KEY_HOST is set but $KEY_FROM is not (§3.27): mail is enabled exactly when both are set — " +
                "set the sender, or unset the host."
        }
        if (from.isNotEmpty() && host.isEmpty()) {
            violations +=
                "$KEY_FROM is set but $KEY_HOST is not (§3.27): mail is enabled exactly when both are set — " +
                "set the SMTP host, or unset the sender."
        }
        if (!mail.opsTo.isNullOrBlank() && host.isEmpty()) {
            violations +=
                "$KEY_OPS_TO is set but $KEY_HOST is not (§3.27): a sys-ops sink nobody can reach is a " +
                "misconfiguration, not a preference."
        }
        if (mail.enabled && mail.authBaseUrl.isNullOrBlank()) {
            violations +=
                "$KEY_HOST is set but $KEY_BASE_URL is not (§3.27): the welcome mail carries the login URL, " +
                "which is built from base-url and never from a request's origin (auth.md §5.2)."
        }
        formatRules(mail, violations)
    }

    /** The two value-format halves of [checkMailShape] — a branch of that rule, not a check of its own. */
    private fun formatRules(
        mail: MailSnapshot,
        violations: MutableList<String>,
    ) {
        val from = mail.from?.trim().orEmpty()
        if (from.isNotEmpty() && !from.contains('@')) {
            violations += "$KEY_FROM '$from' is not an email address (§3.27): a bare address or `Display Name <address>`."
        }
        mail.port?.let { raw ->
            val parsed = raw.trim().toIntOrNull()
            if (parsed == null || parsed < 1 || parsed > MAX_PORT) {
                violations += "$KEY_PORT '$raw' is not a port in 1..$MAX_PORT (§3.27)."
            }
        }
    }

    /**
     * §7 / §3.27 — the `hardened` posture's two mail refusals, each naming the variable and the
     * posture (the posture table in docs/environments.md, in prose): `starttls` must be true
     * (a submission connection that may fall back to plaintext carries the one-time password
     * on the wire), and a `username` must come with a `password` (a credential half-set is a
     * server that will refuse every message after boot succeeded). Both only when mail is on.
     */
    fun checkMailHardened(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val env = DeploymentEnv.resolveEnv(snapshot.env, snapshot.deploymentName)
        if (DeploymentEnv.resolvePosture(snapshot.posture, env) != DeploymentEnv.HARDENED) return
        val mail = snapshot.mail
        if (!mail.enabled) return
        val where = "under ${DeploymentEnv.POSTURE_KEY}=${DeploymentEnv.HARDENED} (env '$env', §3.23)"

        if (mail.starttls?.trim()?.equals("true", ignoreCase = true) != true) {
            violations +=
                "$KEY_STARTTLS is '${mail.starttls}' $where. A hardened deployment does not hand a " +
                "one-time password to a mail server over plaintext (DATAPIPELINES_MAIL_STARTTLS=true)."
        }
        if (!mail.username.isNullOrBlank() && !mail.passwordSet) {
            violations +=
                "$KEY_USERNAME is set but $KEY_PASSWORD is not $where. Set both (DATAPIPELINES_MAIL_PASSWORD), or " +
                "neither for a relay that takes no credentials."
        }
    }
}
