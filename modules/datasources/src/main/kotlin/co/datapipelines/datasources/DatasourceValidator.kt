package co.datapipelines.datasources

import co.datapipelines.datasources.ValidationResult.ValidationError
import co.datapipelines.typesystem.Dialect
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.Properties

/**
 * Runs the datasources.md §9 validation rules on create and update, before any row is written
 * (§2 principle 7). Failures are **collected, not short-circuited** (§6.1): a save returns
 * every rule that failed so the UI renders one form pass.
 *
 * The rule that makes the passthrough model of §5 safe without an allowlist is the **test pool
 * build** (§5.4): the `hikari`/`jdbc` maps are validated by actually constructing a
 * `HikariConfig` (with `initializationFailTimeout = -1`, so no reachable database is required)
 * and letting HikariCP and the driver reject what they do not accept. This validator adds a
 * per-key probe on top so the offending `hikari` key can be **named** in the error, and an
 * explicit denylist for the server-managed and injection-surface keys HikariCP would otherwise
 * accept silently.
 *
 * `duplicate_name` (§9) is not here — it needs the database and is raised by
 * [DatasourceRepository] from the primary-key violation, the only atomic authority (the same
 * pattern `PipelineRepository` uses). `dialect_invalid` is enforced at deserialization: a
 * [Datasource] carries a typed [Dialect], so an invalid value cannot reach this point.
 */
