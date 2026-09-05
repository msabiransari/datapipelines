package co.datapipelines.application.endpoints

/**
 * The published-endpoint path grammar (published-endpoints design §4.1).
 *
 * A `path_pattern` is 1–10 segments, each either a **literal** `[a-z0-9][a-z0-9_.-]{0,63}` or a
 * **variable** `{name}` whose name obeys the parameter grammar `[a-z_][a-z0-9_]*`; at most 200
 * characters; a leading `/`, no trailing slash, and no `**`.
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
 * `/a/{x}` and `/a/b` both match `GET /api/x/a/b`. Spring's own request mapping resolves this by
 * preferring the more specific pattern; this design deliberately does NOT (§4.1) — the second
 * publish is refused with `endpoint.path_conflict` naming the first. The consequence is worth
 * stating because the whole matcher depends on it: **at request time at most one pattern can
 * match**, so the matcher never has to rank, and a reader of the registry can tell what a URL
 * does by finding the one row it matches.
 */
object EndpointPath {
    /** §4.1 — a literal segment. Same alphabet as a template-name segment. */
    val LITERAL_SEGMENT = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")

    /** §4.1 — a variable segment `{name}`, the name obeying the parameter grammar. */
    val VARIABLE_SEGMENT = Regex("^\\{([a-z_][a-z0-9_]*)}$")

    const val MAX_LENGTH = 200
    const val MAX_SEGMENTS = 10

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
     * Parses [pattern] against §4.1, or returns why it is not a legal path.
     *
     * Every rule is checked and the FIRST failure is reported: unlike the request validator
     * (§5.3, which reports every defect at once because a client fixes a request once), a path
     * is authored once by a human who fixes one thing at a time, and naming the first broken
     * segment is more actionable than naming five consequences of it.
     */
    @Suppress("ReturnCount") // each §4.1 rule is its own refusal; collapsing them would obscure the grammar
    fun parse(pattern: String): Result<Parsed> {
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
        val segments = mutableListOf<Segment>()
        val seenVariables = mutableSetOf<String>()
        for (segment in raw) {
            val variable = VARIABLE_SEGMENT.find(segment)
            when {
                variable != null -> {
                    val name = variable.groupValues[1]
                    if (!seenVariables.add(name)) {
                        return reject("Path variable '{$name}' appears twice in '$pattern'; each names one parameter (§4.1).")
                    }
                    segments += Segment.Variable(name)
                }

                LITERAL_SEGMENT.matches(segment) -> {
                    segments += Segment.Literal(segment)
                }

                // A segment that LOOKS like a variable but is not a legal one gets its own
                // message: '{Borough}' failing as "not a literal" would send the author looking
                // at the wrong rule entirely.
                segment.startsWith('{') || segment.endsWith('}') -> {
                    return reject(
                        "Path variable segment '$segment' is malformed; a variable is '{name}' with " +
                            "name matching [a-z_][a-z0-9_]* (§4.1).",
                    )
                }

                else -> {
                    return reject(
                        "Path segment '$segment' is not legal; a literal segment matches " +
                            "[a-z0-9][a-z0-9_.-]{0,63} (§4.1).",
                    )
                }
            }
        }
        return Result.success(Parsed(pattern, segments))
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

    private fun reject(message: String): Result<Parsed> = Result.failure(EndpointPathException(message))
}

/** Carries a [EndpointPath.parse] rejection message; the surface maps it to `endpoint.path_invalid`. */
class EndpointPathException(
    message: String,
) : IllegalArgumentException(message)
