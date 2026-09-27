package co.datapipelines.application.pipelines

import co.datapipelines.executor.ExecutionReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import java.time.LocalDate

/**
 * **The pipeline input resolver** (scheduler design revision §5.2, #9 slice 3) — one shared
 * piece of code for scheduled and direct execution, owned by the execution layer (record §8):
 * it turns a pipeline's declared parameters, a request's literal `parameters`, optional explicit
 * `parameter_bindings` and the run's frozen [ExecutionReference] into ONE literal parameter map
 * that `ParameterBinder` then validates exactly as today. It runs BEFORE ordinary input
 * coercion and before any node runs, so the pipeline nodes receive typed values — a bound
 * `as_of_date` arrives as the wire DATE string `"2026-09-22"`, never as a keyword.
 *
 * ## The rules
 * - Keywords are an EXACT allowlist ([TODAY], [YESTERDAY], uppercase) matched only inside an
 *   explicit `{"source": "keyword", "name": …}` binding — never by scanning arbitrary strings:
 *   a literal STRING `"TODAY"` reaches the pipeline as the string (§9.5 [3]). No expression
 *   language, no evaluation.
 * - `TODAY` is `LocalDate.ofInstant(reference.at, reference.timezone)` — the derivation
 *   `OrgContext.platformValues` uses for `current_date`, on the FROZEN instant and the
 *   SCHEDULE'S zone instead of the actual start and the org zone (§5.1). `YESTERDAY` is
 *   `TODAY.minusDays(1)` — a calendar day, never 24 hours (DST's fall-back day is 25 hours).
 * - The same name in `parameters` and `parameter_bindings` is refused ([Refusal.Conflict]) —
 *   the resolver rejects ambiguity instead of inventing another precedence rule (§5.2).
 * - A bound name must be a declared parameter of type `DATE` of the version being prepared;
 *   anything else — an unknown name, another type, an unknown keyword or source, a literal
 *   binding without a value, a keyword with no reference — is refused with a [Refusal.Invalid]
 *   reason a caller can act on.
 *
 * ## Save time vs run time
 * At save the payload has no reference to resolve against, so the adapter runs
 * [checkStructure] — every structural refusal, keyword bindings included — and binds the
 * literals with its own placeholder DATE per bound name so a required parameter passes
 * (the placeholder is never persisted). [resolve] is the run-time entry: a keyword binding
 * and a null [reference] is [Refusal.Reason.NO_REFERENCE], never a silent skip.
 *
 * The envelope's KEY-SET contract (a binding is an object holding `source` and its one payload
 * key) belongs to the payload schema, so the adapter that owns the payload checks it — a
 * binding carrying unrelated keys, `reference*` included, is a payload refusal before this
 * class runs (§5.1: the client cannot replace the schedule's time by embedding matching fields).
 *
 * Deterministic: names are judged in sorted order and the first refusal is returned.
 */
class PipelineInputResolver {
    /** One resolution: the literal map to bind, or why no map was produced. */
    sealed interface Result {
        /**
         * Every literal and every binding, resolved to wire values — keyword bindings as DATE
         * strings, literal bindings passed through untouched for `ParameterBinder` to type-check.
         */
        data class Resolved(
            val parameters: Map<String, JsonNode>,
        ) : Result

        data class Refused(
            val refusal: Refusal,
        ) : Result
    }

    sealed interface Refusal {
        /** The binding the refusal names; the surface carries it as `details.parameter`. */
        val parameter: String

        val message: String

        /** A binding that cannot be resolved; [Reason] is the `details.reason` a refusal carries. */
        data class Invalid(
            val reason: Reason,
            override val parameter: String,
            override val message: String,
        ) : Refusal

        /** The same name supplied twice — once as a literal, once as a binding (§5.2's ambiguity rule). */
        data class Conflict(
            override val parameter: String,
            override val message: String,
        ) : Refusal

        enum class Reason(
            val wire: String,
        ) {
            UNKNOWN_PARAMETER("unknown_parameter"),
            TYPE_MISMATCH("type_mismatch"),
            UNKNOWN_KEYWORD("unknown_keyword"),
            UNKNOWN_SOURCE("unknown_source"),
            LITERAL_INVALID("literal_invalid"),
            NO_REFERENCE("no_reference"),
        }
    }

    /**
     * Resolves [bindings] against [declared] and [reference].
     *
     * @param declared the version's declared parameters by name, as their wire type spellings
     *   (`"DATE"`, `"STRING"`, …) — what `ParameterBinder` will coerce the result against.
     * @param literals the request's ordinary `parameters` object, passed through untouched.
     * @param bindings the payload's `parameter_bindings` entries by name, or null when the
     *   payload carries none.
     * @param reference the run's frozen logical time; a keyword binding with none is refused
     *   [Refusal.Reason.NO_REFERENCE] — never resolved against "now".
     */
    fun resolve(
        declared: Map<String, String>,
        literals: Map<String, JsonNode>,
        bindings: Map<String, JsonNode>?,
        reference: ExecutionReference?,
    ): Result {
        val resolved = LinkedHashMap<String, JsonNode>(literals)
        for (name in bindings?.keys?.sorted().orEmpty()) {
            val node = requireNotNull(bindings)[name] ?: continue
            refusalFor(name, declared, literals, node)?.let { return Result.Refused(it) }
            resolved[name] =
                when (node.path(BINDING_SOURCE).asText()) {
                    SOURCE_KEYWORD -> {
                        if (reference == null) {
                            return resolveNoReference(name)
                        }
                        keywordValue(node, reference)
                    }

                    else -> {
                        requireNotNull(node.get(BINDING_VALUE))
                    }
                }
        }
        return Result.Resolved(resolved)
    }

