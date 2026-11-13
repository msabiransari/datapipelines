package co.datapipelines.web.dependencies

import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.wire.RefusalReason
import co.datapipelines.dependencies.wire.RequestField
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * One parsed tree request (dependency-graph record §11.1, as amended by erratum (8)(a)): the family is the ROUTE, so
 * only `name`, `id` and `version` are read from the query; a `family` field, or any other field, supplied anyway is
 * ignored. [lookupKey] is the family's own lookup key as its native validator canonicalised it (an API path without its
 * one `/api` prefix; every other family the value verbatim).
 */
data class DependenciesRequest(
    val family: FamilyKey,
    val lookupKey: String,
    val selector: Selector,
) {
    /** The version the request pins, or null for an entity-selected family (schedule, datasource). */
    val version: Int? get() = selector.number

    /** The walker's root request in [workspaceId] — the one address G1's providers resolve. */
    fun toObjectRequest(workspaceId: UUID): ObjectRequest =
        ObjectRequest(ObjectAddress(family, LookupScope(workspaceId), lookupKey), selector)

    /** What [parse] answers: a request, or the FIRST rule the query broke (field + reason, never the value). */
    sealed interface Parsed {
        data class Accepted(
            val request: DependenciesRequest,
        ) : Parsed

        data class Refused(
            val field: RequestField,
            val reason: RefusalReason,
        ) : Parsed
    }

    companion object {
        /** §11.1: "at most 200 characters, the longest native grammar" — counted in code points, checked before any grammar. */
        const val MAX_VALUE_LENGTH = 200

        private val READ_FIELDS = listOf(RequestField.NAME, RequestField.ID, RequestField.VERSION)
        private val VERSION_FORM = Regex("^[1-9][0-9]*$")
        private const val HEX = 16
        private const val ESCAPE_LENGTH = 3
        private const val HEX_LETTER_OFFSET = 10

        /** An ASCII hex digit's value, or -1 — never a non-ASCII digit `Character.digit` would accept. */
        private fun hexValue(c: Char?): Int =
            when (c) {
                null -> -1
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + HEX_LETTER_OFFSET
                in 'A'..'F' -> c - 'A' + HEX_LETTER_OFFSET
                else -> -1
            }

        /**
         * Parses the RAW query string [rawQuery] (still percent-encoded, as `HttpServletRequest.getQueryString` returns
         * it) for [row]'s family. The order is §A's and is the only order: (1) every read value is at most
         * [MAX_VALUE_LENGTH] code points of valid UTF-8 — a malformed escape or an undecodable byte sequence is "not
         * UTF-8"; (2) the selector shape fits the family; (3) the family's native validator admits the name or id;
         * (4) the version is canonical decimal in `1..Int.MAX_VALUE`. The first failure answers.
         *
         * The raw string is decoded here rather than through the servlet's parameter map because that map decodes with
         * a lenient charset (an undecodable byte becomes U+FFFD) — the §11.1 "non-UTF-8 → refused" rule would be
         * unobservable through it.
         */
        fun parse(
            row: FamilyTransportRow,
            rawQuery: String?,
        ): Parsed {
            val values = mutableMapOf<RequestField, MutableList<String?>>()
            for ((rawName, rawValue) in pairs(rawQuery)) {
                val field = READ_FIELDS.firstOrNull { decode(rawName) == it.wire } ?: continue
                values.getOrPut(field) { mutableListOf() } += decode(rawValue)
            }
            (lengthOrEncoding(values) ?: shape(row.selector, values))?.let { return it }
            val keyField = row.selector.keyField
            val key = single(values, keyField)
            val canonical = row.nativeKey.canonical(key) ?: return Parsed.Refused(keyField, row.selector.keyRefusal)
            val selector = selector(row.selector, values) ?: return refused(RequestField.VERSION, RefusalReason.VERSION_INVALID)
            return Parsed.Accepted(DependenciesRequest(row.family, canonical, selector))
        }

        /** The exact version of a versioned family (null = `version_invalid`), or the entity selector. */
        private fun selector(
            shape: SelectorShape,
            values: Map<RequestField, List<String?>>,
        ): Selector? =
            if (shape.versioned) {
                versionNumber(single(values, RequestField.VERSION))?.let(Selector::ExactVersion)
            } else {
                Selector.Entity
            }

        private fun lengthOrEncoding(values: Map<RequestField, List<String?>>): Parsed.Refused? =
            READ_FIELDS.firstNotNullOfOrNull { field ->
                val all = values[field].orEmpty()
                if (all.any { it == null || it.codePointCount(0, it.length) > MAX_VALUE_LENGTH }) {
                    refused(field, RefusalReason.VALUE_TOO_LONG)
                } else {
                    null
                }
            }

        private fun shape(
            shape: SelectorShape,
            values: Map<RequestField, List<String?>>,
        ): Parsed.Refused? {
            val present = values.filterValues { it.isNotEmpty() }.keys
            val forbidden =
                buildList {
                    if (shape.keyField == RequestField.NAME) add(RequestField.ID) else add(RequestField.NAME)
                    if (!shape.versioned) add(RequestField.VERSION)
                }
            forbidden.firstOrNull { it in present }?.let { return refused(it, RefusalReason.SELECTOR_INCOMPATIBLE) }
            if (shape.keyField !in present) return refused(shape.keyField, RefusalReason.SELECTOR_INCOMPATIBLE)
            if (shape.versioned && RequestField.VERSION !in present) return refused(RequestField.VERSION, RefusalReason.VERSION_INVALID)
            return READ_FIELDS.firstOrNull { values[it].orEmpty().size > 1 }?.let { refused(it, RefusalReason.SELECTOR_INCOMPATIBLE) }
        }

        private fun single(
            values: Map<RequestField, List<String?>>,
            field: RequestField,
        ): String = requireNotNull(values[field]?.singleOrNull()) { "the shape check admits exactly one ${field.wire}" }

        /** Canonical decimal only: no sign, no leading zero, no `current`, and within the 32-bit version column. */
        private fun versionNumber(value: String): Int? = value.takeIf { VERSION_FORM.matches(it) }?.toIntOrNull()

        private fun refused(
            field: RequestField,
            reason: RefusalReason,
        ) = Parsed.Refused(field, reason)

        private fun pairs(rawQuery: String?): List<Pair<String, String>> =
            rawQuery
                .orEmpty()
                .split('&')
                .filter { it.isNotEmpty() }
                .map { part ->
                    val eq = part.indexOf('=')
                    if (eq < 0) part to "" else part.substring(0, eq) to part.substring(eq + 1)
                }

        /**
         * `application/x-www-form-urlencoded` decoding (`+` is a space, `%XY` a byte) into STRICT UTF-8: null when an
         * escape is malformed or the bytes are not UTF-8 — never a replacement character.
         */
        internal fun decode(raw: String): String? {
            val bytes = ByteArrayOutputStream(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%') {
                    val high = hexValue(raw.getOrNull(i + 1))
                    val low = hexValue(raw.getOrNull(i + 2))
                    if (high < 0 || low < 0) return null
                    bytes.write(high * HEX + low)
                    i += ESCAPE_LENGTH
                } else {
                    val codePoint = if (c == '+') ' '.code else raw.codePointAt(i)
                    bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(codePoint)
                }
            }
            return try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
