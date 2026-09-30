package co.datapipelines.logging

import java.util.regex.Pattern

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
 *    members under `json`, and the console pattern's `%replace` under `console`
 *    ([ObservabilityLoggingFormatPostProcessor] embeds [messageReplacePattern] /
 *    [exceptionReplacePattern] — the SAME pattern sources, so the two formats cannot drift).
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

    // The keys are regex-safe literals ([a-z_]); joined plainly so the %replace option strings
    // stay free of escapes.
    private val sensitiveAlternation: String = SENSITIVE_KEYS.joinToString("|")

    /**
     * One identifier carrying a sensitive key as itself or an underscore compound: optional
     * `<word>_` prefixes, the key, optional `_<word>` suffixes. Case-insensitive; `passwordless`
     * and `secrets` do not match (no underscore boundary), `user_password` and `api_key_v2` do.
     */
    private val identifier: String =
        "(?i:(?:[A-Za-z0-9]+_)*(?:$sensitiveAlternation)(?:_[A-Za-z0-9]+)*)"

    /** `key=value` — the value ends at whitespace or a JSON delimiter; quotes make it one token. */
    private val assignment: Pattern =
        Pattern.compile("($identifier)[ \\t]*=[ \\t]*(?:\"[^\"]*\"|[^\\s\"\\x27\\x2C;)\\]}]+)")

    /** `"key": "value"` — the JSON form, double-quoted on both sides. */
    private val jsonPair: Pattern =
        Pattern.compile("(\"(?:[A-Za-z0-9]+_)*(?:$sensitiveAlternation)(?:_[A-Za-z0-9]+)*\"[ \\t]*:[ \\t]*)\"[^\"]*\"")

    // The logback %replace option strings are the SAME two patterns as strings: no single
    // quotes, no commas (logback splits options on commas), no `${` (Spring placeholder
    // resolution) — asserted by ObservationRedactionPatternsTest over every key.
    private const val ASSIGNMENT_REPLACEMENT = "${'$'}1=$MASK"
    private const val JSON_PAIR_REPLACEMENT = "${'$'}1\"$MASK\""

    /** `true` when [key] — an MDC entry, a key-value pair, a member name — must never carry a value. */
    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.trim().lowercase()
        if (normalized.isEmpty() || normalized in NEVER_REDACTED) return false
        val lastSegment = normalized.substringAfterLast('.')
        if (lastSegment in NEVER_REDACTED) return false
        return SENSITIVE_KEYS.any { sensitive ->
            lastSegment == sensitive ||
                lastSegment.startsWith("${sensitive}_") ||
                lastSegment.endsWith("_$sensitive")
        }
    }

    /** [text] with every `key=value` and `"key": "value"` occurrence of the list rewritten to `***`. */
    fun scrubText(text: String): String {
        if (!text.contains('=') && !text.contains(':')) return text
        val afterAssignment = assignment.matcher(text).replaceAll(ASSIGNMENT_REPLACEMENT)
        return jsonPair.matcher(afterAssignment).replaceAll(JSON_PAIR_REPLACEMENT)
    }

    /** The `key=value` pattern as a literal for a logback `%replace` option (the console pattern). */
    fun messageReplacePattern(): String = assignment.pattern()

    /** The `key=value` replacement for a logback `%replace` option — group 1 is the key. */
    fun messageReplaceReplacement(): String = ASSIGNMENT_REPLACEMENT

    /** The `"key": "value"` pattern as a literal for a logback `%replace` option. */
    fun exceptionReplacePattern(): String = jsonPair.pattern()

    /** The `"key": "value"` replacement for a logback `%replace` option. */
    fun exceptionReplaceReplacement(): String = JSON_PAIR_REPLACEMENT

    /** True when either pattern would break a logback `%replace(p){'regex','repl'}` option string. */
    fun patternsSafeForReplaceOptions(): Boolean =
        listOf(
            messageReplacePattern(),
            messageReplaceReplacement(),
            exceptionReplacePattern(),
            exceptionReplaceReplacement(),
        ).none { it.contains(',') || it.contains('\'') || it.contains("\${") }
}
