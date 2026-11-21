package co.datapipelines.typesystem.body

/**
 * The envelope budget of one structured body (published-mutation requirement §5): the UTF-8 byte
 * cap (`datapipelines.endpoints.body-max-bytes`, a positive integer, default 1048576) and the
 * nesting cap (`datapipelines.endpoints.body-max-depth`, 1–64, default 32). Neither imposes a
 * property schema or a row count.
 *
 * Depth counts containers: the root object or array is depth 1, so `{"a":{}}` and `[{}]` are
 * depth 2.
 */
data class StructuredBodyLimits(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    init {
        require(maxBytes > 0) { "body-max-bytes must be a positive integer, was $maxBytes" }
        require(maxDepth in MIN_DEPTH..MAX_DEPTH) { "body-max-depth must be in $MIN_DEPTH..$MAX_DEPTH, was $maxDepth" }
    }

    /**
     * The budget verdict on a body that is already a value tree — the form an authored fixture
     * (a template test case, `templates_evaluate`, a release re-run) arrives in after its own
     * request was parsed. Applies the same two numbers the decoder applies to a raw entity: the
     * nesting cap first (the root container counts 1), then the byte cap over the tree's
     * [StructuredBody.canonical] UTF-8 form. Returns the bounded reason, or null within budget.
     * It judges the budget only; the root rule stays with the caller's own gate.
     */
    fun refusalOf(value: Any?): StructuredBodyRefusalReason? {
        if (depthOf(value) > maxDepth) return StructuredBodyRefusalReason.TOO_DEEP
        val bytes =
            StructuredBodyCanonical
                .write(value)
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
        return if (bytes > maxBytes) StructuredBodyRefusalReason.TOO_MANY_BYTES else null
    }

    /** Container nesting of [root] by an explicit stack, so a hand-built deep tree cannot overflow. */
    private fun depthOf(root: Any?): Int {
        var deepest = 0
        val pending = ArrayDeque<Pair<Any?, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            val children =
                when (node) {
                    is Map<*, *> -> node.values
                    is List<*> -> node
                    else -> continue
                }
            deepest = maxOf(deepest, depth + 1)
            // Past the cap nothing deeper changes the verdict: stop descending.
            if (deepest > maxDepth) return deepest
            children.forEach { pending.addLast(it to depth + 1) }
        }
        return deepest
    }

    companion object {
        /** `datapipelines.endpoints.body-max-bytes`'s default: 1 MiB. */
        const val DEFAULT_MAX_BYTES: Long = 1_048_576L

        /** `datapipelines.endpoints.body-max-depth`'s default. */
        const val DEFAULT_MAX_DEPTH: Int = 32

        /** The smallest depth an operator may configure: flat objects and arrays of empty objects. */
        const val MIN_DEPTH: Int = 1

        /** The largest depth an operator may configure. */
        const val MAX_DEPTH: Int = 64
    }
}

/**
 * Why a supplied body is not a structured body. [tooLarge] reasons are the requirement §4's 413
 * (`endpoint.request.too_large`); every other reason is its 400 (`endpoint.request.invalid`).
 * The [wire] word is the bounded detail a refusal may name — never a supplied value.
 */
enum class StructuredBodyRefusalReason(
    val wire: String,
    val tooLarge: Boolean,
) {
    /** Not one complete JSON value: a syntax error, an empty-but-whitespace body or trailing tokens. */
    MALFORMED("malformed_json", tooLarge = false),

    /** Bytes that are not UTF-8, or a string holding an unpaired surrogate escape. */
    INVALID_ENCODING("invalid_encoding", tooLarge = false),

    /** One object names the same member twice. */
    DUPLICATE_MEMBER("duplicate_member", tooLarge = false),

    /** A null or scalar root, or a root array holding a non-object element. */
    INVALID_ROOT("invalid_root", tooLarge = false),

    /** A number whose exact value needs more than [StructuredBodyDecoder.MAX_NUMBER_DIGITS] digits. */
    NUMBER_OUT_OF_RANGE("number_out_of_range", tooLarge = false),

    /** More entity bytes than the byte cap allows. */
    TOO_MANY_BYTES("too_many_bytes", tooLarge = true),

    /** Containers nested past the depth cap. */
    TOO_DEEP("too_deep", tooLarge = true),

    /** One token (a number or a member name) longer than the parser's token limit. */
    TOKEN_TOO_LARGE("token_too_large", tooLarge = true),
}

/**
 * One decoder refusal: the [reason], the character [offset] where the decoder stopped (null when
 * unknown) and, for [StructuredBodyRefusalReason.INVALID_ROOT] in a root array, the zero-based
 * [index] of the first non-object element. Deliberately carries no supplied value, member name or
 * engine message (requirement §4: never echo supplied values).
 */
data class StructuredBodyRefusal(
    val reason: StructuredBodyRefusalReason,
    val offset: Long? = null,
    val index: Int? = null,
)

/** The decoder's verdict: the decoded body, or the refusal. */
sealed interface StructuredBodyDecoding {
    /** The body decoded within the budget. */
    data class Decoded(
        val body: StructuredBody,
    ) : StructuredBodyDecoding

    /** The body was refused before anything read it. */
    data class Refused(
        val refusal: StructuredBodyRefusal,
    ) : StructuredBodyDecoding
}
