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
     * A sensitive key with its optional underscore suffix: `password`, `password_hash`,
     * `api_key_v2`. Case-insensitive; `passwordless` and `secrets` do not match (no underscore
     * boundary). There is deliberately NO prefix part: the text shapes are searched unanchored, so
     * `db_password=` and `dbPassword=` are found at the key itself and the prefix is re-emitted
     * untouched — and a prefix group is what made the delivered pattern throw
     * `StackOverflowError` on a few-KB `a_a_a_…` token and take quadratic time on a long word
     * (measured; a group loop recurses per iteration and a prefix is retried from every start).
     * The suffix is one possessive character run, never a group loop. BOTH text shapes embed this
     * one string, so the JSON form cannot disagree with the assignment form about case or
     * compounds (#337-b F1).
     */
    private val identifier: String =
        "(?i:(?:$sensitiveAlternation)(?:_[A-Za-z0-9_.-]*+)?)"

    // Regex escapes for characters a logback %replace option string cannot carry raw: `\x5C` is a
    // backslash (a doubled backslash is an escape in the option tokenizer), `\x27` a single quote,
    // `\x2C` a comma, `\x5B`/`\x7B` the opening bracket and brace. No bounded repetition either:
    // `{0,64}` carries a comma, which splits the option.
    private const val BACKSLASH = "\\x5C"

    /**
     * A double-quoted value: escape pairs (`\"`, `\\`) are consumed WHOLE, so an escaped quote
     * never ends the value (#337-b F2); it stops at the first unescaped quote, which is consumed,
     * or at the end of its line — an unterminated quote masks the rest of that line and never
     * more, so one stray quote cannot wipe a stack trace. The repetition is possessive: a hostile
     * line of open quotes is one linear scan per match, never a backtracking search.
     */
    private const val DOUBLE_QUOTED = "\"(?:[^\"$BACKSLASH\\r\\n]|$BACKSLASH[^\\r\\n]?)*+\"?"

    /** The single-quoted twin of [DOUBLE_QUOTED] (`secret='two words'`). */
    private const val SINGLE_QUOTED = "\\x27(?:[^\\x27$BACKSLASH\\r\\n]|$BACKSLASH[^\\r\\n]?)*+\\x27?"

    /** A bare token: ends at whitespace or a delimiter, never starts with a quote. */
    private const val BARE_TOKEN = "[^\\s\"\\x27\\x2C;)\\]}]+"

    /**
     * `key=value` — the value is a quoted string (escapes honoured) or a bare token ending at
     * whitespace or a delimiter. The quotes are part of the masked value.
     */
    private val assignment: Pattern =
        Pattern.compile("($identifier)[ \\t]*=[ \\t]*(?:$DOUBLE_QUOTED|$SINGLE_QUOTED|$BARE_TOKEN)")

    /**
     * `"key": "value"` — the JSON form. The key is the same [identifier] (case-insensitive,
     * compounds) behind any prefix of key characters — dotted (`"spring.datasource.password"`),
     * underscored, hyphenated (`"client-secret"`) or glued (`"dbPassword"`), as the assignment form
     * already matches glued names; the value is a
     * double-quoted string with escapes honoured, or a bare scalar (number, `true`, `null`) —
     * never an object or array, whose members are matched on their own keys.
     */
    private val jsonPair: Pattern =
        Pattern.compile(
            "(\"[A-Za-z0-9_.-]*?$identifier\"[ \\t]*:[ \\t]*)" +
                "(?:$DOUBLE_QUOTED|[^\\s\"\\x27\\x2C;)\\]}\\x5B\\x7B]+)",
        )

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
        // The key bounded by underscores appears whole in `_<key>_`: that is the whole key, the
        // `<key>_*` and `*_<key>` compounds AND the prefixed-and-suffixed `db_password_v2` the
        // text matcher ([identifier]) has always masked — one rule, so a member and a message
        // cannot disagree.
        val bounded = "_${lastSegment}_"
        return SENSITIVE_KEYS.any { sensitive -> bounded.contains("_${sensitive}_") }
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
