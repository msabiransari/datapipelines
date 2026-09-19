package co.datapipelines.application.endpoints

/**
 * The published-endpoint path grammar (published-endpoints design §4.1, ruling R-EP5).
 *
 * A `path_pattern` is 3–10 segments: a **category**, a **version**, then the path itself. Each
 * segment is either a **literal** `[a-z0-9][a-z0-9_.-]{0,63}` or a **variable** `{name}` whose
 * name obeys the parameter grammar `[a-z_][a-z0-9_]*`; at most 200 characters; a leading `/`, no
 * trailing slash, and no `**`. Variables are allowed only AFTER the version — the category and
 * the version are always literal, because they are what the URL is routed and reserved on.
 *
 * The served URL is `/api{path_pattern}` — the stored form is always the part AFTER `/api`
 * (R-EP5). A pattern handed in WITH one `/api` prefix is normalised, not refused: the one prefix
 * is stripped and the rest is validated ([normalize]).
 *
 * The **category** is the engineer's namespace — a business domain, a team, a product line. Two
 * categories are RESERVED ([RESERVED_CATEGORY]): anything matching `v[0-9]+`, which is the
 * product's own API namespace (`/api/v1/…` today, any `v<n>` tomorrow), and the literal `api`.
 * The reservation is what makes a published path unable to shadow a product route BY
 * CONSTRUCTION rather than by a route-table check. It is not a parse failure — it is a policy
 * refusal (`endpoint.path_reserved`), checked by the publish service and re-checked when the
 * serve registry is built ([reservedCategory]), so a row written around the publish path is
 * dropped from serving rather than breaking every other endpoint's read.
 *
 * The **version** is one free-form literal segment — no pattern is enforced; `v1` is the docs'
 * convention, `2025` is equally legal.
 *
 * The literal segment rule is deliberately the SAME one hierarchical template names use
 * (template-hierarchy-design §4) — one grammar to learn for both trees, and the same reason
 * behind it: a segment that starts alphanumeric and stays in that alphabet is unambiguous in a
 * URL, in a file name and in a shell.
 *
 * `**` is refused rather than supported: a published endpoint names a concrete resource, and a
 * wildcard subtree would make the §4.1 ambiguity rule (below) undecidable — every `**` pattern
 * overlaps every pattern beneath it, so publishing one would have to refuse everything under it.
 *
 * ## Why ambiguity is refused instead of resolved
 *
 * `/nyc/v1/{x}` and `/nyc/v1/b` both match `GET /api/nyc/v1/b`. Spring's own request mapping
 * resolves this by preferring the more specific pattern; this design deliberately does NOT
 * (§4.1) — the second publish is refused with `endpoint.path_conflict` naming the first. The
 * consequence is worth stating because the whole matcher depends on it: **at request time at
 * most one pattern can match**, so the matcher never has to rank, and a reader of the registry
 * can tell what a URL does by finding the one row it matches.
 */
object EndpointPath {
    /** §4.1 — a literal segment. Same alphabet as a template-name segment. */
    val LITERAL_SEGMENT = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")

    /** §4.1 — a variable segment `{name}`, the name obeying the parameter grammar. */
    val VARIABLE_SEGMENT = Regex("^\\{([a-z_][a-z0-9_]*)}$")

    const val MAX_LENGTH = 200
    const val MAX_SEGMENTS = 10

    /** R-EP5 — the shape floor: category, version, and at least one path segment. */
    const val MIN_SEGMENTS = 3

    /** Segment positions the shape rules talk about (R-EP5). */
    const val CATEGORY_INDEX = 0
    const val VERSION_INDEX = 1

    /** The URL root the published-endpoint handler owns; a stored pattern never carries it. */
    const val API_ROOT = "/api"

    /**
     * R-EP5 — the reserved categories: the product's API namespace (`v[0-9]+`) and the literal
     * `api`. A category that matches is a publish-time refusal and a serve-time drop, never a
     * route.
     */
    val RESERVED_CATEGORY = Regex("^v[0-9]+$|^api$")

    /**
     * [RESERVED_CATEGORY] as a Spring path-variable constraint for the catch-all mapping, so the
     * routing TABLE states the reservation and a reserved category does not match the published
     * subtree at all — `/api/v1/…` falls through to the product's own routes or its own 404.
     * Anchored inside the lookaheads because a segment regex must fail on `v1a` nowhere but at
     * the look: `v1a` is a legal category, `v1` is not.
     */
    const val CATEGORY_URL_PATTERN = "(?!v[0-9]+${'$'}|api${'$'})[a-z0-9][a-z0-9_.-]*"

