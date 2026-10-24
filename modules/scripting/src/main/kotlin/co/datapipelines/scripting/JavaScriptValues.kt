package co.datapipelines.scripting

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The JavaScript engine's two crossings (#8, D3 ruled 2026-10-20 "numbers by logical type"):
 * the input encoded as JSON text for the guest's `JSON.parse`, and the guest's JSON text decoded
 * back into the module's JSON shape.
 *
 * ## The number rule, input side
 *
 * A JavaScript number is a double: an integer past ±2^53 or a decimal whose double is not the same
 * value would arrive silently changed. So an integer within ±2^53 crosses as a number, and an integer
 * past it — and every [BigInteger], the BIGINTEGER logical type's carrier — crosses as its decimal
 * STRING; a [BigDecimal] (DECIMAL) crosses as a number only when `BigDecimal(d.toString())` is the
 * same value as the double `d`, else as its plain decimal string. A DECIMAL may therefore arrive as a
 * string when the double would lose digits: `Number(x)` loses digits beyond 15; for exact money use
 * integer minor units. A non-finite double is refused (JSON has no NaN).
 *
 * ## The number rule, output side
 *
 * The guest's text is parsed with every float as a [BigDecimal]: an integral number becomes a [Long]
 * (a [BigInteger] past Long), a non-integral one stays a [BigDecimal] of the shortest round-trip
 * spelling `JSON.stringify` wrote; a BigInt arrives as its decimal string (the prelude's replacer).
 *
 * ## The decode is the boundary
 *
 * The prelude's shape check is an aid. This decode is what the host trusts: strict JSON (duplicate
 * keys and trailing tokens refused), nesting capped at [MAX_NESTING] (past it:
 * [ScriptResourceLimitException.Kind.DEPTH]), and only Map/List/String/Number/Boolean/null out.
 */
internal object JavaScriptValues {
    /** The nesting both crossings allow — the input encoder and the result decoder alike. */
    const val MAX_NESTING = 1000

    private const val SAFE_LONG = 9_007_199_254_740_992L // 2^53
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

    private val factory: JsonFactory = JsonFactory()

    /**
     * Encodes [value] as JSON text, refusing past [maxBytes] UTF-8 bytes
     * ([ScriptResourceLimitException.Kind.INPUT_BYTES]) while it writes — never after building it whole.
     */
    fun encodeInput(
        value: Any?,
        maxBytes: Long,
    ): String {
        val buffer =
            BoundedBuffer(maxBytes) { attempted ->
                ScriptResourceLimitException(
                    ScriptResourceLimitException.Kind.INPUT_BYTES,
                    "the serialized input passed $maxBytes bytes (at least $attempted) — refused before the sandbox started",
                )
            }
        factory.createGenerator(buffer).use { generator -> writeIterative(generator, value) }
        return buffer.utf8()
    }

    /** One pending step of the iterative writer: a value to write, or a container to close. */
    private sealed interface Step {
        data class Write(
            val value: Any?,
            val depth: Int,
        ) : Step

        data class Field(
            val name: String,
        ) : Step

        data object EndArray : Step

        data object EndObject : Step
    }

    // Iterative, so a deep input cannot overflow the host stack before the nesting cap refuses it.
    private fun writeIterative(
        generator: JsonGenerator,
        root: Any?,
    ) {
        val pending = ArrayDeque<Step>()
        pending.addLast(Step.Write(root, 0))
        while (pending.isNotEmpty()) {
            when (val step = pending.removeLast()) {
                is Step.Field -> generator.writeFieldName(step.name)
                Step.EndArray -> generator.writeEndArray()
                Step.EndObject -> generator.writeEndObject()
                is Step.Write -> writeOne(generator, step, pending)
            }
        }
    }

    private fun writeOne(
        generator: JsonGenerator,
        step: Step.Write,
        pending: ArrayDeque<Step>,
    ) {
        when (val value = step.value) {
            null -> {
                generator.writeNull()
            }

            is String -> {
                generator.writeString(value)
            }

            is Boolean -> {
                generator.writeBoolean(value)
            }

            is Number -> {
                writeNumber(generator, value)
            }

            is Map<*, *> -> {
                refuseDepth(step.depth)
                generator.writeStartObject()
                pending.addLast(Step.EndObject)
                for ((k, v) in value.entries.reversed()) {
                    pending.addLast(Step.Write(v, step.depth + 1))
                    pending.addLast(Step.Field(keyString(k)))
                }
            }

            is List<*> -> {
                refuseDepth(step.depth)
                generator.writeStartArray()
                pending.addLast(Step.EndArray)
                for (item in value.asReversed()) pending.addLast(Step.Write(item, step.depth + 1))
            }

            else -> {
                throw ScriptEvaluationException(
                    "input value of type ${value.javaClass.name} is not JSON-shaped (Map/List/String/Number/Boolean/null expected)",
                )
            }
        }
    }

