package co.datapipelines.datasources

/**
 * Truncates a reflected inbound value before it enters an error `message`, `field`, or
 * `details` map.
 *
 * The same rule `pipeline-contract` applies (`truncateForError`): a hostile or accidental
 * megabyte of property key must not become a megabyte of log line or error body. `datasources`
 * cannot import that helper across the layering boundary (§4.2), so it carries its own copy —
 * bounded at [MAX_REFLECTED_CHARS] with an ellipsis marker so a reader can tell truncation
 * happened.
 */
internal fun String.truncateForError(): String = if (length <= MAX_REFLECTED_CHARS) this else take(MAX_REFLECTED_CHARS) + "…"

/** 64 chars: long enough to identify a key or scheme, short enough to bound a log line. */
internal const val MAX_REFLECTED_CHARS = 64

/**
 * 512 chars for **server-produced** text (a HikariCP or driver message). These are not attacker
 * length-controlled the way a reflected inbound key is, and 64 chars truncates the one sentence
 * that tells an operator what actually went wrong. Always applied *after* [scrubSecrets].
 */
internal const val MAX_SERVER_MESSAGE_CHARS = 512

/** What every redacted span collapses to, so a reader can see that scrubbing happened. */
private const val REDACTED = "***"

/**
 * `key=value` for any **secret-valued** key, up to the next JDBC-URL separator. Case-insensitive.
 *
 * The key half is a *suffix* match with an optional prefix, deliberately mirroring
 * [RefusedPropertyKeys.SECRET_VALUED_SUFFIXES] — a `\b`-anchored alternation (the pre-fix form)
 * only matched a key that *began* at a word boundary, so the compound keys drivers actually emit
 * (`sslpassword=`, `trustStorePassword=`, `clientKeyPassword=`, `keyVaultProviderClientKey=`)
 * had a word character before the alternation and were left in the reflected message (DS-SEC-17).
 *
 * `[0-9]*` after the suffix keeps Connector/J's multi-factor slots (`password1`…`password3`), and
 * `[A-Za-z0-9_.]*?` is lazy so the replacement re-emits the key the operator actually saw.
 */
private val CREDENTIAL_ASSIGNMENT =
    Regex(
        """([A-Za-z0-9_.]*?(?:password|passwd|pwd|secret|clientkey)[0-9]*)\s*=\s*[^\s;&,)"']*""",
        RegexOption.IGNORE_CASE,
    )

/**
 * The standalone `user=` / `userName=` assignment. Kept separate from [CREDENTIAL_ASSIGNMENT]
 * because "user" cannot take an arbitrary prefix the way the secret suffixes can — folding it into
 * the prefix-tolerant pattern would redact `…?currentUser=` style diagnostics that are not
 * credentials, and over-redaction removes the sentence the operator needs (§6.1).
 */
private val USER_ASSIGNMENT =
    Regex("""\b(user|username)\s*=\s*[^\s;&,)"']*""", RegexOption.IGNORE_CASE)

/** The `//user:password@host` authority of a JDBC/URI-shaped string. */
private val CREDENTIAL_AUTHORITY = Regex("""//[^/@\s:]+:[^/@\s]*@""")

/**
 * Scrubs a message before it becomes [TestResult.error] or a
 * [ValidationResult.ValidationError.message] (datasources.md §6.1: neither ever carries a
 * password or the credential portion of a JDBC URL; observability.md §9.2).
 *
 * Four passes, all necessary:
 *  1. the literal [password] value, wherever the driver echoed it (some drivers put the
 *     credential straight into their exception text);
 *  2. a `//user:pass@host` authority;
 *  3. any secret-valued `*password=` / `*secret=` / `*clientKey=` assignment — **including the
 *     compound driver keys** (`sslpassword`, `trustStorePassword`), which is how a credential
 *     rides inside a JDBC URL;
 *  4. the standalone `user=` / `userName=` assignment.
 *
 * Deliberately *not* a "does it look like a secret" heuristic — the shapes above are the ones the
 * credential can actually take on this path, and a heuristic that redacts more would also redact
 * the sentence the operator needs.
 */
internal fun scrubSecrets(
    text: String,
    password: String? = null,
): String {
    val withoutLiteral =
        if (password.isNullOrEmpty()) text else text.replace(password, REDACTED)
    return withoutLiteral
        .replace(CREDENTIAL_AUTHORITY, "//$REDACTED:$REDACTED@")
        .replace(CREDENTIAL_ASSIGNMENT) { match -> "${match.groupValues[1]}=$REDACTED" }
        .replace(USER_ASSIGNMENT) { match -> "${match.groupValues[1]}=$REDACTED" }
}

/** [scrubSecrets] then bound at [MAX_SERVER_MESSAGE_CHARS] — the order matters, never the reverse. */
internal fun String.scrubbedForError(password: String? = null): String {
    val scrubbed = scrubSecrets(this, password)
    return if (scrubbed.length <= MAX_SERVER_MESSAGE_CHARS) scrubbed else scrubbed.take(MAX_SERVER_MESSAGE_CHARS) + "…"
}
