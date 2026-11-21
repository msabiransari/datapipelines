package co.datapipelines.mcp

import co.datapipelines.pipeline.RequestLimits
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerationException
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import java.io.StringWriter

/**
 * The transport mapper's guard for the structured request body (#1038, review R1): the one point
 * where a `pipelines_execute` call's `request_body` is still the client's own text.
 *
 * The SDK parses a JSON-RPC message into plain `Map`/`List` values before any tool runs. A lenient
 * parse keeps the LAST of a repeated member silently and rounds a fraction to a `double`, so a body
 * that framework execute and the published codec would refuse or keep exactly reached the
 * transformer altered. This wrapper reads the same text once more, with a parser that sees every
 * token, and does two things for the one member `params.arguments.request_body` only:
 *
 * 1. **Refuses a repeated member** anywhere inside the value (and a repeated `request_body`,
 *    `arguments` or `params` member that would make the value ambiguous) by throwing before the
 *    message becomes a request — no tool, execution, reservation or write follows. The refusal
 *    names no member and no value.
 * 2. **Restores the value exactly**: the subtree is rebuilt with every fraction as a
 *    `BigDecimal` and put back in place of the lossy one. Integers were already exact.
 *
 * Every other member of every message is left exactly as the delegate read it — other tools'
 * arguments keep their lenient, double-valued behaviour; nothing outside the body changes.
 */
internal class ExactRequestBodyJsonMapper(
    private val delegate: McpJsonMapper,
    private val factory: JsonFactory = RequestLimits.jsonFactory(),
) : McpJsonMapper by delegate {
    private val exact: ObjectMapper = ObjectMapper(factory).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    override fun <T> readValue(
        content: String,
        type: Class<T>,
    ): T = restored(content, delegate.readValue(content, type))

    override fun <T> readValue(
        content: ByteArray,
        type: Class<T>,
    ): T = restored(content.decodeToString(), delegate.readValue(content, type))

    override fun <T> readValue(
        content: String,
        type: TypeRef<T>,
    ): T = restored(content, delegate.readValue(content, type))

    override fun <T> readValue(
        content: ByteArray,
        type: TypeRef<T>,
    ): T = restored(content.decodeToString(), delegate.readValue(content, type))

    /** [value] with its request body replaced by the exact one, or [value] itself when the message has none. */
    private fun <T> restored(
        text: String,
        value: T,
    ): T {
        val body = bodyOf(text) ?: return value

        @Suppress("UNCHECKED_CAST")
        val params = (value as? MutableMap<String, Any?>)?.get("params") as? MutableMap<String, Any?> ?: return value

        @Suppress("UNCHECKED_CAST")
        val arguments = params["arguments"] as? MutableMap<String, Any?> ?: return value
        arguments[REQUEST_BODY] = exact.readValue(body, Any::class.java)
        return value
    }

    /** The exact text of the one `params.arguments.request_body` value, or null when there is none. */
    private fun bodyOf(text: String): String? {
        val found = Found()
        factory.createParser(text).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return null
            eachMember(parser, PARAMS) {
                found.params++
                eachMember(parser, ARGUMENTS) {
                    found.arguments++
                    eachMember(parser, REQUEST_BODY, anyToken = true) { found.bodies += copyExact(parser) }
                }
            }
        }
        if (found.bodies.isEmpty()) return null
        if (found.bodies.size > 1 || found.params > 1 || found.arguments > 1) throw refused()
        return found.bodies.single()
    }

    private class Found {
        var params = 0
        var arguments = 0
        val bodies = mutableListOf<String>()
    }

    /**
     * Walks the members of the object [parser] is on, skipping every member but [target]; [onTarget]
     * runs with the parser on the target's first token. A container target must be an object unless
     * [anyToken] (the request body may be any JSON value — the decoder judges its root).
     */
    private fun eachMember(
        parser: JsonParser,
        target: String,
        anyToken: Boolean = false,
        onTarget: () -> Unit,
    ) {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName()
            parser.nextToken()
            if (name == target && (anyToken || parser.currentToken == JsonToken.START_OBJECT)) onTarget() else parser.skipChildren()
        }
    }

    /** The value [parser] is on, as exact JSON text; a repeated member inside it is the generator's refusal. */
    @Suppress("SwallowedException") // the generator's message names the member: refused value-free
    private fun copyExact(parser: JsonParser): String {
        val out = StringWriter()
        try {
            factory.createGenerator(out).use { generator ->
                generator.enable(JsonGenerator.Feature.STRICT_DUPLICATE_DETECTION)
                generator.copyCurrentStructureExact(parser)
            }
        } catch (err: JsonGenerationException) {
            throw refused()
        }
        return out.toString()
    }

    private fun refused(): JsonProcessingException = RepeatedRequestBodyMember()

    /** The refusal; its message carries no member name or value. */
    private class RepeatedRequestBodyMember : JsonProcessingException("request_body repeats a member")

    private companion object {
        const val PARAMS = "params"
        const val ARGUMENTS = "arguments"
        const val REQUEST_BODY = "request_body"
    }
}
