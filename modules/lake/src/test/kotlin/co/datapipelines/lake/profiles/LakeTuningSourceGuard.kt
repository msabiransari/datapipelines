package co.datapipelines.lake.profiles

/** One Kotlin source: its repository-relative path and its text. */
internal data class GuardSource(
    val path: String,
    val text: String,
)

/** A rule broken at [path]: [rule] is one of `a` to `e`, [what] the token that broke it. */
internal data class GuardViolation(
    val rule: Char,
    val path: String,
    val what: String,
)

/** One admitted token in one file (relative to `co/datapipelines/lake/`), with why it is not a tuning value. */
internal data class Admitted(
    val file: String,
    val token: String,
    val reason: String,
)

/**
 * The five rules of `LakeTuningSourceGuardTest` (record M8, brief 956 C.2) over a set of sources. A source scan, not a
 * data-flow analysis: what it cannot see is listed in the test's KDoc and in lake.md "Profiles". Scoped code is every
 * file under `modules/lake/src/main/kotlin/co/datapipelines/lake/append/` or `.../maintenance/`, sub-packages included.
 */
internal object LakeTuningSourceGuard {
    const val LAKE = "modules/lake/src/main/kotlin/co/datapipelines/lake/"

    /**
     * The allowlist, one entry per line (later P6 lanes add theirs): protocol and safety bounds, unit conversions,
     * format, grammar and codec facts, never a tuning value. Rule (a) matches the token inside the offending line;
     * rules (b) and (c) match it exactly against the referenced `Owner.NAME` or the read.
     */
    val ALLOWLIST: List<Admitted> =
        listOf(
            Admitted("append/AppendColumnCheck.kt", "MAX_PRECISION = 38", "the largest decimal precision: a format bound"),
            Admitted("append/AppendColumnCheck.kt", "UNSIGNED_LONG_PRECISION = 20", "digits of the unsigned 64-bit maximum: a type fact"),
            Admitted("append/AppendCommitter.kt", "MAX_ATTEMPTS = 3", "commit attempts: a §8 safety rule (call 35)"),
            Admitted("append/AppendValues.kt", "NANOS_PER_MICRO = 1_000", "a unit conversion"),
            Admitted("append/CommitClassifier.kt", "MAX_CHAIN = 16", "cause-chain walk bound: a safety bound"),
            Admitted("append/LakeAppendAutoConfiguration.kt", "STATEMENT_TIMEOUT_SECONDS = 5", "a statement timeout: protocol"),
            Admitted("append/LakeBoundedJdbcTemplate.kt", "SOCKET_SLACK_SECONDS = 5", "socket slack over the statement timeout"),
            Admitted("append/LakeBoundedJdbcTemplate.kt", "MILLIS_PER_SECOND = 1_000", "a unit conversion"),
            Admitted("append/ManagedLakeAppendPort.kt", "parts.size != 2", "a target is `namespace.table`: two parts, a grammar fact"),
            Admitted("append/RecipeWriter.kt", "PRECONDITION_FAILED = 412", "a failed conditional PUT: protocol"),
            Admitted("append/ParquetAppendWriter.kt", "TableProperties.PARQUET_COMPRESSION", "SETS the codec, zstd (code, call 35)"),
            Admitted("append/ParquetAppendWriter.kt", "TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES", "SETS it from the profile"),
            Admitted("append/LakeWritebackRunner.kt", "LakeApplyLeases.APPLY_LEASE_TTL", "the table lease's TTL: lease protocol"),
            Admitted("append/LakeWritebackRunner.kt", "LakeApplyLeases.DEFAULT_POLL_INTERVAL", "the busy lease's poll: lease protocol"),
            Admitted("append/AppendRecipeAssembler.kt", ".properties()[\"dp.definition.version\"]", "the definition identity"),
            Admitted("maintenance/remove/LakeObjectDeleter.kt", "MAX_BATCH = 1000", "S3 DeleteObjects per-request maximum: protocol"),
            Admitted("maintenance/remove/ClockSkewProbe.kt", "Duration.ofSeconds(3_600)", "the record's skew bound (call 33): safety"),
        )

    /** The LakeProperties groups scoped code may read (rule c), each with why it is not a profile value. */
    val ADMITTED_GROUPS: List<Admitted> =
        listOf(
            Admitted("*", "properties.admission", "server-wide admission capacity: configuration until the ingestion design"),
            Admitted("*", "properties.storage", "where the lake's bucket is: deployment, not tuning"),
            Admitted("*", "properties.catalog", "catalog lock and lease timeouts: protocol (record call 35)"),
            Admitted("*", "properties.name", "the managed lake's datasource name"),
            Admitted("*", "properties.retention", "protection days and orphan grace: one value per deployment (rule e)"),
        )

    /** The files rule (d) admits: the start-up seed, its wiring and the boot rule. */
    val SEED_READERS: Set<String> =
        setOf(
            "${LAKE}profiles/LakeProfileSeed.kt",
            "${LAKE}profiles/LakeProfilesAutoConfiguration.kt",
            "modules/app/src/main/kotlin/co/datapipelines/config/LakeRules.kt",
        )

