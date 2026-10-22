package co.datapipelines.scripting

/**
 * Where a script's `console.*` lines go ([EvaluationLimits.console]). Not a bound: a caller that
 * passes none discards the lines.
 *
 * Called on the evaluating thread after the evaluation returned or failed, once per line, in the
 * order the body wrote them — at most [MAX_LINES] lines of at most [MAX_LINE_CHARS] characters,
 * then one final `… N more lines dropped` line at [ConsoleLevel.LOG] when more were written.
 */
fun interface ConsoleSink {
    /** Receives one line. */
    fun accept(line: ConsoleLine)

    companion object {
        /** The most lines one evaluation hands to a sink (the drop notice excluded). */
        const val MAX_LINES = 200

        /** The longest line a sink receives; a longer one is cut to this length. */
        const val MAX_LINE_CHARS = 2000
    }
}

/** One console line: the level the body called and the text it wrote. */
data class ConsoleLine(
    val level: ConsoleLevel,
    val text: String,
)

/** The five `console` methods the JavaScript engine provides; anything else is not a function. */
enum class ConsoleLevel { LOG, INFO, DEBUG, WARN, ERROR }
