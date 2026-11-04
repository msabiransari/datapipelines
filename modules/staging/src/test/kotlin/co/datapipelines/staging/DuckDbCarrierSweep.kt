package co.datapipelines.staging

import org.duckdb.DuckDBConnection
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.text.Normalizer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * The engine side of the carrier tripwires in DuckDbCarrierCatalogTest (#795 round two, review F2).
 * Each sweep asks what the PINNED ENGINE runs, so the texts go to raw duplicates of the staging
 * database's locked root — exactly the connection an author lease wraps — with the gate deliberately
 * not in front. The gate is then asked about the texts the engine ran.
 */

/** A staging database opened by the real factory, with its locked root captured through the open seam. */
internal class EngineUnderTest(
    scratch: Path,
) : AutoCloseable {
    private var captured: Connection? = null
    private val factory =
        DuckDbStagingFactory(duckProps(scratch), ScratchBudget(duckProps(scratch).scratchCapacityBytes)) { url, props ->
            DriverManager.getConnection(url, props).also { captured = it }
        }
    val staging: DuckDbStaging = factory.open()
    private val root: DuckDBConnection =
        requireNotNull(captured) { "the factory opened no root connection" }.unwrap(DuckDBConnection::class.java)

    init {
        // query_table needs a table to read: one row, so a resolved call succeeds.
        root.duplicate().use { c -> c.createStatement().use { it.execute("CREATE TABLE t AS SELECT 1 AS x") } }
    }

    /**
     * The items whose text the engine runs, in input order, swept over [threads] duplicates of the
     * locked root. "Runs" means the statement prepared, executed and delivered its first row
     * without an SQLException; any refusal (parse, bind, catalog) counts as not run.
     */
    fun <T> accepted(
        items: List<T>,
        threads: Int,
        text: (T) -> String,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val parts =
                (0 until threads).map { worker ->
                    pool.submit(
                        Callable {
                            root.duplicate().use { c -> items.indices.filter { it % threads == worker && runs(c, text(items[it])) } }
                        },
                    )
                }
            return parts.flatMap { it.get(SWEEP_TIMEOUT_MINUTES, TimeUnit.MINUTES) }.sorted().map { items[it] }
        } finally {
            pool.shutdownNow()
        }
    }

    /** True when the engine runs [sql] on a fresh duplicate — a positive control's question. */
    fun runs(sql: String): Boolean = root.duplicate().use { runs(it, sql) }

    override fun close() = staging.close()

    private fun runs(
        connection: Connection,
        sql: String,
    ): Boolean =
        try {
            connection.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next() } }
            true
        } catch (_: SQLException) {
            false
        }

    private companion object {
        const val SWEEP_TIMEOUT_MINUTES = 5L
    }
}

/** One sweep text: [carrier] (optionally double-quoted), then the code point, then its argument. */
internal data class CarrierForm(
    val carrier: String,
    val quoted: Boolean,
    val codePoint: Int,
) {
    val text: String
        get() {
            val name = if (quoted) "\"$carrier\"" else carrier
            val argument = if (carrier == "query") "('SELECT 1')" else "('t')"
            return "SELECT * FROM $name${String(Character.toChars(codePoint))}$argument"
        }

    val label: String get() = "%s%s U+%04X".format(carrier, if (quoted) " quoted" else "", codePoint)
}

/** Every code point from U+0001 to [last], surrogates skipped. */
internal fun codePointsUpTo(last: Int): List<Int> = (1..last).filterNot { it in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code }

/**
 * The six names the gate exists for, each with an argument list that SUCCEEDS when the name
 * resolves — so a spelling the engine resolved shows up as "ran", never as a refusal for a reason
 * unrelated to the name. Logging is enabled in memory only (lane rule: never a file storage).
 */
internal val GUARDED_CALLS: Map<String, String> =
    linkedMapOf(
        "query" to "('SELECT 1')",
        "query_table" to "('t')",
        "truncate_duckdb_logs" to "()",
        "disable_logging" to "()",
        "enable_logging" to "(level = 'info', storage = 'memory')",
        "json_execute_serialized_sql" to "(json_serialize_sql('SELECT 1'))",
    )

/**
 * Class B: every non-ASCII code point whose NFKC form, NFKD base, upper case or lower case is a
 * single ASCII letter, substituted at each position of each guarded name holding that letter
 * (case-insensitively).
 */
internal fun letterVariants(): List<Pair<String, String>> {
    val variants = mutableListOf<Pair<String, String>>()
    codePointsUpTo(Character.MAX_CODE_POINT).filter { it >= ASCII_END }.forEach { cp ->
        val letters = asciiLettersOf(cp)
        if (letters.isEmpty()) return@forEach
        val glyph = String(Character.toChars(cp))
        GUARDED_CALLS.forEach { (name, args) ->
            name.forEachIndexed { i, ch ->
                if (ch in letters) {
                    val label = "%s[%d]=U+%04X".format(name, i, cp)
                    variants += label to "SELECT * FROM ${name.substring(0, i)}$glyph${name.substring(i + 1)}$args"
                }
            }
        }
    }
    return variants
}

/**
 * Class D: every non-ASCII Unicode space separator, format character and control character up to
 * U+2FFFF (a superset of the 18 non-ASCII separators the engine accepts), inserted at every inner
 * position of each guarded name.
 */
internal fun separatorsInsideNames(): List<Pair<String, String>> {
    val separators = codePointsUpTo(SWEEP_LAST_CODE_POINT).filter { it >= ASCII_END && isSeparatorClass(it) }
    return separators.flatMap { cp ->
        val glyph = String(Character.toChars(cp))
        GUARDED_CALLS.flatMap { (name, args) ->
            (1 until name.length).map { i ->
                "%s+%d U+%04X".format(name, i, cp) to "SELECT * FROM ${name.substring(0, i)}$glyph${name.substring(i)}$args"
            }
        }
    }
}

/** The general categories the gate's separator class names: Z (space separators, lines, paragraphs), Cf, Cc. */
internal fun isSeparatorClass(cp: Int): Boolean =
    when (Character.getType(cp).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT, Character.CONTROL -> true
        else -> false
    }

private fun asciiLettersOf(cp: Int): Set<Char> {
    val glyph = String(Character.toChars(cp))
    val decomposed = Normalizer.normalize(glyph, Normalizer.Form.NFKD)
    val nfkdBase = if (decomposed.isEmpty()) decomposed else decomposed.substring(0, Character.charCount(decomposed.codePointAt(0)))
    return listOf(
        Normalizer.normalize(glyph, Normalizer.Form.NFKC),
        nfkdBase,
        String(Character.toChars(Character.toUpperCase(cp))),
        String(Character.toChars(Character.toLowerCase(cp))),
    ).mapNotNull { mapped -> mapped.singleOrNull()?.takeIf { it in 'a'..'z' || it in 'A'..'Z' }?.lowercaseChar() }.toSet()
}

/** The last code point the separator sweep covers (review F2: at least U+0001–U+2FFFF). */
internal const val SWEEP_LAST_CODE_POINT = 0x2FFFF

private const val ASCII_END = 0x80
