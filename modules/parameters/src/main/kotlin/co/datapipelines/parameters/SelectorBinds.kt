package co.datapipelines.parameters

import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.namedparam.AbstractSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils
import org.springframework.jdbc.core.namedparam.ParsedSql

/**
 * The selector runner's `:name` → `?` translation (record §6.2, P29) — Spring's
 * [NamedParameterUtils], the parser every other statement path in the house rides (quote- and
 * comment-aware; its dialect blind spots are pinned in `dag`'s `NamedParameterTranslationTest`),
 * plus the three things a selector needs that those paths do not:
 *
 *  - **List expansion.** A `MULTI` parent binds as its list. The pinned spring-jdbc 6.2.19 ALREADY
 *    expands an `Iterable` value into `?, ?, ?` in the SQL — but its value array keeps the list as
 *    ONE element, so the values are FLATTENED here to match the placeholders (measured on the
 *    pinned jar; `SelectorBindsTest` pins both halves). Each member is its own placeholder: a value
 *    never becomes SQL text.
 *  - **The empty list.** Spring renders `IN ()` for an empty `Iterable` — a syntax error on every
 *    engine. An empty list binds as ONE placeholder holding `NULL` instead (`IN (?)` ⇒ `IN (NULL)`,
 *    which matches no row) — still a placeholder, never a literal.
 *  - **The `in_list` slices (P29).** A `<name>__<k>` reference whose `<name>` is a bound list is a
 *    slice of that list: the list is dealt IN ORDER across the `N` slices the SQL names (`N` = the
 *    highest `k` referenced), `ceil(size / N)` members each — which is at most the macro's `chunk`
 *    whenever the macro emits `ceil(size / chunk)` slices, as it must to cover the list. The runner
 *    binds the slices the rendered SQL hands it and nothing else; an empty slice binds `NULL` like
 *    an empty list.
 *
 * Every EXPANDED placeholder is counted against `max-binds-per-statement` (P29 — SQL Server's
 * 2,100 floor): the count is the flattened value array's length. A `:name` with no value in the
 * binds is refused, never bound as null — the executor's `sql_parameter_missing` rule.
 */
internal object SelectorBinds {
    /** What [translate] produced. */
    sealed interface Translation {
        /** The positional SQL and its values, `sql`'s `?` count == `values.size`. */
        data class Translated(
            val sql: String,
            val values: List<Any?>,
        ) : Translation

        /** [name] is referenced by the SQL and has no value. */
        data class Missing(
            val name: String,
        ) : Translation

        /** [count] expanded placeholders, over the statement cap [max]. */
        data class TooManyBinds(
            val count: Int,
            val max: Int,
        ) : Translation

        /** The statement carries a bare `?` (a selector binds `:name` only), or Spring refused its placeholders; [detail] says which. */
        data class Refused(
            val detail: String,
        ) : Translation
    }

    /** `<name>__<digits>` — a slice reference ([SLICE_BIND]'s grammar), split into its list and its 1-based index. */
    private val SLICE = Regex("^([a-z_][a-z0-9_]*)__([0-9]+)$")

    @Suppress("ReturnCount") // one early answer per refusal; the translation is the fall-through
    fun translate(
        sql: String,
        binds: Map<String, Any?>,
        maxBinds: Int,
    ): Translation {
        val parsed = NamedParameterUtils.parseSqlStatement(sql)
        return try {
            val (referenced, occurrences, total) = referencedNames(parsed)
            if (total > occurrences) {
                return Translation.Refused("a bare ? placeholder — a selector binds its values by :name only")
            }
            val slices = sliceCounts(referenced, binds)
            val missing = referenced.firstOrNull { it !in binds && sliceOf(it, binds, slices) == null }
            if (missing != null) return Translation.Missing(missing)
            val source = BindSource(binds, slices)
            val positional = NamedParameterUtils.substituteNamedParameters(parsed, source)
            val values = NamedParameterUtils.buildValueArray(parsed, source, null).flatMap(::flatten)
            if (values.size > maxBinds) return Translation.TooManyBinds(values.size, maxBinds)
            Translation.Translated(positional, values)
        } catch (e: InvalidDataAccessApiUsageException) {
            Translation.Refused(e.message.orEmpty())
        }
    }

    /**
     * Every `:name` the parser finds, in order — read through a recording source, because the parse's
     * own name list is package-private and `buildValueArray` asks the source for each name's value.
     */
    private fun referencedNames(parsed: ParsedSql): Triple<List<String>, Int, Int> {
        val names = LinkedHashSet<String>()
        var occurrences = 0
        val recorder =
            object : AbstractSqlParameterSource() {
                override fun hasValue(paramName: String): Boolean = true

                override fun getValue(paramName: String): Any? {
                    names.add(paramName)
                    occurrences++
                    return null
                }
            }
        // The array has one slot per placeholder, named or bare; the source is asked only for the named ones.
        val total = NamedParameterUtils.buildValueArray(parsed, recorder, null).size
        return Triple(names.toList(), occurrences, total)
    }

    /** For each bound list referenced through slices, how many slices the SQL names (the highest index). */
    private fun sliceCounts(
        referenced: List<String>,
        binds: Map<String, Any?>,
    ): Map<String, Int> =
        referenced
            .filter { it !in binds }
            .mapNotNull { name -> SLICE.matchEntire(name)?.destructured?.let { (base, k) -> base to k.toIntOrNull() } }
            .filter { (base, k) -> binds[base] is List<*> && k != null && k >= 1 }
            .groupBy({ it.first }, { it.second!! })
            .mapValues { (_, indexes) -> indexes.max() }

    /** The slice [name] names, or null when it is not a slice of a bound list. */
    private fun sliceOf(
        name: String,
        binds: Map<String, Any?>,
        slices: Map<String, Int>,
    ): List<Any?>? {
        val (base, k) = SLICE.matchEntire(name)?.destructured ?: return null
        val list = binds[base] as? List<*>
        val count = slices[base] ?: 0
        val index = k.toIntOrNull()?.takeIf { it in 1..count }
        if (list == null || index == null) return null
        val per = (list.size + count - 1) / count
        return list.drop((index - 1) * per).take(per)
    }

    /** One placeholder per list member; an empty list is one `NULL` placeholder; a scalar is itself. */
    private fun flatten(value: Any?): List<Any?> =
        when (value) {
            is Iterable<*> -> value.toList()
            else -> listOf(value)
        }

    /** The values Spring's substitution reads: lists (an empty one as `[NULL]`), slices, scalars. */
    private class BindSource(
        private val binds: Map<String, Any?>,
        private val slices: Map<String, Int>,
    ) : AbstractSqlParameterSource() {
        override fun hasValue(paramName: String): Boolean = paramName in binds || sliceOf(paramName, binds, slices) != null

        override fun getValue(paramName: String): Any? {
            val value = if (paramName in binds) binds[paramName] else sliceOf(paramName, binds, slices)
            return if (value is List<*> && value.isEmpty()) EMPTY_LIST_BINDS else value
        }
    }

    /** `IN (?)` bound `NULL` — no row matches, and no engine sees `IN ()`. */
    private val EMPTY_LIST_BINDS: List<Any?> = listOf(null)
}
