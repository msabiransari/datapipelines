package co.datapipelines.config

/**
 * The guard's pure core: no file system, no catalog file — the test hands it rows, catalog and files (#826).
 *
 * Each [PinRow] pins one engine and classifies ITS OWN literals: equal to its catalog pin (or its [PinRow.short]
 * form — DuckDB's three-part core; the other engines' full version) is current; a provenance note — a measurement
 * word and a date in the literal's window, or a dated Change Log row — stays true forever as written; anything
 * else is that row's red. At a bump of one row, red is exactly that row's current list.
 *
 * Ambiguity: when two or more rows claim a literal with the same (path, line, version), a context pattern of one
 * of them capturing that version ON THAT LINE resolves the claim to that row (the engine named in the line owns
 * it). With no context on the line: members that are all provenance-dated or allow-owned agree on their outcome
 * and keep it; otherwise the literal is red for every claiming row and listed in [PinReport.ambiguous] — the fix
 * is wording the line to name the engine, never admitting the literal.
 *
 * Detection does not depend on knowing the old version: a row's context patterns capture every version the tree
 * attaches to its engine (`duckdb_jdbc 1.5.5.1`, `DuckDB v1.5.5`, the ARG, the tag URLs), and a second pass finds
 * every bare occurrence of a captured version (`measured on 1.5.5.1`). Limit: a bare literal of a version that no
 * context line names anywhere outside the allowlist is invisible.
 *
 * The window is the literal's paragraph — the run of lines with text once comment markers are stripped — bounded
 * to [WINDOW] lines above and below; a table row, in Markdown or inside a comment (a KDoc `* | … |`), is its own
 * window. An allow row that owns nothing is reported by identity ([PinReport.staleAllow]), not by its reason.
 */
internal object PinDrift {
    private const val WINDOW = 4
    private val WORD = Regex("""(?i)\b(?:verified|probed|measured)\b""")
    private val DATE = Regex("""\b20\d\d-\d\d-\d\d\b""")
    private val DATED_ROW = Regex("""^\s*\|\s*20\d\d-\d\d-\d\d\s*\|""")
    private val COMMENT = Regex("""^(?://+|#+|/\*+|\*+/|\*+|<!--|-->|--)""")

    fun scan(
        rows: List<PinRow>,
        catalog: Map<String, String>,
        files: List<TrackedFile>,
        allow: List<AllowRow>,
    ): PinReport {
        val lines = files.associate { it.path to it.text.split('\n') }
        val versions = mutableSetOf<String>()
        val rendered = mutableListOf<String>()
        val claims = mutableListOf<Claim>()
        rows.forEach { row ->
            val pinned = checkNotNull(catalog[row.alias]) { "no catalog version for ${row.alias}" }
            val rowVersions =
                setOf(pinned, row.short(pinned)) +
                    lines.flatMap { (path, text) ->
                        text.filter { line -> allow.none { it.owns(path, line) } }.flatMap { captures(row, it) }
                    }
            versions += rowVersions
            val alternatives = rowVersions.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            claimsFor(row, Regex("""(?<![0-9.])v?($alternatives)(?![0-9]|\.[0-9])"""), lines, allow, claims, rendered)
        }
        val (literals, ambiguous) = resolveClaims(claims, lines)
        val free = literals.filter { it.second == null }.map { it.first }
        val provenance = free.filter { provenanced(lines.getValue(it.path), it.line - 1) }
        val rest = free - provenance.toSet()
        val admittedByRow =
            rows.associate { row -> row.alias to catalog.getValue(row.alias).let { pin -> setOf(pin, row.short(pin)) } }
        return PinReport(
            scanned = files.size,
            versions = versions,
            literals = literals.map { it.first },
            literalCountByRow = rows.associate { row -> row.alias to literals.count { (literal, _) -> literal.alias == row.alias } },
            red = rest.filter { it.version !in admittedByRow.getValue(it.alias) },
            provenance = provenance,
            current = rest.filter { it.version in admittedByRow.getValue(it.alias) },
            ambiguous = ambiguous,
            allowed = literals.mapNotNull { (literal, owner) -> owner?.let { it.reason to literal } }.groupBy({ it.first }, { it.second }),
            staleAllow = allow.filter { row -> literals.none { (_, owner) -> owner === row } },
            rendered = rendered.distinct(),
        )
    }

    /** Every claim one row makes: bare matches of its alternatives, plus an owned line's own context captures. */
    private fun claimsFor(
        row: PinRow,
        bare: Regex,
        lines: Map<String, List<String>>,
        allow: List<AllowRow>,
        claims: MutableList<Claim>,
        rendered: MutableList<String>,
    ) {
        lines.forEach { (path, text) ->
            text.forEachIndexed { index, line ->
                val owner = allow.firstOrNull { it.owns(path, line) }
                val contextVersions = captures(row, line).toSet()
                val found =
                    bare.findAll(line).map { it.groupValues[1] }.toSet() +
                        (if (owner != null) contextVersions else emptySet())
                found.forEach { version ->
                    claims += Claim(Literal(path, index + 1, version, line, row.alias), owner, version in contextVersions)
                }
                if (path.contains("/src/main/") && row.renderedBy?.containsMatchIn(line) == true) {
                    rendered += "$path:${index + 1} (rendered by ${row.alias})"
                }
            }
        }
    }

