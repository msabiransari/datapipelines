package co.datapipelines.auth

/**
 * Authorization scopes (auth.md §7.5) — a strict hierarchy, lowest to highest:
 *
 * `read` ⊂ `execute` ⊂ `author` ⊂ `admin`
 *
 * A holder of a higher scope implicitly satisfies every lower one ([implies]).
 * The wire form (JWT `scopes` claim, `api_keys.scopes` TEXT[]) is the lowercase
 * name ([wire]) — never `Scope.name`, which is upper-case.
 */
enum class Scope {
    // Declaration order IS the hierarchy: each scope's `ordinal` is its rank
    // (read=0 … admin=3), so `implies`/`expand` compare ordinals — no magic ranks.
    READ,
    EXECUTE,
    AUTHOR,
    ADMIN,
    ;

    /** Lowercase wire token as stored in the JWT claim and `api_keys.scopes`. */
    val wire: String get() = name.lowercase()

    /** True when holding `this` scope also grants [required] (hierarchy, §7.5). */
    fun implies(required: Scope): Boolean = this.ordinal >= required.ordinal

    /** This scope plus every lower scope it subsumes — the effective grant set. */
    fun expand(): Set<Scope> = entries.filter { this.ordinal >= it.ordinal }.toSet()

    companion object {
        /** Parses a wire token (`"read"`, `"admin"`). Throws on an unknown token. */
        fun fromWire(token: String): Scope =
            entries.firstOrNull { it.wire == token.lowercase() }
                ?: throw IllegalArgumentException("Unknown scope: $token")

        /** The effective grant set for a collection of held scopes (union of expansions). */
        fun effective(held: Collection<Scope>): Set<Scope> = held.flatMapTo(mutableSetOf()) { it.expand() }

        /** True when [held] satisfies [required] via the hierarchy. */
        fun satisfies(
            held: Collection<Scope>,
            required: Scope,
        ): Boolean = held.any { it.implies(required) }
    }
}
