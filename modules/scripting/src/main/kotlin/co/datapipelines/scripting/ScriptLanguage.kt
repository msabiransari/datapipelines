package co.datapipelines.scripting

/**
 * The script language a [ScriptEngine] evaluates (transform-nodes design §4.1).
 *
 * Deliberately NOT the template model's `TemplateType`: that enum is pipeline-contract's,
 * a layer above this module, and the seam must stay reusable by every caller (node runner,
 * template service, dashboard runtime) without a contract dependency. The template lane (7b)
 * maps the two.
 *
 * `JAVASCRIPT` exists so round two's engine can implement the same seam without touching
 * this vocabulary; no engine for it ships yet.
 */
enum class ScriptLanguage {
    /** JSONata, via `com.dashjoin:jsonata`, in-process. */
    JSONATA,

    /** JavaScript (GraalJS polyglot isolate) — round two; reserved, not implemented. */
    JAVASCRIPT,
}
