package co.datapipelines.datasources

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * The module's log capture (#362): Logback's [ListAppender.list] is a plain `ArrayList` that logger
 * threads append to, so a test reading it while the code under test still logs can trip a
 * `ConcurrentModificationException` mid-iteration (the HikariConnectionPoolShutdownTest red). This
 * appender appends under the list's own monitor and every read takes the monitor and walks a COPY,
 * so a reader never iterates a list a writer is mutating and never sees a torn view. The events are
 * Logback's own; nothing here filters or formats beyond [messages]' `formattedMessage`.
 */
class SnapshotListAppender : ListAppender<ILoggingEvent>() {
    init {
        // An appender Logback has not started swallows every event (AppenderBase's started guard):
        // every use of this class captures, so it starts itself.
        start()
    }

    override fun append(eventObject: ILoggingEvent) {
        synchronized(list) { super.append(eventObject) }
    }

    /** An immutable snapshot of the events captured so far. */
    fun events(): List<ILoggingEvent> = synchronized(list) { List(list.size) { list[it] } }

    /** An immutable snapshot of the events' formatted messages. */
    fun messages(): List<String> = synchronized(list) { List(list.size) { list[it].formattedMessage } }
}

/**
 * Captures the ROOT logger's output for the duration of [block] and returns the formatted messages.
 * Detaches in a `finally` — a throwing block must not leave the appender attached to the JVM's root
 * logger for the next test. Pass a shared [SnapshotListAppender] when a `waitUntil`-style poll must
 * read the SAME capture the block writes (the HikariConnectionPoolShutdownTest shape).
 */
fun capturingLogs(
    appender: SnapshotListAppender = SnapshotListAppender().apply { start() },
    block: () -> Unit,
): List<String> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
    appender.start()
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
        appender.stop()
    }
    return appender.messages()
}
