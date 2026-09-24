package co.datapipelines.auth

/**
 * What a `users` row IS (#215, record PK5; `users.kind`, V34, CHECK `chk_users_kind`).
 *
 * Only a [HUMAN] signs in, is listed and administered by user administration, becomes a
 * workspace member or receives an invitation. A [SERVICE] row is a key's own identity — created
 * in the same transaction as its key, named after it, acting as the key everywhere a principal
 * is attributed — and a [SYSTEM] row is the one System actor (auth.md §4.5). Neither can log in
 * by construction (no password, an unresolvable `.invalid` email, a provider reserved at startup),
 * and every login and administration path also refuses them by this kind — belt and braces, so a
 * future path that forgets one of the constructions still meets the other.
 */
enum class UserKind {
    HUMAN,
    SERVICE,
    SYSTEM,
    ;

    /** The wire and database token (`human`, `service`, `system`). */
    val wire: String get() = name.lowercase()

    companion object {
        /** Parses a stored token. Throws on an unknown one — a row that cannot be classified must not authenticate. */
        fun fromWire(token: String): UserKind =
            entries.firstOrNull { it.wire == token } ?: throw IllegalArgumentException("Unknown user kind: $token")
    }
}
