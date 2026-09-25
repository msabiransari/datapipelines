package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import java.time.Instant
import java.util.UUID

/**
 * The API-key table's row model (091, ui-screens.md §4.18; the `/api-keys` page's since 179) —
 * built in ONE place because three renders show the same table: the page, the post-create
 * out-of-band refresh, and the rows a revoke swaps in. Before 091 the third of those was a
 * Kotlin string builder kept "byte-for-byte the shape of" the template's fragment by a parity
 * test; there is now one fragment and one row model, so the two cannot disagree.
 *
 * Every derived value is computed HERE rather than in Thymeleaf: the relative ages need a `now`,
 * and a template that calls `#temporals` on one render and a Kotlin helper on another is exactly
 * the drift this class removes.
 */
class ApiKeyRows(
    private val bindings: EndpointKeyBindingRepository,
) {
    /**
     * One key as the table shows it. [prefix] is the public `dpk_…` handle — never the secret,
     * which exists only in the response that minted it (and, for the V37-migrated login keys,
     * in the sealed column the Keys page's copy endpoint opens once).
     *
     * [createdBy] is the CREATOR's display label — the Keys page lists the workspace's keys
     * (D17), so who created each one is a column, not an assumption. [actsAs] is the key's own
     * identity (#215, record §3.3) — the name its runs and received versions are attributed to
     * — and [role] its key role's label (`author`, `api caller`, …).
     */
    data class Row(
        val id: String,
        val name: String,
        val kind: String,
        val prefix: String,
        val createdBy: String,
        val actsAs: String,
        val role: String?,
        val boundPaths: List<String>,
        val createdRelative: String,
        val createdAbsolute: String,
        val lastUsedRelative: String,
        val lastUsedAbsolute: String?,
        val expiresRelative: String,
        val expiresAbsolute: String?,
        val isRevoked: Boolean,
        val isExpired: Boolean,
        /** The key still carries its one readable sealed copy (V31 leftovers) — the row may offer Copy. */
        val copyable: Boolean = false,
    ) {
        /** A key that can still authenticate — the only kind with a revoke affordance. */
        val isLive: Boolean get() = !isRevoked && !isExpired

        /** The UI name (keys v2 A19): `mcp` reads "MCP key", `endpoint` reads "API key". */
        val kindLabel: String
            get() =
                when (kind) {
                    ApiKeyKind.MCP.wire -> "MCP key"
                    ApiKeyKind.ENDPOINT.wire -> "API key"
                    else -> "Server key"
                }
    }

    /**
     * [keys] as rows, live ones first and newest first within each group — a revoked key stays
     * VISIBLE (its `is_revoked` is a fact an operator checks) but never at the top.
     *
     * Bindings are read once per ENDPOINT key, and only for endpoint keys: no other kind has
     * any, and a per-row query for keys that cannot have bindings is a page of empty reads.
     *
     * [userLabels] maps every user id the rows name — creators and identities — to its label.
     */
    fun of(
        keys: List<ApiKey>,
        now: Instant,
        userLabels: Map<UUID, String> = emptyMap(),
    ): List<Row> =
        keys
            .sortedWith(compareBy({ it.isRevoked }, { -it.createdAt.epochSecond }))
            .map { key -> row(key, now, userLabels) }

    private fun row(
        key: ApiKey,
        now: Instant,
        userLabels: Map<UUID, String>,
    ): Row =
        Row(
            id = key.id,
            name = key.name,
            kind = key.kind.wire,
            prefix = key.id.take(PREFIX_CHARS) + "…",
            createdBy =
                (userLabels[key.createdBy] ?: UNKNOWN_OWNER) +
                    // B6: on a revoked row the creator may be gone — the label says so rather
                    // than leaving a live-looking name on a dead credential.
                    if (key.isRevoked) " (removed)" else "",
            actsAs = userLabels[key.userId] ?: UNKNOWN_OWNER,
            role = key.role?.label,
            boundPaths =
                if (key.kind == ApiKeyKind.ENDPOINT) {
                    bindings.findByKey(key.id).map { it.pathPrefix }.sorted()
                } else {
                    emptyList()
                },
            createdRelative = RelativeTime.since(key.createdAt, now),
            createdAbsolute = RelativeTime.absolute(key.createdAt),
            lastUsedRelative = key.lastUsedAt?.let { RelativeTime.since(it, now) } ?: NEVER,
            lastUsedAbsolute = key.lastUsedAt?.let { RelativeTime.absolute(it) },
            expiresRelative = key.expiresAt?.let { RelativeTime.until(it, now) } ?: NEVER,
            expiresAbsolute = key.expiresAt?.let { RelativeTime.absolute(it) },
            isRevoked = key.isRevoked,
            // An expired key is dead exactly as a revoked one is (§7.3 step 5), and the table
            // says so rather than showing a live-looking row with a past date in it.
            isExpired = key.expiresAt?.isBefore(now) ?: false,
            copyable = key.hasSealedSecret,
        )

    companion object {
        /** `dpk_` plus eight characters — the D16 top-bar prefix length, shared by the table. */
        const val PREFIX_CHARS = 12

        /** The word both null cases render as; "—" would leave the reader guessing which. */
        const val NEVER = "never"

        /** The owner of a key whose user row is gone — shown, never crashed on. */
        const val UNKNOWN_OWNER = "(deleted user)"
    }
}
