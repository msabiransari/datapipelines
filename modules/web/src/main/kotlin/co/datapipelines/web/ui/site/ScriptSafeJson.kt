package co.datapipelines.web.ui.site

/**
 * The ONE escaper for JSON a template inserts into a `<script>` block (185): the HTML
 * tokenizer reads a script element's content as raw text whatever its `type`, so a
 * `</script>` sequence inside a JSON string value ends the element there and the rest of
 * the blob renders as markup — a stored XSS through any free-text field the blob carries.
 * Jackson does not escape `/`, so the escaping happens here, once, at the writer.
 *
 * The replacements keep the payload VALID JSON (RFC 8259 escapes only): a parser reading
 * the block back — `JSON.parse` on the client, Jackson in the tests — sees the original
 * string unchanged.
 *
 *  - `</` → `<\/` (ASCII case-insensitive — the tokenizer closes the element on any
 *    casing of the tag name). `\/` is a legal JSON escape for `/`.
 *  - `<!--` → `\u003c!--`. A raw `<!--` puts the HTML tokenizer into the script-data-escaped
 *    state, where a later `</script>` is swallowed and the element's real end can be missed;
 *    the `<` cannot survive as text. The prompt's sketch `<\!--` was not used because `\!`
 *    is not a legal JSON escape and would corrupt the blob — `\u003c` is the escape that
 *    satisfies both the tokenizer and the JSON grammar.
 *  - U+2028 / U+2029 → `\u2028` / `\u2029` (belt and braces: legal JSON escapes that also
 *    keep the text safe inside a JS string literal, where the raw code points are line
 *    terminators).
 *
 * Callers: [DocJsonLd] and [FaqJsonLd] (the JSON-LD blocks, which escaped only `</`
 * before 185) and the pipeline editor's two JSON blobs
 * (`PipelineEditorController`). A `<script type="application/json">` slot is added ONLY
 * through this helper — `ScriptBlockUtextAuditTest` holds the template-side allowlist.
 */
object ScriptSafeJson {
    /** JSON that cannot break out of the script block it is inserted into. */
    fun forScriptBlock(json: String): String =
        json
            .replace("</", "<\\/", ignoreCase = true)
            .replace("<!--", "\\u003c!--")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
}
