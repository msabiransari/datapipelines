package co.datapipelines.logcapture

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * The shared test log capture following #362 (module-structure §5.23): append and snapshot-copy own the
 * same list monitor. Readers receive detached lists in capture order, retaining the original events and
 * metadata. Never read or clear the inherited backing list from a consumer.
 *
 * Test-only: consumers declare this module with `testImplementation` (#756).
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

    /** A detached snapshot; later appends and caller list mutations cannot change each other. */
    fun events(): List<ILoggingEvent> = synchronized(list) { List(list.size) { list[it] } }

    /** Formats only detached events, so formatting never walks the live backing list. */
    fun messages(): List<String> = events().map { it.formattedMessage }
}

/**
 * Captures [logger] during [block] and returns the captured events, detaching and stopping even when the
 * block throws. The appender is (re)started before it is attached, so a [SnapshotListAppender] stopped
 * by an earlier capture records again.
 */
fun capturingLogEvents(
    logger: Logger,
    appender: SnapshotListAppender = SnapshotListAppender(),
    block: () -> Unit,
): List<ILoggingEvent> {
    appender.start()
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
    return appender.events()
}

/**
 * Captures the ROOT logger's output for the duration of [block] and returns the formatted messages.
 * Detaches in a `finally` — a throwing block must not leave the appender attached to the JVM's root
 * logger for the next test. Pass a shared [SnapshotListAppender] when a `waitUntil`-style poll must
 * read the SAME capture the block writes; it is (re)started before it is attached.
 */
fun capturingLogs(
    appender: SnapshotListAppender = SnapshotListAppender(),
    block: () -> Unit,
): List<String> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
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
