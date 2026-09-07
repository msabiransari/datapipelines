package co.datapipelines.templates

/**
 * The work one [TemplateValidator.validate] call actually did, counted.
 *
 * ## Why this exists
 *
 * templates.md §12.3 requires that validation of adversarial input completes "within a bounded
 * time". That property was asserted with a stopwatch — `elapsed < 5_000` — which is not the
 * property at all: it is a claim about this laptop, this JVM's warm-up, and how many other
 * Testcontainers happened to be starting. It measured 5,833 ms during a loaded full gate and
 * turned a correct build red; it was green in isolation every time it was checked, which is the
 * worst combination a guard can have. Five of the last seven gates carried it (round 083).
 *
 * The bound the code actually offers is not a duration, it is a **step count**: one linear pass
 * over the body, at most one parse, and an import walk the `(library, depth)` memo bounds at
 * distinct-libraries × depth. Those are countable, derivable from the input's own size, and
 * identical on a loaded box and an idle one — so that is what the tests assert now. The one
 * property that genuinely is temporal (a parse that must FAIL FAST rather than burn 37 seconds of
 * save-thread CPU) keeps a single wall-clock tripwire at 60 s, which is a tripwire and not a
 * budget: nothing healthy comes within two orders of magnitude of it.
 *
 * ## Reading it
 *
 * The counters are written by the validator and its collaborators and read by tests; nothing in
 * production branches on them. A fresh instance is created per `validate()` call when the caller
 * does not supply one, so the default path costs one allocation and no bookkeeping.
 */
class ValidationTrace {
    /**
     * Characters the bracket pre-scan examined — exactly one linear pass over the body, or 0 when
     * the body was refused before the parser was reached (the length cap, a source-level refusal).
     */
    var bracketScanChars: Int = 0
        internal set

    /**
     * Parses attempted: 0 or 1, never more. templates.md §4.2 makes "the body is parsed exactly
     * once, and the scan reads that parse" normative — a second parse is a second opinion the
     * scanner and the parser could disagree about.
     */
    var parseAttempts: Int = 0
        internal set

    /** `imports` entries judged across the whole closure walk (§6.4). */
    var importVisits: Int = 0
        internal set

    /** Libraries expanded — the distinct `(library, depth)` pairs the memo admitted. */
    var importExpansions: Int = 0
        internal set

    /**
     * Every counted step of this validation, as one number.
     *
     * Summing a character scan and a graph walk is only meaningful as a BOUND — "this validation
     * did no more than N units of work, where N follows from the input's size" — which is exactly
     * the claim §12.3 makes. Read the individual counters when the question is which stage did
     * the work.
     */
    val steps: Int get() = bracketScanChars + parseAttempts + importVisits + importExpansions

    override fun toString(): String =
        "ValidationTrace(steps=$steps, bracketScanChars=$bracketScanChars, parseAttempts=$parseAttempts, " +
            "importVisits=$importVisits, importExpansions=$importExpansions)"
}
