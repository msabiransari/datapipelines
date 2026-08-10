package co.datapipelines.datasources

/**
 * Shared JDBC-URL parsing and the §5.6 refusal guard every [DialectAdapter] runs.
 *
 * A JDBC URL is `jdbc:<sub-protocol>:<sub-name>`, and the sub-name commonly carries driver
 * properties — after `?` and `&` for most drivers, after `;` for SQL Server, H2 and DuckDB.
 * Several of those properties are a remote-code / local-file surface:
 *
 *  - **H2** `INIT` runs arbitrary SQL (`RUNSCRIPT FROM '...'`) at connect.
 *  - **PostgreSQL** `socketFactory` / `sslfactory` load an attacker-named class.
 *  - **MySQL** `allowLoadLocalInfile` / `allowUrlInLocalInfile` / `autoDeserialize` turn a
 *    malicious server into file read / gadget deserialization.
 *  - **DuckDB** `session_init_sql_file` fetches a file and runs its SQL on every connection.
 *
 * The guard tokenizes the URL on `?`, `&`, and `;`, reads the key before each `=`, and refuses
 * the whole URL if any key is in the refusal union ([RefusedPropertyKeys.forDialect]) — the very
 * same union [DatasourceValidator] applies to `properties.jdbc.*`, which is how §5.6's "refusal
 * applies to both carriers identically" is enforced by construction rather than by two lists
 * that happen to agree. Matching is case-insensitive, because driver property matching is, and
 * `Socketfactory` must not slip past.
 *
 * Credentials get a second, structural check: §5.6 refuses them in the URL outright (the URL is
 * stored plaintext and returned to `read`-scope principals, §3.2), so a `//user:pw@host`
 * authority is rejected as well as a `user=` / `password=` property.
 */
internal object JdbcUrlGuard {
    private const val JDBC_PREFIX = "jdbc:"

    /** What introduces an authority in every network JDBC URL form this guard sees. */
    private const val AUTHORITY_MARKER = "//"
    private val TOKEN_SEPARATORS = Regex("[?;&]")

    /**
     * Checks scheme, basic shape, and the refusal guard for [url] under [dialect]'s sub-protocol.
     *
     * @param subProtocol the expected `jdbc:<subProtocol>:` for the dialect (e.g. `postgresql`).
     * @param refusedKeys the §5.6 refusal union for the dialect (lowercased).
     */
    fun validate(
        url: String,
        subProtocol: String,
        refusedKeys: Set<String>,
    ): ValidationResult {
        val trimmed = url.trim()
        val schemePrefix = "$JDBC_PREFIX$subProtocol:"
        if (!trimmed.startsWith(schemePrefix, ignoreCase = true)) {
            return schemeError("URL must begin with '$schemePrefix' for this dialect")
        }
        val subName = trimmed.substring(schemePrefix.length)
        if (subName.isBlank()) {
            return malformedError("'$schemePrefix' is present but the connection sub-name is empty")
        }
        return refusalErrors(subName, refusedKeys)?.let { ValidationResult.of(listOf(it)) } ?: ValidationResult.ok()
    }

    /**
     * The §5.6 refusal scan alone, without the scheme check — the fail-closed backstop
     * [DatasourceValidator] runs against the **dialect-derived** union, so a non-conforming
     * [DialectAdapter] implementation cannot exempt a URL its own `validateJdbcUrl` waved through.
     *
     * @return the single error, or null when the URL carries nothing refused.
     */
    fun refusalErrors(
        urlOrSubName: String,
        refusedKeys: Set<String>,
    ): ValidationResult.ValidationError? {
        val subName = subNameOf(urlOrSubName)
        if (hasCredentialAuthority(subName)) {
            return malformedErrorValue(
                "the URL authority carries credentials — supply them through the 'username' and " +
                    "'password' fields instead; jdbc_url is stored in plaintext",
            )
        }
        val offending = propertyKeys(subName).firstOrNull { RefusedPropertyKeys.isRefused(it, refusedKeys) } ?: return null
        return malformedErrorValue(
            "URL property '${offending.truncateForError()}' is not permitted — it is a credential, a " +
                "server-managed value, or a code-execution / local-file surface; set connection behavior " +
                "via properties.jdbc instead",
        )
    }

