package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.ColumnInfo
import java.security.MessageDigest

/**
 * Design §3.2 — the shape a fact was learned against, so drift can be DETECTED rather than
 * guessed (D-S6).
 *
 * Per referenced table: SHA-256 over the sorted `(column, canonical type)` list as introspection
 * returns it. The STORED value concatenates the per-table digests as sorted `tableKey=hex`
 * entries rather than hashing them once more — a join fact references two tables, and the
 * read-time check (§6) recomputes only the table whose columns it just read, so each table's
 * digest must stay addressable. Hashing the concatenation would make a two-table fact
 * un-checkable from either table's listing alone.
 */
object SchemaFingerprint {
    private const val ENTRY_SEPARATOR = ";"
    private const val KEY_SEPARATOR = "="

    /** One table's digest: sorted `name\ttype` lines, joined, SHA-256, lowercase hex. */
    fun of(columns: List<ColumnInfo>): String {
        val canonical = columns.map { "${it.column.name}\t${it.column.type.wire}" }.sorted().joinToString("\n")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** The stored form: every referenced table's digest, sorted by key. */
    fun combine(perTable: Map<String, String>): String =
        perTable.entries
            .map { (key, digest) -> "$key$KEY_SEPARATOR$digest" }
            .sorted()
            .joinToString(ENTRY_SEPARATOR)

    /** The digest recorded for [tableKey] inside a stored fingerprint, or null when the table is not part of it. */
    fun segment(
        stored: String,
        tableKey: String,
    ): String? =
        stored
            .split(ENTRY_SEPARATOR)
            .firstOrNull { it.startsWith("$tableKey$KEY_SEPARATOR") }
            ?.substringAfter(KEY_SEPARATOR)
}
