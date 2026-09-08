package co.datapipelines.typesystem

/**
 * The cause chain of a translated failure, as ONE log-line fragment.
 *
 * The boundaries that turn a driver failure into a catalogued error keep driver text off the
 * WIRE on purpose (a static message and a code go to the caller). That rule was being applied
 * to the LOG as well, by omission: a lake datasource whose connection init failed logged
 * `Datasource 'sample-lake' could not be reached for schema introspection.` and nothing else,
 * and the operator had to reproduce the connect by hand to learn it was an S3 `403` on a
 * listing (2026-09-08). The wire stays static; the log gets the chain.
 *
 * Messages only — never a stack: the WARN demotion for a caller's own downstream being down
 * exists so that a customer database outage is not a 5xx stack in our logs. Bounded in depth
 * and width, newlines collapsed, so a driver that dumps its whole configuration into a
 * message cannot flood a line.
 */
object CauseChain {
    private const val MAX_DEPTH = 5
    private const val MAX_MESSAGE = 300

    /** `" cause=SQLException: …; HttpException: …"`, or `""` when there is no cause. */
    fun summarize(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current = error.cause
        val seen = mutableSetOf<Throwable>()
        while (current != null && parts.size < MAX_DEPTH && seen.add(current)) {
            val message =
                (current.message ?: "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_MESSAGE)
            parts += "${current::class.simpleName ?: current::class.java.name}: $message"
            current = current.cause
        }
        return if (parts.isEmpty()) "" else " cause=" + parts.joinToString("; ")
    }
}
