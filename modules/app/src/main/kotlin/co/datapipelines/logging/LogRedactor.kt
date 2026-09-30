package co.datapipelines.logging

/**
 * The one redaction authority for log output (#337, observability.md §9.2 — normative, not
 * configurable): the sensitive-key list, the never-redacted list, the member-key matcher and the
 * message-text scrub, all from this object so no layer can disagree with another.
 *
 * Two layers, both reading THIS list, so no logger call site can bypass redaction by choosing an
 * idiom:
 *
 * 1. **Structured members** — the JSON encoder's value processor ([RedactingJsonMembersCustomizer])
 *    asks [isSensitiveKey] per member (MDC entries and key-value pairs are members); a hit replaces
 *    the VALUE with `***`, the key stays. The `message` and `stack_trace` members are scrubbed by
 *    [scrubText] instead.
 * 2. **Rendered text** — [scrubText] rewrites the two shapes a secret takes inside free text,
 *    `key=value` and `"key": "value"`, wherever text is rendered: the message and stack-trace
 *    members under `json`, and the console pattern's [RedactingConverter] under `console` (it calls
 *    [scrubText] itself — ONE recognizer, [RedactionScanner], so the two formats cannot drift and
 *    the two shapes cannot disagree about where a value ends).
 *
 * There is no key, profile or flag that disables any of this (§9.2): a switch that can turn
 * secret-scrubbing off is a switch that will be off in some deployment.
 */
internal object LogRedactor {
    /**
     * §9.2's sensitive keys — matched case-insensitively on the WHOLE key and on the `*_<key>` /
     * `<key>_*` compounds (`db_password`, `password_hash`, `dp_api_key` match; `passwordless`
     * does not). Order is irrelevant; the matcher is a set.
     */
    val SENSITIVE_KEYS: Set<String> =
        setOf(
            "password", // datasource credentials, OIDC client secrets
            "secret", // JWT secret, OIDC client-secret
            "api_key", // the dpk_... plaintext, returned once at creation
            "authorization", // bearer / DP-API-Key header values
            "jdbc_url", // credentials inline for several drivers; internal topology besides
            "encryption_key", // DATAPIPELINES_DB_ENCRYPTION_KEY
        )

    /**
     * §9.2's never-redacted keys — the fields that make an incident diagnosable. The matcher
     * consults this first; none of them is a compound of a sensitive key, but the list is the
     * record's, and the drift test pins it independently.
     */
    val NEVER_REDACTED: Set<String> =
        setOf(
            "correlation_id",
            "execution_id",
            "pipeline_id",
            "node_id",
            "datasource_name",
            "user_id",
        )

    /** The whole redacted value — the key's presence is itself diagnostic, the value never is. */
    const val MASK: String = "***"

    /** `true` when [key] — an MDC entry, a key-value pair, a member name — must never carry a value. */
    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.trim().lowercase()
        if (normalized.isEmpty() || normalized in NEVER_REDACTED) return false
        val lastSegment = normalized.substringAfterLast('.')
        if (lastSegment in NEVER_REDACTED) return false
        // The key bounded by underscores appears whole in `_<key>_`: that is the whole key, the
        // `<key>_*` and `*_<key>` compounds AND the prefixed-and-suffixed `db_password_v2` the
        // text scanner ([RedactionScanner]) has always masked — one rule, so a member and a message
        // cannot disagree.
        val bounded = "_${lastSegment}_"
        return SENSITIVE_KEYS.any { sensitive -> bounded.contains("_${sensitive}_") }
    }

    /**
     * [text] with every `key=value` and `"key": "value"` occurrence of the list rewritten to `***`.
     * One left-to-right pass over the original text ([RedactionScanner]): a value's extent is
     * decided before anything is rewritten, so overlapping forms cannot change each other's
     * boundaries (#337-c F5), and a failed match is never rescanned (F6).
     */
    fun scrubText(text: String): String = scrubMeasured(text).text

    /** [scrubText] plus the scanner's operation count — the deterministic bound the tests hold. */
    fun scrubMeasured(text: String): Scrubbed {
        val scanner = RedactionScanner(text, SENSITIVE_KEYS.toList(), MASK)
        val scrubbed = scanner.scrub()
        return Scrubbed(scrubbed, scanner.work)
    }

    /** The scrubbed text and the number of scan steps it took. */
    internal class Scrubbed(
        val text: String,
        val work: Long,
    )
}