    /** Context lines resolve a multi-row group to their row; without context every claiming row must agree on its outcome. */
    private fun resolveClaims(
        claims: List<Claim>,
        lines: Map<String, List<String>>,
    ): Pair<MutableList<Pair<Literal, AllowRow?>>, MutableList<String>> {
        val literals = mutableListOf<Pair<Literal, AllowRow?>>()
        val ambiguous = mutableListOf<String>()
        claims.groupBy { Triple(it.literal.path, it.literal.line, it.literal.version) }.forEach { (_, members) ->
            val resolved = members.filter { it.onContext }
            val chosen =
                when {
                    resolved.isNotEmpty() -> {
                        resolved
                    }

                    members.size == 1 ||
                        members.all { it.owner != null || provenanced(lines.getValue(it.literal.path), it.literal.line - 1) } -> {
                        members
                    }

                    else -> {
                        ambiguous +=
                            "${members.first().literal.path}:${members.first().literal.line}: " +
                            "${members.first().literal.version} — ambiguous: rows " +
                            members
                                .map { it.literal.alias }
                                .distinct()
                                .sorted()
                                .joinToString(", ") +
                            " all claim it and no context on the line names the engine — reword the line to name it"
                        members
                    }
                }
            chosen.forEach { literals += it.literal to it.owner }
        }
        return literals to ambiguous
    }

    private fun captures(
        row: PinRow,
        line: String,
    ): Sequence<String> = row.contexts.asSequence().flatMap { pattern -> pattern.findAll(line).map { it.groupValues[1] } }

    /**
     * A dated Change Log row, or a measurement word and a date in the literal's bounded paragraph. The comment
     * marker is stripped BEFORE the table-row test, so a KDoc or `#`-comment table row (`* | DUCKDB | … |`) is
     * judged as a row — by its own cells — never by the dated row beside it (#826 review).
     */
    private fun provenanced(
        lines: List<String>,
        index: Int,
    ): Boolean {
        val line = lines[index].trim().replace(COMMENT, "").trimStart()
        if (line.startsWith("|")) return DATED_ROW.containsMatchIn(line) || (WORD in line && DATE in line)
        var low = index
        while (low > 0 && index - low < WINDOW && hasText(lines[low - 1])) low--
        var high = index
        while (high < lines.lastIndex && high - index < WINDOW && hasText(lines[high + 1])) high++
        val window = lines.subList(low, high + 1).joinToString("\n")
        return WORD in window && DATE in window
    }

    private fun hasText(line: String): Boolean = line.trim().replace(COMMENT, "").isNotBlank()

    private operator fun Regex.contains(text: String): Boolean = containsMatchIn(text)

    /** One version literal one row found, with whether a context pattern of that row caught it on its own line. */
    private data class Claim(
        val literal: Literal,
        val owner: AllowRow?,
        val onContext: Boolean,
    )
}

/**
 * One pinned engine: its catalog alias, the patterns whose group 1 captures a version it names, its short form,
 * and — if it has a rendered token — the pattern matching statements rendered from it.
 */
internal data class PinRow(
    val alias: String,
    val contexts: List<Regex>,
    val short: (String) -> String,
    val renderedBy: Regex? = null,
)

/**
 * A file (and, optionally, only its lines matching [line]) whose literals another owner keeps true, with the reason.
 *
 * Staleness is per ROW OBJECT, never per reason: many rows share one reason (every in-flight lane's rows do), and a
 * row that owns nothing is stale even while a sibling with the same reason still owns a literal.
 */
internal data class AllowRow(
    val path: Regex,
    val reason: String,
    val line: Regex? = null,
) {
    fun owns(
        file: String,
        text: String,
    ): Boolean = path.containsMatchIn(file) && (line?.containsMatchIn(text) ?: true)

    /** `<path>[:<line>] — <reason>`: the row as a reader finds it in the allowlist. */
    override fun toString(): String = "${path.pattern}${line?.let { ":${it.pattern}" }.orEmpty()} — $reason"
}

internal data class TrackedFile(
    val path: String,
    val text: String,
)

internal data class Literal(
    val path: String,
    val line: Int,
    val version: String,
    val text: String,
    val alias: String,
) {
    override fun toString(): String = "$path:$line: $version — ${text.trim().take(TEXT_CHARS)}"

    private companion object {
        const val TEXT_CHARS = 120
    }
}

internal data class PinReport(
    val scanned: Int,
    val versions: Set<String>,
    val literals: List<Literal>,
    val literalCountByRow: Map<String, Int>,
    val red: List<Literal>,
    val provenance: List<Literal>,
    val current: List<Literal>,
    val ambiguous: List<String>,
    val allowed: Map<String, List<Literal>>,
    val staleAllow: List<AllowRow>,
    val rendered: List<String>,
) {
    /** The re-probe list a bump starts from: dated notes, then the pages that render the token. */
    fun render(): String =
        buildString {
            append("Pin drift: $scanned files, ${literals.size} literals of $versions; ")
            append(
                literalCountByRow.entries.joinToString(prefix = "by row: ", postfix = "; ") { (alias, count) -> "$alias $count" },
            )
            append("${current.size} current, ${provenance.size} provenance, ${allowed.values.sumOf { it.size }} owned, ${red.size} red\n")
            append("RE-PROBE at a bump — provenance notes (${provenance.size}):\n")
            provenance.forEach { append("  $it\n") }
            append("RE-PROBE at a bump — statements rendered from a token (${rendered.size}):\n")
            rendered.forEach { append("  $it\n") }
            ambiguous.forEach { append("AMBIGUOUS $it\n") }
            allowed.forEach { (reason, owned) -> append("OWNED ${owned.size}: $reason\n") }
            staleAllow.forEach { append("STALE $it\n") }
            red.forEach { append("RED $it\n") }
        }
}
