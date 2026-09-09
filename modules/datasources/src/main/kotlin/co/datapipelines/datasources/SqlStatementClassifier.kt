package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect

/**
 * The probe SQL failed the [SqlStatementClassifier] gate. Module-local with a STATIC message on
 * purpose (the [DatasourceUnreachableException] precedent): this module may depend on
 * `typesystem` only, so it cannot raise a catalogued code — the calling surface maps the type.
 * The message never carries SQL text; [keyword] names the denylist entry that tripped, which is
 * one of a fixed set and therefore reveals nothing of the statement.
 */
class SqlProbeRefusalException(
    message: String,
    val keyword: String? = null,
) : RuntimeException(message)

/**
 * The read-only gate every [SqlProbe] statement passes through BEFORE anything is leased or
 * executed. There is deliberately no general SQL parser in this repo (see [DialectAdapter]'s
 * seam KDocs), so this is a conservative token scan with one job: admit exactly one
 * `SELECT`/`WITH` statement and nothing that can write, mutate session state, or shell out to
 * the engine.
 *
 * The scan strips line comments (`--`, and `#` on MySQL only — elsewhere `#` is an operator and
 * must stay a token), nested block comments, single- and double-quoted literals (MySQL
 * backslash escapes included), and Postgres/DuckDB dollar-quoted strings before looking at
 * words — so a denylist word inside a string or comment never trips the gate, and a `;` inside
 * one never counts as a statement separator.
 *
 * Refusals are conservative BY DESIGN: a column named `merge` trips the denylist, and a
 * statement-shaped trick the scanner cannot see through (a side-effecting function call such as
 * `pg_terminate_backend`) is NOT caught — the gate is a shape check, not a side-effect proof,
 * and the datasource's own DB-user privileges remain the last line (datasources.md §5.7).
 *
 * `INTO` sits in the denylist one step past a plain keyword list: `SELECT ... INTO t` is the
 * one statement that STARTS with `SELECT` and still writes (Postgres, MSSQL, MySQL).
 */
internal object SqlStatementClassifier {
    /**
     * Admits [sql] for probing and returns the statement text with its single trailing
     * semicolon (if any) removed — the text the probe actually prepares, since several drivers
     * refuse a trailing separator inside a prepared statement.
     *
     * @throws SqlProbeRefusalException when the text is not exactly one `SELECT`/`WITH`
     *   statement free of denylisted keywords.
     */
    fun classify(
        sql: String,
        dialect: Dialect,
    ): String {
        val tokens = scan(sql, dialect)
        refusalOf(tokens)?.let { throw it }
        return if (tokens.lastOrNull() == Token.Semicolon) sql.trim().removeSuffix(";").trim() else sql
    }

    /** The refusal [tokens] earns, or null when the gate passes — one throw point above. */
    private fun refusalOf(tokens: List<Token>): SqlProbeRefusalException? {
        val words = tokens.filterIsInstance<Token.Word>()
        val first = words.firstOrNull()
        val denied = words.firstOrNull { it.text.uppercase() in DENYLIST }
        val semicolons = tokens.count { it == Token.Semicolon }
        val multipleStatements = semicolons > 1 || (semicolons == 1 && tokens.lastOrNull() != Token.Semicolon)
        return when {
            first == null -> {
                SqlProbeRefusalException("The probe SQL holds no statement.")
            }

            !first.text.equals("SELECT", ignoreCase = true) && !first.text.equals("WITH", ignoreCase = true) -> {
                SqlProbeRefusalException("Only a SELECT or WITH statement is probeable.")
            }

            multipleStatements -> {
                SqlProbeRefusalException("Only a single statement is probeable.")
            }

            denied != null -> {
                SqlProbeRefusalException(
                    "The probe SQL contains the denied keyword ${denied.text.uppercase()}.",
                    keyword = denied.text.uppercase(),
                )
            }

            else -> {
                null
            }
        }
    }

    /**
     * The denied whole words, matched case-insensitively AFTER comment/string stripping. Every
     * entry is a statement or session verb that can write, mutate, or step outside the read
     * path; `EXPLAIN`/`ANALYZE` are denied because the probe wraps the statement itself and a
     * nested one would defeat the timebox accounting.
     */
    private val DENYLIST =
        (
            "INSERT UPDATE DELETE MERGE UPSERT REPLACE DROP CREATE ALTER TRUNCATE GRANT REVOKE DENY " +
                "CALL EXEC EXECUTE COPY ATTACH DETACH INSTALL LOAD PRAGMA SET USE VACUUM ANALYZE EXPLAIN " +
                "IMPORT EXPORT CHECKPOINT INTO"
        ).split(" ").toSet()

    /** What the scanner emits: words (identifier/keyword runs), statement separators, nothing else. */
    private sealed interface Token {
        data class Word(
            val text: String,
        ) : Token

        data object Semicolon : Token
    }