class DatasourceValidator(
    private val adapters: (Dialect) -> DialectAdapter = DialectAdapters::forDialect,
    private val driverAvailable: (Dialect) -> Boolean = JdbcDrivers::isAvailable,
) {
    /** @param isCreate true on create (password becomes required, §9). */
    fun validate(
        datasource: Datasource,
        isCreate: Boolean,
    ): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        validateName(datasource.name, errors)
        if (isCreate && datasource.password.isNullOrEmpty()) {
            errors += error(DatasourceErrorCodes.PASSWORD_MISSING, "password", "A password is required on create.")
        }
        validateQueryTimeout(datasource.queryTimeoutSeconds, errors)
        validateIntrospectionIncludeSchemas(datasource, errors)
        validateJdbcUrl(datasource, errors)
        validateProperties(datasource, errors)

        return ValidationResult.of(errors)
    }

    /**
     * The adapter's own URL check, plus the §5.6 **fail-closed backstop**.
     *
     * The adapter is an injected dependency, so "the adapter validated it" is not a guarantee the
     * validator can rely on: a non-conforming implementation could wave a URL through. The second
     * pass therefore re-scans the URL against the union derived from the **dialect enum**
     * ([RefusedPropertyKeys.forDialect]), which no adapter can shrink. It is skipped when the
     * adapter already rejected the URL, so one bad key still yields exactly one error.
     */
    private fun validateJdbcUrl(
        datasource: Datasource,
        errors: MutableList<ValidationError>,
    ) {
        val adapter = adapters(datasource.dialect)
        val adapterErrors = adapter.validateJdbcUrl(datasource.jdbcUrl).errors
        errors += adapterErrors
        if (adapterErrors.isNotEmpty()) return
        JdbcUrlGuard
            .refusalErrors(datasource.jdbcUrl, RefusedPropertyKeys.forDialect(datasource.dialect, adapter))
            ?.let { errors += it }
    }

    /**
     * The §11.1 immutability guard for `PUT /datasources/{name}`: a body whose `name` differs
     * from the path segment is rejected with `name_invalid`, because renaming the primary key
     * would silently break every pipeline pointing at the old value. A REST-layer check, kept
     * here so it is unit-testable without a controller.
     */
    fun validateNameMatchesPath(
        pathName: String,
        body: Datasource,
    ): ValidationResult =
        if (pathName == body.name) {
            ValidationResult.ok()
        } else {
            ValidationResult.of(
                listOf(
                    error(
                        DatasourceErrorCodes.NAME_INVALID,
                        "name",
                        "name is immutable: the request body name '${body.name.truncateForError()}' " +
                            "must equal the path '${pathName.truncateForError()}'. Rename is delete + create (§11.1).",
                    ),
                ),
            )
        }

    private fun validateName(
        name: String,
        errors: MutableList<ValidationError>,
    ) {
        if (!NAME_PATTERN.matches(name) || name.length !in 1..MAX_NAME_LENGTH) {
            errors +=
                error(
                    DatasourceErrorCodes.NAME_INVALID,
                    "name",
                    "name '${name.truncateForError()}' must match [a-z0-9_-]+, length 1-$MAX_NAME_LENGTH.",
                )
        }
    }

    private fun validateQueryTimeout(
        seconds: Int?,
        errors: MutableList<ValidationError>,
    ) {
        if (seconds != null && seconds < 1) {
            errors +=
                error(
                    DatasourceErrorCodes.QUERY_TIMEOUT_INVALID,
                    "query_timeout_seconds",
                    "query_timeout_seconds, when set, must be an integer >= 1.",
                )
        }
    }

    /**
     * §3.3/§7A: `introspection_include_schemas` entries are exact schema names — non-blank,
     * no wildcard patterns. The prefix language (`apex_*`) belongs to the exclusion floors
     * alone; an allowlist pattern would look like it exempts a family while exempting nothing,
     * so it is rejected here rather than silently ignored. Lowercase normalization happens at
     * the registration bind, before this rule runs.
     */
    private fun validateIntrospectionIncludeSchemas(
        datasource: Datasource,
        errors: MutableList<ValidationError>,
    ) {
        datasource.introspectionIncludeSchemas.forEach { entry ->
            if (entry.isBlank() || entry.contains('*')) {
                errors +=
                    propertiesError(
                        "introspection_include_schemas",
                        "introspection_include_schemas entries must be non-blank schema names without wildcard " +
                            "patterns; '${entry.truncateForError()}' is not.",
                    )
            }
        }
    }

    private fun validateProperties(
        datasource: Datasource,
        errors: MutableList<ValidationError>,
    ) {
        val props = datasource.properties
        // Per-key/structural property errors are collected separately so they can gate the full
        // test pool build: if a key is already known-bad, building the pool would only re-report
        // the same failure (buildHikariConfig throws on the same key), so the build runs only
        // when the individual checks passed — keeping the error list free of duplicates while
        // still catching the cross-field rules only HikariConfig.validate() knows.
        val propertyErrors = mutableListOf<ValidationError>()

        props.unknownNamespaces.forEach { ns ->
            propertyErrors +=
                propertiesError(
                    "properties.${ns.truncateForError()}",
                    "'${ns.truncateForError()}' is not a recognized properties namespace; only 'hikari' and 'jdbc' are allowed.",
                )
        }
        // Server-managed keys under either namespace are derived from the entity/adapter and
        // must never be supplied (§5). Under jdbc they are also an injection vector.
        serverManagedIn("hikari", props.hikari.keys, propertyErrors)
        serverManagedIn("jdbc", props.jdbc.keys, propertyErrors)
        // jdbc values are a flat string map; a nested map / list is not a driver property.
        props.jdbc.forEach { (key, value) ->
            if (value is Map<*, *> || value is Collection<*>) {
                propertyErrors +=
                    propertiesError(
                        "properties.jdbc.${key.truncateForError()}",
                        "jdbc property '${key.truncateForError()}' must be a scalar value, not a nested object.",
                    )
            }
        }
        // §5.6: properties.jdbc is validated against the SAME refusal union as the JDBC URL —
        // refusal sets, server-managed keys, credentials, AND the secret-valued suffix predicate.
        // Resolved from the dialect enum, so a non-conforming adapter cannot shrink it (fail
        // closed). Server-managed keys were already reported above with their own message;
        // excluded here to avoid a duplicate.
        //
        // Membership goes through RefusedPropertyKeys.isRefused, never a bare `in refused`: the
        // suffix predicate is unbounded over key names and so cannot be expressed as a set, and a
        // bare set test here is exactly how this carrier fell out of step with the URL guard —
        // `fooClientKey` was refused in the URL and accepted under properties.jdbc.
        val refused = RefusedPropertyKeys.forDialect(datasource.dialect, adapters(datasource.dialect))
        props.jdbc.keys
            .filter { RefusedPropertyKeys.isRefused(it, refused) && it.lowercase() !in RefusedPropertyKeys.SERVER_MANAGED }
            .forEach { key ->
                propertyErrors +=
                    propertiesError(
                        "properties.jdbc.${key.truncateForError()}",
                        "jdbc property '${key.truncateForError()}' is not permitted — it is a credential, or a " +
                            "code-execution / local-file / TLS-verification surface (§5.6).",
                    )
            }
        // Name each hikari key HikariCP rejects (unknown name / un-parseable value).
        props.hikari
            .filterKeys { it.lowercase() !in RefusedPropertyKeys.SERVER_MANAGED }
            .forEach { (key, value) -> probeHikariKey(key, value, propertyErrors) }

        errors += propertyErrors

        // Driver presence gates the full test pool build (§10.3: checked before the build so a
        // missing driver reads as driver_not_loaded, not a confusing properties_invalid).
        if (!driverAvailable(datasource.dialect)) {
            errors +=
                error(
                    DatasourceErrorCodes.DRIVER_NOT_LOADED,
                    "dialect",
                    "The JDBC driver for dialect ${datasource.dialect.wire} is not on the classpath.",
                )
            return
        }
        if (propertyErrors.isEmpty()) testPoolBuild(datasource, errors)
    }

    private fun serverManagedIn(
        namespace: String,
        keys: Set<String>,
        errors: MutableList<ValidationError>,
    ) {
        keys.filter { it.lowercase() in RefusedPropertyKeys.SERVER_MANAGED }.forEach { key ->
            errors +=
                propertiesError(
                    "properties.$namespace.${key.truncateForError()}",
                    "'${key.truncateForError()}' is server-managed and cannot be set under properties.$namespace.",
                )
        }
    }

    /**
     * Applies one hikari key in isolation so the failing key can be named (not just "invalid").
     *
     * `TooGenericExceptionCaught` is suppressed deliberately: HikariCP signals an unknown
     * property name and an un-parseable value both as bare `RuntimeException`s
     * (property-not-found, `NumberFormatException`, …), so catching the family IS the save-time
     * rejection signal (§5.4). The underlying message is surfaced in the error, not swallowed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun probeHikariKey(
        key: String,
        value: Any?,
        errors: MutableList<ValidationError>,
    ) {
        try {
            HikariConfig(Properties().apply { setProperty(key, value?.toString() ?: "") })
        } catch (e: RuntimeException) {
            errors +=
                propertiesError(
                    "properties.hikari.${key.truncateForError()}",
                    "hikari property '${key.truncateForError()}' was rejected: " +
                        (e.message?.scrubbedForError() ?: "invalid"),
                )
        }
    }

    /**
     * Constructs the full pool with `initializationFailTimeout = -1` (§5.4 step 4): validates
     * cross-field `HikariConfig` rules and confirms a *constructible* pool without requiring a
     * reachable database. Closed immediately; nothing retained.
     *
     * `TooGenericExceptionCaught` is suppressed deliberately: `HikariConfig.validate()` and pool
     * construction throw bare `RuntimeException`s for every cross-field property defect, so
     * catching the family is the §5.4 constructibility gate. The underlying message is surfaced
     * in the error — scrubbed of credentials first (§6.1) — not swallowed.
     *
     * A `PUT` that omits `password` legitimately reaches here with `password = null`, which
     * [AbstractDialectAdapter.buildHikariConfig] refuses (DS-SEC-10). The credential is never used
     * — `initializationFailTimeout = -1` means no connection is attempted — so an explicit,
     * obviously-synthetic [VALIDATION_ONLY_PASSWORD] is substituted rather than weakening the
     * pool-build contract with a silent empty string.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun testPoolBuild(
        datasource: Datasource,
        errors: MutableList<ValidationError>,
    ) {
        val forBuild = if (datasource.password == null) datasource.copy(password = VALIDATION_ONLY_PASSWORD) else datasource
        try {
            val config = adapters(datasource.dialect).buildHikariConfig(forBuild)
            config.initializationFailTimeout = TEST_POOL_NO_CONNECT
            HikariDataSource(config).close()
        } catch (e: RuntimeException) {
            errors +=
                propertiesError(
                    "properties",
                    "the connection pool could not be built: " +
                        (e.message?.scrubbedForError(datasource.password) ?: "invalid properties"),
                )
        }
    }

    private fun error(
        code: String,
        field: String?,
        message: String,
    ) = ValidationError(code, field, message)

    private fun propertiesError(
        field: String,
        message: String,
    ) = ValidationError(DatasourceErrorCodes.PROPERTIES_INVALID, field, message)

    companion object {
        const val MAX_NAME_LENGTH = 63
        private val NAME_PATTERN = Regex("^[a-z0-9_-]+$")

        /** §5.4: negative initializationFailTimeout → pool constructs without connecting. */
        private const val TEST_POOL_NO_CONNECT = -1L

        /**
         * Stand-in credential for the save-time pool build of a `PUT` that omits `password`. Never
         * reaches a socket (§5.4 builds with `initializationFailTimeout = -1`) and never leaves
         * this class; spelled so that any appearance in a log is unmistakably not a real secret.
         */
        const val VALIDATION_ONLY_PASSWORD = "<validation-only-no-credential-supplied>"

        /**
         * Server-managed HikariCP keys (lowercased) — refused under both namespaces (§5, §5.6).
         *
         * Retained as the module's published name for the set; the single definition lives in
         * [RefusedPropertyKeys.SERVER_MANAGED], which the JDBC-URL guard shares.
         */
        val SERVER_MANAGED_KEYS: Set<String> get() = RefusedPropertyKeys.SERVER_MANAGED
    }
}
