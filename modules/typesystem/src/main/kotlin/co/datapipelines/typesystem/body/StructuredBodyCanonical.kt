package co.datapipelines.typesystem.body

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import java.io.StringWriter

/**
 * Writes [StructuredBody.canonical]'s text. Iterative, so a body at the decoder's maximum depth
 * (64) — or a deeper tree a caller built by hand — cannot overflow the stack.
 */
internal object StructuredBodyCanonical {
    private val factory = JsonFactory()

    private sealed interface Step {
        data class Write(
            val value: Any?,
        ) : Step

        data class Field(
            val name: String,
        ) : Step

        data object EndArray : Step

        data object EndObject : Step
    }

    fun write(root: Any?): String {
        val out = StringWriter()
        factory.createGenerator(out).use { generator ->
            val pending = ArrayDeque<Step>()
            pending.addLast(Step.Write(root))
            while (pending.isNotEmpty()) {
                when (val step = pending.removeLast()) {
                    is Step.Field -> generator.writeFieldName(step.name)
                    Step.EndArray -> generator.writeEndArray()
                    Step.EndObject -> generator.writeEndObject()
                    is Step.Write -> writeOne(generator, step.value, pending)
                }
            }
        }
        return out.toString()
    }

    private fun writeOne(
        generator: JsonGenerator,
        value: Any?,
        pending: ArrayDeque<Step>,
    ) {
        when (value) {
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
                generator.writeNumber(StructuredBody.canonicalNumber(value))
            }

            is Map<*, *> -> {
                generator.writeStartObject()
                pending.addLast(Step.EndObject)
                val sorted = value.entries.map { (k, v) -> keyOf(k) to v }.sortedBy { it.first }
                for ((k, v) in sorted.asReversed()) {
                    pending.addLast(Step.Write(v))
                    pending.addLast(Step.Field(k))
                }
            }

            is List<*> -> {
                generator.writeStartArray()
                pending.addLast(Step.EndArray)
                for (item in value.asReversed()) pending.addLast(Step.Write(item))
            }

            else -> {
                throw IllegalArgumentException("a structured body holds only JSON values, not ${value.javaClass.name}")
            }
        }
    }

    private fun keyOf(key: Any?): String =
        key as? String ?: throw IllegalArgumentException("a structured body's object keys are strings, not ${key?.javaClass?.name}")
}
