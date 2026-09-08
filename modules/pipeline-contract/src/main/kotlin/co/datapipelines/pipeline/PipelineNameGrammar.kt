package co.datapipelines.pipeline

/**
 * §4.1's `segment` production, the one source both the path regex and the
 * [PipelineNameGrammar.refusalReason] check are built from — a second literal here is exactly
 * the drift `PipelineNameGrammarSpecDriftTest` exists to prevent, one level down.
 */
private const val SEGMENT = "[a-z0-9][a-z0-9_.-]{0,63}"

/**
 * The pipeline name grammar (pipeline-contract §3.2, template-hierarchy-design §14): **two**
 * to ten `/`-separated segments, each `[a-z0-9][a-z0-9_.-]{0,63}`, total length
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
 * 077 adds a second narrowing on exactly the same terms: §4.1 now requires a FOLDER, so a
 * flat `active_users` is refused where it was accepted. `details.reason` separates the two
 * kinds of refusal for a caller — [REASON_FOLDER_REQUIRED] when the only thing wrong is the
 * missing folder, [REASON_GRAMMAR] otherwise (see [PipelineNameGrammar.refusalReason]).
 *
 * Neither narrowing needs **a migration or a deploy gate**, and the reason is a real
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
 *
 * The repetition below is `{1,9}` — at least one separator, so at least two segments.
 */
internal val PIPELINE_PATH = Regex("^$SEGMENT(/$SEGMENT){1,9}$")

/** True when [name] is one legal §4.1 segment and nothing else — a flat, folderless name. */
private val SINGLE_SEGMENT = Regex("^$SEGMENT$")

/**
 * §4.1's `path` production **minus its last segment** — a folder path, 1 to 9 segments.
 *
 * A browse request names a FOLDER, not an asset, so it cannot be checked against
 * [PIPELINE_PATH]: since 077 that regex refuses `nyc`, which is exactly the prefix the tree UI
 * and `pipelines_list` ask for one level below the root.
 */
private val PIPELINE_PREFIX = Regex("^$SEGMENT(/$SEGMENT){0,8}$")

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

    /**
     * True when [segment] is exactly ONE legal §4.1 segment — `[a-z0-9][a-z0-9_.-]{0,63}`.
     *
     * Published for the dp-lake registry (089 §A): a lake table's namespace segments and name
     * are individual segments of this same grammar (datasources.md §4.1's lake row, metadata-db
     * §4.15), and a second copy of the production in another module is exactly the drift the
     * spec-drift guards exist to prevent. Read from the validator's own [SINGLE_SEGMENT], so
     * changing the grammar changes every consumer in one edit.
     */
    fun matchesSegment(segment: String): Boolean = SINGLE_SEGMENT.matches(segment)

    /** A human rendering of the rule, for a hint and for a refusal message. */
    const val DESCRIPTION: String =
        "A folder path: 2 to 10 lower-case segments separated by `/` — each segment starts with a letter or " +
            "digit and may contain letters, digits, `_`, `.` and `-`; 64 characters per segment, 200 in total. " +
            "A folder is required (`test/scratch`, not `scratch`)."

    /** `details.reason` when a name is refused ONLY because it carries no folder (§4.1, 077). */
    const val REASON_FOLDER_REQUIRED: String = "folder_required"

    /** `details.reason` for every other §4.1 violation — a bad character, too many segments, too long. */
    const val REASON_GRAMMAR: String = "grammar"

    /**
     * True when [prefix] is a legal FOLDER PATH — the thing a browse request names, which is
     * not the same shape as a name (077).
     *
     * §4.1 requires a name to carry a folder, so a name is 2–10 segments. A prefix is the
     * folder part alone: **1–9** segments. Checking a prefix against [matches] was correct
     * while a name could be one segment and became a live defect the moment it could not —
     * `prefix: "nyc"` is the first thing an agent or the tree UI asks for after listing the
     * roots, and it would have answered an empty level for every root in the workspace.
     *
     * The bound is 9 rather than 10 for the same reason the name's lower bound is 2: whatever
     * sits under the prefix needs a segment of its own.
     */
    fun matchesPrefix(prefix: String): Boolean = prefix.length <= MAX_PIPELINE_PATH_CHARS && PIPELINE_PREFIX.matches(prefix)

    /**
     * Why [name] was refused, as the `details.reason` of `pipeline.validation.name_invalid`.
     *
     * [REASON_FOLDER_REQUIRED] is reported only when adding a folder would actually fix the
     * name — it is one legal segment, within the length cap, and simply has no `/`. `_helper`
     * is [REASON_GRAMMAR], because `test/_helper` is illegal too and telling an agent to add a
     * folder would send it round the loop a second time.
     *
     * Only meaningful for a name that [matches] rejects; a legal name has no reason.
     */
    fun refusalReason(name: String): String =
        if (name.length <= MAX_PIPELINE_PATH_CHARS && SINGLE_SEGMENT.matches(name)) {
            REASON_FOLDER_REQUIRED
        } else {
            REASON_GRAMMAR
        }
}
