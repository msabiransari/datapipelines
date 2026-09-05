package co.datapipelines.pipeline

/**
 * The pipeline name grammar (pipeline-contract §3.2, template-hierarchy-design §14): one to
 * ten `/`-separated segments, each `[a-z0-9][a-z0-9_.-]{0,63}`, total length
 * ≤ [MAX_PIPELINE_PATH_CHARS].
 *
 * It is **character-for-character the template grammar** (§4.1). That is the whole design of
 * 067: a folder is a name prefix and nothing else, so a pipeline and the templates it uses can
 * only share a prefix — `nyc/mobility/revenue_by_borough` next to `nyc/mobility/daily_by_zone.sql`
 * — if both kinds of asset spell a path the same way. Two nearly-identical rules would be a
 * trap for every agent and every UI that renders both trees.
 *
 * The copy is deliberate and guarded, not accidental: `modules/templates` depends on this
 * module, never the reverse, so the constant cannot simply be imported from there.
 * `PipelineNameGrammarSpecDriftTest` parses the §4.1 grammar block out of
 * `docs/template-hierarchy-design.md` and asserts THIS regex against it, which anchors both
 * copies to one written rule — the house spec-drift pattern, applied to a value that would
 * otherwise drift silently.
 *
 * ## Relationship to the pre-067 rule (normative)
 *
 * The old rule was [IDENTIFIER] — `[a-z0-9_]{1,63}`, the identifier rule §15.1 froze for
 * names, node ids and tables. This grammar is a **widening in every respect that matters**
 * (`/` as a separator, `.` and `-` inside a segment, 200 chars total) with exactly one
 * narrowing: a name whose first character is `_` was legal and is not (a segment must start
 * alphanumeric, which is what forbids `.` and `..` segments without a special-case list).
 * Measured, not reasoned about — see `PipelineNameGrammarTest.containment`.
 *
 * That narrowing needs **no migration and no deploy gate**, and the reason is a real
 * difference from templates rather than an optimistic reading of the same situation. A
 * template name is re-validated at RENDER time in two more places (§4.6: `parseKey` and the
 * import-prologue synthesis), so a stored name the grammar stops accepting breaks execution
 * of already-released pipelines. A pipeline name is validated at SAVE only: pipelines are
 * UUID-addressed over HTTP, [PipelineResolver] looks a child reference up by name without
 * re-checking its shape, and nothing on the execute path consults this regex. A legacy
 * `_scratch` pipeline therefore keeps listing, keeps opening and keeps executing; its next
 * SAVE is refused with the catalogued `pipeline.validation.name_invalid`, naming the value —
 * loud, actionable, and not silent at any point.
 *
 * ## Node ids, output tables and parameter names are NOT widened
 *
 * They keep [IDENTIFIER] (and parameter keys their own `[a-z_][a-z0-9_]*`). A node id becomes
 * an H2/Postgres identifier and a Freemarker-visible key; `/` has no meaning there and every
 * reason to be refused. `StructuralRulesTest` pins that separation with a `/`-bearing node id
 * and output table, both still rejected.
 */
internal val PIPELINE_PATH = Regex("^[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){0,9}$")

/** Total pipeline-name length cap (§3.2 — the template rule's 200, for the same reason: paths are longer). */
internal const val MAX_PIPELINE_PATH_CHARS = 200

/** True when [name] satisfies the full grammar — shape and total length. */
internal fun isValidPipelineName(name: String): Boolean = name.length <= MAX_PIPELINE_PATH_CHARS && PIPELINE_PATH.matches(name)

/**
 * The grammar **published for a caller outside this module** — a form's `pattern`/`maxlength`,
 * a prefix check on a browse endpoint, a refusal's hint text.
 *
 * Every member is a *read of the validator's own values*, never a second copy of them, so a
 * client-side check and the server rule cannot drift: changing the grammar changes both in one
 * edit, by construction. The server still validates every write and its rejection is the only
 * one that counts (template-hierarchy-design §9.5, which this mirrors deliberately).
 *
 * [pattern] stays inside the character-class-and-repetition subset that HTML5 `pattern`
 * (a JavaScript RegExp, implicitly anchored) and `kotlin.text.Regex` read identically — no
 * lookarounds, no named groups, no back-references.
 */
object PipelineNameGrammar {
    /** The grammar's pattern source, taken from the validator's own [Regex]. */
    val pattern: String get() = PIPELINE_PATH.pattern

    /** The total-length cap, taken from the validator's own constant. */
    val maxLength: Int get() = MAX_PIPELINE_PATH_CHARS

    /**
     * True when [name] satisfies the grammar — the same total check the save path runs
     * ([isValidPipelineName]), published so a caller outside this module can ask the question
     * without owning a second copy of the answer.
     */
    fun matches(name: String): Boolean = isValidPipelineName(name)

    /** A human rendering of the rule, for a hint and for a refusal message. */
    const val DESCRIPTION: String =
        "Lower-case path segments separated by `/` — each segment starts with a letter or digit " +
            "and may contain letters, digits, `_`, `.` and `-`; at most 10 segments, 200 characters."
}
