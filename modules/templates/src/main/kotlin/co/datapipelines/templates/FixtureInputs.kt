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
 * C2): the ordinary values every input has always had, plus every member occurrence's exact JSON
 * text, in the order it was written.
 *
 * Why both. A template's contract decides which input is the request `body`, and the contract is
 * not known while a request is parsed (`templates_evaluate` resolves the template afterwards; a save
 * may carry the contract beside the tests). The ordinary values are what a table or value input
 * has always received — a fraction as a `double`, a repeated member's last copy — and they stay
 * exactly that. A `body` input is instead decoded by the runner from the member's exact text with
 * the shared structured-body decoder, so its fractions stay exact, its member order is the author's
 * and a repeated member anywhere inside it, or the input named twice, is refused — the same
 * verdicts framework execute and `pipelines_execute` give the same fixture.
 *
 * The exact text is only as exact as the token stream it was copied from: a stream over raw JSON
 * text (a REST body, the text an MCP transport kept) carries every token; a stream over an
 * already-parsed tree or map carries what that parse kept. Surfaces that accept a body fixture
 * therefore bind [ScriptTestInput] from text.
 *
 * ## Wire and storage
 *
 * On the wire the object is written as it was read: every occurrence, in order, so a read-back
 * keeps a repeated input name. The stored `tests_json` is `jsonb`, which keeps a decimal but
 * reorders members and keeps one copy of a repeated name, at any depth. Storage therefore adds to
 * the `inputs` object one reserved member, [ENVELOPE_KEY], listing the exact text of every
 * occurrence of each body input of the contract. It is a name no input can have (an input name is a
 * lowercase identifier), it is written and read only in storage mode (a writer or reader holding
 * the attribute [STORAGE_BODY_INPUTS] or [STORAGE_READ]) — a request carrying it is an ordinary
 * unknown input — and it is part of the version's body hash, so a different order or spelling of a
 * body fixture is a different version. A version with no body input stores exactly what it always did.
 *
 * Equality and hashing are the ordinary map's, so two inputs with the same ordinary values compare
 * equal whatever their spelling; a test that cares about spelling reads [exactText], [occurrences]
 * and [isRepeated].
 */