    /** The roots rule (d) scans. */
    val SEED_ROOTS: List<String> = listOf("modules/lake/src/main/", "modules/web/src/main/", "modules/app/src/main/")

    private val SCOPED = Regex("""^${Regex.escape(LAKE)}(append|maintenance)/""")
    private val MAINTENANCE = Regex("""^${Regex.escape(LAKE)}maintenance/""")
    private val NUMBER = Regex("""(?<![\w.])(0[xX][0-9a-fA-F_]+[uUlL]*|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?[uUlLfF]*)(?![\w.])""")
    private val TRIVIAL = setOf("0", "1", "0L", "1L", "0.0", "1.0")
    private val NUMERIC_VAL =
        Regex("""\b(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*[\w.<>?, ]+?)?\s*=\s*(?:-?\d|(?:java\.time\.)?Duration\.|DataSize\.)""")
    private val TYPE_DECLARATION = Regex("""\b(?:class|object|interface)\s+([A-Z]\w*)""")
    private val DECLARATION_START =
        Regex("""\n\s*(?:(?:private|internal|public|data|enum|sealed|abstract|open)\s+)*(?:class|object|interface|fun|val|var)\b""")
    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val HEADER_LINE = Regex("""^\s*(?:package|import)\s.*$""", RegexOption.MULTILINE)
    private val TABLE_PROPERTY = Regex("""\.properties\(\)(?:\[\s*"[^"]*"\s*])?|\bPropertyUtil\b|\bTableProperties\.[A-Z_]+""")
    private val LAKE_GROUP = Regex("""\b(?:lakeProperties|properties)\.([a-z]\w*)""")
    private val SEED_MEMBER = Regex("""\.maintenance\b(?!\s*[:(])|\bmaintenance\.(?:window|maxBytesRewrittenPerRun)\b""")
    private val SEED_KEY =
        Regex("""(?:lake|PREFIX)\.maintenance\.(?:window|max-bytes-rewritten-per-run)|LAKE_MAINTENANCE_(?:WINDOW|MAX_BYTES)""")
    private val RETENTION_READ = Regex("""([\w.]*)\.(protectionDays|orphanGraceHours)\b""")
    private val RETENTION_KEY = Regex("""protection_days|orphan_grace_hours""")
    private val FROM_PROPERTIES = Regex("""(?:^|\.)\w*[pP]roperties\.retention$""")

    fun violations(sources: List<GuardSource>): List<GuardViolation> {
        val scoped = sources.filter { SCOPED.containsMatchIn(it.path) }
        return scoped.flatMap { literals(it) } +
            constantReferences(sources, scoped) +
            scoped.flatMap { tuningReads(it) } +
            seedReaders(sources) +
            sources.filter { MAINTENANCE.containsMatchIn(it.path) }.flatMap { retention(it) }
    }

    /** (a) A numeric literal other than 0 and 1 in scoped code, unless its line holds an allowlisted token. */
    private fun literals(source: GuardSource): List<GuardViolation> =
        KotlinText.code(source.text).lines().flatMap { line ->
            NUMBER
                .findAll(line)
                .map { it.groupValues[1] }
                .filter { it !in TRIVIAL && !admittedIn(source.path, line) }
                .map { GuardViolation('a', source.path, "$it in `${line.trim()}`") }
                .toList()
        }

    /**
     * (b) Scoped code referring to a numeric constant declared outside `append/` and `maintenance/`, anywhere in the
     * given sources (`profiles/` included): `Owner.NAME`, `Owner.Companion.NAME` or an import of it.
     */
    private fun constantReferences(
        sources: List<GuardSource>,
        scoped: List<GuardSource>,
    ): List<GuardViolation> {
        val declared = sources.filter { !SCOPED.containsMatchIn(it.path) }.flatMap { declaredConstants(it) }
        return scoped.flatMap { source ->
            val code = KotlinText.code(source.text)
            declared
                .filter { constant -> code.contains(constant.name) && constant.patterns.any { it.containsMatchIn(code) } }
                .filter { constant -> !admittedExactly(source.path, constant.qualified) }
                .map { GuardViolation('b', source.path, "${it.qualified} (declared in ${it.path})") }
        }
    }

    /** (c) Scoped code reading an Iceberg table property or a LakeProperties group the allowlist does not name. */
    private fun tuningReads(source: GuardSource): List<GuardViolation> {
        val iceberg =
            TABLE_PROPERTY
                .findAll(KotlinText.withoutComments(source.text))
                .map { it.value }
                .filter { !admittedExactly(source.path, it) }
        val groups =
            LAKE_GROUP
                .findAll(KotlinText.code(source.text))
                .map { "properties.${it.groupValues[1]}" }
                .filter { group -> ADMITTED_GROUPS.none { it.token == group } }
        return (iceberg + groups).distinct().map { GuardViolation('c', source.path, it) }.toList()
    }

