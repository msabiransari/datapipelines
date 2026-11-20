package co.datapipelines.application.endpoints

/**
 * The HTTP method of a published API (published-mutation-apis design record §2, §3.1) — half of a
 * route's identity, as immutable as its path.
 *
 * A published entity is one (method, path) pair serving one pinned pipeline version: `GET` is the
 * legacy default every pre-#1037 row carries, and `POST`/`PUT`/`DELETE` are the mutation methods,
 * each granted to a key on its OWN entity (never inherited down a tree, never re-granted on a
 * recreated route — the grant names the entity's id).
 *
 * Stored UPPERCASE (`GET`, `POST`, …) — the protocol's own spelling and the house convention for
 * an enum column (V11's `chk_triggered_via`, enums.md §18): the database CHECK refuses a value
 * this enum cannot name, so a bad write fails where it happens.
 *
 * [PATCH] is deliberately absent: the design record §4.4 applies the visibility rule to it, but no
 * PATCH entity may be authored — the closed set is what the `Allow` header draws from, and a value
 * outside it is refused at publish with `endpoint.method_invalid` rather than stored unreachable.
 */
enum class EndpointMethod {
    GET,
    POST,
    PUT,
    DELETE,
    ;

    /** Whether this method serves a MUTATION — everything the endpoint-key walk never inherits. */
    val isMutation: Boolean get() = this != GET

    companion object {
        /** Every method a route may be published with, in `Allow`-header order. */
        val ALL: List<EndpointMethod> = entries.toList()

        /**
         * The method a request or a management call names, or null when the value is not one a
         * route may be published with — the caller refuses with `endpoint.method_invalid`
         * (publish) or answers it as an unavailable method (serve, where the closed set is what
         * makes `Allow` exhaustive).
         */
        fun of(name: String): EndpointMethod? = entries.firstOrNull { it.name == name }
    }
}