    private fun refuseDepth(depth: Int) {
        if (depth >= MAX_NESTING) {
            throw ScriptResourceLimitException(
                ScriptResourceLimitException.Kind.DEPTH,
                "the input nests deeper than $MAX_NESTING levels",
            )
        }
    }

    private fun writeNumber(
        generator: JsonGenerator,
        value: Number,
    ) {
        when (value) {
            is Int, is Short, is Byte -> {
                generator.writeNumber(value.toLong())
            }

            is Long -> {
                if (value in -SAFE_LONG..SAFE_LONG) generator.writeNumber(value) else generator.writeString(value.toString())
            }

            is BigInteger -> {
                generator.writeString(value.toString())
            }

            is BigDecimal -> {
                val asDouble = value.toDouble()
                if (asDouble.isFinite() && BigDecimal(asDouble.toString()).compareTo(value) == 0) {
                    generator.writeNumber(asDouble)
                } else {
                    generator.writeString(value.toPlainString())
                }
            }

            else -> {
                val asDouble = value.toDouble()
                if (!asDouble.isFinite()) {
                    throw ScriptEvaluationException("input number $value is not finite; JSON has no NaN or Infinity")
                }
                generator.writeNumber(asDouble)
            }
        }
    }

    private fun keyString(key: Any?): String =
        key as? String
            ?: throw ScriptEvaluationException("map key must be a JSON string, was ${key?.javaClass?.name ?: "null"}")

    /**
     * Decodes the guest's result text — after the caller measured its UTF-8 size — into the
     * module's JSON shape (see the class KDoc).
     */
    @Suppress("SwallowedException", "ThrowsCount") // a limit verdict carries the constraint's message, never a cause
    fun decodeResult(text: String): Any? {
        val mapper = resultMapper(text.length)
        val tree =
            try {
                mapper.readTree(text)
            } catch (err: StreamConstraintsException) {
                throw ScriptResourceLimitException(
                    ScriptResourceLimitException.Kind.DEPTH,
                    "the result nests deeper than $MAX_NESTING levels (${err.originalMessage})",
                )
            } catch (err: com.fasterxml.jackson.core.JacksonException) {
                throw ScriptEvaluationException("the engine's result is not strict JSON: ${err.originalMessage}", err)
            }
        return toValue(tree ?: throw ScriptEvaluationException("the engine's result is empty"))
    }

    private fun resultMapper(maxChars: Int): JsonMapper {
        val decodeFactory =
            JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxNestingDepth(MAX_NESTING)
                        .maxStringLength(maxOf(maxChars, StreamReadConstraints.DEFAULT_MAX_STRING_LEN))
                        .build(),
                ).build()
        return JsonMapper
            .builder(decodeFactory)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    }

    // The tree is at most MAX_NESTING deep (the parser refused deeper), so recursion is bounded.
    private fun toValue(node: JsonNode): Any? =
        when {
            node.isNull -> {
                null
            }

            node.isTextual -> {
                node.textValue()
            }

            node.isBoolean -> {
                node.booleanValue()
            }

            node.isNumber -> {
                number(node.decimalValue())
            }

            node.isArray -> {
                node.map { toValue(it) }
            }

            node.isObject -> {
                val out = LinkedHashMap<String, Any?>(node.size())
                for ((name, child) in node.properties()) out[name] = toValue(child)
                out
            }

            else -> {
                throw ScriptEvaluationException("the engine's result holds a non-JSON node (${node.nodeType})")
            }
        }

    /** Integral → Long (BigInteger past Long); non-integral → the BigDecimal as written. */
    fun number(value: BigDecimal): Number {
        val integral = value.signum() == 0 || value.stripTrailingZeros().scale() <= 0
        if (!integral) return value
        val big = value.toBigIntegerExact()
        return if (big in LONG_MIN..LONG_MAX) big.toLong() else big
    }
}