class FixtureInputs private constructor(
    values: Map<String, Any?>,
    private val members: List<Pair<String, String>>,
) : LinkedHashMap<String, Any?>(values) {
    private val byName: Map<String, List<String>> = members.groupBy({ it.first }, { it.second })

    /** The exact JSON text of the last occurrence of member [name], or null when it was not read from a stream. */
    fun exactText(name: String): String? = byName[name]?.last()

    /** Every occurrence of member [name], exact and in the order written; empty when it was not read from a stream. */
    fun occurrences(name: String): List<String> = byName[name].orEmpty()

    /** True when the `inputs` object named [name] more than once — ambiguous for a body input. */
    fun isRepeated(name: String): Boolean = (byName[name]?.size ?: 0) > 1

    /** True when this map still holds exactly the members that were read, so the wire may replay every occurrence in order. */
    private fun isUnchangedRead(): Boolean = members.isNotEmpty() && keys == byName.keys

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
            val storage = ctxt.getAttribute(STORAGE_READ) == true
            val values = LinkedHashMap<String, Any?>()
            val members = ArrayList<Pair<String, String>>()
            var envelope: Map<String, List<String>>? = null
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                val name = p.currentName()
                p.nextToken()
                if (storage && name == ENVELOPE_KEY) {
                    envelope = readEnvelope(p, ctxt)
                    continue
                }
                val text = copyExact(p)
                members += name to text
                // The ordinary value is the same reading a plain untyped bind gives: a later copy wins.
                values[name] = ORDINARY.readValue(text, Any::class.java)
            }
            return FixtureInputs(values, envelope?.let { restore(values, members, it) } ?: members)
        }

        /** The member list with each enveloped body input's occurrences put back, and its ordinary value re-read. */
        private fun restore(
            values: MutableMap<String, Any?>,
            members: List<Pair<String, String>>,
            envelope: Map<String, List<String>>,
        ): List<Pair<String, String>> {
            val restored = ArrayList<Pair<String, String>>()
            members.map { it.first }.distinct().forEach { name ->
                val texts = envelope[name]?.takeIf { it.isNotEmpty() } ?: members.filter { it.first == name }.map { it.second }
                texts.forEach { restored += name to it }
                values[name] = ORDINARY.readValue(texts.last(), Any::class.java)
            }
            return restored
        }

        private fun readEnvelope(
            p: JsonParser,
            ctxt: DeserializationContext,
        ): Map<String, List<String>> {
            if (!p.isExpectedStartObjectToken) return malformed(ctxt, p)
            val out = LinkedHashMap<String, List<String>>()
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                val name = p.currentName()
                if (p.nextToken() != JsonToken.START_ARRAY) return malformed(ctxt, p)
                val texts = ArrayList<String>()
                while (p.nextToken() == JsonToken.VALUE_STRING) texts += p.text
                if (p.currentToken() != JsonToken.END_ARRAY) return malformed(ctxt, p)
                out[name] = texts
            }
            return out
        }

        private fun malformed(
            ctxt: DeserializationContext,
            p: JsonParser,
        ): Map<String, List<String>> {
            @Suppress("UNCHECKED_CAST") // handleUnexpectedToken throws for a stored envelope that is not names to string lists
            return ctxt.handleUnexpectedToken(Map::class.java, p) as Map<String, List<String>>
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
     *
     * Without [STORAGE_BODY_INPUTS] the wire form is written: every occurrence, in the order read.
     * With it, the storage form: each name once (the ordinary reading `jsonb` keeps) and the
     * [ENVELOPE_KEY] member for the named body inputs.
     */
    class Serializer : StdSerializer<Map<String, Any?>>(Map::class.java, false) {
        override fun serialize(
            value: Map<String, Any?>,
            gen: JsonGenerator,
            provider: SerializerProvider,
        ) {
            val read = value as? FixtureInputs
            val bodyNames = provider.getAttribute(STORAGE_BODY_INPUTS) as? Set<*>
            gen.writeStartObject()
            if (bodyNames == null && read?.isUnchangedRead() == true) {
                read.members.forEach { (name, text) ->
                    gen.writeFieldName(name)
                    copyText(text, gen)
                }
            } else {
                value.forEach { (name, member) ->
                    gen.writeFieldName(name)
                    val text = read?.exactText(name)
                    if (text == null) provider.defaultSerializeValue(member, gen) else copyText(text, gen)
                }
            }
            if (bodyNames != null && read != null) writeEnvelope(read, bodyNames, gen)
            gen.writeEndObject()
        }

        private fun writeEnvelope(
            read: FixtureInputs,
            bodyNames: Set<*>,
            gen: JsonGenerator,
        ) {
            val enveloped = read.keys.filter { it in bodyNames && read.occurrences(it).isNotEmpty() }
            if (enveloped.isEmpty()) return
            gen.writeObjectFieldStart(ENVELOPE_KEY)
            enveloped.forEach { name ->
                gen.writeArrayFieldStart(name)
                read.occurrences(name).forEach(gen::writeString)
                gen.writeEndArray()
            }
            gen.writeEndObject()
        }

        private fun copyText(
            text: String,
            gen: JsonGenerator,
        ) = FACTORY.createParser(text).use { parser ->
            parser.nextToken()
            gen.copyCurrentStructureExact(parser)
        }
    }

    companion object {
        /** The reserved member a stored `inputs` object carries for its body inputs' exact text. */
        const val ENVELOPE_KEY: String = "\$exact"

        /** Writer attribute: the contract's body input names; its presence selects the storage form. */
        const val STORAGE_BODY_INPUTS: String = "co.datapipelines.templates.fixture-inputs.storage-body-inputs"

        /** Reader attribute (true): bind a stored `inputs` object, restoring what [ENVELOPE_KEY] holds. */
        const val STORAGE_READ: String = "co.datapipelines.templates.fixture-inputs.storage-read"

        private val FACTORY: JsonFactory = JsonFactory()
        private val ORDINARY: ObjectMapper = ObjectMapper(FACTORY)
    }
}
