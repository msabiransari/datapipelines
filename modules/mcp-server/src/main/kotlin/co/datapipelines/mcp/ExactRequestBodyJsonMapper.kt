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
 * 3. **Keeps authored fixtures' exact text** (review C2): the exact JSON text of a call's
 *    `arguments.input` (`templates_evaluate`) and `arguments.tests` (`templates_create`,
 *    `templates_update`) is put into the request's `params._meta` under [EXACT_ARGUMENTS], where
 *    [McpToolDispatcher] hands it to the tool as [McpArguments.exactText]. Which `inputs` member is
 *    a template's `body` input is only known once the tool resolves the contract, so nothing is
 *    refused or changed here: the tool binds the fixture from the text, the runner decodes a
 *    `body` input exactly and refuses its repeated members, and every other input keeps the
 *    ordinary reading. A client-supplied [EXACT_ARGUMENTS] entry is always removed first, so the
 *    only text a tool sees under it is this mapper's copy of the same message. A repeated `input`
 *    or `tests` argument keeps its last copy, as the ordinary reading does.
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

    /**
     * [value] with its request body replaced by the exact one and its authored fixtures' exact text
     * under `params._meta`, or [value] itself when the message has neither.
     */
    private fun <T> restored(
        text: String,
        value: T,
    ): T {
        @Suppress("UNCHECKED_CAST")
        val params = (value as? MutableMap<String, Any?>)?.get(PARAMS) as? MutableMap<String, Any?> ?: return value

        @Suppress("UNCHECKED_CAST")
        val meta = params[META] as? MutableMap<String, Any?>
        // Only this mapper writes the entry: a client's own copy never reaches a tool.
        meta?.remove(EXACT_ARGUMENTS)
        val found = scan(text) ?: return value

        @Suppress("UNCHECKED_CAST")
        val arguments = params[ARGUMENTS] as? MutableMap<String, Any?> ?: return value
        found.body()?.let { arguments[REQUEST_BODY] = exact.readValue(it, Any::class.java) }
        if (found.fixtures.isNotEmpty() && (meta != null || params[META] == null)) {
            val target = meta ?: LinkedHashMap<String, Any?>().also { params[META] = it }
            target[EXACT_ARGUMENTS] = LinkedHashMap(found.fixtures)
        }
        return value
    }

    /** What the message's text holds under `params.arguments`, or null when it is not an object. */
    private fun scan(text: String): Found? {
        val found = Found()
        factory.createParser(text).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return null
            eachMember(parser, setOf(PARAMS)) { _ ->
                found.params++
                eachMember(parser, setOf(ARGUMENTS)) { _ ->
                    found.arguments++
                    eachMember(parser, ARGUMENT_TARGETS, anyToken = true) { name ->
                        if (name == REQUEST_BODY) {
                            found.bodies += copyExact(parser, strict = true)
                        } else if (parser.currentToken.isStructStart) {
                            found.fixtures[name] = copyExact(parser, strict = false)
                        } else {
                            parser.skipChildren()
                        }
                    }
                }
            }
        }
        return found
    }

    private inner class Found {
        var params = 0
        var arguments = 0
        val bodies = mutableListOf<String>()
        val fixtures = LinkedHashMap<String, String>()

        /** The exact text of the one `request_body`, or null; a repeated one (or an ambiguous enclosure) is refused. */
        fun body(): String? {
            if (bodies.isEmpty()) return null
            if (bodies.size > 1 || params > 1 || arguments > 1) throw refused()
            return bodies.single()
        }
    }

    /**
     * Walks the members of the object [parser] is on, skipping every member not in [targets];
     * [onTarget] runs with the parser on the target's first token and must consume the value. A
     * container target must be an object unless [anyToken] (the request body may be any JSON
     * value — the decoder judges its root).
     */
    private fun eachMember(
        parser: JsonParser,
        targets: Set<String>,
        anyToken: Boolean = false,
        onTarget: (String) -> Unit,
    ) {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            val name = parser.currentName()
            parser.nextToken()
            if (name in targets && (anyToken || parser.currentToken == JsonToken.START_OBJECT)) onTarget(name) else parser.skipChildren()
        }
    }

    /**
     * The value [parser] is on, as exact JSON text. With [strict], a repeated member inside it is the
     * generator's refusal; without, every member is copied as written (the runner judges it).
     */
    @Suppress("SwallowedException") // the generator's message names the member: refused value-free
    private fun copyExact(
        parser: JsonParser,
        strict: Boolean,
    ): String {
        val out = StringWriter()
        try {
            factory.createGenerator(out).use { generator ->
                if (strict) generator.enable(JsonGenerator.Feature.STRICT_DUPLICATE_DETECTION)
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

    companion object {
        /**
         * The `params._meta` entry holding authored fixtures' exact text: an object from argument
         * name (`input`, `tests`) to that argument's exact JSON text. Namespaced, so it cannot be
         * mistaken for a protocol field.
         */
        const val EXACT_ARGUMENTS = "co.datapipelines/exact-arguments"

        private const val PARAMS = "params"
        private const val ARGUMENTS = "arguments"
        private const val META = "_meta"
        private const val REQUEST_BODY = "request_body"

        /** The arguments whose exact text this mapper keeps: the authored fixture carriers. */
        private val FIXTURE_ARGUMENTS = setOf("input", "tests")
        private val ARGUMENT_TARGETS = FIXTURE_ARGUMENTS + REQUEST_BODY
    }
}
