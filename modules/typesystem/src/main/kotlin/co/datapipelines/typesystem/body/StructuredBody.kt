package co.datapipelines.typesystem.body

import java.math.BigDecimal
import java.math.BigInteger

/**
 * A decoded structured request body (published-mutation requirement §3.2–§3.4, #1038): the one
 * value every surface that accepts a body produces — the published HTTP route, framework execute
 * and the MCP execution fixture — and the one value a SCRIPT `body` input receives.
 *
 * ## Three states, never conflated
 *
 * [Absent] (no body was supplied), [ObjectRoot] (a JSON object, `{}` included) and [ArrayRoot] (a
 * JSON array whose every element is an object, `[]` included). A null root, a scalar root or an
 * array holding a non-object is not a body at all — [StructuredBodyDecoder] refuses it — so no
 * state here can carry one. A singleton array stays an [ArrayRoot]; nothing converts it to an
 * object.
 *
 * ## The value tree
 *
 * Objects are [LinkedHashMap]s in source member order with [String] keys; arrays are [List]s in
 * source order; scalars are [String], [Boolean], null, and the exact parsed number: [Long] for an
 * integer that fits, [BigInteger] past it, [BigDecimal] (as written, scale included) for any
 * number with a fraction or exponent. Unknown fields, nulls and empty containers are data like any
 * other. The tree is never mutated after decoding; callers must not mutate it either.
 *
 * ## Comparison
 *
 * [canonical] is the comparison form #1039's request fingerprint reads and [sameRequestBodyAs]
 * compares: object member order is immaterial, array order is material, a number compares by
 * exact value (`1.50` equals `1.5`, `1` equals `1.0`), and [Absent] differs from `{}` and `[]`.
 */
sealed interface StructuredBody {
    /** True for an object or array root; false for [Absent]. */
    val present: Boolean

    /** The native tree a script engine receives — a Map or a List; null only for [Absent]. */
    val value: Any?

    /** No body was supplied. Distinct from `{}`, `[]` and JSON null. */
    data object Absent : StructuredBody {
        override val present: Boolean get() = false
        override val value: Any? get() = null
    }

    /** A JSON object root, possibly `{}`. */
    data class ObjectRoot(
        val members: Map<String, Any?>,
    ) : StructuredBody {
        override val present: Boolean get() = true
        override val value: Any? get() = members
    }

    /** A JSON array root whose every element is an object, possibly `[]` or a singleton. */
    data class ArrayRoot(
        val items: List<Map<String, Any?>>,
    ) : StructuredBody {
        override val present: Boolean get() = true
        override val value: Any? get() = items
    }

    /**
     * The canonical comparison text, or null for [Absent]: compact JSON, object members sorted
     * by key (UTF-16 order, the same at every call), arrays in order, numbers in the normalized
     * exact form (see [canonicalNumber]), strings JSON-escaped. Not a display form.
     */
    fun canonical(): String? = if (present) StructuredBodyCanonical.write(value) else null

    /** True when both bodies are the same request input under [canonical]'s rules. */
    fun sameRequestBodyAs(other: StructuredBody): Boolean = canonical() == other.canonical()

    companion object {
        /**
         * The normalized exact spelling of one number: its [BigDecimal] value with trailing
         * zeros stripped, in [BigDecimal.toString]'s form (scientific when the exponent is
         * large — never the plain form, whose length an exponent could inflate without bound).
         */
        fun canonicalNumber(number: Number): String =
            when (number) {
                is BigDecimal -> number
                is BigInteger -> BigDecimal(number)
                is Long, is Int, is Short, is Byte -> BigDecimal.valueOf(number.toLong())
                else -> BigDecimal(number.toString())
            }.stripTrailingZeros().toString()
    }
}
