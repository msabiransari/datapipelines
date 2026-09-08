package co.datapipelines.web.ui

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import java.time.Instant

/**
 * The API-key table's row model (091, ui-screens.md §4.18) — built in ONE place because three
 * renders show the same table: the page, the post-create out-of-band refresh, and the rows a
 * revoke swaps in. Before 091 the third of those was a Kotlin string builder kept "byte-for-byte
 * the shape of" the template's fragment by a parity test; there is now one fragment and one row
 * model, so the two cannot disagree.
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
     * which exists only in the response that minted it.
     */
    data class Row(
        val id: String,
        val name: String,
        val kind: String,
        val prefix: String,
        val scopes: List<String>,
        val boundPaths: List<String>,
        val createdRelative: String,
        val createdAbsolute: String,
        val lastUsedRelative: String,
        val lastUsedAbsolute: String?,
        val expiresRelative: String,
        val expiresAbsolute: String?,
        val isRevoked: Boolean,
        val isExpired: Boolean,
    ) {
        /** A key that can still authenticate — the only kind with a revoke affordance. */
        val isLive: Boolean get() = !isRevoked && !isExpired
    }

    /**
     * [keys] as rows, live ones first and newest first within each group — a revoked key stays
     * VISIBLE (its `is_revoked` is a fact an operator checks) but never at the top.
     *
     * Bindings are read once per ENDPOINT key, and only for endpoint keys: no other kind has
     * any, and a per-row query for keys that cannot have bindings is a page of empty reads.
     */
    fun of(
        keys: List<ApiKey>,
        now: Instant,
    ): List<Row> =
        keys
            .sortedWith(compareBy({ it.isRevoked }, { -it.createdAt.epochSecond }))
            .map { key -> row(key, now) }

    private fun row(
        key: ApiKey,
        now: Instant,
    ): Row =
        Row(
            id = key.id,
            name = key.name,
            kind = key.kind.wire,
            prefix = key.id.take(PREFIX_CHARS) + "…",
            scopes = key.scopes.map { it.wire }.sorted(),
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
        )

    private companion object {
        /** `dpk_` plus four characters — enough to recognise a key, useless to anyone else. */
        const val PREFIX_CHARS = 8

        /** The word both null cases render as; "—" would leave the reader guessing which. */
        const val NEVER = "never"
    }
}
