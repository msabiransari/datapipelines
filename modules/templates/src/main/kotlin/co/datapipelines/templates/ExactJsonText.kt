package co.datapipelines.templates

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.io.StringWriter

/**
 * Exact JSON text of one member of a request a surface has already parsed (#1038, review C2): the
 * text an authored fixture is bound from, so [FixtureInputs] sees every token the client wrote.
 */
object ExactJsonText {
    private val factory = JsonFactory()

    /**
     * The first JSON value of [text] copied exactly (numbers as written, repeated members kept), or
     * null for a text with no value. For a pane or field the caller has already parsed as JSON.
     */
    fun firstValue(text: String): String? =
        factory.createParser(text).use { parser ->
            if (parser.nextToken() == null) return null
            copyValue(parser)
        }

    /**
     * The exact text of the top-level member [name] of the JSON object [json] — the LAST copy when
     * the member repeats, which is the copy a parsed tree keeps — or null when [json] is not an
     * object or has no such member. Numbers are copied as written; repeated members inside the
     * value are copied as written too (the reader of the text judges them).
     */
    fun topLevelMember(
        json: String,
        name: String,
    ): String? {
        var found: String? = null
        factory.createParser(json).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return null
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val member = parser.currentName()
                parser.nextToken()
                if (member == name) found = copyValue(parser) else parser.skipChildren()
            }
        }
        return found
    }

    /**
     * The exact text of each element of the top-level array member [name] of the JSON object [json] —
     * the LAST such member, as a parsed tree keeps it — or null when [json] is not an object or has no
     * such array. The elements line up with the tree's, so an importer can bind each entry's fixtures
     * from text ([TemplateDeserializer.fromTree]).
     */
    fun arrayEntries(
        json: String,
        name: String,
    ): List<String>? {
        var found: List<String>? = null
        factory.createParser(json).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return null
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val member = parser.currentName()
                val first = parser.nextToken()
                if (member == name && first == JsonToken.START_ARRAY) found = copyElements(parser) else parser.skipChildren()
            }
        }
        return found
    }

    /** The exact text of each element of the array [parser] stands on the start of, copied token by token. */
    private fun copyElements(parser: JsonParser): List<String> {
        val entries = ArrayList<String>()
        while (parser.nextToken() != JsonToken.END_ARRAY) entries += copyValue(parser)
        return entries
    }

    /** The exact text of the value [parser] stands on, copied token by token. */
    private fun copyValue(parser: JsonParser): String {
        val out = StringWriter()
        factory.createGenerator(out).use { it.copyCurrentStructureExact(parser) }
        return out.toString()
    }
}
