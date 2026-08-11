package co.datapipelines.web.api

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The success envelope every non-SSE, non-binary response carries (rest-api.md §4.1).
 *
 * ```json
 * {"schema_version": 1, "correlation_id": "uuid", "data": {…}}
 * ```
 *
 * `schemaVersion` and `correlationId` both match the `^[a-z][A-Z]` shape the Kotlin rules
 * flag as a Jackson naming-strategy trap, so every field carries an explicit
 * [JsonProperty] on all three use-site targets rather than relying on a strategy.
 * [ApiEnvelopeSerializationTest] is the standing guard.
 */
data class ApiResponse<T>(
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int,
    @field:JsonProperty("correlation_id") @get:JsonProperty("correlation_id") @param:JsonProperty("correlation_id")
    val correlationId: String,
    @field:JsonProperty("data") @get:JsonProperty("data") @param:JsonProperty("data")
    val data: T,
) {
    companion object {
        /** rest-api §4.1 — the response envelope version. Currently 1. */
        const val SCHEMA_VERSION = 1

        /**
         * Wraps [data] with the current request's correlation id.
         *
         * The id is read from the MDC that [co.datapipelines.web.CorrelationIdFilter] set at
         * request start — never generated here, so the value in the body, the response header
         * and every log line of the request are one value (observability §3.3).
         */
        fun <T> of(data: T): ApiResponse<T> = ApiResponse(SCHEMA_VERSION, CorrelationId.current(), data)
    }
}

/**
 * The `data` payload of a list endpoint (rest-api.md §4.3): `items` plus `pagination`.
 */
data class PagedData<T>(
    @field:JsonProperty("items") @get:JsonProperty("items") @param:JsonProperty("items")
    val items: List<T>,
    @field:JsonProperty("pagination") @get:JsonProperty("pagination") @param:JsonProperty("pagination")
    val pagination: Pagination,
)

/**
 * The `pagination` block (rest-api.md §4.3).
 *
 * `hasMore` is derived, never supplied: `offset + items.size < total`. `total` is the count
 * of matching rows when the underlying repository can supply one; where it cannot (the
 * repositories this module reads offer offset/limit without a `count`), [Pagination.unknownTotal]
 * reports the best honest value — the highest index observed — rather than a fabricated count.
 */
data class Pagination(
    @field:JsonProperty("offset") @get:JsonProperty("offset") @param:JsonProperty("offset")
    val offset: Int,
    @field:JsonProperty("limit") @get:JsonProperty("limit") @param:JsonProperty("limit")
    val limit: Int,
    @field:JsonProperty("total") @get:JsonProperty("total") @param:JsonProperty("total")
    val total: Long,
    @field:JsonProperty("has_more") @get:JsonProperty("has_more") @param:JsonProperty("has_more")
    val hasMore: Boolean,
) {
    companion object {
        /** rest-api §4.3 — "Max `limit` is 200 (configurable)". */
        const val MAX_LIMIT = 200

        /** rest-api §4.3 — the documented default page size. */
        const val DEFAULT_LIMIT = 50

        /**
         * Clamps a client-supplied `limit` into `1..`[MAX_LIMIT]; `null` takes [DEFAULT_LIMIT].
         *
         * Clamping rather than rejecting matches the result cursor's own clamp discipline
         * (rest-api §7.4) and keeps an over-large page from becoming an unbounded scan.
         */
        fun clampLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        /** Clamps a client-supplied `offset` to `>= 0`. */
        fun clampOffset(requested: Int?): Int = (requested ?: 0).coerceAtLeast(0)

        /** Pagination over a page whose total row count IS known. */
        fun of(
            offset: Int,
            limit: Int,
            total: Long,
            pageSize: Int,
        ): Pagination = Pagination(offset, limit, total, offset.toLong() + pageSize < total)

        /**
         * Pagination for a repository that exposes no `count`.
         *
         * The page is fetched with `limit + 1` rows; a full extra row proves there is more.
         * `total` then reports the rows proven to exist so far — an honest lower bound, never
         * a guess. Documented on the endpoints that use it.
         */
        fun unknownTotal(
            offset: Int,
            limit: Int,
            pageSize: Int,
            hasMore: Boolean,
        ): Pagination = Pagination(offset, limit, offset.toLong() + pageSize, hasMore)
    }
}
