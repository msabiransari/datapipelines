package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect

/**
 * An environment-specific connection to an external database (datasources.md §3).
 *
 * A datasource is referenced by pipelines through its stable [name] only; the registry
 * resolves the name to these connection details per environment. The mapping to the
 * `datasources` table is metadata-db §4.10 (the sole DDL authority).
 *
 * ## The [password] field
 *
 * [password] is the **plaintext** credential and is present only transiently:
 * - on create/update it carries the operator-supplied secret to the encryptor and the
 *   save-time test pool build (§5.4);
 * - when a row is loaded for a **pool build** it is populated by decrypting
 *   `password_encrypted` (§7.4);
 * - it is `null` everywhere else — a datasource read for listing or a `GET` response never
 *   holds it, and it is **never** serialized into a DTO, endpoint body, or log (§2 principle
 *   2, observability.md §9.2). The wire response substitutes `password_set: true|false`.
 */
data class Datasource(
    val name: String,
    val displayName: String,
    val description: String? = null,
    val dialect: Dialect,
    val jdbcUrl: String,
    val username: String,
    val password: String? = null,
    val queryTimeoutSeconds: Int? = null,
    val properties: DatasourceProperties = DatasourceProperties(),
    /**
     * §7A introspection include-schemas allowlist (§3.3): schema names exempt from the
     * dialect's system-schema exclusion in ALL THREE introspection operations. The escape
     * hatch for the exclusion floors' one known blind spot — a prefix entry like Oracle's
     * `apex_*` hides a customer's own `APEX_REPORTING` schema just like the engine's versioned
     * ones, with no warning; naming it here makes it visible again.
     *
     * **Lowercase, exact names, no patterns** (an entry carrying `*` is rejected at save — a
     * pattern here would look like it exempts a family while exempting nothing); normalization
     * to lowercase happens at the registration bind. Absent/empty = today's behavior: the
     * dialect floor applies unchanged. Matching is case-insensitive, like the exclusion itself.
     */
    val introspectionIncludeSchemas: List<String> = emptyList(),
) {
    /**
     * Overridden because the generated `data class` [toString] prints **every** property,
     * including the plaintext [password] — and a datasource lands in exception messages, debug
     * logs and IDE watches by accident far more often than by design (§2 principle 2,
     * observability.md §9.2). `password_set` mirrors the §3.2 response shape.
     *
     * `jdbc_url` is included: §3.2 returns it to `read`-scope principals, and §5.6 guarantees it
     * carries no credential.
     */
    override fun toString(): String =
        "Datasource(name=$name, dialect=${dialect.wire}, jdbcUrl=$jdbcUrl, username=$username, " +
            "password_set=${password != null}, queryTimeoutSeconds=$queryTimeoutSeconds)"
}
// The `password_set` field of a GET response is derived at the web layer from row existence:
// every persisted datasource has a NOT NULL `password_encrypted` (metadata-db §4.10), so a
// datasource the registry returns always has a credential set. This entity never exposes a
// read-side `passwordSet` flag off the transient (null-on-read) `password` field.

/**
 * The two namespaced passthrough maps under `properties` (datasources.md §5).
 *
 * There is no allowlist of supported keys: [hikari] is applied verbatim to `HikariConfig`
 * and [jdbc] is passed to the driver via `addDataSourceProperty`. Correctness is enforced by
 * the save-time test pool build (§5.4), not by enumeration here.
 *
 * [unknownNamespaces] preserves any top-level key of the incoming `properties` object that is
 * neither `hikari` nor `jdbc`. It exists so validation can reject it
 * (`datasource.validation.properties_invalid`, §9) instead of silently dropping it — a typed
 * model with two fields alone would lose the evidence at parse time.
 */
data class DatasourceProperties(
    val hikari: Map<String, Any?> = emptyMap(),
    val jdbc: Map<String, Any?> = emptyMap(),
    val unknownNamespaces: Set<String> = emptySet(),
) {
    companion object {
        /** The only two reserved namespaces (§3.1). */
        val RESERVED_NAMESPACES = setOf("hikari", "jdbc")

        /**
         * Splits a raw deserialized `properties` object (e.g. from the request JSON or from
         * `properties_json`) into the two known namespaces plus [unknownNamespaces].
         *
         * A `hikari` or `jdbc` value that is not itself a map is treated as an unknown
         * namespace: it cannot be applied as a property map, so validation must reject it
         * rather than the code guessing.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromRaw(raw: Map<String, Any?>): DatasourceProperties {
            val hikari = (raw["hikari"] as? Map<String, Any?>).orEmpty()
            val jdbc = (raw["jdbc"] as? Map<String, Any?>).orEmpty()
            val unknown =
                raw.entries
                    .filter { (key, value) -> key !in RESERVED_NAMESPACES || value !is Map<*, *> }
                    .map { it.key }
                    .toSet()
            return DatasourceProperties(hikari = hikari, jdbc = jdbc, unknownNamespaces = unknown)
        }
    }
}