    private fun scan(
        sql: String,
        dialect: Dialect,
    ): List<Token> {
        val features = ScannerFeatures.forDialect(dialect)
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < sql.length) {
            i = step(sql, i, features, tokens)
        }
        return tokens
    }

    /** The dialect's lexical switches — the three places the fleet's SQL lexing actually differs. */
    private data class ScannerFeatures(
        val hashComments: Boolean,
        val backslashEscapes: Boolean,
        val dollarQuotes: Boolean,
    ) {
        companion object {
            fun forDialect(dialect: Dialect) =
                ScannerFeatures(
                    hashComments = dialect == Dialect.MYSQL,
                    backslashEscapes = dialect == Dialect.MYSQL,
                    dollarQuotes = dialect == Dialect.POSTGRES || dialect == Dialect.DUCKDB || dialect == Dialect.LAKE,
                )
        }
    }

    /** One scanner step: emit any token for the construct at [i] and return the next index. */
    private fun step(
        sql: String,
        i: Int,
        features: ScannerFeatures,
        tokens: MutableList<Token>,
    ): Int {
        val c = sql[i]
        return when {
            isLineCommentStart(sql, i, features.hashComments) -> {
                skipLineComment(sql, i)
            }

            isBlockCommentStart(sql, i) -> {
                skipBlockComment(sql, i)
            }

            c == '\'' || c == '"' -> {
                skipQuoted(sql, i, c, features.backslashEscapes)
            }

            c == '$' && features.dollarQuotes -> {
                skipDollarQuoted(sql, i)
            }

            c == ';' -> {
                tokens += Token.Semicolon
                i + 1
            }

            isWordStart(c) -> {
                val end = readWordEnd(sql, i)
                tokens += Token.Word(sql.substring(i, end))
                end
            }

            else -> {
                i + 1
            }
        }
    }

    private fun isLineCommentStart(
        sql: String,
        i: Int,
        hashComments: Boolean,
    ): Boolean = (sql[i] == '-' && sql.getOrNull(i + 1) == '-') || (hashComments && sql[i] == '#')

    private fun isBlockCommentStart(
        sql: String,
        i: Int,
    ): Boolean = sql[i] == '/' && sql.getOrNull(i + 1) == '*'

    /** The end index (exclusive) of the word starting at [start]. */
    private fun readWordEnd(
        sql: String,
        start: Int,
    ): Int {
        var end = start
        while (end < sql.length && isWordChar(sql[end])) end++
        return end
    }

    /**
     * Past a dollar-quoted string's closing tag (`$$…$$`, `$tag$…$tag$`), or one character past
     * the `$` when it opens no tag (a lone `$` is not a word character, so nothing is missed).
     * An unterminated body consumes the rest — the engine rejects the statement at prepare.
     */
    private fun skipDollarQuoted(
        sql: String,
        start: Int,
    ): Int {
        val tag = DOLLAR_TAG.matchAt(sql, start) ?: return start + 1
        val close = sql.indexOf(tag.value, start + tag.value.length)
        return if (close < 0) sql.length else close + tag.value.length
    }

    /** Word characters: identifiers and keywords — `$` continues one (Postgres allows it in names). */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun isWordStart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** Past the end of a `--`/`#` line comment (the newline or EOF). */
    private fun skipLineComment(
        sql: String,
        start: Int,
    ): Int {
        val newline = sql.indexOf('\n', start)
        return if (newline < 0) sql.length else newline + 1
    }

    /**
     * Past the end of a `/* */` block comment. Nesting is honoured (Postgres and DuckDB nest
     * block comments); an unterminated comment consumes the rest — the engine rejects the
     * statement at prepare, which the probe reports as an execution failure.
     */
    private fun skipBlockComment(
        sql: String,
        start: Int,
    ): Int {
        var depth = 1
        var i = start + 2
        while (i < sql.length && depth > 0) {
            when {
                sql[i] == '/' && sql.getOrNull(i + 1) == '*' -> {
                    depth++
                    i += 2
                }

                sql[i] == '*' && sql.getOrNull(i + 1) == '/' -> {
                    depth--
                    i += 2
                }

                else -> {
                    i++
                }
            }
        }
        return i
    }

    /** Past the end of a quoted literal; the quote character doubled is its escape. */
    private fun skipQuoted(
        sql: String,
        start: Int,
        quote: Char,
        backslashEscapes: Boolean,
    ): Int {
        var i = start + 1
        while (i < sql.length) {
            when {
                backslashEscapes && sql[i] == '\\' -> i += 2
                sql[i] == quote && sql.getOrNull(i + 1) == quote -> i += 2
                sql[i] == quote -> return i + 1
                else -> i++
            }
        }
        return sql.length
    }

    /** A dollar-quote opening tag at a `$`: `$$` or `$tag$` (tag starts with a letter/underscore). */
    private val DOLLAR_TAG = Regex("""\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$""")
}
