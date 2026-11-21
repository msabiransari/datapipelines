package co.datapipelines.templates

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import java.io.StringWriter

/**
 * The `inputs` object of an authored test input ([ScriptTestInput]) as it was READ (#1038, review
 * C2): the ordinary values every input has always had, plus each member's exact JSON text.
 *
 * Why both. A template's contract decides which input is the request `body`, and the contract is
 * not known while a request is parsed (`templates_evaluate` resolves the template afterwards; a save
 * may carry the contract beside the tests). The ordinary values are what a table or value input
 * has always received — a fraction as a `double`, a repeated member's last copy — and they stay
 * exactly that. A `body` input is instead decoded by the runner from the member's exact text with
 * the shared structured-body decoder, so its fractions stay exact and a repeated member anywhere
 * inside it is refused — the same verdicts framework execute and `pipelines_execute` give the same
 * fixture.
 *
 * The exact text is only as exact as the token stream it was copied from: a stream over raw JSON
 * text (a REST body, the stored `tests_json`, the text an MCP transport kept) carries every token;
 * a stream over an already-parsed tree or map carries what that parse kept. Surfaces that accept a
 * body fixture therefore bind [ScriptTestInput] from text.
 *
 * Equality and hashing are the ordinary map's, so two inputs with the same ordinary values compare
 * equal whatever their spelling.
 */
class FixtureInputs private constructor(
    values: Map<String, Any?>,
    private val exact: Map<String, String>,
    private val repeated: Set<String>,
) : LinkedHashMap<String, Any?>(values) {
    /** The exact JSON text member [name] was read from, or null when it was not read from a stream. */
    fun exactText(name: String): String? = exact[name]

    /** True when the `inputs` object named [name] more than once — ambiguous for a body input. */
    fun isRepeated(name: String): Boolean = name in repeated

    /** Binds `inputs` from the token stream, keeping each member's exact text beside its ordinary value. */
    class Deserializer : StdDeserializer<Map<String, Any?>>(Map::class.java) {
        override fun deserialize(
            p: JsonParser,
            ctxt: DeserializationContext,
        ): Map<String, Any?> {
            if (!p.isExpectedStartObjectToken) {
                @Suppress("UNCHECKED_CAST") // handleUnexpectedToken throws the ordinary mismatch for a non-object
                return ctxt.handleUnexpectedToken(Map::class.java, p) as Map<String, Any?>
            }
            val values = LinkedHashMap<String, Any?>()
            val exact = LinkedHashMap<String, String>()
            val repeated = LinkedHashSet<String>()
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                val name = p.currentName()
                p.nextToken()
                val text = copyExact(p)
                if (exact.containsKey(name)) repeated += name
                // The ordinary value is the same reading a plain untyped bind gives: a later copy wins.
                values[name] = ORDINARY.readValue(text, Any::class.java)
                exact[name] = text
            }
            return FixtureInputs(values, exact, repeated)
        }

        /** The value the parser is on as exact JSON text: decimals as written, every member kept. */
        private fun copyExact(p: JsonParser): String {
            val out = StringWriter()
            FACTORY.createGenerator(out).use { generator: JsonGenerator -> generator.copyCurrentStructureExact(p) }
            return out.toString()
        }
    }

    /**
     * Writes `inputs` from the exact text where it has one (a stored or returned fixture keeps the
     * decimals it was written with), the ordinary value otherwise. Token by token, never raw, so
     * every generator — a tree builder included — receives ordinary JSON events.
     */
    class Serializer : StdSerializer<Map<String, Any?>>(Map::class.java, false) {
        override fun serialize(
            value: Map<String, Any?>,
            gen: JsonGenerator,
            provider: SerializerProvider,
        ) {
            val read = value as? FixtureInputs
            gen.writeStartObject()
            value.forEach { (name, member) ->
                gen.writeFieldName(name)
                val text = read?.exactText(name)
                if (text == null) {
                    provider.defaultSerializeValue(member, gen)
                } else {
                    FACTORY.createParser(text).use { parser ->
                        parser.nextToken()
                        gen.copyCurrentStructureExact(parser)
                    }
                }
            }
            gen.writeEndObject()
        }
    }

    private companion object {
        val FACTORY: JsonFactory = JsonFactory()
        val ORDINARY: ObjectMapper = ObjectMapper(FACTORY)
    }
}
