package co.datapipelines.typesystem.body

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The one structured-body decoder (published-mutation requirement §3.2, §4, §5; #1038). Every
 * surface that accepts a body runs it — the published HTTP route over the raw entity ([decode]),
 * framework execute and MCP over the fixture embedded in their own request ([decodeEmbedded]) —
 * so the root rule, duplicate rule, depth cap and number handling cannot differ between them.
 *
 * It checks JSON syntax, strict UTF-8, duplicate member names, the [StructuredBodyLimits] budget
 * and the root type (an object, or an array of objects), and nothing else: no property schema,
 * no row count, no conversion. Business interpretation belongs to the pipeline's transformer.
 *
 * Numbers are kept exact ([StructuredBody]'s KDoc). A number whose exact value needs more than
 * [MAX_NUMBER_DIGITS] digits on either side of the point (`1e999999999`) is refused: a later
 * plain rendering of it would be unbounded.
 *
 * The decoder never closes the caller's stream and never puts a supplied value into a refusal.
 */
class StructuredBodyDecoder(
    val limits: StructuredBodyLimits = StructuredBodyLimits(),
) {
    private val factory: JsonFactory =
        JsonFactory
            .builder()
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .build()

    /**
     * Decodes one raw UTF-8 entity, counting its bytes as they are read (a chunked entity
     * included) and refusing past the byte cap without reading further. Zero bytes is
     * [StructuredBody.Absent]; whitespace alone is malformed.
     */
    fun decode(entity: InputStream): StructuredBodyDecoding {
        val counted = PushbackInputStream(CountingStream(entity, limits.maxBytes))
        return refusing(offsetOf = { it?.currentLocation()?.charOffset }) { hold ->
            val first = counted.read()
            if (first == -1) return@refusing StructuredBody.Absent
            counted.unread(first)
            val decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            factory.createParser(InputStreamReader(counted, decoder)).use { parser ->
                hold(parser)
                val body = readRoot(parser, parser.nextToken())
                if (parser.nextToken() != null) throw Refusal(StructuredBodyRefusalReason.MALFORMED)
                body
            }
        }
    }

    /** Decodes [bytes] as one raw UTF-8 entity — [decode] over an in-memory stream. */
    fun decode(bytes: ByteArray): StructuredBodyDecoding = decode(bytes.inputStream())

    /**
     * Decodes the fixture value [parser] is positioned ON (its current token is the value's first
     * token) — the form a framework or MCP request embeds beside its scalar parameters. On return
     * the parser rests on the value's last token. The byte cap applies to the decoded value's
     * canonical UTF-8 form, the depth cap to the value itself (not the enclosing request).
     */
    fun decodeEmbedded(parser: JsonParser): StructuredBodyDecoding =
        refusing(offsetOf = { parser.currentLocation()?.charOffset }) { _ ->
            val body = readRoot(parser, parser.currentToken())
            if (canonicalBytes(body) > limits.maxBytes) throw Refusal(StructuredBodyRefusalReason.TOO_MANY_BYTES)
            body
        }

    /**
     * Decodes [text] — one complete JSON value that a surface has already received inside its own
     * request (an MCP `request_body` argument, an authored `body` fixture of a template test case
     * or `templates_evaluate`) — as a fixture: the same syntax, strict-text, duplicate-member,
     * root, depth and number rules as [decode], with the byte cap applied to the decoded value's
     * canonical UTF-8 form exactly as [decodeEmbedded] applies it. One fixture therefore has one
     * byte verdict on every fixture surface, whatever spelling (`1.000…0` against `1`, spaces,
     * member order) the surface happened to carry. The enclosing request's own outer cap bounds
     * [text]; the raw published entity keeps [decode]'s streaming count. Trailing tokens are
     * malformed.
     */
    fun decodeFixture(text: String): StructuredBodyDecoding =
        refusing(offsetOf = { it?.currentLocation()?.charOffset }) { hold ->
            factory.createParser(text).use { parser ->
                hold(parser)
                val body = readRoot(parser, parser.nextToken())
                if (parser.nextToken() != null) throw Refusal(StructuredBodyRefusalReason.MALFORMED)
                if (canonicalBytes(body) > limits.maxBytes) throw Refusal(StructuredBodyRefusalReason.TOO_MANY_BYTES)
                body
            }
        }

    /** The UTF-8 length of [body]'s canonical form; 0 for [StructuredBody.Absent]. */
    private fun canonicalBytes(body: StructuredBody): Long =
        body
            .canonical()
            ?.toByteArray(StandardCharsets.UTF_8)
            ?.size
            ?.toLong() ?: 0L

    /**
     * Decodes the top-level [member] of the JSON object [json] as a fixture — the form framework
     * execute carries (`{"parameters": {...}, "request_body": ...}`). An absent member is
     * [StructuredBody.Absent]; a member given twice is [StructuredBodyRefusalReason.DUPLICATE_MEMBER]
     * (a lenient reader would keep the last copy silently); `null` is an invalid root, never absence.
     * The enclosing request's other members are skipped unread.
     */
    fun decodeMember(
        json: String,
        member: String,
    ): StructuredBodyDecoding {
        var found: StructuredBodyDecoding = StructuredBodyDecoding.Decoded(StructuredBody.Absent)
        val outer =
            refusing(offsetOf = { it?.currentLocation()?.charOffset }) { hold ->
                factory.createParser(json).use { parser ->
                    hold(parser)
                    if (parser.nextToken() != JsonToken.START_OBJECT) throw Refusal(StructuredBodyRefusalReason.MALFORMED)
                    var seen = false
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        val name = parser.currentName()
                        parser.nextToken()
                        if (name != member) {
                            parser.skipChildren()
                            continue
                        }
                        if (seen) throw Refusal(StructuredBodyRefusalReason.DUPLICATE_MEMBER)
                        seen = true
                        found = decodeEmbedded(parser)
                        if (found is StructuredBodyDecoding.Refused) return@refusing StructuredBody.Absent
                    }
                    StructuredBody.Absent
                }
            }
        return outer as? StructuredBodyDecoding.Refused ?: found
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    // Every failure maps to one bounded reason; the cause's message may quote supplied text.
    private inline fun refusing(
        offsetOf: (JsonParser?) -> Long?,
        block: ((JsonParser) -> Unit) -> StructuredBody,
    ): StructuredBodyDecoding {
        var parser: JsonParser? = null
        val reason =
            try {
                return StructuredBodyDecoding.Decoded(block { parser = it })
            } catch (err: Refusal) {
                return StructuredBodyDecoding.Refused(err.detail.copy(offset = offsetOf(parser)))
            } catch (err: TooManyBytes) {
                StructuredBodyRefusalReason.TOO_MANY_BYTES
            } catch (err: CharacterCodingException) {
                StructuredBodyRefusalReason.INVALID_ENCODING
            } catch (err: StreamConstraintsException) {
                StructuredBodyRefusalReason.TOKEN_TOO_LARGE
            } catch (err: com.fasterxml.jackson.core.JacksonException) {
                (err.cause as? TooManyBytes)?.let { StructuredBodyRefusalReason.TOO_MANY_BYTES }
                    ?: (err.cause as? CharacterCodingException)?.let { StructuredBodyRefusalReason.INVALID_ENCODING }
                    ?: StructuredBodyRefusalReason.MALFORMED
            }
        return StructuredBodyDecoding.Refused(StructuredBodyRefusal(reason, offsetOf(parser)))
    }

    /** The root rule, then the tree — iterative, so the depth cap is the only bound that matters. */
    private fun readRoot(
        parser: JsonParser,
        first: JsonToken?,
    ): StructuredBody =
        when (first) {
            JsonToken.START_OBJECT -> StructuredBody.ObjectRoot(members(readTree(parser, first, rootArray = false)))
            JsonToken.START_ARRAY -> StructuredBody.ArrayRoot(objectItems(readTree(parser, first, rootArray = true)))
            null -> throw Refusal(StructuredBodyRefusalReason.MALFORMED)
            else -> throw Refusal(StructuredBodyRefusalReason.INVALID_ROOT)
        }

    @Suppress("UNCHECKED_CAST") // a tree opened by START_OBJECT closes as the frame's LinkedHashMap
    private fun members(tree: Any?): Map<String, Any?> = tree as Map<String, Any?>

    /**
     * A root array's elements were each checked as they completed: a scalar or null element is
     * refused at once, a container element when it closes (its contents pass the duplicate and
     * depth rules first), so only objects reach here.
     */
    @Suppress("UNCHECKED_CAST")
    private fun objectItems(tree: Any?): List<Map<String, Any?>> = tree as List<Map<String, Any?>>

    private sealed interface Frame {
        /** The finished container. */
        val value: Any?

        class Obj(
            val members: LinkedHashMap<String, Any?> = LinkedHashMap(),
            var name: String? = null,
        ) : Frame {
            override val value: Any? get() = members
        }

        class Arr(
            val items: ArrayList<Any?> = ArrayList(),
        ) : Frame {
            override val value: Any? get() = items
        }
    }

    /** Walks one value with an explicit stack; [rootArray] applies the root array's element rule. */
    private fun readTree(
        parser: JsonParser,
        first: JsonToken,
        rootArray: Boolean,
    ): Any? {
        val stack = ArrayDeque<Frame>()
        var token: JsonToken? = first
        while (true) {
            when (token) {
                JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                    open(stack, token)
                }

                JsonToken.FIELD_NAME -> {
                    nameMember(stack.last() as Frame.Obj, parser.currentName())
                }

                else -> {
                    val closing = token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY
                    val value = if (closing) stack.removeLast().value else scalarOf(token, parser)
                    val parent = stack.lastOrNull() ?: return value
                    attach(parent, value, rootElement = rootArray && stack.size == 1)
                }
            }
            token = parser.nextToken()
        }
    }

    private fun open(
        stack: ArrayDeque<Frame>,
        token: JsonToken,
    ) {
        if (stack.size >= limits.maxDepth) throw Refusal(StructuredBodyRefusalReason.TOO_DEEP)
        stack.addLast(if (token == JsonToken.START_OBJECT) Frame.Obj() else Frame.Arr())
    }

    private fun nameMember(
        obj: Frame.Obj,
        rawName: String,
    ) {
        val name = checkedText(rawName)
        if (obj.members.containsKey(name)) throw Refusal(StructuredBodyRefusalReason.DUPLICATE_MEMBER)
        obj.name = name
    }

    private fun attach(
        parent: Frame,
        value: Any?,
        rootElement: Boolean,
    ) {
        when (parent) {
            is Frame.Obj -> {
                parent.members[requireNotNull(parent.name) { "a member value arrived without its name" }] = value
                parent.name = null
            }

            is Frame.Arr -> {
                if (rootElement && value !is Map<*, *>) {
                    throw Refusal(StructuredBodyRefusalReason.INVALID_ROOT, index = parent.items.size)
                }
                parent.items.add(value)
            }
        }
    }

    private fun scalarOf(
        token: JsonToken?,
        parser: JsonParser,
    ): Any? =
        when (token) {
            JsonToken.VALUE_STRING -> checkedText(parser.text)
            JsonToken.VALUE_NUMBER_INT -> integerOf(parser)
            JsonToken.VALUE_NUMBER_FLOAT -> decimalOf(parser.decimalValue)
            JsonToken.VALUE_TRUE -> true
            JsonToken.VALUE_FALSE -> false
            JsonToken.VALUE_NULL -> null
            else -> throw Refusal(StructuredBodyRefusalReason.MALFORMED)
        }

    /** An integer token as a Long when it fits, else its exact BigInteger (token length is the parser's bound). */
    private fun integerOf(parser: JsonParser): Number =
        when (parser.numberType) {
            JsonParser.NumberType.INT, JsonParser.NumberType.LONG -> parser.longValue
            else -> parser.bigIntegerValue
        }

    private fun decimalOf(value: BigDecimal): BigDecimal {
        val integerDigits = value.precision() - value.scale()
        if (value.scale() > MAX_NUMBER_DIGITS || integerDigits > MAX_NUMBER_DIGITS) {
            throw Refusal(StructuredBodyRefusalReason.NUMBER_OUT_OF_RANGE)
        }
        return value
    }

    /** Refuses a string or name holding an unpaired surrogate — it has no UTF-8 form. */
    private fun checkedText(text: String): String {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) {
                    throw Refusal(StructuredBodyRefusalReason.INVALID_ENCODING)
                }
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) throw Refusal(StructuredBodyRefusalReason.INVALID_ENCODING)
            i++
        }
        return text
    }

    /** A refusal the tree walk raises; caught in [refusing] and never escapes the decoder. */
    private class Refusal(
        reason: StructuredBodyRefusalReason,
        index: Int? = null,
    ) : RuntimeException(reason.wire, null, false, false) {
        val detail = StructuredBodyRefusal(reason, index = index)
    }

    /** The byte cap firing inside a read — an IOException so it passes through the parser unchanged. */
    private class TooManyBytes : IOException("the body passed its byte cap")

    /** Counts every byte read from [source] and refuses the first read past [maxBytes]. */
    private class CountingStream(
        private val source: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val b = source.read()
            if (b >= 0) charge(1)
            return b
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            // Never ask for more than one byte past the cap: the overrun is detected without
            // draining an unbounded entity.
            val allowed = (maxBytes - count + 1).coerceAtMost(length.toLong()).toInt().coerceAtLeast(1)
            val n = source.read(buffer, offset, allowed)
            if (n > 0) charge(n.toLong())
            return n
        }

        private fun charge(n: Long) {
            count += n
            if (count > maxBytes) throw TooManyBytes()
        }
    }

    companion object {
        /** The most digits a decoded number's exact value may need on either side of the point. */
        const val MAX_NUMBER_DIGITS: Int = 1000
    }
}
