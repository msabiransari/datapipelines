package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * One rejected §7 template check.
 *
 * [code] is always a constant from [PipelineErrorCodes.Template] — never a string literal — so
 * the drift test that reads §13.9 out of pipeline-contract.md guards every spelling this
 * module uses. [details] carries the machine-readable facts behind [message] for the unified
 * error response's `details` object (rules/02-error-handling.md); any reflected inbound value
 * in either field is truncated to ≤64 chars ([truncateForError]).
 */
data class TemplateValidationFailure(
    val code: String,
    val message: String,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * The outcome of validating a template — **exhaustive**, never fail-fast.
 *
 * templates.md §7 runs the whole check set before anything is written (D2), so an author
 * fixing an LLM-generated template sees every problem at once rather than one per round-trip:
 * a syntax error, a forbidden construct, and a broken import all surface in a single response.
 */
data class TemplateValidationResult(
    val failures: List<TemplateValidationFailure>,
) {
    val isValid: Boolean get() = failures.isEmpty()

    /** The distinct codes present, first-seen order — what most assertions and logs want. */
    val codes: List<String> get() = failures.map { it.code }.distinct()

    /** Throws [TemplateValidationException] unless the template is valid. */
    fun orThrow() {
        if (!isValid) throw TemplateValidationException(this)
    }

    companion object {
        val VALID = TemplateValidationResult(emptyList())
    }
}

/**
 * Thrown when an invalid template reaches a boundary that cannot report a list.
 *
 * The exception's `code` is the **first** failure's code (the unified error response carries
 * one code); the full list travels in `details["failures"]`, which the REST layer renders.
 * Extends [DatapipelinesException] per module-structure §4.3 — the shared base lives in
 * `typesystem`, the one module everyone may depend on.
 */
class TemplateValidationException(
    val result: TemplateValidationResult,
) : DatapipelinesException(
        code = result.failures.firstOrNull()?.code ?: PipelineErrorCodes.Template.SYNTAX_ERROR,
        message = "Template validation failed with ${result.failures.size} error(s): ${result.codes.joinToString()}",
        details =
            mapOf(
                "failures" to
                    result.failures.map {
                        mapOf("code" to it.code, "message" to it.message, "details" to it.details)
                    },
            ),
    )

/**
 * §4.1's `segment` production, the one source both [TEMPLATE_PATH] and
 * [TemplateNameGrammar.refusalReason] are built from — a second literal here is exactly the
 * drift `TemplateNameGrammarSpecDriftTest` exists to prevent, one level down.
 */
private const val SEGMENT = "[a-z0-9][a-z0-9_.-]{0,63}"

/**
 * The template name grammar of template-hierarchy-design.md §4.1: **two** to ten
 * `/`-separated segments, each `[a-z0-9][a-z0-9_.-]{0,63}`, total length
 * ≤ [MAX_TEMPLATE_PATH_CHARS]. The repetition is `{1,9}` — at least one separator, so at
 * least two segments: **a folder is mandatory** (077).
 *
 * A regex composition, kept boring and total on purpose: the length cap rides beside the
 * pattern because a regex cannot count across both segment shapes without unreadable
 * arithmetic. The grammar is **narrower** than the pre-043 rule in three respects now
 * (segments must start alphanumeric; a segment caps at 64 where the flat rule allowed 100;
 * and since 077 a folder is required) — §4.6 is the deploy-time gate for stored names that do
 * not survive a change, re-issued as `V12__folder_required.sql`, and it is why the three call
 * sites (save: `TemplateValidator`; render: `RegistryTemplateLoader.parseKey`; prologue
 * synthesis: [isSafeToSynthesize]) moved together.
 */
internal val TEMPLATE_PATH = Regex("^$SEGMENT(/$SEGMENT){1,9}$")

/** True when a name is one legal §4.1 segment and nothing else — a flat, folderless name. */
private val SINGLE_SEGMENT = Regex("^$SEGMENT$")

/**
 * §4.1's `path` production **minus its last segment** — a folder path, 1 to 9 segments.
 *
 * A browse request names a FOLDER, not an asset, so it cannot be checked against
 * [TEMPLATE_PATH]: since 077 that regex refuses `nyc`, which is exactly the prefix the tree UI
 * and `templates_list` ask for one level below the root.
 */
private val TEMPLATE_PREFIX = Regex("^$SEGMENT(/$SEGMENT){0,8}$")

/** Total template-name length cap (§4.1 — raised from the flat rule's 100: paths are longer). */
internal const val MAX_TEMPLATE_PATH_CHARS = 200

/**
 * The §4.1 grammar **published for a form to render** — the one thing a client-side check may
 * take from the server (template-hierarchy-design §9.5).
 *
 * The point of this object is that it is a *read of the validator's own values*, never a
 * second copy of them: [pattern] is [TEMPLATE_PATH]'s own source and [maxLength] is
 * [MAX_TEMPLATE_PATH_CHARS] itself, so a create form that renders them into an HTML
 * `pattern` / `maxlength` attribute cannot drift from the rule the server enforces. Changing
 * the grammar changes both, in one edit, by construction.
 *
 * The server still validates every write and its rejection is the only one that counts
 * (`TemplateValidator`): what a form renders from here is a convenience that saves a
 * round-trip, never an authority.
 *
 * [pattern] is deliberately expressed in the character-class-and-repetition subset that HTML5
 * `pattern` (a JavaScript RegExp, implicitly anchored) and `kotlin.text.Regex` read
 * identically — no lookarounds, no named groups, no back-references.
 */
object TemplateNameGrammar {
    /** The §4.1 pattern source, taken from the validator's own [Regex]. */
    val pattern: String get() = TEMPLATE_PATH.pattern

    /** The §4.1 total-length cap, taken from the validator's own constant. */
    val maxLength: Int get() = MAX_TEMPLATE_PATH_CHARS

    /**
     * True when [name] satisfies the §4.1 grammar — the same total check the save path runs
     * ([isValidTemplateName]), published so a caller outside this module can ask the question
     * without owning a second copy of the answer.
     */
    fun matches(name: String): Boolean = isValidTemplateName(name)

    /** A human rendering of the rule, for a form's hint text and a refusal message. */
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
    fun matchesPrefix(prefix: String): Boolean = prefix.length <= MAX_TEMPLATE_PATH_CHARS && TEMPLATE_PREFIX.matches(prefix)

    /**
     * Why [name] was refused, as the `details.reason` of `template.validation.id_invalid`.
     *
     * [REASON_FOLDER_REQUIRED] is reported only when adding a folder would actually fix the
     * name — it is one legal segment, within the length cap, and simply has no `/`. `_helper`
     * is [REASON_GRAMMAR], because `test/_helper` is illegal too and telling an agent to add a
     * folder would send it round the loop a second time.
     *
     * Only meaningful for a name [matches] rejects; a legal name has no reason.
     */
    fun refusalReason(name: String): String =
        if (name.length <= MAX_TEMPLATE_PATH_CHARS && SINGLE_SEGMENT.matches(name)) {
            REASON_FOLDER_REQUIRED
        } else {
            REASON_GRAMMAR
        }
}

/** True when [name] satisfies the full §4.1 grammar — shape and total length. */
internal fun isValidTemplateName(name: String): Boolean = name.length <= MAX_TEMPLATE_PATH_CHARS && TEMPLATE_PATH.matches(name)

/**
 * A namespace alias must be a plain identifier — `[a-zA-Z_][a-zA-Z0-9_]*`, templates.md §6.3
 * verbatim.
 *
 * The length cap this carried before v1.3 ratified the rule is deliberately gone: the spec sets
 * no bound, and rejecting an alias the contract permits is a false rejection, not extra safety.
 * The character class is what carries the security property, and it is unchanged.
 *
 * **This is a security rule, not a tidiness rule.** templates.md §6.3 has the engine
 * *synthesize* `<#import "{id}@{version}" as {alias}>` from the `imports` array, so both
 * interpolated pieces are author-controlled input that becomes template **source** —
 * source [ForbiddenConstructScanner] never sees, because it scans the body. An alias of
 * `d>${"x"}<#assign z=1` closes the synthesized directive and appends arbitrary FTL. That is
 * precisely the bypass §4.2 forbids a literal `<#import>` in a body to prevent ("a literal
 * directive in a body would bypass alias/version/`is_library` validation"), arriving through
 * the other door; the alias rule is what makes that sentence true.
 *
 * templates.md §7 names no dedicated code for a malformed alias, so it is reported as
 * `template.validation.dangerous_construct` — the §4.2 code for a forbidden construct reaching
 * the engine — rather than by inventing a catalog entry. That mapping is the settled answer, not
 * an open question: §6.3 classifies a prologue-injection attempt as `dangerous_construct` in so
 * many words.
 */
internal val IMPORT_ALIAS = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

/** True when this import entry is safe to interpolate into the synthesized §6.3 prologue. */
internal fun TemplateImport.isSafeToSynthesize(): Boolean = isValidTemplateName(id) && IMPORT_ALIAS.matches(alias) && version > 0

/** Longest reflected raw input allowed in an error message or `details` value (security rule). */
internal const val MAX_REFLECTED_VALUE_LENGTH = 64

private const val CONTROL_REPLACEMENT = '�'

/**
 * Makes a value that came from an inbound payload safe to echo into an error message,
 * `details` map, or log line.
 *
 * Two carry-forwards, both required by the module's security brief:
 *  - **length** — reflected input is truncated to [MAX_REFLECTED_VALUE_LENGTH] so an attacker's
 *    template body cannot flood logs or bloat error responses.
 *  - **control characters** — every ISO control character (U+0000–U+001F, U+007F–U+009F)
 *    becomes U+FFFD, so a newline in a reflected value cannot forge a log record and an escape
 *    sequence cannot smuggle terminal codes into an operator's console.
 *
 * Truncation happens before sanitising, so the work is bounded by the cap, not by the input.
 */
internal fun String?.truncateForError(): String {
    val raw = this ?: return "null"
    val clipped = if (raw.length <= MAX_REFLECTED_VALUE_LENGTH) raw else raw.take(MAX_REFLECTED_VALUE_LENGTH) + "…"
    return buildString(clipped.length) {
        for (ch in clipped) append(if (ch.isISOControl()) CONTROL_REPLACEMENT else ch)
    }
}
