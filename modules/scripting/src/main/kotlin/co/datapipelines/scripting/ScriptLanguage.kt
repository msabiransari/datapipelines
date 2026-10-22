package co.datapipelines.scripting

/**
 * The script language a [ScriptEngine] evaluates (transform-nodes design §4.1).
 *
 * Deliberately NOT the template model's `TemplateType`: that enum is pipeline-contract's,
 * a layer above this module, and the seam must stay reusable by every caller (node runner,
 * template service, dashboard runtime) without a contract dependency. The template lane (7b)
 * maps the two.
 *
 * `JAVASCRIPT` is [JavaScriptEngine] (#8 lane 8a), on the same seam and vocabulary; no
 * product surface selects it yet (a javascript template is still refused until lane 8b).
 */
enum class ScriptLanguage {
    /** JSONata, via `com.dashjoin:jsonata`, in-process. */
    JSONATA,

    /** JavaScript (ECMAScript 2025, pinned), via the GraalJS polyglot isolate: [JavaScriptEngine]. */
    JAVASCRIPT,
}
