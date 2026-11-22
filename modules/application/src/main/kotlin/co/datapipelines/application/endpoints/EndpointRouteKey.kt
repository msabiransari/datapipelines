package co.datapipelines.application.endpoints

/**
 * The API family's pin key in the release and dependency graph (#1037, requirement §2/§3): ONE string that names a
 * published route by BOTH halves of its identity, method and path. The graph's neutral address carries a single lookup
 * key, so a path alone cannot tell `GET /shop/v1/orders` from `POST /shop/v1/orders` — two entities, two pins, two
 * version histories that may share version numbers.
 *
 * The spelling is deliberately asymmetric, and the asymmetry is the compatibility rule:
 *
 * - **GET is the bare path** (`/shop/v1/orders`) — byte-identical to every key a pre-#1037 caller, plan digest, tree
 *   golden or stored link already holds, so legacy path-only addressing defaults to GET with no migration.
 * - **A mutation method is `METHOD path`** (`POST /shop/v1/orders`) — one ASCII space. A stored path always begins
 *   with `/` and never contains a space, so the two forms can never collide and [parse] has exactly one reading.
 *
 * The method part is the closed [EndpointMethod] set, upper case, so an unknown or lower-case word is not a key.
 */
object EndpointRouteKey {
    private const val SEPARATOR = ' '

    /** A parsed key: the route's method and its path as stored or as supplied (not yet normalised). */
    data class Route(
        val method: EndpointMethod,
        val path: String,
    )

    /** The key of [method] at the STORED [pathPattern]; GET is the bare path. */
    fun of(
        method: EndpointMethod,
        pathPattern: String,
    ): String = if (method == EndpointMethod.GET) pathPattern else "${method.name}$SEPARATOR$pathPattern"

    /**
     * The route [key] names, or null when its method word is not a published method (including `GET path`, which is
     * not a key: GET's only spelling is the bare path, so one route has one key). A bare value is a GET.
     */
    fun parse(key: String): Route? {
        val space = key.indexOf(SEPARATOR)
        if (space < 0) return Route(EndpointMethod.GET, key)
        val method = EndpointMethod.of(key.substring(0, space))?.takeIf { it.isMutation } ?: return null
        return Route(method, key.substring(space + 1))
    }
}
