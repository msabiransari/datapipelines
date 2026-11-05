package co.datapipelines.config

/**
 * Source policy behind the final #461 exemptions. Scan every main source/resource and lockfile,
 * not today's sender filenames. SSE sources cannot mix view types with emitters; sends must build
 * an event inline, with Jackson-serialized JSON or a constant comment. An opaque builder/helper
 * requires review rather than silently inheriting the exemption. This is a conservative lexical
 * audit, not whole-program data-flow analysis. Import aliases and qualified type names are covered.
 */
internal object SpringAdvisoryPolicy {
    private val XSLT = Regex("""(?i)\b(?:XsltView(?:Resolver)?|XSLT)\b|<\s*xsl:(?:stylesheet|transform)\b|\btext/xsl\b""")
    private val VIEW = Regex("""\b(?:ModelAndView|FragmentsRendering)\b""")
    private val WEBFLUX = Regex("""(?mi)^\s*[^:#\s]+:[^:\s=]*webflux[^:\s=]*:""")
    private val ALIAS = Regex("""\btypealias\s+(\w+)\s*=\s*(?:[\w]+\.)*(\w+)""")
    private val IMPORT_ALIAS = Regex("""\bimport\s+(?:[\w]+\.)*(SseEmitter|ResponseBodyEmitter)\s+as\s+(\w+)""")
    private val CONSTANT = Regex("""(?:[\w]+\.)*[A-Z][A-Z_0-9]*""")
    private val JSON = Regex("""(?:org\.springframework\.http\.)?MediaType\.APPLICATION_JSON""")
    private val SERIALIZE = Regex("""[\w.]+\.writeValueAsString\s*\(""")

    fun xsltViolations(files: List<AdvisorySource>): List<String> =
        files.flatMap { file ->
            val extension = file.path.substringAfterLast('.').lowercase()
            if (extension in setOf("xsl", "xslt")) {
                listOf("${file.path}: XSLT resource invalidates #461")
            } else {
                XSLT
                    .findAll(file.text)
                    .map { "${file.path}:${line(file.text, it.range.first)}: XSLT invalidates #461" }
                    .distinct()
                    .toList()
            }
        }

    fun webfluxViolations(files: List<AdvisorySource>): List<String> =
        files.flatMap { file ->
            WEBFLUX.findAll(file.text).map { "${file.path}:${line(file.text, it.range.first)}: WebFlux invalidates #461" }.toList()
        }

    fun sseAudit(files: List<AdvisorySource>): SseSourceAudit {
        val types = emitterTypes(files)
        val type = Regex("\\b(?:${types.joinToString("|") { Regex.escape(it) }})\\b")
        val event = Regex("(?:[\\w]+\\.)*(?:${types.joinToString("|") { Regex.escape(it) }})\\s*\\.\\s*event\\s*\\(\\s*\\)")
        val violations = mutableListOf<String>()
        var sends = 0
        var data = 0
        files.filter { it.path.endsWith(".kt") || it.path.endsWith(".java") }.forEach { file ->
            val code = codeOnly(file.text)
            if (!type.containsMatchIn(code)) return@forEach
            VIEW.findAll(code).forEach {
                violations += "${file.path}:${line(code, it.range.first)}: view type in SSE source invalidates #461"
            }
            calls(code, "send").forEach { call ->
                sends++
                val body = call.arguments.singleOrNull().orEmpty()
                val payloads = calls(body, "data")
                data += payloads.size
                val comments = calls(body, "comment")
                val json = payloads.size == 1 && comments.isEmpty() && serializedJson(payloads.single().arguments)
                val comment =
                    payloads.isEmpty() && comments.size == 1 &&
                        comments
                            .single()
                            .arguments
                            .singleOrNull()
                            ?.matches(CONSTANT) == true
                if (!inlineEvent(body, event) || (!json && !comment)) {
                    violations += "${file.path}:${line(code, call.start)}: SSE send must carry serialized JSON or a constant comment (#461)"
                }
            }
        }
        return SseSourceAudit(sends, data, violations)
    }

    private fun serializedJson(arguments: List<String>): Boolean {
        if (arguments.size != 2 || !arguments[1].matches(JSON)) return false
        val value = arguments[0]
        val serializer = SERIALIZE.find(value) ?: return false
        return serializer.range.first == 0 && closingParen(value, serializer.range.last) == value.lastIndex
    }

