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
 * ## The credential ([credentialKind], [username], [secret])
 *
 * [credentialKind] says WHAT the stored credential is (§3.4): a password, a bearer/PAT token, a
 * PEM private key, a service-account JSON blob, or nothing at all. It decides which of the other
 * two fields may be present ([CredentialKind.usernameRule] / [CredentialKind.secretRule]) and
 * where the adapter puts the secret at pool build ([DialectAdapter.applyCredential]) — the caller
 * never names a driver slot.
 *
 * [secret] is the **plaintext** credential and is present only transiently:
 * - on create/update it carries the operator-supplied secret to the encryptor and the
 *   save-time test pool build (§5.4);
 * - when a row is loaded for a **pool build** it is populated by decrypting
 *   `credential_encrypted` (§7.4);
 * - it is `null` everywhere else — a datasource read for listing or a `GET` response never
 *   holds it, and it is **never** serialized into a DTO, endpoint body, or log (§2 principle
 *   2, observability.md §9.2). The wire response substitutes `password_set: true|false`.
 *
 * It is `null` PERMANENTLY for [CredentialKind.NONE]: there is no credential, which is a
 * different fact from "we did not load it" and is why the column is nullable (V13).
 */
data class Datasource(
    val name: String,
    val displayName: String,
    val description: String? = null,
    val dialect: Dialect,
    val jdbcUrl: String,
    /**
     * The login name, when the credential kind has one (§3.4): REQUIRED for
     * [CredentialKind.PASSWORD], optional for [CredentialKind.TOKEN], and **null** for every
     * other kind — a private key, a service-account blob and "no credential" have no username,
     * and storing a placeholder there is the lie V13 exists to end.
     */
    val username: String? = null,
    /** §3.4: what [secret] IS. Defaults to the legacy `username`/`password` pair's meaning. */
    val credentialKind: CredentialKind = CredentialKind.DEFAULT,
    /** The plaintext credential — transient, kind-agnostic; see the class KDoc. */
    val secret: String? = null,
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
     * The V23 `owner_workspace_id` column (metadata-db §4.10; RBAC design §4): the workspace
     * that OWNS this datasource — set when a workspace admin registered it, so it is granted
     * to that workspace and they cannot grant it elsewhere; **null = an instance datasource**,
     * registered by a super admin.
     *
     * Ownership is not visibility. Visibility is the GRANT (`datasource_workspaces`), and
     * "global" is gone (D-R7): a datasource that used to be visible everywhere by virtue of a
     * NULL binding now holds an explicit grant row per workspace, which the V23 backfill
     * created for every workspace that existed. Null here no longer means "everyone can see
     * it" — it means "no single workspace owns it". The NAME namespace stays global and flat.
     */
    val ownerWorkspaceId: UUID? = null,
    /**
     * The owning workspace's NAME (joined at read time), surfaced as the additive `workspace`
     * payload field — null exactly when [ownerWorkspaceId] is null. Derived, never stored.
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
            "credential_kind=${credentialKind.wire}, secret_present=${secret != null}, " +
            "queryTimeoutSeconds=$queryTimeoutSeconds, isReadonly=$isReadonly, " +
            "workspace=${workspaceName ?: "global"})"

    /**
     * The §3.2 `password_set` flag, derived from the KIND rather than from the transient
     * [secret] — which is null on every read path and would report `false` for every
     * datasource in existence.
     *
     * It is derivable because V13's CHECK makes it so: `credential_kind = 'none'` iff
     * `credential_encrypted IS NULL`. So "this datasource has a stored credential" and "its
     * kind is not none" are the same statement, enforced by the database rather than by a
     * read-side flag this entity would have to carry and every constructor would have to get
     * right.
     */
    val credentialSet: Boolean get() = credentialKind != CredentialKind.NONE
}

/**
 * The three namespaced maps under `properties` (datasources.md §5, §12.1).
 *
 * [hikari] and [jdbc] are PASSTHROUGH: there is no allowlist of supported keys — [hikari] is
 * applied verbatim to `HikariConfig` and [jdbc] is passed to the driver via
 * `addDataSourceProperty`, and correctness is enforced by the save-time test pool build (§5.4),
 * not by enumeration here.
 *
 * [dialect] (087) is the opposite by design: TYPED per-dialect configuration, validated by the
 * adapter (`DialectAdapter.validateDialectProperties`) and refused key by key when unrecognized.
 * It exists because the connectors on the roadmap need settings that are neither a pool knob nor
 * a driver property — Snowflake's `warehouse` and `role`, Databricks's `http_path` and `catalog`,
 * a lake's catalog kind, region and endpoint. Those become connect-time SQL or driver arguments
 * the ADAPTER assembles; letting them arrive as untyped passthrough would put engine
 * configuration on the same footing as a Hikari timeout, and a typo would silently do nothing.
 * The default implementation refuses the whole namespace, so a dialect gains `dialect.*` keys by
 * declaring them, never by omission.
 *
 * [unknownNamespaces] preserves any top-level key of the incoming `properties` object that is
 * none of the three. It exists so validation can reject it
 * (`datasource.validation.properties_invalid`, §9) instead of silently dropping it — a typed
 * model with three fields alone would lose the evidence at parse time.
 */
data class DatasourceProperties(
    val hikari: Map<String, Any?> = emptyMap(),
    val jdbc: Map<String, Any?> = emptyMap(),
    val dialect: Map<String, Any?> = emptyMap(),
    val unknownNamespaces: Set<String> = emptySet(),
) {
    companion object {
        /** The reserved namespaces (§3.1, §12.1): two passthrough, one typed. */
        val RESERVED_NAMESPACES = setOf("hikari", "jdbc", "dialect")

        /**
         * Splits a raw deserialized `properties` object (e.g. from the request JSON or from
         * `properties_json`) into the two known namespaces plus [unknownNamespaces].
         *
         * A `hikari`, `jdbc` or `dialect` value that is not itself a map is treated as an
         * unknown namespace: it cannot be applied as a property map, so validation must reject
         * it rather than the code guessing.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromRaw(raw: Map<String, Any?>): DatasourceProperties {
            val hikari = (raw["hikari"] as? Map<String, Any?>).orEmpty()
            val jdbc = (raw["jdbc"] as? Map<String, Any?>).orEmpty()
            val dialect = (raw["dialect"] as? Map<String, Any?>).orEmpty()
            val unknown =
                raw.entries
                    .filter { (key, value) -> key !in RESERVED_NAMESPACES || value !is Map<*, *> }
                    .map { it.key }
                    .toSet()
            return DatasourceProperties(hikari = hikari, jdbc = jdbc, dialect = dialect, unknownNamespaces = unknown)
        }
    }
}
