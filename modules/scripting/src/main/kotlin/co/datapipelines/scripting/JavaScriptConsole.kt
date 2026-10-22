package co.datapipelines.scripting

/**
 * Turns the guest's captured `out` stream into [ConsoleLine]s for a [ConsoleSink] (#8).
 *
 * The prelude's `console` writes every level to ONE stream as `LEVEL\u0001text\n`, so the order of
 * lines across levels is the order the body wrote them. A segment that does not start with a known
 * level tag continues the line before it (a `console.log('a\nb')` is one line with a newline in its
 * text); one before any tagged line is [ConsoleLevel.LOG].
 */
internal object JavaScriptConsole {
    private const val TAG = '\u0001'
    private val levels = ConsoleLevel.entries.associateBy { it.name }

    /** Delivers at most [ConsoleSink.MAX_LINES] bounded lines, then the drop notice. */
    fun deliver(
        captured: String,
        sink: ConsoleSink,
    ) {
        val lines = parse(captured)
        lines.take(ConsoleSink.MAX_LINES).forEach { sink.accept(it.copy(text = it.text.take(ConsoleSink.MAX_LINE_CHARS))) }
        val dropped = lines.size - ConsoleSink.MAX_LINES
        if (dropped > 0) sink.accept(ConsoleLine(ConsoleLevel.LOG, "… $dropped more lines dropped"))
    }

    fun parse(captured: String): List<ConsoleLine> {
        if (captured.isEmpty()) return emptyList()
        val body = if (captured.endsWith('\n')) captured.dropLast(1) else captured
        val out = ArrayList<ConsoleLine>()
        for (segment in body.split('\n')) {
            val tag = segment.indexOf(TAG)
            val level = if (tag > 0) levels[segment.substring(0, tag)] else null
            when {
                level != null -> out.add(ConsoleLine(level, segment.substring(tag + 1)))
                out.isEmpty() -> out.add(ConsoleLine(ConsoleLevel.LOG, segment))
                else -> out[out.lastIndex] = out.last().let { it.copy(text = it.text + "\n" + segment) }
            }
        }
        return out
    }
}
