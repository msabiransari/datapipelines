package co.datapipelines.templates

/**
 * The save-time scan behind 072's ordering rule: **which `:name` bind parameters does a template
 * body carry?**
 *
 * A CALCULATOR node writes its value into the Context at its DAG position, so a SQL node binding
 * `:that_key` is only correct if it `depends_on` the node that writes it (calculators design
 * §0.3). Answering that at save time needs the bind names, and this is where they are.
 *
 * ## Why a text scan here, when [InterpolatedParameterScanner] insists on the AST
 *
 * The two questions are different. 042's question — "does this body interpolate a declared
 * parameter into the SQL string?" — is about Freemarker's own tree, and a regex over source text
 * could be lied to by a construct the parser resolves differently. This question is about the
 * SQL the body emits, and `:name` binds live in the template's *static text*, which the AST
 * models as opaque strings. There is no tree to consult.
 *
 * ## What it is allowed to get wrong, and in which direction
 *
 * The scan reads the body, not the rendered SQL, so:
 *
 *  - a bind inside a `<#include>`d macro is **missed** — the same limitation
 *    [InterpolatedParameterScanner] accepts, and the executor's `sql_parameter_missing` is the
 *    backstop that still refuses the run;
 *  - a `:word` inside a comment or a string literal is **reported** — a false positive whose
 *    entire cost is an author being asked for a `depends_on` edge that is harmless to add.
 *
 * Refuse, don't miss: the same direction 042 §4.2 takes.
 *
 * Postgres casts are handled rather than accepted as noise: `amount::numeric` must not report a
 * bind named `numeric`. The lookbehind refuses a colon preceded by a word character or another
 * colon, which rules out both halves of `::` and `a:b` in one rule.
 */
object SqlBindScanner {
    /**
     * A `:name` reference — a colon that starts neither a cast (`::`) nor a suffix of a word,
     * followed by a §6.1-shaped context key.
     */
    private val BIND = Regex("""(?<![:\w]):([a-z_][a-z0-9_]*)""")

    /** Every distinct `:name` in [body], in first-seen order. */
    fun scan(body: String): List<String> =
        BIND
            .findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
}