    /**
     * The save-time structural check: every refusal a binding could carry EXCEPT the reference
     * ones (a save has structure but no clock). Null when every binding is well-formed against
     * [declared].
     */
    fun checkStructure(
        declared: Map<String, String>,
        literals: Map<String, JsonNode>,
        bindings: Map<String, JsonNode>?,
    ): Refusal? =
        bindings?.keys?.sorted()?.firstNotNullOfOrNull { name ->
            refusalFor(
                name,
                declared,
                literals,
                requireNotNull(bindings)[name] ?: return null,
            )
        }

    /** The first structural refusal for [name], or null — every check except resolution, in order. */
    private fun refusalFor(
        name: String,
        declared: Map<String, String>,
        literals: Map<String, JsonNode>,
        node: JsonNode,
    ): Refusal? {
        val echoed = name.reflectSafely()
        if (name in literals) {
            return Refusal.Conflict(
                echoed,
                "Parameter '$echoed' is supplied both in `parameters` and in `parameter_bindings` — " +
                    "give it one source; a keyword's value is decided by the schedule's timezone and its logical occurrence time.",
            )
        }
        val declaredType = declared[name]
        if (declaredType == null) {
            return Refusal.Invalid(
                Refusal.Reason.UNKNOWN_PARAMETER,
                echoed,
                "'$echoed' is not a parameter the pipeline declares, so it cannot be bound.",
            )
        }
        if (!declaredType.equals(DATE, ignoreCase = true)) {
            return Refusal.Invalid(
                Refusal.Reason.TYPE_MISMATCH,
                echoed,
                "'$echoed' is declared $declaredType; only a DATE parameter can take a binding.",
            )
        }
        val keyword = node.path(BINDING_NAME).takeIf { it.isTextual }?.asText()
        return when (node.path(BINDING_SOURCE).takeIf { it.isTextual }?.asText()) {
            SOURCE_KEYWORD -> {
                if (keyword in KEYWORDS) {
                    null
                } else {
                    Refusal.Invalid(
                        Refusal.Reason.UNKNOWN_KEYWORD,
                        echoed,
                        "Keyword bindings name " + KEYWORDS.sorted().joinToString(" or ") +
                            " (uppercase), not '${keyword.reflectSafely()}'.",
                    )
                }
            }

            SOURCE_LITERAL -> {
                if (node.has(BINDING_VALUE)) {
                    null
                } else {
                    Refusal.Invalid(
                        Refusal.Reason.LITERAL_INVALID,
                        echoed,
                        "A literal binding carries a `value` — '$echoed's does not.",
                    )
                }
            }

            else -> {
                Refusal.Invalid(
                    Refusal.Reason.UNKNOWN_SOURCE,
                    echoed,
                    "Binding sources are \"$SOURCE_KEYWORD\" or \"$SOURCE_LITERAL\", not '${node.path(BINDING_SOURCE).asText().reflectSafely()}'.",
                )
            }
        }
    }

    /** The wire DATE a validated keyword binding resolves to (the keyword check has already run). */
    private fun keywordValue(
        node: JsonNode,
        reference: ExecutionReference,
    ): JsonNode {
        val today = LocalDate.ofInstant(reference.at, reference.timezone)
        val date =
            if (node.path(BINDING_NAME).asText() == TODAY) {
                today
            } else {
                // A calendar day, never 24 hours: DST's fall-back day is 25 hours long.
                today.minusDays(1)
            }
        return TextNode(date.toString())
    }

    private fun resolveNoReference(name: String): Result =
        Result.Refused(
            Refusal.Invalid(
                Refusal.Reason.NO_REFERENCE,
                name.reflectSafely(),
                "'${name.reflectSafely()}' is bound to a keyword, and this run has no reference time to resolve it against.",
            ),
        )

    companion object {
        const val TODAY = "TODAY"
        const val YESTERDAY = "YESTERDAY"
        const val DATE = "DATE"

        const val BINDING_SOURCE = "source"
        const val BINDING_NAME = "name"
        const val BINDING_VALUE = "value"

        val KEYWORDS = setOf(TODAY, YESTERDAY)

        const val SOURCE_KEYWORD = "keyword"
        const val SOURCE_LITERAL = "literal"

        /** Longest client-supplied text a refusal reflects (#269; the house `truncateForError` cap). */
        private const val MAX_ECHO = 64

        /** Replacement for an ISO control character in reflected text (CF-1 — the house shape). */
        private const val CONTROL_REPLACEMENT = '�'

        /**
         * Makes client-supplied text safe to echo into a refusal — the byte-for-byte carry-forwards
         * of `typesystem`'s and `pipeline-contract`'s `truncateForError` (#269): clipped at
         * [MAX_ECHO] characters (CF-2 — bounded reflection), every ISO control character become
         * U+FFFD (CF-1 — no forged log lines). Truncation BEFORE sanitising, so the work is bounded
         * by the cap, not by the caller's length. `internal` so the tests can pin it.
         */
        internal fun String?.reflectSafely(): String {
            val raw = this ?: return "null"
            val clipped = if (raw.length <= MAX_ECHO) raw else raw.take(MAX_ECHO) + "…"
            return if (clipped.none { it.isISOControl() }) {
                clipped
            } else {
                clipped.map { if (it.isISOControl()) CONTROL_REPLACEMENT else it }.joinToString("")
            }
        }
    }
}
