package co.datapipelines.templates

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.InvalidNullException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import java.math.BigDecimal
import java.math.BigInteger

/**
 * How a Jackson failure to bind a transform-block payload is told to the author (#333).
 *
 * Two kinds of failure reach a strict reader, and they need different words:
 *  - [RULE_UNKNOWN_FIELD] - a typo inside a block (an `UnrecognizedPropertyException`), or the
 *    creator parameter the typo leaves missing (the Kotlin module's `InvalidNullException`), or any
 *    other failure of a constructor: the author's key is the problem.
 *  - [RULE_WRONG_TYPE] - the key is right and its JSON value has the wrong shape: a number where
 *    the contract declares a string, a string where it declares an integer. Jackson's own text for
 *    this ("Cannot coerce Integer value (987654321) to `java.lang.String`") QUOTES the value, so
 *    the message is built here from the path and the expected shape and never from that text.
 *
 * Everything reflected is clipped to [MAX_REFLECTED_LENGTH] and its control characters replaced -
 * the key of an unknown field and Jackson's message are attacker-controlled text. The `path` keeps
 * Jackson's `pathReference` spelling (`co.datapipelines.templates.TemplateDraft["contract"]`),
 * because the editor's pane mapping reads the substring of it; only its length is bounded.
 */
class BindFailure private constructor(
    /** [RULE_UNKNOWN_FIELD] or [RULE_WRONG_TYPE]. */
    val rule: String,
    /** The offending key (unknown field) or the last path segment (wrong type), clipped. */
    val field: String,
    /** Jackson's `pathReference`, clipped. */
    val path: String,
    /** The clipped text a message embeds: Jackson's message for an unknown field, the expected shape for a wrong type. */
    private val reflected: String,
) {
    /**
     * The author-facing sentence: [lead] (`"The transform blocks do not bind"`) and the reason, then
     * [unknownFieldAdvice] after an unknown-field reason only.
     */
    fun messageFor(
        lead: String,
        unknownFieldAdvice: String = "",
    ): String =
        when (rule) {
            RULE_WRONG_TYPE -> "$lead: '$field' must be $reflected."
            else -> "$lead: $reflected.$unknownFieldAdvice"
        }

    /** The `details` every bind refusal carries: `rule`, `field`, `path` - the same keys as before #333. */
    fun details(): Map<String, Any?> = mapOf("rule" to rule, "field" to field, "path" to path)

    companion object {
        const val RULE_UNKNOWN_FIELD = "unknown_field"
        const val RULE_WRONG_TYPE = "wrong_type"

        /** The longest reflected path, key or Jackson message - the house's reflected-path budget. */
        const val MAX_REFLECTED_LENGTH = 160

        private const val CONTROL_REPLACEMENT = '�'

        /** Classifies [err]; never reflects a JSON value. */
        fun of(err: JsonMappingException): BindFailure {
            val path = clip(err.pathReference)
            return when {
                err is UnrecognizedPropertyException -> {
                    BindFailure(RULE_UNKNOWN_FIELD, clip(err.propertyName), path, clip(err.originalMessage))
                }

                err is InvalidNullException || err !is MismatchedInputException -> {
                    BindFailure(RULE_UNKNOWN_FIELD, path, path, clip(err.originalMessage))
                }

                else -> {
                    BindFailure(
                        RULE_WRONG_TYPE,
                        clip(err.path.lastOrNull()?.fieldName ?: err.pathReference),
                        path,
                        expectedShape(err.targetType),
                    )
                }
            }
        }

        /** What a declared Kotlin/Java type is called to an author; never the value that failed. */
        internal fun expectedShape(target: Class<*>?): String =
            when {
                target == null -> "a value of the declared type"
                CharSequence::class.java.isAssignableFrom(target) -> "a string"
                target == Boolean::class.javaObjectType || target == Boolean::class.javaPrimitiveType -> "a boolean"
                isInteger(target) -> "an integer"
                isDecimal(target) -> "a number"
                target.isArray || Collection::class.java.isAssignableFrom(target) -> "an array"
                else -> "an object"
            }

        private fun isInteger(target: Class<*>): Boolean = target in INTEGER_TYPES || target == BigInteger::class.java

        private fun isDecimal(target: Class<*>): Boolean =
            target in DECIMAL_TYPES || target == BigDecimal::class.java || target == Number::class.java

        private val INTEGER_TYPES: Set<Class<*>> =
            setOf(Int::class, Long::class, Short::class, Byte::class).flatMap { listOf(it.javaPrimitiveType!!, it.javaObjectType) }.toSet()

        private val DECIMAL_TYPES: Set<Class<*>> =
            setOf(Double::class, Float::class).flatMap { listOf(it.javaPrimitiveType!!, it.javaObjectType) }.toSet()

        /** Truncation before sanitising, so the work is bounded by the cap and not by the attacker's length. */
        private fun clip(raw: String?): String {
            val text = raw ?: return "null"
            val clipped = if (text.length <= MAX_REFLECTED_LENGTH) text else text.take(MAX_REFLECTED_LENGTH) + "…"
            return buildString(clipped.length) {
                for (ch in clipped) append(if (ch.isISOControl()) CONTROL_REPLACEMENT else ch)
            }
        }
    }
}