    private fun emitterTypes(files: List<AdvisorySource>): Set<String> {
        val types = mutableSetOf("SseEmitter", "ResponseBodyEmitter")
        val sources = files.filter { it.path.endsWith(".kt") || it.path.endsWith(".java") }.map { codeOnly(it.text) }
        sources.flatMap { IMPORT_ALIAS.findAll(it).toList() }.forEach { types += it.groupValues[2] }
        val aliases = sources.flatMap { ALIAS.findAll(it).toList() }
        do {
            val before = types.size
            aliases.filter { it.groupValues[2] in types }.forEach { types += it.groupValues[1] }
        } while (types.size != before)
        return types
    }

    private fun inlineEvent(
        body: String,
        event: Regex,
    ): Boolean {
        val start = event.find(body)?.takeIf { it.range.first == 0 } ?: return false
        var cursor = start.range.last + 1
        val member = Regex("""\s*\.\s*(?:name|id|data|comment|reconnectTime)\s*\(""")
        while (body.substring(cursor).isNotBlank()) {
            val next = member.find(body, cursor)?.takeIf { it.range.first == cursor } ?: return false
            cursor = closingParen(body, next.range.last) + 1
        }
        return true
    }

    private fun calls(
        code: String,
        name: String,
    ): List<SourceCall> =
        Regex("\\.\\s*$name\\s*\\(")
            .findAll(code)
            .mapNotNull { match ->
                if (Regex("""\bfun\s+$""").containsMatchIn(code.substring(0, match.range.first))) return@mapNotNull null
                val open = match.range.last
                val close = closingParen(code, open)
                SourceCall(match.range.first, arguments(code.substring(open + 1, close)))
            }.toList()

    private fun closingParen(
        code: String,
        open: Int,
    ): Int {
        var depth = 1
        var cursor = open + 1
        while (cursor < code.length) {
            when (code[cursor]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return cursor
            }
            cursor++
        }
        error("Unclosed source call at $open")
    }

    private fun arguments(body: String): List<String> {
        var depth = 0
        var start = 0
        val result = mutableListOf<String>()
        body.forEachIndexed { index, char ->
            when (char) {
                '(', '[', '{' -> {
                    depth++
                }

                ')', ']', '}' -> {
                    depth--
                }

                ',' -> {
                    if (depth == 0) {
                        result += body.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
        }
        body
            .substring(start)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { result += it }
        return result
    }

    /** The house source-audit lexer: mask comments and literals, retaining offsets and newlines. */
    private fun codeOnly(source: String): String {
        val code = source.toCharArray()
        val starts = Regex("//|/\\*|\"\"\"|\"|'")
        var cursor = 0
        while (cursor < source.length) {
            val match = starts.find(source, cursor) ?: break
            val start = match.range.first
            val end = tokenEnd(source, start, match.value)
            for (index in start until end) if (code[index] != '\n') code[index] = ' '
            if (!match.value.startsWith('/')) code[start] = '_'
            cursor = end
        }
        return String(code)
    }

    private fun tokenEnd(
        source: String,
        start: Int,
        token: String,
    ): Int =
        when (token) {
            "//" -> {
                source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
            }

            "/*" -> {
                var depth = 1
                var cursor = start + token.length
                val delimiters = Regex("/\\*|\\*/")
                while (depth > 0) {
                    val next = delimiters.find(source, cursor) ?: error("Unclosed source comment")
                    depth += if (next.value == "/*") 1 else -1
                    cursor = next.range.last + 1
                }
                cursor
            }

            "\"\"\"" -> {
                source.indexOf(token, start + token.length).takeIf { it >= 0 }?.plus(token.length) ?: source.length
            }

            else -> {
                var cursor = start + 1
                while (cursor < source.length && source[cursor].toString() != token) {
                    cursor += if (source[cursor] == '\\') 2 else 1
                }
                (cursor + 1).coerceAtMost(source.length)
            }
        }

    private fun line(
        text: String,
        offset: Int,
    ): Int = text.take(offset).count { it == '\n' } + 1

    private data class SourceCall(
        val start: Int,
        val arguments: List<String>,
    )
}

internal data class AdvisorySource(
    val path: String,
    val text: String,
)

internal data class SseSourceAudit(
    val sends: Int,
    val data: Int,
    val violations: List<String>,
)