    /** (d) Anything but the seed, its wiring and the boot rule reading the two `maintenance.*` values. */
    private fun seedReaders(sources: List<GuardSource>): List<GuardViolation> =
        sources
            .filter { source -> SEED_ROOTS.any { source.path.startsWith(it) } && source.path !in SEED_READERS }
            .flatMap { source ->
                val members = SEED_MEMBER.findAll(HEADER_LINE.replace(KotlinText.code(source.text), "")).map { it.value }
                val keys = SEED_KEY.findAll(KotlinText.withoutComments(source.text)).map { it.value }
                (members + keys).distinct().map { GuardViolation('d', source.path, it) }.toList()
            }

    /**
     * (e) Code under `maintenance/` reading protection days or the orphan grace from anything but LakeProperties: a
     * read is admitted only as `properties.retention.<value>` (any `...Properties` receiver) or through a name the file
     * types `Retention`; the old policy keys in a string are a read of a store that no longer holds them.
     */
    private fun retention(source: GuardSource): List<GuardViolation> {
        val code = KotlinText.code(source.text)
        val reads =
            RETENTION_READ
                .findAll(code)
                .filter { !fromProperties(it.groupValues[1], code) }
                .map { it.value }
        val keys = RETENTION_KEY.findAll(KotlinText.withoutComments(source.text)).map { it.value }
        return (reads + keys).distinct().map { GuardViolation('e', source.path, it) }.toList()
    }

    private fun fromProperties(
        receiver: String,
        code: String,
    ): Boolean {
        if (FROM_PROPERTIES.containsMatchIn(receiver)) return true
        val name = receiver.substringAfterLast('.')
        return name.isNotEmpty() && Regex("""\b${Regex.escape(name)}\s*:\s*(?:LakeProperties\.)?Retention\b""").containsMatchIn(code)
    }

    private fun admittedIn(
        path: String,
        line: String,
    ): Boolean = ALLOWLIST.any { path == LAKE + it.file && line.contains(it.token) }

    private fun admittedExactly(
        path: String,
        token: String,
    ): Boolean = ALLOWLIST.any { path == LAKE + it.file && token == it.token }

    private data class DeclaredConstant(
        val path: String,
        val name: String,
        val qualified: String,
        val patterns: List<Regex>,
    )

    private fun declaredConstants(source: GuardSource): List<DeclaredConstant> {
        val code = KotlinText.code(source.text)
        val pkg = PACKAGE.find(source.text)?.groupValues?.get(1) ?: return emptyList()
        val owners = TYPE_DECLARATION.findAll(code).map { it.groupValues[1] to body(code, it.range.last + 1) }.toList()
        return NUMERIC_VAL
            .findAll(code)
            .map { match ->
                val name = match.groupValues[1]
                val owner =
                    owners
                        .filter { (_, body) -> match.range.first in body }
                        .minByOrNull { (_, body) -> body.last - body.first }
                        ?.first
                val patterns =
                    owners.map { (type, _) -> Regex("""\b${Regex.escape(type)}(?:\.Companion)?\.$name\b""") } +
                        Regex("""\bimport\s+${Regex.escape(pkg)}(?:\.\w+)*\.$name\b""")
                DeclaredConstant(source.path, name, if (owner == null) name else "$owner.$name", patterns)
            }.distinctBy { it.qualified }
            .toList()
    }

    /**
     * The body of the type declared just before [from]: its first brace outside parentheses, to the matching one; an
     * empty range when the declaration has no body (a newline at depth 0 followed by another declaration ends it).
     */
    private fun body(
        code: String,
        from: Int,
    ): IntRange {
        var parens = 0
        var i = from
        while (i < code.length) {
            val c = code[i]
            when {
                c == '(' -> parens++
                c == ')' -> parens--
                parens == 0 && c == '{' -> return i..closing(code, i)
                parens == 0 && DECLARATION_START.matchesAt(code, i) -> return IntRange.EMPTY
            }
            i++
        }
        return IntRange.EMPTY
    }

    private fun closing(
        code: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}' && --depth == 0) return i
        }
        return code.length - 1
    }
}

/** Kotlin text with its comments (and, for [code], its string contents) blanked; line structure kept. */
internal object KotlinText {
    fun code(text: String): String = strip(text, keepStrings = false)

    fun withoutComments(text: String): String = strip(text, keepStrings = true)

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun strip(
        text: String,
        keepStrings: Boolean,
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    i = skipBlockComment(text, i, out)
                }

                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }

                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + RAW_QUOTE).let { if (it < 0) text.length else it + RAW_QUOTE }
                    val literal = text.substring(i, end)
                    out.append(if (keepStrings) literal else "\"\"" + "\n".repeat(literal.count { it == '\n' }))
                    i = end
                }

                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    var j = i + 1
                    while (j < text.length && text[j] != quote && text[j] != '\n') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, text.length)
                    out.append(if (keepStrings) text.substring(i, end) else "$quote$quote")
                    i = end
                }

                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Skips a block comment starting at [start] (Kotlin block comments nest), keeping its newlines; returns the next index. */
    private fun skipBlockComment(
        text: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }

                text.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }

                else -> {
                    if (text[i] == '\n') out.append('\n')
                    i++
                }
            }
        }
        return i
    }

    private const val RAW_QUOTE = 3
}