    /** Lowercased property keys appearing as `key=` after any `?`, `&`, or `;` separator. */
    fun propertyKeys(subName: String): Set<String> =
        subName
            .split(TOKEN_SEPARATORS)
            .mapNotNull { token ->
                val eq = token.indexOf('=')
                if (eq <= 0) null else token.substring(0, eq).trim().lowercase()
            }.toSet()

    /**
     * The `<sub-name>` of `jdbc:<sub-protocol>:<sub-name>`, or [url] unchanged when it is already
     * a sub-name (the adapter path has parsed the scheme off before calling in).
     */
    fun subNameOf(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith(JDBC_PREFIX, ignoreCase = true)) return trimmed
        val rest = trimmed.substring(JDBC_PREFIX.length)
        val colon = rest.indexOf(':')
        return if (colon < 0) rest else rest.substring(colon + 1)
    }

    /**
     * Whether the sub-name embeds a `user:password@` / `user/password@` **userinfo authority in
     * any position** (§5.6, v1.6 — DS-SEC-13).
     *
     * Keying off a leading `//` was not enough: the credential-bearing forms differ per driver and
     * two of them put the scheme *before* the authority, so a `//`-anchored check let a plaintext
     * credential into a column that is stored unencrypted and returned to `read` scope (§3.2).
     *
     * | Form | Sub-name seen here | Verdict |
     * |---|---|---|
     * | PG / MySQL | `//admin:pw@host/db` | refused |
     * | H2 network | `tcp://user:pw@host/db` | refused |
     * | Oracle thin | `thin:scott/tiger@//host:1521/svc` | refused |
     * | Oracle thin, no credential | `thin:@//host:1521/svc` | allowed — empty userinfo |
     * | DuckDB / SQLite file path | `/var/lib/a@b.db` | allowed — a path, not an authority |
     *
     * The separation rests on three observations, in order:
     *  1. Only the **connect part** is scanned (everything before the first `?` or `;`), so an `@`
     *     inside a property *value* — `?ApplicationName=a@b` — is not an authority.
     *  2. The userinfo is the run before `@` that follows the last `//` or, failing that, the last
     *     `:`. A sub-name with neither is a bare filesystem path, so its `@` is part of a filename.
     *  3. A real userinfo carries a `:` or `/` separating user from password and does not begin
     *     with `/`. That last clause keeps an absolute path that happens to sit after a drive-style
     *     colon (`C:/data/a@b.db`) out of the refusal.
     */
    private fun hasCredentialAuthority(subName: String): Boolean {
        val connectPart = subName.takeWhile { it != '?' && it != ';' }
        val at = connectPart.indexOf('@')
        if (at < 0) return false
        val beforeAt = connectPart.substring(0, at)
        val userinfo =
            when {
                beforeAt.contains(AUTHORITY_MARKER) -> beforeAt.substringAfterLast(AUTHORITY_MARKER)

                beforeAt.contains(':') -> beforeAt.substringAfterLast(':')

                // No scheme marker at all: a bare path whose filename contains '@'.
                else -> return false
            }
        if (userinfo.isEmpty() || userinfo.startsWith('/')) return false
        return userinfo.any { it == ':' || it == '/' }
    }

    private fun schemeError(detail: String) =
        ValidationResult.of(
            listOf(
                ValidationResult.ValidationError(
                    code = DatasourceErrorCodes.JDBC_URL_SCHEME_INVALID,
                    field = "jdbc_url",
                    message = "Invalid JDBC URL scheme: $detail.",
                ),
            ),
        )

    private fun malformedError(detail: String) = ValidationResult.of(listOf(malformedErrorValue(detail)))

    private fun malformedErrorValue(detail: String) =
        ValidationResult.ValidationError(
            code = DatasourceErrorCodes.JDBC_URL_MALFORMED,
            field = "jdbc_url",
            message = "Malformed JDBC URL: $detail.",
        )
}