    /** Why a pattern is not a legal path (the message the 400 carries). */
    sealed interface Rejection {
        val message: String

        data class Malformed(
            override val message: String,
        ) : Rejection
    }

    /**
     * The parsed form: the ordered segments, each literal or variable.
     *
     * Kept as a value so the matcher, the ambiguity check and the tree screen all read ONE
     * parse rather than three re-splits of the string.
     */
    data class Parsed(
        val pattern: String,
        val segments: List<Segment>,
    ) {
        /** The declared parameter names this pattern binds, in order. */
        val variableNames: List<String> get() = segments.filterIsInstance<Segment.Variable>().map { it.name }
    }

    sealed interface Segment {
        data class Literal(
            val value: String,
        ) : Segment

        data class Variable(
            val name: String,
        ) : Segment
    }

    /**
     * Parses [pattern] against §4.1 as a PUBLISHED ENDPOINT, or returns why it is not a legal
     * path. The one `/api` prefix is stripped first ([normalize]); the parsed form carries the
     * stored spelling (the part after `/api`).
     *
     * Every rule is checked and the FIRST failure is reported: unlike the request validator
     * (§5.3, which reports every defect at once because a client fixes a request once), a path
     * is authored once by a human who fixes one thing at a time, and naming the first broken
     * segment is more actionable than naming five consequences of it.
     */
    fun parse(pattern: String): Result<Parsed> = parseNormalized(normalize(pattern), endpointShape = true)

    /**
     * Parses a TREE NODE — what a key binding names (§5.2). Same segments, same normalisation,
     * none of the endpoint shape: a binding may stop at the category (`/nyc` authorises
     * everything beneath it), so the three-segment floor and the literal category/version rules
     * do not apply here.
     */
    fun parseTreeNode(pattern: String): Result<Parsed> = parseNormalized(normalize(pattern), endpointShape = false)

    /**
     * Normalisation, not refusal (R-EP5): a pattern beginning with `/api/` has that ONE prefix
     * stripped — the stored form is always the part after it, and `/api/api/…` therefore leaves
     * `api` as the category, which [RESERVED_CATEGORY] then refuses. A bare `/api` collapses to
     * the root, which the grammar refuses on its own terms.
     */
    fun normalize(pattern: String): String =
        when {
            pattern == API_ROOT -> "/"
            pattern.startsWith("$API_ROOT/") -> pattern.removePrefix(API_ROOT)
            else -> pattern
        }

    /**
     * The category this pattern would serve under, when it is one of the reserved ones
     * ([RESERVED_CATEGORY]) — null for a legal pattern. A separate check from [parse] on
     * purpose: the reserved rule is a publish-time REFUSAL (`endpoint.path_reserved`, naming the
     * segment) and a serve-time DROP, and a row that somehow bypassed both must still LOAD —
     * mapping it to a parse failure would take every other endpoint's registry read down with it.
     */
    fun reservedCategory(parsed: Parsed): String? {
        val first = parsed.segments.firstOrNull() as? Segment.Literal ?: return null
        return first.value.takeIf { RESERVED_CATEGORY.matches(it) }
    }

    @Suppress("ReturnCount") // each §4.1 rule is its own refusal; collapsing them would obscure the grammar
    private fun parseNormalized(
        pattern: String,
        endpointShape: Boolean,
    ): Result<Parsed> {
        if (pattern.length > MAX_LENGTH) {
            return reject("Path is ${pattern.length} characters; the limit is $MAX_LENGTH (§4.1).")
        }
        if (!pattern.startsWith('/')) {
            return reject("Path must start with '/' (§4.1); '$pattern' does not.")
        }
        if (pattern == "/") {
            return reject("The root path '/' cannot be published (§4.1) — an endpoint names at least one segment.")
        }
        if (pattern.endsWith('/')) {
            return reject("Path must not end with '/' (§4.1); '$pattern' does.")
        }
        if (pattern.contains("**")) {
            return reject("Wildcards are not part of the path grammar (§4.1); '$pattern' contains '**'.")
        }
        val raw = pattern.removePrefix("/").split('/')
        if (raw.size > MAX_SEGMENTS) {
            return reject("Path has ${raw.size} segments; the limit is $MAX_SEGMENTS (§4.1).")
        }
        if (endpointShape && raw.size < MIN_SEGMENTS) {
            return reject(
                "Path has ${raw.size} segment(s); an endpoint is at least $MIN_SEGMENTS — " +
                    "/<category>/<version>/<path…> (R-EP5): the category is your namespace, the version one free-form segment.",
            )
        }
        val segments = mutableListOf<Segment>()
        val seenVariables = mutableSetOf<String>()
        for ((index, segment) in raw.withIndex()) {
            segments += parseSegment(pattern, segment, index, endpointShape, seenVariables).getOrElse { return Result.failure(it) }
        }
        return Result.success(Parsed(pattern, segments))
    }

