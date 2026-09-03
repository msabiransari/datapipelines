package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import java.util.UUID

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
     * The V4 `is_readonly` column (metadata-db §4.10; workspaces design §6, D6): forbids the
     * three write-shaped uses of this datasource in the pipeline contract — a `DML`/`DDL`
     * node's `source`, and any node's `output.target: "datasource"`. DQL reads and everything
     * `tempdb` are untouched.
     *
     * Writable through the REST surface's D8 gates (workspaces design §6 last paragraph):
     * whoever may edit the datasource may flip it, except that only `admin` may flip it on a
     * GLOBAL datasource. Every write crosses the registry's save boundary, which evicts the
     * pool — a flip takes effect at the next pool build (§5.2).
     *
     * Semantics, not containment: JDBC read-only enforcement varies by driver, so a
     * datasource whose data must not change still gets a SELECT-only DB user regardless
     * (datasources.md §5.7).
     */
    val isReadonly: Boolean = false,
    /**
     * The V4 `workspace_id` column (metadata-db §4.10; workspaces design §3/D8): the
     * workspace this datasource is bound to, or **null = global** (visible to every
     * workspace — D9's backfill choice, preserving pre-workspaces shared behavior).
     * Binding is visibility/ownership only; the NAME namespace stays global and flat.
     */
    val workspaceId: UUID? = null,
    /**
     * The bound workspace's NAME (joined at read time), surfaced as the additive `workspace`
     * payload field (workspaces design §9) — null exactly when [workspaceId] is null, i.e.
     * global. Derived, never stored on `datasources`.
     */
    val workspaceName: String? = null,
    /**
     * §7A introspection include-schemas allowlist (§3.3): schema names exempt from the
     * dialect's system-schema exclusion in ALL THREE introspection operations. The escape
     * hatch for the exclusion floors' one known blind spot — a prefix entry like Oracle's
     * `apex_*` hides a customer's own `APEX_REPORTING` schema just like the engine's versioned
     * ones, with no warning; naming it here makes it visible again.
     *
     * **Lowercase, exact names, over the legal-identifier alphabet of the supported
     * dialects** (letters, digits, `_`, `$`, `#` — anything else is rejected at save; an
     * entry outside the alphabet can only ever look like it exempts something while
     * exempting nothing); normalization — trim, lowercase, drop blanks, dedupe — happens at
     * the registry's save boundary — the single place every write path crosses — and again
     * at the repository's read boundary, so a row whose allowlist landed by restore or a
     * manual JSONB edit cannot sit silently inert. Absent/empty = today's behavior: the
     * dialect floor applies unchanged. Matching is case-insensitive, like the exclusion
     * itself.
     */
    val introspectionIncludeSchemas: List<String> = emptyList(),
    /**
     * The outcome of the LAST connection test against this datasource (V9 `last_test_at` /
     * `last_test_ok` / `last_test_message`; datasources.md §8.1B, 061/T84) — null when the
     * datasource has never been probed.
     *
     * **An observation, not configuration.** It is the one field of this entity nobody
     * supplies: it is written by [DatasourceRegistry.testConnection] and by the §8A.3 rule-3
     * bootstrap credential probe, and that write deliberately leaves `updated_at` and every
     * other column alone. It exists because LISTING a datasource does not connect to it — on
     * 2026-09-02 the screen said `sample-trips` was fine while every execution failed at
     * CONNECT with `password authentication failed`, and no surface disagreed.
     *
     * [DatasourceTestOutcome.message] is redaction-scrubbed at the probe (never a password,
     * never a credential-bearing URL), so it is safe on the wire and on the screen.
     */
    val lastTest: DatasourceTestOutcome? = null,
) {
    companion object {
        /**
         * The ONE normalization rule of the §7A include-schemas allowlist: entries are
         * trimmed, lowercased, **blank-after-trim entries are dropped**, and duplicates
         * collapse to the first occurrence (order preserved). Matching lowercases the
         * driver-reported schema before comparing against stored entries verbatim, so only
         * the normalized form is live — a blank or duplicated entry can match nothing and
         * exists only to poison the GET→PUT round-trip (the validator rejects blanks), so
         * normalization never keeps one — applied at [DatasourceRegistry]'s save boundary
         * (every programmatic write crosses it: REST create/update today, any future MCP
         * create tool tomorrow) AND at the repository's row-read (restore and manual JSONB
         * edits write rows without crossing save; an unnormalized entry there would silently
         * exempt nothing — inert, not rejected).
         */
        fun normalizeIncludeSchemas(entries: List<String>): List<String> {
            // Fast path (R5 F6): this runs per row on the uncached list() read path, and the
            // overwhelming case is "no allowlist" or "already normalized" — return the input
            // as-is (no allocation) when the ONE rule would change nothing.
            if (entries.isEmpty() || entries.isAlreadyNormalized()) return entries
            return entries
                .asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()
        }

        /** [entries] already satisfies the ONE rule — non-blank, lowercase, no duplicates. */
        private fun List<String>.isAlreadyNormalized(): Boolean {
            val seen = HashSet<String>(size)
            return all { entry ->
                entry.isNotEmpty() && !entry.any { it.isWhitespace() || it.isUpperCase() } && seen.add(entry)
            }
        }
    }

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
            "password_set=${password != null}, queryTimeoutSeconds=$queryTimeoutSeconds, isReadonly=$isReadonly, " +
            "workspace=${workspaceName ?: "global"})"
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
