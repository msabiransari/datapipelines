package co.datapipelines.pipeline

/**
 * The §11.4 detector behind `pipeline.validation.forbidden_env_specific_value`.
 *
 * §2 principle 1 — "environment-portable by construction" — is the promise this enforces: a
 * pipeline exported from dev and imported into prod (§11.3) must mean the same thing there,
 * which it cannot if a hostname, a JDBC URL, a credential fragment or a UUID is baked into
 * its body.
 *
 * The heuristics are the seven §11.4 names them, and they are deliberately **conservative**
 * — whole-value or prefix matches only. The document's own examples are the acceptance
 * criteria: `stg_orders` containing "stg" is fine, and `pg-prod` is fine because it is a
 * datasource *name*. A false positive here blocks a legitimate save, which is worse than the
 * class of mistake the rule catches (an env-specific value is also caught at import time by
 * `pipeline.import.missing_datasource`).
 */
object EnvSpecificValueScanner {
    /** The kind of env-specific value found — reported in the failure's `details.heuristic`. */
    enum class Heuristic {
        /** `^jdbc:` prefix. */
        JDBC_URL,

        /** A dot-separated hostname with a TLD-like tail, or a `host:port` pair. */
        HOSTNAME,

        /** IPv4 dotted-quad, or a bracketed IPv6 literal. */
        IP_ADDRESS,

        /** RFC 4122 UUID shape. The pipeline-level `id` / `owner` are the only allowed UUIDs. */
        UUID,

        /** A `password=` / `pwd=` / `token=` / `secret=` fragment, or the `dpk_` API-key prefix. */
        CREDENTIAL,

        /** A leading `/`, or a Windows drive-letter prefix. */
        ABSOLUTE_PATH,

        /** The whole value is `dev`, `staging`, `prod` or `production`. */
        ENVIRONMENT_LITERAL,
    }

    /**
     * §12.2 crash-safety: a scanned value longer than this is passed through **un-flagged**,
     * before any regex runs.
     *
     * "A scanned value longer than 512 chars cannot be a hostname/URL/UUID/credential." The
     * bound is not an optimisation: `java.util.regex` recurses once per iteration of a
     * `(...)*` group, so a 5000-segment `a.a.a.…` value drove [HOSTNAME] into a
     * `StackOverflowError` — a save-time denial of service from a 10 KB payload, and an
     * `Error` escaping past every handler. Length is checked first because it is the only
     * check that is O(1) in the attacker's input.
     */
    internal const val MAX_SCANNED_LENGTH = 512

    /** Repetition cap on the dot-separated groups; see [MAX_SCANNED_LENGTH]. */
    private const val MAX_LABELS = 10

    private val JDBC_URL = Regex("^jdbc:", RegexOption.IGNORE_CASE)

    // A dot-separated name whose last label looks like a TLD (letters only, 2+ chars), or an
    // explicit host:port. Anchored whole-value: `fetch_orders.sql` has a 3-letter tail but no
    // template ref is ever scanned, and a table name cannot contain a dot ([a-z0-9_]+).
    //
    // The label group is bounded ({0,MAX_LABELS}) rather than `*`: an unbounded group is what
    // recurses per iteration in java.util.regex. Belt and braces with MAX_SCANNED_LENGTH — a
    // value with more than 10 labels is not a hostname anyone registers, and one long enough
    // to matter never reaches here anyway.
    private val HOSTNAME = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+){0,$MAX_LABELS}\\.[A-Za-z]{2,}$")
    private val HOST_PORT = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+){1,$MAX_LABELS}:[0-9]+$")

    private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:[0-9]+)?$")
    private val IPV6_BRACKETED = Regex("^\\[[0-9A-Fa-f:]+]")

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val CREDENTIAL_FRAGMENTS = listOf("password=", "pwd=", "token=", "secret=")
    private const val API_KEY_PREFIX = "dpk_"

    private val WINDOWS_PATH = Regex("^[A-Za-z]:[\\\\/]")

    private val ENVIRONMENT_LITERALS = setOf("dev", "staging", "prod", "production")

    /**
     * The heuristic [value] trips, or null when it is portable.
     *
     * Ordered most-specific first so the reported heuristic is the informative one: a JDBC
     * URL contains a hostname, and a connection string contains both plus a credential.
     */
    @Suppress("ReturnCount")
    fun detect(value: String): Heuristic? {
        if (value.isEmpty() || value.length > MAX_SCANNED_LENGTH) return null
        val trimmed = value.trim()
        if (JDBC_URL.containsMatchIn(trimmed)) return Heuristic.JDBC_URL
        if (isCredentialShaped(trimmed)) return Heuristic.CREDENTIAL
        if (UUID.containsMatchIn(trimmed)) return Heuristic.UUID
        if (IPV4.matches(trimmed) || IPV6_BRACKETED.containsMatchIn(trimmed)) return Heuristic.IP_ADDRESS
        if (HOST_PORT.matches(trimmed) || HOSTNAME.matches(trimmed)) return Heuristic.HOSTNAME
        if (trimmed.startsWith("/") || WINDOWS_PATH.containsMatchIn(trimmed)) return Heuristic.ABSOLUTE_PATH
        if (trimmed.lowercase() in ENVIRONMENT_LITERALS) return Heuristic.ENVIRONMENT_LITERAL
        return null
    }

    private fun isCredentialShaped(value: String): Boolean {
        val lower = value.lowercase()
        return CREDENTIAL_FRAGMENTS.any { lower.contains(it) } || lower.contains(API_KEY_PREFIX)
    }
}