    /** One segment against the §4.1 grammar plus, for an endpoint, the R-EP5 position rules. */
    @Suppress("ReturnCount") // each grammar rule is its own refusal; collapsing them would obscure the grammar
    private fun parseSegment(
        pattern: String,
        segment: String,
        index: Int,
        endpointShape: Boolean,
        seenVariables: MutableSet<String>,
    ): Result<Segment> {
        val variable = VARIABLE_SEGMENT.find(segment)
        if (variable != null) {
            if (endpointShape && index <= VERSION_INDEX) {
                val role = if (index == CATEGORY_INDEX) "category" else "version"
                return reject(
                    "The $role segment of '$pattern' is a variable; the category and the version are always " +
                        "literal — variables are allowed only after the version (R-EP5).",
                )
            }
            val name = variable.groupValues[1]
            if (!seenVariables.add(name)) {
                return reject("Path variable '{$name}' appears twice in '$pattern'; each names one parameter (§4.1).")
            }
            return Result.success(Segment.Variable(name))
        }
        if (LITERAL_SEGMENT.matches(segment)) {
            return Result.success(Segment.Literal(segment))
        }
        // A segment that LOOKS like a variable but is not a legal one gets its own
        // message: '{Borough}' failing as "not a literal" would send the author looking
        // at the wrong rule entirely.
        if (segment.startsWith('{') || segment.endsWith('}')) {
            return reject(
                "Path variable segment '$segment' is malformed; a variable is '{name}' with " +
                    "name matching [a-z_][a-z0-9_]* (§4.1).",
            )
        }
        return reject(
            "Path segment '$segment' is not legal; a literal segment matches " +
                "[a-z0-9][a-z0-9_.-]{0,63} (§4.1).",
        )
    }

    /**
     * Whether two patterns could match the SAME request URL — the §4.1 ambiguity rule.
     *
     * Two patterns overlap when they have the same number of segments and, position by
     * position, either side is a variable or both literals are equal. A variable matches
     * exactly one segment, which is what makes segment count the first and cheapest test.
     *
     * This is an equivalence-free relation on purpose: it is symmetric and reflexive but says
     * nothing about which pattern is "better", because §4.1 does not rank — it refuses.
     */
    fun overlaps(
        a: Parsed,
        b: Parsed,
    ): Boolean {
        if (a.segments.size != b.segments.size) return false
        return a.segments.zip(b.segments).all { (left, right) ->
            left !is Segment.Literal || right !is Segment.Literal || left.value == right.value
        }
    }

    /**
     * The ancestor chain of a request path, most specific FIRST, ending at the root `/`
     * (§5.2's hierarchical key resolution walks exactly this list).
     *
     * `/lending/manhattan/home` → `["/lending/manhattan/home", "/lending/manhattan", "/lending", "/"]`.
     * The path itself is included: a binding may name a leaf, not only a folder.
     */
    fun ancestors(path: String): List<String> {
        val normalized = "/" + path.trim('/')
        if (normalized == "/") return listOf("/")
        val segments = normalized.removePrefix("/").split('/')
        return (segments.size downTo 1).map { "/" + segments.take(it).joinToString("/") } + "/"
    }

    private fun <T> reject(message: String): Result<T> = Result.failure(EndpointPathException(message))
}

/** Carries a [EndpointPath.parse] rejection message; the surface maps it to `endpoint.path_invalid`. */
class EndpointPathException(
    message: String,
) : IllegalArgumentException(message)
