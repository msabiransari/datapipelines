package co.datapipelines.mcp

import io.modelcontextprotocol.spec.McpError
import java.util.Base64

/**
 * The `resources/list` cursor (mcp-server.md §7.3).
 *
 * §7.3 is normative: the cursor is an **opaque server-issued token** clients must not parse,
 * construct or persist. It is encoded here as unpadded base64url of `{"k":"<kind>","o":<offset>}`
 * — opaque to a client, decodable by this server, and cheap. A cursor the server cannot decode is
 * JSON-RPC `-32602 invalid params`, exactly as §7.3 requires.
 *
 * There is deliberately no signature or expiry: the token addresses a position in a public
 * enumeration order, carries no authority (the listing is re-filtered by the caller's scope on
 * every page) and is therefore not a capability.
 */
data class McpResourceCursor(
    val kind: String,
    val offset: Int,
) {
    fun encode(): String = ENCODER.encodeToString("""{"k":"$kind","o":$offset}""".toByteArray(Charsets.UTF_8))

    companion object {
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()
        private val SHAPE = Regex("""\{"k":"([a-z]+)","o":(\d+)}""")

        /** The first page: no cursor. */
        fun first(kinds: List<String>): McpResourceCursor = McpResourceCursor(kinds.first(), 0)

        /**
         * Decodes a client-supplied cursor.
         *
         * @throws McpError `-32602` when the token is not one this server issued (§7.3).
         */
        fun decode(
            token: String,
            kinds: List<String>,
        ): McpResourceCursor = parse(token, kinds) ?: throw invalid(token)

        /** The decode attempt, as a nullable — one rejection point, one throw site. */
        private fun parse(
            token: String,
            kinds: List<String>,
        ): McpResourceCursor? {
            val decoded = runCatching { String(DECODER.decode(token), Charsets.UTF_8) }.getOrNull() ?: return null
            val match = SHAPE.matchEntire(decoded) ?: return null
            val kind = match.groupValues[1]
            val offset = match.groupValues[2].toIntOrNull()
            return if (kind in kinds && offset != null) McpResourceCursor(kind, offset) else null
        }

        private fun invalid(token: String): McpError =
            McpArguments.invalidParams("cursor '${token.take(MAX_ECHOED_CHARS)}' is not a cursor this server issued.")

        /** A rejected cursor is echoed truncated — it is unbounded caller input. */
        private const val MAX_ECHOED_CHARS = 32
    }
}
