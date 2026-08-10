package co.datapipelines.staging

/**
 * Identifier safety for source column labels (staging.md §4.5, normative).
 *
 * Column labels come from the result-set metadata of user-authored SQL
 * (`SELECT x AS "whatever the author typed"`) and are **never trusted**. Before any label is
 * interpolated into generated DDL/DML it is validated here; the caller then double-quotes it
 * unconditionally (validation is the security boundary, quoting the second layer).
 *
 * **Sanitising is forbidden.** A bad label fails the node — it is never renamed to `col_3`,
 * which would silently change the schema the caller receives and the names downstream
 * `source: tempdb` templates must use.
 */
internal object StagingIdentifiers {
    /**
     * A valid column label: a leading letter or underscore, then letters/digits/underscores,
     * total length 1–63 (H2's identifier limit).
     */
    val COLUMN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,62}")

    /**
     * Validates a result set's labels in order and returns them unchanged (§4.5 steps 1–2).
     *
     * @throws StagingInvalidColumnNameException on the first null, malformed, or
     *   case-insensitively duplicated label, carrying its 1-based ordinal.
     */
    fun validateColumnNames(labels: List<String?>): List<String> {
        val seen = mutableSetOf<String>()
        return labels.mapIndexed { i, raw ->
            // One throw for all three failure modes — null, malformed shape, or a case-insensitive
            // duplicate (H2 folds unquoted identifiers to upper case, so `total` and `TOTAL`
            // collide). The `||` short-circuits, so `seen.add` runs only for a well-formed label,
            // and the disjunction's falsehood smart-casts `raw` to non-null for the return.
            if (raw == null || !COLUMN_NAME.matches(raw) || !seen.add(raw.uppercase())) {
                throw StagingInvalidColumnNameException(ordinal = i + 1, label = raw)
            }
            raw
        }
    }

    /** Double-quotes an identifier for generated SQL. Any embedded `"` is doubled per SQL rules. */
    fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""
}
